package dev.cueopen.app.uiconnector

import dev.cueopen.app.uiconnector.iqoption.IqAccountMessageFlow
import dev.cueopen.app.uiconnector.iqoption.IqProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fake transport feeds observed message shapes into the production account-message flow. */
class IqAccountMessageFlowTest {
    private class FakeTransport {
        val flow = IqAccountMessageFlow()
        var generation = 0L
        var connectionWaiter: IqAccountMessageFlow.Waiter? = null

        fun connect(newGeneration: Long) {
            generation = newGeneration
            connectionWaiter = flow.beginConnection(newGeneration)
        }

        fun requestSwitch(balanceId: Long): IqAccountMessageFlow.Waiter =
            flow.beginSwitch(balanceId, generation)

        fun request(id: String, name: String): IqAccountMessageFlow.Waiter =
            flow.registerWaiter(id, setOf(name))

        fun receive(raw: String, sourceGeneration: Long = generation): IqAccountMessageFlow.AccountUpdate? =
            flow.onMessage(IqProtocol.parseObject(raw)!!, sourceGeneration)

        fun timeout(waiter: IqAccountMessageFlow.Waiter) {
            waiter.deferred.completeExceptionally(IllegalStateException("simulated request timeout"))
            flow.removeWaiter(waiter)
        }

        fun failSwitch(balanceId: Long, waiter: IqAccountMessageFlow.Waiter) {
            flow.removeWaiter(waiter)
            flow.finishSwitch(balanceId, confirmed = false, reason = "timeout fake")
        }
    }

    private fun initialProfile(balanceId: Long = 101L) = """
        {"name":"profile","msg":{"balance_id":$balanceId,"balances":[
          {"id":101,"type":4,"amount":10000,"currency":"USD"},
          {"id":202,"type":1,"amount":25,"currency":"BRL"}
        ]}}
    """.trimIndent()

    @Test fun `evento profile inicial confirma somente a conta anunciada no login`() {
        val transport = FakeTransport()
        transport.connect(1L)

        val update = transport.receive(initialProfile())

        assertEquals("profile", update?.eventName)
        assertTrue(update!!.accepted)
        assertTrue(transport.connectionWaiter!!.deferred.isCompleted)
        assertEquals(101L, transport.flow.activeBalance()?.id)
        assertEquals(101L, transport.flow.orderBalance()?.id)
        assertTrue(transport.flow.authenticated)
    }

    @Test fun `profile parcial com balance_id exato confirma troca e evento DEMO atrasado nao reverte`() {
        val transport = FakeTransport()
        transport.connect(10L)
        transport.receive(initialProfile())
        val switchWaiter = transport.requestSwitch(202L)

        val oldProfile = transport.receive(
            """{"name":"profile","msg":{"balance_id":101,"balances":[{"id":101,"type":4,"amount":10000,"currency":"USD"}]}}""",
        )
        assertFalse(oldProfile!!.accepted)
        assertFalse(switchWaiter.deferred.isCompleted)
        assertNull(transport.flow.orderBalance())

        val targetProfile = transport.receive("""{"name":"profile","msg":{"balance_id":202}}""")
        assertTrue(targetProfile!!.accepted)
        assertTrue(switchWaiter.deferred.isCompleted)
        transport.flow.finishSwitch(202L, confirmed = true, reason = null)
        assertEquals(202L, transport.flow.orderBalance()?.id)

        val delayedDemo = transport.receive(
            """{"name":"balance-changed","msg":{"current_balance":{"id":101,"type":4,"amount":10000,"currency":"USD"}}}""",
        )
        assertFalse(delayedDemo!!.accepted)
        assertEquals(202L, transport.flow.orderBalance()?.id)
    }

    @Test fun `balance-changed confirma apenas o ID alvo em formato aninhado ou plano`() {
        val transport = FakeTransport()
        transport.connect(20L)
        transport.receive(initialProfile())

        val nestedWaiter = transport.requestSwitch(202L)
        val nested = transport.receive(
            """{"name":"balance-changed","msg":{"current_balance":{"id":202,"type":1,"amount":25,"currency":"BRL"}}}""",
        )
        assertEquals(202L, nested?.serverBalanceId)
        assertTrue(nested!!.accepted)
        assertTrue(nestedWaiter.deferred.isCompleted)
        transport.flow.finishSwitch(202L, confirmed = true, reason = null)
        assertEquals(202L, transport.flow.orderBalance()?.id)

        val demoWaiter = transport.requestSwitch(101L)
        val flat = transport.receive(
            """{"name":"balance-changed","msg":{"id":101,"type":4,"amount":10000,"currency":"USD"}}""",
        )
        assertEquals(101L, flat?.serverBalanceId)
        assertTrue(flat!!.accepted)
        assertTrue(demoWaiter.deferred.isCompleted)
        transport.flow.finishSwitch(101L, confirmed = true, reason = null)
        assertEquals(101L, transport.flow.orderBalance()?.id)
    }

    @Test fun `timeout generico de positions nao confirma nem falha a troca sem resposta`() {
        val transport = FakeTransport()
        transport.connect(30L)
        transport.receive(initialProfile())
        val switchWaiter = transport.requestSwitch(202L)
        val positionsWaiter = transport.request("cue-positions", "positions")

        // A resposta antiga de positions não casa por nome quando tem request_id divergente.
        transport.receive("""{"name":"positions","request_id":"cue-old","msg":{"positions":[]}}""")
        assertFalse(positionsWaiter.deferred.isCompleted)
        assertFalse(switchWaiter.deferred.isCompleted)

        // Simula o timeout isolado de outra requisição; não toca no waiter da conta.
        transport.timeout(positionsWaiter)
        assertTrue(positionsWaiter.deferred.isCompleted)
        assertFalse(switchWaiter.deferred.isCompleted)
        transport.receive("""{"name":"instruments","request_id":"cue-instruments","msg":{"instruments":[]}}""")
        assertFalse(switchWaiter.deferred.isCompleted)

        transport.failSwitch(202L, switchWaiter)
        assertTrue(transport.flow.selection.switchFailed())
        assertEquals("timeout fake", transport.flow.selection.failureMessage())
        assertNull(transport.flow.activeBalance())
        assertNull(transport.flow.orderBalance())
    }

    @Test fun `resync explicito cria nova geracao e ignora ACK tardio do socket anterior`() {
        val transport = FakeTransport()
        transport.connect(40L)
        transport.receive(initialProfile())
        val switchWaiter = transport.requestSwitch(202L)
        transport.failSwitch(202L, switchWaiter)
        assertTrue(transport.flow.selection.switchFailed())
        assertNull(transport.flow.orderBalance())

        // O novo socket só começa no caminho explícito de reconciliação; até chegar profile, não há carteira operável.
        transport.connect(41L)
        assertNull(transport.flow.activeBalance())
        assertNull(transport.flow.orderBalance())
        assertFalse(transport.flow.selection.switchFailed())

        val oldSocketAck = transport.receive("""{"name":"profile","msg":{"balance_id":202}}""", sourceGeneration = 40L)
        assertNull(oldSocketAck)
        assertNull(transport.flow.activeBalance())
        assertNull(transport.flow.orderBalance())

        val resyncProfile = transport.receive(initialProfile(balanceId = 101L), sourceGeneration = 41L)
        assertTrue(resyncProfile!!.accepted)
        assertTrue(transport.connectionWaiter!!.deferred.isCompleted)
        assertEquals(101L, transport.flow.activeBalance()?.id)
        assertEquals(101L, transport.flow.orderBalance()?.id)
    }

    @Test fun `timeout de troca sem resposta nunca retorna silenciosamente a carteira anterior`() {
        val transport = FakeTransport()
        transport.connect(50L)
        transport.receive(initialProfile())
        val waiter = transport.requestSwitch(202L)

        // Sem profile/balance-changed com o ID 202, positions/instruments e request IDs não são ACK da conta.
        transport.receive("""{"name":"positions","request_id":"cue-2","msg":{"positions":[]}}""")
        transport.receive("""{"name":"instruments","request_id":"cue-4","msg":{"instruments":[]}}""")
        assertFalse(waiter.deferred.isCompleted)
        assertNull(transport.flow.activeBalance())
        assertNull(transport.flow.orderBalance())

        transport.failSwitch(202L, waiter)
        assertTrue(transport.flow.selection.switchFailed())
        assertNull(transport.flow.activeBalance())
        assertNull(transport.flow.orderBalance())
    }
}
