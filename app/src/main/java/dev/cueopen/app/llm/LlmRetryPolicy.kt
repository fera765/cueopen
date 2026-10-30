package dev.cueopen.app.llm

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

    /** Honors a provider hint when present; otherwise uses capped exponential backoff. */
    fun delayMillis(attempt: Int, retryAfterMillis: Long?): Long {
        retryAfterMillis?.let { return it.coerceAtLeast(0L) }
        val exponent = (attempt.coerceAtLeast(1) - 1).coerceAtMost(20)
        return (BASE_BACKOFF_MILLIS * (1L shl exponent)).coerceAtMost(MAX_BACKOFF_MILLIS)
    }
}
