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

// Gatilhos: produzem o primeiro item da execução (com o payload do WhatsApp/webhook/erro, ou o exemplo do nó em teste).

internal suspend fun nodeTrigger(c: WfCall): NodeResult {
    val app = FluwyApp.instance; val w = c.w; val run = c.run; val node = c.node; val prev = c.prev
    val r = c::r; val rt = c::rt; val rh = c::rh; val setStatus = c::setStatus
    return NodeResult(buildJsonObject {
                put("trigger", run.trigger); put("time", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date()))
                put("workflow", w.name)
                if (node.type == "trigger_webhook" || node.type == "trigger_whatsapp" || node.type == "trigger_error") {
                    // Teste/execução manual: usa o exemplo do nó (ou o padrão do tipo); no WhatsApp, com a conversa configurada no gatilho.
                    // Antes vinha "" e o nó seguinte falhava com "não achei {{trigger.payload.chat}}".
                    val raw = WorkflowEngine.payloads[run.id] ?: sampleFor(node)
                    val el = runCatching { Json.parseToJsonElement(raw) }.getOrNull()
                    if (el != null && raw.isNotBlank()) put("payload", el) else put("payload", raw)
                }
            }.toString())
}

internal fun sampleFor(node: WfNode): String {
    if (node.type == "trigger_error") return "{\"workflow\": {\"id\": \"exemplo\", \"name\": \"Fluxo de exemplo\"}, \"execution\": {\"id\": \"teste\", \"error\": \"Erro de teste\", \"lastNode\": \"Nó de exemplo\", \"mode\": \"test\"}}"
    val base = node.config["sample"]?.takeIf { it.isNotBlank() } ?: WfCatalog.of(node.type).fields.firstOrNull { it.key == "sample" }?.default.orEmpty()
    if (node.type != "trigger_whatsapp") return base
    val o = runCatching { Json.parseToJsonElement(base.ifBlank { "{}" }) as JsonObject }.getOrDefault(JsonObject(emptyMap()))
    val chat = node.config["chat"].orEmpty().trim()
    val m = o.toMutableMap()
    if (chat.isNotEmpty()) {
        val d = chat.filter { it.isDigit() }
        m["chat"] = kotlinx.serialization.json.JsonPrimitive(if (chat.contains('@')) chat else if (d.length >= 10) d + "@c.us" else chat)
        m["chat_name"] = kotlinx.serialization.json.JsonPrimitive(if (chat.contains('@') || d.length >= 10) (m["chat_name"]?.toString()?.trim('"') ?: "Contato") else chat)
    }
    if (node.config["scope"] == "groups" && m["chat"]?.toString()?.contains("@g.us") != true) { m["chat"] = kotlinx.serialization.json.JsonPrimitive("120363000000000000@g.us"); m["is_group"] = kotlinx.serialization.json.JsonPrimitive(true) }
    val kw = node.config["keyword"].orEmpty().split('|').map { it.trim() }.firstOrNull { it.isNotEmpty() }
    if (kw != null && m["text"]?.toString()?.contains(kw, true) != true) m["text"] = kotlinx.serialization.json.JsonPrimitive("$kw ${m["text"]?.toString()?.trim('"').orEmpty()}".trim())
    if (node.config["from_me"] == "true") m["from_me"] = kotlinx.serialization.json.JsonPrimitive(true)
    if (m["id"] == null) m["id"] = kotlinx.serialization.json.JsonPrimitive("teste_" + System.currentTimeMillis())
    if (m["type"] == null) m["type"] = kotlinx.serialization.json.JsonPrimitive("chat")
    return JsonObject(m).toString()
}