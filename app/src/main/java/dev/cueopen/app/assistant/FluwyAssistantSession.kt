package dev.cueopen.app.assistant

import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSession.AssistState
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.cueopen.app.MainActivity

/** Overlay exibido pelo sistema apenas quando a pessoa invoca o assistente Android padrão. */
class FluwyAssistantSession(baseContext: Context) : VoiceInteractionSession(baseContext) {
    private val appContext = baseContext.applicationContext
    private var screenText = ""
    private var promptField: EditText? = null
    private var statusLabel: TextView? = null
    private var screenPreview: TextView? = null
    private var screenshotPreview: ImageView? = null

    override fun onCreateContentView(): View {
        val density = appContext.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        fun rounded(color: Int, border: Int? = null) = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(24).toFloat()
            if (border != null) setStroke(dp(1), border)
        }
        fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(appContext).apply {
            this.text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }
        fun gap(height: Int) = View(appContext).apply {
            layoutParams = LinearLayout.LayoutParams(1, dp(height))
        }

        val panel = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(20))
            background = rounded(0xFF151515.toInt(), 0xFF34312E.toInt())
        }
        val header = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val brand = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        brand.addView(text("FLUWY  /  ASSIST", 12f, 0xFFFF7A50.toInt(), true))
        brand.addView(text("Assistente do Android", 12f, 0xFFB4B0A8.toInt()))
        header.addView(brand)
        val close = text("Fechar", 13f, 0xFFB4B0A8.toInt()).apply {
            setPadding(dp(12), dp(8), dp(2), dp(8))
            contentDescription = "Fechar assistente"
            setOnClickListener { finish() }
        }
        header.addView(close)
        panel.addView(header)
        panel.addView(gap(18))
        panel.addView(text("Qual é o próximo passo?", 24f, 0xFFF3F1EC.toInt(), true))
        panel.addView(gap(6))
        panel.addView(text(
            "A tela só é consultada nesta invocação. O conteúdo permanece aqui até você escolher continuar.",
            14f, 0xFFB4B0A8.toInt(),
        ))
        panel.addView(gap(14))

        statusLabel = text("Aguardando contexto do Android…", 13f, 0xFFFFC2AE.toInt(), true).apply {
            setPadding(dp(12), dp(11), dp(12), dp(11))
            background = rounded(0xFF29211E.toInt())
        }
        panel.addView(statusLabel)
        panel.addView(gap(8))
        screenPreview = text("O Android pode não fornecer texto de algumas telas, por exemplo quando o app de origem bloqueia o Assist API.", 12f, 0xFFB4B0A8.toInt()).apply {
            maxLines = 4
            setPadding(dp(4), dp(2), dp(4), dp(2))
        }
        panel.addView(screenPreview)
        screenshotPreview = ImageView(appContext).apply {
            visibility = View.GONE
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "Prévia local da tela fornecida pelo Android"
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150)).apply {
                topMargin = dp(8)
            }
            background = rounded(0xFF222222.toInt())
            clipToOutline = true
        }
        panel.addView(screenshotPreview)
        panel.addView(gap(14))

        promptField = EditText(appContext).apply {
            hint = "Escreva o que você quer fazer…"
            setHintTextColor(0xFF8A857D.toInt())
            setTextColor(0xFFF3F1EC.toInt())
            textSize = 16f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            maxLines = 4
            gravity = Gravity.TOP or Gravity.START
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(0xFF242322.toInt(), 0xFF3A3835.toInt())
        }
        panel.addView(promptField, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        panel.addView(gap(12))

        val continueButton = Button(appContext).apply {
            text = "Continuar no Fluwy"
            isAllCaps = false
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = rounded(0xFFFF6B3D.toInt())
            contentDescription = "Abrir um rascunho no Fluwy com o contexto revisável da tela"
            setOnClickListener { openReviewableDraft() }
        }
        panel.addView(continueButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        panel.addView(gap(6))
        panel.addView(text("Nada é enviado nem executado até você revisar e enviar no chat.", 11f, 0xFF8A857D.toInt()))

        return ScrollView(appContext).apply {
            isFillViewport = true
            setPadding(dp(12), dp(8), dp(12), dp(8))
            addView(panel, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            setBackgroundColor(Color.TRANSPARENT)
        }
    }

    @Suppress("DEPRECATION")
    override fun onHandleAssist(data: Bundle?, structure: AssistStructure?, content: AssistContent?) {
        updateScreenText(readAssistStructure(structure))
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        if (screenshot == null) {
            screenshotPreview?.visibility = View.GONE
            statusLabel?.text = "O Android não forneceu uma prévia da tela."
            return
        }
        screenshotPreview?.apply {
            setImageBitmap(screenshot)
            visibility = View.VISIBLE
        }
        statusLabel?.text = if (screenText.isBlank())
            "O Android forneceu uma prévia local da tela."
        else
            "Contexto recebido pelo Android e mantido localmente."
    }

    override fun onHide() {
        clearEphemeralContext()
        super.onHide()
    }

    override fun onDestroy() {
        clearEphemeralContext()
        super.onDestroy()
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

    private fun updateScreenText(value: String) {
        screenText = value
        statusLabel?.text = if (value.isBlank())
            "O Android não forneceu texto desta tela."
        else
            "Texto da tela disponível · somente neste dispositivo"
        screenPreview?.text = if (value.isBlank())
            "O app de origem pode bloquear o Assist API. Você ainda pode escrever um pedido sem incluir contexto da tela."
        else
            "Prévia local (não enviada):\n" + value.take(420) + if (value.length > 420) "…" else ""
    }

    private fun openReviewableDraft() {
        val prompt = promptField?.text?.toString()?.trim().orEmpty().ifBlank {
            "Ajude-me a entender esta tela."
        }
        val draft = buildString {
            append(prompt)
            if (screenText.isNotBlank()) {
                append("\n\nContexto da tela fornecido pelo Android (revise antes de enviar):\n")
                append(screenText)
            }
        }
        val intent = Intent(appContext, MainActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, draft)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startAssistantActivity(intent)
        clearEphemeralContext()
        finish()
    }

    private fun clearEphemeralContext() {
        screenText = ""
        screenshotPreview?.setImageDrawable(null)
        screenshotPreview?.visibility = View.GONE
        promptField?.setText("")
        updateScreenText("")
    }
}
