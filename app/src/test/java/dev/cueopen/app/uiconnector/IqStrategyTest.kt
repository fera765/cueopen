package dev.cueopen.app.uiconnector

import dev.cueopen.app.uiconnector.iqoption.IqRules
import dev.cueopen.app.uiconnector.iqoption.Masaniello
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IqStrategyTest {
    /** Propriedade do Masaniello: QUALQUER sequência que chega a K acertos termina no mesmo alvo; nunca perde mais que a banca. */
    @Test fun `masaniello atinge o alvo em qualquer ordem e limita a perda`() {
        val n = 10; val k = 5; val odds = 1.85; val bank = 10.0
        val target = Masaniello.target(bank, n, k, odds)
        for (mask in 0 until (1 shl n)) {
            var b = bank; var nn = n; var kk = k
            for (i in 0 until n) {
                val st = Masaniello.stake(b, nn, kk, odds) ?: break
                if ((mask shr i) and 1 == 1) { b += st * (odds - 1); kk-- } else b -= st
                nn--
            }
            if (kk == 0) assertEquals("mask $mask", target, b, 1e-6)
            else assertTrue("mask $mask banca $b", b >= -1e-9)
        }
        assertEquals(1.658, Masaniello.stake(10.0, 10, 5, 1.85)!!, 0.001)
    }

    @Test fun `pips a favor da operacao`() {
        assertEquals("+2.5 pips", IqRules.favorDiff("EURUSD", "call", 1.10000, 1.10025))
        assertEquals("−2.5 pips", IqRules.favorDiff("EURUSD-OTC", "put", 1.10000, 1.10025))
        assertEquals("+3.0 pips", IqRules.favorDiff("USDJPY", "put", 150.000, 149.970))
        assertEquals("+12.5 pts", IqRules.favorDiff("BTCUSD", "call", 60000.0, 60012.5))
        assertEquals("—", IqRules.favorDiff("EURUSD", "call", null, 1.1))
    }

    @Test fun `resultado liquido e direcao`() {
        assertEquals(0.85, IqRules.netPnl("win", 1.0, 1.85), 1e-9)
        assertEquals(0.85, IqRules.netPnl("win", 1.0, 0.85), 1e-9)
        assertEquals(-1.0, IqRules.netPnl("loose", 1.0, 0.0), 1e-9)
        assertEquals("call", IqRules.direction("Compra", false))
        assertEquals(null, IqRules.direction("Compra", true))
    }

    @Test fun `backtest roda sobre a serie e respeita a conta`() {
        val bars = (0 until 400).map { i -> val o = 1.1 + 0.002 * Math.sin(i / 9.0); val c = o + 0.0003 * Math.sin(i / 2.0); IqRules.Bar(o, maxOf(o, c) + 0.0001, minOf(o, c) - 0.0001, c, 100.0) }
        val b = IqRules.backtest(bars, 0.85, false)
        assertEquals(b.trades, b.wins + b.losses + b.ties)
        assertEquals(b.wins * 0.85 - b.losses, b.flatPnl, 1e-9)
        assertEquals(1 / 1.85, b.breakEven, 1e-9)
    }
}
