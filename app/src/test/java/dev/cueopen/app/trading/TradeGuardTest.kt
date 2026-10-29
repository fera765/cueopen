package dev.cueopen.app.trading

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TradeGuardTest {
    private var now = 1_000_000L
    private fun gate() = SignalGate(clock = { now }, ttlMs = 60_000L, floor = 70)

    @Test fun `sides sao interpretados`() {
        assertEquals(Side.BUY, Sides.parse("CALL")); assertEquals(Side.BUY, Sides.parse(" buy "))
        assertEquals(Side.SELL, Sides.parse("put")); assertEquals(Side.SELL, Sides.parse("sell"))
        assertNull(Sides.parse("hold")); assertNull(Sides.parse(null))
    }

    @Test fun `piso de 70 nao pode ser rebaixado`() {
        val g = gate()
        assertFalse(g.approve("a", "solana", "SOL", Side.BUY, 69.0, 10))
        assertTrue(g.approve("a", "solana", "SOL", Side.BUY, 70.0, 10))
        assertFalse(g.approve("a", "solana", "BONK", Side.BUY, 75.0, 80)) // minimo do agente maior que o piso
    }

    @Test fun `gate rejeita confianca baixa e numeros fora do intervalo`() {
        val g = gate()
        assertFalse(g.approve("a", "solana", "SOL", Side.BUY, 90.0, 70, confidence = 69.9))
        assertFalse(g.approve("a", "solana", "SOL", Side.BUY, 101.0, 70, confidence = 90.0))
        assertTrue(g.approve("a", "solana", "SOL", Side.BUY, 90.0, 70, confidence = 70.0))
    }

    @Test fun `aprovacao e de uso unico`() {
        val g = gate()
        g.approve("a", "iqoption", "EURUSD", Side.BUY, 80.0, 70)
        assertNotNull(g.consume("a", "iqoption", setOf("eurusd"), Side.BUY))
        assertNull(g.consume("a", "iqoption", setOf("EURUSD"), Side.BUY))
    }

    @Test fun `lado agente e venue precisam bater`() {
        val g = gate()
        g.approve("a", "iqoption", "EURUSD", Side.BUY, 80.0, 70)
        assertNull(g.consume("a", "iqoption", setOf("EURUSD"), Side.SELL))
        assertNull(g.consume("b", "iqoption", setOf("EURUSD"), Side.BUY))
        assertNull(g.consume("a", "solana", setOf("EURUSD"), Side.BUY))
        assertNotNull(g.consume("a", "iqoption", setOf("EUR/USD"), Side.BUY))
    }

    @Test fun `aprovacao expira`() {
        val g = gate()
        g.approve("a", "solana", "SOL", Side.BUY, 90.0, 70)
        now += 60_001
        assertNull(g.consume("a", "solana", setOf("SOL"), Side.BUY))
        assertEquals(0, g.pendingCount())
    }

    @Test fun `venue em branco vale para qualquer venue e restore devolve`() {
        val g = gate()
        g.approve("a", null, "SOL", Side.SELL, 90.0, 70)
        val ap = g.consume("a", "solana", setOf("SOL"), Side.SELL)
        assertNotNull(ap)
        g.restore(ap!!)
        assertNotNull(g.consume("a", "qualquer", setOf("SOL"), Side.SELL))
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

    @Test fun `SOL do guard bate com o do cliente de swap`() =
        assertEquals(dev.cueopen.app.wallet.SolanaSwapClient.SOL_MINT, SwapLegs.SOL)

    @Test fun `parse de sinais e resultados`() {
        val ins = SignalIO.parseInputs("""[{"id":"EURUSD","direction":"call","venue":"iqoption","data":"x"},{"nada":1}]""")
        assertEquals(1, ins.size); assertEquals("iqoption", ins[0].venue)
        val res = SignalIO.parseResults("texto ```json [{\"id\":\"EURUSD\",\"accuracy\":\"85%\",\"confidence\":60,\"reason\":\"ok\"}] ```")
        assertEquals(85.0, res!![0].accuracy, 0.0)
        assertNull(SignalIO.parseResults("sem json"))
    }

    @Test fun `entrada e resultado estritos falham fechados`() {
        assertNull(SignalIO.parseInputsStrict("[{\"id\":\"EURUSD\",\"direction\":\"call\"},{\"id\":\"eur/usd\",\"direction\":\"put\"}]"))
        assertEquals(1, SignalIO.parseInputsStrict("[{\"id\":\"EURUSD\",\"venue\":\"iqoption\",\"direction\":\"call\",\"data\":{\"price\":1}}]")!!.size)
        assertEquals(false, SignalIO.parseInputsStrict("[{\"id\":\"EURUSD\",\"direction\":\"call\",\"data\":\"x\"}]")!!.single().hasEvidence)
        val valid = """[{"id":"EURUSD","accuracy":82.5,"confidence":75,"reason":"velas e direção alinhadas"}]"""
        assertEquals(82.5, SignalIO.parseResultsStrict(valid, listOf("EURUSD"))!!.single().accuracy, 0.0)
        val objectEnvelope = """{"results":[{"id":"EURUSD","accuracy":82.5,"confidence":75,"reason":"velas e direção alinhadas"}]}"""
        assertEquals(82.5, SignalIO.parseResultsStrict(objectEnvelope, listOf("EURUSD"))!!.single().accuracy, 0.0)
        assertNull(SignalIO.parseResultsStrict("texto $valid", listOf("EURUSD")))
        assertNull(SignalIO.parseResultsStrict("""[{"id":"EURUSD","accuracy":"82","confidence":75,"reason":"ok"}]""", listOf("EURUSD")))
        assertNull(SignalIO.parseResultsStrict("""[{"id":"EURUSD","accuracy":82,"confidence":75}]""", listOf("EURUSD")))
        assertNull(SignalIO.parseResultsStrict("""[{"id":"EURUSD","accuracy":82,"confidence":75,"reason":"ok"}]""", listOf("EURUSD", "BTCUSD")))
    }

    @Test fun `reservas de ativo entre agentes`() {
        assertNull(TradeCoordinator.claim("iqoption", "XAUUSD", "a1", 1000, 0))
        assertEquals("a1", TradeCoordinator.claim("iqoption", "xau/usd", "a2", 1000, 500))
        assertNull(TradeCoordinator.claim("iqoption", "XAUUSD", "a2", 1000, 1500)) // expirou
        TradeCoordinator.release("iqoption", "XAUUSD", "a2")
        assertNull(TradeCoordinator.claim("iqoption", "XAUUSD", "a3", 1000, 1600))
    }

    @Test fun `venues diferentes nao se bloqueiam`() = runBlocking {
        val a = TradeCoordinator.venueLock("iqoption")
        val b = TradeCoordinator.venueLock("solana")
        assertTrue(a.tryLock()); assertTrue(b.tryLock())
        assertFalse(TradeCoordinator.venueLock("IQOption").tryLock())
        a.unlock(); b.unlock()
    }
}
