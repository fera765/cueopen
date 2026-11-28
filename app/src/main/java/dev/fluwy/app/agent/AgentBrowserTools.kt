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

/** Browser: navegação, leitura, interação, rede e console. Devolve null quando a ferramenta não é desta família. */
internal suspend fun AgentRunner.browserTools(inv: ToolInvocation): String? = with(inv) {
    when (call.name) {
        "browser_navigate" -> {
            // Caminhos do sandbox (/root, /public, /home ou file:///root/…) viram o arquivo real do aparelho:
            // o WebView não enxerga o filesystem do proot (era a causa de net::ERR_FILE_NOT_FOUND).
            browserMode(sessionId, s("mode"))
            val raw = SecretVault.resolve(sessionId, s("url")).trim()
            val guest = raw.removePrefix("file://").let { if (raw.startsWith("file://")) it.substringBefore('?').substringBefore('#') else it }
            val isSandboxPath = guest.startsWith("/root") || guest.startsWith("/public") || guest.startsWith("/home") || (raw.startsWith("file://") && !guest.startsWith("/data/") && !guest.startsWith("/storage/") && !guest.startsWith("/sdcard"))
            val hostF = if (isSandboxPath) sandbox.hostFile(guest, sessionId) else null
            val hubGuard = if (hostF != null) null else hubBrowserGuard(sessionId, raw)
            if (hubGuard != null) hubGuard
            else if (hostF != null && !hostF.exists()) {
                "ERROR: arquivo não existe no workspace: $guest. Use file_list para achar o caminho certo (arquivos de conectores ficam em /root/connectors/<id>/workspace)."
            } else {
                val url = hostF?.let { "file://" + it.absolutePath } ?: BrowserVisitLog.canonical(raw)
                // Sessão de login salva (Browser › Sessões): só agentes autorizados entram com ela.
                val loginDenied = if (hostF != null) null else runCatching {
                    val login = dev.fluwy.app.browser.BrowserPolicies.loginFor(dev.fluwy.app.browser.BrowserPolicies.hostOf(url), store.state.value.browserLogins)
                    if (login == null || login.status == "revoked") null
                    else dev.fluwy.app.browser.BrowserPolicies.loginDenial(login, store.session(sessionId)?.agentId)
                        ?.let { "RECUSADO: $it. Não tente entrar por conta própria; peça ao usuário para liberar em Browser › Sessões ou siga sem esse site." }
                        ?: run { store.editBrowserLogin(login.id) { it.copy(lastUsedAt = System.currentTimeMillis(), useCount = it.useCount + 1) }; null }
                }.getOrNull()
                // Guarda-corpo: não reabrir a mesma busca/página em looping (mesmo com parâmetros reordenados).
                val current = runCatching { browserFor(sessionId).ui.value.url }.getOrNull()
                val repeated = BrowserVisitLog.check(sessionId, url, current)
                if (loginDenied != null) loginDenied else if (repeated != null) repeated else run {
                var navigation = browserFor(sessionId).navigate(url)
                // App fechado e sem permissão de sobreposição: em vez de devolver o erro (o agente insistia
                // e gastava tokens), ESPERA o app voltar para a tela — sem custo — e navega de novo sozinho.
                if (navigation.contains("browser indisponível com o app fechado")) {
                    setActivity(sessionId, Activity(Activity.Kind.BROWSER, "Browser em espera: abra o app para continuar"))
                    val deadline = System.currentTimeMillis() + BROWSER_WAIT_FOREGROUND_MS
                    while (!dev.fluwy.app.browser.OffscreenStage.foreground && System.currentTimeMillis() < deadline) kotlinx.coroutines.delay(1_500)
                    navigation = if (dev.fluwy.app.browser.OffscreenStage.foreground) { kotlinx.coroutines.delay(800); browserFor(sessionId).navigate(url) }
                        else "ERROR: o app ficou fechado por ${BROWSER_WAIT_FOREGROUND_MS / 60_000} min e o browser não funciona assim. NÃO navegue de novo: " +
                            "siga com o que não depende do browser ou use sleep (10+ min) e tente depois. O usuário pode liberar 'Sobrepor a outros apps' para o browser rodar com o app fechado."
                }
                BrowserVisitLog.record(sessionId, url, navigation)
                if (navigation.startsWith("ERROR:")) navigation
                else if (navigation.startsWith("TIMEOUT:")) {
                    // Como o Playwright MCP: mesmo lento, devolve o que a página mostra agora.
                    val now = runCatching { browserFor(sessionId).read(2500, trackRepeat = false) }.getOrDefault("")
                    navigation + (if (now.isNotBlank()) "\n\n[Estado atual da página]\n$now" else "")
                }
                else {
                    val page = browserFor(sessionId).read(4000, trackRepeat = false)
                    val notFound = Regex("(?i)(t[ií]tulo:[^\\n]*(404|not found|n[aã]o encontrad)|this page doesn.t exist|page not found|p[aá]gina n[aã]o encontrada)").containsMatchIn(page.take(600))
                    val loginHint = runCatching {
                        val finalHost = dev.fluwy.app.browser.BrowserPolicies.hostOf(browserFor(sessionId).ui.value.url.ifBlank { url })
                        dev.fluwy.app.browser.BrowserPolicies.noLoginHint(finalHost, page, store.state.value.browserLogins)
                    }.getOrNull()
                    (if (notFound) "⚠ A página carregou, mas é um ERRO 404 (não existe). Não use este conteúdo; procure o endereço correto.\n" else "") + navigation + "\n\n" + page +
                        (loginHint?.let { "\n\n$it" } ?: "")
                }
                }
            }
        }
        "browser_new_tab" -> browserFor(sessionId).newTab(SecretVault.resolve(sessionId, s("url")))
        "browser_select_tab" -> browserFor(sessionId).selectTabForAgent(s("tab_id"))
        "browser_close_tab" -> browserFor(sessionId).closeTabForAgent(s("tab_id"))
        "reply_audio", "notify", "show_cards", "cofre_listar", "pix_qr" -> extraOutputTool(sessionId, call.name, a)
        "conectores_listar" -> dev.fluwy.app.connectors.HubConnectors.list(store)
        "conector_acoes" -> dev.fluwy.app.connectors.HubConnectors.actions(store, s("conector"), s("acao"), s("busca"))
        "conector_executar" -> dev.fluwy.app.connectors.HubConnectors.run(store, s("conector"), s("acao"),
            resolveArgs(sessionId, a)["campos"], s("conexao"),
            full = runCatching { a["completo"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase() == "true" }.getOrDefault(false))
        "browser_read", "browser_snapshot" -> browserReadTool(sessionId, (i("max_chars") ?: 9000).coerceIn(1000, 20000), model)
        "browser_wait_for" -> {
            val br = browserFor(sessionId)
            val waited = br.waitFor(s("text"), s("text_gone"), i("seconds") ?: 10)
            waited + "\n\n" + br.read(4000)
        }
        "browser_click" -> browserFor(sessionId).click(i("index") ?: -1)
        "browser_type" -> browserFor(sessionId).type(i("index") ?: -1, SecretVault.resolve(sessionId, s("text")), b("submit"))
        "browser_scroll" -> browserFor(sessionId).scroll(s("direction") != "up")
        // Voltar invalida os índices: já devolve a leitura nova (antes o agente clicava no índice velho e errava em sequência).
        "browser_back" -> { browserFor(sessionId).back(); "ok: voltou. Página atual (use estes índices):\n" + browserReadTool(sessionId, 6000, model) }
        "browser_js" -> browserFor(sessionId).runJs(SecretVault.resolve(sessionId, s("code")))
        "browser_network" -> browserFor(sessionId).networkSummary(s("filter"))
        "browser_console" -> browserFor(sessionId).consoleSummary()
        else -> null
    }
}
