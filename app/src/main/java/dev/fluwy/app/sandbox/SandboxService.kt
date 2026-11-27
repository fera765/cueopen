package dev.fluwy.app.sandbox

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import dev.fluwy.app.FluwyApp
import dev.fluwy.app.MainActivity

/**
 * Serviço em foreground: mantém o terminal (axs + PTYs) e o agente rodando com o app fechado,
 * com um aviso fixo na barra de notificações (como o TerminalService do Acode / Termux).
 */
class SandboxService : Service() {
    private var wake: PowerManager.WakeLock? = null
    /** Só com Live Server no ar: o Wi-Fi não dorme com a tela apagada, então o túnel (e o link) não cai. */
    private var wifi: android.net.wifi.WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            runCatching { FluwyApp.instance.store.state.value.sessions.forEach { FluwyApp.instance.agent.stop(it.id) } }
            runCatching { FluwyApp.instance.sandbox.stopAxs() }
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            return START_NOT_STICKY
        }
        // Texto fixo: a notificação só indica que o app está rodando (nada de execuções antigas presas nela).
        val text = "Rodando"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Terminal e agente", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, SandboxService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CH)
            .setContentTitle("Fluwy Bot")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Encerrar", stop).build())
            .build()
        startForeground(7, n)
        if (wake == null) {
            wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fluwy:terminal").apply { setReferenceCounted(false); acquire() }
        }
        runCatching {
            val live = dev.fluwy.app.live.LiveServer.hasActive()
            if (live && wifi == null) {
                @Suppress("DEPRECATION")
                wifi = applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
                    .createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "fluwy:live").apply { setReferenceCounted(false); acquire() }
            } else if (!live) { wifi?.release(); wifi = null }
        }
        return START_STICKY
    }

    override fun onDestroy() { runCatching { wake?.release() }; wake = null; runCatching { wifi?.release() }; wifi = null; super.onDestroy() }

    // App removido dos recentes: o serviço continua vivo (agente segue em background).
    override fun onTaskRemoved(rootIntent: Intent?) {}

    companion object {
        const val CH = "sandbox"
        const val ACTION_STOP = "dev.fluwy.STOP"
        fun start(ctx: Context, text: String = "Terminal ativo") = runCatching {
            val i = Intent(ctx, SandboxService::class.java).putExtra("text", text)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
        fun stop(ctx: Context) = runCatching { ctx.stopService(Intent(ctx, SandboxService::class.java)) }
    }
}
