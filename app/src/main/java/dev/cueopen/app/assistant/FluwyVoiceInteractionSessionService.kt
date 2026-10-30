package dev.cueopen.app.assistant

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/** O Android cria a sessão ao invocar o assistente padrão escolhido pela pessoa. */
class FluwyVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = FluwyAssistantSession(this)
}
