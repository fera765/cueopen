package dev.cueopen.app.assistant

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.cueopen.app.CueApp
import dev.cueopen.app.data.ChatItem
import dev.cueopen.app.data.Session
import dev.cueopen.app.data.SessionStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Executa apenas no processo principal, onde Store, AgentRunner e WorkManager já são inicializados. */
class AssistantCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != AssistantRoutingPolicy.ACTION_SUBMIT_COMMAND) return
        val transcript = intent.getStringExtra(AssistantRoutingPolicy.EXTRA_TRANSCRIPT)?.trim()?.take(4_000).orEmpty()
        val requestId = intent.getStringExtra(AssistantRoutingPolicy.EXTRA_REQUEST_ID)?.take(80).orEmpty()
        if (transcript.isBlank() || requestId.isBlank()) return

        val app = context.applicationContext as? CueApp ?: return
        if (!rememberRequest(app, requestId)) return
        val (target, targetLabel) = resolveTarget(app, transcript)
        val startedAt = System.currentTimeMillis()
        AssistantOverlayStateStore.write(app, AssistantOverlayState(
            requestId = requestId,
            sessionId = target.id,
            targetLabel = targetLabel,
            transcript = transcript,
            phase = "queued",
            detail = "Comando encaminhado para o chat. Aguardando a fila do agente…",
            startedAt = startedAt,
        ))

        // Uma única fila serializa tarefas disparadas por voz. O send normal continua responsável
        // por todas as permissões de agente, conectores, conta e limites de risco.
        app.applicationScope.launch {
            runCatching {
                voiceQueue.withLock {
                    while (app.agent.isRunning(target.id)) {
                        AssistantOverlayStateStore.updateIfCurrent(app, requestId) {
                            it.copy(phase = "queued", detail = "Aguardando a tarefa atual neste chat terminar…")
                        }
                        delay(350)
                    }
                    AssistantOverlayStateStore.updateIfCurrent(app, requestId) {
                        it.copy(phase = "working", detail = "O agente está iniciando a tarefa…")
                    }
                    val job = app.agent.send(target.id, transcript)
                    while (job.isActive) {
                        val session = app.store.session(target.id)
                        val activity = app.agent.activity.value[target.id]
                        val step = session?.items?.filterIsInstance<ChatItem.Step>()?.lastOrNull()
                        val media = session?.items?.filterIsInstance<ChatItem.Media>()
                            ?.lastOrNull { it.time >= startedAt && it.caption.contains("captura", ignoreCase = true) }
                        AssistantOverlayStateStore.updateIfCurrent(app, requestId) {
                            it.copy(
                                phase = "working",
                                detail = activity?.detail?.takeIf(String::isNotBlank)
                                    ?: step?.title?.takeIf(String::isNotBlank)
                                    ?: "O agente está trabalhando no chat…",
                                screenshotPath = media?.path,
                            )
                        }
                        delay(450)
                    }
                    job.join()
                    val session = app.store.session(target.id)
                    val finalText = session?.items?.filterIsInstance<ChatItem.Assistant>()
                        ?.lastOrNull { it.final }?.text.orEmpty()
                    val errorText = session?.items?.filterIsInstance<ChatItem.Error>()?.lastOrNull()?.text.orEmpty()
                    // Assistente de voz responde falando (Dii no aparelho, sem esperar arquivo): começa em décimos de segundo.
                    if (finalText.isNotBlank() && dev.cueopen.app.voice.DiiVoice.enabled(app))
                        runCatching { dev.cueopen.app.voice.DiiVoice.speak(app, VoiceReplies.speakable(finalText)) }
                    val media = session?.items?.filterIsInstance<ChatItem.Media>()
                        ?.lastOrNull { it.time >= startedAt && it.caption.contains("captura", ignoreCase = true) }
                    val phase = when {
                        session?.status == SessionStatus.WAITING_USER -> "needs_input"
                        session?.status == SessionStatus.ERROR || errorText.isNotBlank() -> "error"
                        session?.status == SessionStatus.STOPPED -> "stopped"
                        else -> "done"
                    }
                    AssistantOverlayStateStore.updateIfCurrent(app, requestId) {
                        it.copy(
                            phase = phase,
                            detail = when (phase) {
                                "needs_input" -> "O agente precisa de uma resposta. Abra o chat para continuar."
                                "error" -> errorText.ifBlank { "A tarefa terminou com um erro. Consulte o chat para ver os detalhes." }
                                "stopped" -> "A tarefa foi interrompida."
                                else -> "Resposta pronta no chat. Nenhum áudio de resposta foi reproduzido."
                            },
                            result = finalText.ifBlank { errorText },
                            screenshotPath = media?.path,
                        )
                    }
                }
            }.onFailure { error ->
                AssistantOverlayStateStore.updateIfCurrent(app, requestId) {
                    it.copy(phase = "error", detail = error.message ?: "Não foi possível iniciar a tarefa.")
                }
            }
        }
    }

    private fun rememberRequest(app: CueApp, requestId: String): Boolean = synchronized(processedLock) {
        val prefs = app.getSharedPreferences(AssistantRoutingPolicy.PREFERENCES, Context.MODE_PRIVATE)
        val previous = prefs.getStringSet(AssistantRoutingPolicy.KEY_PROCESSED_REQUESTS, emptySet()).orEmpty()
        if (requestId in previous) return@synchronized false
        prefs.edit().putStringSet(AssistantRoutingPolicy.KEY_PROCESSED_REQUESTS, (previous + requestId).toList().takeLast(80).toSet()).apply()
        true
    }

    private fun resolveTarget(app: CueApp, transcript: String): Pair<Session, String> {
        val store = app.store
        val state = store.state.value
        val prefs = app.getSharedPreferences(AssistantRoutingPolicy.PREFERENCES, Context.MODE_PRIVATE)
        val mentionedAgent = AssistantRoutingPolicy.findMentionedAgent(transcript, state.agents)
        if (mentionedAgent != null) {
            val target = mentionedAgent.homeSessionId?.let(store::session)?.takeIf { it.agentId == mentionedAgent.id }
                ?: state.sessions.firstOrNull { it.agentId == mentionedAgent.id }
                ?: Session(agentId = mentionedAgent.id, title = "${mentionedAgent.name} · voz").also(store::upsertSession)
            if (mentionedAgent.homeSessionId != target.id) store.upsertAgent(mentionedAgent.copy(homeSessionId = target.id))
            return target to mentionedAgent.name
        }

        val rememberedId = prefs.getString(AssistantRoutingPolicy.KEY_ACTIVE_SESSION_ID, null)
        val target = rememberedId?.let(store::session)
            ?: state.sessions.firstOrNull()
            ?: Session(title = "Assistente de voz").also(store::upsertSession)
        prefs.edit().putString(AssistantRoutingPolicy.KEY_ACTIVE_SESSION_ID, target.id).apply()
        val targetAgent = target.agentId?.let(store::agent)
        return target to (targetAgent?.name ?: target.title.ifBlank { "Chat ativo" })
    }

    private companion object {
        val voiceQueue = Mutex()
        val processedLock = Any()
    }
}
