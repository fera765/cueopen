package dev.cueopen.app.device

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.provider.Telephony
import dev.cueopen.app.data.now

/**
 * Serviço de leitura de notificações (concedido pelo usuário em Configurações › Acesso a notificações).
 * Só roda se o usuário ligar a capacidade e conceder a permissão do sistema. Guarda em memória as
 * notificações recentes para a ferramenta device_notifications dos agentes.
 */
class CueNotificationListener : NotificationListenerService() {
    override fun onNotificationRemoved(sbn: StatusBarNotification) { runCatching { NotificationReply.forget(sbn.key) } }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        runCatching {
            val notification = sbn.notification
            val defaultSmsPackage = runCatching { Telephony.Sms.getDefaultSmsPackage(this) }.getOrNull()
            if (!NotificationPrivacyPolicy.shouldCapture(
                    sbn.packageName,
                    defaultSmsPackage,
                    notification.category == android.app.Notification.CATEGORY_MESSAGE,
                )) return
            val e = notification.extras
            val title = e.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString().orEmpty()
            val text = e.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()
                ?: e.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
            if (title.isBlank() && text.isBlank()) return
            val pm = packageManager
            val app = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString() }.getOrDefault(sbn.packageName)
            // Resumo de grupo ("5 mensagens de 2 conversas") não é uma conversa: ignora.
            if (notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0) return
            val action = NotificationReply.replyAction(notification)
            val id = NotificationReply.register(sbn.key, app, title, action)
            NotifStore.add(CapturedNotif(now(), sbn.packageName, app, title, text, id = id, replyable = action != null,
                conversation = NotificationReply.conversation(e)))
        }
    }
}
