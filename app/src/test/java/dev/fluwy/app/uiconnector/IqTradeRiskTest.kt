package dev.fluwy.app.uiconnector

import dev.fluwy.app.uiconnector.iqoption.IqTradeRisk
import dev.fluwy.app.uiconnector.iqoption.RiskState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class IqTradeRiskTest {
    @Test fun `serializa e le somente o ledger de risco`() {
        val state = RiskState(day = "2026-10-30", dayPnl = -2.5, dayTrades = 4, consecLosses = 2, pausedUntil = 1234)
        assertEquals(state, IqTradeRisk.decode(IqTradeRisk.encode(state)))
        assertNotNull(IqTradeRisk.decode("{\"day\":\"d\",\"extra\":1}"))
    }

    @Test fun `calcula resultado e diferenca de exibicao sem sinal de estrategia`() {
        assertEquals(0.85, IqTradeRisk.netPnl("win", 1.0, 1.85), 1e-9)
        assertEquals(-1.0, IqTradeRisk.netPnl("loose", 1.0, 0.0), 1e-9)
        assertEquals("+2.5 pips", IqTradeRisk.favorDiff("EURUSD", "call", 1.10000, 1.10025))
        assertEquals("−2.5 pips", IqTradeRisk.favorDiff("EURUSD-OTC", "put", 1.10000, 1.10025))
    }
}
