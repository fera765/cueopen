package dev.cueopen.app.agent

import dev.cueopen.app.data.MemoryItem
import java.text.Normalizer
import kotlin.math.sqrt

/** Seleção determinística de memórias por escopo, relevância e orçamento de caracteres. */
internal object MemoryRecallPolicy {
    fun select(
        memories: List<MemoryItem>,
        query: String,
        agentId: String?,
        k: Int = 8,
        maxChars: Int = 2_400,
    ): List<MemoryItem> {
        if (k <= 0 || maxChars <= 0) return emptyList()
        val queryWords = words(query)
        val pool = memories.filter { it.agentId == null || it.agentId == agentId }
        val ranked = pool.map { it to (score(queryWords, it.text, it.tags) + it.uses * 0.01) }
            .sortedByDescending { it.second }
            .filter { it.second > 0 }
            .map { it.first }
        // Memórias próprias recentes continuam recuperáveis mesmo sem correspondência lexical.
        val recentAgent = if (agentId == null) emptyList() else pool
            .filter { it.agentId == agentId }
            .sortedByDescending { it.createdAt }
            .take(3)
        val candidates = (ranked + recentAgent).distinctBy { it.id }.take(k)
        var used = 0
        return candidates.filter { item ->
            val cost = item.text.length + item.tags.sumOf(String::length)
            if (used + cost > maxChars) false else {
                used += cost
                true
            }
        }
    }

    private fun words(text: String): Set<String> = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .split(Regex("[^a-z0-9]+"))
        .filter { it.length > 2 }
        .toSet()

    private fun score(query: Set<String>, text: String, tags: List<String>): Double {
        if (query.isEmpty()) return 0.0
        val corpus = words(text) + tags.flatMap(::words)
        val hits = query.count { it in corpus }
        return hits / sqrt(corpus.size.coerceAtLeast(1).toDouble())
    }
}
