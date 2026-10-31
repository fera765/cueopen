package dev.cueopen.app.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class HttpRetryAfterTest {
    @Test
    fun parsesSecondsAndHttpDateWithoutWaitingEarly() {
        val now = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
        assertEquals(7_000L, HttpRetryAfter.delayMillis("7", now))
        assertEquals(30_000L, HttpRetryAfter.delayMillis("Thu, 01 Jan 2026 00:00:30 GMT", now))
    }

    @Test
    fun expiredDatesAreImmediateAndInvalidOrNegativeValuesAreIgnored() {
        val now = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
        assertEquals(0L, HttpRetryAfter.delayMillis("Wed, 31 Dec 2025 23:59:59 GMT", now))
        assertNull(HttpRetryAfter.delayMillis("-1", now))
        assertNull(HttpRetryAfter.delayMillis("not-a-header", now))
    }

    @Test
    fun hugeSecondsSaturateInsteadOfOverflowing() {
        assertEquals(Long.MAX_VALUE, HttpRetryAfter.delayMillis(Long.MAX_VALUE.toString(), 0L))
    }
}
