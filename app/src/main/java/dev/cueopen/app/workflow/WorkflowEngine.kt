package dev.cueopen.app.workflow

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.cueopen.app.CueApp
import dev.cueopen.app.data.ChatItem
import dev.cueopen.app.data.Session
import dev.cueopen.app.data.SessionStatus
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
    private const val MAX_STEPS = 200

    fun isRunning(workflowId: String) = WorkflowStore.runs.value.any { it.workflowId == workflowId && (it.status == "running" || it.status == "waiting") }

    /** Dispara uma execução (manual ou pelo agendamento). Devolve o id da execução, ou um erro de validação. */
    fun start(workflowId: String, trigger: String = "manual", triggerNodeId: String? = null): Result<String> {
        val w = WorkflowStore.get(workflowId) ?: return Result.failure(IllegalStateException("Fluxo não encontrado."))
        val blocking = WfLogic.problems(w).filterNot { it.contains("não está ligado a nada") }
        if (blocking.isNotEmpty()) return Result.failure(IllegalStateException(blocking.joinToString("\n")))
        if (isRunning(workflowId)) return Result.failure(IllegalStateException("Este fluxo já está em execução."))
        val run = WfRun(workflowId = w.id, workflowName = w.name, trigger = trigger)
        WorkflowStore.putRun(run)
        runCatching { dev.cueopen.app.sandbox.SandboxService.start(CueApp.instance, "Fluxo em execução: ${w.name}") }
        jobs[run.id] = scope.launch { execute(w, run, triggerNodeId) }
        return Result.success(run.id)
    }

    fun cancel(runId: String) {
        jobs.remove(runId)?.cancel()
        approvals.keys.filter { it.startsWith("$runId|") }.forEach { approvals.remove(it)?.complete(false) }
        WorkflowStore.runs.value.firstOrNull { it.id == runId }?.takeIf { it.status == "running" || it.status == "waiting" }?.let {
            WorkflowStore.putRun(it.copy(status = "cancelled", finishedAt = System.currentTimeMillis(), error = "Parada pelo usuário."))
        }
    }

    /** Resposta do usuário a um nó de Aprovação. */
    fun approve(runId: String, nodeId: String, ok: Boolean) { approvals.remove("$runId|$nodeId")?.complete(ok) }
    fun pendingApproval(runId: String, nodeId: String) = approvals.containsKey("$runId|$nodeId")

    private suspend fun execute(w: Workflow, run0: WfRun, triggerNodeId: String?) {
        var run = run0
        fun save(r: WfRun) { run = r; WorkflowStore.putRun(r) }
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
                if (++steps > MAX_STEPS) throw IllegalStateException("Mais de $MAX_STEPS passos: verifique se o fluxo não tem um ciclo.")
                val t0 = System.currentTimeMillis()
                save(run.copy(steps = run.steps + WfStep(node.id, labels.getValue(node.id), node.type, "running", t0)))
                fun r(key: String) = WfLogic.render(node.config[key] ?: WfCatalog.of(node.type).fields.firstOrNull { it.key == key }?.default.orEmpty(), outputs, labels, prev, vars)
                val res = try {
                    runNode(w, run, node, prev, ::r) { status -> save(run.copy(status = status)) }
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
        } catch (c: kotlinx.coroutines.CancellationException) {
            if (run.status == "running" || run.status == "waiting") save(run.copy(status = "cancelled", finishedAt = System.currentTimeMillis(), error = "Parada pelo usuário."))
        } catch (e: Throwable) {
            save(run.copy(status = "error", finishedAt = System.currentTimeMillis(), error = (e.message ?: e.javaClass.simpleName).take(1_000)))
            notifyResult(w, run)
        } finally {
            jobs.remove(run.id)
        }
    }

    private fun notifyResult(w: Workflow, run: WfRun) {
        if (run.trigger == "manual") return
        runCatching {
            dev.cueopen.app.notify.NotificationCenter.post("Fluxo \"${w.name}\" falhou", run.error.take(200),
                dev.cueopen.app.notify.NotificationCenter.Priority.HIGH, dev.cueopen.app.notify.NotificationCenter.Category.FAILURE)
        }
    }

    data class NodeResult(val output: String = "", val port: String? = null, val error: String? = null, val sessionId: String? = null)

    private suspend fun runNode(w: Workflow, run: WfRun, node: WfNode, prev: String, r: (String) -> String, setStatus: (String) -> Unit): NodeResult {
        val app = CueApp.instance
        return when (node.type) {
            "trigger_manual", "trigger_schedule" -> NodeResult(buildJsonObject {
                put("trigger", run.trigger); put("time", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date()))
                put("workflow", w.name)
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
                app.agent.send(session.id, "[Fluxo automático \"${w.name}\" — ninguém está acompanhando agora; execute e responda com o resultado final, sem perguntas.]\n\n$prompt").join()
                val finished = withTimeoutOrNull(timeoutMin * 60_000L) { while (app.agent.isRunning(session.id)) delay(1_000); true }
                if (finished == null) { app.agent.stop(session.id); return NodeResult(error = "O agente passou de $timeoutMin min.", sessionId = session.id) }
                val s = app.store.session(session.id)
                val answer = (s?.items?.lastOrNull { it is ChatItem.Assistant } as? ChatItem.Assistant)?.text.orEmpty()
                when {
                    s?.status == SessionStatus.ERROR -> NodeResult(error = (s.items.lastOrNull { it is ChatItem.Error } as? ChatItem.Error)?.text ?: "O agente falhou.", sessionId = session.id)
                    s?.status == SessionStatus.WAITING_USER -> NodeResult(error = "O agente parou pedindo uma confirmação ou resposta. Abra o chat da execução.", sessionId = session.id)
                    answer.isBlank() -> NodeResult(error = "O agente terminou sem resposta.", sessionId = session.id)
                    else -> NodeResult(answer, sessionId = session.id)
                }
            }

            "connector" -> {
                if (!dev.cueopen.app.connectors.HubConnectors.available(app.store))
                    return NodeResult(error = "O hub de conectores não está ligado (Conectores › Apps).")
                val camposText = r("campos").ifBlank { "{}" }
                val campos = runCatching { Json.parseToJsonElement(camposText) }.getOrElse { return NodeResult(error = "Campos não são um JSON válido depois de preencher os valores: ${camposText.take(300)}") }
                val out = dev.cueopen.app.connectors.HubConnectors.run(app.store, r("conector").trim(), r("acao").trim(), campos, r("conexao").trim(), full = true)
                if (Regex("^\\s*(ERRO|ERROR|RECUSADO|Falha)", RegexOption.IGNORE_CASE).containsMatchIn(out)) NodeResult(error = out.take(1_500)) else NodeResult(out)
            }

            "mcp" -> {
                val argsText = r("args").ifBlank { "{}" }
                val args = runCatching { Json.parseToJsonElement(argsText) as JsonObject }.getOrElse { return NodeResult(error = "Argumentos não são um objeto JSON válido: ${argsText.take(300)}") }
                val out = dev.cueopen.app.connectors.Connectors.callTool(app.store, r("tool").trim(), args)
                if (Regex("^\\s*(ERRO|ERROR|RECUSADO)", RegexOption.IGNORE_CASE).containsMatchIn(out)) NodeResult(error = out.take(1_500)) else NodeResult(out)
            }

            "http" -> withContext(Dispatchers.IO) { http(r("method").ifBlank { "GET" }, r("url").trim(), r("headers"), r("body"), (r("timeout").toIntOrNull() ?: 30).coerceIn(1, 300)) }

            "shell" -> {
                val cmd = r("command").trim()
                if (cmd.isBlank()) return NodeResult(error = "Comando vazio.")
                if (!app.sandbox.isInstalled()) return NodeResult(error = "O Linux do app não está instalado (Configurações › Sandbox).")
                val res = app.sandbox.exec(dev.cueopen.app.sandbox.InteractivePrompts.ENV + " ; " + cmd + " < /dev/null",
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
                val d = CompletableDeferred<Boolean>()
                approvals["${run.id}|${node.id}"] = d
                setStatus("waiting")
                runCatching {
                    dev.cueopen.app.notify.NotificationCenter.post("Aprovar fluxo \"${w.name}\"?", q.take(200),
                        dev.cueopen.app.notify.NotificationCenter.Priority.HIGH, dev.cueopen.app.notify.NotificationCenter.Category.APPROVAL, needsAction = true)
                }
                val hours = (r("hours").toLongOrNull() ?: 24L).coerceIn(1, 168)
                val ok = withTimeoutOrNull(TimeUnit.HOURS.toMillis(hours)) { d.await() } ?: false
                approvals.remove("${run.id}|${node.id}")
                setStatus("running")
                NodeResult(if (ok) "aprovado" else "recusado", port = if (ok) "true" else "false")
            }

            "notify" -> {
                dev.cueopen.app.notify.NotificationCenter.post(r("title").ifBlank { w.name }.take(80), r("text").ifBlank { "Fluxo concluído." }.take(900),
                    dev.cueopen.app.notify.NotificationCenter.Priority.NORMAL, dev.cueopen.app.notify.NotificationCenter.Category.DONE,
                    dedupeKey = "wf|${run.id}|${node.id}")
                NodeResult(prev)
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
            val out = buildJsonObject { put("status", code); put("body", text.take(50_000)); if (data != null) put("data", data) }.toString()
            if (code >= 400) NodeResult(out, error = "HTTP $code: ${text.take(400)}") else NodeResult(out)
        } catch (e: Exception) {
            NodeResult(error = "Falha de rede: ${e.message ?: e.javaClass.simpleName}")
        } finally { c.disconnect() }
    }
}

/** Agenda os gatilhos de horário (WorkManager, um trabalho por gatilho; cada disparo agenda o próximo). */
object WorkflowScheduler {
    private fun tag(wfId: String) = "wf_$wfId"
    private fun name(wfId: String, nodeId: String) = "wf_${wfId}_$nodeId"

    fun sync(ctx: Context, w: Workflow) {
        val wm = WorkManager.getInstance(ctx)
        wm.cancelAllWorkByTag(tag(w.id))
        if (!w.enabled) return
        w.nodes.filter { it.type == "trigger_schedule" }.forEach { n -> scheduleNext(ctx, w.id, n) }
    }

    fun cancel(ctx: Context, wfId: String) = WorkManager.getInstance(ctx).cancelAllWorkByTag(tag(wfId))

    fun syncAll(ctx: Context) = WorkflowStore.flows.value.forEach { runCatching { sync(ctx, it) } }

    fun scheduleNext(ctx: Context, wfId: String, n: WfNode) {
        val at = WfLogic.nextFire(n.config, System.currentTimeMillis()) ?: return
        val req = OneTimeWorkRequestBuilder<WorkflowWorker>()
            .setInitialDelay((at - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            .setInputData(workDataOf("wf" to wfId, "node" to n.id))
            .addTag(tag(wfId)).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(name(wfId, n.id), ExistingWorkPolicy.REPLACE, req)
    }

    /** Próximo disparo de cada gatilho de horário do fluxo (para mostrar no editor). */
    fun nextRuns(w: Workflow): List<Long> = if (!w.enabled) emptyList()
        else w.nodes.filter { it.type == "trigger_schedule" }.mapNotNull { WfLogic.nextFire(it.config, System.currentTimeMillis()) }
}

class WorkflowWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val wfId = inputData.getString("wf") ?: return Result.success()
        val nodeId = inputData.getString("node") ?: return Result.success()
        WorkflowStore.init(applicationContext.filesDir)
        val w = WorkflowStore.get(wfId) ?: return Result.success()
        val node = w.nodes.firstOrNull { it.id == nodeId && it.type == "trigger_schedule" } ?: return Result.success()
        if (!w.enabled) return Result.success()
        // Agenda o próximo ANTES de rodar: uma execução longa ou com erro não apaga a agenda.
        WorkflowScheduler.scheduleNext(applicationContext, wfId, node)
        WorkflowEngine.start(wfId, trigger = "schedule", triggerNodeId = nodeId)
        return Result.success()
    }
}
