package dev.fluwy.app.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantVoiceActivityGateTest {
    @Test fun `ruido baixo nao renova atividade mas resultado final curto continua valido`() {
        val gate = AssistantVoiceActivityGate()
        gate.beginAttempt()

        assertFalse(gate.onAmplitudeChanged(0.10f, 0L))
        assertFalse(gate.onAmplitudeChanged(0.18f, 100L))
        assertFalse(gate.onAmplitudeChanged(0.22f, 300L))
        assertFalse(gate.speechConfirmedInAttempt)
        assertFalse(gate.acceptsPartial())
        assertTrue(gate.acceptsFinal("frase curta reconhecida"))
    }

    @Test fun `impulso alto e curto nao confirma fala`() {
        val gate = AssistantVoiceActivityGate()
        gate.beginAttempt()

        assertFalse(gate.onAmplitudeChanged(0.90f, 1_000L))
        assertFalse(gate.onAmplitudeChanged(0.85f, 1_050L))
        assertFalse(gate.onAmplitudeChanged(0.12f, 1_090L))
        assertFalse(gate.speechConfirmedInAttempt)
    }

    @Test fun `nivel alto sustentado confirma depois de amostras e duracao minima`() {
        val gate = AssistantVoiceActivityGate()
        gate.beginAttempt()

        assertFalse(gate.onAmplitudeChanged(0.55f, 2_000L))
        assertFalse(gate.onAmplitudeChanged(0.58f, 2_080L))
        assertTrue(gate.onAmplitudeChanged(0.52f, 2_180L))
        assertTrue(gate.speechConfirmedInAttempt)
        assertTrue(gate.acceptsPartial())
        assertTrue(gate.acceptsFinal("fala válida"))
    }

    @Test fun `histerese mantem atividade abaixo do limiar inicial e encerra abaixo do limiar de retencao`() {
        val gate = AssistantVoiceActivityGate()
        gate.beginAttempt()
        gate.onAmplitudeChanged(0.5f, 0L)
        gate.onAmplitudeChanged(0.5f, 100L)
        assertTrue(gate.onAmplitudeChanged(0.5f, 180L))

        assertTrue(gate.onAmplitudeChanged(0.32f, 220L))
        assertFalse(gate.onAmplitudeChanged(0.27f, 260L))
        assertTrue(gate.speechConfirmedInAttempt)
    }

    @Test fun `provedor sem callbacks RMS usa transcricao como fallback`() {
        val gate = AssistantVoiceActivityGate()
        gate.beginAttempt()

        assertTrue(gate.acceptsPartial())
        assertTrue(gate.acceptsFinal("resultado do provedor"))
        assertFalse(gate.acceptsFinal("   "))
    }

    @Test fun `gate e reiniciado entre tentativas sem alterar a politica de silencio`() {
        val gate = AssistantVoiceActivityGate()
        gate.beginAttempt()
        gate.onAmplitudeChanged(0.5f, 0L)
        gate.onAmplitudeChanged(0.5f, 100L)
        gate.onAmplitudeChanged(0.5f, 180L)
        assertTrue(gate.speechConfirmedInAttempt)

        gate.beginAttempt()
        assertFalse(gate.hasRmsInAttempt)
        assertFalse(gate.speechConfirmedInAttempt)
        assertFalse(gate.speechActive)
        assertTrue(AssistantListeningPolicy.TURN_END_SILENCE_MS == 2_000L)
        assertTrue(AssistantListeningPolicy.INACTIVITY_CLOSE_TIMEOUT_MS == 60_000L)
    }
}
