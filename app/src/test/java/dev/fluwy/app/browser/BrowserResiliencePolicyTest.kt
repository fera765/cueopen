package dev.fluwy.app.browser

import dev.fluwy.app.browser.MemoryPressurePolicy.TabState
import dev.fluwy.app.browser.RendererRecoveryPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserResiliencePolicyTest {
    @Test fun recoveryRetriesOnceThenStopsRetryingThenGivesUp() {
        val p = RendererRecoveryPolicy(maxPerTab = 2, windowMs = 1_000)
        assertEquals(Decision.RECOVER_AND_RETRY, p.onFailure("a", 0))
        assertEquals(Decision.RECOVER_ONLY, p.onFailure("a", 10))
        assertEquals(Decision.GIVE_UP, p.onFailure("a", 20))
    }

    @Test fun recoveryWindowExpiresAndResetClears() {
        val p = RendererRecoveryPolicy(maxPerTab = 2, windowMs = 1_000)
        p.onFailure("a", 0); p.onFailure("a", 10)
        assertEquals(Decision.RECOVER_AND_RETRY, p.onFailure("a", 5_000))
        p.reset("a")
        assertEquals(0, p.failuresInWindow("a", 5_001))
        assertEquals(Decision.RECOVER_AND_RETRY, p.onFailure("a", 5_002))
    }

    @Test fun globalLimitStopsCrashStormAcrossTabs() {
        val p = RendererRecoveryPolicy(maxPerTab = 5, maxGlobal = 2, windowMs = 1_000)
        p.onFailure("a", 0); p.onFailure("b", 1)
        assertEquals(Decision.GIVE_UP, p.onFailure("c", 2))
    }

    private fun tab(id: String, used: Long, active: Boolean = false, visible: Boolean = false, busy: Boolean = false, live: Boolean = true) =
        TabState(id, live, active, visible, busy, used)

    @Test fun neverParksActiveVisibleOrBusyTabs() {
        val tabs = listOf(tab("act", 0, active = true), tab("vis", 0, visible = true), tab("busy", 0, busy = true), tab("old", 0), tab("dead", 0, live = false))
        assertEquals(listOf("old"), MemoryPressurePolicy.tabsToPark(tabs, MemoryPressurePolicy.COMPLETE, now = 1_000_000))
    }

    @Test fun moderateParksOnlyIdleTabs() {
        val tabs = listOf(tab("idle", 0), tab("recent", 99_000))
        assertEquals(listOf("idle"), MemoryPressurePolicy.tabsToPark(tabs, MemoryPressurePolicy.RUNNING_MODERATE, now = 100_000))
    }

    @Test fun lowKeepsOneMostRecentExtraTab() {
        val tabs = listOf(tab("a", 95_000), tab("b", 97_000), tab("c", 99_000))
        assertEquals(listOf("a", "b"), MemoryPressurePolicy.tabsToPark(tabs, MemoryPressurePolicy.RUNNING_LOW, now = 100_000))
    }

    @Test fun uiHiddenDoesNothingAndFlags() {
        assertTrue(MemoryPressurePolicy.tabsToPark(listOf(tab("a", 0)), MemoryPressurePolicy.UI_HIDDEN, now = 1_000_000).isEmpty())
        assertFalse(MemoryPressurePolicy.shouldPauseHidden(MemoryPressurePolicy.UI_HIDDEN))
        assertTrue(MemoryPressurePolicy.shouldPauseHidden(MemoryPressurePolicy.RUNNING_LOW))
        assertTrue(MemoryPressurePolicy.shouldClearCache(MemoryPressurePolicy.RUNNING_CRITICAL))
        assertFalse(MemoryPressurePolicy.shouldClearCache(MemoryPressurePolicy.BACKGROUND))
    }
}
