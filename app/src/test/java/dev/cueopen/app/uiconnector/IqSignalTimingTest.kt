package dev.cueopen.app.uiconnector

import dev.cueopen.app.uiconnector.iqoption.IqSignalTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IqSignalTimingTest {
    private val alignedNowSec = 1_800_000_000L // alinhado a M1/M2/M5/M10

    @Test fun `alvo fica pelo menos dois minutos a frente e alinha ao timeframe escolhido`() {
        val nowMs = alignedNowSec * 1_000L
        for (timeframe in listOf(60, 120, 300, 600)) {
            assertEquals(alignedNowSec + 120L, IqSignalTiming.nextOpenAtLeastLead(nowMs, timeframeSeconds = timeframe))
            val expectedAfter1ms = Math.floorDiv(alignedNowSec + 120L + 1L + timeframe - 1L, timeframe.toLong()) * timeframe
            assertEquals(expectedAfter1ms, IqSignalTiming.nextOpenAtLeastLead(nowMs + 1L, timeframeSeconds = timeframe))
            val target = IqSignalTiming.nextOpenAtLeastLead(nowMs + 1L, timeframeSeconds = timeframe)
            assertTrue(target * 1_000L - (nowMs + 1L) >= 120_000L)
            assertEquals(0L, target % timeframe)
        }
    }

    @Test fun `analise e revalidacao ocorrem em T menos dois minutos e dez segundos`() {
        val target = alignedNowSec + 600L
        assertEquals((target - 120L) * 1_000L, IqSignalTiming.analysisAtMs(target))
        assertEquals((target - 10L) * 1_000L, IqSignalTiming.revalidateAtMs(target))
    }

    @Test fun `somente candles fechados ate o corte sao considerados no timeframe escolhido`() {
        val target = alignedNowSec + 600L
        for (timeframe in listOf(60, 120, 300, 600)) {
            assertEquals(target - 2L * timeframe, IqSignalTiming.latestClosedCandleOpen(target - 10L, timeframe.toLong()))
            assertEquals(target - timeframe, IqSignalTiming.latestClosedCandleOpen(target, timeframe.toLong()))
        }
        assertTrue(IqSignalTiming.isExpectedCandle(target, target))
        assertFalse(IqSignalTiming.isExpectedCandle(target - 60L, target))
    }

    @Test fun `janela de entrada rejeita instante anterior e atraso acima do limite`() {
        val target = alignedNowSec + 120L
        assertFalse(IqSignalTiming.isWithinEntryWindow(target * 1_000L - 1L, target, 10L))
        assertTrue(IqSignalTiming.isWithinEntryWindow(target * 1_000L, target, 10L))
        assertTrue(IqSignalTiming.isWithinEntryWindow(target * 1_000L + 10_000L, target, 10L))
        assertFalse(IqSignalTiming.isWithinEntryWindow(target * 1_000L + 10_001L, target, 10L))
    }
}
