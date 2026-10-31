package dev.cueopen.app

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
import dev.cueopen.app.agent.AgentRunner
import dev.cueopen.app.browser.BrowserHost
import dev.cueopen.app.data.Store
import dev.cueopen.app.llm.OpenRouter
import dev.cueopen.app.sandbox.Sandbox
import dev.cueopen.app.proxy.ProxyScope
import dev.cueopen.app.proxy.ProxyStore
import dev.cueopen.app.proxy.ProxyValidator
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
class CueApp : Application() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var store: Store; private set
    lateinit var sandbox: Sandbox; private set
    lateinit var proxyStore: ProxyStore; private set
    val connectorDebugLog: dev.cueopen.app.diag.ConnectorDebugLog by lazy {
        dev.cueopen.app.diag.ConnectorDebugLog(java.io.File(filesDir, "connector-debug"))
    }
    private val browsers = java.util.concurrent.ConcurrentHashMap<String, BrowserHost>()
    private val proxyExecutor = Executors.newSingleThreadExecutor { Thread(it, "browser-proxy").apply { isDaemon = true } }
    private val browserProxyGeneration = AtomicLong(0)
    private var proxyRotationJob: Job? = null
    private val _browserProxyStatus = MutableStateFlow("Aguardando configuração")
    val browserProxyStatus: StateFlow<String> = _browserProxyStatus.asStateFlow()
    /** Cada chat mantém seu próprio WebView, cookies, URL, histórico e estado de rede. */
    fun browserFor(sessionId: String): BrowserHost = browsers.computeIfAbsent(sessionId) { BrowserHost(this) }
    /** Fecha e descarta o browser isolado de uma sessão (ex.: subagente que terminou). */
    fun closeBrowser(sessionId: String) { browsers.remove(sessionId)?.dispose() }
    /** Exclui um chat e encerra somente suas execuções, agendas e heartbeats associados. */
    fun deleteChat(sessionId: String) {
        val deletion = store.deleteSessionAndLinkedWork(sessionId)
        deletion.cancelledScheduledTaskIds.forEach { dev.cueopen.app.scheduler.Scheduler.cancel(this, it) }
        deletion.cancelledHeartbeatAgentIds.forEach { dev.cueopen.app.scheduler.Heartbeat.cancel(this, it) }
        deletion.removedSessionIds.forEach { id ->
            agent.stop(id)
            closeBrowser(id)
            getSystemService(NotificationManager::class.java)
                .cancel(SessionNotificationRouting.stableId(id))
        }
        store.cleanupWorkspaces(deletion.removedSessionIds)
    }
    val browser: BrowserHost get() = browserFor("__legacy__")
    lateinit var llm: OpenRouter; private set
    lateinit var agent: AgentRunner; private set
    lateinit var terminals: dev.cueopen.app.sandbox.TerminalManager; private set
    /** Hub dos UI Connectors (conectores com interface própria e ferramentas para o agente). */
    val uiConnectors: dev.cueopen.app.uiconnector.UiConnectorHub by lazy { dev.cueopen.app.uiconnector.UiConnectorHub(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        dev.cueopen.app.diag.ErrorLog.init(this)
        // Chave Demo opcional da CoinGecko (gratuita) para o mercado, se o usuário tiver configurado.
        runCatching {
            dev.cueopen.app.wallet.MarketApiConfig.geckoApiKey = getSharedPreferences("solana_wallet_settings", MODE_PRIVATE)
                .getString("gecko_api_key", null)?.trim()?.ifBlank { null }
        }
        // registra falhas para diagnóstico (Configurações > Último erro / Logs de erros)
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { dev.cueopen.app.diag.ErrorLog.lastCrashFile(this).writeText("${java.util.Date()}\n${t.name}\n${e.stackTraceToString()}") }
            runCatching { dev.cueopen.app.diag.ErrorLog.log("crash", "${t.name}: ${e.stackTraceToString()}") }
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
        store.onDeleteWorkspace = { sid -> runCatching { sandbox.deleteEnvironment(sid) }; runCatching { sandbox.workspaceFor(sid).deleteRecursively() } }
        llm = OpenRouter { store.settings }
        terminals = dev.cueopen.app.sandbox.TerminalManager(this, sandbox)
        agent = AgentRunner(store, sandbox, ::browserFor, llm, terminals, this)
        agent.resumeSleeping()
        agent.startAutonomy()
        dev.cueopen.app.scheduler.AutonomyWorker.ensure(this)
        // terminal já ativo em segundo plano assim que o app abre (se o sandbox estiver instalado)
        if (sandbox.isInstalled()) dev.cueopen.app.sandbox.SandboxService.start(this, "Terminal ativo")
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CH_TASKS, "Tarefas", NotificationManager.IMPORTANCE_DEFAULT)
        )
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
    fun configureProxyRotation(value: dev.cueopen.app.proxy.ProxyRotationSettings) {
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
                    val next = dev.cueopen.app.proxy.ProxyRotationPolicy.nextProfileId(
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
            runCatching { dev.cueopen.app.diag.ErrorLog.log("proxy", "ProxyController falhou (${t.javaClass.simpleName}).") }
        }
    }

    companion object {
        const val CH_TASKS = "tasks"
        lateinit var instance: CueApp; private set
    }
}
