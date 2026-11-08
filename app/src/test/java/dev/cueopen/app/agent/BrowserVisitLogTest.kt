package dev.cueopen.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserVisitLogTest {
    private val a = "https://www.facebook.com/ads/library/?active_status=active&ad_type=all&country=BR&q=desafio&search_type=keyword_unordered&media_type=all"
    private val aReordered = "https://www.facebook.com/ads/library/?country=BR&media_type=all&q=desafio&active_status=active&search_type=keyword_unordered&ad_type=all"

    @Test
    fun reorderedParamsAreTheSamePage() {
        assertEquals(BrowserVisitLog.normalize(a), BrowserVisitLog.normalize(aReordered))
    }

    @Test
    fun failedPageIsRefusedAfterTwoAttempts() {
        val s = "t1-" + System.nanoTime()
        assertNull(BrowserVisitLog.check(s, a, null))
        BrowserVisitLog.record(s, a, "TIMEOUT: x")
        assertNull(BrowserVisitLog.check(s, aReordered, null))
        BrowserVisitLog.record(s, aReordered, "TIMEOUT: x")
        assertTrue(BrowserVisitLog.check(s, a, null)!!.startsWith("RECUSADO"))
    }

    @Test
    fun openPageIsNotReloaded() {
        val s = "t2-" + System.nanoTime()
        BrowserVisitLog.record(s, a, "OK: página carregada")
        val msg = BrowserVisitLog.check(s, aReordered, a)
        assertNotNull(msg); assertTrue(msg!!.contains("já está aberta"))
        assertNull(BrowserVisitLog.check(s, a, "https://www.facebook.com/ads/library/?id=1"))
    }

    @Test
    fun summaryListsRecentVisits() {
        val s = "t3-" + System.nanoTime()
        BrowserVisitLog.record(s, a, "OK")
        BrowserVisitLog.record(s, a, "OK")
        assertTrue(BrowserVisitLog.summary(s).first().startsWith("- [ok ×2]"))
    }
}
