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
 * Motor dos fluxos (orquestração): fila/concorrência, pausa, cancelamento, aprovações/revisões, persistência das execuções,
 * publicação testada e fluxo de erro. A execução em si (itens, ordem, retentativas…) fica em [WfExecutor]; os nós em
 * [WfNodeRegistry]; as expressões em [WfExpr].
 */
object WorkflowEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    internal val approvals = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    /** Pedido de revisão aberto (nó "Revisar e aprovar"): o que a página mostra. */
    data class Review(val runId: String, val nodeId: String, val workflowId: String, val workflowName: String, val title: String,
                      val view: String, val draft: String, val createdAt: Long = System.currentTimeMillis())
    data class ReviewDecision(val approved: Boolean, val draft: String)
    internal val reviewWaits = ConcurrentHashMap<String, CompletableDeferred<ReviewDecision>>()
    internal val reviewsState = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Review>>(emptyMap())
    val reviews: kotlinx.coroutines.flow.StateFlow<Map<String, Review>> = reviewsState
    fun review(runId: String, nodeId: String): Review? = reviewsState.value["$runId|$nodeId"]
    fun pendingReviewOf(workflowId: String): Review? = reviewsState.value.values.firstOrNull { it.workflowId == workflowId }
    /** Resposta do usuário na página de revisão (texto final, possivelmente editado). */
    fun decideReview(runId: String, nodeId: String, approved: Boolean, draft: String) {
        reviewWaits.remove("$runId|$nodeId")?.complete(ReviewDecision(approved, draft))
    }

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

    /** Payload recebido por um gatilho (webhook/WhatsApp/erro), por execução (só em memória). */
    internal val payloads = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Dispara uma execução. Devolve o id da execução, ou um erro de validação. [until] = execução parcial
     * ("executar até este nó"): roda em modo teste só o nó e o que vem antes dele.
     */
    fun start(workflowId: String, trigger: String = "manual", triggerNodeId: String? = null, payload: String? = null, until: String? = null): Result<String> {
        val w = WorkflowStore.get(workflowId) ?: return Result.failure(IllegalStateException("Fluxo não encontrado."))
        val blocking = WfLogic.problems(w).filterNot { it.contains("não está ligado a nada") }
        if (blocking.isNotEmpty()) return Result.failure(IllegalStateException(blocking.joinToString("\n")))
        if (isRunning(workflowId)) return Result.failure(IllegalStateException("Este fluxo já está em execução."))
        val busy = WorkflowStore.runs.value.count { it.status == "running" || it.status == "paused" }
        val run = WfRun(workflowId = w.id, workflowName = w.name, trigger = if (until != null) "test" else trigger, status = if (busy >= MAX_CONCURRENT) "queued" else "running",
            until = until.orEmpty())
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

    /**
     * Testa um nó sozinho com os itens que ele recebeu na última execução (ou a saída mais recente dos nós de entrada).
     * Os demais nós ficam disponíveis para as expressões com a saída mais recente de cada um.
     */
    suspend fun testNode(w: Workflow, nodeId: String): NodeResult {
        val node = w.nodes.firstOrNull { it.id == nodeId } ?: return NodeResult(error = "Nó não encontrado.")
        if (node.type == "approval") return NodeResult(error = "A aprovação não é testada sozinha: execute o fluxo inteiro.")
        val outputs = linkedMapOf<String, List<WfItem>>()
        var lastInput: List<WfItem>? = null
        WorkflowStore.runsOf(w.id).reversed().forEach { run -> run.steps.forEach { st ->
            if (st.status == "ok") outputs[st.nodeId] = WfItems.fromStep(st.output, st.items)
            if (st.nodeId == nodeId && st.input.isNotBlank()) lastInput = WfItems.fromStep(st.input, st.inputItems)
        } }
        val input = lastInput ?: w.edges.filter { it.to == nodeId }.firstNotNullOfOrNull { outputs[it.from] } ?: listOf(WfItem.EMPTY)
        if (node.type == "delay") return NodeResult(WfItems.toText(input))
        val run = WfRun(workflowId = w.id, workflowName = w.name, trigger = "test")
        val trig = w.nodes.firstOrNull { WfCatalog.of(it.type).trigger }?.id
        val solo = w.copy(nodes = w.nodes.map { if (it.id == nodeId) it.copy(settings = it.settings.copy(pinData = "", disabled = false)) else it })
        val res = WfExecutor(solo, run, WfNodeRegistry::of, legacyVars = vars(w, "test")).run(nodeId, input, until = nodeId, seed = outputs - nodeId, triggerId = trig)
        val st = res.steps.firstOrNull() ?: return NodeResult(error = res.error.ifBlank { "Nada executado." })
        return NodeResult(st.output, error = st.error.takeIf { st.status == "error" }, sessionId = st.sessionId)
    }

    private fun vars(w: Workflow, trigger: String) = mapOf("trigger.time" to java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date()),
        "trigger.type" to trigger, "workflow.name" to w.name)

    /** Resposta do usuário a um nó de Aprovação. */
    fun approve(runId: String, nodeId: String, ok: Boolean) { approvals.remove("$runId|$nodeId")?.complete(ok) }
    fun pendingApproval(runId: String, nodeId: String) = approvals.containsKey("$runId|$nodeId")

    private suspend fun execute(w: Workflow, run0: WfRun, triggerNodeId: String?) {
        var run = run0
        fun save(r: WfRun) { run = if (r.status == "running" && r.id in pauses) r.copy(status = "paused") else r; WorkflowStore.putRun(run) }
        val start = w.nodes.firstOrNull { it.id == triggerNodeId }
            ?: run.until.takeIf { it.isNotBlank() }?.let { u -> w.nodes.firstOrNull { t -> WfCatalog.of(t.type).trigger && u in WfExecutor.plan(w, t.id).first } }
            ?: (if (run.trigger == "error") w.nodes.firstOrNull { it.type == "trigger_error" } else null)
            ?: w.nodes.firstOrNull { it.type == "trigger_manual" } ?: w.nodes.first { WfCatalog.of(it.type).trigger }
        val hooks = object : WfExecHooks {
            override suspend fun beforeNode() {
                if (run.id in pauses) { save(run.copy(status = "paused")); while (run.id in pauses) delay(400); save(run.copy(status = "running")) }
            }
            override fun step(step: WfStep, replace: Boolean) {
                save(run.copy(status = "running", steps = if (replace) run.steps.dropLast(1) + step else run.steps + step))
            }
            override fun status(status: String) = save(run.copy(status = status))
        }
        try {
            val manual = run.trigger == "manual" || run.trigger == "test"
            val res = WfExecutor(w, run, WfNodeRegistry::of, hooks, vars(w, run.trigger)).run(start.id, until = run.until.ifBlank { null }, usePins = manual)
            if (!res.ok) {
                val failed = w.nodes.firstOrNull { it.id == res.failedNodeId }
                dev.fluwy.app.diag.ErrorLog.log("workflow", "${w.name} · ${res.error} (${failed?.type})", tool = failed?.type)
                save(run.copy(status = "error", finishedAt = System.currentTimeMillis(), error = res.error))
                notifyResult(w, run); runErrorWorkflow(w, run, failed); return
            }
            save(run.copy(status = "ok", finishedAt = System.currentTimeMillis()))
            // Versão testada com sucesso (vale para publicar enquanto ninguém alterar nós/ligações). Execução parcial não conta.
            if (run.until.isBlank()) WorkflowStore.get(w.id)?.let { cur -> val sig = WfLogic.signature(w); if (WfLogic.signature(cur) == sig && cur.testedSig != sig) WorkflowStore.upsert(cur.copy(testedSig = sig)) }
        } catch (c: kotlinx.coroutines.CancellationException) {
            pauses.remove(run.id)
            if (isActive(run.status)) save(run.copy(status = "cancelled", finishedAt = System.currentTimeMillis(), error = "Parada pelo usuário."))
        } catch (e: Throwable) {
            dev.fluwy.app.diag.ErrorLog.log("workflow", e, "Fluxo ${w.name}")
            save(run.copy(status = "error", finishedAt = System.currentTimeMillis(), error = (e.message ?: e.javaClass.simpleName).take(1_000)))
            notifyResult(w, run); runErrorWorkflow(w, run, null)
        } finally {
            pauses.remove(run.id)
            jobs.remove(run.id)
        }
    }

    /** Fluxo de erro (como no n8n): execução real que falha dispara o fluxo escolhido com os dados da falha. */
    private fun runErrorWorkflow(w: Workflow, run: WfRun, failed: WfNode?) {
        if (w.errorWorkflowId.isBlank() || w.errorWorkflowId == w.id || run.trigger == "test" || run.trigger == "error") return
        val payload = buildJsonObject {
            put("workflow", buildJsonObject { put("id", w.id); put("name", w.name) })
            put("execution", buildJsonObject { put("id", run.id); put("error", run.error.take(2_000)); put("lastNode", failed?.let { WfLogic.label(it) }.orEmpty()); put("mode", run.trigger) })
        }.toString()
        start(w.errorWorkflowId, trigger = "error", payload = payload).onFailure { dev.fluwy.app.diag.ErrorLog.log("workflow", "Fluxo de erro de ${w.name}: ${it.message}", tool = "error_workflow") }
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

}
