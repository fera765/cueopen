package dev.fluwy.app.trading

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class TradeGuardTest {
    @Test fun `sides sao interpretados`() {
        assertEquals(Side.BUY, Sides.parse("CALL")); assertEquals(Side.BUY, Sides.parse(" buy "))
        assertEquals(Side.SELL, Sides.parse("put")); assertEquals(Side.SELL, Sides.parse("sell"))
        assertNull(Sides.parse("hold")); assertNull(Sides.parse(null))
    }

    @Test fun `swap legs`() {
        val tok = "Tok3n11111111111111111111111111111111111111"
        val usdc = SwapLegs.STABLES.first()
        assertEquals(listOf(SwapLegs.Leg(tok, Side.BUY)), SwapLegs.legs(SwapLegs.SOL, tok))
        assertEquals(listOf(SwapLegs.Leg(tok, Side.SELL)), SwapLegs.legs(tok, usdc))
        assertEquals(listOf(SwapLegs.Leg("A", Side.SELL), SwapLegs.Leg("B", Side.BUY)), SwapLegs.legs("A", "B"))
        assertEquals(listOf(SwapLegs.Leg(SwapLegs.SOL, Side.SELL)), SwapLegs.legs(SwapLegs.SOL, usdc))
        assertEquals(listOf(SwapLegs.Leg(SwapLegs.SOL, Side.BUY)), SwapLegs.legs(usdc, SwapLegs.SOL))
        assertTrue(SwapLegs.legs(usdc, SwapLegs.STABLES.last()).isEmpty())
    }

    @Test fun `SOL do coordenador bate com o do cliente de swap`() =
        assertEquals(dev.fluwy.app.wallet.SolanaSwapClient.SOL_MINT, SwapLegs.SOL)

    @Test fun `reservas de ativo entre agentes expiram e podem ser liberadas`() {
        val venue = "iqoption-${UUID.randomUUID()}"
        val asset = "XAUUSD_${UUID.randomUUID()}"
        assertNull(TradeCoordinator.claim(venue, asset, "a1", 1000, 0))
        assertEquals("a1", TradeCoordinator.claim(venue, asset.lowercase(), "a2", 1000, 500))
        assertNull(TradeCoordinator.claim(venue, asset, "a2", 1000, 1500))
        TradeCoordinator.release(venue, asset, "a2")
        assertNull(TradeCoordinator.claim(venue, asset, "a3", 1000, 1600))
    }

    @Test fun `venues diferentes tem locks separados e nomes equivalentes compartilham lock`() = runBlocking {
        val suffix = UUID.randomUUID().toString()
        val a = TradeCoordinator.venueLock("iqoption-$suffix")
        val b = TradeCoordinator.venueLock("solana-$suffix")
        assertTrue(a.tryLock()); assertTrue(b.tryLock())
        assertFalse(TradeCoordinator.venueLock("IQOption-$suffix").tryLock())
        a.unlock(); b.unlock()
    }
}
