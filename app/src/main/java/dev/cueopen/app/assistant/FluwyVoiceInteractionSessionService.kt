package dev.cueopen.app.assistant

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import dev.cueopen.app.diag.ErrorLog

/** O Android cria a sessão ao invocar o assistente padrão escolhido pela pessoa. */
class FluwyVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        ErrorLog.log("assistant", "O Android vinculou o serviço e criou uma sessão de voz.")
        return FluwyAssistantSession(this)
    }
}
