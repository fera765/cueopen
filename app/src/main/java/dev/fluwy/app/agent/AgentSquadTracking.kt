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
