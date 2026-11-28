package dev.fluwy.app.agent

/**
 * Menções "@Nome" no texto (skills, conectores, agentes). Nomes podem ter espaços; o mais longo vence
 * ("@Activepieces Hub" antes de "@Activepieces"). A menção termina no fim do texto ou num separador.
 */
object Mentions {
    enum class Kind { SKILL, CONNECTOR, AGENT, COMMAND, WEBAPI }
    data class Target(val label: String, val kind: Kind, val id: String)
    data class Hit(val start: Int, val end: Int, val target: Target)

    fun find(text: String, targets: Collection<Target>): List<Hit> {
        if (!text.contains('@') || targets.isEmpty()) return emptyList()
        val sorted = targets.filter { it.label.isNotBlank() }.sortedByDescending { it.label.length }
        val hits = ArrayList<Hit>()
        var i = text.indexOf('@')
        while (i >= 0) {
            val atBoundary = i == 0 || !text[i - 1].isLetterOrDigit()
            var matched: Hit? = null
            if (atBoundary) for (t in sorted) {
                val end = i + 1 + t.label.length
                if (end <= text.length && text.regionMatches(i + 1, t.label, 0, t.label.length, ignoreCase = true) &&
                    (end == text.length || !text[end].isLetterOrDigit())) { matched = Hit(i, end, t); break }
            }
            if (matched != null) { hits += matched; i = text.indexOf('@', matched.end) } else i = text.indexOf('@', i + 1)
        }
        return hits
    }
}
