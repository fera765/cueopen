package dev.cueopen.app.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantListeningPolicyTest {
    @Test
    fun `limiar solicitado de fim de fala e exatamente dois segundos`() {
        assertEquals(2_000L, AssistantListeningPolicy.TURN_END_SILENCE_MS)
    }

    @Test
    fun `fecha somente depois de sessenta segundos realmente inativos`() {
        assertFalse(AssistantListeningPolicy.shouldCloseForInactivity(59_999L, false, false))
        assertTrue(AssistantListeningPolicy.shouldCloseForInactivity(60_000L, false, false))
    }

    @Test
    fun `fala ainda nao finalizada ou agente trabalhando impedem fechar`() {
        assertFalse(AssistantListeningPolicy.shouldCloseForInactivity(90_000L, true, false))
        assertFalse(AssistantListeningPolicy.shouldCloseForInactivity(90_000L, false, true))
    }

    @Test
    fun `retry cresce exponencialmente e fica limitado`() {
        assertEquals(250L, AssistantListeningPolicy.restartDelayMs(1))
        assertEquals(500L, AssistantListeningPolicy.restartDelayMs(2))
        assertEquals(1_000L, AssistantListeningPolicy.restartDelayMs(3))
        assertEquals(8_000L, AssistantListeningPolicy.restartDelayMs(12))
        assertEquals(8_000L, AssistantListeningPolicy.restartDelayMs(100))
    }
}
