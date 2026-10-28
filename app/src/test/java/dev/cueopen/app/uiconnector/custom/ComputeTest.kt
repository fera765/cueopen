package dev.cueopen.app.uiconnector.custom

import dev.cueopen.app.uiconnector.ConnectorSettings
import dev.cueopen.app.uiconnector.UiBlock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ComputeTest {
    private fun ev(src: String, env: Map<String, Any?> = emptyMap()) = Compute.eval(src, env, null)

    @Test fun `expressoes basicas`() {
        assertEquals(7.0, ev("1 + 2 * 3"))
        assertEquals(512.0, ev("2 ^ 3 ^ 2"))
        assertEquals("ab3", ev("'a' + \"b\" + 3"))
        assertEquals(listOf(2.0, 4.0, 6.0), ev("map([1, 2, 3], x -> x * 2)"))
        assertEquals(6.0, ev("reduce([1, 2, 3], (a, x) -> a + x, 0)"))
        assertEquals("sim", ev("len([1,2]) == 2 && !false ? 'sim' : 'não'"))
        assertEquals(3.0, ev("[1, 2, 3][-1]"))
        assertEquals(listOf(4.0, 6.0), ev("[1, 2] * 2 + [2, 2]"))
        assertEquals(listOf(1.0, 2.0, 3.0), ev("concat([1], [2, 3])"))
        assertEquals(listOf("x", "y"), ev("pluck([{n: 'x'}, {n: 'y'}], 'n')"))
        assertEquals(listOf(mapOf("a" to 1.0, "b" to 3.0), mapOf("a" to 2.0, "b" to 4.0)), ev("zip_rows({a: [1, 2], b: [3, 4]})"))
        assertEquals(10.0, ev("s.max * 2", mapOf("s" to mapOf("max" to 5.0))))
        assertEquals(4999950000.0, ev("reduce(range(100000), (a, x) -> a + x, 0)"))
    }

    @Test fun `indicadores conferem com os valores de referencia`() {
        assertEquals(listOf(null, null, 2.0, 3.0, 4.0), ev("sma([1, 2, 3, 4, 5], 3)"))
        assertEquals(listOf(null, null, 2.0, 3.0, 4.0), ev("ema([1, 2, 3, 4, 5], 3)"))
        assertEquals(100.0, ev("last(rsi(range(1, 40), 14))"))
        assertEquals(0.0, ev("last(rsi(range(40, 1, -1), 14))"))
        val m = ev("macd(range(1, 60))") as Map<*, *>
        assertTrue(m.keys.containsAll(listOf("macd", "signal", "hist")))
        val bb = ev("bollinger(repeat(5, 30), 20, 2)") as Map<*, *>
        assertEquals(5.0, (bb["upper"] as List<*>).last())
        assertEquals(true, ev("crossover([1, 3], [2, 2])"))
        assertEquals(mapOf("p" to 2.0, "r1" to 3.0, "s1" to 1.0, "r2" to 4.0, "s2" to 0.0, "r3" to 5.0, "s3" to -1.0), ev("pivots(3, 1, 2)"))
    }

    @Test fun `validacao estatica e limites`() {
        assertTrue(Compute.problems("foo(1)", emptySet()).any { it.contains("função desconhecida") })
        assertTrue(Compute.problems("x + 1", emptySet()).any { it.contains("identificador desconhecido") })
        assertTrue(Compute.problems("sma([1])", emptySet()).any { it.contains("argumento") })
        assertTrue(Compute.problems("map(l, x -> x + y)", setOf("l")).any { it.contains("'y'") })
        assertTrue(Compute.problems("map(l, x -> x)", setOf("l")).isEmpty())
        assertTrue(Compute.problems("1 +", emptySet()).isNotEmpty())
        try { ev("map(range(200000), x -> map(range(100), y -> y))"); fail("devia estourar o orçamento") }
        catch (e: Compute.ComputeError) { assertTrue(e.message!!.contains("limite")) }
    }

    private fun klines(n: Int) = (0 until n).joinToString(",", "[", "]") { i ->
        val base = 100 + i * 0.1 + 5 * Math.sin(i / 7.0)
        val o = base; val c = base + Math.sin(i / 3.0); val h = maxOf(o, c) + 0.5; val l = minOf(o, c) - 0.5
        "[${1_700_000_000_000L + i * 3_600_000L},\"$o\",\"$h\",\"$l\",\"$c\",\"${1000 + i}\",0]"
    }

    @Test fun `suite de indicadores recebe velas e nao busca dados`() = runBlocking {
        val http = object : HttpExec { override suspend fun exec(r: HttpReq): HttpRes = throw AssertionError("não pode fazer requisição: ${r.url}") }
        val p = CustomDefParser.parse(CustomTemplates.TV_INDICATORS.json)
        assertEquals(emptyList<String>(), p.errors)
        val c = CustomConnector(p.def!!, http, permissive = true)
        val st = ConnectorSettings { null }
        val t0 = System.nanoTime()
        val out = c.callTool("analisar", mapOf("velas" to klines(300), "ativo" to "EURUSD 1m"), st)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue(out, out.contains("RESUMO:") && out.contains("RSI(14):") && out.contains("Supertrend") && !out.contains("ERRO"))
        assertTrue(out, out.lines().size >= 35)
        assertTrue("rápido: ${ms}ms", ms < 3_000)
        // mesmo formato de texto que o get_candles da IQ Option devolve
        val txt = "Velas EURUSD · 60s · 3 (horário UTC) — open high low close volume\n" + (0 until 120).joinToString("\n") { i ->
            val b = 1.08 + i * 0.0001 + 0.001 * Math.sin(i / 5.0); "28/10 13:${i % 60} $b ${b + 0.0004} ${b - 0.0004} ${b + 0.0002} ${100 + i}" }
        val out2 = c.callTool("analisar", mapOf("velas" to txt), st)
        assertTrue(out2, out2.contains("120 velas") && out2.contains("MACD"))
        val blocks = c.snapshot(st)
        assertTrue(blocks.any { it is UiBlock.Note })
        val (passed, report) = c.selfTest(st, mapOf("analisar" to mapOf("velas" to klines(250))))
        assertTrue(report, passed)
    }

    @Test fun `suite nativa e funcao no compute`() {
        val v = Compute.eval("indicators(candles(x))", mapOf("x" to klines(260)), null) as Map<*, *>
        assertTrue((v["rows"] as List<*>).size >= 35)
        assertTrue(v["resumo"] in setOf("Forte compra", "Compra", "Neutro", "Venda", "Forte venda"))
    }
}
