package dev.cueopen.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import dev.cueopen.app.agent.AgentRunner
import dev.cueopen.app.browser.BrowserHost
import dev.cueopen.app.data.Store
import dev.cueopen.app.llm.OpenRouter
import dev.cueopen.app.sandbox.Sandbox

/** Container simples de dependências (sem login, tudo local). */
class CueApp : Application() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var store: Store; private set
    lateinit var sandbox: Sandbox; private set
    private val browsers = java.util.concurrent.ConcurrentHashMap<String, BrowserHost>()
    /** Cada chat mantém seu próprio WebView, cookies, URL, histórico e estado de rede. */
    fun browserFor(sessionId: String): BrowserHost = browsers.computeIfAbsent(sessionId) { BrowserHost(this) }
    /** Fecha e descarta o browser isolado de uma sessão (ex.: subagente que terminou). */
    fun closeBrowser(sessionId: String) { browsers.remove(sessionId)?.dispose() }
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
            runCatching { java.io.File(filesDir, "last_crash.txt").writeText("${java.util.Date()}\n${t.name}\n${e.stackTraceToString()}") }
            runCatching { dev.cueopen.app.diag.ErrorLog.log("crash", "${t.name}: ${e.stackTraceToString()}") }
            prev?.uncaughtException(t, e)
        }
        store = Store(this)
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
        dev.cueopen.app.uiconnector.iqoption.IqLegacySignalCleanup.cancelAndClear(this)
    }

    companion object {
        const val CH_TASKS = "tasks"
        lateinit var instance: CueApp; private set
    }
}
