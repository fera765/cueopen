package dev.cueopen.app

import java.nio.ByteBuffer
import java.security.MessageDigest

/** Contrato local da notificação que abre uma conversa persistida. */
object SessionNotificationRouting {
    const val ACTION_OPEN_SESSION = "dev.cueopen.OPEN_SESSION"
    const val EXTRA_SESSION = "session" // compatibilidade com notificações existentes
    const val EXTRA_SESSION_ID = "session_id"
    const val FALLBACK_NOTIFICATION_ID = 0x435545

    fun deepLink(sessionId: String): String =
        "cueopen://chat/${java.net.URLEncoder.encode(sessionId, "UTF-8").replace("+", "%20")}"

    /** Inteiro determinístico para PendingIntent/NotificationManager, sem hashCode(). */
    fun stableId(value: String, fallback: Int = FALLBACK_NOTIFICATION_ID): Int {
        val key = value.trim()
        if (key.isBlank()) return fallback
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        return (ByteBuffer.wrap(digest).int and Int.MAX_VALUE).takeIf { it != 0 } ?: fallback
    }

    fun validSessionId(id: String?): String? = id?.trim()?.takeIf { it.isNotEmpty() }

    fun candidateId(extraId: String?, deepLinkId: String?): String? =
        validSessionId(extraId) ?: validSessionId(deepLinkId)

    fun shouldOpen(currentSessionId: String?, targetSessionId: String?): Boolean =
        targetSessionId != null && targetSessionId != currentSessionId
}
