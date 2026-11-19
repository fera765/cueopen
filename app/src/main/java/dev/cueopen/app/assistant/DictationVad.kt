package dev.cueopen.app.assistant

/**
 * Política de fim de fala do ditado (o reconhecedor do Android para a cada pausa). Uma pausa curta dentro da frase
 * não encerra: a escuta continua por [END_SILENCE_MS]; silêncio além disso encerra a gravação de vez, sem reiniciar.
 */
object DictationVad {
    /** Tolerância de silêncio depois de um trecho reconhecido antes de considerar a fala terminada. */
    const val END_SILENCE_MS = 2_500L

    enum class Action { COMMIT, LISTEN_AGAIN, GIVE_UP }

    /** Reconhecedor terminou sem fala nesta rodada. */
    fun onSilence(hasDictation: Boolean, silentRounds: Int): Action = when {
        hasDictation -> Action.COMMIT
        silentRounds < 2 -> Action.LISTEN_AGAIN   // ainda não começou a falar: uma chance a mais
        else -> Action.GIVE_UP
    }
}
