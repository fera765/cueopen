package dev.cueopen.app.device

/** Bloqueia o conteúdo de notificações que poderia servir como atalho para ler SMS. */
object NotificationPrivacyPolicy {
    fun shouldCapture(
        notificationPackage: String,
        defaultSmsPackage: String?,
        isMessageCategory: Boolean,
    ): Boolean {
        if (!defaultSmsPackage.isNullOrBlank() && notificationPackage == defaultSmsPackage) return false
        if (defaultSmsPackage.isNullOrBlank() && isMessageCategory) return false
        return true
    }
}
