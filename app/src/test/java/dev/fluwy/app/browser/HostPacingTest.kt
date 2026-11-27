package dev.fluwy.app.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostPacingTest {
    @Test
    fun backoffGrowsAndResetsOnSuccess() {
        val h = "pacing-test-" + System.nanoTime()
        assertEquals(0L, HostPacing.delayFor(h))
        val now = System.currentTimeMillis()
        assertEquals(10_000L, HostPacing.strike(h, now))
        assertEquals(30_000L, HostPacing.strike(h, now))
        assertTrue(HostPacing.delayFor(h, now) in 29_000L..30_000L)
        HostPacing.success(h)
        assertEquals(10_000L, HostPacing.strike(h, now))
    }
}
