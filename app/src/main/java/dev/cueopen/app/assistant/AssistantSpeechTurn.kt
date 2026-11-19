package dev.cueopen.app.assistant

/** Agrupa resultados finais de sessões SpeechRecognizer separadas enquanto a pausa não chega a 2 s. */
internal class AssistantSpeechTurn(
    private val silenceWindowMs: Long = AssistantListeningPolicy.TURN_END_SILENCE_MS,
) {
    private val finalSegments = mutableListOf<String>()
    private var lastSpeechActivityAt: Long? = null
    private var speechActive = false
    private var stopRequested = false
    private var awaitingTerminalResult = false

    fun noteSpeechActivity(atElapsedRealtime: Long) {
        lastSpeechActivityAt = atElapsedRealtime
        speechActive = true
        stopRequested = false
        awaitingTerminalResult = false
    }

    fun onProviderEndOfSpeech() {
        speechActive = false
        awaitingTerminalResult = true
    }

    fun onFinalResult(text: String, atElapsedRealtime: Long) {
        text.trim().takeIf(String::isNotEmpty)?.let(finalSegments::add)
        if (lastSpeechActivityAt == null && text.isNotBlank()) {
            // Alguns provedores omitem RMS/partials; o resultado final confirma que houve fala.
            lastSpeechActivityAt = atElapsedRealtime
        }
        speechActive = false
        stopRequested = false
        awaitingTerminalResult = false
    }

    /** Um erro encerra apenas a tentativa atual; segmentos finais anteriores continuam válidos. */
    fun onRecognitionError() {
        speechActive = false
        stopRequested = false
        awaitingTerminalResult = false
    }

    /** Retorna true uma única vez quando a tentativa ainda está capturando após 2 s sem voz. */
    fun shouldRequestStopListening(nowElapsedRealtime: Long): Boolean {
        val lastSpeech = lastSpeechActivityAt ?: return false
        if (!speechActive || stopRequested || nowElapsedRealtime - lastSpeech < silenceWindowMs) return false
        stopRequested = true
        speechActive = false
        awaitingTerminalResult = true
        return true
    }

    /** Tempo restante até a janela de silêncio, ou null se não há um turno aguardando. */
    fun nextSilenceCheckDelayMs(nowElapsedRealtime: Long): Long? {
        val lastSpeech = lastSpeechActivityAt ?: return null
        if (awaitingTerminalResult || (!speechActive && finalSegments.isEmpty())) return null
        return (lastSpeech + silenceWindowMs - nowElapsedRealtime).coerceAtLeast(0L)
    }

    /** Entrega uma única mensagem combinada apenas após a janela completa de silêncio. */
    fun finishIfSilent(nowElapsedRealtime: Long): String? {
        if (speechActive || awaitingTerminalResult || finalSegments.isEmpty()) return null
        val lastSpeech = lastSpeechActivityAt ?: return null
        if (nowElapsedRealtime - lastSpeech < silenceWindowMs) return null

        val transcript = finalSegments.joinToString(" ").trim()
        reset()
        return transcript.takeIf(String::isNotBlank)
    }

    /** Entrega já o que foi transcrito (o turno acabou): sem esperar nova janela nem reabrir o microfone. */
    fun flush(): String? {
        val transcript = finalSegments.joinToString(" ").trim()
        reset()
        return transcript.takeIf(String::isNotBlank)
    }

    val hasPendingSpeech: Boolean
        get() = speechActive || awaitingTerminalResult || finalSegments.isNotEmpty()

    fun reset() {
        finalSegments.clear()
        lastSpeechActivityAt = null
        speechActive = false
        stopRequested = false
        awaitingTerminalResult = false
    }
}
