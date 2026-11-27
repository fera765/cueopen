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

/**
 * Motor dos fluxos: parte do gatilho e segue as ligações; cada nó roda uma vez por execução, recebe a saída do anterior
 * ({{prev}}) e de qualquer nó já executado ({{id}} / {{id.campo}}). Erro num nó para a execução e fica registrado.
 */
object WorkflowEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val approvals = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    /** Pedido de revisão aberto (nó "Revisar e aprovar"): o que a página mostra. */
    data class Review(val runId: String, val nodeId: String, val workflowId: String, val workflowName: String, val title: String,
                      val view: String, val draft: String, val createdAt: Long = System.currentTimeMillis())
    data class ReviewDecision(val approved: Boolean, val draft: String)
    private val reviewWaits = ConcurrentHashMap<String, CompletableDeferred<ReviewDecision>>()
    private val _reviews = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Review>>(emptyMap())
    val reviews: kotlinx.coroutines.flow.StateFlow<Map<String, Review>> = _reviews
    fun review(runId: String, nodeId: String): Review? = _reviews.value["$runId|$nodeId"]
    fun pendingReviewOf(workflowId: String): Review? = _reviews.value.values.firstOrNull { it.workflowId == workflowId }
    /** Resposta do usuário na página de revisão (texto final, possivelmente editado). */
    fun decideReview(runId: String, nodeId: String, approved: Boolean, draft: String) {
        reviewWaits.remove("$runId|$nodeId")?.complete(ReviewDecision(approved, draft))
    }
    private const val MAX_STEPS = 200
    /** Campos onde um {{…}} vazio é erro (IA/agente/aviso/e-mail não podem receber instrução sem os dados). */
    private val EMPTY_CHECK = mapOf("ai" to listOf("prompt"), "agent" to listOf("prompt"), "notify" to listOf("text", "title"), "connector" to listOf("campos"))

    private val pauses = ConcurrentHashMap.newKeySet<String>()

    fun isActive(status: String) = status == "running" || status == "waiting" || status == "paused" || status == "queued"

    /** Quantos fluxos rodam ao mesmo tempo; os demais esperam na fila (com dezenas de fluxos agendados o celular não engasga). */
    const val MAX_CONCURRENT = 3
    /** Teto de uma execução inteira: nada fica "rodando" para sempre. */
    const val MAX_RUN_MS = 60 * 60_000L
    private val slots = kotlinx.coroutines.sync.Semaphore(MAX_CONCURRENT)
    fun isRunning(workflowId: String) = WorkflowStore.runs.value.any { it.workflowId == workflowId && isActive(it.status) }
    fun activeRun(workflowId: String): WfRun? = WorkflowStore.runs.value.filter { it.workflowId == workflowId && isActive(it.status) }.maxByOrNull { it.startedAt }

    /** Pausa entre um nó e o próximo (o nó que está rodando termina). */
    fun pause(runId: String) {
        pauses.add(runId)
        WorkflowStore.runs.value.firstOrNull { it.id == runId }?.takeIf { it.status == "running" }?.let { WorkflowStore.putRun(it.copy(status = "paused")) }
    }
    fun resume(runId: String) {
        pauses.remove(runId)
        WorkflowStore.runs.value.firstOrNull { it.id == runId }?.takeIf { it.status == "paused" }?.let { WorkflowStore.putRun(it.copy(status = "running")) }
    }
    fun isPaused(runId: String) = runId in pauses

    /** Dispara uma execução (manual ou pelo agendamento). Devolve o id da execução, ou um erro de validação. */
    /** Payload recebido por um gatilho webhook, por execução (só em memória). */
    private val payloads = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun start(workflowId: String, trigger: String = "manual", triggerNodeId: String? = null, payload: String? = null): Result<String> {
        val w = WorkflowStore.get(workflowId) ?: return Result.failure(IllegalStateException("Fluxo não encontrado."))
        val blocking = WfLogic.problems(w).filterNot { it.contains("não está ligado a nada") }
        if (blocking.isNotEmpty()) return Result.failure(IllegalStateException(blocking.joinToString("\n")))
        if (isRunning(workflowId)) return Result.failure(IllegalStateException("Este fluxo já está em execução."))
        val busy = WorkflowStore.runs.value.count { it.status == "running" || it.status == "paused" }
        val run = WfRun(workflowId = w.id, workflowName = w.name, trigger = trigger, status = if (busy >= MAX_CONCURRENT) "queued" else "running")
        WorkflowStore.putRun(run)
        payload?.let { payloads[run.id] = it }
        runCatching { dev.fluwy.app.sandbox.SandboxService.start(FluwyApp.instance, "Fluxo em execução: ${w.name}") }
        jobs[run.id] = scope.launch {
            slots.acquire()
            try {
                val cur = WorkflowStore.runs.value.firstOrNull { it.id == run.id } ?: run
                if (cur.status == "cancelled") return@launch
                val started = cur.copy(status = "running", startedAt = System.currentTimeMillis())
                if (cur.status == "queued") WorkflowStore.putRun(started)
                val finished = kotlinx.coroutines.withTimeoutOrNull(MAX_RUN_MS) { execute(w, started, triggerNodeId); true }
                if (finished == null) WorkflowStore.runs.value.firstOrNull { it.id == run.id }?.takeIf { isActive(it.status) }?.let {
                    WorkflowStore.putRun(it.copy(status = "error", finishedAt = System.currentTimeMillis(), error = "A execução passou de ${MAX_RUN_MS / 60_000} min e foi encerrada."))
                }
            } finally { slots.release(); payloads.remove(run.id) }
        }
        return Result.success(run.id)
    }

    fun cancel(runId: String) {
        pauses.remove(runId)
        jobs.remove(runId)?.cancel()
        approvals.keys.filter { it.startsWith("$runId|") }.forEach { approvals.remove(it)?.complete(false) }
        reviewWaits.keys.filter { it.startsWith("$runId|") }.forEach { reviewWaits.remove(it)?.complete(ReviewDecision(false, "")) }
        WorkflowStore.runs.value.firstOrNull { it.id == runId }?.takeIf { isActive(it.status) }?.let {
            WorkflowStore.putRun(it.copy(status = "cancelled", finishedAt = System.currentTimeMillis(), error = "Parada pelo usuário."))
        }
    }

    /** Espera uma execução terminar (ou ficar aguardando aprovação). Null se passar do tempo. */
    suspend fun awaitRun(runId: String, timeoutMs: Long): WfRun? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val r = WorkflowStore.runs.value.firstOrNull { it.id == runId }
            if (r != null && r.status != "running" && r.status != "queued") return r
            delay(400)
        }
        return null
    }

    /** Testa um nó sozinho, usando as saídas da última execução como dados de entrada ({{prev}}, {{id}}). */
    suspend fun testNode(w: Workflow, nodeId: String): NodeResult {
        val node = w.nodes.firstOrNull { it.id == nodeId } ?: return NodeResult(error = "Nó não encontrado.")
        if (node.type == "approval") return NodeResult(error = "A aprovação não é testada sozinha: execute o fluxo inteiro.")
        val outputs = linkedMapOf<String, String>()
        WorkflowStore.runsOf(w.id).reversed().forEach { run -> run.steps.filter { it.status == "ok" }.forEach { outputs[it.nodeId] = it.output } }
        val prev = w.edges.filter { it.to == nodeId }.firstNotNullOfOrNull { outputs[it.from] }.orEmpty()
        val labels = w.nodes.associate { it.id to WfLogic.label(it) }
        val vars = mapOf("trigger.time" to java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date()),
            "trigger.type" to "test", "workflow.name" to w.name)
        fun r(key: String) = WfLogic.render(node.config[key] ?: WfCatalog.of(node.type).fields.firstOrNull { it.key == key }?.default.orEmpty(), outputs, labels, prev, vars)
        fun rt(t: String, mode: Int) = WfLogic.render(t, outputs, labels, prev, vars, humanize = when (mode) { 1 -> { v: String -> WfFormat.oneLine(v) }; 2 -> { v: String -> WfFormat.readable(v) }; else -> null })
        fun rh(key: String, short: Boolean) = WfLogic.render(node.config[key] ?: WfCatalog.of(node.type).fields.firstOrNull { it.key == key }?.default.orEmpty(), outputs, labels, prev, vars,
            humanize = { v: String -> if (short) WfFormat.oneLine(v) else WfFormat.readable(v) })
        if (node.type == "delay") return NodeResult(prev)
        val run = WfRun(workflowId = w.id, workflowName = w.name, trigger = "test")
        return try { runNode(w, run, node, prev, ::r, ::rh, ::rt) { } } catch (c: kotlinx.coroutines.CancellationException) { throw c } catch (e: Throwable) {
            NodeResult(error = (e.message ?: e.javaClass.simpleName).take(1_500))
        }
    }

    /** Resposta do usuário a um nó de Aprovação. */
    fun approve(runId: String, nodeId: String, ok: Boolean) { approvals.remove("$runId|$nodeId")?.complete(ok) }
    fun pendingApproval(runId: String, nodeId: String) = approvals.containsKey("$runId|$nodeId")

    private suspend fun execute(w: Workflow, run0: WfRun, triggerNodeId: String?) {
        var run = run0
        fun save(r: WfRun) { run = if (r.status == "running" && r.id in pauses) r.copy(status = "paused") else r; WorkflowStore.putRun(run) }
        val outputs = linkedMapOf<String, String>()
        val labels = w.nodes.associate { it.id to WfLogic.label(it) }
        val start = w.nodes.firstOrNull { it.id == triggerNodeId }
            ?: w.nodes.firstOrNull { it.type == "trigger_manual" } ?: w.nodes.first { WfCatalog.of(it.type).trigger }
        val vars = mapOf("trigger.time" to java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date()),
            "trigger.type" to run.trigger, "workflow.name" to w.name)
        val queue = ArrayDeque<Pair<WfNode, String>>().apply { add(start to "") }
        val done = mutableSetOf<String>()
        try {
            var steps = 0
            while (queue.isNotEmpty()) {
                val (node, prev) = queue.removeFirst()
                if (!done.add(node.id)) continue
                if (run.id in pauses) { save(run.copy(status = "paused")); while (run.id in pauses) delay(400); save(run.copy(status = "running")) }
                if (++steps > MAX_STEPS) throw IllegalStateException("Mais de $MAX_STEPS passos: verifique se o fluxo não tem um ciclo.")
                val t0 = System.currentTimeMillis()
                save(run.copy(steps = run.steps + WfStep(node.id, labels.getValue(node.id), node.type, "running", t0)))
                fun r(key: String) = WfLogic.render(node.config[key] ?: WfCatalog.of(node.type).fields.firstOrNull { it.key == key }?.default.orEmpty(), outputs, labels, prev, vars)
                fun rt(t: String, mode: Int) = WfLogic.render(t, outputs, labels, prev, vars, humanize = when (mode) { 1 -> { v: String -> WfFormat.oneLine(v) }; 2 -> { v: String -> WfFormat.readable(v) }; else -> null })
                fun rh(key: String, short: Boolean) = WfLogic.render(node.config[key] ?: WfCatalog.of(node.type).fields.firstOrNull { it.key == key }?.default.orEmpty(), outputs, labels, prev, vars,
                    humanize = { v: String -> if (short) WfFormat.oneLine(v) else WfFormat.readable(v) })
                val emptyRef = EMPTY_CHECK[node.type]?.flatMap { k -> WfLogic.emptyRefs(node.config[k].orEmpty(), outputs, labels, prev, vars) }?.firstOrNull()
                val res = if (emptyRef != null) NodeResult(error = "$emptyRef veio vazio: o nó anterior não produziu esse dado. Abra a saída dele no teste e use um caminho que exista (veja \"caminhos\").")
                else try {
                    runNode(w, run, node, prev, ::r, ::rh, ::rt) { status -> save(run.copy(status = status)) }
                } catch (c: kotlinx.coroutines.CancellationException) { throw c } catch (e: Throwable) {
                    NodeResult(error = (e.message ?: e.javaClass.simpleName).take(1_500))
                }
                val step = WfStep(node.id, labels.getValue(node.id), node.type, if (res.error == null) "ok" else "error", t0, System.currentTimeMillis(),
                    res.output.take(20_000), res.error.orEmpty(), res.sessionId)
                save(run.copy(status = "running", steps = run.steps.dropLast(1) + step))
                if (res.error != null) {
                    save(run.copy(status = "error", finishedAt = System.currentTimeMillis(), error = "${labels[node.id]}: ${res.error}"))
                    notifyResult(w, run); return
                }
                outputs[node.id] = res.output
                WfLogic.nextNodes(w, node.id, res.port).forEach { queue.add(it to res.output) }
            }
            save(run.copy(status = "ok", finishedAt = System.currentTimeMillis()))
            // Versão testada com sucesso (vale para publicar enquanto ninguém alterar nós/ligações).
            WorkflowStore.get(w.id)?.let { cur -> val sig = WfLogic.signature(w); if (WfLogic.signature(cur) == sig && cur.testedSig != sig) WorkflowStore.upsert(cur.copy(testedSig = sig)) }
        } catch (c: kotlinx.coroutines.CancellationException) {
            pauses.remove(run.id)
            if (isActive(run.status)) save(run.copy(status = "cancelled", finishedAt = System.currentTimeMillis(), error = "Parada pelo usuário."))
        } catch (e: Throwable) {
            save(run.copy(status = "error", finishedAt = System.currentTimeMillis(), error = (e.message ?: e.javaClass.simpleName).take(1_000)))
            notifyResult(w, run)
        } finally {
            pauses.remove(run.id)
            jobs.remove(run.id)
        }
    }

    private fun notifyResult(w: Workflow, run: WfRun) {
        if (run.trigger == "manual" || run.trigger == "test") return
        runCatching {
            dev.fluwy.app.notify.NotificationCenter.post("Fluxo \"${w.name}\" falhou", run.error.take(200),
                dev.fluwy.app.notify.NotificationCenter.Priority.HIGH, dev.fluwy.app.notify.NotificationCenter.Category.FAILURE,
                agent = "Fluxo · ${w.name}", detail = run.error.take(4_000), route = "workflow/${w.id}", routeLabel = "Abrir fluxo")
        }
    }

    /**
     * Publicar = testar ponta a ponta antes. Versão já testada com sucesso publica direto; senão roda o teste completo
     * (leituras reais, envios simulados) e só ativa se tudo passar. Devolve (publicou, mensagem).
     */
    suspend fun publishTested(ctx: Context, wfId: String): Pair<Boolean, String> {
        val w = WorkflowStore.get(wfId) ?: return false to "Fluxo não encontrado."
        WfLogic.problems(w).filterNot { it.contains("não está ligado a nada") }.takeIf { it.isNotEmpty() }?.let { return false to "Corrija antes de publicar:\n• " + it.joinToString("\n• ") }
        if (w.testedSig != WfLogic.signature(w)) {
            val id = start(wfId, trigger = "test").getOrElse { return false to (it.message ?: "Não foi possível testar.") }
            val r = awaitRun(id, 6 * 60_000L) ?: return false to "O teste passou de 6 min; não publiquei."
            if (r.status != "ok") return false to "O teste falhou; não publiquei.\n" + (r.steps.firstOrNull { it.status == "error" }?.let { "${it.label}: ${it.error.take(400)}" } ?: r.error.take(400))
        }
        val cur = WorkflowStore.get(wfId) ?: return false to "Fluxo não encontrado."
        if (cur.testedSig != WfLogic.signature(cur)) return false to "O fluxo mudou durante o teste; teste de novo."
        val p = WorkflowStore.upsert(cur.copy(enabled = true)); WorkflowScheduler.sync(ctx, p)
        return true to "Testado de ponta a ponta e publicado."
    }

    fun publish(ctx: Context, wfId: String, onDone: (Boolean, String) -> Unit) {
        scope.launch { val r = publishTested(ctx, wfId); withContext(Dispatchers.Main) { onDone(r.first, r.second) } }
    }

    /** Envio externo no teste: valida o conteúdo e devolve o que SERIA enviado, sem acionar nada. */
    private fun simulated(node: WfNode, target: String, payload: String): NodeResult {
        if (WfQuality.isEmailSend(node)) runCatching { Json.parseToJsonElement(payload) as JsonObject }.getOrNull()
            ?.let { WfQuality.emailProblem(it) }?.let { return NodeResult(error = "$it (teste: nada foi enviado)") }
        if (payload.isNotBlank()) Regex("\\{\\{[^}]*\\}\\}").find(payload)?.let { return NodeResult(error = "Campo não preenchido no envio: ${it.value} (teste: nada foi enviado)") }
        return NodeResult(buildJsonObject {
            put("simulated", true); put("target", target)
            put("would_send", runCatching { Json.parseToJsonElement(payload) }.getOrElse { kotlinx.serialization.json.JsonPrimitive(payload.take(8_000)) })
            put("note", "Teste: envio externo simulado — nada saiu do celular.")
        }.toString())
    }

    /**
     * Atendente de WhatsApp: mensagem do gatilho + histórico da conversa + base de conhecimento → resposta da IA
     * (ou de uma conversa do app acordada pelo fluxo) → envio no próprio chat. No teste, tudo é validado sem enviar.
     */
    private suspend fun atendente(w: Workflow, run: WfRun, node: WfNode, r: (String) -> String, rt: (String, Int) -> String): NodeResult {
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

    private fun sampleFor(node: WfNode): String {
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

    data class NodeResult(val output: String = "", val port: String? = null, val error: String? = null, val sessionId: String? = null)

    private suspend fun runNode(w: Workflow, run: WfRun, node: WfNode, prev: String, r: (String) -> String, rh: (String, Boolean) -> String, rt: (String, Int) -> String, setStatus: (String) -> Unit): NodeResult {
        val app = FluwyApp.instance
        return when (node.type) {
            "trigger_manual", "trigger_schedule", "trigger_webhook", "trigger_whatsapp" -> NodeResult(buildJsonObject {
                put("trigger", run.trigger); put("time", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date()))
                put("workflow", w.name)
                if (node.type == "trigger_webhook" || node.type == "trigger_whatsapp") {
                    // Teste/execução manual: usa o exemplo do nó (ou o padrão do tipo); no WhatsApp, com a conversa configurada no gatilho.
                    // Antes vinha "" e o nó seguinte falhava com "não achei {{trigger.payload.chat}}".
                    val raw = payloads[run.id] ?: sampleFor(node)
                    val el = runCatching { Json.parseToJsonElement(raw) }.getOrNull()
                    if (el != null && raw.isNotBlank()) put("payload", el) else put("payload", raw)
                }
            }.toString())

            "agent" -> {
                val prompt = r("prompt").trim()
                if (prompt.isBlank()) return NodeResult(error = "Instrução vazia.")
                val agentName = r("agent").trim()
                val agent = app.store.state.value.agents.firstOrNull { agentName.isNotBlank() && it.name.equals(agentName, true) }
                if (agentName.isNotBlank() && agent == null) return NodeResult(error = "Agente \"$agentName\" não existe.")
                val session = Session(title = "[Workflow] ${w.name} · ${WfLogic.label(node)}", agentId = agent?.id)
                app.store.upsertSession(session)
                val timeoutMin = (r("timeout").toIntOrNull() ?: 15).coerceIn(1, 120)
                val msg = "[Fluxo automático \"${w.name}\" — ninguém está acompanhando agora; execute e responda com o resultado final, sem perguntas. " +
                    "Os dados que vieram dos nós anteriores já estão nesta mensagem: use-os direto, não busque de novo nos conectores. " +
                    "Seja econômico: prefira web_search e web_fetch (no máximo ~6 consultas); só use o navegador se for indispensável. " +
                    "Links: use o endereço direto da fonte (nunca links de redirecionamento como news.google.com/read); não tente abrir ou decodificar links de redirecionamento. " +
                    "Sua ÚLTIMA mensagem é a entrega que segue para os próximos nós: escreva nela o resultado completo (ex.: o briefing pronto), nunca um relato do que fez nem o próximo passo. Não use ask_question nem peça confirmação." +
                    (if (run.trigger == "test") " MODO TESTE: pesquise e leia de verdade, mas NÃO envie nada para fora (e-mail, mensagem, POST, publicação, compra); descreva o que enviaria." else "") + "]\n\n$prompt"
                val maxSteps = (r("maxSteps").toIntOrNull() ?: 30).coerceAtLeast(30).coerceAtMost(60)
                val budget = (r("budget").toLongOrNull() ?: 200_000L).coerceIn(30_000L, 1_500_000L)
                val finished = withTimeoutOrNull(timeoutMin * 60_000L) { app.agent.runBudgeted(session.id, msg, maxSteps, budget); true }
                if (finished == null) { app.agent.stop(session.id); return NodeResult(error = "O agente passou de $timeoutMin min.", sessionId = session.id) }
                val s = app.store.session(session.id)
                val assistants = s?.items?.filterIsInstance<ChatItem.Assistant>().orEmpty()
                val answer = (assistants.lastOrNull { it.final } ?: assistants.lastOrNull())?.text.orEmpty()
                when {
                    s?.status == SessionStatus.ERROR -> NodeResult(error = (s.items.lastOrNull { it is ChatItem.Error } as? ChatItem.Error)?.text ?: "O agente falhou.", sessionId = session.id)
                    s?.status == SessionStatus.WAITING_USER -> NodeResult(error = "O agente parou pedindo uma confirmação ou resposta. Abra o chat da execução.", sessionId = session.id)
                    answer.isBlank() -> NodeResult(error = "O agente terminou sem resposta.", sessionId = session.id)
                    WfQuality.agentUnfinished(answer) -> NodeResult(answer, error = "O agente parou no meio do trabalho (\"${answer.take(120)}\"). Deixe a instrução mais direta, aumente passos/tempo ou divida em nós.", sessionId = session.id)
                    else -> WfQuality.aiOutputProblem("A resposta do agente", answer)?.let { NodeResult(answer, error = it, sessionId = session.id) } ?: NodeResult(answer, sessionId = session.id)
                }
            }

            "connector" -> {
                // Preenche os {{…}} DENTRO de cada valor do JSON (texto com aspas/quebras de linha — ex.: um rascunho de e-mail —
                // não quebra mais o JSON). Modelo que não é JSON válido cai no preenchimento de texto antigo.
                val tpl = node.config["campos"].orEmpty().ifBlank { "{}" }
                val parsedTpl = runCatching { Json.parseToJsonElement(tpl) as JsonObject }.getOrNull()
                val campos = if (parsedTpl != null) {
                    fun walk(e: kotlinx.serialization.json.JsonElement): kotlinx.serialization.json.JsonElement = when (e) {
                        is kotlinx.serialization.json.JsonPrimitive -> if (e.isString && e.content.contains("{{")) kotlinx.serialization.json.JsonPrimitive(rt(e.content, 0)) else e
                        is kotlinx.serialization.json.JsonArray -> kotlinx.serialization.json.JsonArray(e.map { walk(it) })
                        is JsonObject -> JsonObject(e.mapValues { walk(it.value) })
                    }
                    walk(parsedTpl) as JsonObject
                } else {
                    val camposText = r("campos").ifBlank { "{}" }
                    runCatching { Json.parseToJsonElement(camposText) as JsonObject }.getOrElse { return NodeResult(error = "Campos não são um objeto JSON válido depois de preencher os valores: ${camposText.take(300)}") }
                }
                if (WfQuality.isEmailSend(node)) WfQuality.emailProblem(campos)?.let { return NodeResult(error = "$it Nada foi enviado.") }
                val kind = node.config["source"].orEmpty().ifBlank { WfConnectors.HUB }
                // E-mail sai sempre em HTML formatado (Markdown → HTML com estilos inline) + versão em texto.
                val sendCampos = if (WfQuality.isEmailSend(node)) {
                    val keys = runCatching { WfConnectors.fields(app.store, kind, r("conector").trim(), r("acao").trim(), r("conexao").trim()).map { it.key }.toSet() }.getOrDefault(emptySet())
                    // Itens com link (notícias/produtos) ganham a foto da página antes de virar HTML.
                    val bodyKey = listOf("body_text", "body", "corpo", "text").firstOrNull { (campos[it] as? kotlinx.serialization.json.JsonPrimitive)?.content?.isNotBlank() == true }
                    val withImgs = bodyKey?.let { k ->
                        val md = (campos[k] as kotlinx.serialization.json.JsonPrimitive).content
                        val links = if (WfEmail.looksHtml(md)) emptyList() else WfEmail.linkedItemsWithoutImage(md)
                        if (links.isEmpty()) campos else {
                            val imgs = withTimeoutOrNull(8_000) { links.map { u -> u to dev.fluwy.app.web.ImageEnricher.imageFor(u) } }?.mapNotNull { (u, i) -> i?.let { u to it } }?.toMap().orEmpty()
                            JsonObject(campos + (k to kotlinx.serialization.json.JsonPrimitive(WfEmail.withImages(md, imgs))))
                        }
                    } ?: campos
                    WfEmail.prepare(withImgs, keys)
                } else campos
                if (run.trigger == "test" && WfQuality.isExternalSend("connector", node.config))
                    return simulated(node, "${r("conector").trim()} · ${r("acao").trim()}", sendCampos.toString())
                val o = WfConnectors.run(app.store, kind, r("conector").trim(), r("acao").trim(), sendCampos, r("conexao").trim())
                NodeResult(o.output, error = o.error)
            }
            "mcp" -> {
                val argsText = r("args").ifBlank { "{}" }
                val args = runCatching { Json.parseToJsonElement(argsText) as JsonObject }.getOrElse { return NodeResult(error = "Argumentos não são um objeto JSON válido: ${argsText.take(300)}") }
                if (run.trigger == "test" && WfQuality.isExternalSend("mcp", node.config)) return simulated(node, r("tool").trim(), args.toString())
                val out = dev.fluwy.app.connectors.Connectors.callTool(app.store, r("tool").trim(), args)
                if (Regex("^\\s*(ERRO|ERROR|RECUSADO|Falha)", RegexOption.IGNORE_CASE).containsMatchIn(out)) NodeResult(error = out.take(1_500)) else NodeResult(out)
            }

            "atendente" -> atendente(w, run, node, r = r, rt = rt)
            "webapi" -> {
                val slug = r("api").trim()
                val api = dev.fluwy.app.browser.WebApiHub.find(app, slug)?.takeIf { it.enabled }
                    ?: return NodeResult(error = "Web API \"$slug\" não existe ou está desligada no API Hub.")
                val tpl = runCatching { Json.parseToJsonElement(node.config["args"].orEmpty().ifBlank { "{}" }) as kotlinx.serialization.json.JsonObject }
                    .getOrElse { return NodeResult(error = "Argumentos não são um JSON válido: ${it.message?.take(120)}") }
                // Templates resolvidos valor a valor (texto com aspas não quebra o JSON).
                fun res(e: kotlinx.serialization.json.JsonElement): kotlinx.serialization.json.JsonElement = when (e) {
                    is kotlinx.serialization.json.JsonPrimitive -> if (e.isString) kotlinx.serialization.json.JsonPrimitive(rt(e.content, 0)) else e
                    is kotlinx.serialization.json.JsonObject -> kotlinx.serialization.json.JsonObject(e.mapValues { res(it.value) })
                    is kotlinx.serialization.json.JsonArray -> kotlinx.serialization.json.JsonArray(e.map { res(it) })
                    else -> e
                }
                var args = res(tpl) as kotlinx.serialization.json.JsonObject
                // Fluxo publicado = autorização do usuário para a ação configurada; no teste, só valida.
                if (api.sensitive) args = kotlinx.serialization.json.JsonObject(args + ("confirmed" to kotlinx.serialization.json.JsonPrimitive("true")) +
                    (if (run.trigger == "test") mapOf("__dry_run" to kotlinx.serialization.json.JsonPrimitive("true")) else emptyMap()))
                val out = dev.fluwy.app.browser.WebApiHub.call(app, api, args)
                if (out.contains("\"ok\":true")) NodeResult(out)
                else NodeResult(error = "${api.slug}: " + (Regex("\"error\":\"([^\"]*)").find(out)?.groupValues?.get(1) ?: out.take(300)))
            }
            "http" -> withContext(Dispatchers.IO) {
                val method = r("method").ifBlank { "GET" }
                if (run.trigger == "test" && WfQuality.isExternalSend("http", node.config, method)) {
                    val url = r("url").trim()
                    if (!Regex("^https?://[^\\s/]+\\.[^\\s]+").containsMatchIn(url)) return@withContext NodeResult(error = "URL inválida: ${url.take(200)}")
                    return@withContext simulated(node, "${method.uppercase()} $url", r("body"))
                }
                var res = http(method, r("url").trim(), r("headers"), r("body"), (r("timeout").toIntOrNull() ?: 30).coerceIn(1, 300))
                var attempt = 0
                while (WfRetry.shouldRetry(method, res.error, attempt)) {
                    kotlinx.coroutines.delay(WfRetry.backoffMs(attempt)); attempt++
                    res = http(method, r("url").trim(), r("headers"), r("body"), (r("timeout").toIntOrNull() ?: 30).coerceIn(1, 300))
                }
                // "Continuar": o erro vira dado ({status, error}) para um nó Condição decidir o caminho.
                if (res.error != null && node.config["onError"] == "continue")
                    NodeResult(res.output.ifBlank { buildJsonObject { put("status", 0); put("error", res.error) }.toString() })
                else res
            }

            "shell" -> {
                val cmd = r("command").trim()
                if (cmd.isBlank()) return NodeResult(error = "Comando vazio.")
                if (run.trigger == "test" && WfQuality.isExternalSend("shell", mapOf("command" to cmd))) return simulated(node, "comando que envia para fora", cmd)
                if (!app.sandbox.isInstalled()) return NodeResult(error = "O Linux do app não está instalado (Configurações › Sandbox).")
                val res = app.sandbox.exec(dev.fluwy.app.sandbox.InteractivePrompts.ENV + "\n{\n" + cmd + "\n} < /dev/null",
                    cwd = "/root/workspace", timeoutSec = (r("timeout").toLongOrNull() ?: 120L).coerceIn(5, 1_800))
                val out = buildJsonObject { put("exit", res.exitCode); put("output", res.output.takeLast(15_000)) }.toString()
                if (res.exitCode != 0) NodeResult(out, error = "Saiu com código ${res.exitCode}: ${res.output.takeLast(500)}") else NodeResult(out)
            }

            "set" -> NodeResult(buildJsonObject {
                r("values").lines().map { it.trim() }.filter { it.isNotEmpty() && it.contains('=') }.forEach { line ->
                    put(line.substringBefore('=').trim(), line.substringAfter('=').trim())
                }
            }.toString())

            "if" -> {
                val ok = WfLogic.condition(r("left"), node.config["op"] ?: "contains", r("right"))
                NodeResult(prev, port = if (ok) "true" else "false")
            }

            "delay" -> { delay((r("seconds").toLongOrNull() ?: 10L).coerceIn(0, 3_600) * 1_000L); NodeResult(prev) }

            "approval" -> {
                val q = r("question").ifBlank { "Posso continuar?" }
                WfQuality.contentProblem("A pergunta de aprovação", q)?.let { return NodeResult(error = it) }
                if (run.trigger == "test") return NodeResult("aprovado (teste: aprovação simulada; o envio seguinte também é simulado)", port = "true")
                val d = CompletableDeferred<Boolean>()
                approvals["${run.id}|${node.id}"] = d
                setStatus("waiting")
                runCatching {
                    dev.fluwy.app.notify.NotificationCenter.post("Aprovar fluxo \"${w.name}\"?", q.take(200),
                        dev.fluwy.app.notify.NotificationCenter.Priority.HIGH, dev.fluwy.app.notify.NotificationCenter.Category.APPROVAL, needsAction = true,
                        agent = "Fluxo · ${w.name}", detail = q.take(4_000), route = "workflow/${w.id}", routeLabel = "Responder no fluxo")
                }
                val hours = (r("hours").toLongOrNull() ?: 24L).coerceIn(1, 168)
                val ok = withTimeoutOrNull(TimeUnit.HOURS.toMillis(hours)) { d.await() } ?: false
                approvals.remove("${run.id}|${node.id}")
                setStatus("running")
                NodeResult(if (ok) "aprovado" else "recusado", port = if (ok) "true" else "false")
            }

            "ai_decide" -> {
                val criteria = r("criteria").trim()
                if (criteria.isBlank()) return NodeResult(error = "Defina o critério da decisão.")
                val item = rh("input", false).ifBlank { prev }.take(14_000)
                if (item.isBlank()) return NodeResult(error = dev.fluwy.app.prompts.Prompts.render("workflow.3", mapOf<String, Any?>("lb" to "{{")))
                val ask = dev.fluwy.app.prompts.Prompts.render("workflow.2", mapOf<String, Any?>("criteria" to (criteria), "item" to (item), "lb" to "{{")) +
                    dev.fluwy.app.prompts.Prompts.render("workflow.1", mapOf<String, Any?>("lb" to "{{"))
                val raw = app.llm.quick(ask, maxTokens = 300, conversationId = "workflow-${w.id}-${node.id}").trim()
                val obj = Regex("\\{[\\s\\S]*\\}").find(raw)?.value?.let { runCatching { Json.parseToJsonElement(it) as JsonObject }.getOrNull() }
                    ?: return NodeResult(error = dev.fluwy.app.prompts.Prompts.render("workflow.4", mapOf<String, Any?>("raw" to (raw.take(200)), "lb" to "{{")))
                val d = obj["decisao"] ?: obj["decision"]
                val yes = (d as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim()?.lowercase() in setOf("true", "sim", "yes", "1")
                NodeResult(buildJsonObject {
                    put("decision", yes)
                    put("reason", (obj["motivo"] ?: obj["reason"])?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.orEmpty())
                    put("category", (obj["categoria"] ?: obj["category"])?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.orEmpty())
                    put("confidence", (obj["confianca"] ?: obj["confidence"])?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull() } ?: 0.0)
                }.toString(), port = if (yes) "true" else "false")
            }

            "review" -> {
                val title = WfFormat.oneLine(rh("title", true)).ifBlank { "Revisar" }
                val itemRaw = r("item").ifBlank { prev }
                val draft = rh("draft", false).trim()
                val auto = (node.config["mode"] ?: "ask") == "auto" || w.autonomous
                fun out(ok: Boolean, text: String, how: String) = NodeResult(buildJsonObject {
                    put("approved", ok); put("draft", text); put("decided_by", how); put("edited", text != draft)
                }.toString(), port = if (ok) "true" else "false")
                when {
                    // Teste nunca dispara o envio de verdade (ex.: responder um cliente): segue por "não".
                    run.trigger == "test" -> NodeResult(buildJsonObject { put("approved", true); put("draft", draft); put("decided_by", "teste")
                        put("note", "teste: aprovação simulada para testar o caminho todo; o envio seguinte é simulado. Em execução real " + (if (auto) "segue sozinho (automático)" else "espera a sua aprovação na página de revisão")) }.toString(), port = "true")
                    auto -> out(true, draft, "automático")
                    else -> {
                        val key = "${run.id}|${node.id}"
                        val view = WfView.view(WfView.itemBlocks(itemRaw))
                        val wait = CompletableDeferred<ReviewDecision>()
                        reviewWaits[key] = wait
                        _reviews.value = _reviews.value + (key to Review(run.id, node.id, w.id, w.name, title, view, draft))
                        setStatus("waiting")
                        runCatching {
                            dev.fluwy.app.notify.NotificationCenter.post("✍ $title", WfView.plainText(view).lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(160),
                                dev.fluwy.app.notify.NotificationCenter.Priority.HIGH, dev.fluwy.app.notify.NotificationCenter.Category.APPROVAL, needsAction = true,
                                agent = "Fluxo · ${w.name}", dedupeKey = "review|$key", route = "review/${run.id}/${node.id}", routeLabel = "Revisar")
                        }
                        val hours = (r("hours").toLongOrNull() ?: 24L).coerceIn(1, 168)
                        val dec = try { withTimeoutOrNull(TimeUnit.HOURS.toMillis(hours)) { wait.await() } } finally {
                            reviewWaits.remove(key); _reviews.value = _reviews.value - key
                        }
                        setStatus("running")
                        if (dec == null) out(false, draft, "tempo esgotado") else out(dec.approved, dec.draft.ifBlank { draft }, "você")
                    }
                }
            }

            "notify" -> {
                // Nada de JSON para o usuário: {{…}} com dados viram texto claro; o quadro/página usa o UI kit (cards, listas, imagens).
                val title = WfFormat.oneLine(rh("title", true)).ifBlank { w.name }.take(80)
                val text = rh("text", true).ifBlank { "Fluxo concluído." }
                val open = node.config["open"] ?: "modal"
                val layout = node.config["layout"] ?: "auto"
                val detailTpl = node.config["detail"].orEmpty()
                val rawDetail = if (detailTpl.isBlank()) "" else r("detail")
                val prevIsData = prev.isNotBlank() && !(WfFormat.isJson(prev) && Regex("\"trigger\"\\s*:").containsMatchIn(prev.take(200)))
                val time = java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale("pt", "BR")).format(java.util.Date())
                var view = ""
                var detail = ""
                if (open == "modal" || open == "page") {
                    when {
                        // Modelo próprio (UI kit) escrito no nó.
                        WfView.isView(detailTpl) -> view = WfView.fromTemplate(detailTpl, { t -> rt(t, 1) }, { t -> rt(t, 0) })
                        // Um nó (ex.: IA) devolveu uma view pronta.
                        WfView.isView(rawDetail) -> view = rawDetail.trim()
                        layout == "markdown" -> detail = if (detailTpl.isNotBlank()) rh("detail", false) else if (prevIsData) text + "\n\n" + WfFormat.readable(prev) else text
                        else -> {
                            val data = when {
                                detailTpl.isBlank() -> prev.takeIf { prevIsData }
                                WfFormat.isJson(rawDetail) -> rawDetail
                                else -> null
                            }
                            val md = if (detailTpl.isNotBlank() && data == null) rh("detail", false) else null
                            view = WfView.build(title, text, md, data, time)
                        }
                    }
                    if (detail.isBlank()) detail = if (view.isNotBlank()) WfView.plainText(view).take(6_000) else text
                }
                // Cards do UI kit com link ganham a foto da página; card que segue sem foto sai.
                if (view.isNotBlank()) view = runCatching { dev.fluwy.app.web.ImageEnricher.enrichCardsJson(view) }.getOrDefault(view)
                // Portão de conteúdo: nada vazio, sem {{…}} sobrando, sem JSON cru para o usuário.
                WfQuality.contentProblem("O título da notificação", title)?.let { return NodeResult(error = it) }
                WfQuality.contentProblem("O texto da notificação", text)?.let { return NodeResult(error = it) }
                if (view.isBlank() && detail.isNotBlank()) WfQuality.contentProblem("O conteúdo do quadro/página", detail)?.let { return NodeResult(error = it) }
                if (view.isNotBlank()) WfQuality.contentProblem("O conteúdo do quadro/página", WfView.plainText(view))?.let { return NodeResult(error = it) }
                var route = if (open == "none") null else "workflow/${w.id}"
                if (open == "chat") {
                    // Chat do fluxo: uma conversa com o conteúdo completo (Markdown estilo GitHub + UI kit), onde dá para continuar perguntando.
                    val md = buildString {
                        append("**").append(title).append("**\n\n").append(text)
                        val extra = if (detailTpl.isNotBlank()) rh("detail", false) else if (prevIsData) WfFormat.readable(prev) else ""
                        if (extra.isNotBlank() && extra.trim() != text.trim()) append("\n\n").append(extra)
                        if (WfView.isView(rawDetail)) append("\n\n```fluwyui\n").append(rawDetail.trim()).append("\n```")
                    }
                    WfQuality.contentProblem("A mensagem do chat do fluxo", md)?.let { return NodeResult(error = it) }
                    if (run.trigger != "test") route = WfChat.post(app.store, w, md)?.let { "chat/$it" } ?: route
                    detail = ""; view = ""
                }
                dev.fluwy.app.notify.NotificationCenter.post(title, text.replace("**", "").take(300),
                    dev.fluwy.app.notify.NotificationCenter.Priority.NORMAL, dev.fluwy.app.notify.NotificationCenter.Category.DONE,
                    agent = "Fluxo · ${w.name}", dedupeKey = "wf|${run.id}|${node.id}",
                    detail = detail, route = route, routeLabel = if (route?.startsWith("chat/") == true) "Abrir chat" else "Abrir fluxo", page = open == "page", view = view)
                NodeResult(buildJsonObject { put("title", title); put("text", text.take(4_000)); if (detail.isNotEmpty()) put("detail", detail.take(4_000)); put("layout", if (view.isNotEmpty()) "ui-kit" else "markdown") }.toString())
            }

            "ai" -> {
                val prompt = r("prompt").trim()
                if (prompt.isBlank()) return NodeResult(error = "Instrução vazia.")
                if (prompt.length > 120_000) return NodeResult(error = "Os dados ficaram grandes demais para a IA (${prompt.length / 1000} mil caracteres). Use um caminho mais específico, ex.: {{id.data.campo}}.")
                val max = (r("maxTokens").toIntOrNull() ?: 800).coerceIn(50, 8_000)
                // Provedores como o OpenCode exigem um id de conversa estável: um por fluxo+nó.
                // Entrada com XML de feed cru: troca pela lista enxuta (o modelo travava com 6–160 KB de XML).
                val input = if (Regex("(?i)<rss[\\s>]|<feed[\\s>]").containsMatchIn(prompt))
                    Regex("(?is)(<\\?xml[^>]*>\\s*)?<(rss|feed)[\\s>].*?(</(rss|feed)>|$)").replace(prompt) { m -> WfFeed.toText(WfFeed.parse(m.value, 25)).ifBlank { m.value.take(8_000) } }
                else prompt
                var out = ""
                for (attempt in 0..1) {
                    out = runCatching { app.llm.quick(input, maxTokens = max, conversationId = "workflow-${w.id}-${node.id}") }.getOrDefault("").trim()
                    if (out.isNotBlank()) break
                    kotlinx.coroutines.delay(2_000)
                }
                // Resposta em bloco de código (```json … ```) vira o conteúdo puro (ex.: uma view do UI kit).
                val clean = Regex("^```[a-zA-Z]*\\s*([\\s\\S]*?)\\s*```$").find(out)?.groupValues?.get(1)?.trim() ?: out
                if (clean.isBlank()) NodeResult(error = "O modelo não respondeu (2 tentativas, entrada de ${input.length / 1000} mil caracteres). Reduza os dados: use {{id.text}} ou {{id.items}} em vez do corpo inteiro.")
                else WfQuality.aiOutputProblem("A resposta da IA", clean)?.let { NodeResult(clean, error = it) } ?: NodeResult(clean)
            }

            "if_new" -> {
                val key = r("key").trim()
                if (key.isBlank()) return NodeResult(error = "A chave ficou vazia: confira o caminho em {{…}} (o nó anterior não trouxe esse campo).")
                val slot = "${w.id}|${node.id}"
                val seen = WorkflowStore.seen(slot)
                val isNew = key !in seen
                when {
                    run.trigger == "test" -> NodeResult(buildJsonObject { put("key", key); put("new", isNew); put("note", "teste: segue por \"sim\" para testar o resto; nada foi memorizado") }.toString(), port = "true")
                    seen.isEmpty() && !WorkflowStore.seenInitialized(slot) && (node.config["first"] ?: "skip") == "skip" -> {
                        WorkflowStore.remember(slot, key); NodeResult(buildJsonObject { put("key", key); put("new", false); put("note", "primeira execução: memorizado") }.toString(), port = "false")
                    }
                    else -> {
                        if (isNew) WorkflowStore.remember(slot, key)
                        NodeResult(buildJsonObject { put("key", key); put("new", isNew) }.toString(), port = if (isNew) "true" else "false")
                    }
                }
            }

            else -> NodeResult(error = "Tipo de nó desconhecido: ${node.type}")
        }
    }

    private fun http(method: String, url: String, headers: String, body: String, timeoutSec: Int): NodeResult {
        if (!Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(url)) return NodeResult(error = "URL inválida: \"$url\" (use http:// ou https://).")
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        return try {
            c.requestMethod = method.uppercase()
            c.connectTimeout = 15_000; c.readTimeout = timeoutSec * 1_000; c.instanceFollowRedirects = true
            headers.lines().map { it.trim() }.filter { it.contains(':') }.forEach { c.setRequestProperty(it.substringBefore(':').trim(), it.substringAfter(':').trim()) }
            if (body.isNotBlank() && method.uppercase() != "GET") {
                c.doOutput = true
                if (c.getRequestProperty("Content-Type") == null)
                    c.setRequestProperty("Content-Type", if (body.trim().startsWith("{") || body.trim().startsWith("[")) "application/json" else "text/plain; charset=utf-8")
                c.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = c.responseCode
            val text = runCatching { (if (code >= 400) c.errorStream else c.inputStream)?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty().take(200_000)
            val data = runCatching { Json.parseToJsonElement(text) }.getOrNull()
            val out = buildJsonObject { put("status", code); put("body", if (WfFeed.isFeed(text)) text.take(4_000) else text.take(50_000)); if (data != null) put("data", data)
                val feed = if (data == null && WfFeed.isFeed(text)) WfFeed.parse(text, 30) else emptyList()
                if (feed.isNotEmpty()) { put("items", WfFeed.toJson(feed)); put("text", WfFeed.toText(feed)); put("count", feed.size) }
                else if (data == null && Regex("<(html|body|div|p)[\\s>]", RegexOption.IGNORE_CASE).containsMatchIn(text)) put("text", WfLogic.htmlText(text).take(20_000)) }.toString()
            if (code >= 400) NodeResult(out, error = "HTTP $code: ${text.take(400)}") else NodeResult(out)
        } catch (e: Exception) {
            NodeResult(error = "Falha de rede: ${e.message ?: e.javaClass.simpleName}")
        } finally { c.disconnect() }
    }
}

/**
 * Agenda os gatilhos de horário. Funciona com o app aberto, em segundo plano ou fechado:
 * - alarme exato do sistema (setExactAndAllowWhileIdle, acorda mesmo no Doze) → [WorkflowAlarmReceiver];
 * - reserva no WorkManager 90 s depois (caso o alarme seja perdido) — o disparo duplicado é descartado;
 * - último/próximo disparo persistidos ([WfScheduleState]): o intervalo não reinicia ao abrir o app e
 *   o card mostra sempre a última e a próxima execução.
 * Religado no boot (BootReceiver) e ao abrir o app (FluwyApp → syncAll).
 */
object WorkflowScheduler {
    private fun tag(wfId: String) = "wf_$wfId"
    private fun name(wfId: String, nodeId: String) = "wf_${wfId}_$nodeId"
    const val ACTION = "com.fluwy.bot.WORKFLOW_FIRE"

    private fun pi(ctx: Context, wfId: String, nodeId: String, create: Boolean = true): android.app.PendingIntent? {
        val i = android.content.Intent(ctx, WorkflowAlarmReceiver::class.java).setAction(ACTION)
            .putExtra("wf", wfId).putExtra("node", nodeId).setData(android.net.Uri.parse("fluwy://wf/$wfId/$nodeId"))
        val flags = android.app.PendingIntent.FLAG_IMMUTABLE or (if (create) android.app.PendingIntent.FLAG_UPDATE_CURRENT else android.app.PendingIntent.FLAG_NO_CREATE)
        return android.app.PendingIntent.getBroadcast(ctx, "$wfId/$nodeId".hashCode(), i, flags)
    }

    fun sync(ctx: Context, w: Workflow) {
        cancel(ctx, w)
        if (!w.enabled) return
        w.nodes.filter { it.type == "trigger_schedule" }.forEach { n -> scheduleNext(ctx, w.id, n, keepPending = true) }
        // Webhook: confere a issue a cada minuto (mesmo alarme exato + reserva do agendamento).
        w.nodes.filter { it.type == "trigger_webhook" }.forEach { n -> scheduleNext(ctx, w.id, n, keepPending = true) }
        // Mensagem no WhatsApp: publicar o fluxo liga o webhook do WhatsApp (escuta em tempo real).
        if (w.nodes.any { it.type == "trigger_whatsapp" }) runCatching {
            val c = dev.fluwy.app.browser.WhatsAppHub.config(ctx)
            if (!c.webhook) dev.fluwy.app.browser.WhatsAppHub.save(ctx, c.copy(webhook = true)) else dev.fluwy.app.browser.WhatsAppHub.start(ctx)
        }
    }

    private fun cancel(ctx: Context, w: Workflow) {
        WorkManager.getInstance(ctx).cancelAllWorkByTag(tag(w.id))
        val am = ctx.getSystemService(android.app.AlarmManager::class.java)
        w.nodes.forEach { n -> pi(ctx, w.id, n.id, create = false)?.let { am.cancel(it) } }
        if (!w.enabled) WfScheduleState.clearNext(ctx, w.id)
    }

    fun cancel(ctx: Context, wfId: String) {
        WorkManager.getInstance(ctx).cancelAllWorkByTag(tag(wfId))
        WorkflowStore.get(wfId)?.let { w ->
            val am = ctx.getSystemService(android.app.AlarmManager::class.java)
            w.nodes.forEach { n -> pi(ctx, wfId, n.id, create = false)?.let { am.cancel(it) } }
        }
        WfScheduleState.clearNext(ctx, wfId)
    }

    fun syncAll(ctx: Context) = WorkflowStore.flows.value.forEach { runCatching { sync(ctx, it) } }

    /**
     * Agenda o próximo disparo. [keepPending] = mantém um próximo já agendado (mesma config) que ainda está no
     * futuro — abrir o app ou reiniciar não empurra a agenda.
     */
    fun scheduleNext(ctx: Context, wfId: String, n: WfNode, keepPending: Boolean = false) {
        val now = System.currentTimeMillis()
        val sig = n.config.toSortedMap().toString()
        val st = WfScheduleState.get(ctx, wfId, n.id)
        val at = if (keepPending && st.sig == sig && st.next != null && st.next > now) st.next
            else WfLogic.nextFire(if (n.type == "trigger_webhook") mapOf("mode" to "interval", "minutes" to "1") else n.config, now, lastFire = st.last.takeIf { st.sig == sig }) ?: return
        WfScheduleState.setNext(ctx, wfId, n.id, at, sig)
        // 1) alarme exato
        runCatching {
            val am = ctx.getSystemService(android.app.AlarmManager::class.java)
            val p = pi(ctx, wfId, n.id)!!
            try {
                if (android.os.Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, p)
                else am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, p)
            } catch (_: SecurityException) { am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, p) }
        }
        // 2) reserva no WorkManager
        val req = OneTimeWorkRequestBuilder<WorkflowWorker>()
            .setInitialDelay((at - now + 90_000L).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setInputData(workDataOf("wf" to wfId, "node" to n.id, "due" to at))
            .addTag(tag(wfId)).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(name(wfId, n.id), ExistingWorkPolicy.REPLACE, req)
    }

    /** Dispara agora (alarme chegou): roda num trabalho do WorkManager para o Android manter o processo vivo. */
    fun fireNow(ctx: Context, wfId: String, nodeId: String) {
        val req = OneTimeWorkRequestBuilder<WorkflowWorker>()
            .setInputData(workDataOf("wf" to wfId, "node" to nodeId, "due" to System.currentTimeMillis()))
            .addTag(tag(wfId)).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("wf_fire_${wfId}_$nodeId", ExistingWorkPolicy.KEEP, req)
    }

    /** Próximo disparo de cada gatilho de horário do fluxo (o agendado de verdade; senão o calculado). */
    fun nextRuns(w: Workflow, ctx: Context? = null): List<Long> = if (!w.enabled) emptyList()
        else w.nodes.filter { it.type == "trigger_schedule" }.mapNotNull { n ->
            val now = System.currentTimeMillis()
            val st = ctx?.let { WfScheduleState.get(it, w.id, n.id) }
            st?.next?.takeIf { it > now - 120_000 && st.sig == n.config.toSortedMap().toString() }
                ?: WfLogic.nextFire(n.config, now, lastFire = st?.last)
        }.sorted()

    /** Último disparo agendado do fluxo (qualquer gatilho de horário). */
    fun lastFire(w: Workflow, ctx: Context): Long? = w.nodes.filter { it.type == "trigger_schedule" }
        .mapNotNull { WfScheduleState.get(ctx, w.id, it.id).last }.maxOrNull()
}

/** Último/próximo disparo por gatilho (SharedPreferences: leve e lido também com o app fechado). */
object WfScheduleState {
    data class St(val last: Long?, val next: Long?, val sig: String?)
    private fun sp(ctx: Context) = ctx.getSharedPreferences("wf_schedule", Context.MODE_PRIVATE)
    fun get(ctx: Context, wf: String, node: String): St = sp(ctx).let {
        St(it.getLong("$wf|$node|last", 0).takeIf { v -> v > 0 }, it.getLong("$wf|$node|next", 0).takeIf { v -> v > 0 }, it.getString("$wf|$node|sig", null))
    }
    fun setNext(ctx: Context, wf: String, node: String, at: Long, sig: String) =
        sp(ctx).edit().putLong("$wf|$node|next", at).putString("$wf|$node|sig", sig).apply()
    fun setLast(ctx: Context, wf: String, node: String, at: Long) = sp(ctx).edit().putLong("$wf|$node|last", at).apply()
    fun clearNext(ctx: Context, wf: String) = sp(ctx).let { p ->
        p.edit().apply { p.all.keys.filter { it.startsWith("$wf|") && it.endsWith("|next") }.forEach { remove(it) } }.apply()
    }
}

/** Alarme exato do gatilho de horário chegou (app aberto, em segundo plano ou fechado). */
class WorkflowAlarmReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: android.content.Intent) {
        val wf = intent.getStringExtra("wf") ?: return
        val node = intent.getStringExtra("node") ?: return
        WorkflowScheduler.fireNow(context.applicationContext, wf, node)
    }
}

class WorkflowWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val wfId = inputData.getString("wf") ?: return Result.success()
        val nodeId = inputData.getString("node") ?: return Result.success()
        val due = inputData.getLong("due", System.currentTimeMillis())
        val ctx = applicationContext
        WorkflowStore.init(ctx.filesDir)
        val w = WorkflowStore.get(wfId) ?: return Result.success()
        val node = w.nodes.firstOrNull { it.id == nodeId && (it.type == "trigger_schedule" || it.type == "trigger_webhook") } ?: return Result.success()
        if (!w.enabled) return Result.success()
        // Disparo duplicado (alarme + reserva): este horário já rodou.
        val st = WfScheduleState.get(ctx, wfId, nodeId)
        if (!WfLogic.shouldFire(due, st.last, st.next, System.currentTimeMillis())) return Result.success()
        WfScheduleState.setLast(ctx, wfId, nodeId, System.currentTimeMillis())
        // Agenda o próximo ANTES de rodar: uma execução longa ou com erro não apaga a agenda.
        WorkflowScheduler.scheduleNext(ctx, wfId, node)
        if (node.type == "trigger_webhook") {
            // Uma execução por chamada nova, em ordem; nada de rodar sem chamada.
            WfWebhook.poll(ctx, wfId, node).forEach { h ->
                WorkflowEngine.start(wfId, trigger = "webhook", triggerNodeId = nodeId, payload = h.payload).onSuccess { WorkflowEngine.awaitRun(it, 4 * 60_000L) }
            }
            return Result.success()
        }
        // Espera a execução (até ~9 min) para o Android manter o processo vivo; já rodando = este disparo é pulado.
        WorkflowEngine.start(wfId, trigger = "schedule", triggerNodeId = nodeId).onSuccess { WorkflowEngine.awaitRun(it, 9 * 60_000L) }
        return Result.success()
    }
}
