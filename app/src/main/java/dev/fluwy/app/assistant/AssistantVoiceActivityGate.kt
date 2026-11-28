package dev.fluwy.app.assistant

/**
 * Filtro local adicional sobre onRmsChanged: rejeita níveis baixos e impulsos breves antes de
 * deixar o RMS renovar o silêncio do turno. Não substitui o endpointer do provedor Android.
 */
internal class AssistantVoiceActivityGate(
    private val startThreshold: Float = DEFAULT_START_THRESHOLD,
    private val holdThreshold: Float = DEFAULT_HOLD_THRESHOLD,
    private val minimumOnsetMs: Long = DEFAULT_MINIMUM_ONSET_MS,
    private val minimumOnsetSamples: Int = DEFAULT_MINIMUM_ONSET_SAMPLES,
) {
    private var candidateStartedAt: Long? = null
    private var candidateSamples = 0

    var hasRmsInAttempt: Boolean = false
        private set
    var speechConfirmedInAttempt: Boolean = false
        private set
    var speechActive: Boolean = false
        private set

    fun beginAttempt() {
        candidateStartedAt = null
        candidateSamples = 0
        hasRmsInAttempt = false
        speechConfirmedInAttempt = false
        speechActive = false
    }

    /** Returns true only while a sustained, gated sound is active. */
    fun onRmsChanged(rmsDb: Float, nowMs: Long): Boolean {
        if (!rmsDb.isFinite()) return false
        hasRmsInAttempt = true
        return onAmplitudeChanged(normalizedAmplitude(rmsDb), nowMs)
    }

    /** Pure hook used by tests so threshold and timing behavior do not need a microphone. */
    fun onAmplitudeChanged(amplitude: Float, nowMs: Long): Boolean {
        if (!amplitude.isFinite()) return speechActive
        hasRmsInAttempt = true
        val level = amplitude.coerceIn(0f, 1f)

        if (speechActive) {
            if (level >= holdThreshold) return true
            speechActive = false
            resetCandidate()
            return false
        }

        if (level < startThreshold) {
            resetCandidate()
            return false
        }

        val startedAt = candidateStartedAt ?: nowMs.also { candidateStartedAt = it }
        candidateSamples++
        if (candidateSamples >= minimumOnsetSamples && nowMs - startedAt >= minimumOnsetMs) {
            speechActive = true
            speechConfirmedInAttempt = true
        }
        return speechActive
    }

    /** Providers' non-empty final transcription is semantic evidence, even when RMS onset was short. */
    fun acceptsPartial(): Boolean = !hasRmsInAttempt || speechActive

    fun acceptsFinal(transcript: String): Boolean = transcript.isNotBlank()

    private fun resetCandidate() {
        candidateStartedAt = null
        candidateSamples = 0
    }

    companion object {
        const val DEFAULT_START_THRESHOLD = 0.40f
        const val DEFAULT_HOLD_THRESHOLD = 0.28f
        const val DEFAULT_MINIMUM_ONSET_MS = 180L
        const val DEFAULT_MINIMUM_ONSET_SAMPLES = 3

        fun normalizedAmplitude(rmsDb: Float): Float = ((rmsDb + 2.5f) / 13.5f).coerceIn(0f, 1f)
    }
}
