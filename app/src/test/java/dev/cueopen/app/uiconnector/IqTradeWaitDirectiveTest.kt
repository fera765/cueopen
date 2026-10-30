package dev.cueopen.app.uiconnector

import dev.cueopen.app.uiconnector.iqoption.IqTradeWaitDirective
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IqTradeWaitDirectiveTest {
    @Test fun `consume removes valid markers and deduplicates trade IDs`() {
        val raw = IqTradeWaitDirective.append("Aguardando", 12, 30) + IqTradeWaitDirective.append("", 12, 20)
        val result = IqTradeWaitDirective.consume(raw)
        assertEquals("Aguardando", result.output)
        assertEquals(listOf(IqTradeWaitDirective.Wait(12, 30)), result.waits)
    }

    @Test fun `invalid marker remains inert and creates no wait`() {
        val result = IqTradeWaitDirective.consume("texto [[CUE_WAIT_TRADE id=0 seconds=90]]")
        assertTrue(result.waits.isEmpty())
    }

    @Test fun `wait includes post-expiry grace and caps long sleeps`() {
        assertEquals(32, IqTradeWaitDirective.secondsUntilExpiry(130, 100))
        assertEquals(2, IqTradeWaitDirective.secondsUntilExpiry(100, 100))
        assertEquals(86_400, IqTradeWaitDirective.secondsUntilExpiry(200_000, 100))
    }
}
