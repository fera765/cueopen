package dev.cueopen.app.device

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.runtime.mutableStateListOf
import dev.cueopen.app.data.now
import kotlinx.serialization.Serializable

/**
 * Capacidades do dispositivo que um Agent pode receber (ligadas por toggle na criação do agente).
 * Cada uma mapeia para uma permissão real do Android, concedida pelo usuário na tela do sistema —
 * nada é acessado sem o grant explícito. As ferramentas correspondentes só entram no contexto do
 * agente quando a capacidade está ligada E a permissão do sistema concedida.
 */
object DeviceCaps {
    data class Cap(val id: String, val title: String, val desc: String, val permission: String)

    const val NOTIF_READ = "notif_read"
    const val NOTIF_POST = "notif_post"
    const val OVERLAY = "overlay"
    const val SCREEN = "screen"

    val all = listOf(
        Cap(NOTIF_READ, "Ler notificações", "Ver as notificações que chegam no aparelho (ex.: acompanhar mensagens). Requer acesso a notificações.", "listener"),
        Cap(NOTIF_POST, "Enviar notificações", "Mostrar alertas e lembretes na barra de status.", android.Manifest.permission.POST_NOTIFICATIONS),
        Cap(OVERLAY, "Sobrepor à tela", "Exibir um balão/aviso flutuante sobre outros apps.", Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
        Cap(SCREEN, "Capturar a tela", "Tirar uma captura do que está na tela quando você pedir.", "projection"),
    )

    fun granted(ctx: Context, id: String): Boolean = when (id) {
        NOTIF_READ -> {
            val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: ""
            flat.contains(ctx.packageName)
        }
        NOTIF_POST -> if (android.os.Build.VERSION.SDK_INT >= 33)
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED else true
        OVERLAY -> Settings.canDrawOverlays(ctx)
        SCREEN -> ScreenCapture.hasPermission()
        else -> false
    }

    /** Abre a tela de sistema para conceder a permissão da capacidade. */
    fun request(ctx: Context, id: String) {
        val i = when (id) {
            NOTIF_READ -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            OVERLAY -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName))
            NOTIF_POST -> Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
            SCREEN -> Intent(ctx, ScreenCapture.RequestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            else -> return
        }
        runCatching { if (i.flags == 0) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); ctx.startActivity(i) }
    }
}

/** Notificações capturadas (quando a capacidade "Ler notificações" está ativa). */
@Serializable
data class CapturedNotif(val time: Long, val pkg: String, val app: String, val title: String, val text: String)

object NotifStore {
    val items = mutableStateListOf<CapturedNotif>()
    fun add(n: CapturedNotif) { items.add(0, n); while (items.size > 500) items.removeAt(items.size - 1) }
    fun recent(filter: String, limit: Int): List<CapturedNotif> =
        items.filter { filter.isBlank() || it.app.contains(filter, true) || it.pkg.contains(filter, true) || it.text.contains(filter, true) }.take(limit)
}
