package dev.cueopen.app.assistant

/**
 * Fim de fala do ditado decidido pelo app: o reconhecedor do Android nem sempre encerra sozinho (ou demora),
 * então, depois que a fala começou, [END_SILENCE_MS] sem texto novo na transcrição ao vivo encerra a gravação.
 * Ruído de fundo não conta: só texto novo prova que ainda há fala.
 */
object DictationVad {
    /** Pausa que encerra o ditado. */
    const val END_SILENCE_MS = 1_800L

    /** Sem nenhuma fala desde o início: desiste. */
    const val NO_SPEECH_TIMEOUT_MS = 8_000L

    /** Limite de uma gravação (o provedor não aceita sessões longas). */
    const val MAX_RECORDING_MS = 60_000L

    /** Espera pelo texto final depois de pedir para parar; depois disso usa a transcrição ao vivo. */
    const val FINAL_RESULT_WAIT_MS = 1_500L

    enum class Check { CONTINUE, FINISH, NO_SPEECH }

    /** [sinceStartMs] desde o início; [sinceSpeechMs] desde o último texto novo (null = ainda não falou). */
    fun check(sinceStartMs: Long, sinceSpeechMs: Long?): Check = when {
        sinceSpeechMs == null -> if (sinceStartMs >= NO_SPEECH_TIMEOUT_MS) Check.NO_SPEECH else Check.CONTINUE
        sinceSpeechMs >= END_SILENCE_MS || sinceStartMs >= MAX_RECORDING_MS -> Check.FINISH
        else -> Check.CONTINUE
    }
}
