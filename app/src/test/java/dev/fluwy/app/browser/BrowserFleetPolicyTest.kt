package dev.fluwy.app.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserFleetPolicyTest {
    private val now = 10 * 3_600_000L
    private fun tab(host: String, id: String = "tab-1", idleMin: Long = 0, liveMin: Long = 5, visible: Boolean = false,
                    busy: Boolean = false, inUse: Boolean = false, pinned: Boolean = false, live: Boolean = true) =
        BrowserFleetPolicy.Tab(host, id, live, visible, busy, inUse, pinned, now - idleMin * 60_000, now - liveMin * 60_000)

    @Test fun keepsBudgetAcrossAllChatsOldestFirst() {
        val tabs = (1..5).map { tab("chat$it", idleMin = it.toLong()) }
        val park = BrowserFleetPolicy.toPark(tabs, now, BrowserFleetPolicy.NORMAL)
        assertEquals(listOf("chat5", "chat4"), park.map { it.hostId })
    }

    @Test fun neverParksVisibleBusyOrInUse() {
        val tabs = listOf(tab("a", idleMin = 60, visible = true), tab("b", idleMin = 60, busy = true), tab("c", idleMin = 60, inUse = true))
        assertTrue(BrowserFleetPolicy.toPark(tabs, now, BrowserFleetPolicy.NORMAL).isEmpty())
    }

    @Test fun recyclesPagesOpenForHoursIncludingPinned() {
        val wa = tab("__webapi_wa__", idleMin = 5, liveMin = 210, pinned = true)
        val fresh = tab("chat", idleMin = 5, liveMin = 30)
        assertEquals(listOf(wa), BrowserFleetPolicy.toPark(listOf(wa, fresh), now, BrowserFleetPolicy.NORMAL))
    }

    @Test fun pinnedIsNotParkedJustForIdling() =
        assertTrue(BrowserFleetPolicy.toPark(listOf(tab("__webapi_wa__", idleMin = 60, pinned = true)), now, BrowserFleetPolicy.NORMAL).isEmpty())

    @Test fun simultaneousDeathsCountAsOneEvent() {
        val d = BrowserFleetPolicy.DeathTracker()
        assertTrue(d.record(1_000)); assertFalse(d.record(1_500)); assertFalse(d.record(2_000))
        assertFalse(d.lowMemory(2_000))
        assertTrue(d.record(600_000))
        assertTrue(d.lowMemory(600_000))
    }

    @Test fun recoveryIsStaggered() {
        val s = BrowserFleetPolicy.RecoveryStagger(1_500)
        assertEquals(listOf(0L, 1_500L, 3_000L), listOf(s.delayFor(0), s.delayFor(0), s.delayFor(0)))
    }
}
