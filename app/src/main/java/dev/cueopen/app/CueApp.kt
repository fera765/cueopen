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

    override fun onCreate() {
        super.onCreate()
        instance = this
        store = Store(this)
        sandbox = Sandbox(this)
        browser = BrowserHost(this)
        llm = OpenRouter { store.settings }
        agent = AgentRunner(store, sandbox, browser, llm)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CH_TASKS, "Tarefas", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    companion object {
        const val CH_TASKS = "tasks"
        lateinit var instance: CueApp; private set
    }
}
