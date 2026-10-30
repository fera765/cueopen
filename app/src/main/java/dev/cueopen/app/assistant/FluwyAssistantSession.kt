package dev.cueopen.app.assistant

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
import dev.cueopen.app.MainActivity
import java.io.File
import java.util.UUID

/** Overlay enxuto do assistente Android, executado no processo isolado :assistant. */
class FluwyAssistantSession(baseContext: Context) : VoiceInteractionSession(baseContext) {
    private val appContext = baseContext.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var orb: AssistantOrbView? = null
    private var screenshotPreview: ImageView? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var listening = false
    private var commandSubmitted = false
    private var liveTranscript = ""
    private var lastVoiceActivityAt = 0L
    private var lastRenderedStateAt = 0L

    private val refreshState = object : Runnable {
        override fun run() {
            renderState(AssistantOverlayStateStore.read(appContext))
            mainHandler.postDelayed(this, STATE_REFRESH_INTERVAL_MS)
        }
    }

    /** Um comando já despachado continua na fila do app mesmo após o overlay ser fechado. */
    private val closeAfterInactivity = object : Runnable {
        override fun run() {
            val idleFor = SystemClock.elapsedRealtime() - lastVoiceActivityAt
            val hasUnfinalizedSpeech = !commandSubmitted && liveTranscript.isNotBlank()
            if (idleFor >= INACTIVITY_CLOSE_TIMEOUT_MS && !hasUnfinalizedSpeech) {
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
            contentDescription = "Assistente de voz. Toque no círculo para tentar ouvir."
            setOnClickListener {
                if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    requestMicrophonePermission()
                } else if (!commandSubmitted && speechRecognizer == null) {
                    startListening()
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
        mainHandler.removeCallbacks(refreshState)
        mainHandler.post(refreshState)
        lastVoiceActivityAt = SystemClock.elapsedRealtime()
        mainHandler.removeCallbacks(closeAfterInactivity)
        mainHandler.postDelayed(closeAfterInactivity, INACTIVITY_CHECK_INTERVAL_MS)
        if (!listening && speechRecognizer == null && !commandSubmitted) startListening()
    }

    override fun onHandleScreenshot(screenshot: android.graphics.Bitmap?) {
        // Deliberadamente não lê, mostra, salva ou encaminha capturas automáticas da Assist API.
    }

    override fun onHide() {
        mainHandler.removeCallbacks(refreshState)
        mainHandler.removeCallbacks(closeAfterInactivity)
        stopListening()
        super.onHide()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(refreshState)
        mainHandler.removeCallbacks(closeAfterInactivity)
        stopListening()
        super.onDestroy()
    }

    private fun startListening() {
        if (listening || speechRecognizer != null || commandSubmitted) return
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            orb?.contentDescription = "Permissão do microfone necessária. Toque no círculo para abrir o app e autorizá-la."
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            orb?.contentDescription = "Reconhecimento de fala indisponível nas configurações do Android."
            return
        }
        val recognizer = runCatching { SpeechRecognizer.createSpeechRecognizer(appContext) }.getOrElse {
            orb?.contentDescription = "Não foi possível iniciar o microfone. Toque no círculo para tentar novamente."
            return
        }
        val finalResultGate = AssistantRecognitionGate()
        speechRecognizer = recognizer
        liveTranscript = ""
        orb?.contentDescription = "Aguardando o microfone."

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                listening = true
                orb?.contentDescription = "Estou ouvindo. Fale e faça uma pausa para enviar automaticamente."
            }

            override fun onBeginningOfSpeech() {
                listening = true
                noteVoiceActivity()
                orb?.contentDescription = "Ouvindo sua fala."
            }

            override fun onRmsChanged(rmsdB: Float) {
                val amplitude = ((rmsdB + 2.5f) / 13.5f).coerceIn(0f, 1f)
                orb?.setRms(amplitude)
                if (amplitude >= VOICE_ACTIVITY_THRESHOLD) noteVoiceActivity()
            }

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                listening = false
                noteVoiceActivity()
                orb?.setRms(0f)
                orb?.contentDescription = "Fala encerrada. Aguardando a transcrição final para enviar."
            }

            override fun onError(error: Int) {
                if (!finalResultGate.resolve()) return
                if (speechRecognizer === recognizer) speechRecognizer = null
                listening = false
                liveTranscript = ""
                orb?.setRms(0f)
                orb?.contentDescription = speechError(error)
                recognizer.destroy()
            }

            override fun onResults(results: Bundle?) {
                if (!finalResultGate.resolve()) return
                if (speechRecognizer === recognizer) speechRecognizer = null
                listening = false
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim()?.take(MAX_TRANSCRIPT_LENGTH).orEmpty()
                liveTranscript = ""
                finishRecognition(text)
                recognizer.destroy()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (finalResultGate.isResolved || commandSubmitted) return
                val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim()?.take(MAX_TRANSCRIPT_LENGTH).orEmpty()
                if (partial.isNotBlank()) {
                    liveTranscript = partial
                    noteVoiceActivity()
                    orb?.contentDescription = "Transcrevendo sua fala. Aguarde a pausa para envio automático."
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })

        val request = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
            // Finaliza a fala após uma pausa curta; o envio só acontece em onResults (resultado final).
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, END_OF_SPEECH_SILENCE_MS)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, POSSIBLE_END_OF_SPEECH_SILENCE_MS)
        }
        runCatching { recognizer.startListening(request) }.onFailure {
            if (speechRecognizer === recognizer) speechRecognizer = null
            listening = false
            orb?.contentDescription = "Não foi possível iniciar o reconhecedor. Toque no círculo para tentar novamente."
            recognizer.destroy()
        }
    }

    private fun finishRecognition(text: String) {
        orb?.setRms(0f)
        if (text.isBlank()) {
            orb?.contentDescription = "Não ouvi uma frase completa. Toque no círculo para tentar novamente."
            return
        }
        if (commandSubmitted) return
        commandSubmitted = true
        val intent = Intent(appContext, AssistantCommandReceiver::class.java).apply {
            action = AssistantRoutingPolicy.ACTION_SUBMIT_COMMAND
            putExtra(AssistantRoutingPolicy.EXTRA_REQUEST_ID, UUID.randomUUID().toString())
            putExtra(AssistantRoutingPolicy.EXTRA_TRANSCRIPT, text)
        }
        orb?.contentDescription = "Comando enviado ao chat ativo. A tarefa continuará mesmo quando o overlay fechar."

        if (AssistantRoutingPolicy.explicitlyRequestsScreenshot(text)) {
            // A captura explícita segue pelo fluxo de consentimento MediaProjection já existente.
            finish()
            mainHandler.postDelayed({ appContext.sendBroadcast(intent) }, SCREENSHOT_DISPATCH_DELAY_MS)
        } else {
            appContext.sendBroadcast(intent)
        }
    }

    private fun noteVoiceActivity() {
        lastVoiceActivityAt = SystemClock.elapsedRealtime()
    }

    private fun stopListening() {
        val recognizer = speechRecognizer ?: return
        speechRecognizer = null
        listening = false
        runCatching { recognizer.cancel() }
        runCatching { recognizer.destroy() }
        orb?.setRms(0f)
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
        SpeechRecognizer.ERROR_AUDIO -> "O microfone do Android não ficou disponível. Toque no círculo para tentar novamente."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "O Android negou o acesso ao microfone. Toque no círculo para autorizá-lo."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "O reconhecimento não conseguiu conectar. Toque no círculo para tentar novamente."
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Não ouvi uma frase completa. Toque no círculo para tentar novamente."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "O reconhecedor já está ocupado. Toque no círculo para tentar novamente."
        else -> "O reconhecimento foi interrompido. Toque no círculo para tentar novamente."
    }

    private companion object {
        const val BACKGROUND_DIM_AMOUNT = 0.58f
        const val END_OF_SPEECH_SILENCE_MS = 1_200L
        const val POSSIBLE_END_OF_SPEECH_SILENCE_MS = 900L
        const val INACTIVITY_CLOSE_TIMEOUT_MS = 60_000L
        const val INACTIVITY_CHECK_INTERVAL_MS = 5_000L
        const val STATE_REFRESH_INTERVAL_MS = 450L
        const val SCREENSHOT_DISPATCH_DELAY_MS = 450L
        const val MAX_TRANSCRIPT_LENGTH = 4_000
        const val VOICE_ACTIVITY_THRESHOLD = 0.12f
    }
}
