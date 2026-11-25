package dev.cueopen.app.device

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat

/** Apps de banco que recusam abrir com serviço de acessibilidade de terceiros ligado. */
object BankApps {
    private val KNOWN = setOf(
        "com.nu.production", "br.com.intermedium", "com.itau", "com.itau.iphone", "br.com.itau", "br.com.bb.android",
        "com.bradesco", "br.com.bradesco.next", "com.santander.app", "br.com.gabba.Caixa", "br.gov.caixa.tem", "com.picpay",
        "com.mercadopago.wallet", "br.com.original.bank", "com.c6bank.app", "com.neon", "br.com.uol.ps.myaccount",
        "com.btg.pactual.banking", "br.com.sicredi.app", "br.com.sicoob.app", "com.inter.app", "br.com.banrisul",
    )

    fun isBank(pkg: String): Boolean = pkg in KNOWN || pkg.startsWith("com.nu.") || Regex("(^|\\.)(bank|banco|banking)(\\.|$)").containsMatchIn(pkg)

    /** Ligado por padrão; dá para desativar em Configurações. */
    fun autoOff(ctx: Context) = ctx.getSharedPreferences("bank_guard", Context.MODE_PRIVATE).getBoolean("auto_off", true)
    fun setAutoOff(ctx: Context, on: Boolean) = ctx.getSharedPreferences("bank_guard", Context.MODE_PRIVATE).edit().putBoolean("auto_off", on).apply()

    fun notifyDisabled(ctx: Context, pkg: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("bank_guard", "Apps de banco", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(ctx, 7301, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(7301, NotificationCompat.Builder(ctx, "bank_guard")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("Acessibilidade do Fluwy desligada")
            .setContentText("Desliguei para o app do banco abrir com segurança. Se ele já mostrou o aviso, feche e abra de novo. Toque para religar quando terminar.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Desliguei a acessibilidade do Fluwy Bot para o app do banco ($pkg) abrir. Se o banco já mostrou o aviso, feche e abra de novo. Quando terminar, toque aqui para religar."))
            .setContentIntent(open).setAutoCancel(true).build())
    }
}
