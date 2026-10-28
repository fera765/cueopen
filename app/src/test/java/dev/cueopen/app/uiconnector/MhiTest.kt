package dev.cueopen.app.uiconnector

import dev.cueopen.app.uiconnector.iqoption.Mhi
import org.junit.Assert.assertEquals
import org.junit.Test

class MhiTest {
    private fun c(t: Long, up: Boolean?) = Mhi.M1(t, 1.0, when (up) { true -> 1.1; false -> 0.9; null -> 1.0 })

    @Test fun `minoria e maioria pelas 3 ultimas velas do quadrante`() {
        val q = 3000L // múltiplo de 300
        val m = listOf(c(q - 180, true), c(q - 120, true), c(q - 60, false)).associateBy { it.from }
        assertEquals("put", Mhi.direction(m, q, minority = true))   // 2 verdes, 1 vermelha → minoria vermelha
        assertEquals("call", Mhi.direction(m, q, minority = false))
        val doji = listOf(c(q - 180, true), c(q - 120, null), c(q - 60, false)).associateBy { it.from }
        assertEquals(null, Mhi.direction(doji, q, true))
    }

    @Test fun `backtest conta G0 G1 G2`() {
        val q = 3000L
        // quadrante anterior: últimas 3 verdes,verdes,vermelha → MHI1 minoria = PUT; entradas q (verde), q+60 (vermelha) → vitória no G1
        val candles = listOf(c(q - 300, true), c(q - 240, true), c(q - 180, true), c(q - 120, true), c(q - 60, false),
            c(q, true), c(q + 60, false), c(q + 120, true), c(q + 180, true), c(q + 240, true))
        val st = Mhi.backtest(candles, Mhi.VARIANTS[0])
        assertEquals(1, st.signals); assertEquals(0, st.g0); assertEquals(1, st.g1)
        val up = Mhi.upcoming(candles.take(5), q + 3, 10)
        assertEquals("put", up.first { it.variant.name == "MHI 1 (minoria)" }.direction)
    }
}
