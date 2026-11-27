package dev.fluwy.app

import android.app.Activity
import android.content.Context

/**
 * "Sair" de verdade: para todos os agentes e serviços, cancela os despertadores e fecha o processo. Enquanto o usuário
 * não abrir o app de novo, nenhum alarme, agenda ou boot religa nada (a marca "saiu" é conferida em cada entrada).
 */
object AppExit {
    private const val PREFS = "app_exit"

    fun exited(ctx: Context): Boolean = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("exited", false)

    /** Usuário abriu o app: volta ao normal e religa o que estava ligado. */
    fun reopen(app: FluwyApp) {
        if (!exited(app)) return
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("exited", false).commit()
        app.startBackground()
    }

    fun exit(activity: Activity) {
        val app = activity.applicationContext as FluwyApp
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("exited", true).commit()
        runCatching { app.agent.stopEverything() }
        runCatching { app.store.state.value.agents.forEach { dev.fluwy.app.scheduler.Heartbeat.cancel(app, it.id) } }
        runCatching { androidx.work.WorkManager.getInstance(app).cancelUniqueWork("autonomy") }
        runCatching { dev.fluwy.app.sandbox.SandboxService.stop(app) }
        runCatching { app.store.flush() }
        activity.finishAndRemoveTask()
        android.os.Process.killProcess(android.os.Process.myPid())
        kotlin.system.exitProcess(0)
    }
}
