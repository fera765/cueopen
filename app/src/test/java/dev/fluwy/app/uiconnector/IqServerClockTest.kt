package dev.fluwy.app.uiconnector

import dev.fluwy.app.uiconnector.iqoption.IqServerClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IqServerClockTest {
    @Test fun `clock advances from monotonic anchor and expires after configured freshness`() {
        val clock = IqServerClock(maxAgeMs = 30_000)
        assertTrue(clock.update(1_700_000_000_000, 10_000))
        assertEquals(1_700_000_015L, clock.nowSec(25_000))
        assertNull(clock.nowSec(40_001))
    }

    @Test fun `invalid anchor and negative elapsed age fail closed`() {
        val clock = IqServerClock()
        assertFalse(clock.update(0, 1_000))
        assertNull(clock.nowMs(1_001))
        assertTrue(clock.update(1_700_000_000_000, 10_000))
        assertNull(clock.nowMs(9_999))
        clock.clear()
        assertNull(clock.nowMs(10_000))
    }
}
