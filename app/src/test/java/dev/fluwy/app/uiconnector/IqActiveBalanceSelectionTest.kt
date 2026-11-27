package dev.fluwy.app.uiconnector

import dev.fluwy.app.uiconnector.iqoption.IqActiveBalanceSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fake broker exercises the reducer only; no credentials, network or orders are used. */
class IqActiveBalanceSelectionTest {
    private class FakeBalanceBroker(private val selection: IqActiveBalanceSelection) {
        var generation = 0L
        fun connect(newGeneration: Long) {
            generation = newGeneration
            selection.beginConnection(generation)
        }
        fun requestSwitch(balanceId: Long) = selection.beginSwitch(balanceId, generation)
        fun profile(balanceId: Long, sourceGeneration: Long = generation): Boolean =
            selection.observeServerConfirmation(balanceId, sourceGeneration)
        fun timeout(balanceId: Long) = selection.finishSwitch(balanceId, confirmed = false, reason = "timeout fake")
    }

    @Test fun `demo inicial, troca real, ACK exato e evento antigo posterior nao revertem`() {
        val state = IqActiveBalanceSelection()
        val broker = FakeBalanceBroker(state)
        broker.connect(10)
        assertTrue(broker.profile(101))
        assertEquals(101L, state.activeId())

        broker.requestSwitch(202)
        assertNull(state.activeId())
        assertFalse(broker.profile(101)) // profile atrasado do estado anterior
        assertNull(state.activeId())
        assertTrue(broker.profile(202)) // confirmação explícita do alvo
        assertEquals(202L, state.activeId())

        assertFalse(broker.profile(101)) // balance-changed antigo após confirmação REAL
        assertEquals(202L, state.activeId())
        assertFalse(state.switchFailed())
    }

    @Test fun `timeout mantém conta desconhecida e bloqueia eventos não relacionados`() {
        val state = IqActiveBalanceSelection()
        val broker = FakeBalanceBroker(state)
        broker.connect(20)
        assertTrue(broker.profile(101))
        broker.requestSwitch(202)
        broker.timeout(202)

        assertNull(state.activeId())
        assertTrue(state.switchFailed())
        assertEquals("timeout fake", state.failureMessage())
        assertFalse(broker.profile(101))
        assertNull(state.activeId())
        assertTrue(state.switchFailed())
    }

    @Test fun `ACK tardio exato recupera timeout mas outro balance id nao`() {
        val state = IqActiveBalanceSelection()
        val broker = FakeBalanceBroker(state)
        broker.connect(30)
        assertTrue(broker.profile(101))
        broker.requestSwitch(202)
        broker.timeout(202)

        assertFalse(broker.profile(101))
        assertTrue(state.switchFailed())
        assertTrue(broker.profile(202)) // o servidor confirmou exatamente o ID solicitado
        assertEquals(202L, state.activeId())
        assertFalse(state.switchFailed())
    }

    @Test fun `reconexao invalida callback antigo e confirma estado apenas na nova geração`() {
        val state = IqActiveBalanceSelection()
        val broker = FakeBalanceBroker(state)
        broker.connect(40)
        assertTrue(broker.profile(101))
        broker.requestSwitch(202)
        broker.timeout(202)
        assertNull(state.activeId())

        // A sincronização explícita cria geração nova; callbacks do socket antigo não valem.
        broker.connect(41)
        assertFalse(broker.profile(202, sourceGeneration = 40))
        assertNull(state.activeId())
        assertTrue(broker.profile(101, sourceGeneration = 41))
        assertEquals(101L, state.activeId()) // o estado real do servidor fica explícito na UI
        assertFalse(state.switchFailed())
    }

    @Test fun `wallet ativa nao muda para qualquer ID quando nao ha troca pendente`() {
        val state = IqActiveBalanceSelection()
        val broker = FakeBalanceBroker(state)
        broker.connect(50)
        assertTrue(broker.profile(101))
        assertFalse(broker.profile(303))
        assertEquals(101L, state.activeId())
    }
}
