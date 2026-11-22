package dev.cueopen.app.web

import dev.cueopen.app.web.SearchApi.Engine
import dev.cueopen.app.web.WebExtract.SearchHit
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SearchApiTest {
    @Test fun urls() {
        assertEquals("https://www.google.com/search?q=clima+s%C3%A3o+paulo&hl=pt-BR&gl=br&num=10", SearchApi.url(Engine.GOOGLE, " clima são paulo "))
        assertTrue(SearchApi.url(Engine.BING, "a b").startsWith("https://www.bing.com/search?q=a+b"))
        assertTrue(SearchApi.url(Engine.DUCK, "x").startsWith("https://html.duckduckgo.com/html/?q=x"))
    }

    @Test fun parsesEscapedWebViewResult() {
        val inner = """{"hits":[{"t":"Clima Tempo","u":"/url?q=https://www.climatempo.com.br/&sa=U","s":"Previsão  de hoje"},{"t":"Google","u":"https://www.google.com/search?q=x","s":""},{"t":"","u":"https://a.com","s":""},{"t":"Clima Tempo","u":"https://www.climatempo.com.br/","s":"dup"}],"blocked":false}"""
        val quoted = kotlinx.serialization.json.JsonPrimitive(inner).toString() // como evaluateJavascript devolve
        val p = SearchApi.parse(quoted, Engine.GOOGLE)
        assertEquals(1, p.hits.size)
        assertEquals("https://www.climatempo.com.br/", p.hits[0].url)
        assertEquals("Previsão  de hoje", p.hits[0].snippet)
        assertFalse(p.blocked)
        assertTrue(SearchApi.parse("""{"hits":[],"blocked":true}""", Engine.GOOGLE).blocked)
        assertTrue(SearchApi.parse("null", Engine.BING).hits.isEmpty())
        assertTrue(SearchApi.parse("lixo{", Engine.BING).hits.isEmpty())
    }

    @Test fun cleansRedirects() {
        assertEquals("https://ex.com/a?b=1", SearchApi.cleanUrl("https://www.google.com/url?q=https%3A%2F%2Fex.com%2Fa%3Fb%3D1&sa=U", Engine.GOOGLE))
        assertEquals("https://ex.com/p", SearchApi.cleanUrl("//duckduckgo.com/l/?uddg=https%3A%2F%2Fex.com%2Fp&rut=1", Engine.DUCK))
        val b64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("https://bing-dest.com/x".toByteArray())
        assertEquals("https://bing-dest.com/x", SearchApi.cleanUrl("https://www.bing.com/ck/a?!&&p=abc&u=a1$b64&ntb=1", Engine.BING))
        assertEquals("https://plain.com", SearchApi.cleanUrl("https://plain.com", Engine.BING))
        assertNull(SearchApi.cleanUrl("javascript:void(0)", Engine.GOOGLE))
    }

    @Test fun mergeInterleavesWithoutDuplicates() {
        val g = listOf(SearchHit("A", "https://a.com/", ""), SearchHit("B", "https://b.com", ""))
        val d = listOf(SearchHit("A2", "http://www.a.com", ""), SearchHit("C", "https://c.com", ""))
        assertEquals(listOf("A", "B", "C"), SearchApi.merge(listOf(g, d), 10).map { it.title })
        assertEquals(2, SearchApi.merge(listOf(g, d), 2).size)
        assertTrue(SearchApi.format("q", g, "Google").contains("1. A\n   https://a.com/"))
    }

    @Test fun cacheExpires() {
        val c = SearchApi.Cache(ttlMs = 1000)
        c.put("Q", "r", now = 0); assertEquals("r", c.get(" q ", now = 500)); assertNull(c.get("q", now = 2000))
    }

    private fun hits(n: Int, tag: String) = (1..n).map { SearchHit("$tag$it", "https://$tag$it.com", "") }

    @Test fun raceReturnsFastAndMergesGraceResults() = runBlocking {
        val t0 = System.currentTimeMillis()
        val r = HybridSearch.race(listOf(
            "Duck" to suspend { delay(50); hits(4, "d") },
            "Google" to suspend { delay(200); hits(5, "g") },
            "Bing" to suspend { delay(5_000); hits(5, "b") },
        ), firstMin = 3, graceMs = 400, totalMs = 10_000)
        val el = System.currentTimeMillis() - t0
        assertTrue("demorou $el ms", el < 1_500)
        assertEquals(4, r[0].second.size); assertEquals(5, r[1].second.size); assertTrue(r[2].second.isEmpty())
    }

    @Test fun raceSurvivesFailuresAndTimeout() = runBlocking {
        val r = HybridSearch.race(listOf(
            "Erro" to suspend { throw IllegalStateException("x") },
            "Pouco" to suspend { delay(30); hits(1, "p") },
            "Lento" to suspend { delay(9_000); hits(9, "l") },
        ), firstMin = 3, graceMs = 300, totalMs = 800)
        assertTrue(r[0].second.isEmpty()); assertEquals(1, r[1].second.size); assertTrue(r[2].second.isEmpty())
    }
}
