package dev.cueopen.app.uiconnector

import dev.cueopen.app.uiconnector.iqoption.IqRiskBlock
import dev.cueopen.app.uiconnector.iqoption.IqRiskLimits
import dev.cueopen.app.uiconnector.iqoption.IqRiskPolicy
import dev.cueopen.app.uiconnector.iqoption.RiskState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IqRiskPolicyTest {
    private val limits = IqRiskLimits(dailyStopLoss = 10.0, dailyTakeProfit = 10.0, maxConsecutiveLosses = 3, cooldownMillis = 30_000)

    @Test fun `stop diario bloqueia no limite`() {
        val result = IqRiskPolicy.evaluate(RiskState(dayPnl = -10.0), 1_000, limits)
        assertEquals(IqRiskBlock.DAILY_STOP_LOSS, result.block)
    }

    @Test fun `meta diaria bloqueia no limite`() {
        val result = IqRiskPolicy.evaluate(RiskState(dayPnl = 10.0), 1_000, limits)
        assertEquals(IqRiskBlock.DAILY_TAKE_PROFIT, result.block)
    }

    @Test fun `pausa vigente precede os demais checks`() {
        val result = IqRiskPolicy.evaluate(RiskState(dayPnl = -20.0, pausedUntil = 5_000), 1_000, limits)
        assertEquals(IqRiskBlock.PAUSED, result.block)
        assertEquals(5_000L, result.pauseUntilMillis)
    }

    @Test fun `limite de perdas consecutivas inicia pausa e zera contador`() {
        val state = RiskState(consecLosses = 3)
        val result = IqRiskPolicy.evaluate(state, 1_000, limits)
        assertEquals(IqRiskBlock.CONSECUTIVE_LOSSES, result.block)
        assertEquals(31_000L, result.updatedState.pausedUntil)
        assertEquals(0, result.updatedState.consecLosses)
        assertEquals(3, result.consecutiveLossesAtBlock)
    }

    @Test fun `estado abaixo dos limites segue permitido e inalterado`() {
        val state = RiskState(dayPnl = -2.0, consecLosses = 1)
        val result = IqRiskPolicy.evaluate(state, 1_000, limits)
        assertNull(result.block)
        assertEquals(state, result.updatedState)
    }
}
