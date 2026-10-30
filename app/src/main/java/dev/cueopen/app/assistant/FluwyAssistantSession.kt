package dev.cueopen.app.assistant

import android.Manifest
import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.service.voice.VoiceInteractionSession
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import dev.cueopen.app.MainActivity
import java.io.File
import java.util.UUID

/** Overlay transparente do assistente Android, executado no processo isolado :assistant. */
class FluwyAssistantSession(baseContext: Context) : VoiceInteractionSession(baseContext) {
    private val appContext = baseContext.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private var orb: AssistantOrbView? = null
    private var phaseTitle: TextView? = null
    private var detailText: TextView? = null
    private var resultText: TextView? = null
    private var chatButton: TextView? = null
    private var transcriptText: TextView? = null
    private var contextButton: TextView? = null
    private var permissionButton: TextView? = null
    private var screenshotPreview: ImageView? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var listening = false
    private var contextRequested = false
    private var screenText = ""
    private var liveTranscript = ""
    private var lastRenderedStateAt = 0L

    private val refreshState = object : Runnable {
        override fun run() {
            renderState(AssistantOverlayStateStore.read(appContext))
            mainHandler.postDelayed(this, 450L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Assist screenshots are never used. Explicit screenshot requests go through the existing
        // MediaProjection + agent-capability flow, after this assistant window is dismissed.
        setDisabledShowContext(SHOW_WITH_SCREENSHOT)
        getWindow()?.window?.let { window ->
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.attributes = window.attributes.apply {
                width = ViewGroup.LayoutParams.MATCH_PARENT
                height = ViewGroup.LayoutParams.MATCH_PARENT
                dimAmount = 0f
            }
        }
    }

    override fun onCreateContentView(): View {
        val density = appContext.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        fun rounded(color: Int, stroke: Int? = null, radius: Int = 22) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            stroke?.let { setStroke(dp(1), it) }
        }
        fun label(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(appContext).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            includeFontPadding = false
        }
        fun pill(value: String, description: String, onClick: () -> Unit) = label(value, 13f, 0xFFFF9A55.toInt(), true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rounded(0xCC17110D.toInt(), 0x66FF8B45, 30)
            contentDescription = description
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

        val root = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setBackgroundColor(Color.TRANSPARENT)
            clipChildren = false
            clipToPadding = false
        }

        val header = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48))
        }
        val identity = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }
        identity.addView(label("FLUWY  /  ASSISTENTE", 11f, 0xFFFF9145.toInt(), true))
        identity.addView(label("Voz para texto · resposta visual", 11f, 0xFFD3C8BE.toInt()).apply { setPadding(0, dp(4), 0, 0) })
        header.addView(identity)

        val settings = label("⚙", 23f, 0xFFFF9A42.toInt(), true).apply {
            gravity = Gravity.CENTER
            background = rounded(0xAA17110D.toInt(), 0x77FF8A31, 30)
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(8) }
            contentDescription = "Abrir configurações do aplicativo"
            isClickable = true
            isFocusable = true
            setOnClickListener { openMainActivity(AssistantRoutingPolicy.ACTION_OPEN_SETTINGS) }
        }
        header.addView(settings)
        header.addView(label("×", 27f, 0xFFE3D6CC.toInt()).apply {
            gravity = Gravity.CENTER
            background = rounded(0x7717110D, 0x33FFFFFF, 30)
            layoutParams = LinearLayout.LayoutParams(dp(42), dp(42))
            contentDescription = "Fechar assistente"
            isClickable = true
            isFocusable = true
            setOnClickListener { finish() }
        })
        root.addView(header)

        val responseCard = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(12), dp(15), dp(12))
            background = rounded(0xB8120D0A.toInt(), 0x40FF8A39)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            }
        }
        phaseTitle = label("Pronto para ouvir", 13f, 0xFFFFAF71.toInt(), true)
        detailText = label("Sua fala será enviada a um único chat. O agente responde aqui e no histórico do chat.", 12f, 0xFFE4DAD2.toInt()).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(5), 0, 0)
        }
        resultText = label("", 13f, 0xFFF8F1EA.toInt()).apply {
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            visibility = View.GONE
            setPadding(0, dp(7), 0, 0)
        }
        responseCard.addView(phaseTitle)
        responseCard.addView(detailText)
        responseCard.addView(resultText)
        chatButton = label("Abrir conversa completa →", 12f, 0xFFFFA064.toInt(), true).apply {
            visibility = View.GONE
            setPadding(0, dp(9), 0, 0)
            contentDescription = "Abrir o chat do agente com o resultado completo"
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val state = AssistantOverlayStateStore.read(appContext)
                state.sessionId.takeIf(String::isNotBlank)?.let { openMainActivity(AssistantRoutingPolicy.ACTION_OPEN_CHAT, it) }
            }
        }
        responseCard.addView(chatButton)
        root.addView(responseCard)

        val orbZone = FrameLayout(appContext).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                topMargin = dp(4)
                bottomMargin = dp(2)
            }
            minimumHeight = dp(250)
            setBackgroundColor(Color.TRANSPARENT)
        }
        orb = AssistantOrbView(appContext).apply {
            layoutParams = FrameLayout.LayoutParams(dp(310), dp(310), Gravity.CENTER)
            setOnClickListener {
                if (listening) stopListening(cancel = true)
                startListening()
            }
        }
        orbZone.addView(orb)
        root.addView(orbZone)

        val footer = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(0xB8120D0A.toInt(), 0x35FF8734)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        transcriptText = label("Toque no círculo para falar", 13f, 0xFFF5ECE4.toInt()).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            minHeight = dp(34)
            gravity = Gravity.CENTER_VERTICAL
        }
        footer.addView(transcriptText)

        val controls = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        contextButton = pill("Contexto desligado", "Solicitar explicitamente o texto não protegido desta tela", ::requestAssistContext)
        controls.addView(contextButton, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(8) })
        permissionButton = pill("Permitir microfone", "Abrir a permissão de microfone do Android", ::requestMicrophonePermission).apply {
            visibility = View.GONE
        }
        controls.addView(permissionButton, LinearLayout.LayoutParams(0, dp(42), 1f))
        footer.addView(controls)
        footer.addView(label("Sem áudio de resposta · capturas só quando você pedir", 10f, 0xFFB7AAA0.toInt()).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(9), 0, 0)
        })
        root.addView(footer)

        screenshotPreview = ImageView(appContext).apply {
            visibility = View.GONE
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = "Captura solicitada, abaixo do conteúdo do assistente"
            background = rounded(0xCC17110D.toInt(), 0x66FF8A39)
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(118)).apply { topMargin = dp(8) }
        }
        root.addView(screenshotPreview)

        renderState(AssistantOverlayStateStore.read(appContext))
        return root
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        mainHandler.removeCallbacks(refreshState)
        mainHandler.post(refreshState)
        if (!listening) startListening()
    }

    override fun onHandleAssist(data: Bundle?, structure: AssistStructure?, content: AssistContent?) {
        // AssistStructure só é percorrida depois do toque explícito em “Contexto desligado”.
        if (!contextRequested) return
        screenText = readAssistStructure(structure)
        contextRequested = false
        contextButton?.text = if (screenText.isBlank()) "Contexto indisponível" else "Contexto incluído"
        detailText?.text = if (screenText.isBlank())
            "O Android não disponibilizou texto desta tela; somente sua fala será enviada."
        else
            "Texto não protegido recebido por solicitação explícita. Será anexado à próxima fala."
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        // Intencionalmente não lê, mostra, salva nem encaminha a captura automática da Assist API.
        // Capturas explícitas usam apenas device_screenshot + consentimento MediaProjection do Android.
    }

    override fun onHide() {
        mainHandler.removeCallbacks(refreshState)
        stopListening(cancel = true)
        clearEphemeralContext()
        super.onHide()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(refreshState)
        stopListening(cancel = true)
        clearEphemeralContext()
        super.onDestroy()
    }

    private fun startListening() {
        if (listening) return
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            phaseTitle?.text = "Microfone não autorizado"
            detailText?.text = "Toque em Permitir microfone. O Android mostrará a confirmação; depois, invoque o assistente novamente."
            permissionButton?.visibility = View.VISIBLE
            orb?.setRms(0f)
            return
        }
        permissionButton?.visibility = View.GONE
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            phaseTitle?.text = "Reconhecimento indisponível"
            detailText?.text = "Confira o serviço de reconhecimento de fala escolhido nas configurações do Android."
            return
        }
        val recognizer = runCatching { SpeechRecognizer.createSpeechRecognizer(appContext) }.getOrElse {
            phaseTitle?.text = "Não foi possível iniciar o microfone"
            detailText?.text = it.message ?: "O serviço de voz do Android não está disponível."
            return
        }
        speechRecognizer = recognizer
        liveTranscript = ""
        transcriptText?.text = "Aguardando o microfone…"
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                listening = true
                phaseTitle?.text = "Estou ouvindo"
                detailText?.text = "Fale naturalmente. Toque no círculo para interromper e ouvir novamente."
            }
            override fun onBeginningOfSpeech() {
                phaseTitle?.text = "Ouvindo sua fala"
            }
            override fun onRmsChanged(rmsdB: Float) {
                val amplitude = ((rmsdB + 2.5f) / 13.5f).coerceIn(0f, 1f)
                orb?.setRms(amplitude)
            }
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() {
                listening = false
                phaseTitle?.text = "Entendendo…"
                orb?.setRms(0f)
            }
            override fun onError(error: Int) {
                listening = false
                speechRecognizer = null
                orb?.setRms(0f)
                if (error != SpeechRecognizer.ERROR_CLIENT || liveTranscript.isBlank()) {
                    phaseTitle?.text = "Pronto para ouvir"
                    detailText?.text = speechError(error)
                }
                recognizer.destroy()
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim()?.take(4_000).orEmpty()
                finishRecognition(text)
                recognizer.destroy()
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.trim()?.take(4_000).orEmpty()
                if (partial.isNotBlank()) {
                    liveTranscript = partial
                    transcriptText?.text = partial
                    phaseTitle?.text = "Transcrevendo sua fala"
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        val request = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
        }
        runCatching { recognizer.startListening(request) }.onFailure {
            listening = false
            speechRecognizer = null
            phaseTitle?.text = "Não foi possível ouvir"
            detailText?.text = it.message ?: "O reconhecedor do Android não iniciou."
            recognizer.destroy()
        }
    }

    private fun finishRecognition(text: String) {
        listening = false
        orb?.setRms(0f)
        if (text.isBlank()) {
            phaseTitle?.text = "Não ouvi uma frase completa"
            detailText?.text = "Toque no círculo para tentar de novo."
            return
        }
        liveTranscript = text
        transcriptText?.text = text
        val command = if (screenText.isNotBlank()) {
            "$text\n\n[Contexto da tela solicitado pelo usuário — conteúdo protegido por senha foi excluído]\n$screenText"
        } else text
        screenText = ""
        contextRequested = false
        contextButton?.text = "Contexto desligado"

        val intent = Intent(appContext, AssistantCommandReceiver::class.java).apply {
            action = AssistantRoutingPolicy.ACTION_SUBMIT_COMMAND
            putExtra(AssistantRoutingPolicy.EXTRA_REQUEST_ID, UUID.randomUUID().toString())
            putExtra(AssistantRoutingPolicy.EXTRA_TRANSCRIPT, command)
        }
        phaseTitle?.text = "Enviando para o chat"
        detailText?.text = "O comando será executado no processo principal, com as permissões e limites configurados para o agente."

        if (AssistantRoutingPolicy.explicitlyRequestsScreenshot(text)) {
            // Oculta o orb antes de iniciar uma captura solicitada, para que a imagem represente
            // o aplicativo que estava atrás do assistente e não inclua a própria interface.
            finish()
            mainHandler.postDelayed({ appContext.sendBroadcast(intent) }, 450L)
        } else {
            appContext.sendBroadcast(intent)
        }
    }

    private fun stopListening(cancel: Boolean) {
        val recognizer = speechRecognizer ?: return
        speechRecognizer = null
        listening = false
        runCatching { if (cancel) recognizer.cancel() else recognizer.stopListening() }
        runCatching { recognizer.destroy() }
        orb?.setRms(0f)
    }

    private fun requestAssistContext() {
        contextRequested = true
        screenText = ""
        contextButton?.text = "Solicitando contexto…"
        detailText?.text = "A tela só será consultada após este toque. Capturas de tela continuam desativadas aqui."
        show(Bundle(), SHOW_WITH_ASSIST)
    }

    private fun requestMicrophonePermission() {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            action = AssistantRoutingPolicy.ACTION_REQUEST_MICROPHONE
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startAssistantActivity(intent)
    }

    private fun openMainActivity(action: String, sessionId: String? = null) {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            this.action = action
            sessionId?.let { putExtra(AssistantRoutingPolicy.EXTRA_SESSION_ID, it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startAssistantActivity(intent)
    }

    private fun renderState(state: AssistantOverlayState) {
        if (state.updatedAt < lastRenderedStateAt) return
        lastRenderedStateAt = state.updatedAt
        if (state.phase == "idle") return
        val title = when (state.phase) {
            "queued" -> "Na fila · ${state.targetLabel.ifBlank { "chat ativo" }}"
            "working" -> "Trabalhando · ${state.targetLabel.ifBlank { "chat ativo" }}"
            "done" -> "Concluído · ${state.targetLabel.ifBlank { "chat ativo" }}"
            "needs_input" -> "Precisa de você · ${state.targetLabel.ifBlank { "chat ativo" }}"
            "error" -> "A tarefa encontrou um problema"
            "stopped" -> "Tarefa interrompida"
            else -> "Pronto para ouvir"
        }
        phaseTitle?.text = title
        detailText?.text = state.detail.ifBlank { "Acompanhe a atividade do agente no chat." }
        val result = state.result.trim()
        resultText?.apply {
            text = result
            visibility = if (result.isBlank()) View.GONE else View.VISIBLE
        }
        chatButton?.visibility = if (state.sessionId.isNotBlank()) View.VISIBLE else View.GONE
        if (liveTranscript.isBlank()) transcriptText?.text = state.transcript.ifBlank { "Toque no círculo para falar" }
        if (!listening && state.phase in setOf("working", "done", "needs_input", "error", "stopped")) {
            permissionButton?.visibility = View.GONE
        }
        val imageFile = state.screenshotPath?.let(::File)?.takeIf { it.isFile && it.lastModified() >= state.startedAt - 3_000L }
        if (imageFile != null) {
            val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
            val bitmap = runCatching { BitmapFactory.decodeFile(imageFile.absolutePath, opts) }.getOrNull()
            if (bitmap != null) {
                screenshotPreview?.setImageBitmap(bitmap)
                screenshotPreview?.visibility = View.VISIBLE
            }
        } else {
            screenshotPreview?.setImageDrawable(null)
            screenshotPreview?.visibility = View.GONE
        }
    }

    private fun readAssistStructure(structure: AssistStructure?): String {
        if (structure == null) return ""
        val nodes = ArrayList<AssistTextPolicy.NodeText>()
        var visited = 0
        fun walk(node: AssistStructure.ViewNode?) {
            if (node == null || visited >= AssistTextPolicy.MAX_NODES) return
            visited++
            nodes += AssistTextPolicy.NodeText(
                text = node.text?.toString(),
                hint = node.hint?.toString(),
                contentDescription = node.contentDescription?.toString(),
                inputType = node.inputType,
            )
            for (index in 0 until node.childCount) {
                if (visited >= AssistTextPolicy.MAX_NODES) break
                walk(node.getChildAt(index))
            }
        }
        for (index in 0 until structure.windowNodeCount) {
            if (visited >= AssistTextPolicy.MAX_NODES) break
            walk(structure.getWindowNodeAt(index).rootViewNode)
        }
        return AssistTextPolicy.collect(nodes)
    }

    private fun speechError(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "O microfone do Android não ficou disponível."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "O Android negou a permissão do microfone."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "O serviço de reconhecimento não conseguiu conectar."
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Não ouvi uma frase completa. Toque no círculo para tentar novamente."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "O reconhecedor do Android já está ocupado."
        else -> "O reconhecimento foi interrompido (código $code). Toque no círculo para tentar novamente."
    }

    private fun clearEphemeralContext() {
        screenText = ""
        contextRequested = false
    }
}
