package dev.cueopen.app.skills

/** Reconhece pedidos de instalar skill: "set up https://x/SKILL.md", "instale a skill X deste endereço: <github>". */
object SkillInstallIntent {
    data class Intent(val source: String, val name: String?)

    private val url = Regex("https?://[^\\s)\"'<>]+")
    private val verbs = Regex("(?i)\\b(set ?up|setup|install|instal[ae]r?|instale|adicione|adicionar|importe|importar|baixe|configure)\\b")

    fun detect(text: String): Intent? {
        val urls = url.findAll(text).map { it.value.trimEnd('.', ',', ';', ':') }.toList()
        val src = urls.firstOrNull { it.contains("SKILL.md", true) } ?: urls.firstOrNull { it.contains("github.com/", true) && Regex("(?i)skill").containsMatchIn(text) }
            ?: urls.firstOrNull { verbs.containsMatchIn(text) && Regex("(?i)\\bskills?\\b").containsMatchIn(text) }
            ?: return null
        if (!verbs.containsMatchIn(text) && !src.contains("SKILL.md", true)) return null
        val name = Regex("(?i)(?:chamad[ao]|named|nome(?:ada)?|como skill(?: local)?(?: chamada)?)\\s+[\"“']?([A-Za-z0-9][A-Za-z0-9._-]{1,60})")
            .find(text)?.groupValues?.get(1)
            ?: Regex("(?i)skill\\s+[\"“']([A-Za-z0-9][A-Za-z0-9._ -]{1,60})[\"”']").find(text)?.groupValues?.get(1)
        return Intent(src, name?.trim())
    }
}
