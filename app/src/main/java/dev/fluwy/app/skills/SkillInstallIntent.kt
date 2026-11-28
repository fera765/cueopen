package dev.fluwy.app.skills

import java.text.Normalizer

/**
 * Reconhece pedidos de instalar skill, em qualquer formato de origem: URL de SKILL.md, link/owner-repo do GitHub,
 * .zip (URL ou anexo no workspace) ou página com a skill. Também diz se, depois de instalar, ainda há trabalho para o
 * agente (ex.: "set up", "prepare o dashboard") ou se é só instalar.
 */
object SkillInstallIntent {
    data class Intent(val source: String, val name: String?, val followUp: Boolean)

    private val url = Regex("https?://[^\\s)\"'<>]+")
    private val ownerRepo = Regex("(?<![\\w./-])([A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9._-]{1,100})(?![\\w/-])")
    private val localPkg = Regex("(?<![\\w/])((?:/root/workspace/)?[\\w./-]+\\.zip|(?:/root/workspace/)?[\\w./-]*SKILL\\.md)\\b", RegexOption.IGNORE_CASE)
    private val installVerb = Regex("(?i)\\b(install|instal[ae]r?|instale|adicion[ae]r?|adicione|import[ae]r?|importe|baixe|baixar|add)\\b")
    private val setupVerb = Regex("(?i)\\b(set ?up|setup|configur[ae]r?|configure|prepar[ae]r?|prepare|rod[ae]r?|rode|execut[ae]r?|execute|abr[ae]|abrir|use|usar|inicie|iniciar|suba|subir|deixe pronto)\\b")
    private fun norm(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    fun detect(text: String): Intent? {
        val urls = url.findAll(text).map { it.value.trimEnd('.', ',', ';', ':', ')') }.toList()
        val mentionsSkill = Regex("(?i)\\bskills?\\b|SKILL\\.md").containsMatchIn(text)
        val anyVerb = installVerb.containsMatchIn(text) || setupVerb.containsMatchIn(text)
        val src = urls.firstOrNull { it.contains("SKILL.md", true) }
            ?: urls.firstOrNull { it.substringBefore('?').endsWith(".zip", true) && (mentionsSkill || anyVerb) }
            ?: urls.firstOrNull { it.contains("github.com/", true) && mentionsSkill && anyVerb }
            ?: urls.firstOrNull { mentionsSkill && installVerb.containsMatchIn(text) }
            ?: if (urls.isEmpty() && mentionsSkill && installVerb.containsMatchIn(text))
                (localPkg.find(text)?.groupValues?.get(1)?.trim() ?: ownerRepo.find(text)?.groupValues?.get(1)) else null
        if (src == null) return null
        if (!anyVerb && !src.contains("SKILL.md", true)) return null
        val name = Regex("(?i)(?:chamad[ao]|named|nome(?:ada)?|como skill(?: local)?(?: chamada)?)\\s+[\"“']?([A-Za-z0-9][A-Za-z0-9._-]{1,60})")
            .find(text)?.groupValues?.get(1)
            ?: Regex("(?i)skill\\s+[\"“']([A-Za-z0-9][A-Za-z0-9._ -]{1,60})[\"”']").find(text)?.groupValues?.get(1)
        // Sobra do pedido, tirando endereço, verbos de instalar e enchimento: se ainda pede algo, o agente continua.
        val rest = norm(text.replace(src, " ")).replace(Regex("https?://\\S+"), " ")
            .replace(Regex("\\b(install|instal[ae]r?|instale|adicion[ae]r?|import[ae]r?|baixe|baixar|add|a|o|as|os|esta|essa|esse|este|deste|desse|endereco|link|skill|skills|skill\\.md|md|local|chamad[ao]|como|salve|salvar|de|do|da|em|e|por|favor|pf|me|para|pra|aqui|agora|nova|novo)\\b"), " ")
            .replace(name?.let { norm(it) } ?: "\u0000", " ")
            .split(Regex("[^a-z0-9]+")).filter { it.length > 2 }
        val followUp = setupVerb.containsMatchIn(text) || rest.size >= 3
        return Intent(src, name?.trim(), followUp)
    }
}
