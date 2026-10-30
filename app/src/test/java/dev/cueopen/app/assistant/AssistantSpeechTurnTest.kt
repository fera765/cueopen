package dev.cueopen.app.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantSpeechTurnTest {
    @Test
    fun `nao finaliza antes de dois segundos e envia no limite`() {
        val turn = AssistantSpeechTurn()
        turn.noteSpeechActivity(1_000L)
        turn.onProviderEndOfSpeech()
        turn.onFinalResult("Olá", 1_000L)

        assertNull(turn.finishIfSilent(2_999L))
        assertEquals("Olá", turn.finishIfSilent(3_000L))
        assertFalse(turn.hasPendingSpeech)
    }

    @Test
    fun `pausa menor que dois segundos une o proximo trecho na mesma mensagem`() {
        val turn = AssistantSpeechTurn()
        turn.noteSpeechActivity(1_000L)
        turn.onFinalResult("Bom dia", 1_100L)
        assertNull(turn.finishIfSilent(2_500L))

        turn.noteSpeechActivity(2_500L)
        turn.onFinalResult("como vai?", 2_600L)
        assertNull(turn.finishIfSilent(4_499L))
        assertEquals("Bom dia como vai?", turn.finishIfSilent(4_500L))
    }

    @Test
    fun `se resultado final chega depois do prazo envia assim que chega`() {
        val turn = AssistantSpeechTurn()
        turn.noteSpeechActivity(5_000L)
        turn.onProviderEndOfSpeech()
        turn.onFinalResult("Pedido final", 7_300L)

        assertEquals("Pedido final", turn.finishIfSilent(7_300L))
    }

    @Test
    fun `stop e solicitado apenas uma vez ao completar dois segundos de silencio`() {
        val turn = AssistantSpeechTurn()
        turn.noteSpeechActivity(10_000L)

        assertFalse(turn.shouldRequestStopListening(11_999L))
        assertTrue(turn.shouldRequestStopListening(12_000L))
        assertFalse(turn.shouldRequestStopListening(12_001L))
    }

    @Test
    fun `resultado anterior aguarda callback final do trecho atual`() {
        val turn = AssistantSpeechTurn()
        turn.noteSpeechActivity(1_000L)
        turn.onFinalResult("Trecho um", 1_100L)
        turn.noteSpeechActivity(2_000L)
        turn.onProviderEndOfSpeech()

        assertNull(turn.finishIfSilent(4_000L))
        turn.onFinalResult("Trecho dois", 4_500L)
        assertEquals("Trecho um Trecho dois", turn.finishIfSilent(4_500L))
    }

    @Test
    fun `erro nao envia parcial e permite recuperar segmentos finais anteriores`() {
        val turn = AssistantSpeechTurn()
        turn.noteSpeechActivity(0L)
        turn.onFinalResult("Trecho completo", 100L)
        turn.noteSpeechActivity(1_000L)
        turn.onRecognitionError()

        assertNull(turn.finishIfSilent(2_999L))
        assertEquals("Trecho completo", turn.finishIfSilent(3_000L))
    }
}
