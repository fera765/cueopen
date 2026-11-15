package dev.cueopen.app.llm

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelFallbackTest {
    @After fun clean() = ModelFallback.reset()

    @Test fun `rate limit switches to the alternative and the primary is retried after the cooldown`() {
        val t0 = 1_000_000L
        assertEquals("main", ModelFallback.resolve("main", "alt", t0))
        assertTrue(ModelFallback.onRateLimit("main", "alt", t0))
        assertEquals("alt", ModelFallback.resolve("main", "alt", t0 + 1))
        val retry = t0 + ModelFallback.FIRST_COOLDOWN_MS
        assertEquals("main", ModelFallback.resolve("main", "alt", retry))
        // ainda limitado: espera dobra
        ModelFallback.onRateLimit("main", "alt", retry)
        assertEquals("alt", ModelFallback.resolve("main", "alt", retry + ModelFallback.FIRST_COOLDOWN_MS))
        assertEquals("main", ModelFallback.resolve("main", "alt", retry + 2 * ModelFallback.FIRST_COOLDOWN_MS))
        // voltou a responder: zera
        assertTrue(ModelFallback.onSuccess("main", retry + 2 * ModelFallback.FIRST_COOLDOWN_MS))
        ModelFallback.onRateLimit("main", "alt", retry + 3 * ModelFallback.FIRST_COOLDOWN_MS)
        assertEquals(retry + 4 * ModelFallback.FIRST_COOLDOWN_MS, ModelFallback.activeUntil("main", retry + 3 * ModelFallback.FIRST_COOLDOWN_MS))
    }

    @Test fun `no alternative configured means no switch and the cooldown is capped`() {
        assertFalse(ModelFallback.onRateLimit("main", "", 0))
        assertFalse(ModelFallback.onRateLimit("main", "main", 0))
        assertEquals("main", ModelFallback.resolve("main", "", 0))
        var t = 0L
        repeat(10) { ModelFallback.onRateLimit("x", "alt", t); t = ModelFallback.activeUntil("x", t)!! }
        ModelFallback.onRateLimit("x", "alt", t)
        assertEquals(t + ModelFallback.MAX_COOLDOWN_MS, ModelFallback.activeUntil("x", t))
    }

    @Test fun `success of the alternative during the cooldown does not end it`() {
        ModelFallback.onRateLimit("main", "alt", 0)
        assertFalse(ModelFallback.onSuccess("main", 10))
        assertEquals("alt", ModelFallback.resolve("main", "alt", 10))
    }

    @Test fun `chain walks to the next model and back`() {
        val t0 = 1_000L
        assertEquals("a", ModelFallback.resolve("a", "groq::b, c", t0))
        assertTrue(ModelFallback.onRateLimit("a", "groq::b, c", t0))
        assertEquals("groq::b", ModelFallback.resolve("a", "groq::b, c", t0 + 1))
        assertTrue(ModelFallback.onRateLimit("groq::b", "groq::b, c", t0 + 2, primary = "a"))
        assertEquals("c", ModelFallback.resolve("a", "groq::b, c", t0 + 3))
        assertEquals("a", ModelFallback.resolve("a", "groq::b, c", t0 + ModelFallback.FIRST_COOLDOWN_MS + 1))
    }
}
