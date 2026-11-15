package dev.cueopen.app.device

import java.text.Normalizer

/** Acha um controle pelo texto/rótulo numa leitura de tela, sem mandar a árvore inteira ao modelo. */
object ScreenMatch {
    data class Hit(val snapshotId: String, val elementId: Int, val label: String, val clickable: Boolean)

    private val line = Regex("^\\[(\\d+)] ([^·]*)(?: · (.*))?$")
    private val flagWords = setOf("clickable", "editable", "scrollable", "password hidden")
    private fun norm(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").replace(Regex("\\s+"), " ").trim()

    fun snapshotId(screen: String): String? = Regex("snapshot_id=([A-Za-z0-9-]+)").find(screen)?.groupValues?.get(1)

    /** Todos os controles da leitura, como (id, rótulo, clicável). */
    fun elements(screen: String): List<Triple<Int, String, Boolean>> = screen.lineSequence().mapNotNull { l ->
        val m = line.find(l.trim()) ?: return@mapNotNull null
        val parts = m.groupValues[3].split(" · ").map { it.trim() }.filter { it.isNotEmpty() }
        val flags = parts.lastOrNull()?.split(", ")?.map { it.trim() }?.takeIf { f -> f.isNotEmpty() && f.all { it in flagWords } }.orEmpty()
        val label = (if (flags.isEmpty()) parts else parts.dropLast(1)).joinToString(" · ")
        Triple(m.groupValues[1].toInt(), label, "clickable" in flags)
    }.toList()

    /**
     * Melhor controle para [query]: rótulo idêntico antes de rótulo que contém o texto; clicável antes de não clicável;
     * [occurrence] escolhe a n-ésima correspondência (1 = primeira) quando há várias.
     */
    fun find(screen: String, query: String, occurrence: Int = 1): Hit? {
        val snap = snapshotId(screen) ?: return null
        val q = norm(query)
        if (q.isEmpty()) return null
        val ranked = elements(screen).mapNotNull { (id, label, clickable) ->
            val l = norm(label)
            val score = when { l == q -> 3; l.split(" · ").any { it == q } -> 2; l.contains(q) -> 1; else -> 0 }
            if (score == 0) null else Triple(Hit(snap, id, label, clickable), score, clickable)
        }.sortedWith(compareByDescending<Triple<Hit, Int, Boolean>> { it.second }.thenByDescending { it.third }.thenBy { it.first.elementId })
        return ranked.getOrNull((occurrence - 1).coerceAtLeast(0))?.first
    }
}
