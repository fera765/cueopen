package dev.fluwy.app.pages

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageHtmlTest {
    @Test fun `export is mobile first and escapes content`() {
        val p = PageOps.create("s", "Quadro <x>", "kanban", "a").getOrThrow().copy(cards = listOf(PageCard("c1", "<script>alert(1)</script>", "A fazer")))
        val h = PageHtml.export(p)
        assertTrue(h.contains("width=device-width")); assertTrue(h.contains("&lt;script&gt;")); assertFalse(h.contains("<script>alert"))
        assertTrue(PageHtml.ensureMobile("<p>oi</p>").contains("viewport"))
        assertTrue(PageHtml.ensureMobile("<html><head></head><body></body></html>").contains("<head><meta name=\"viewport\""))
    }
}
