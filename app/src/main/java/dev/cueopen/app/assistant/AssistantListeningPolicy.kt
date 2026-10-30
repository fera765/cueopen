package dev.cueopen.app.assistant

internal object AssistantListeningPolicy {
    const val TURN_END_SILENCE_MS = 2_000L
    const val INACTIVITY_CLOSE_TIMEOUT_MS = 60_000L
    const val MIN_RESTART_DELAY_MS = 250L
    const val MAX_RESTART_DELAY_MS = 8_000L

    fun shouldCloseForInactivity(
        idleForMs: Long,
        hasUnfinalizedSpeech: Boolean,
        assistantIsProcessing: Boolean,
    ): Boolean = idleForMs >= INACTIVITY_CLOSE_TIMEOUT_MS &&
        !hasUnfinalizedSpeech &&
        !assistantIsProcessing

    fun restartDelayMs(consecutiveFailures: Int): Long {
        val exponent = (consecutiveFailures - 1).coerceIn(0, 20)
        val multiplier = 1L shl exponent
        return (MIN_RESTART_DELAY_MS * multiplier).coerceAtMost(MAX_RESTART_DELAY_MS)
    }

    fun shouldDispatchFinal(transcript: String): Boolean = transcript.isNotBlank()
}
