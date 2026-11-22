package dev.cueopen.app.ui

/** Ajustes estilo GitHub antes do renderizador (fora de blocos de código): listas de tarefas e links soltos. */
object GfmPrep {
    private val task = Regex("(?m)^(\\s*)[-*+] \\[( |x|X)] ")
    private val bareUrl = Regex("(?<![(<\\[\"'=])\\bhttps?://[^\\s<>()\\[\\]]+[^\\s<>()\\[\\].,;:!?\"']")

    fun normalize(md: String): String {
        if (md.isEmpty()) return md
        val out = StringBuilder()
        var inFence = false
        md.split('\n').forEachIndexed { i, line ->
            if (i > 0) out.append('\n')
            if (line.trimStart().startsWith("```")) { inFence = !inFence; out.append(line); return@forEachIndexed }
            if (inFence) { out.append(line); return@forEachIndexed }
            var l = task.replace(line) { m -> m.groupValues[1] + "- " + (if (m.groupValues[2].isBlank()) "☐ " else "☑ ") }
            // Link solto vira link clicável; não mexe em código inline nem em links markdown já prontos.
            if (!l.contains('`')) l = bareUrl.replace(l) { "<" + it.value + ">" }
            out.append(l)
        }
        return out.toString()
    }
}
