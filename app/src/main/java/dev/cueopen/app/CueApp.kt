package dev.cueopen.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dev.cueopen.app.agent.AgentRunner
import dev.cueopen.app.browser.BrowserHost
import dev.cueopen.app.data.Store
import dev.cueopen.app.llm.OpenRouter
import dev.cueopen.app.sandbox.Sandbox

/** Container simples de dependências (sem login, tudo local). */
class CueApp : Application() {
    lateinit var store: Store; private set
    lateinit var sandbox: Sandbox; private set
    lateinit var browser: BrowserHost; private set
    lateinit var llm: OpenRouter; private set
    lateinit var agent: AgentRunner; private set
    lateinit var terminals: dev.cueopen.app.sandbox.TerminalManager; private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        // registra falhas para diagnóstico (Configurações > Último erro)
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { java.io.File(filesDir, "last_crash.txt").writeText("${java.util.Date()}\n${t.name}\n${e.stackTraceToString()}") }
            prev?.uncaughtException(t, e)
        }
        store = Store(this)
        sandbox = Sandbox(this)
        browser = BrowserHost(this)
        llm = OpenRouter { store.settings }
        terminals = dev.cueopen.app.sandbox.TerminalManager(this, sandbox)
        agent = AgentRunner(store, sandbox, browser, llm, terminals, this)
        agent.resumeSleeping()
        agent.startAutonomy()
        dev.cueopen.app.scheduler.AutonomyWorker.ensure(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CH_TASKS, "Tarefas", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    companion object {
        const val CH_TASKS = "tasks"
        lateinit var instance: CueApp; private set
    }
}
