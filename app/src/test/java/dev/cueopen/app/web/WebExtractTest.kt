package dev.cueopen.app.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebExtractTest {
    @Test fun `main text keeps article structure and drops chrome`() {
        val html = """<html><head><title>Guia</title><script>var x=1</script></head><body>
            <nav><a href="/">Início</a> Menu gigante</nav>
            <article><h1>Como regar</h1><p>Regue de manhã.</p><ul><li><p>Pouca água</p></li><li>Sol indireto</li></ul></article>
            <footer>Copyright</footer></body></html>"""
        val t = WebExtract.mainText(html, "https://x.com/a")
        assertTrue(t.contains("Título: Guia")); assertTrue(t.contains("# Como regar")); assertTrue(t.contains("- Pouca água"))
        assertFalse(t.contains("Menu gigante")); assertFalse(t.contains("Copyright")); assertFalse(t.contains("var x"))
        assertEquals(1, Regex("Pouca água").findAll(t).count())
    }

    @Test fun `duckduckgo results are parsed and redirect links opened`() {
        val html = """<div class="result"><a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fp%3Fa%3D1&rut=x">Exemplo</a>
            <a class="result__snippet">Um trecho</a></div>
            <div class="result result--ad"><a class="result__a" href="https://ads.com">Anúncio</a></div>"""
        val hits = WebExtract.parseDuckDuckGo(html)
        assertEquals(1, hits.size)
        assertEquals("https://example.com/p?a=1", hits[0].url); assertEquals("Um trecho", hits[0].snippet)
    }
}
