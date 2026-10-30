package dev.cueopen.app.assistant

/** Modela uma sessão do SpeechRecognizer: onEndOfSpeech não libera uma nova tentativa. */
internal class AssistantRecognitionLoop {
    private var attemptActive = false

    val isActive: Boolean
        get() = attemptActive

    fun beginAttempt(): Boolean {
        if (attemptActive) return false
        attemptActive = true
        return true
    }

    /** Apenas informativo; o serviço ainda pode estar preparando onResults/onError. */
    fun onEndOfSpeech() = Unit

    /** Chamado exclusivamente depois de onResults ou onError. */
    fun onTerminalCallback(): Boolean {
        if (!attemptActive) return false
        attemptActive = false
        return true
    }

    fun cancel() {
        attemptActive = false
    }
}
