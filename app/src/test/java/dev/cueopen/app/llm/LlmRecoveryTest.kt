package dev.cueopen.app.llm

import dev.cueopen.app.llm.LlmRecovery.Category
import dev.cueopen.app.llm.LlmRecovery.CircuitBreaker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmRecoveryTest {
    @Test fun `errors are classified by kind`() {
        assertEquals(Category.RATE_LIMIT, LlmRecovery.classify("HTTP 429 — Rate limit exceeded", true))
        assertEquals(Category.SERVER, LlmRecovery.classify("HTTP 500 — internal", true))
        assertEquals(Category.SERVER, LlmRecovery.classify("HTTP 503 — overloaded", true))
        assertEquals(Category.SERVER, LlmRecovery.classify("HTTP 404 — Upstream request failed: Endpoint is unavailable", true))
        assertEquals(Category.TIMEOUT, LlmRecovery.classify("HTTP 504 — gateway timeout", true))
        assertEquals(Category.TIMEOUT, LlmRecovery.classify("Falha de comunicação com o provedor: timeout", true))
        assertEquals(Category.NETWORK, LlmRecovery.classify("Falha de comunicação com o provedor: Unable to resolve host", true))
        assertEquals(Category.AUTH, LlmRecovery.classify("HTTP 401 — Invalid API key", false))
        assertEquals(Category.CREDITS, LlmRecovery.classify("HTTP 402 — Insufficient credits", false))
        assertEquals(Category.AUTH, LlmRecovery.classify("HTTP 400 — This OpenCode Go model trains on request data. Turn on \"Allow paid endpoints that train on request data\"", false))
        assertEquals(Category.MODEL_NOT_FOUND, LlmRecovery.classify("HTTP 404 — model xyz not found", false))
        assertEquals(Category.ENDPOINT, LlmRecovery.classify("Base URL inválida.", false))
        assertEquals(Category.CONTEXT, LlmRecovery.classify("HTTP 400 — maximum context length is 128000 tokens", false))
        assertEquals(Category.STREAM, LlmRecovery.classify("cortada", true, truncated = true))
        assertEquals(Category.FORMAT, LlmRecovery.classify("HTTP 400 — invalid tool schema", false))
    }

    @Test fun `permanent errors are never retried or auto resumed`() {
        listOf(Category.AUTH, Category.CREDITS, Category.MODEL_NOT_FOUND, Category.ENDPOINT).forEach {
            assertFalse(LlmRecovery.shouldRetry(it, 1, true))
            assertNull(LlmRecovery.autoResumeDelayMillis(1, it))
        }
        assertTrue(LlmRecovery.shouldRetry(Category.RATE_LIMIT, 1, true))
        assertTrue(LlmRecovery.shouldRetry(Category.SERVER, 2, true))
        assertFalse(LlmRecovery.shouldRetry(Category.SERVER, 3, true))
        assertEquals(20 * 60_000L, LlmRecovery.autoResumeDelayMillis(3, Category.RATE_LIMIT))
        assertNull(LlmRecovery.autoResumeDelayMillis(4, Category.RATE_LIMIT))
    }

    @Test fun `jitter only adds up to a quarter`() {
        assertEquals(1000L, LlmRecovery.withJitter(1000, 0.0))
        assertEquals(1250L, LlmRecovery.withJitter(1000, 1.0))
    }

    @Test fun `breaker opens after repeated failures probes once and closes on success`() {
        val b = CircuitBreaker(threshold = 3, baseOpenMillis = 1000, maxOpenMillis = 8000)
        repeat(2) { b.onFailure(0); assertEquals(0L, b.acquire(0)) }
        b.onFailure(0)
        assertEquals(CircuitBreaker.State.OPEN, b.state)
        assertEquals(1000L, b.acquire(0))
        assertEquals(0L, b.acquire(1000))                      // janela passou: uma sonda
        assertEquals(CircuitBreaker.State.HALF_OPEN, b.state)
        assertTrue(b.acquire(1000) > 0)                        // os outros agentes esperam a sonda
        b.onFailure(1000)                                      // sonda falhou: abre por mais tempo
        assertEquals(2000L, b.acquire(1000))
        assertEquals(0L, b.acquire(3000))
        b.onSuccess()
        assertEquals(CircuitBreaker.State.CLOSED, b.state)
        assertEquals(0L, b.acquire(3000))
    }

    @Test fun `retry-after is shared as a cooldown without opening the circuit`() {
        val b = CircuitBreaker()
        b.onFailure(0, retryAfterMillis = 5000)
        assertEquals(CircuitBreaker.State.CLOSED, b.state)
        assertEquals(5000L, b.acquire(0))
        assertEquals(0L, b.acquire(5000))
    }

    @Test fun `provider label never leaks credentials`() {
        assertEquals("api.x.com", LlmRecovery.providerLabel("https://user:secret@api.x.com/v1"))
    }
}
