package dev.fluwy.app.assistant

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import dev.fluwy.app.diag.ErrorLog

/** O Android cria a sessão ao invocar o assistente padrão escolhido pela pessoa. */
class FluwyVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        android.util.Log.i("FluwyAssistant", "sessão de voz criada")  // informativo: não é erro
        return FluwyAssistantSession(this)
    }
}
