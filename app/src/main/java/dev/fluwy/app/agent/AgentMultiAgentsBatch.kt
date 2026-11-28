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

internal suspend fun AgentRunner.runMultiAgentsBatch(sessionId: String, tasksJson: String, repoPathArg: String, requestedWorkers: Int, requestedBudget: Int, allowDirtyWorkspace: Boolean = false): String {
    val tasks = runCatching {
        val arr = org.json.JSONArray(tasksJson)
        (0 until arr.length()).mapNotNull { arr.optString(it).trim().takeIf { task -> task.isNotBlank() } }
    }.getOrElse { return "ERRO: tasks_json deve ser um array JSON de strings." }
    if (tasks.isEmpty()) return "ERRO: informe pelo menos uma subtarefa."

    val count = minOf(tasks.size, requestedWorkers.coerceIn(1, 5))
    val budget = requestedBudget.coerceIn(2000, 12000).toLong()
    val parentPath = sandbox.guestPath(sessionId)
    val terminal = terminals.get(sessionId, parentPath)
    val parentSession = store.session(sessionId)
    val parentRequest = parentSession?.llm?.lastOrNull {
        it.role == "user" && it.content?.startsWith("[Sistema]") != true
    }?.content.orEmpty()
    val parentPlan = (parentSession?.items?.lastOrNull { it is ChatItem.Plan } as? ChatItem.Plan)
        ?.phases?.joinToString("\n") { phase -> "- [${if (phase.done) "x" else " "}] ${phase.title}" }.orEmpty()
    // Não use git worktree dentro de /root/workspace: cada sessão é montada num rootfs
    // público diferente, e a pasta de destino já existe. A cópia é feita no filesystem host.
    val gitRoot: String? = null
    val baseSha: String? = null
    var baseSnapshot: Map<String, String> = emptyMap()

    val workers = tasks.take(count).mapIndexed { index, task ->
        Session(
            title = "[Worker " + (index + 1) + "] " + task.take(42),
            agentId = store.session(sessionId)?.agentId,
            status = SessionStatus.RUNNING,
            llm = listOf(
                LlmMessage("user", PromptContextPolicy.workerTask(
                    task = task,
                    parentRequest = parentRequest,
                    parentSummary = parentSession?.summary.orEmpty(),
                    plan = parentPlan,
                ))
            )
        )
    }
    workers.forEach { store.upsertSession(it) }

    if (gitRoot != null && baseSha != null) {
        val setupErrors = mutableListOf<String>()
        workers.forEach { child ->
            val branchName = "fluwy/agents/" + child.id
            val target = sandbox.guestPath(child.id)
            val cmd = "git -C " + shQuote(gitRoot) + " worktree add -b " +
                shQuote(branchName) + " " + shQuote(target) + " " + shQuote(baseSha)
            val result = terminal.runAgentCommand(cmd, 45L, false)
            if (result.exitCode != 0) setupErrors += child.id + ": " + result.output.takeLast(350)
        }
        if (setupErrors.isNotEmpty()) {
            workers.forEach { child ->
                terminal.runAgentCommand(
                    "git -C " + shQuote(gitRoot) + " worktree remove --force " +
                        shQuote(sandbox.guestPath(child.id)) + " >/dev/null 2>&1 || true; git -C " +
                        shQuote(gitRoot) + " branch -D " + shQuote("fluwy/agents/" + child.id) +
                        " >/dev/null 2>&1 || true",
                    20L, false
                )
                store.deleteSession(child.id)
            }
            return "Não iniciei workers porque a criação de worktrees falhou:\n" + setupErrors.joinToString("\n")
        }
    } else {
        val source = sandbox.workspaceFor(sessionId)
        // Snapshot do projeto original: é contra ele que as alterações de cada worker serão comparadas na volta.
        baseSnapshot = projectSnapshot(source)
        for (child in workers) {
            val target = sandbox.workspaceFor(child.id)
            val copied = runCatching { source.copyRecursively(target, overwrite = true); true }.getOrElse { false }
            if (!copied) {
                workers.forEach { store.deleteSession(it.id) }
                return "Não iniciei os workers: falhou a cópia isolada do workspace para " + child.id + "."
            }
        }
    }

    val workerSteps = workers.associate { child ->
        val workerNumber = child.title.removePrefix("[Worker ").substringBefore("]")
        val taskLabel = child.title.substringAfter("] ", child.title).take(48)
        val step = ChatItem.Step(
            tool = "multi_agent_worker",
            title = "Agente " + workerNumber + " · " + taskLabel,
            args = "Na fila",
            result = "Workspace isolado preparado.",
            status = StepStatus.RUNNING
        )
        store.addItem(sessionId, step)
        child.id to step
    }

    val outcomes = java.util.concurrent.ConcurrentHashMap<String, WorkerOutcome>()
    val workerJobs = workers.mapIndexed { workerIndex, child ->
        scope.launch {
            val parentStep = workerSteps.getValue(child.id)
            val initial = store.session(child.id)
            val initialTokens = initial?.let { it.tokensIn + it.tokensOut } ?: 0L
            try {
                store.replaceItem(sessionId, parentStep.copy(args = "Executando", result = "Agente em execução no workspace isolado."))
                loop(child.id, maxStepsOverride = 30, tokenBudgetOverride = budget)
                val finished = store.session(child.id)
                val assistant = finished?.items?.lastOrNull { it is ChatItem.Assistant } as? ChatItem.Assistant
                val errors = finished?.items?.filterIsInstance<ChatItem.Error>()?.map { it.text }.orEmpty()
                val workerStatus = finished?.status
                val usage = ((finished?.tokensIn ?: 0L) + (finished?.tokensOut ?: 0L) - initialTokens).coerceAtLeast(0L)
                val outcome = WorkerOutcome(child.id, tasks[workerIndex], workerStatus, assistant?.text.orEmpty(), errors, usage)
                outcomes[child.id] = outcome
                val label = when {
                    outcome.completed -> "Pronto para integração"
                    workerStatus == SessionStatus.STOPPED -> "Cancelado"
                    workerStatus == SessionStatus.WAITING_USER -> "Precisa de intervenção"
                    workerStatus == SessionStatus.ERROR || errors.isNotEmpty() -> "Falhou"
                    else -> "Parcial / não validado"
                }
                val details = "Estado: " + label + " (" + (workerStatus ?: "desconhecido") + "). Uso registrado: " +
                    usage + " tokens. Testes agregados ainda não executados.\n" +
                    (outcome.summary.ifBlank { errors.firstOrNull() ?: "Nenhum relatório final foi produzido." })
                store.replaceItem(sessionId, parentStep.copy(args = label, result = details.take(1800),
                    status = if (outcome.completed) StepStatus.OK else StepStatus.ERROR))
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                store.editSession(child.id) { it.copy(status = SessionStatus.STOPPED) }
                outcomes[child.id] = WorkerOutcome(child.id, tasks[workerIndex], SessionStatus.STOPPED, "",
                    listOf("Subtarefa cancelada."), 0L)
                store.replaceItem(sessionId, parentStep.copy(args = "Cancelado", result = "Subtarefa cancelada.",
                    status = StepStatus.ERROR))
                throw cancel
            } catch (t: Throwable) {
                val message = (t.message ?: t.toString()).take(300)
                store.addItem(child.id, ChatItem.Error(text = "Worker falhou: " + message))
                store.editSession(child.id) { it.copy(status = SessionStatus.ERROR) }
                outcomes[child.id] = WorkerOutcome(child.id, tasks[workerIndex], SessionStatus.ERROR, "", listOf(message), 0L)
                store.replaceItem(sessionId, parentStep.copy(args = "Falhou", result = message, status = StepStatus.ERROR))
            }
        }
    }
    synchronized(childJobsByParent) { childJobsByParent[sessionId] = workerJobs.toMutableList() }
    try {
        workerJobs.joinAll()
    } finally {
        synchronized(childJobsByParent) { childJobsByParent.remove(sessionId) }
    }

    val report = StringBuilder().apply {
        appendLine("Parallel Agent Engine: " + workers.size + " worker(s), orçamento configurado de " + budget + " tokens por worker.")
        appendLine("Conclusão textual não certifica compilação nem testes do projeto agregado.")
        workers.forEachIndexed { index, child ->
            val outcome = outcomes[child.id]
            appendLine()
            appendLine("### Agente " + (index + 1) + " · " + (outcome?.status ?: "desconhecido"))
            appendLine("Tarefa: " + tasks[index])
            appendLine("Resultado: " + (outcome?.summary ?: "Nenhum relatório final foi produzido.").take(1200))
            if (outcome?.errors?.isNotEmpty() == true) appendLine("Erros: " + outcome.errors.joinToString(" | ").take(400))
        }
    }

    var integrationFailures = 0
    var integratedCount = 0
    if (gitRoot != null && baseSha != null) {
        workers.forEachIndexed { index, child ->
            val outcome = outcomes[child.id]
            if (outcome?.completed != true) {
                integrationFailures++
                appendReportLine(report, "Agente " + (index + 1) + ": não integrado automaticamente porque o resultado não terminou sem erros.")
                return@forEachIndexed
            }
            val patchPath = gitRoot.trimEnd('/') + "/.fluwy-worker-" + (index + 1) + ".patch"
            val createPatch = terminal.runAgentCommand(
                "git -C " + shQuote(sandbox.guestPath(child.id)) +
                    " add -N -A >/dev/null 2>&1 || true; git -C " +
                    shQuote(sandbox.guestPath(child.id)) + " diff --binary " + shQuote(baseSha) +
                    " -- > " + shQuote(patchPath) + " && test -s " + shQuote(patchPath),
                30L, false
            )
            if (createPatch.exitCode == 0) {
                val apply = terminal.runAgentCommand(
                    "git -C " + shQuote(gitRoot) + " apply --3way " + shQuote(patchPath),
                    45L, false
                )
                if (apply.exitCode == 0) {
                    terminal.runAgentCommand("rm -f " + shQuote(patchPath), 10L, false)
                    integratedCount++
                    appendReportLine(report, "Agente " + (index + 1) + ": patch aplicado ao workspace principal.")
                } else {
                    integrationFailures++
                    appendReportLine(report, "Agente " + (index + 1) + ": CONFLITO; patch preservado em " + patchPath + ".")
                }
            } else {
                appendReportLine(report, "Agente " + (index + 1) + ": nenhum diff aplicável foi gerado.")
            }
        }
        if (integratedCount > 0) {
            val status = terminal.runAgentCommand("git -C " + shQuote(gitRoot) + " status --short", 15L, false)
            appendReportLine(report, "Workspace após integração: " + status.output.lines().count { it.isNotBlank() } +
                " caminho(s) alterado(s). Testes/build agregados ainda não executados.")
        }
    } else {
        workers.forEachIndexed { index, child ->
            val outcome = outcomes[child.id]
            if (outcome?.completed != true) {
                integrationFailures++
                appendReportLine(report, "Agente " + (index + 1) + ": artefatos não integrados; resultado parcial preservado.")
                return@forEachIndexed
            }
            val merge = runCatching { integrateWorker(baseSnapshot, sandbox.workspaceFor(child.id), sandbox.workspaceFor(sessionId), index + 1) }.getOrNull()
            if (merge == null) { integrationFailures++; appendReportLine(report, "Agente " + (index + 1) + ": integração falhou; revise o workspace."); return@forEachIndexed }
            if (merge.conflicts.isEmpty()) integratedCount++ else integrationFailures++
            appendReportLine(report, "Agente " + (index + 1) + ": " + merge.applied.size + " arquivo(s) aplicado(s) ao projeto principal" +
                (if (merge.applied.isNotEmpty()) " (" + merge.applied.take(12).joinToString() + (if (merge.applied.size > 12) ", …" else "") + ")" else "") +
                (if (merge.deleted.isNotEmpty()) "; " + merge.deleted.size + " removido(s)" else "") +
                (if (merge.conflicts.isNotEmpty()) ". CONFLITO em " + merge.conflicts.take(12).joinToString() +
                    ": outro worker (ou você) alterou o mesmo arquivo. O principal ficou como estava e a versão deste worker está em <arquivo>.worker-" + (index + 1) + " — mescle e apague o .worker." else "."))
        }
    }

    if (gitRoot != null && baseSha != null && integratedCount > 0) {
        val status = terminal.runAgentCommand("git -C " + shQuote(gitRoot) + " status --short", 15L, false)
        appendReportLine(report, "Workspace agregado: " + status.output.lines().count { it.isNotBlank() } + " caminho(s) alterado(s).")
        appendReportLine(report, "Build e testes agregados: ainda não executados por este orquestrador.")
    }
    if (integrationFailures > 0) {
        appendReportLine(report, "ATENÇÃO: " + integrationFailures + " worker(s) precisam de revisão.")
    }

    workers.forEachIndexed { index, child ->
        val outcome = outcomes[child.id]
        val clean = outcome?.completed == true && integrationFailures == 0
        // Encerra os recursos ISOLADOS do subagente (terminal próprio + browser próprio): cada worker
        // roda em modo isolado e, ao terminar, devolve o controle ao agente principal sem deixar
        // sessão de terminal/WebView viva consumindo recursos.
        runCatching { terminals.close(child.id) }
        runCatching { dev.fluwy.app.FluwyApp.instance.closeBrowser(child.id, clearPersistentData = clean) }
        if (clean) {
            if (gitRoot != null && baseSha != null) {
                terminal.runAgentCommand(
                    "git -C " + shQuote(gitRoot) + " worktree remove --force " +
                        shQuote(sandbox.guestPath(child.id)) + " >/dev/null 2>&1 || true; git -C " +
                        shQuote(gitRoot) + " branch -D " + shQuote("fluwy/agents/" + child.id) +
                        " >/dev/null 2>&1 || true",
                    30L, false
                )
            }
            store.deleteSession(child.id)
        } else {
            appendReportLine(report, "Worker " + (index + 1) + " preservado para inspeção: " + child.id +
                " (estado=" + (outcome?.status ?: "desconhecido") + ").")
        }
    }
    return report.toString()
}

internal fun AgentRunner.appendReportLine(report: StringBuilder, line: String) { report.appendLine(line) }

/** Regras do ask_question (fora do runTool, que já está no limite de tamanho do método JVM). */
internal fun AgentRunner.askQuestionGuard(sessionId: String): ToolResult? = run {
            // GUARDRAIL: só existe para uma dúvida real dentro de um pedido do usuário presente —
            // nunca em ciclos autônomos/agendados e no máximo uma vez por mensagem do usuário.
            val sess = store.session(sessionId)
            val items = sess?.items.orEmpty()
            val lastUser = items.indexOfLast { it is ChatItem.User }
            val autonomous = sess?.continuous == true || sess?.llm?.lastOrNull { it.role == "user" }?.content?.startsWith("[Heartbeat") == true
            if (lastUser < 0 || autonomous) return@run ToolResult("RECUSADO: ask_question só pode ser usado quando o usuário pediu uma tarefa e está na conversa. Siga com a suposição mais razoável e diga qual assumiu.")
            // Pedido de entrevista ("faça-me perguntas até entender") libera até 4 rodadas; senão, 1 por pedido.
            val interview = items.filterIsInstance<ChatItem.User>().any { AskTools.wantsInterview(it.text) }
            val rounds = items.drop(lastUser + 1).count { it is ChatItem.Question }
            val pending = items.drop(lastUser + 1).filterIsInstance<ChatItem.Question>().any { it.state == "pending" }
            if (pending) return@run ToolResult("RECUSADO: ainda há perguntas sem resposta. Aguarde o usuário.", SessionStatus.WAITING_USER)
            if (rounds >= (if (interview) 4 else 1)) return@run ToolResult("RECUSADO: você já fez perguntas para este pedido. Use as respostas recebidas (ou uma suposição razoável) e continue a tarefa.")
            null
}

internal fun AgentRunner.shQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"

internal fun AgentRunner.appendTerm(sessionId: String, text: String) {
    val m = _termLog.value
    _termLog.value = m + (sessionId to ((m[sessionId] ?: "") + text).takeLast(60_000))
}

internal fun AgentRunner.finalizeRunningSteps(sessionId: String, reason: String) {
    val steps = store.session(sessionId)?.items?.filterIsInstance<ChatItem.Step>()
        ?.filter { it.status == StepStatus.RUNNING && it.tool != TEAM_MEMBER_STEP }.orEmpty()
    steps.forEach { step ->
        store.replaceItem(sessionId, step.copy(result = reason, status = StepStatus.ERROR))
    }
}

internal fun AgentRunner.setActivity(sessionId: String, a: Activity) {
    _activity.value = _activity.value + (sessionId to a)
}
