package dev.cueopen.app.assistant

import android.os.Bundle
import android.service.voice.VoiceInteractionService
import dev.cueopen.app.diag.ErrorLog

/** Serviço leve, ativado somente após seleção explícita como assistente padrão nas Configurações Android. */
class FluwyVoiceInteractionService : VoiceInteractionService() {
    override fun onShowSessionFailed(args: Bundle) {
        super.onShowSessionFailed(args)
        ErrorLog.log("assistant", "O Android não conseguiu vincular ou exibir a sessão do assistente.")
    }
}
