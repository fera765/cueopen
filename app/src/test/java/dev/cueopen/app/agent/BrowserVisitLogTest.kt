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

class BrowserVisitLogGuardsTest {
    @Test
    fun facebookMobileAndWebVariantsBecomeWww() {
        assertEquals("https://www.facebook.com/ads/library/?q=curso&country=BR",
            BrowserVisitLog.canonical("https://m.facebook.com/ads/library/?q=curso&country=BR&_rdr"))
        assertEquals("https://www.facebook.com/ads/library/?q=x",
            BrowserVisitLog.canonical("https://web.facebook.com/ads/library/?q=x&_rdc=1&_rdr"))
        assertEquals("https://example.com/a%5Bb%5D?x=1", BrowserVisitLog.canonical("https://example.com/a%5Bb%5D?x=1"))
        assertEquals(BrowserVisitLog.normalize("https://web.facebook.com/ads/library/?q=x"),
            BrowserVisitLog.normalize("https://www.facebook.com/ads/library/?q=x&_rdr"))
    }

    @Test
    fun hostCircuitBreakerTripsAfterFourFailuresAndResetsOnSuccess() {
        val host = "breaker-" + System.nanoTime() + ".test"
        val s = "s-" + System.nanoTime()
        repeat(4) { i -> BrowserVisitLog.record(s, "https://$host/p$i", "TIMEOUT: x") }
        val msg = BrowserVisitLog.check(s, "https://$host/outra", null)
        assertNotNull(msg); assertTrue(msg!!.contains("falhou 4×"))
        BrowserVisitLog.resetHost(host)
        assertNull(BrowserVisitLog.check(s, "https://$host/outra", null))
        repeat(3) { i -> BrowserVisitLog.record(s, "https://$host/q$i", "TIMEOUT: x") }
        BrowserVisitLog.record(s, "https://$host/ok", "OK: página carregada")
        BrowserVisitLog.record(s, "https://$host/z", "TIMEOUT: x")
        assertNull(BrowserVisitLog.check(s, "https://$host/nova", null))
    }
}
