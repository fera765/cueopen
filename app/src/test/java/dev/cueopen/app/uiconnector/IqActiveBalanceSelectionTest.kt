package dev.cueopen.app.uiconnector

import dev.cueopen.app.uiconnector.iqoption.IqActiveBalanceSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IqActiveBalanceSelectionTest {
    @Test fun `iniciar troca remove imediatamente autorizacao da conta antiga`() {
        val state = IqActiveBalanceSelection()
        assertTrue(state.observeServerConfirmation(10L))
        assertEquals(10L, state.activeId())
        state.beginSwitch(20L)
        assertNull(state.activeId())
        state.finishSwitch(20L)
        assertNull(state.activeId())
    }

    @Test fun `evento de outra carteira nao confirma o alvo pendente`() {
        val state = IqActiveBalanceSelection()
        state.observeServerConfirmation(10L)
        state.beginSwitch(20L)
        assertFalse(state.observeServerConfirmation(10L))
        assertNull(state.activeId())
        state.finishSwitch(20L)
        assertNull(state.activeId())
    }

    @Test fun `somente confirmacao do ID solicitado libera nova conta`() {
        val state = IqActiveBalanceSelection()
        state.beginSwitch(20L)
        assertTrue(state.observeServerConfirmation(20L))
        state.finishSwitch(20L, confirmed = true)
        assertEquals(20L, state.activeId())
    }

    @Test fun `evento nao correlacionado atrasado nao desbloqueia apos troca falha`() {
        val state = IqActiveBalanceSelection()
        state.beginSwitch(20L)
        state.finishSwitch(20L)
        assertNull(state.activeId())
        assertFalse(state.observeServerConfirmation(20L))
        assertNull(state.activeId())
        state.beginSwitch(20L)
        assertTrue(state.observeServerConfirmation(20L))
        state.finishSwitch(20L, confirmed = true)
        assertEquals(20L, state.activeId())
    }

    @Test fun `desconexao invalida carteira confirmada`() {
        val state = IqActiveBalanceSelection()
        state.observeServerConfirmation(10L)
        state.invalidate()
        assertNull(state.activeId())
    }

    @Test fun `mudanca de carteira nao solicitada invalida selecao confirmada`() {
        val state = IqActiveBalanceSelection()
        assertTrue(state.observeServerConfirmation(10L))
        assertFalse(state.observeServerConfirmation(20L))
        assertNull(state.activeId())
        assertFalse(state.observeServerConfirmation(10L))
        assertNull(state.activeId())
    }
}
