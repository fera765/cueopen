package dev.fluwy.app.agent

import dev.fluwy.app.data.MemoryItem

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
        val queryWords = MemoryCurator.terms(query)
        val pool = memories.filter { it.agentId == null || it.agentId == agentId }
        val ranked = pool.map { it to MemoryCurator.memoryScore(queryWords, it) }
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
}
