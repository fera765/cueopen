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
