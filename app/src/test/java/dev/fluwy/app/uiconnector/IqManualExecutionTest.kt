package dev.fluwy.app.uiconnector

import dev.fluwy.app.uiconnector.iqoption.IqCloseAck
import dev.fluwy.app.uiconnector.iqoption.IqForexOrder
import dev.fluwy.app.uiconnector.iqoption.IqManualExecution
import dev.fluwy.app.uiconnector.iqoption.IqManualRequestPort
import dev.fluwy.app.uiconnector.iqoption.IqOptionOrder
import dev.fluwy.app.uiconnector.iqoption.IqProtocol
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IqManualExecutionTest {
    private class FakeWire : IqManualRequestPort {
        data class Sent(val id: String, val payload: String, val names: List<String>, val timeout: Long)
        val sent = mutableListOf<Sent>()
        val answers = ArrayDeque<JsonObject>()
        override suspend fun request(requestId: String, payload: String, expectedNames: List<String>, timeoutMs: Long): JsonObject {
            sent += Sent(requestId, payload, expectedNames, timeoutMs)
            return answers.removeFirst()
        }
    }
    private fun obj(text: String) = IqProtocol.parseObject(text)!!

    @Test fun `ordem de opcao monta mensagem binaria pelo transporte falso`() = runBlocking {
        val wire = FakeWire().apply { answers += obj("""{"status":2000,"msg":{"id":42,"value":1.85}}""") }
        val execution = IqManualExecution({ "r-1" }, wire)
        val ack = execution.openOption(IqOptionOrder(7, 76, "call", 1_700_000_060, 2.0, true))
        assertEquals(42L, ack.optionId)
        val envelope = obj(wire.sent.single().payload)
        assertEquals("r-1", envelope["request_id"]!!.jsonPrimitive.content)
        val command = envelope["msg"]!!.jsonObject
        assertEquals("binary-options.open-option", command["name"]!!.jsonPrimitive.content)
        assertEquals(listOf("option", "option-rejected"), wire.sent.single().names)
    }

    @Test fun `fechamento Forex sem status nao e confirmado`() = runBlocking {
        val wire = FakeWire().apply { answers += obj("""{"msg":{}}""") }
        val execution = IqManualExecution({ "r-unknown" }, wire)
        assertEquals(IqCloseAck(false, null), execution.closeForex(88))
    }

    @Test fun `ordem forex envia apenas tipo forex com stop e take`() = runBlocking {
        val wire = FakeWire().apply { answers += obj("""{"status":2000,"msg":{"id":88}}""") }
        val execution = IqManualExecution({ "r-2" }, wire)
        assertEquals(88L, execution.openForex(IqForexOrder(7, "EURUSD", "sell", 1.0, 5, 20.0, 30.0)).optionId)
        val inner = obj(wire.sent.single().payload)["msg"]!!.jsonObject
        assertEquals("place-order-temp", inner["name"]!!.jsonPrimitive.content)
        val body = inner["body"]!!.jsonObject
        assertEquals("forex", body["instrument_type"]!!.jsonPrimitive.content)
        assertEquals("sell", body["side"]!!.jsonPrimitive.content)
        assertEquals("20.0", body["stop_lose_value"]!!.jsonPrimitive.content)
        assertEquals("30.0", body["take_profit_value"]!!.jsonPrimitive.content)
    }

    @Test fun `fechamento Forex so e aceito em ACK de sucesso`() = runBlocking {
        val wire = FakeWire().apply { answers += obj("""{"status":2000,"msg":{}}""") }
        val execution = IqManualExecution({ "r-3" }, wire)
        assertEquals(IqCloseAck(true, 2000), execution.closeForex(88))
        val inner = obj(wire.sent.single().payload)["msg"]!!.jsonObject
        assertEquals("close-position", inner["name"]!!.jsonPrimitive.content)
        assertEquals("position-closed", wire.sent.single().names.last())
        assertTrue(wire.sent.single().timeout > 0)
    }

    @Test fun `alteracao Forex espera tpsl-changed e so aceita status de sucesso`() = runBlocking {
        val wire = FakeWire().apply { answers += obj("""{"name":"tpsl-changed","status":2000,"msg":{"position_id":88}}""") }
        val execution = IqManualExecution({ "unused" }, wire)
        assertEquals(IqCloseAck(true, 2000), execution.adjustForexStops(88, 20.0, 30.0))
        assertEquals("", wire.sent.single().id)
        assertEquals(listOf("tpsl-changed"), wire.sent.single().names)
        val body = obj(wire.sent.single().payload)["msg"]!!.jsonObject["body"]!!.jsonObject
        assertEquals(88L, body["position_id"]!!.jsonPrimitive.content.toLong())
        assertEquals("percent", body["stop_lose_kind"]!!.jsonPrimitive.content)
        assertEquals("percent", body["take_profit_kind"]!!.jsonPrimitive.content)
    }

    @Test fun `alteracao Forex sem status e recusada`() = runBlocking {
        val wire = FakeWire().apply { answers += obj("""{"name":"tpsl-changed","msg":{}}""") }
        val execution = IqManualExecution({ "unused" }, wire)
        assertEquals(IqCloseAck(false, null), execution.adjustForexStops(88, 20.0, 30.0))
    }

    @Test fun `alteracao Forex com status diferente de 2000 nao e sucesso`() = runBlocking {
        val wire = FakeWire().apply { answers += obj("""{"name":"tpsl-changed","status":2010,"msg":{}}""") }
        val execution = IqManualExecution({ "unused" }, wire)
        assertEquals(IqCloseAck(false, 2010), execution.adjustForexStops(88, 20.0, 30.0))
    }
}
