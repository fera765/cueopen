package dev.fluwy.app.agent

import dev.fluwy.app.data.KnowledgeItem
import dev.fluwy.app.data.MemoryItem
import java.text.Normalizer
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Regras puras da memória de longo prazo e da base de conhecimento: normalização de termos,
 * categorias canônicas, limites, deduplicação/merge de fatos parecidos e ranking de busca.
 */
internal object MemoryCurator {
    const val MAX_CHARS = 500
    const val MAX_ITEMS = 400
    const val MAX_TAGS = 8
    const val MAX_TAG_LEN = 24
    const val MAX_TITLE = 80
    const val DUP_THRESHOLD = 0.7

    const val SOURCE_AGENT = "agente"
    const val SOURCE_AUTO = "auto"
    const val SOURCE_USER = "usuario"

    private val STOP = setOf("que", "com", "para", "por", "uma", "um", "dos", "das", "nos", "nas", "the", "and", "for", "sobre", "mais", "como", "ele", "ela", "seu", "sua")

    /** Termos normalizados: minúsculas, sem acento, sem plural simples, sem palavras vazias. */
    fun terms(text: String): Set<String> = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .split(Regex("[^a-z0-9]+"))
        .filter { it.length > 2 && it !in STOP }
        .map { if (it.length > 4 && it.endsWith("s")) it.dropLast(1) else it }
        .toSet()

    private val KIND_SYNONYMS = mapOf(
        "preferencias" to "preferencia", "preference" to "preferencia", "gosto" to "preferencia",
        "people" to "pessoa", "person" to "pessoa", "pessoas" to "pessoa", "contato" to "pessoa",
        "project" to "projeto", "projetos" to "projeto",
        "work" to "trabalho", "emprego" to "trabalho", "empresa" to "trabalho",
        "tool" to "ferramenta", "ferramentas" to "ferramenta", "app" to "ferramenta", "conta" to "ferramenta",
        "place" to "lugar", "local" to "lugar", "endereco" to "lugar",
        "routine" to "rotina", "habito" to "rotina", "habit" to "rotina",
        "other" to "outro", "geral" to "outro",
    )

    /** Categoria canônica de [MemoryGraph.KINDS], ou vazio se desconhecida. */
    fun canonicalKind(kind: String): String {
        val k = MemoryGraph.norm(kind)
        if (k in MemoryGraph.KINDS) return k
        return KIND_SYNONYMS[k].orEmpty()
    }

    fun cleanTags(tags: List<String>): List<String> = tags.map { MemoryGraph.norm(it).take(MAX_TAG_LEN) }
        .filter { it.isNotBlank() }.distinct().take(MAX_TAGS)

    /** Semelhança 0..1: Jaccard de termos, ou contenção quase total quando um fato estende o outro. */
    fun similarity(a: String, b: String): Double {
        val x = terms(a); val y = terms(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        val inter = x.intersect(y).size.toDouble()
        val jac = inter / x.union(y).size
        val small = minOf(x.size, y.size)
        val containment = if (small >= 4) inter / small else 0.0
        return maxOf(jac, if (containment >= 0.9) DUP_THRESHOLD else 0.0)
    }

    /** Fato do mesmo escopo (global ou do mesmo Agent) parecido o bastante para ser fundido. */
    fun findDuplicate(items: List<MemoryItem>, text: String, agentId: String?): MemoryItem? = items
        .filter { it.agentId == agentId }
        .map { it to similarity(it.text, text) }
        .filter { it.second >= DUP_THRESHOLD }
        .maxByOrNull { it.second }?.first

    /** Funde um fato novo num existente sem perder informação (texto mais completo, tags/links unidos). */
    fun merge(old: MemoryItem, text: String, tags: List<String>, title: String, kind: String, links: List<String>, now: Long): MemoryItem =
        old.copy(
            text = if (text.length >= old.text.length) text else old.text,
            tags = cleanTags(old.tags + tags),
            title = title.trim().take(MAX_TITLE).ifBlank { old.title },
            kind = canonicalKind(kind).ifBlank { old.kind },
            links = (old.links + links).distinct().filter { it != old.id }.take(MemoryGraph.MAX_LINKS),
            lastUsedAt = now,
        )

    /** Ids a remover para caber em [max]: menos usadas e mais antigas primeiro. */
    fun overflow(items: List<MemoryItem>, max: Int = MAX_ITEMS): List<String> =
        if (items.size <= max) emptyList()
        else items.sortedWith(compareBy({ it.uses }, { it.lastUsedAt })).take(items.size - max).map { it.id }

    /** Relevância lexical: termos da consulta no título (peso 2), tags (1,5) e texto (1), normalizada pelo tamanho. */
    fun relevance(query: Set<String>, title: String, text: String, tags: List<String> = emptyList()): Double {
        if (query.isEmpty()) return 0.0
        val tt = terms(title); val tg = tags.flatMap { terms(it) }.toSet(); val tx = terms(text)
        val hits = query.sumOf { q -> when { q in tt -> 2.0; q in tg -> 1.5; q in tx -> 1.0; else -> 0.0 } }
        if (hits == 0.0) return 0.0
        val coverage = query.count { it in tt || it in tg || it in tx }.toDouble() / query.size
        return hits / sqrt((tx.size + tt.size).coerceAtLeast(1).toDouble()) + coverage
    }

    /** Pontuação de uma memória: relevância + leve bônus por uso (log), para desempatar. */
    fun memoryScore(query: Set<String>, m: MemoryItem): Double {
        val r = relevance(query, m.title, m.text, m.tags)
        return if (r <= 0) 0.0 else r + ln(1.0 + m.uses) * 0.05
    }

    /** Knowledge ordenado por relevância (só itens ativos; empate mantém a ordem original). */
    fun rankKnowledge(items: List<KnowledgeItem>, query: String): List<Pair<KnowledgeItem, Double>> {
        val q = terms(query)
        return items.filter { it.enabled }.map { it to relevance(q, it.title, it.content) }.sortedByDescending { it.second }
    }

    /** Origem legível: quem salvou e quando (ex.: "Salva pelo agente · 28/11/2026"). */
    fun originLabel(m: MemoryItem, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String {
        val who = when (m.source) {
            SOURCE_AGENT -> "Salva pelo agente"; SOURCE_AUTO -> "Aprendida automaticamente"
            SOURCE_USER -> "Adicionada por você"; else -> "Salva"
        }
        val date = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy").format(java.time.Instant.ofEpochMilli(m.createdAt).atZone(zone))
        return "$who · $date"
    }
}
