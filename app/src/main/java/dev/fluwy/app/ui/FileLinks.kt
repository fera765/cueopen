package dev.fluwy.app.ui

import androidx.compose.runtime.staticCompositionLocalOf

/** Abre um arquivo citado pelo agente (caminho do workspace ou /root/...) no visualizador do app. Null fora do chat. */
val LocalOpenFile = staticCompositionLocalOf<((String) -> Unit)?> { null }

object FileLinks {
    /** "📎 relatorio.md — entrega final" → ("relatorio.md", "entrega final"). Null se a linha não é um anexo. */
    fun parseAttachmentLine(line: String): Pair<String, String>? {
        val t = line.trim()
        if (!t.startsWith("📎")) return null
        val body = t.removePrefix("📎").trim().trim('`')
        if (body.isBlank()) return null
        val parts = body.split(Regex("\\s+[—–-]\\s+"), limit = 2)
        return parts[0].trim().trim('`') to parts.getOrNull(1).orEmpty().trim()
    }
}
