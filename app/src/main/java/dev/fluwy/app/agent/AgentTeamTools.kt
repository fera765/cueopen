package dev.fluwy.app.agent

import dev.fluwy.app.browser.BrowserHost
import dev.fluwy.app.data.AgentProfile
import dev.fluwy.app.data.ChatItem
import dev.fluwy.app.data.KnowledgeItem
import dev.fluwy.app.data.LlmMessage
import dev.fluwy.app.data.PlanPhase
import dev.fluwy.app.data.Session
import dev.fluwy.app.data.Section
import dev.fluwy.app.data.SectionTask
import dev.fluwy.app.data.TaskStatus
import dev.fluwy.app.data.newId
import dev.fluwy.app.data.SessionStatus
import dev.fluwy.app.data.Skill
import dev.fluwy.app.data.StepStatus
import dev.fluwy.app.data.Store
import dev.fluwy.app.files.AtomicTextFile
import dev.fluwy.app.llm.OpenRouter
import dev.fluwy.app.llm.LlmTruncationPolicy
import dev.fluwy.app.llm.ToolCall
import dev.fluwy.app.llm.bool
import dev.fluwy.app.llm.int
import dev.fluwy.app.llm.str
import dev.fluwy.app.llm.strArray
import dev.fluwy.app.llm.tool
import dev.fluwy.app.util.runCatchingCancellable
import dev.fluwy.app.sandbox.Sandbox
import dev.fluwy.app.trading.TradeCoordinator
import dev.fluwy.app.uiconnector.iqoption.IqTradeWaitDirective
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import dev.fluwy.app.wallet.WalletAgentPolicyStore
import dev.fluwy.app.wallet.SolanaWalletRepository
import dev.fluwy.app.wallet.SolanaRpcClient
import dev.fluwy.app.wallet.SolanaNetwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.HashMap
import java.util.Locale
import dev.fluwy.app.agent.AgentRunner.AgentTask
import dev.fluwy.app.agent.AgentRunner.CustomDefParserFence
import dev.fluwy.app.agent.AgentRunner.Delegation
import dev.fluwy.app.agent.AgentRunner.Delivered
import dev.fluwy.app.agent.AgentRunner.FileChange
import dev.fluwy.app.agent.AgentRunner.QueuedMessage
import dev.fluwy.app.agent.AgentRunner.SectionPermissions
import dev.fluwy.app.agent.AgentRunner.SquadTask
import dev.fluwy.app.agent.AgentRunner.ToolResult
import dev.fluwy.app.agent.AgentRunner.WorkerMerge
import dev.fluwy.app.agent.AgentRunner.WorkerOutcome
import dev.fluwy.app.agent.AgentRunner.LoopGuards.ACTION_INTENT
import dev.fluwy.app.agent.AgentRunner.LoopGuards.BROWSER_WAIT_FOREGROUND_MS
import dev.fluwy.app.agent.AgentRunner.LoopGuards.CONTENT_REPEAT_TOOLS
import dev.fluwy.app.agent.AgentRunner.LoopGuards.DELEGATION_STARTED
import dev.fluwy.app.agent.AgentRunner.LoopGuards.MAX_AUTO_NUDGES
import dev.fluwy.app.agent.AgentRunner.LoopGuards.MAX_EDIT_FAILS
import dev.fluwy.app.agent.AgentRunner.LoopGuards.MAX_PARALLEL_MEMBERS
import dev.fluwy.app.agent.AgentRunner.LoopGuards.MAX_PLAN_NUDGES
import dev.fluwy.app.agent.AgentRunner.LoopGuards.MEMBER_SLOW_MINUTES
import dev.fluwy.app.agent.AgentRunner.LoopGuards.NETWORK_MAX_WAIT_MS
import dev.fluwy.app.agent.AgentRunner.LoopGuards.NETWORK_RETRY_HINT_MS
import dev.fluwy.app.agent.AgentRunner.LoopGuards.POST_COMPACTION_READS
import dev.fluwy.app.agent.AgentRunner.LoopGuards.PURE_REPEAT_TOOLS
import dev.fluwy.app.agent.AgentRunner.LoopGuards.STALE_SNAPSHOT_MINUTES
import dev.fluwy.app.agent.AgentRunner.LoopGuards.STRICT_REPEAT_TOOLS
import dev.fluwy.app.agent.AgentRunner.LoopGuards.hasActionIntent
import dev.fluwy.app.agent.AgentRunner.LoopGuards.memoizeConnectorBuilderCall
import dev.fluwy.app.agent.AgentRunner.LoopGuards.repeatToolThreshold
import dev.fluwy.app.agent.AgentRunner.LoopGuards.similar

/** Orquestra tarefas paralelas com workspaces isolados e estados explícitos. */
/**
 * Parallel agent orchestration: each worker executes in isolation, reports structured outcomes,
 * and only then participates in integration. Model completion alone does not mean task success.
 */
/**
 * Dependency-aware scheduler. Strings remain supported as independent tasks; object entries can
 * declare id, task/title and depends_on. Each ready wave is bounded by max_workers.
 */
internal suspend fun AgentRunner.runMultiAgents(sessionId: String, tasksJson: String, repoPathArg: String, requestedWorkers: Int, requestedBudget: Int): String {
    val tasks = runCatching {
        val arr = org.json.JSONArray(tasksJson)
        (0 until arr.length()).mapNotNull { index ->
            when (val item = arr.opt(index)) {
                is String -> item.trim().takeIf { it.isNotBlank() }?.let {
                    AgentTask("task-" + (index + 1), it, emptyList())
                }
                is org.json.JSONObject -> {
                    val id = item.optString("id").trim().ifBlank { "task-" + (index + 1) }
                    val task = item.optString("task").trim().ifBlank { item.optString("title").trim() }
                    val depsJson = item.optJSONArray("depends_on")
                    val deps = if (depsJson == null) emptyList() else
                        (0 until depsJson.length()).map { depsJson.optString(it).trim() }.filter { it.isNotBlank() }
                    if (task.isBlank()) null else AgentTask(id, task, deps)
                }
                else -> null
            }
        }
    }.getOrElse { return "ERRO: tasks_json deve ser um array JSON de strings ou objetos {id, task, depends_on}." }
    if (tasks.isEmpty()) return "ERRO: informe pelo menos uma subtarefa."
    if (tasks.map { it.id }.toSet().size != tasks.size) return "ERRO: cada subtarefa precisa ter um id único."
    val ids = tasks.map { it.id }.toSet()
    val invalid = tasks.firstOrNull { task -> task.dependsOn.any { it !in ids || it == task.id } }
    if (invalid != null) return "ERRO: dependência inválida na tarefa '" + invalid.id + "'. Confira depends_on e evite autorreferência."
    if (hasDependencyCycle(tasks)) return "ERRO: o plano contém um ciclo de dependências; nenhuma tarefa foi iniciada."

    val workerLimit = requestedWorkers.coerceIn(1, 5)
    val pending = tasks.toMutableList()
    val completed = linkedSetOf<String>()
    val failed = linkedSetOf<String>()
    val report = StringBuilder()
    appendReportLine(report, "Plano: " + tasks.size + " subtarefa(s), até " + workerLimit + " worker(s) simultâneos.")
    var hasRunBatch = false
    while (pending.isNotEmpty()) {
        val blocked = pending.filter { task -> task.dependsOn.any { it in failed } }
        if (blocked.isNotEmpty()) {
            blocked.forEach { task ->
                pending.remove(task)
                failed += task.id
                appendReportLine(report, "Tarefa '" + task.id + "' bloqueada: uma dependência falhou.")
            }
        }
        val ready = pending.filter { task -> task.dependsOn.all { it in completed } }
        if (ready.isEmpty()) {
            if (pending.isNotEmpty()) {
                appendReportLine(report, "ERRO: não há tarefas executáveis; dependências pendentes: " +
                    pending.joinToString { it.id + " <- " + it.dependsOn.joinToString(",") } + ".")
                break
            }
            continue
        }
        ready.chunked(workerLimit).forEachIndexed { batchIndex, batch ->
            appendReportLine(report, "")
            appendReportLine(report, "## Onda · lote " + (batchIndex + 1) + " · " + batch.joinToString { it.id })
            val payload = org.json.JSONArray().apply { batch.forEach { put(it.task) } }.toString()
            val result = runMultiAgentsBatch(
                sessionId = sessionId,
                tasksJson = payload,
                repoPathArg = repoPathArg,
                requestedWorkers = workerLimit,
                requestedBudget = requestedBudget,
                allowDirtyWorkspace = hasRunBatch
            )
            hasRunBatch = true
            appendReportLine(report, result)
            val success = !result.startsWith("ERRO:") &&
                !result.startsWith("Multi Agents pausado:") &&
                !result.contains("ATENÇÃO:")
            batch.forEach { task ->
                pending.remove(task)
                if (success) completed += task.id else failed += task.id
            }
        }
    }
    appendReportLine(report, "")
    appendReportLine(report, "Resumo do plano: " + completed.size + " concluída(s), " + failed.size + " falha(s)/bloqueada(s).")
    if (failed.isNotEmpty()) appendReportLine(report, "Revisar tarefas: " + failed.joinToString(", ") + ".")
    return report.toString()
}

internal fun AgentRunner.hasDependencyCycle(tasks: List<AgentTask>): Boolean {
    val byId = tasks.associateBy { it.id }
    val visiting = mutableSetOf<String>()
    val visited = mutableSetOf<String>()
    fun visit(id: String): Boolean {
        if (id in visiting) return true
        if (id in visited) return false
        visiting += id
        val cycle = byId[id]?.dependsOn.orEmpty().any { visit(it) }
        visiting -= id
        visited += id
        return cycle
    }
    return tasks.any { visit(it.id) }
}
