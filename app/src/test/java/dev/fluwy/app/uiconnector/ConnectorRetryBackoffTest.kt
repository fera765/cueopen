package dev.fluwy.app.uiconnector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectorRetryBackoffTest {
    @Test fun `atrasos crescem exponencialmente e respeitam limite`() {
        val backoff = ConnectorRetryBackoff(baseDelayMs = 100L, maxDelayMs = 400L)

        assertEquals(100L, backoff.recordFailure("iqoption", 0L, "timeout").retryAtElapsedMs)
        assertEquals(300L, backoff.recordFailure("iqoption", 100L, "timeout").retryAtElapsedMs)
        assertEquals(700L, backoff.recordFailure("iqoption", 300L, "timeout").retryAtElapsedMs)
        assertEquals(1_100L, backoff.recordFailure("iqoption", 700L, "timeout").retryAtElapsedMs)
        assertEquals(400L, backoff.remainingMs("iqoption", 700L))
    }

    @Test fun `cooldown termina no prazo e mantem a ultima falha`() {
        val backoff = ConnectorRetryBackoff(baseDelayMs = 1_000L, maxDelayMs = 4_000L)
        backoff.recordFailure("gmail", 5_000L, "falha de rede")

        assertEquals(250L, backoff.remainingMs("gmail", 5_750L))
        assertEquals(0L, backoff.remainingMs("gmail", 6_000L))
        assertEquals("falha de rede", backoff.state("gmail")?.reason)
    }

    @Test fun `estado e reset sao independentes por conector`() {
        val backoff = ConnectorRetryBackoff(baseDelayMs = 1_000L, maxDelayMs = 4_000L)
        backoff.recordFailure("iqoption", 10L, "timeout")
        backoff.recordFailure("gmail", 10L, "timeout")
        backoff.recordFailure("iqoption", 20L, "timeout")

        assertEquals(2, backoff.state("iqoption")?.failures)
        assertEquals(1, backoff.state("gmail")?.failures)
        backoff.reset("iqoption")
        assertNull(backoff.state("iqoption"))
        assertEquals(1, backoff.state("gmail")?.failures)
    }
}
