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

// ───────── Exército de agentes (build_team): dezenas de agentes no mesmo projeto, ao vivo no browser ─────────
/** wallet_request_transfer (fora do runTool, que está no limite de tamanho de método da JVM). */
internal suspend fun AgentRunner.walletRequestTransfer(sessionId: String, recipient: String, amountSol: String): String {
    return run {
            // Identity comes from the selected local session, never from LLM arguments.
            val agentId = store.session(sessionId)?.agentId
            val policies = WalletAgentPolicyStore(ctx.applicationContext)
            if (agentId.isNullOrBlank() || store.agent(agentId) == null) {
                "Pedido recusado: esta sessão não está vinculada a um agente local válido."
            } else if (!policies.canAgentRequestTransfer(agentId)) {
                "Pedido recusado: o usuário não concedeu a este agente permissão para solicitar transferências."
            } else {
                val networkPrefs = ctx.applicationContext.getSharedPreferences(
                    "solana_wallet_settings", android.content.Context.MODE_PRIVATE
                )
                val networkResult = runCatching {
                    val stored = networkPrefs.getString("network", null)
                        ?: error("A rede da carteira não foi configurada. Abra a tela Carteira e selecione explicitamente Mainnet ou Devnet.")
                    SolanaNetwork.valueOf(stored)
                }
                if (networkResult.isFailure) {
                    "Pedido bloqueado: abra a carteira e selecione Mainnet ou Devnet antes de solicitar uma transferência."
                } else {
                    val network = networkResult.getOrThrow()
                    val wallet = SolanaWalletRepository(ctx.applicationContext)
                    if (!wallet.hasWallet() || !wallet.backupConfirmed()) {
                        "Pedido bloqueado: carteira indisponível ou backup ainda não confirmado."
                    } else {
                        runCatching {
                            val nowMs = System.currentTimeMillis()
                            val start = java.util.Calendar.getInstance().apply {
                                set(java.util.Calendar.HOUR_OF_DAY, 0)
                                set(java.util.Calendar.MINUTE, 0)
                                set(java.util.Calendar.SECOND, 0)
                                set(java.util.Calendar.MILLISECOND, 0)
                            }
                            val windowStart = start.timeInMillis
                            val windowEnd = (start.clone() as java.util.Calendar).apply {
                                add(java.util.Calendar.DAY_OF_YEAR, 1)
                            }.timeInMillis
                            val intent = dev.fluwy.app.wallet.WalletTransferIntent.fromSol(
                                requestId = java.util.UUID.randomUUID().toString().replace("-", ""),
                                agentId = agentId,
                                recipientAddress = recipient.trim(),
                                amountSol = amountSol.trim(),
                                createdAtEpochMs = nowMs,
                                network = network
                            )
                            val policy = policies.transferPolicy(agentId, network)
                            val ledger = dev.fluwy.app.wallet.WalletRequestLedger(ctx.applicationContext)
                            val duplicate = ledger.findRecentDuplicateIntent(intent, nowMs)
                            if (duplicate != null) {
                                "Pedido duplicado recente detectado; nenhum novo pedido criado. ID: ${duplicate.requestId}; estado: ${duplicate.state}. Revise o pedido existente na tela Carteira."
                            } else {
                                when (val claim = ledger.claimAndAuthorize(
                                    intent, policy, windowStart, windowEnd, nowMs
                                )) {
                                    is dev.fluwy.app.wallet.LedgerAuthorizationResult.Created ->
                                        if (claim.record.state == dev.fluwy.app.wallet.LedgerState.AWAITING_APPROVAL)
                                            "Pedido registrado e aguardando aprovação na tela Carteira. ID: ${intent.requestId}; rede: ${network.displayName}; destinatário: ${intent.recipientAddress}; valor: ${String.format(Locale.US, "%.9f SOL", intent.amountLamports / 1_000_000_000.0)}. Nenhuma assinatura ou transmissão foi realizada."
                                        else
                                            // Autonomous grant: the user enabled automatic transfers for this agent,
                                            // so sign + broadcast without manual approval, still bounded by the
                                            // persisted policy (per-tx/daily limits + mandatory allowlist), which the
                                            // signer re-validates. Report only what the network actually returned.
                                            runAutonomousTransfer(intent, policy, ledger, wallet, network, agentId, windowStart, windowEnd, nowMs)
                                    is dev.fluwy.app.wallet.LedgerAuthorizationResult.Rejected ->
                                        "Pedido recusado pela política local: ${claim.decision.reason}"
                                    is dev.fluwy.app.wallet.LedgerAuthorizationResult.Existing ->
                                        "Pedido duplicado detectado; nenhuma nova autorização criada. ID: ${claim.record.requestId}; estado: ${claim.record.state}."
                                    is dev.fluwy.app.wallet.LedgerAuthorizationResult.Approved ->
                                        "Este pedido já foi aprovado anteriormente; nenhuma nova assinatura foi autorizada."
                                }
                            }
                        }.getOrElse { error ->
                            "Pedido de transferência não criado: ${error.message ?: "erro de validação"}"
                        }
                    }
                }
            }
    }
}

/** Agente do exército: o cursor dele no preview segue o elemento que está editando (fora do runTool, que já está no limite da JVM). */
internal fun AgentRunner.squadTrack(sessionId: String, call: ToolCall, title: String, argPreview: String) {
    if (store.session(sessionId)?.squadParentId == null) return
    val (sq, w) = BuildSquad.byWorkerSession(sessionId) ?: return
    val o = runCatching { org.json.JSONObject(call.argsJson) }.getOrNull()
    val edit = call.name == "file_edit" || call.name == "file_write"
    val sel = if (edit && o != null) BuildSquad.selectorFromEdit(o.optString("new_text") + "\n" + o.optString("content")) else null
    val hint = if (edit && o != null) BuildSquad.hintFromPath(o.optString("path")) else ""
    BuildSquad.editWorker(sq.id, w.idx) { it.copy(doing = (title + " " + argPreview).trim().take(60),
        target = sel ?: it.target, hint = hint.ifBlank { it.hint }) }
}

/** Modo exército: o chat líder não edita o projeto direto (só o time). Leitura continua livre. */
internal fun AgentRunner.squadModeGuard(sessionId: String, call: ToolCall): String? {
    val sess = store.session(sessionId) ?: return null
    val proj = sess.squadProject?.trimEnd('/')?.takeIf { sess.squadParentId == null && it.isNotBlank() } ?: return null
    val o = runCatching { org.json.JSONObject(call.argsJson.ifBlank { "{}" }) }.getOrNull() ?: return null
    val msg = "RECUSADO: este chat está no modo exército — alterações no projeto $proj são feitas pelo time. Chame build_team com preview=\"$proj\" " +
        "e uma tarefa específica para esta mudança (ex.: 1 agente). Para sair do modo, o usuário precisa pedir explicitamente."
    return when (call.name) {
        "file_write", "file_edit", "file_delete", "file_move" -> {
            val path = o.optString("path").ifBlank { o.optString("from") }
            val abs = if (path.startsWith("/")) path else "/root/workspace/" + path
            if (abs.startsWith(proj + "/") || abs == proj) msg else null
        }
        "shell" -> {
            val cmd = o.optString("command")
            val touches = cmd.contains(proj) || Regex("""(^|[;&|]\s*)cd\s+\S*${Regex.escape(proj.substringAfterLast('/'))}""").containsMatchIn(cmd)
            val writes = Regex("""(?<![<>])>{1,2}(?!&)|\bsed\s+-i|\btee\b|\brm\s|\bmv\s|\bcp\s|\bnpm\s+(i|install|uninstall|remove)\b|\bnpx\s+\S+\s+(init|create)|\bgit\s+(checkout|reset|apply)|\bpatch\b|\bcat\s*<<""").containsMatchIn(cmd)
            if (touches && writes) msg else null
        }
        else -> null
    }
}

internal fun AgentRunner.toolArgsGuard(call: ToolCall): String? {
    if (call.name != "file_write" && call.name != "file_edit") return null
    val o = runCatching { org.json.JSONObject(call.argsJson.ifBlank { "{}" }) }.getOrNull()
    val cut = "Sua resposta foi cortada pelo limite de saída antes de terminar os argumentos; NADA foi gravado. " +
        "Escreva arquivos grandes em partes: file_write com a 1ª parte (path + content) e depois file_write com append=true para as seguintes " +
        "(cada parte com até ~150 linhas). Não repita a mesma chamada inteira."
    return when {
        o == null || o.length() == 0 -> "ERRO: argumentos vazios. $cut"
        o.optString("path").isBlank() -> "ERRO: informe path. $cut"
        call.name == "file_write" && !o.has("content") -> "ERRO: content ausente — o arquivo NÃO foi gravado (gravá-lo vazio quebraria o projeto). $cut"
        call.name == "file_edit" && (!o.has("old_text") || !o.has("new_text")) -> "ERRO: file_edit precisa de old_text e new_text. $cut"
        else -> null
    }
}

/** Agente do exército: caminho relativo ("src/…") vira o caminho na pasta do projeto do time, antes de executar. */
internal fun AgentRunner.squadFixArgs(sessionId: String, tc: ToolCall): ToolCall {
    if (tc.name !in setOf("file_read", "file_write", "file_edit", "file_list", "file_search", "show_file")) return tc
    val parent = store.session(sessionId)?.squadParentId ?: return tc
    val proj = squadProjectDir[parent]?.trimEnd('/') ?: return tc
    val o = runCatching { org.json.JSONObject(tc.argsJson) }.getOrNull() ?: return tc
    val p = o.optString("path").trim()
    if (p.isBlank() || p.startsWith("/")) return tc
    val root = sandbox.workspaceFor(sessionId)
    if (File(root, p).exists() || p.startsWith(proj.substringAfterLast('/') + "/")) return tc
    o.put("path", proj + "/" + p.removePrefix("./"))
    return tc.copy(argsJson = o.toString())
}

/** Ferramenta workflow_manage (fora do runTool, que está no limite de tamanho da JVM). */
internal suspend fun AgentRunner.workflowTool(argsJson: String): String = runCatching {
    val o = org.json.JSONObject(argsJson)
    val st = dev.fluwy.app.workflow.WorkflowStore
    val logic = dev.fluwy.app.workflow.WfLogic
    val engine = dev.fluwy.app.workflow.WorkflowEngine
    val id = o.optString("id").trim()
    // id com erro de digitação: aceita prefixo único ou nome; a mensagem de erro lista os fluxos existentes
    fun find() = st.get(id) ?: st.flows.value.firstOrNull { it.name.equals(id, true) }
        ?: st.flows.value.filter { id.length >= 4 && (it.id.startsWith(id) || id.startsWith(it.id)) }.singleOrNull()
    val notFound = "ERRO: fluxo não encontrado (id \"$id\"). Fluxos: " + st.flows.value.take(12).joinToString("; ") { "${it.name} = ${it.id}" }.ifBlank { "nenhum" }
    fun describe(w: dev.fluwy.app.workflow.Workflow) = "\"${w.name}\" (id ${w.id}) · ${w.nodes.size} nós · " +
        (if (w.enabled) "publicado" else "rascunho") + (if (w.testedSig.isNotEmpty() && w.testedSig == logic.signature(w)) " · testado" else " · não testado") +
        logic.problems(w).takeIf { it.isNotEmpty() }?.let { "\nAjustes pendentes: " + it.joinToString("; ") }.orEmpty()
    suspend fun validation(w: dev.fluwy.app.workflow.Workflow): List<String> =
        logic.problems(w).filterNot { it.contains("não está ligado a nada") } +
            w.nodes.flatMap { dev.fluwy.app.workflow.WfConnectors.validate(store, it) }
    fun report(r: dev.fluwy.app.workflow.WfRun) = "Status: ${r.status}" + (if (r.error.isNotBlank()) " — ${r.error.take(600)}" else "") + "\n" +
        r.steps.joinToString("\n") { s -> "- ${s.label} [${s.type}, id ${s.nodeId}] ${s.status}" + (if (s.error.isNotBlank()) ": ${s.error.take(400)}" else if (s.output.isNotBlank()) " → " + s.output.replace(Regex("\\s+"), " ").take(350) +
            logic.paths(s.output).takeIf { it.isNotEmpty() }?.let { p -> "\n    caminhos: " + p.take(30).joinToString { "{{${s.nodeId}.$it}}" } }.orEmpty() else "") }
    suspend fun stopActive(w: dev.fluwy.app.workflow.Workflow) {
        st.runsOf(w.id).filter { engine.isActive(it.status) }.forEach { engine.cancel(it.id) }
        repeat(25) { if (engine.isRunning(w.id)) kotlinx.coroutines.delay(200) }
    }
    when (val action = o.optString("action").trim().lowercase()) {
        "list" -> st.flows.value.joinToString("\n") { "- " + describe(it) }.ifBlank { "Nenhum fluxo ainda." } +
            "\n\nConversas do app (para atendente session= / agent): " + store.state.value.sessions.filter { !it.title.startsWith("[Workflow]") }.sortedByDescending { it.updatedAt }.take(15).joinToString(" | ") { it.title.take(60) }.ifBlank { "nenhuma" } +
            "\nAgentes: " + store.state.value.agents.joinToString(" | ") { it.name }.ifBlank { "nenhum" } +
            "\nSe o usuário pediu um fluxo ligado a uma conversa ou agente e não disse qual exatamente, pergunte com ask_question usando ESTES nomes como opções."
        "get" -> find()?.let { kotlinx.serialization.json.Json.encodeToString(dev.fluwy.app.workflow.Workflow.serializer(), it) } ?: notFound
        "create", "update" -> {
            val j = org.json.JSONObject(o.optString("workflow_json").ifBlank { "{}" })
            val base = if (action == "update") (find() ?: return@runCatching notFound) else null
            // Evita fluxos duplicados (com dezenas de fluxos, criar de novo em vez de editar gerava cópias rodando em paralelo).
            if (base == null) j.optString("name").trim().takeIf { it.isNotEmpty() }?.let { nm ->
                st.flows.value.firstOrNull { it.name.equals(nm, true) }?.let { dup ->
                    return@runCatching "ERRO: já existe o fluxo \"${dup.name}\" (id ${dup.id}). Para alterá-lo use action=update id=${dup.id}; para um novo, use outro nome."
                }
            }
            val nodesJ = j.optJSONArray("nodes") ?: org.json.JSONArray()
            val types = dev.fluwy.app.workflow.WfCatalog.TYPES.filter { !it.hidden }.map { it.type }.toSet()
            val nodes = mutableListOf<dev.fluwy.app.workflow.WfNode>()
            for (i in 0 until nodesJ.length()) {
                val n = nodesJ.getJSONObject(i)
                var type = n.optString("type")
                val cfgJ = n.optJSONObject("config") ?: org.json.JSONObject()
                var cfg = cfgJ.keys().asSequence().associateWith { k -> cfgJ.get(k).toString() }
                if (type == "mcp") { type = "connector"; cfg = mapOf("source" to "mcp", "conector" to cfg["conector"].orEmpty(), "acao" to cfg["tool"].orEmpty(), "campos" to cfg["args"].orEmpty().ifBlank { "{}" }) }
                if (type !in types) return@runCatching "ERRO: tipo de nó desconhecido \"$type\". Tipos: ${types.joinToString()}."
                if (type == "connector" && cfg["source"].isNullOrBlank()) cfg = cfg + ("source" to "hub")
                // Agendamento: aceita a frequência escrita como o usuário respondeu ("a cada 2 minutos", "toda segunda 8h").
                if (type == "trigger_schedule") cfg = logic.normalizeSchedule(cfg)
                    ?: return@runCatching "ERRO: não entendi o agendamento do nó ${n.optString("id")}. Use mode=interval (minutes) | daily (time HH:mm) | weekly (weekday 1-7, time) " +
                        "ou when=\"frase do usuário\" (ex.: \"a cada 2 minutos\"). Se a frequência não está clara, pergunte ao usuário com ask_question."
                nodes += dev.fluwy.app.workflow.WfNode(id = n.optString("id").ifBlank { dev.fluwy.app.workflow.WfIds.new() }, type = type, label = n.optString("label"), config = cfg)
            }
            if (nodes.map { it.id }.toSet().size != nodes.size) return@runCatching "ERRO: ids de nós repetidos."
            val edgesJ = j.optJSONArray("edges") ?: org.json.JSONArray()
            val edges = (0 until edgesJ.length()).map { i -> edgesJ.getJSONObject(i).let { e ->
                dev.fluwy.app.workflow.WfEdge(from = e.optString("from"), to = e.optString("to"), port = e.optString("port").ifBlank { "main" }) } }
            var finalNodes = if (nodes.isEmpty() && base != null) base.nodes else nodes
            var autoEdges = emptyList<dev.fluwy.app.workflow.WfEdge>()
            // Sem gatilho: entra um gatilho manual ligado aos nós que não recebem ligação (o usuário pode trocar por agendamento).
            if (finalNodes.isNotEmpty() && finalNodes.none { dev.fluwy.app.workflow.WfCatalog.of(it.type).trigger }) {
                val t = dev.fluwy.app.workflow.WfNode(id = "t0", type = "trigger_manual", label = "Início")
                val targets = (if (edges.isEmpty() && base != null) base.edges else edges).map { it.to }.toSet()
                autoEdges = finalNodes.filter { it.id !in targets }.map { dev.fluwy.app.workflow.WfEdge(from = t.id, to = it.id) }
                finalNodes = listOf(t) + finalNodes
            }
            val ids = finalNodes.map { it.id }.toSet()
            edges.firstOrNull { it.from !in ids || it.to !in ids }?.let { return@runCatching "ERRO: ligação ${it.from} → ${it.to} aponta para nó inexistente. Ids: ${ids.joinToString()}." }
            var w = (base ?: dev.fluwy.app.workflow.Workflow()).copy(name = j.optString("name").ifBlank { base?.name ?: "Novo fluxo" },
                nodes = finalNodes, edges = (if (edges.isEmpty() && base != null) base.edges else edges) + autoEdges, enabled = false)
            w = logic.layout(w)
            if (base != null && base.enabled) dev.fluwy.app.workflow.WorkflowScheduler.cancel(ctx, base.id)
            if (base != null) stopActive(base)  // a versão antiga não continua rodando depois da edição
            val saved = st.upsert(w)
            val v = validation(saved)
            // Gatilho por evento (Mensagem no WhatsApp / webhook): o fluxo já nasce ATIVO, escutando — não fica "manual".
            val event = saved.nodes.any { it.type == "trigger_whatsapp" || it.type == "trigger_webhook" }
            if (v.isEmpty() && event) { val on = st.upsert(saved.copy(enabled = true)); dev.fluwy.app.workflow.WorkflowScheduler.sync(ctx, on) }
            "OK: rascunho salvo — " + describe(saved) + "\n" + (if (v.isEmpty() && event) "Validação: ok. ATIVO: dispara sozinho a cada evento (não precisa publicar nem executar manualmente). Rode action=test id=${saved.id} para conferir o caminho."
                else if (v.isEmpty()) "Validação: ok. Próximo passo: action=test id=${saved.id}."
                else "Validação falhou:\n" + v.joinToString("\n") { "• $it" } + "\nCorrija com action=update (veja conectores com conectores_listar / conector_acoes).")
        }
        "validate" -> find()?.let { w -> validation(w).let { v -> if (v.isEmpty()) "Validação: ok." else "Validação falhou:\n" + v.joinToString("\n") { "• $it" } } } ?: notFound
        "test" -> {
            val w = find() ?: return@runCatching notFound
            val v = validation(w)
            if (v.isNotEmpty()) return@runCatching "ERRO: corrija antes de testar:\n" + v.joinToString("\n") { "• $it" }
            stopActive(w)  // execução antiga (ou teste anterior travado) não impede o novo teste
            val runId = engine.start(w.id, trigger = "test").getOrElse { return@runCatching "ERRO: ${it.message}" }
            val r = engine.awaitRun(runId, 5 * 60_000L) ?: return@runCatching "O teste ainda está rodando (id $runId). Consulte com action=status id=${w.id}."
            if (r.status == "waiting") "O teste está aguardando a aprovação do usuário (nó Aprovação). Peça para ele aprovar em Fluxos › ${w.name} › Execuções; depois consulte action=status."
            else (if (r.status == "ok") "Teste concluído.\n" else "ERRO: o teste do fluxo falhou.\n") + report(r) + if (r.status == "ok") "\nTudo certo: publique com action=publish id=${w.id}." else "\nCorrija com action=update e teste de novo."
        }
        "status" -> {
            val w = find() ?: return@runCatching notFound
            var last = st.runsOf(w.id).firstOrNull() ?: return@runCatching "Nenhuma execução ainda."
            // Ainda rodando: espera até 90 s aqui (não fique chamando status em sequência).
            if (last.status == "running") last = engine.awaitRun(last.id, 90_000L) ?: last
            "Última execução (${last.trigger}):\n" + report(last) + if (last.status == "running") "\nAinda rodando. Se um nó demora demais, cancele com action=update corrigindo-o (ex.: troque agent por ai)." else ""
        }
        "publish" -> {
            val w = find() ?: return@runCatching notFound
            val v = validation(w)
            if (v.isNotEmpty()) return@runCatching "ERRO: corrija antes de publicar:\n" + v.joinToString("\n") { "• $it" }
            stopActive(w)
            // Testa ponta a ponta (leituras reais, envios simulados) e só publica se passar.
            val (okPub, msgPub) = engine.publishTested(ctx, w.id)
            if (!okPub) return@runCatching "ERRO: $msgPub\nCorrija com action=update e publique de novo."
            val p = st.get(w.id) ?: return@runCatching notFound
            val next = dev.fluwy.app.workflow.WorkflowScheduler.nextRuns(p, ctx).minOrNull()
            "OK: testado de ponta a ponta e publicado — " + describe(p) + (next?.let { "\nPróxima execução: " + java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale("pt", "BR")).format(java.util.Date(it)) } ?: "\nSem agendamento: roda quando executado.")
        }
        "run" -> find()?.let { w -> engine.start(w.id).fold({ "Execução iniciada (id $it). Veja o resultado com action=status id=${w.id}." }, { "ERRO: ${it.message}" }) } ?: notFound
        "enable" -> "ERRO: use action=publish (valida e exige teste aprovado antes de ativar)."
        "disable" -> find()?.let { w -> val v = st.upsert(w.copy(enabled = false)); dev.fluwy.app.workflow.WorkflowScheduler.sync(ctx, v); "OK: despublicado — " + describe(v) } ?: notFound
        "delete" -> find()?.let { w -> dev.fluwy.app.workflow.WorkflowScheduler.cancel(ctx, w.id); st.delete(w.id); "OK: fluxo \"${w.name}\" excluído." } ?: notFound
        else -> "ERRO: action inválida."
    }
}.getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it; "ERRO: ${it.message}" }

/** Mata servidores de desenvolvimento órfãos numa porta (o destroy() do proot não derruba o processo filho). */
internal suspend fun AgentRunner.killPort(port: Int, sessionId: String?) {
    val pats = listOf("[v]ite.*--port $port", "[n]ext dev -p $port", "[r]eact-scripts start", "PORT=$port").filterNot { it == "[r]eact-scripts start" }
    sandbox.exec(pats.joinToString(" ; ") { "pkill -f '$it'" } + " ; fuser -k $port/tcp 2>/dev/null ; sleep 0.5 ; " + pats.joinToString(" ; ") { "pkill -9 -f '$it'" } + " ; true",
        cwd = "/tmp", timeoutSec = 20, sessionId = sessionId)
}

/** Status HTTP e começo da resposta do preview (0 = sem resposta). */
internal fun AgentRunner.previewStatus(url: String, readTimeoutMs: Int = 3_000): Pair<Int, String> = runCatching {
    val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
    c.connectTimeout = 1_500; c.readTimeout = readTimeoutMs; c.instanceFollowRedirects = true
    try {
        val code = c.responseCode
        // Só os primeiros 400 caracteres (readText() baixava o módulo inteiro e podia levar minutos).
        val body = runCatching { (if (code >= 400) c.errorStream else c.inputStream)?.bufferedReader()?.use { rd -> val b = CharArray(400); val n = rd.read(b); if (n > 0) String(b, 0, n) else "" } }.getOrNull().orEmpty()
        code to body
    } finally { c.disconnect() }
}.getOrDefault(0 to "")

internal fun AgentRunner.portAlive(port: Int): Boolean = runCatching {
    java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", port), 500); true }
}.getOrDefault(false)

/**
 * Config do Vite que herda a do projeto e liga o polling do watcher (HMR ao vivo no proot), sem mexer no arquivo
 * do usuário. Vite empacota a config (e o import da original, inclusive .ts) antes de rodar. null = usa a do projeto.
 */
internal fun AgentRunner.viteWatchConfig(dir: File, addTailwind: Boolean = false): String? {
    val base = listOf("vite.config.ts", "vite.config.mts", "vite.config.js", "vite.config.mjs", "vite.config.cjs").firstOrNull { File(dir, it).isFile }
    val wrapper = File(dir, ".fluwy.vite.config.mjs")
    val importBase = base?.let { "import base from './$it'\n" } ?: ""
    return runCatching {
        wrapper.writeText(importBase + "import { mergeConfig } from 'vite'\n" +
            (if (addTailwind) "import tailwindcss from '@tailwindcss/vite'\n" else "") +
            "const over = { " + (if (addTailwind) "plugins: [tailwindcss()], " else "") +
            // .fluwy/ (plano do time, build de verificação) fora do watcher: gravar ali recarregava a página em loop.
            "server: { host: '127.0.0.1', watch: { usePolling: true, interval: 250, ignored: ['**/.fluwy/**', '**/.fluwy.vite.config.mjs', '**/node_modules/**', '**/.git/**'] } } }\n" +
            (if (base != null) "export default async (env) => mergeConfig(typeof base === 'function' ? await base(env) : (base || {}), over)\n"
                else "export default over\n"))
        wrapper.name
    }.getOrNull()
}

/** Imports default do App (./components/X) que apontam para arquivo ausente, vazio ou sem export default. */
internal fun AgentRunner.brokenImports(dir: File): List<String> {
    val app = listOf("src/App.tsx", "src/App.jsx", "src/App.js").map { File(dir, it) }.firstOrNull { it.isFile } ?: return emptyList()
    val text = app.readText()
    return Regex("""import\s+([A-Z]\w*)\s+from\s+['"](\.{1,2}/[^'"]+)['"]""").findAll(text).mapNotNull { m ->
        val rel = m.groupValues[2]
        val base = File(app.parentFile, rel).normalize()
        val f = listOf("", ".jsx", ".tsx", ".js", ".ts", "/index.jsx", "/index.tsx", "/index.js").map { File(base.path + it) }.firstOrNull { it.isFile }
        when {
            f == null -> "${m.groupValues[1]} ($rel: arquivo não existe)"
            f.length() == 0L -> "${m.groupValues[1]} (${f.name}: arquivo vazio)"
            !Regex("""export\s+default\b""").containsMatchIn(f.readText()) -> "${m.groupValues[1]} (${f.name}: sem export default)"
            else -> null
        }
    }.toList()
}

/** Âncoras internas (href="#x") sem elemento com id="x" no código do projeto. Devolve (quebradas, ids existentes). */
internal fun AgentRunner.brokenAnchors(dir: File): Pair<List<String>, List<String>> {
    val files = (listOf(File(dir, "index.html")) + File(dir, "src").walkTopDown().filter { it.isFile && it.extension in setOf("jsx", "tsx", "js", "ts", "html", "vue", "svelte") }.take(400).toList())
        .filter { it.isFile }
    val text = files.joinToString("\n") { runCatching { it.readText() }.getOrDefault("") }
    val hrefs = Regex("""href\s*=\s*\{?\s*["'`]#([A-Za-z][\w-]*)["'`]""").findAll(text).map { it.groupValues[1] }.toSet()
    val ids = (Regex("""\bid\s*=\s*\{?\s*["'`]([A-Za-z][\w-]*)["'`]""").findAll(text) + Regex("""\bid\s*:\s*["'`]([A-Za-z][\w-]*)["'`]""").findAll(text))
        .map { it.groupValues[1] }.toSet()
    // Menus montados de uma lista ({ id: 'servicos' } / href={`#${s.id}`}) contam como ids conhecidos.
    return hrefs.filter { it !in ids && it != "top" }.sorted() to ids.sorted()
}

internal fun AgentRunner.pascal(raw: String): String = raw.split(Regex("[^A-Za-z0-9]+")).filter { it.isNotBlank() }
    .joinToString("") { it.replaceFirstChar { c -> c.uppercase() } }.takeIf { it.isNotBlank() && it[0].isLetter() } ?: ""
internal fun AgentRunner.kebab(name: String) = name.replace(Regex("([a-z0-9])([A-Z])"), "$1-$2").lowercase()

/**
 * Tailwind pedido (ou já no projeto): instala se faltar, liga o plugin do Vite (pela config do app, sem mexer na do
 * usuário) e garante @import "tailwindcss" no CSS de entrada importado pelo main. Sem isso as classes não estilizam nada.
 * Devolve true se o plugin do Vite precisa entrar pela config do app.
 */
internal suspend fun AgentRunner.ensureTailwind(sessionId: String, dir: File, guestDir: String, wanted: Boolean, log: (String) -> Unit): Boolean {
    val pkgFile = File(dir, "package.json")
    var pkg = runCatching { pkgFile.readText() }.getOrDefault("")
    val has = pkg.contains("\"tailwindcss\"")
    if (!has && !wanted) return false
    val v3 = Regex("\"tailwindcss\"\\s*:\\s*\"[\\^~>=]*3").containsMatchIn(pkg)
    val needs = when {
        !has -> "tailwindcss@^4 @tailwindcss/vite@^4"
        !v3 && !pkg.contains("@tailwindcss/vite") && !pkg.contains("@tailwindcss/postcss") && pkg.contains("\"vite\"") -> "@tailwindcss/vite@^4"
        else -> null
    }
    if (needs != null) {
        log("Ligando o Tailwind CSS ($needs)…")
        val r = sandbox.exec(dev.fluwy.app.sandbox.InteractivePrompts.ENV + " && npm install -D --no-audit --no-fund --loglevel=error $needs < /dev/null",
            cwd = guestDir, timeoutSec = 600, idleTimeoutSec = 240, sessionId = sessionId)
        if (r.exitCode != 0) { log("Não consegui instalar o Tailwind: " + r.output.takeLast(300)); return false }
        pkg = runCatching { pkgFile.readText() }.getOrDefault(pkg)
    }
    // CSS de entrada: o importado pelo main (ou um novo src/index.css importado no topo do main).
    val main = listOf("src/main.tsx", "src/main.jsx", "src/main.ts", "src/main.js", "src/index.tsx", "src/index.jsx").map { File(dir, it) }.firstOrNull { it.isFile }
    if (main != null) {
        val mainText = main.readText()
        val cssRel = Regex("""import\s+['"](\./[^'"]+\.css)['"]""").find(mainText)?.groupValues?.get(1)
        val css = if (cssRel != null) File(main.parentFile, cssRel.removePrefix("./")) else File(dir, "src/index.css")
        if (cssRel == null) main.writeText("import './" + css.relativeTo(main.parentFile).path + "'\n" + mainText)
        val text = if (css.isFile) css.readText() else ""
        val isV3 = v3 && !pkg.contains("@tailwindcss/")
        val fixed = if (isV3) {
            if (text.contains("@tailwind base")) text else "@tailwind base;\n@tailwind components;\n@tailwind utilities;\n\n$text"
        } else {
            // Tema padrão do create-vite (body centralizado, cores escuras) atrapalha o layout: sai junto.
            val base = if (text.contains("place-items: center") && text.contains(":root")) "" else text
            val noOld = base.lines().filterNot { it.trim().startsWith("@tailwind ") }.joinToString("\n")
            if (Regex("""@import\s+['"]tailwindcss['"]""").containsMatchIn(noOld)) noOld else "@import \"tailwindcss\";\n\n$noOld"
        }
        if (fixed != text) { css.parentFile?.mkdirs(); css.writeText(fixed) }
    }
    val viteCfg = listOf("vite.config.ts", "vite.config.mts", "vite.config.js", "vite.config.mjs").map { File(dir, it) }.firstOrNull { it.isFile }?.readText().orEmpty()
    return !v3 && pkg.contains("@tailwindcss/vite") && !viteCfg.contains("@tailwindcss/vite")
}

/**
 * Antes do time: cada componente do plano já existe (esqueleto visível) e já está no App. Assim o usuário vê cada seção
 * sendo construída no lugar dela desde o primeiro salvamento — sem isso a tela ficava branca até o App ser editado no fim.
 * Devolve tarefa → caminho do arquivo do componente (no sandbox).
 */
internal fun AgentRunner.wireComponents(dir: File, guestDir: String, tasks: List<SquadTask>, log: (String) -> Unit): Map<String, Pair<String, String>> {
    val pkg = runCatching { File(dir, "package.json").readText() }.getOrDefault("")
    if (!pkg.contains("\"react\"")) return emptyMap()
    val ts = File(dir, "src/main.tsx").isFile || File(dir, "src/App.tsx").isFile
    val ext = if (ts) "tsx" else "jsx"
    val out = linkedMapOf<String, Pair<String, File>>()  // taskId → (Nome, arquivo)
    val ids = mutableMapOf<String, String>()  // Nome → id da seção (o do alvo do líder, ex.: #inicio; senão o nome)
    tasks.forEach { t ->
        val fromFile = Regex("""(?:^|/)(?:components|sections|pages)/([A-Za-z][A-Za-z0-9]*)\.(?:jsx|tsx|js|ts)$""").find(t.file)?.groupValues?.get(1)
        // Só tarefas que SÃO um componente: arquivo em components/sections/pages, ou sem arquivo mas com alvo #id.
        // (Antes um arquivo de dados como src/data/news.js com alvo #noticias virava "componente" e a tarefa era
        // reprovada por não ter export default; tarefas com alvo #root ganhavam um Root.jsx fantasma.)
        if (t.file.isNotBlank() && fromFile == null) return@forEach
        val name = fromFile ?: t.target.trim().split(Regex("\\s+")).firstOrNull()?.takeIf { it.startsWith("#") || it.startsWith(".") }?.let { pascal(it.drop(1)) }.orEmpty()
        if (name.isBlank() || name in setOf("App", "Main", "Root", "Body", "Html", "Index")) return@forEach
        val f = when {
            t.file.startsWith("/") -> File(dir, t.file.substringAfter(guestDir.trimEnd('/') + "/", "src/components/$name.$ext"))
            t.file.isNotBlank() -> File(dir, t.file.removePrefix("./"))
            else -> File(dir, "src/components/$name.$ext")
        }
        if (out.values.none { it.first == name }) {
            out[t.id] = name to f
            ids[name] = t.target.trim().split(Regex("\\s+")).firstOrNull()?.takeIf { it.startsWith("#") && it.length > 1 }
                ?.drop(1)?.takeIf { Regex("^[A-Za-z][\\w-]*$").matches(it) } ?: kebab(name)
        }
    }
    if (out.isEmpty()) return emptyMap()
    // Ordem natural da página: menu/cabeçalho em cima, rodapé/contato final embaixo (antes o Footer podia cair no meio).
    fun rank(n: String) = when {
        Regex("(?i)^(nav|navbar|header|cabecalho|menu|topbar)").containsMatchIn(n) -> 0
        Regex("(?i)(footer|rodape)").containsMatchIn(n) -> 2
        else -> 1
    }
    val ordered = out.entries.sortedBy { rank(it.value.first) }.associate { it.key to it.value }
    out.clear(); out.putAll(ordered)
    out.values.forEach { (name, f) ->
        if (!f.exists()) {
            f.parentFile?.mkdirs()
            val sid = ids[name] ?: kebab(name)
            f.writeText("export default function $name() {\n  return (\n    <section id=\"$sid\" className=\"${kebab(name)} fluwy-placeholder\"\n" +
                "      style={{ padding: '40px 24px', margin: '12px', border: '2px dashed #c9c9c9', borderRadius: 12, textAlign: 'center', color: '#888', fontFamily: 'system-ui' }}>\n" +
                "      $name · em construção…\n    </section>\n  )\n}\n")
        }
    }
    val app = listOf("src/App.tsx", "src/App.jsx", "src/App.js").map { File(dir, it) }.firstOrNull { it.isFile } ?: File(dir, "src/App.$ext")
    val appText = if (app.isFile) app.readText() else ""
    val wired = out.values.count { (name, _) -> Regex("""import\s+$name\b""").containsMatchIn(appText) }
    // Projeto já montado (edições seguintes): nunca reescreve o App que tem seções próprias; componente novo é ligado pela tarefa.
    val appHasOwnSections = Regex("""import\s+\w+\s+from\s+['"]\./(components|sections|pages)/""").containsMatchIn(appText)
    if (wired == 0 && appHasOwnSections) {
        log("Projeto já existente: o App não foi reescrito; componentes novos (${out.values.joinToString { it.first }}) entram no App pela própria tarefa.")
    } else if (wired == 0) {
        val imports = out.values.joinToString("") { (name, f) ->
            val rel = f.relativeTo(app.parentFile).path.substringBeforeLast('.').let { if (it.startsWith(".")) it else "./$it" }
            "import $name from '$rel'\n"
        }
        app.parentFile?.mkdirs()
        app.writeText(imports + "\nexport default function App() {\n  return (\n    <div className=\"min-h-screen\">\n" +
            out.values.joinToString("") { "      <${it.first} />\n" } + "    </div>\n  )\n}\n")
        log("App ligado a ${out.size} componentes antes do time entrar (cada seção aparece no lugar dela).")
    } else if (wired < out.size) log("O App já importa $wired de ${out.size} componentes; os demais precisam ser ligados pelo líder.")
    return out.mapValues { (_, v) -> (guestDir.trimEnd('/') + "/" + v.second.relativeTo(dir).path) to (ids[v.first] ?: kebab(v.first)) }
}

/** Projeto React + Vite em branco que já roda (o time constrói em cima dele). */
internal fun AgentRunner.scaffoldReactVite(dir: File) {
    dir.mkdirs()
    fun w(path: String, text: String) { val f = File(dir, path); if (!f.exists()) { f.parentFile?.mkdirs(); f.writeText(text.trimIndent() + "\n") } }
    w("package.json", """
        {
          "name": "app", "private": true, "version": "0.0.0", "type": "module",
          "scripts": { "dev": "vite", "build": "vite build", "preview": "vite preview" },
          "dependencies": { "react": "^18.3.1", "react-dom": "^18.3.1" },
          "devDependencies": { "@vitejs/plugin-react": "^4.3.4", "vite": "^5.4.11", "tailwindcss": "^4.1.0", "@tailwindcss/vite": "^4.1.0" }
        }
    """)
    w("vite.config.js", """
        import { defineConfig } from 'vite'
        import react from '@vitejs/plugin-react'
        import tailwindcss from '@tailwindcss/vite'
        export default defineConfig({ plugins: [react(), tailwindcss()], server: { host: '127.0.0.1', watch: { usePolling: true, interval: 250 } } })
    """)
    w("index.html", """
        <!doctype html>
        <html lang="pt-BR">
          <head><meta charset="UTF-8" /><meta name="viewport" content="width=device-width, initial-scale=1.0" /><title>App</title></head>
          <body><div id="root"></div><script type="module" src="/src/main.jsx"></script></body>
        </html>
    """)
    w("src/main.jsx", """
        import React from 'react'
        import ReactDOM from 'react-dom/client'
        import App from './App.jsx'
        import './index.css'
        ReactDOM.createRoot(document.getElementById('root')).render(<App />)
    """)
    w("src/App.jsx", """
        export default function App() {
          return (
            <main className="app">
              <section id="hero" className="hero"><h1>Em construção…</h1></section>
            </main>
          )
        }
    """)
    w("src/index.css", """
        @import "tailwindcss";
        body { margin: 0; font-family: system-ui, sans-serif; }
    """)
}

/**
 * Prepara o app AO VIVO antes do time entrar: pasta vazia vira um projeto React + Vite em branco; com package.json,
 * instala as dependências e sobe o servidor de desenvolvimento (http://127.0.0.1:PORTA, com HMR). Site estático
 * (index.html sem package.json): file://. URL já rodando: usada como está. null = não ficou de pé (ver log).
 */
internal suspend fun AgentRunner.squadPreviewUrl(sessionId: String, preview: String, log: (String) -> Unit, wantTailwind: Boolean = false): String? {
    val p = preview.trim()
    if (p.isBlank()) return null
    if (p.startsWith("http://") || p.startsWith("https://")) return p
    // /public/workspace e caminhos relativos viram /root/workspace/… (o mesmo lugar que o agente e o time usam).
    var guest = p.removePrefix("file://").trimEnd('/').let {
        when {
            it.startsWith("/public/") || it == "/public" -> "/root" + it.removePrefix("/public")
            it.startsWith("/home/") -> "/root" + it.removePrefix("/home")
            it.startsWith("/") -> it
            else -> "/root/workspace/" + it.removePrefix("./")
        }
    }
    var f = sandbox.hostFile(guest, sessionId)
    val isFile = f.isFile || (!f.exists() && f.name.contains('.'))
    if (!isFile && !File(f, "package.json").isFile && !File(f, "index.html").isFile && f.isDirectory) {
        // Preview apontou para a pasta de cima (ex.: /root/workspace) e o projeto está numa subpasta: usa a subpasta.
        val subs = f.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") && it.name != "node_modules" && File(it, "package.json").isFile }.orEmpty()
        if (subs.size == 1) { f = subs[0]; guest = guest.trimEnd('/') + "/" + subs[0].name; log("Projeto encontrado em $guest.") }
    }
    val dir = if (isFile) f.parentFile else f
    val guestDir = if (isFile) guest.substringBeforeLast('/') else guest
    squadProjectDir[sessionId] = guestDir
    if (store.session(sessionId)?.squadProject != guestDir) store.editSession(sessionId) { it.copy(squadProject = guestDir) }
    val pkg = File(dir, "package.json")
    val staticSite = !pkg.isFile && (File(dir, "index.html").isFile || isFile)
    if (staticSite) return "file://" + (if (isFile) f else File(dir, "index.html")).absolutePath
    if (!pkg.isFile) {
        // Nunca cria o projeto solto na raiz do workspace (misturaria com outros arquivos): usa /root/workspace/app.
        if (guestDir.trimEnd('/') == "/root/workspace" && dir.listFiles()?.any { !it.name.startsWith(".") } == true) {
            log("A raiz do workspace já tem outros arquivos; o projeto vai em /root/workspace/app.")
            return squadPreviewUrl(sessionId, "/root/workspace/app", log, wantTailwind)
        }
        log("Criando o projeto React + Vite em branco em $guestDir…"); scaffoldReactVite(dir)
    }
    val text = runCatching { pkg.readText() }.getOrDefault("")
    if (text.contains("\"vite\"") && !File(dir, "index.html").isFile) {
        log("O projeto Vite não tinha index.html na raiz; criei um padrão (div#root + /src/main.jsx).")
        File(dir, "index.html").writeText("<!doctype html>\n<html lang=\"pt-BR\"><head><meta charset=\"UTF-8\" /><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\" /><title>App</title></head>\n" +
            "<body><div id=\"root\"></div><script type=\"module\" src=\"/src/main.jsx\"></script></body></html>\n")
    }
    val port = 5173 + (guestDir.hashCode() and 0x7fffffff) % 300
    squadDevServers[sessionId]?.let { (pt, proc) -> if (pt == port && proc.isAlive && previewStatus("http://127.0.0.1:$port/").first == 200) return "http://127.0.0.1:$port/" }
    squadDevServers.remove(sessionId)?.second?.destroy()
    val q = "'" + guestDir.replace("'", "") + "'"
    // 1) Dependências: só segue com tudo instalado (erro aqui não liga o time).
    if (!File(dir, "node_modules").isDirectory) {
        log("Instalando as dependências (npm install)…")
        val r = sandbox.exec("(command -v npm >/dev/null || apk add --no-cache nodejs npm >/dev/null 2>&1) && " + dev.fluwy.app.sandbox.InteractivePrompts.ENV +
            " && npm install --no-audit --no-fund --loglevel=error < /dev/null",
            cwd = guestDir, timeoutSec = 900, idleTimeoutSec = 300, sessionId = sessionId)
        if (r.exitCode != 0 || !File(dir, "node_modules").isDirectory) { log("npm install falhou: " + r.output.takeLast(600)); return null }
    }
    // 2) Servidor de desenvolvimento.
    val tailwindPlugin = runCatching { ensureTailwind(sessionId, dir, guestDir, wantTailwind, log) }.getOrDefault(false)
    // API Node (Express, Fastify, Koa, Hono, Nest…) sem front-end: sobe com PORT e vale qualquer resposta HTTP.
    val isApi = !Regex("\"(vite|next|react-scripts)\"").containsMatchIn(text) &&
        Regex("\"(express|fastify|koa|hono|@nestjs/core|@hapi/hapi|restify|polka)\"").containsMatchIn(text)
    if (isApi) squadApi.add(sessionId) else squadApi.remove(sessionId)
    val scripts = runCatching { org.json.JSONObject(text).optJSONObject("scripts") }.getOrNull()
    val main = runCatching { org.json.JSONObject(text).optString("main") }.getOrDefault("").ifBlank {
        listOf("server.js", "index.js", "src/server.js", "src/index.js", "app.js", "src/app.js").firstOrNull { File(dir, it).isFile } ?: "index.js" }
    val run = when {
        isApi && scripts?.has("dev") == true -> "PORT=$port HOST=127.0.0.1 npm run dev"
        isApi && scripts?.has("start") == true -> "PORT=$port HOST=127.0.0.1 npm start"
        isApi -> "PORT=$port HOST=127.0.0.1 node $main"
        text.contains("\"next\"") -> "npx next dev -p $port -H 127.0.0.1"
        text.contains("\"vite\"") -> "npx vite --host 127.0.0.1 --port $port --strictPort" + (viteWatchConfig(dir, tailwindPlugin)?.let { " --config $it" } ?: "")
        text.contains("react-scripts") -> "BROWSER=none HOST=127.0.0.1 PORT=$port npx react-scripts start"
        else -> "HOST=127.0.0.1 PORT=$port npm run dev -- --host 127.0.0.1 --port $port"
    }
    log("Subindo o app na porta $port…")
    val logFile = File(sandbox.workspaceFor(sessionId), ".fluwy/devserver.log").apply { parentFile?.mkdirs(); delete() }
    // Polling: no Linux do app (proot) o watcher de arquivos do Vite/Next/CRA não vê as edições, e o HMR não atualiza a tela.
    // Servidor antigo (de outra rodada ou de antes do app reiniciar) segurando a porta: com --strictPort o novo morria
    // na hora e o app via "HTTP 0", enquanto um wget depois achava o antigo de pé. Derruba o antigo e espera a porta liberar.
    if (portAlive(port)) {
        killPort(port, sessionId)
        repeat(20) { if (portAlive(port)) kotlinx.coroutines.delay(300) }
    }
    // `env VAR=x cmd`: `exec VAR=x cmd` tentava executar "VAR=x" como programa (API Node e CRA nunca subiam).
    val proc = sandbox.spawn("cd $q && exec env $run < /dev/null", logFile, sessionId = sessionId,
        extraEnv = mapOf("CHOKIDAR_USEPOLLING" to "true", "CHOKIDAR_INTERVAL" to "250", "WATCHPACK_POLLING" to "true", "BROWSER" to "none"))
    squadDevServers[sessionId] = port to proc
    val url = "http://127.0.0.1:$port/"
    val deadline = System.currentTimeMillis() + 4 * 60_000L
    fun up(c: Int) = if (isApi) c in 100..499 else c == 200
    // Espera de verdade: a 1ª resposta do Vite (pré-empacotamento) pode levar dezenas de segundos no celular.
    // Só desiste antes do prazo se o processo morreu E a porta não abriu.
    var code = 0
    var body = ""
    while (System.currentTimeMillis() < deadline) {
        val r = if (portAlive(port)) previewStatus(url, 30_000) else (0 to "")
        code = r.first; body = r.second
        if (up(code)) break
        if (!proc.isAlive && !portAlive(port)) { kotlinx.coroutines.delay(1_500); if (!portAlive(port)) break }
        kotlinx.coroutines.delay(1_000)
    }
    if (up(code)) log("App respondeu (HTTP $code) na porta $port.")
    if (!up(code)) {
        log("O app não respondeu 200 (HTTP $code) ${body.take(200)}\nLog do servidor: " + runCatching { logFile.readText().takeLast(600) }.getOrDefault(""))
        return null
    }
    // 3) O código de entrada compila (Vite responde 500 se não).
    if (text.contains("\"vite\"")) {
        val entry = Regex("""src=["'](/[^"']+\.(?:jsx?|tsx?))["']""").find(runCatching { File(dir, "index.html").readText() }.getOrDefault(""))?.groupValues?.get(1)
        if (entry != null) {
            var res = previewStatus(url.trimEnd('/') + entry, 90_000)
            repeat(2) { if (res.first == 0) { kotlinx.coroutines.delay(2_000); res = previewStatus(url.trimEnd('/') + entry, 90_000) } }
            val (ec, eb) = res
            if (ec != 200) { log("$entry não compila (HTTP $ec): ${eb.take(300)}"); return null }
        }
    }
    return url
}

/**
 * Aquece o dev server antes do browser: pede o código de entrada e os módulos que ele importa (App, componentes e as
 * dependências pré-empacotadas do Vite). Sem isso a 1ª carga no WebView travava (só onPageStarted por 30 s) enquanto o
 * Vite empacotava o React, e no celular com pouca memória o renderer chegava a ser encerrado.
 */
internal fun AgentRunner.warmDevServer(url: String, html: String, progress: (Int) -> Unit = {}) {
    val base = url.trimEnd('/')
    val seen = mutableSetOf<String>()
    val queue = ArrayDeque(Regex("""src=["'](/[^"']+)["']""").findAll(html).map { it.groupValues[1] }.toList() + "/@vite/client")
    // Teto TOTAL de 75 s: cada pedido usa no máximo o tempo que sobra (antes uma leitura sem limite travava o time aqui para sempre).
    val deadline = System.currentTimeMillis() + 75_000
    while (queue.isNotEmpty() && seen.size < 60) {
        val left = (deadline - System.currentTimeMillis()).toInt()
        if (left < 1_000) break
        val path = queue.removeFirst().substringBefore('?')
        if (!seen.add(path)) continue
        if (seen.size % 5 == 0) progress(seen.size)
        val body = readCapped(base + path, minOf(25_000, left), deadline)
        Regex("""(?:from\s*|import\s*\(?\s*)["'](/(?:src|node_modules/\.vite/deps)/[^"'?]+)""").findAll(body).forEach { queue.add(it.groupValues[1]) }
    }
}

/**
 * GET que nunca trava: além do readTimeout (que é por leitura e reinicia a cada pedaço — um servidor que "pinga" bytes
 * devagar segurava a leitura por minutos), lê em blocos e para no [deadline] ou em 3 MB.
 */
internal fun AgentRunner.readCapped(url: String, readMs: Int, deadline: Long): String = runCatching {
    (java.net.URL(url).openConnection() as java.net.HttpURLConnection).run {
        connectTimeout = 1_500; readTimeout = readMs.coerceAtLeast(500)
        try {
            if (responseCode !in 200..299) return@run ""
            val sb = StringBuilder(); val buf = CharArray(16_384)
            inputStream.bufferedReader().use { rd ->
                while (System.currentTimeMillis() < deadline && sb.length < 3_000_000) { val n = rd.read(buf); if (n < 0) break; sb.append(buf, 0, n) }
            }
            sb.toString()
        } finally { disconnect() }
    }
}.getOrDefault("")

internal fun AgentRunner.fetchText(url: String, readMs: Int): String = readCapped(url, readMs, System.currentTimeMillis() + readMs)

/** O App compila no dev server? (Vite: GET /src/App.jsx|tsx = 200). null = não dá para saber. */
internal fun AgentRunner.appModuleOk(url: String): Boolean? {
    val base = url.trimEnd('/')
    for (p in listOf("/src/App.jsx", "/src/App.tsx", "/src/App.js")) {
        val c = previewStatus(base + p, 60_000).first
        if (c == 200) return true
        if (c in 500..599) return false
    }
    return null
}

/** O app precisa estar VIVO no browser (página carregada e desenhando algo) antes de o time entrar. */
internal suspend fun AgentRunner.appAliveInBrowser(sessionId: String, url: String, log: (String) -> Unit): Boolean {
    val browser = browserFor(sessionId)
    if (sessionId !in squadApi) runCatching {
        log("Aquecendo o app (pré-carregando React e componentes)…")
        // Em paralelo e com espera limitada: mesmo que uma leitura bloqueie, o time segue em até 90 s.
        val warm = kotlinx.coroutines.CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob()).async {
            warmDevServer(url, fetchText(url, 20_000)) { n -> log("Aquecendo o app… $n módulos prontos") }
        }
        if (kotlinx.coroutines.withTimeoutOrNull(90_000) { warm.await() } == null) { warm.cancel(); log("Aquecimento passou de 90 s; seguindo para abrir o app.") }
    }
    // Motor do browser caiu 2+ vezes há pouco: não insiste em carregar/inspecionar (isso derrubava de novo); servidor OK basta.
    if (sessionId !in squadApi && browser.liteMode() && previewStatus(url, 15_000).first == 200 && appModuleOk(url) != false) {
        log("O browser do celular está instável (encerrado pelo Android há pouco); o time começa com o app no ar e a prévia abre quando o browser estabilizar.")
        kotlinx.coroutines.withTimeoutOrNull(20_000) { runCatching { browser.navigate(url) } }
        return true
    }
    // Navegar nunca pode prender o time: o WebView do celular às vezes trava/renasce (renderer encerrado).
    kotlinx.coroutines.withTimeoutOrNull(40_000) { runCatching { browser.navigate(url) } }
    if (sessionId in squadApi) return previewStatus(url).first in 100..499  // API: responder já basta
    val deadline = System.currentTimeMillis() + 120_000  // 1ª compilação no celular pode passar de 1 min
    var last = ""
    var nextNav = System.currentTimeMillis() + 20_000
    var jsFails = 0
    while (System.currentTimeMillis() < deadline) {
        if (System.currentTimeMillis() > nextNav && !last.contains("ok")) { kotlinx.coroutines.withTimeoutOrNull(30_000) { runCatching { browser.navigate(url) } }; nextNav = System.currentTimeMillis() + 20_000 }
        last = runCatching { browser.runJs(
            "(function(){var t=document.title||'';if(/não disponível|not available|ERR_/i.test(t+document.body.innerText.slice(0,200)))return 'erro';" +
                "var r=document.getElementById('root')||document.getElementById('__next')||document.getElementById('app')||document.body;" +
                "return (r&&r.children.length>0&&(r.innerText||'').trim().length+r.querySelectorAll('img,svg,canvas').length>0)?'ok':'vazio';})()") }.getOrDefault("")
        if (last.contains("ok")) return true
        // WebView sem responder JS (renderer ocupado/encerrado por memória) mas servidor e código OK: não barra o time por isso.
        if (last.startsWith("timeout") || last.startsWith("ERROR")) {
            jsFails++
            if (jsFails == 3) kotlinx.coroutines.withTimeoutOrNull(30_000) { runCatching { browser.navigate(url) } }
            if (jsFails >= 5 && previewStatus(url, 15_000).first == 200 && (appModuleOk(url) != false)) {
                log("O browser do celular demorou a responder, mas o app está no ar e compilando (HTTP 200); o time vai começar.")
                return true
            }
        } else jsFails = 0
        if (last.contains("erro")) { kotlinx.coroutines.delay(1_500); kotlinx.coroutines.withTimeoutOrNull(30_000) { runCatching { browser.navigate(url) } } }
        kotlinx.coroutines.delay(1_500)
    }
    // Servidor no ar e App compilando: o problema é o WebView do celular (lento/encerrado), não o projeto. O time começa
    // mesmo assim (antes ficava barrado e o pedido morria aqui).
    if (previewStatus(url, 15_000).first == 200 && appModuleOk(url) != false) {
        log("O browser do celular não respondeu a tempo, mas o app está no ar e compilando (HTTP 200); o time vai começar (preview pode demorar a aparecer).")
        return true
    }
    log("O app não apareceu no browser em 2 min (estado: ${last.ifBlank { "sem resposta" }}). Log do servidor: " +
        runCatching { File(sandbox.workspaceFor(sessionId), ".fluwy/devserver.log").readText().takeLast(500) }.getOrDefault(""))
    return false
}
