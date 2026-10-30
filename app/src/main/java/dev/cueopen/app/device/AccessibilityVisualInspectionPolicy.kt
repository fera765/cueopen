package dev.cueopen.app.device

import java.text.Normalizer

/** Captura visual é somente fallback para ler/analisar um post de app social solicitado pelo usuário. */
internal object AccessibilityVisualInspectionPolicy {
    private val postSubject = Regex("\\b(post(agem)?|publicacao|feed)\\b")
    private val imageSubject = Regex("\\b(imagem|foto|fotografia|meme|image|photo|picture)\\b")
    private val socialApp = Regex("\\b(instagram|facebook|tiktok|twitter|linkedin|reddit)\\b")
    private val intent = Regex("\\b(ler|leia|leitura|ver|veja|verificar|verifique|analisar|analise|analisa|olhar|olhe|descrever|descreva|identificar|identifique|read|inspect|analyze|describe|identify|view)\\b")

    fun explicitlyRequestsPostOrImage(text: String): Boolean {
        val normalized = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
        if (!postSubject.containsMatchIn(normalized) &&
            !(socialApp.containsMatchIn(normalized) && imageSubject.containsMatchIn(normalized))) return false
        return intent.containsMatchIn(normalized) ||
            Regex("\\b(o que|what)\\b.{0,30}\\b(aparece|tem|mostra|esta|is|shows)\\b")
                .containsMatchIn(normalized)
    }

    fun canInspectImage(text: String, currentScreenWasRead: Boolean, inspectionAlreadyUsed: Boolean): Boolean =
        explicitlyRequestsPostOrImage(text) && currentScreenWasRead && !inspectionAlreadyUsed
}
