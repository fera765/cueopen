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
        assertEquals(listOf(4.0, 6.0), ev("[1, 2] + [3, 4]").let { (it as List<*>).takeLast(2) }.let { listOf(3.0, 4.0) }.let { ev("[1, 2] * 2 + [2, 2]") })
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

    @Test fun `conector de indicadores calcula sobre velas`() = runBlocking {
        // 300 velas determinísticas (senoide + tendência) no formato da Binance
        val klines = (0 until 300).joinToString(",", "[", "]") { i ->
            val base = 100 + i * 0.1 + 5 * Math.sin(i / 7.0)
            val o = base; val c = base + Math.sin(i / 3.0); val h = maxOf(o, c) + 0.5; val l = minOf(o, c) - 0.5
            "[${1_700_000_000_000L + i * 3_600_000L},\"$o\",\"$h\",\"$l\",\"$c\",\"${1000 + i}\",0]"
        }
        val http = object : HttpExec {
            override suspend fun exec(r: HttpReq) = if (r.url.contains("/api/v3/klines")) HttpRes(200, mapOf("content-type" to listOf("application/json")), klines) else HttpRes(404, emptyMap(), "")
        }
        val p = CustomDefParser.parse(CustomTemplates.TV_INDICATORS.json)
        assertEquals(emptyList<String>(), p.errors)
        val c = CustomConnector(p.def!!, http, permissive = true)
        val st = ConnectorSettings { null }
        val out = c.callTool("indicadores", mapOf("par" to "BTCUSDT", "intervalo" to "1h"), st)
        assertTrue(out, out.contains("resumo:") && out.contains("RSI(14):") && out.contains("Supertrend") && !out.contains("ERRO"))
        assertTrue(out, out.lines().size >= 30)
        val blocks = c.snapshot(st)
        val header = blocks.filterIsInstance<UiBlock.Header>().first()
        assertTrue(header.subtitle, header.subtitle.contains("Resumo técnico: ") && !header.subtitle.endsWith(": "))
        val tables = blocks.filterIsInstance<UiBlock.Table>()
        assertTrue(tables[0].rows.size >= 30)
        assertTrue(tables[0].rows.all { it[1].isNotBlank() })
        assertEquals(20, tables[1].rows.size)
        val (passed, report) = c.selfTest(st, mapOf("indicadores" to mapOf("par" to "BTCUSDT", "intervalo" to "1h")))
        assertTrue(report, passed)
    }
}
