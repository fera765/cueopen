package dev.fluwy.app.wallet

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Parses the standard Retry-After forms without allowing arithmetic overflow. */
internal object HttpRetryAfter {
    fun delayMillis(value: String?, nowMillis: Long = System.currentTimeMillis()): Long? {
        val header = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        header.toLongOrNull()?.let { seconds ->
            if (seconds < 0L) return null
            return if (seconds > Long.MAX_VALUE / 1_000L) Long.MAX_VALUE else seconds * 1_000L
        }
        val retryAtMillis = runCatching {
            ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        }.getOrNull() ?: return null
        if (retryAtMillis <= nowMillis) return 0L
        return retryAtMillis - nowMillis
    }

    fun secondsCeiling(delayMillis: Long): Long =
        delayMillis / 1_000L + if (delayMillis % 1_000L == 0L) 0L else 1L
}
