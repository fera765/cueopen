package dev.fluwy.app.agent

import java.text.Normalizer

/**
 * Decide quais skills da biblioteca um pedido do usuário aciona — por menção (@nome) ou pelo sentido do pedido.
 * A biblioteca é compartilhada: toda skill ativa vale para todos os agentes, sem seleção por agente.
 */
object SkillMatcher {
    data class Candidate(val name: String, val description: String = "")

    private val stop = setOf("de", "da", "do", "das", "dos", "em", "no", "na", "nos", "nas", "para", "por", "com", "um", "uma", "uns", "que", "the", "and", "for",
        "skill", "skills", "habilidade", "use", "usar", "usa", "usando", "favor", "agora")

    fun norm(s: String): String = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    fun tokens(s: String): Set<String> = norm(s).split(Regex("[^a-z0-9]+")).filter { it.length >= 3 && it !in stop }.toSet()

    /** `@Nome da Skill`, `@nomedaskill`, `@nome-da-skill` ou `@nome_da_skill`, sem diferenciar maiúsculas e acentos. */
    fun mentioned(text: String, name: String): Boolean {
        val t = norm(text)
        val n = norm(name).trim().replace(Regex("\\s+"), " ")
        if (n.isEmpty()) return false
        return listOf(n, n.replace(" ", ""), n.replace(" ", "-"), n.replace(" ", "_")).any { t.contains("@$it") }
    }

    /** Quantas palavras do nome da skill aparecem no pedido. */
    fun nameHits(text: String, name: String): Int { val q = tokens(text); return tokens(name).count { it in q } }

    /** O pedido descreve a skill sem citá-la com @: a maior parte do nome aparece, ou o usuário pede "a skill" e cita parte dele. */
    fun impliedBy(text: String, name: String): Boolean {
        val nameTokens = tokens(name)
        if (nameTokens.isEmpty()) return false
        val hits = nameHits(text, name)
        val asksForSkill = Regex("\\b(skill|skills|habilidade)\\b").containsMatchIn(norm(text))
        return (hits >= 2 && hits * 10 >= nameTokens.size * 6) || (asksForSkill && hits >= 1)
    }

    /** Relevância para ordenar a lista de skills mostrada ao agente (nome pesa mais que descrição). */
    fun score(text: String, c: Candidate): Int {
        val q = tokens(text)
        return tokens(c.name).count { it in q } * 3 + tokens(c.description).count { it in q }
    }

    /** Skills a ativar para este pedido: todas as mencionadas com @ e, no máximo, uma inferida pelo sentido (a de melhor encaixe). */
    fun select(text: String, skills: List<Candidate>): List<Candidate> {
        val explicit = skills.filter { mentioned(text, it.name) }
        val implied = skills.filter { it !in explicit && impliedBy(text, it.name) }
            .sortedWith(compareByDescending<Candidate> { nameHits(text, it.name) }.thenByDescending { score(text, it) })
        val best = implied.firstOrNull()
        // Empate entre duas skills diferentes: não adivinha; o agente decide pela lista.
        val tie = best != null && implied.size > 1 && nameHits(text, implied[1].name) == nameHits(text, best.name) && score(text, implied[1]) == score(text, best)
        return explicit + listOfNotNull(best.takeIf { !tie })
    }
}
