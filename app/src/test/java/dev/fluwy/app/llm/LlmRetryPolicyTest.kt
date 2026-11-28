package dev.fluwy.app.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class LlmRetryPolicyTest {
    @Test fun parsesDelaySeconds() {
        assertEquals(7_000L, LlmRetryPolicy.retryAfterMillis("7", nowMillis = 0L))
        assertEquals(0L, LlmRetryPolicy.retryAfterMillis("0", nowMillis = 0L))
    }

    @Test fun parsesHttpDateRelativeToCurrentTime() {
        val now = Instant.parse("2015-10-21T07:27:50Z").toEpochMilli()
        assertEquals(10_000L, LlmRetryPolicy.retryAfterMillis("Wed, 21 Oct 2015 07:28:00 GMT", now))
    }

    @Test fun expiredDateAndInvalidHeaderDoNotCreateExtraWait() {
        val now = Instant.parse("2015-10-21T07:28:10Z").toEpochMilli()
        assertEquals(0L, LlmRetryPolicy.retryAfterMillis("Wed, 21 Oct 2015 07:28:00 GMT", now))
        assertNull(LlmRetryPolicy.retryAfterMillis("not-a-retry-after", now))
        assertNull(LlmRetryPolicy.retryAfterMillis(null, now))
    }

    @Test fun providerHintOverridesFallbackAndFallbackIsExponential() {
        assertEquals(12_000L, LlmRetryPolicy.delayMillis(attempt = 1, retryAfterMillis = 12_000L))
        assertEquals(1_500L, LlmRetryPolicy.delayMillis(attempt = 1, retryAfterMillis = null))
        assertEquals(3_000L, LlmRetryPolicy.delayMillis(attempt = 2, retryAfterMillis = null))
        assertEquals(6_000L, LlmRetryPolicy.delayMillis(attempt = 3, retryAfterMillis = null))
        assertEquals(30_000L, LlmRetryPolicy.delayMillis(attempt = 10, retryAfterMillis = null))
    }
}
