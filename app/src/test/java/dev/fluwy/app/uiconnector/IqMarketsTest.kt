package dev.fluwy.app.uiconnector

import dev.fluwy.app.uiconnector.iqoption.IqProtocol
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IqMarketsTest {
    private fun obj(s: String) = Json.parseToJsonElement(s) as JsonObject

    @Test fun `instrumentos Forex abertos e fechados por horario`() {
        val now = 1_000L
        val fx = IqProtocol.parseForexInstruments(obj("""{"instruments":[
            {"id":"EURUSD","name":"front.EURUSD","active_id":1,"schedule":[[900,2000]],"is_suspended":false},
            {"id":"GBPUSD","name":"GBPUSD","active_id":2,"schedule":[[1500,2000]],"is_suspended":false}]}"""), now)
        assertEquals("EURUSD", fx[0].ticker); assertTrue(fx[0].open); assertFalse(fx[1].open)
        assertEquals("forex", fx[0].kind); assertEquals("EURUSD", fx[0].instrumentId)
    }

    @Test fun `cotacao ao vivo e resultado parcial`() {
        val q = IqProtocol.parseQuote(obj("""{"active_id":76,"close":1.2345}"""))!!
        assertEquals(76, q.activeId); assertEquals(1.2345, q.price, 0.0)
        assertEquals("WIN", IqProtocol.liveOutcome("call", 1.0, 1.1))
        assertEquals("LOSS", IqProtocol.liveOutcome("call", 1.0, 0.9))
        assertEquals("WIN", IqProtocol.liveOutcome("put", 1.0, 0.9))
        assertEquals("EMPATE", IqProtocol.liveOutcome("put", 1.0, 1.0))
        assertEquals("—", IqProtocol.liveOutcome("put", null, 1.0))
        assertTrue(IqProtocol.subscribeQuotesMessage(76).contains("\"candle-generated\""))
    }

    @Test fun `operacao aberta fora do app e ordem Forex`() {
        val o = IqProtocol.parseOptionOpened(obj("""{"name":"socket-option-opened","msg":{"id":5,"active_id":76,"direction":"put","amount":2,"expiration_time":1700000060,"value":1.5}}"""))!!
        assertEquals(5L, o.optionId); assertEquals("put", o.direction); assertEquals(1.5, o.openPrice!!, 0.0)
        val m = IqProtocol.placeForexOrderMessage("r1", 10, "EURUSD", "buy", 1.0, 10, 20.0, 30.0)
        assertTrue(m.contains("\"place-order-temp\"") && m.contains("\"stop_lose_value\":20.0") && m.contains("\"leverage\":10"))
        val p = IqProtocol.parsePositions(obj("""{"positions":[{"id":9,"instrument_id":"EURUSD","instrument_type":"forex","side":"buy","invest":1,"leverage":10,"pnl_net":0.12,"status":"open"}]}"""))
        assertEquals(0.12, p[0].pnl!!, 0.0); assertEquals("buy", p[0].side)
        assertEquals(1.25, IqProtocol.parseOptionAck(obj("""{"status":2000,"msg":{"id":1,"value":1.25}}""")).openPrice!!, 0.0)
    }
}
