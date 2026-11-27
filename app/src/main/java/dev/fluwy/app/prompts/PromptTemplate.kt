package dev.fluwy.app.prompts

/**
 * Motor de template dos prompts (subconjunto pequeno de Mustache):
 * - `{{nome}}` troca pelo valor da variável;
 * - `{{#flag}}…{{/flag}}` mantém o trecho só se a flag for verdadeira; `{{^flag}}…{{/flag}}` só se for falsa;
 * - uma linha que tinha conteúdo e ficou vazia depois da troca é removida (linhas em branco originais ficam).
 */
object PromptTemplate {
    private val varRe = Regex("""\{\{\s*([A-Za-z0-9_]+)\s*\}\}""")
    private val sectionRe = Regex("""\{\{([#^])([A-Za-z0-9_]+)\}\}(.*?)\{\{/\2\}\}""", RegexOption.DOT_MATCHES_ALL)

    fun render(text: String, vars: Map<String, String> = emptyMap(), flags: Map<String, Boolean> = emptyMap()): String {
        // Tag de seção sozinha na linha não deixa linha em branco para trás.
        var s = text.replace(Regex("""(?m)^[ \t]*(\{\{[#^/][A-Za-z0-9_]+\}\})[ \t]*\n"""), "$1")
        while (true) {
            val next = sectionRe.replace(s) { m ->
                val on = flags[m.groupValues[2]] == true
                if ((m.groupValues[1] == "#") == on) m.groupValues[3] else "\u0000"
            }
            if (next == s) break
            s = next
        }
        val kept = s.split('\n').mapNotNull { line ->
            val replaced = varRe.replace(line) { vars[it.groupValues[1]] ?: "" }.replace("\u0000", "")
            if (line.isNotBlank() && replaced.isBlank()) null else replaced
        }
        return kept.joinToString("\n")
    }

    /** Nomes de variáveis e flags usados no texto (para mostrar na tela de edição). */
    fun placeholders(text: String): List<String> =
        (varRe.findAll(text).map { it.groupValues[1] } + Regex("""\{\{[#^/]([A-Za-z0-9_]+)\}\}""").findAll(text).map { it.groupValues[1] })
            .distinct().toList()
}
