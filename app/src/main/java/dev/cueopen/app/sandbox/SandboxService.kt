package dev.cueopen.app.sandbox

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

/** Mantém o processo vivo enquanto o terminal/agente usa o sandbox (como o TerminalService do Acode). */
class SandboxService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CH, "Sandbox", NotificationManager.IMPORTANCE_LOW))
        }
        val n = Notification.Builder(this, CH)
            .setContentTitle("CueOpen")
            .setContentText("Computador do agente em execução")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .build()
        startForeground(7, n)
        return START_STICKY
    }

    companion object {
        const val CH = "sandbox"
        fun start(ctx: Context) = runCatching {
            val i = Intent(ctx, SandboxService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
        fun stop(ctx: Context) = runCatching { ctx.stopService(Intent(ctx, SandboxService::class.java)) }
    }
}
