package dev.fluwy.app.backup

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Process

/**
 * Reinicia o app depois de importar dados (como o ProcessPhoenix): roda num processo à parte (":restart"),
 * encerra o processo principal e abre o app de novo na tela inicial. Por ser uma Activity visível, o Android
 * permite abrir o app sem restrição de início em segundo plano.
 */
class RestartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pid = intent.getIntExtra(EXTRA_PID, -1)
        if (pid > 0 && pid != Process.myPid()) Process.killProcess(pid)
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }
        finish()
        Runtime.getRuntime().exit(0)
    }

    companion object {
        const val EXTRA_PID = "main_pid"

        fun isRestartProcess(ctx: Context): Boolean {
            val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) android.app.Application.getProcessName()
            else runCatching { java.io.File("/proc/self/cmdline").readText().trim('\u0000', ' ') }.getOrDefault("")
            return name.endsWith(":restart") || name == ctx.packageName + ":restart"
        }
    }
}
