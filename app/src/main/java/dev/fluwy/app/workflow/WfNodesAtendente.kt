package dev.fluwy.app.workflow

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.fluwy.app.FluwyApp
import dev.fluwy.app.data.ChatItem
import dev.fluwy.app.data.Session
import dev.fluwy.app.data.SessionStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

// Atendente de WhatsApp (IA).

    /**
 * Atendente de WhatsApp: mensagem do gatilho + histórico da conversa + base de conhecimento → resposta da IA
 * (ou de uma conversa do app acordada pelo fluxo) → envio no próprio chat. No teste, tudo é validado sem enviar.
 */
internal suspend fun nodeAtendente(c: WfCall): NodeResult {
    val w = c.w; val run = c.run; val r = c::r; val rt = c::rt
    val app = FluwyApp.instance
    val chat = rt("{{trigger.payload.chat}}", 0).trim()
    val text = rt("{{trigger.payload.text}}", 0).trim()
    val who = rt("{{trigger.payload.sender_name}}", 0).ifBlank { rt("{{trigger.payload.chat_name}}", 0) }.trim()
    val msgId = rt("{{trigger.payload.id}}", 0).trim()
    if (chat.isBlank()) return NodeResult(error = "O atendente precisa do gatilho \"Mensagem no WhatsApp\" antes dele (não achei {{trigger.payload.chat}}).")
    if (text.isBlank()) return NodeResult(buildJsonObject { put("skipped", "mensagem sem texto"); put("chat", chat) }.toString())
    val hub = dev.fluwy.app.browser.WebApiHub
    // Histórico da conversa (o que já foi dito) para responder com contexto.
    val n = (r("history").toIntOrNull() ?: 10).coerceIn(0, 30)
    val history = if (n == 0) "" else runCatching {
        val api = hub.find(app, "whatsapp_messages") ?: return@runCatching ""
        val out = hub.call(app, api, buildJsonObject { put("to", chat); put("count", n + 1) })
        val arr = (Json.parseToJsonElement(out) as? JsonObject)?.get("results") as? kotlinx.serialization.json.JsonArray ?: return@runCatching ""
        arr.mapNotNull { it as? JsonObject }.joinToString("\n") { m ->
            val me = m["from_me"]?.toString() == "true"
            (if (me) "Atendente: " else "Cliente: ") + (m["text"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.orEmpty()).take(500)
        }
    }.getOrDefault("")
    val handoffWords = r("handoff").split('|').map { it.trim() }.filter { it.isNotEmpty() }
    val handoff = handoffWords.any { text.contains(it, ignoreCase = true) }
    val sessionTitle = r("session").trim()
    var reply: String
    if (handoff) {
        reply = "Certo! Vou chamar uma pessoa da equipe para continuar com você. Só um instante."
        runCatching { dev.fluwy.app.notify.NotificationCenter.post("Atendimento: pedido de humano", "${who.ifBlank { chat }}: ${text.take(160)}",
            dev.fluwy.app.notify.NotificationCenter.Priority.HIGH, dev.fluwy.app.notify.NotificationCenter.Category.TRIGGER, detail = "Conversa $chat no WhatsApp pediu uma pessoa.") }
    } else if (sessionTitle.isNotBlank()) {
        // Acorda a conversa do app, repassa a mensagem e espera a resposta dela (até 5 min).
        val st = app.store.state.value
        val sess = st.sessions.firstOrNull { it.title.equals(sessionTitle, true) } ?: st.sessions.firstOrNull { it.title.contains(sessionTitle, true) }
            ?: return NodeResult(error = "Conversa \"$sessionTitle\" não encontrada no app.")
        if (run.trigger == "test") reply = dev.fluwy.app.prompts.Prompts.render("workflow.16", mapOf<String, Any?>("sess_title" to (sess.title), "lb" to "{{"))
        else {
            val before = app.store.session(sess.id)?.items?.size ?: 0
            app.agent.sendOrQueue(sess.id, dev.fluwy.app.prompts.Prompts.render("workflow.15", mapOf<String, Any?>("chat" to (who.ifBlank { chat }), "text" to (text), "lb" to "{{")))
            val deadline = System.currentTimeMillis() + 5 * 60_000L
            kotlinx.coroutines.delay(1500)
            while (System.currentTimeMillis() < deadline && app.store.session(sess.id)?.status == dev.fluwy.app.data.SessionStatus.RUNNING) kotlinx.coroutines.delay(2000)
            reply = app.store.session(sess.id)?.items?.drop(before)?.filterIsInstance<dev.fluwy.app.data.ChatItem.Assistant>()?.lastOrNull()?.text?.trim().orEmpty()
            if (reply.isBlank()) return NodeResult(error = dev.fluwy.app.prompts.Prompts.render("workflow.14", mapOf<String, Any?>("sess_title" to (sess.title), "lb" to "{{")))
        }
    } else {
        val goal = when (r("objective")) {
            "vendas" -> dev.fluwy.app.prompts.Prompts.render("workflow.13", mapOf<String, Any?>("lb" to "{{"))
            "recuperar_pagamento" -> dev.fluwy.app.prompts.Prompts.render("workflow.12", mapOf<String, Any?>("lb" to "{{"))
            "recuperar_cliente" -> dev.fluwy.app.prompts.Prompts.render("workflow.11", mapOf<String, Any?>("lb" to "{{"))
            "suporte" -> dev.fluwy.app.prompts.Prompts.render("workflow.10", mapOf<String, Any?>("lb" to "{{"))
            else -> dev.fluwy.app.prompts.Prompts.render("workflow.9", mapOf<String, Any?>("lb" to "{{"))
        }
        val prompt = buildString {
            appendLine(r("persona").ifBlank { dev.fluwy.app.prompts.Prompts.render("workflow.8", mapOf<String, Any?>("lb" to "{{")) })
            appendLine(goal)
            appendLine(dev.fluwy.app.prompts.Prompts.render("workflow.7", mapOf<String, Any?>("lb" to "{{")))
            val kb = r("knowledge").trim()
            if (kb.isNotBlank()) { appendLine("\n### Base de conhecimento\n" + kb.take(30_000)) }
            if (history.isNotBlank()) appendLine("\n### Conversa até agora\n$history")
            appendLine("\n### Nova mensagem de ${who.ifBlank { "cliente" }}\n$text")
            appendLine(dev.fluwy.app.prompts.Prompts.render("workflow.6", mapOf<String, Any?>("lb" to "{{")))
        }
        reply = ""
        for (attempt in 0..1) {
            reply = runCatching { app.llm.quick(prompt, maxTokens = 600, conversationId = "atendente-${w.id}-${chat.hashCode()}") }.getOrDefault("").trim()
            if (reply.isNotBlank()) break
            kotlinx.coroutines.delay(1500)
        }
        if (reply.isBlank()) return NodeResult(error = "A IA não gerou resposta.")
    }
    reply = reply.removePrefix("Atendente:").trim().take(3500)
    var sent = false
    if (r("reply") != "draft") {
        val api = hub.find(app, "whatsapp_send_text") ?: return NodeResult(error = dev.fluwy.app.prompts.Prompts.render("workflow.5", mapOf<String, Any?>("lb" to "{{")))
        // Marca invisível no fim: o webhook ignora as próprias respostas (evita o bot responder a si mesmo).
        val args = buildJsonObject {
            put("to", chat); put("text", reply + dev.fluwy.app.browser.WhatsAppHub.BOT_MARK); put("confirmed", "true")
            if (msgId.isNotBlank() && !msgId.startsWith("teste_")) put("reply_to", msgId)
            if (run.trigger == "test") put("__dry_run", "true")
        }
        val out = hub.call(app, api, args)
        if (!out.contains("\"ok\":true")) return NodeResult(error = "Falha ao enviar no WhatsApp: " + (Regex("\"error\":\"([^\"]*)").find(out)?.groupValues?.get(1) ?: out.take(200)))
        sent = run.trigger != "test"
    }
    return NodeResult(buildJsonObject { put("reply", reply); put("sent", sent); put("chat", chat); put("handoff", handoff); put("to_name", who) }.toString())
}