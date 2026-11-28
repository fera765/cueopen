package dev.fluwy.app.llm

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Retry timing for transient provider failures. */
internal object LlmRetryPolicy {
    const val BASE_BACKOFF_MILLIS = 1_500L
    const val MAX_BACKOFF_MILLIS = 30_000L

    /** Parses the standard Retry-After forms: delay-seconds or an HTTP date. */
    fun retryAfterMillis(value: String?, nowMillis: Long = System.currentTimeMillis()): Long? {
        val header = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val seconds = header.toLongOrNull()
        if (seconds != null) {
            if (seconds < 0L) return null
            return if (seconds > Long.MAX_VALUE / 1_000L) Long.MAX_VALUE else seconds * 1_000L
        }
        val retryAtMillis = runCatching {
            ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        }.getOrNull() ?: return null
        return (retryAtMillis - nowMillis).coerceAtLeast(0L)
    }

    private val transientText = Regex("(?i)upstream|endpoint is unavailable|no endpoints? (found|available)|provider returned error|overloaded|temporarily|try again|unavailable|timeout|timed out|rate.?limit|capacity")

    /**
     * Códigos que normalmente são transitórios. Roteadores (OpenRouter/OpenCode) devolvem 404/400 com
     * "Upstream request failed: Endpoint is unavailable" quando o provedor do modelo cai por instantes.
     */
    fun isTransientHttp(code: Int, message: String?): Boolean =
        code == 408 || code == 425 || code == 429 || code in 500..599 ||
            (code in setOf(400, 403, 404, 409) && message != null && transientText.containsMatchIn(message))

    /** Retomada automática depois que as tentativas imediatas falharam: 2, 5, 15 e 30 minutos. */
    fun autoResumeDelayMillis(round: Int): Long? = when (round) {
        1 -> 2 * 60_000L; 2 -> 5 * 60_000L; 3 -> 15 * 60_000L; 4 -> 30 * 60_000L; else -> null
    }

    /** Honors a provider hint when present; otherwise uses capped exponential backoff. */
    fun delayMillis(attempt: Int, retryAfterMillis: Long?): Long {
        retryAfterMillis?.let { return it.coerceAtLeast(0L) }
        val exponent = (attempt.coerceAtLeast(1) - 1).coerceAtMost(20)
        return (BASE_BACKOFF_MILLIS * (1L shl exponent)).coerceAtMost(MAX_BACKOFF_MILLIS)
    }
}
