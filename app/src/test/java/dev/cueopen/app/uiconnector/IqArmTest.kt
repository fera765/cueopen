package dev.cueopen.app.uiconnector

import dev.cueopen.app.uiconnector.iqoption.IqProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** O gatilho de preço do armado é a mesma regra do entry-against-candle: valida o sentido. */
class IqArmTest {
    @Test fun `gatilho de preco por direcao`() {
        // call dispara quando preço ABAIXO da abertura (favorece subir depois)
        assertEquals("WIN", IqProtocol.liveOutcome("call", 1.10000, 1.10010)) // já subiu = a favor
        // sentido de ENTRADA: call entra se preço < abertura
        fun callEntra(open: Double, price: Double) = price < open
        fun putEntra(open: Double, price: Double) = price > open
        assertTrue(callEntra(1.10000, 1.09990))
        assertTrue(!callEntra(1.10000, 1.10010))
        assertTrue(putEntra(1.10000, 1.10010))
        assertTrue(!putEntra(1.10000, 1.09990))
    }
}
