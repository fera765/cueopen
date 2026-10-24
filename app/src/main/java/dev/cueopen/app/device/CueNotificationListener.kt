package dev.cueopen.app.device

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.cueopen.app.data.now

/**
 * Serviço de leitura de notificações (concedido pelo usuário em Configurações › Acesso a notificações).
 * Só roda se o usuário ligar a capacidade e conceder a permissão do sistema. Guarda em memória as
 * notificações recentes para a ferramenta device_notifications dos agentes.
 */
class CueNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        runCatching {
            val e = sbn.notification.extras
            val title = e.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString().orEmpty()
            val text = e.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()
                ?: e.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
            if (title.isBlank() && text.isBlank()) return
            val pm = packageManager
            val app = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(sbn.packageName)
            NotifStore.add(CapturedNotif(now(), sbn.packageName, app, title, text))
        }
    }
}
