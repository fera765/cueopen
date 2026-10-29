package dev.cueopen.app.uiconnector

import dev.cueopen.app.uiconnector.iqoption.PriceActionAnalyzer
import dev.cueopen.app.uiconnector.iqoption.PriceActionBacktest
import dev.cueopen.app.uiconnector.iqoption.PriceActionExecutionGate
import dev.cueopen.app.uiconnector.iqoption.PriceBar
import dev.cueopen.app.uiconnector.iqoption.PriceSeries
import dev.cueopen.app.uiconnector.iqoption.PriceSetup
import dev.cueopen.app.uiconnector.iqoption.TradeDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PriceActionAnalyzerTest {
    private val analyzer = PriceActionAnalyzer()

    private fun flatBars(count: Int = 40): List<PriceBar> = (0 until count).map { i ->
        PriceBar(i * 60L, 100.0, 100.1, 99.9, 100.0)
    }

    @Test fun `rompimento altista forte produz CALL explicavel`() {
        val bars = flatBars() + PriceBar(40 * 60L, 100.0, 100.35, 99.95, 100.3)
        val result = analyzer.analyze(PriceSeries("TEST", 60, bars))
        assertEquals(TradeDirection.CALL, result.decision)
        assertTrue(PriceSetup.BREAKOUT in result.setups)
        assertTrue(result.strongSetup)
        assertTrue(result.reasons.any { it.contains("rompeu") })
        assertTrue(result.dataValid)
    }

    @Test fun `rompimento baixista forte produz PUT`() {
        val bars = flatBars() + PriceBar(40 * 60L, 100.0, 100.05, 99.65, 99.7)
        val result = analyzer.analyze(PriceSeries("TEST", 60, bars))
        assertEquals(TradeDirection.PUT, result.decision)
        assertTrue(PriceSetup.BREAKOUT in result.setups)
    }

    @Test fun `poucos candles levam a nao operar`() {
        val result = analyzer.analyze(PriceSeries("TEST", 60, flatBars(10)))
        assertEquals(TradeDirection.NO_TRADE, result.decision)
        assertFalse(result.dataValid)
    }

    @Test fun `ohlc inconsistente ou tempos fora de ordem bloqueiam decisao`() {
        val invalid = flatBars().toMutableList().also { it[10] = PriceBar(600, 100.0, 99.0, 98.0, 100.0) }
        assertEquals(TradeDirection.NO_TRADE, analyzer.analyze(PriceSeries("TEST", 60, invalid)).decision)
        val unordered = flatBars().toMutableList().also { it[20] = it[19].copy(timeSeconds = it[19].timeSeconds) }
        assertEquals(TradeDirection.NO_TRADE, analyzer.analyze(PriceSeries("TEST", 60, unordered)).decision)
        val gapped = flatBars().toMutableList().also { it[20] = it[20].copy(timeSeconds = it[19].timeSeconds + 180) }
        assertEquals(TradeDirection.NO_TRADE, analyzer.analyze(PriceSeries("TEST", 60, gapped)).decision)
    }

    @Test fun `gate recusa lado divergente e modo forte recusa rejeicao fraca`() {
        val breakout = flatBars() + PriceBar(40 * 60L, 100.0, 100.35, 99.95, 100.3)
        val call = analyzer.analyze(PriceSeries("TEST", 60, breakout))
        assertFalse(PriceActionExecutionGate.authorize(call, "put").allowed)
        assertTrue(PriceActionExecutionGate.authorize(call, "call", requireStrong = true).allowed)

        val rejection = flatBars() + PriceBar(40 * 60L, 100.0, 100.1, 99.85, 100.03)
        val rejectedAnalysis = analyzer.analyze(PriceSeries("TEST", 60, rejection))
        assertEquals(TradeDirection.CALL, rejectedAnalysis.decision)
        assertTrue(PriceActionExecutionGate.authorize(rejectedAnalysis, "call").allowed)
        assertFalse(PriceActionExecutionGate.authorize(rejectedAnalysis, "call", requireStrong = true).allowed)
    }

    @Test fun `replay usa o sinal apenas para avaliar a vela seguinte`() {
        val bars = flatBars(40) + listOf(
            PriceBar(40 * 60L, 100.0, 100.35, 99.95, 100.3), // sinal fechado
            PriceBar(41 * 60L, 100.3, 100.5, 100.2, 100.4), // resultado WIN para CALL
        )
        val result = PriceActionBacktest.run(bars, payout = 0.85, strongOnly = false, window = 30)
        assertEquals(1, result.trades)
        assertEquals(1, result.calls)
        assertEquals(1, result.wins)
        assertEquals(0, result.losses)
        assertEquals(0.85, result.flatPnl, 1e-9)
        assertEquals(1.0 / 1.85, result.breakEven, 1e-9)
    }

    @Test fun `replay descarta candle de resultado com OHLC invalido`() {
        val bars = flatBars(40) + listOf(
            PriceBar(40 * 60L, 100.0, 100.35, 99.95, 100.3),
            PriceBar(41 * 60L, 100.3, 100.35, 100.2, 100.4), // close acima do high
        )
        val result = PriceActionBacktest.run(bars, payout = 0.85, strongOnly = false, window = 30)
        assertEquals(0, result.trades)
        assertEquals(1, result.invalidResultCandles)
    }

    @Test fun `replay descarta resultado fora do timestamp da vela seguinte`() {
        val bars = flatBars(40) + listOf(
            PriceBar(40 * 60L, 100.0, 100.35, 99.95, 100.3),
            PriceBar(42 * 60L, 100.3, 100.5, 100.2, 100.4), // uma vela intermediária ausente
        )
        val result = PriceActionBacktest.run(bars, payout = 0.85, strongOnly = false, window = 30)
        assertEquals(0, result.trades)
        assertEquals(1, result.invalidResultCandles)
    }
}
