package dev.fluwy.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import dev.fluwy.app.agent.AgentRunner
import dev.fluwy.app.browser.BrowserHost
import dev.fluwy.app.data.Store
import dev.fluwy.app.llm.OpenRouter
import dev.fluwy.app.sandbox.Sandbox
import dev.fluwy.app.proxy.ProxyScope
import dev.fluwy.app.proxy.ProxyStore
import dev.fluwy.app.proxy.ProxyValidator
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import okhttp3.Credentials
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Container simples de dependências (sem login, tudo local). */
class FluwyApp : Application() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate +
        kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
            // Falha em tarefa de fundo: registra em vez de derrubar o app.
            runCatching { dev.fluwy.app.diag.ErrorLog.log("background", "${e::class.java.simpleName}: ${e.message}\n${e.stackTraceToString().take(2500)}") }
        })
    lateinit var store: Store; private set
    lateinit var sandbox: Sandbox; private set
    lateinit var proxyStore: ProxyStore; private set
    val connectorDebugLog: dev.fluwy.app.diag.ConnectorDebugLog by lazy {
        dev.fluwy.app.diag.ConnectorDebugLog(java.io.File(filesDir, "connector-debug"))
    }
    private val browsers = java.util.concurrent.ConcurrentHashMap<String, BrowserHost>()
    private val proxyExecutor = Executors.newSingleThreadExecutor { Thread(it, "browser-proxy").apply { isDaemon = true } }
    private val browserProxyGeneration = AtomicLong(0)
    private var proxyRotationJob: Job? = null
    private val _browserProxyStatus = MutableStateFlow("Aguardando configuração")
    val browserProxyStatus: StateFlow<String> = _browserProxyStatus.asStateFlow()
    /** Cada chat recebe WebView, perfil de dados e arquivo de restauração próprios. */
    fun browserFor(sessionId: String): BrowserHost = browsers.computeIfAbsent(sessionId) { BrowserHost(this, sessionId) }
    /** Browsers já abertos (um por chat/agente), sem criar novos — para a seção Browser listar. */
    fun openBrowsers(): Map<String, BrowserHost> = HashMap(browsers)
    /** Descarta a instância; dados persistidos só são removidos quando o chat é apagado. */
    fun closeBrowser(sessionId: String, clearPersistentData: Boolean = false) {
        val host = browsers.remove(sessionId)
        if (host != null) host.dispose(clearPersistentData)
        else if (clearPersistentData) BrowserHost.clearSessionData(this, sessionId)
    }

    /** Flush best-effort de estado nativo por chat antes de a Activity entrar em background. */
    fun persistRuntimeState() {
        browsers.values.forEach { browser -> runCatching { browser.persistState() } }
        if (::terminals.isInitialized) runCatching { terminals.persistAll() }
    }
    /**
     * Exclui um chat NA HORA: some da lista no mesmo quadro; parar agentes, fechar browser/terminal, derrubar o servidor
     * do exército e apagar o workspace (node_modules tem milhares de arquivos) correm em segundo plano.
     */
    fun deleteChat(sessionId: String) {
        val squadKids = store.state.value.sessions.filter { it.squadParentId == sessionId }.map { it.id }
        val deletion = store.deleteSessionAndLinkedWork(sessionId)
        store.dropSessions(squadKids.toSet())
        val ids = (deletion.removedSessionIds + squadKids).distinct()
        deletion.cancelledScheduledTaskIds.forEach { dev.fluwy.app.scheduler.Scheduler.cancel(this, it) }
        deletion.cancelledHeartbeatAgentIds.forEach { dev.fluwy.app.scheduler.Heartbeat.cancel(this, it) }
        val nm = getSystemService(NotificationManager::class.java)
        ids.forEach { id -> runCatching { nm.cancel(SessionNotificationRouting.stableId(id)) } }
        applicationScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            ids.forEach { id -> runCatching { agent.stop(id) }; runCatching { closeBrowser(id, clearPersistentData = true) } }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                ids.forEach { id -> runCatching { agent.releaseSession(id) } }
                store.cleanupWorkspaces(ids)
            }
        }
    }
    val browser: BrowserHost get() = browserFor("__legacy__")
    lateinit var llm: OpenRouter; private set
    lateinit var agent: AgentRunner; private set
    lateinit var terminals: dev.fluwy.app.sandbox.TerminalManager; private set
    /** Hub dos UI Connectors (conectores com interface própria e ferramentas para o agente). */
    val uiConnectors: dev.fluwy.app.uiconnector.UiConnectorHub by lazy { dev.fluwy.app.uiconnector.UiConnectorHub(this) }

    override fun onCreate() {
        super.onCreate()
        // Voz do app: se instalada, carrega o motor em segundo plano (o 1º áudio já sai rápido).
        runCatching { dev.fluwy.app.voice.AppVoice.prewarm(this) }
        // Processo ":restart" (só reabre o app depois de importar dados): nada do app sobe nele.
        if (dev.fluwy.app.backup.RestartActivity.isRestartProcess(this)) return
        instance = this
        LegacyMigration.run(this)
        dev.fluwy.app.prompts.Prompts.init(filesDir)
        // Backup importado: troca o perfil do browser antes de qualquer WebView existir.
        dev.fluwy.app.backup.DataTransfer.applyStagedBrowserData(this)
        dev.fluwy.app.diag.ErrorLog.init(this)
        dev.fluwy.app.diag.ActivityLog.init(this)
        // Chave Demo opcional da CoinGecko (gratuita) para o mercado, se o usuário tiver configurado.
        runCatching {
            dev.fluwy.app.wallet.MarketApiConfig.geckoApiKey = getSharedPreferences("solana_wallet_settings", MODE_PRIVATE)
                .getString("gecko_api_key", null)?.trim()?.ifBlank { null }
        }
        // registra falhas para diagnóstico (Configurações > Último erro / Logs de erros)
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { dev.fluwy.app.diag.ErrorLog.lastCrashFile(this).writeText("${java.util.Date()}\n${t.name}\n${e.stackTraceToString()}") }
            runCatching { dev.fluwy.app.diag.ErrorLog.log("crash", "${t.name}: ${e.stackTraceToString()}") }
            prev?.uncaughtException(t, e)
        }
        // The voice session runs in :assistant. WorkManager's Startup provider is in the
        // default process, so do not start app-wide workers/agents/proxy in the session process.
        if (!isMainAppProcess()) return
        store = Store(this)
        proxyStore = ProxyStore(this)
        connectorDebugLog
        applyBrowserProxy()
        restartProxyRotation()
        sandbox = Sandbox(this)
        // Agentes de uma mesma Seção compartilham o workspace (arquivos entregues entre etapas).
        sandbox.environmentAlias = { sid ->
            val s = store.session(sid)
            val owner = s?.squadParentId?.let { store.session(it) } ?: s
            owner?.sectionId?.let { "section_$it" } ?: s?.squadParentId
        }
        // Tudo de uma Seção é compartilhado: workspace (acima) e cofre de dados seguros (e-mail, senha, API key).
        dev.fluwy.app.agent.SecretVault.scopeOf = { sid -> store.session(sid)?.sectionId?.let { "section_$it" } }
        dev.fluwy.app.agent.BuildSquad.dir = java.io.File(filesDir, "squads")
        // Fluxos (estilo n8n): carrega e reagenda os gatilhos de horário ativos.
        dev.fluwy.app.workflow.WorkflowStore.init(filesDir)
        runCatching { dev.fluwy.app.workflow.WorkflowScheduler.syncAll(this) }
        sandbox.relativeBase = { sid -> store.session(sid)?.squadParentId?.let { if (::agent.isInitialized) agent.projectDirOf(it) else null } }
        store.onDeleteWorkspace = { sid ->
            // Workspace compartilhado de Seção ainda ativa: não apaga ao remover só um chat do time.
            val sharedAlive = store.session(sid)?.sectionId?.let { store.section(it) != null } == true
            if (!sharedAlive) { runCatching { sandbox.deleteEnvironment(sid) }; runCatching { sandbox.workspaceFor(sid).deleteRecursively() } }
        }
        llm = OpenRouter { store.settings }
        terminals = dev.fluwy.app.sandbox.TerminalManager(this, sandbox)
        agent = AgentRunner(store, sandbox, ::browserFor, llm, terminals, this)
        // Depois de "Sair", nada volta a rodar sozinho até o usuário abrir o app (MainActivity chama AppExit.reopen).
        if (!AppExit.exited(this)) startBackground()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CH_TASKS, "Tarefas", NotificationManager.IMPORTANCE_DEFAULT)
        )
        dev.fluwy.app.notify.NotificationCenter.init(this)
    }

    private fun isMainAppProcess(): Boolean {
        val processName = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            getSystemService(android.app.ActivityManager::class.java)?.runningAppProcesses
                ?.firstOrNull { it.pid == android.os.Process.myPid() }?.processName
        }
        return MainProcessPolicy.isMainProcess(applicationInfo.processName, processName)
    }

    /** Resolve the profile selected for HTTP/WebSocket; an invalid profile fails closed, without bypass. */
    fun proxyHttpClient(scope: ProxyScope, base: OkHttpClient): OkHttpClient {
        val selectedId = when (scope) {
            ProxyScope.BROWSER -> store.settings.browserProxyId
            ProxyScope.TERMINAL -> store.settings.terminalProxyId
            ProxyScope.AGENT_API -> store.settings.agentApiProxyId
        } ?: return base
        val profile = proxyStore.profiles().firstOrNull { it.id == selectedId }
            ?: throw IOException("O perfil de proxy selecionado para $scope não existe mais.")
        val validation = ProxyValidator.validate(profile, scope)
        if (!validation.valid) throw IOException("Perfil de proxy inválido para $scope: ${validation.message}")
        val builder = base.newBuilder().proxy(ProxyValidator.okHttpProxy(profile))
        if (profile.hasCredentials) {
            val credentials = proxyStore.credentials(profile.id)
                ?: throw IOException("As credenciais protegidas do proxy '${profile.name}' não estão disponíveis.")
            builder.proxyAuthenticator { _, response ->
                if (response.request.header("Proxy-Authorization") != null) null
                else response.request.newBuilder()
                    .header("Proxy-Authorization", Credentials.basic(credentials.username, credentials.password))
                    .build()
            }
        }
        return builder.build()
    }

    /** Saves and applies the optional, per-scope rotation policy. Disabled by default. */
    fun configureProxyRotation(value: dev.fluwy.app.proxy.ProxyRotationSettings) {
        proxyStore.saveRotationSettings(value)
        restartProxyRotation()
    }

    private fun restartProxyRotation() {
        proxyRotationJob?.cancel()
        val config = proxyStore.rotationSettings()
        if (!config.enabled) return
        if (ProxyScope.entries.all { config.profileIds(it).isEmpty() }) {
            connectorDebugLog.recordEvent("proxy", "rotation", "enabled but no profile pools are configured")
            return
        }
        proxyRotationJob = applicationScope.launch {
            while (isActive) {
                delay(config.intervalMinutes.coerceIn(1, 1_440) * 60_000L)
                val latest = proxyStore.rotationSettings()
                if (!latest.enabled) break
                val profiles = proxyStore.profiles()
                for (scope in ProxyScope.entries) {
                    val pool = latest.profileIds(scope)
                    if (pool.isEmpty()) continue
                    val current = when (scope) {
                        ProxyScope.BROWSER -> store.settings.browserProxyId
                        ProxyScope.TERMINAL -> store.settings.terminalProxyId
                        ProxyScope.AGENT_API -> store.settings.agentApiProxyId
                    }
                    val next = dev.fluwy.app.proxy.ProxyRotationPolicy.nextProfileId(
                        current, pool, profiles, scope, kotlin.random.Random.nextInt(),
                    )
                    if (next == null) {
                        connectorDebugLog.recordEvent("proxy", "rotation", "no eligible alternate profile for scope=${scope.name}")
                        continue
                    }
                    store.updateSettings { s -> when (scope) {
                        ProxyScope.BROWSER -> s.copy(browserProxyId = next)
                        ProxyScope.TERMINAL -> s.copy(terminalProxyId = next)
                        ProxyScope.AGENT_API -> s.copy(agentApiProxyId = next)
                    } }
                    when (scope) {
                        ProxyScope.BROWSER -> applyBrowserProxy()
                        ProxyScope.TERMINAL -> Unit // variables are resolved when each terminal command starts
                        ProxyScope.AGENT_API -> Unit // new HTTP calls and new WebSockets use the new profile; active sockets are not forcibly restarted
                    }
                    connectorDebugLog.recordEvent(
                        "proxy", "rotation",
                        "scope=${scope.name} selected profile_id=$next; existing long-lived transports are unchanged",
                    )
                }
            }
        }
    }

    /** Android WebView proxy override is process-wide, never per BrowserHost. */
    fun applyBrowserProxy() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            _browserProxyStatus.value = "Indisponível: esta versão do WebView não oferece ProxyController."
            return
        }
        val selectedId = store.settings.browserProxyId
        val direct = ProxyConfig.Builder().addDirect().build()
        val block = ProxyConfig.Builder().addProxyRule("http://127.0.0.1:1").build()
        val config: ProxyConfig
        val status: String
        if (selectedId == null) {
            config = direct
            status = "Direto (sem proxy)"
        } else {
            val selected = proxyStore.profiles().firstOrNull { it.id == selectedId }
            val validation = selected?.let { ProxyValidator.validate(it, ProxyScope.BROWSER) }
            if (selected == null || validation?.valid != true) {
                config = block
                status = "Navegação bloqueada: perfil Browser ausente ou inválido."
            } else {
                config = ProxyConfig.Builder().addProxyRule(ProxyValidator.endpoint(selected)).build()
                status = "Proxy Browser aplicado."
            }
        }
        val generation = browserProxyGeneration.incrementAndGet()
        _browserProxyStatus.value = "Aplicando configuração Browser…"
        try {
            ProxyController.getInstance().setProxyOverride(config, proxyExecutor) {
                if (browserProxyGeneration.get() == generation) _browserProxyStatus.value = status
            }
        } catch (t: Exception) {
            if (browserProxyGeneration.get() == generation)
                _browserProxyStatus.value = "Falha ao aplicar ProxyController; a configuração anterior pode continuar ativa."
            runCatching { dev.fluwy.app.diag.ErrorLog.log("proxy", "ProxyController falhou (${t.javaClass.simpleName}).") }
        }
    }

    companion object {
        const val CH_TASKS = "tasks"
        lateinit var instance: FluwyApp; private set
    }

    /** Agentes dormindo, seções, autonomia e terminal em segundo plano. */
    fun startBackground() {
        agent.resumeSleeping()
        agent.recoverSections()  // planos de seção interrompidos continuam sozinhos
        agent.startAutonomy()
        dev.fluwy.app.scheduler.AutonomyWorker.ensure(this)
        dev.fluwy.app.scheduler.Heartbeat.rescheduleAll(this)
        // terminal já ativo em segundo plano assim que o app abre (se o sandbox estiver instalado)
        if (sandbox.isInstalled()) dev.fluwy.app.sandbox.SandboxService.start(this, "Terminal ativo")
        runCatching { dev.fluwy.app.browser.WaLocalServer.start(this) }  // endereço local: webhook do WhatsApp + Web APIs em stream
        runCatching { dev.fluwy.app.browser.WhatsAppHub.start(this) }
    }

}
