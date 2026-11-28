package dev.fluwy.app.assistant

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.service.voice.VoiceInteractionSession
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.content.ContextCompat
import dev.fluwy.app.MainActivity
import java.io.File
import java.util.UUID

/** Overlay enxuto do assistente Android, executado no processo isolado :assistant. */
/** Comando falado com o celular bloqueado: o app só o executa depois do desbloqueio. */
const val ACTION_RUN_AFTER_UNLOCK = "dev.fluwy.app.assistant.RUN_AFTER_UNLOCK"

class FluwyAssistantSession(baseContext: Context) : VoiceInteractionSession(baseContext) {
    private val appContext = baseContext.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val speechTurn = AssistantSpeechTurn()
    private val recognitionLoop = AssistantRecognitionLoop()
    private val voiceActivityGate = AssistantVoiceActivityGate()
    private var orb: AssistantOrbView? = null
    private var screenshotPreview: ImageView? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var activeRecognitionGate: AssistantRecognitionGate? = null
    private var listening = false
    private var providerEndedSpeech = false
    private var sessionVisible = false
    private var restartRunnable: Runnable? = null
    private var terminalCallbackWatchdog: Runnable? = null
    private var consecutiveRecognitionErrors = 0
    /** Um turno por ativação: depois de enviar (ou de não ouvir nada) o microfone não reabre sozinho. */
    private var turnDone = false
    private var liveTranscript = ""
    private var lastVoiceActivityAt = 0L
    private var sessionShownAtWallClock = 0L
    private var lastRenderedStateAt = 0L

    private var autoListenedRequest = ""
    private val refreshState = object : Runnable {
        override fun run() {
            val st = AssistantOverlayStateStore.read(appContext)
            renderState(st)
            // Conversa contínua por voz: terminou de responder (e de falar) → volta a ouvir sozinho.
            if (st.phase == "done" && st.requestId.isNotBlank() && st.requestId != autoListenedRequest && st.startedAt >= sessionShownAtWallClock &&
                turnDone && sessionVisible && speechRecognizer == null && restartRunnable == null && !dev.fluwy.app.voice.AppVoice.speaking.value) {
                autoListenedRequest = st.requestId
                lastVoiceActivityAt = SystemClock.elapsedRealtime()
                beginTurn()
            }
            mainHandler.postDelayed(this, STATE_REFRESH_INTERVAL_MS)
        }
    }

    private val turnSilenceCheck = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            if (speechTurn.shouldRequestStopListening(now) && !providerEndedSpeech) {
                val recognizer = speechRecognizer
                val gate = activeRecognitionGate
                if (recognizer != null && gate != null) {
                    listening = false
                    providerEndedSpeech = true
                    orb?.setListening(false)
                    orb?.contentDescription = "Pausa de 2 segundos detectada; aguardando a transcrição final para enviar."
                    runCatching { recognizer.stopListening() }
                        .onSuccess {
                            if (isCurrentAttempt(recognizer, gate)) scheduleTerminalCallbackWatchdog(recognizer, gate)
                        }
                        .onFailure { handleRecognitionError(recognizer, gate, SpeechRecognizer.ERROR_CLIENT) }
                }
            }

            val completedTurn = speechTurn.finishIfSilent(now)
            if (completedTurn != null) {
                endTurn(completedTurn)
                return
            }
            scheduleTurnSilenceCheck()
        }
    }

    /** Um comando já despachado continua na fila do app mesmo após o overlay ser fechado. */
    private val closeAfterInactivity = object : Runnable {
        override fun run() {
            val idleFor = SystemClock.elapsedRealtime() - lastVoiceActivityAt
            val state = AssistantOverlayStateStore.read(appContext)
            val assistantIsProcessing = state.startedAt >= sessionShownAtWallClock &&
                state.phase in setOf("queued", "working", "speaking")
            val hasUnfinalizedSpeech = speechTurn.hasPendingSpeech || liveTranscript.isNotBlank()
            if (AssistantListeningPolicy.shouldCloseForInactivity(idleFor, hasUnfinalizedSpeech, assistantIsProcessing)) {
                finish()
            } else {
                mainHandler.postDelayed(this, INACTIVITY_CHECK_INTERVAL_MS)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // AssistStructure e screenshots automáticos nunca são lidos; capturas explícitas continuam
        // usando o fluxo de MediaProjection e o consentimento Android já existente.
        setDisabledShowContext(SHOW_WITH_ASSIST or SHOW_WITH_SCREENSHOT)
        getWindow()?.window?.let { window ->
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.attributes = window.attributes.apply {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = ViewGroup.LayoutParams.MATCH_PARENT
                dimAmount = BACKGROUND_DIM_AMOUNT
            }
        }
    }

    override fun onCreateContentView(): View {
        val density = appContext.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val root = FrameLayout(appContext).apply {
            setBackgroundColor(Color.TRANSPARENT)
            clipChildren = false
            clipToPadding = false
        }
        orb = AssistantOrbView(appContext).apply {
            layoutParams = FrameLayout.LayoutParams(dp(310), dp(310), Gravity.CENTER)
            contentDescription = "Assistente de voz. Fale; uma pausa de 2 segundos envia a mensagem e o microfone desliga. Toque para falar de novo."
            setOnClickListener {
                if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    requestMicrophonePermission()
                } else if (dev.fluwy.app.voice.AppVoice.speaking.value) {
                    dev.fluwy.app.voice.AppVoice.stop()  // toque interrompe a fala e já ouve
                    lastVoiceActivityAt = SystemClock.elapsedRealtime()
                    beginTurn()
                } else if (speechRecognizer == null && restartRunnable == null) {
                    beginTurn()
                }
            }
        }
        root.addView(orb)

        // A prévia aparece somente após uma captura explicitamente solicitada; não cobre o orb.
        screenshotPreview = ImageView(appContext).apply {
            visibility = View.GONE
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = "Captura solicitada pelo usuário"
            background = ColorDrawable(0xCC17110D.toInt())
            clipToOutline = true
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(118), Gravity.BOTTOM).apply {
                leftMargin = dp(18)
                rightMargin = dp(18)
                bottomMargin = dp(18)
            }
        }
        root.addView(screenshotPreview)
        renderState(AssistantOverlayStateStore.read(appContext))
        return root
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        sessionVisible = true
        sessionShownAtWallClock = System.currentTimeMillis()
        mainHandler.removeCallbacks(refreshState)
        mainHandler.post(refreshState)
        lastVoiceActivityAt = SystemClock.elapsedRealtime()
        mainHandler.removeCallbacks(closeAfterInactivity)
        mainHandler.postDelayed(closeAfterInactivity, INACTIVITY_CHECK_INTERVAL_MS)
        if (speechRecognizer == null && restartRunnable == null) beginTurn()
    }

    private fun beginTurn() {
        turnDone = false
        consecutiveRecognitionErrors = 0
        speechTurn.reset()
        startListening()
    }

    /** Encerra o turno: envia o que foi dito (se houver) e desliga o microfone sem reinício automático. */
    private fun endTurn(transcript: String?) {
        turnDone = true
        stopListening()
        if (transcript != null && AssistantListeningPolicy.shouldDispatchFinal(transcript)) {
            dispatchUtterance(transcript)
            orb?.contentDescription = "Mensagem enviada. Toque no círculo para falar de novo."
        } else {
            orb?.contentDescription = "Não ouvi nada. Toque no círculo para falar."
        }
    }

    override fun onHandleScreenshot(screenshot: android.graphics.Bitmap?) {
        // Deliberadamente não lê, mostra, salva ou encaminha capturas automáticas da Assist API.
    }

    override fun onHide() {
        sessionVisible = false
        mainHandler.removeCallbacks(refreshState)
        mainHandler.removeCallbacks(closeAfterInactivity)
        mainHandler.removeCallbacks(turnSilenceCheck)
        cancelScheduledRestart()
        stopListening()
        super.onHide()
    }

    override fun onDestroy() {
        sessionVisible = false
        mainHandler.removeCallbacks(refreshState)
        mainHandler.removeCallbacks(closeAfterInactivity)
        mainHandler.removeCallbacks(turnSilenceCheck)
        cancelScheduledRestart()
        stopListening()
        super.onDestroy()
    }

    private fun startListening() {
        if (turnDone || !sessionVisible || speechRecognizer != null || restartRunnable != null || recognitionLoop.isActive) return
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            orb?.setListening(false)
            orb?.contentDescription = "Permissão do microfone necessária. Toque no círculo para abrir o app e autorizá-la."
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            orb?.setListening(false)
            orb?.contentDescription = "Reconhecimento de fala indisponível nas configurações do Android."
            return
        }
        val recognizer = runCatching { SpeechRecognizers.create(appContext) }.getOrElse {
            if (consecutiveRecognitionErrors >= AssistantListeningPolicy.MAX_TRANSIENT_RETRIES) {
                endTurn(null)
                orb?.contentDescription = "Não foi possível iniciar o microfone. Toque no círculo para tentar de novo."
                return
            }
            orb?.contentDescription = "Não foi possível iniciar o microfone. Tentando de novo."
            consecutiveRecognitionErrors += 1
            scheduleRecognitionRestart(nextRecognitionRetryDelay())
            return
        }
        val finalResultGate = AssistantRecognitionGate()
        if (!recognitionLoop.beginAttempt()) {
            runCatching { recognizer.destroy() }
            return
        }
        voiceActivityGate.beginAttempt()
        speechRecognizer = recognizer
        activeRecognitionGate = finalResultGate
        liveTranscript = ""
        providerEndedSpeech = false
        orb?.contentDescription = "Conectando ao serviço de reconhecimento de fala do Android."

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (!isCurrentAttempt(recognizer, finalResultGate)) return
                listening = true
                orb?.setListening(true)
                orb?.contentDescription = "Estou ouvindo. Fale; após 2 segundos sem fala, envio a mensagem."
            }

            override fun onBeginningOfSpeech() {
                if (!isCurrentAttempt(recognizer, finalResultGate)) return
                listening = true
                providerEndedSpeech = false
                orb?.setListening(true)
                orb?.contentDescription = "Ouvindo e transcrevendo sua fala ao vivo."
            }

            override fun onRmsChanged(rmsdB: Float) {
                if (!isCurrentAttempt(recognizer, finalResultGate)) return
                val amplitude = AssistantVoiceActivityGate.normalizedAmplitude(rmsdB)
                val gatedSpeech = voiceActivityGate.onRmsChanged(rmsdB, SystemClock.elapsedRealtime())
                // RMS só anima o orbe: ruído ambiente (TV, ventilador) não pode segurar o turno aberto.
                // Atividade de fala = texto novo da transcrição ao vivo (onPartialResults).
                orb?.setRms(if (gatedSpeech) amplitude else 0f)
            }

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                if (!isCurrentAttempt(recognizer, finalResultGate)) return
                // Este callback indica apenas o fim de áudio do provedor; o texto final vem depois.
                listening = false
                providerEndedSpeech = true
                recognitionLoop.onEndOfSpeech()
                speechTurn.onProviderEndOfSpeech()
                orb?.setRms(0f)
                orb?.setListening(false)
                orb?.contentDescription = "Pausa detectada. Aguardando onResults para concluir e enviar a mensagem."
                scheduleTerminalCallbackWatchdog(recognizer, finalResultGate)
            }

            override fun onError(error: Int) {
                handleRecognitionError(recognizer, finalResultGate, error)
            }

            override fun onResults(results: Bundle?) {
                val candidate = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim()?.take(MAX_TRANSCRIPT_LENGTH).orEmpty()
                val text = candidate.takeIf(voiceActivityGate::acceptsFinal).orEmpty()
                handleRecognitionResults(recognizer, finalResultGate, text)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (!isCurrentAttempt(recognizer, finalResultGate) || providerEndedSpeech) return
                val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim()?.take(MAX_TRANSCRIPT_LENGTH).orEmpty()
                if (partial.isNotBlank() && partial != liveTranscript) {
                    noteSpeechActivity()
                    liveTranscript = partial
                    orb?.contentDescription = "Transcrevendo ao vivo: ${partial.take(120)}. A pausa de 2 segundos envia a mensagem."
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val request = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
            // O buffer do app aplica o mesmo limiar; estes extras orientam o provedor a encerrar após 2 s.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, AssistantListeningPolicy.TURN_END_SILENCE_MS)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, AssistantListeningPolicy.TURN_END_SILENCE_MS)
        }
        runCatching { recognizer.startListening(request) }
            .onFailure { handleRecognitionError(recognizer, finalResultGate, SpeechRecognizer.ERROR_CLIENT) }
    }

    private fun handleRecognitionResults(recognizer: SpeechRecognizer, gate: AssistantRecognitionGate, text: String) {
        if (!gate.resolve()) return
        if (speechRecognizer !== recognizer || activeRecognitionGate !== gate) {
            runCatching { recognizer.destroy() }
            return
        }
        if (!recognitionLoop.onTerminalCallback()) return
        val live = liveTranscript
        releaseCurrentRecognizer(recognizer, gate, cancel = false)
        // O provedor já encerrou a fala: o turno termina aqui. Reabrir o microfone era o que fazia
        // o assistente "se reativar" sozinho com ruído ou com o próprio bipe.
        speechTurn.onFinalResult(text.ifBlank { live }, SystemClock.elapsedRealtime())
        endTurn(speechTurn.flush())
    }

    private fun handleRecognitionError(recognizer: SpeechRecognizer, gate: AssistantRecognitionGate, error: Int) {
        if (!gate.resolve()) return
        if (speechRecognizer !== recognizer || activeRecognitionGate !== gate) {
            runCatching { recognizer.destroy() }
            return
        }
        if (!recognitionLoop.onTerminalCallback()) return
        val live = liveTranscript
        releaseCurrentRecognizer(recognizer, gate, cancel = false)
        speechTurn.onRecognitionError()
        if (live.isNotBlank()) speechTurn.onFinalResult(live, SystemClock.elapsedRealtime())
        liveTranscript = ""
        providerEndedSpeech = false
        orb?.setRms(0f)
        orb?.setListening(false)

        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
            turnDone = true
            orb?.contentDescription = "O Android negou o acesso ao microfone. Toque no círculo para autorizá-lo."
            return
        }
        val pending = speechTurn.flush()
        // Só falhas técnicas momentâneas, sem nada dito, ganham nova tentativa (no máximo 2).
        if (pending == null && AssistantListeningPolicy.isTransientError(error) &&
            consecutiveRecognitionErrors < AssistantListeningPolicy.MAX_TRANSIENT_RETRIES) {
            consecutiveRecognitionErrors += 1
            orb?.contentDescription = "${speechError(error)} Tentando de novo."
            scheduleRecognitionRestart(nextRecognitionRetryDelay())
            return
        }
        endTurn(pending)
    }

    private fun dispatchUtterance(text: String) {
        if (!AssistantListeningPolicy.shouldDispatchFinal(text)) return
        val intent = Intent(appContext, AssistantCommandReceiver::class.java).apply {
            action = AssistantRoutingPolicy.ACTION_SUBMIT_COMMAND
            putExtra(AssistantRoutingPolicy.EXTRA_REQUEST_ID, UUID.randomUUID().toString())
            putExtra(AssistantRoutingPolicy.EXTRA_TRANSCRIPT, text.take(MAX_TRANSCRIPT_LENGTH))
        }

        // Celular bloqueado: como o Google Assistente, nada é executado sem desbloquear. O comando abre o app,
        // o Android pede o desbloqueio e só então ele segue para o agente.
        val keyguard = appContext.getSystemService(android.app.KeyguardManager::class.java)
        if (keyguard?.isKeyguardLocked == true) {
            val unlock = Intent(appContext, dev.fluwy.app.MainActivity::class.java).apply {
                action = ACTION_RUN_AFTER_UNLOCK
                putExtra(AssistantRoutingPolicy.EXTRA_TRANSCRIPT, text.take(MAX_TRANSCRIPT_LENGTH))
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            runCatching { startAssistantActivity(unlock) }.onFailure { runCatching { appContext.startActivity(unlock) } }
            finish()
            return
        }
        if (AssistantRoutingPolicy.explicitlyRequestsScreenshot(text)) {
            // A captura explícita segue pelo fluxo de consentimento MediaProjection já existente.
            finish()
            mainHandler.postDelayed({ appContext.sendBroadcast(intent) }, SCREENSHOT_DISPATCH_DELAY_MS)
        } else {
            appContext.sendBroadcast(intent)
        }
    }

    private fun noteSpeechActivity() {
        val now = SystemClock.elapsedRealtime()
        lastVoiceActivityAt = now
        speechTurn.noteSpeechActivity(now)
        scheduleTurnSilenceCheck()
    }

    private fun scheduleTurnSilenceCheck() {
        mainHandler.removeCallbacks(turnSilenceCheck)
        val delay = speechTurn.nextSilenceCheckDelayMs(SystemClock.elapsedRealtime()) ?: return
        mainHandler.postDelayed(turnSilenceCheck, delay)
    }

    private fun scheduleRecognitionRestart(delayMs: Long) {
        if (!sessionVisible || speechRecognizer != null || restartRunnable != null) return
        val restart = Runnable {
            restartRunnable = null
            if (sessionVisible && speechRecognizer == null) startListening()
        }
        restartRunnable = restart
        mainHandler.postDelayed(restart, delayMs.coerceAtLeast(0L))
    }

    private fun scheduleTerminalCallbackWatchdog(recognizer: SpeechRecognizer, gate: AssistantRecognitionGate) {
        cancelTerminalCallbackWatchdog()
        val watchdog = Runnable {
            terminalCallbackWatchdog = null
            if (isCurrentAttempt(recognizer, gate)) {
                // O provedor não mandou o texto final: usa a transcrição ao vivo em vez de descartar a fala.
                val live = liveTranscript
                if (live.isNotBlank()) handleRecognitionResults(recognizer, gate, live)
                else handleRecognitionError(recognizer, gate, SpeechRecognizer.ERROR_CLIENT)
            }
        }
        terminalCallbackWatchdog = watchdog
        mainHandler.postDelayed(watchdog, TERMINAL_CALLBACK_TIMEOUT_MS)
    }

    private fun cancelTerminalCallbackWatchdog() {
        terminalCallbackWatchdog?.let(mainHandler::removeCallbacks)
        terminalCallbackWatchdog = null
    }

    private fun cancelScheduledRestart() {
        restartRunnable?.let(mainHandler::removeCallbacks)
        restartRunnable = null
    }

    private fun nextRecognitionRetryDelay(): Long =
        AssistantListeningPolicy.restartDelayMs(consecutiveRecognitionErrors.coerceAtLeast(1))

    private fun isCurrentAttempt(recognizer: SpeechRecognizer, gate: AssistantRecognitionGate): Boolean =
        speechRecognizer === recognizer && activeRecognitionGate === gate && !gate.isResolved

    private fun releaseCurrentRecognizer(recognizer: SpeechRecognizer, gate: AssistantRecognitionGate, cancel: Boolean) {
        cancelTerminalCallbackWatchdog()
        if (speechRecognizer === recognizer) speechRecognizer = null
        if (activeRecognitionGate === gate) activeRecognitionGate = null
        listening = false
        runCatching { if (cancel) recognizer.cancel() }
        runCatching { recognizer.destroy() }
        orb?.setRms(0f)
        orb?.setListening(false)
    }

    private fun stopListening() {
        cancelScheduledRestart()
        cancelTerminalCallbackWatchdog()
        mainHandler.removeCallbacks(turnSilenceCheck)
        activeRecognitionGate?.resolve()
        recognitionLoop.cancel()
        val recognizer = speechRecognizer
        speechRecognizer = null
        activeRecognitionGate = null
        listening = false
        providerEndedSpeech = false
        liveTranscript = ""
        speechTurn.reset()
        if (recognizer != null) {
            runCatching { recognizer.cancel() }
            runCatching { recognizer.destroy() }
        }
        orb?.setRms(0f)
        orb?.setListening(false)
    }

    private fun requestMicrophonePermission() {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            action = AssistantRoutingPolicy.ACTION_REQUEST_MICROPHONE
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startAssistantActivity(intent)
    }

    private fun renderState(state: AssistantOverlayState) {
        if (state.updatedAt < lastRenderedStateAt) return
        lastRenderedStateAt = state.updatedAt
        val imageFile = state.screenshotPath?.let(::File)
            ?.takeIf { it.isFile && it.lastModified() >= state.startedAt - 3_000L }
        val preview = screenshotPreview ?: return
        if (imageFile == null) {
            preview.setImageDrawable(null)
            preview.visibility = View.GONE
            return
        }
        val options = BitmapFactory.Options().apply { inSampleSize = 4 }
        val bitmap = runCatching { BitmapFactory.decodeFile(imageFile.absolutePath, options) }.getOrNull()
        if (bitmap == null) {
            preview.visibility = View.GONE
        } else {
            preview.setImageBitmap(bitmap)
            preview.visibility = View.VISIBLE
        }
    }

    private fun speechError(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "O microfone do Android ficou indisponível."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "O Android negou o acesso ao microfone."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "O serviço de reconhecimento perdeu a conexão."
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Não recebi uma transcrição final nesta tentativa."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "O reconhecedor estava ocupado."
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "O serviço limitou temporariamente as solicitações."
        else -> "O reconhecimento foi interrompido."
    }

    private companion object {
        const val BACKGROUND_DIM_AMOUNT = 0.78f
        const val INACTIVITY_CHECK_INTERVAL_MS = 5_000L
        const val STATE_REFRESH_INTERVAL_MS = 450L
        const val SCREENSHOT_DISPATCH_DELAY_MS = 450L
        const val TERMINAL_CALLBACK_TIMEOUT_MS = 3_000L
        const val MAX_TRANSCRIPT_LENGTH = 4_000
    }
}
