package dev.cueopen.app.assistant

import android.service.voice.VoiceInteractionService

/** Serviço leve, ativado somente após seleção explícita como assistente padrão nas Configurações Android. */
class FluwyVoiceInteractionService : VoiceInteractionService()
