package dev.cueopen.app.uiconnector

import dev.cueopen.app.uiconnector.iqoption.PriceBar
import dev.cueopen.app.uiconnector.iqoption.TradeDirection
import dev.cueopen.app.uiconnector.iqoption.TwoCandleSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TwoCandleSignalTest {
    private fun risingBars(timeframe: Int, target: Long): List<PriceBar> =
        (0 until (target / timeframe).toInt()).map { index ->
            val open = 100.0 + index * 0.1
            val close = open + 0.05
            PriceBar(index * timeframe.toLong(), open, close + 0.01, open - 0.01, close, 1.0)
        }

    @Test fun `usa somente os dois candles fechados do timeframe selecionado`() {
        val target = 1_800L
        for (timeframe in TwoCandleSignal.SUPPORTED_TIMEFRAMES_SECONDS) {
            val result = TwoCandleSignal.analyze(risingBars(timeframe, target), target, target, timeframe)
            assertTrue("M${timeframe / 60}: ${result.reason}", result.allowed)
            assertEquals(timeframe, result.timeframeSeconds)
            assertEquals(TradeDirection.CALL, result.direction)
            assertEquals(listOf(target - 2L * timeframe, target - timeframe), result.candleOpens)
        }
    }

    @Test fun `periodo selecionado pode operar sem concordancia dos outros periodos`() {
        val target = 1_800L
        val m1 = risingBars(60, target)
        // O analisador recebe só M1; nenhum dado ou consenso M2/M5/M10 é requisito.
        val result = TwoCandleSignal.analyze(m1, target, target, 60)
        assertTrue(result.allowed)
        assertEquals(TradeDirection.CALL, result.direction)
    }

    @Test fun `vela-alvo ainda aberta e candles posteriores nao entram no par`() {
        val target = 1_800L
        val tf = 300
        val partialTarget = PriceBar(target, 50.0, 50.1, 49.9, 49.0, 1.0)
        val future = PriceBar(target + tf, 1.0, 1.1, 0.9, 0.8, 1.0)
        val result = TwoCandleSignal.analyze(risingBars(tf, target) + listOf(partialTarget, future), target, target, tf)
        assertTrue(result.allowed)
        assertEquals(listOf(target - 2L * tf, target - tf), result.candleOpens)
        assertEquals(TradeDirection.CALL, result.direction)
    }

    @Test fun `candle recente ausente impede usar um par antigo como se fosse atual`() {
        val target = 1_800L
        val tf = 120
        val bars = risingBars(tf, target).filterNot { it.timeSeconds == target - tf }
        val result = TwoCandleSignal.analyze(bars, target, target, tf)
        assertFalse(result.allowed)
        assertEquals(TradeDirection.NO_TRADE, result.direction)
        assertTrue(result.reason.contains("mais recente está ausente ou obsoleto"))
    }

    @Test fun `doji ou direcoes diferentes nos dois candles bloqueiam a entrada`() {
        val target = 1_800L
        val tf = 300
        val bars = risingBars(tf, target).map { bar ->
            when (bar.timeSeconds) {
                target - tf -> bar.copy(close = bar.open, high = bar.open, low = bar.open)
                else -> bar
            }
        }
        val doji = TwoCandleSignal.analyze(bars, target, target, tf)
        assertFalse(doji.allowed)
        assertEquals(TradeDirection.NO_TRADE, doji.direction)

        val mixedBars = risingBars(tf, target).map { bar ->
            if (bar.timeSeconds == target - tf) bar.copy(close = bar.open - 0.05, high = bar.open + 0.01, low = bar.open - 0.06) else bar
        }
        val mixed = TwoCandleSignal.analyze(mixedBars, target, target, tf)
        assertFalse(mixed.allowed)
        assertEquals(TradeDirection.NO_TRADE, mixed.direction)
    }

    @Test fun `prevalidacao em T menos dez usa somente candles ja fechados`() {
        val target = 1_800L
        val tf = 300
        val cutoff = target - 10L
        val result = TwoCandleSignal.analyze(risingBars(tf, target), target, cutoff, tf)
        assertTrue(result.allowed)
        assertEquals(listOf(target - 3L * tf, target - 2L * tf), result.candleOpens)
    }

    @Test fun `look-ahead alvo desalinhado e timeframe nao suportado sao recusados`() {
        val bars = risingBars(60, 1_800L)
        assertFalse(TwoCandleSignal.analyze(bars, 1_800L, 1_801L, 60).allowed)
        assertFalse(TwoCandleSignal.analyze(bars, 1_801L, 1_790L, 60).allowed)
        assertFalse(TwoCandleSignal.analyze(bars, 1_800L, 1_800L, 90).allowed)
    }
}
