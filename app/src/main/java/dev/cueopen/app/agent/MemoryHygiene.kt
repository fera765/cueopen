package dev.cueopen.app.agent

import dev.cueopen.app.data.LlmMessage
import dev.cueopen.app.data.Session
import java.text.Normalizer

/** Ideias de contexto/memória trazidas do OpenMausBot, em funções puras e testáveis. */
object MemoryHygiene {
    private fun norm(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
    private val stop = setOf("para", "com", "que", "uma", "um", "dos", "das", "nos", "nas", "por", "mais", "como", "isso", "esse", "essa",
        "este", "esta", "voce", "sobre", "the", "and", "with", "from", "this", "that", "usuario", "user", "prefere", "gosta", "quer", "usa")
    fun contentWords(s: String): Set<String> = norm(s).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 3 && it !in stop }.toSet()

    private val urlRx = Regex("https?://[^\\s)\\]>\"']+")
    private val numRx = Regex("\\b\\d[\\d.,:/-]*\\d\\b|\\b\\d\\b")
    private val pathRx = Regex("(?:/[\\w.-]+){2,}|\\b[\\w-]+\\.(?:md|txt|csv|json|py|kt|pdf|xlsx|docx|html)\\b")

    /**
     * Checagem anti-alucinação da captura de memória (OpenMausBot): todo número, URL e caminho citado no fato precisa
     * aparecer no que o USUÁRIO escreveu, e o fato precisa ter pelo menos uma palavra de conteúdo dita por ele.
     */
    fun factBackedByUser(fact: String, userTexts: List<String>): Boolean {
        val user = userTexts.joinToString("\n")
        val userNorm = norm(user)
        val factNoUntil = fact.replace(untilRx, "")
        val literals = urlRx.findAll(factNoUntil).map { it.value.trimEnd('.', ',') } + pathRx.findAll(factNoUntil).map { it.value } + numRx.findAll(factNoUntil).map { it.value }
        if (literals.any { !user.contains(it) }) return false
        val words = contentWords(factNoUntil)
        return words.isEmpty() || words.any { userNorm.contains(it) }
    }

    /** Validade opcional da memória: "… · até 2026-12-31" (some depois dessa data). */
    val untilRx = Regex("\\s*·\\s*até\\s+(\\d{4}-\\d{2}-\\d{2})\\s*$")

    fun active(text: String, today: String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())): Boolean {
        val until = untilRx.find(text)?.groupValues?.get(1) ?: return true
        return until >= today
    }

    /**
     * Histerese da compactação (OpenMausBot "regrowth floor"): depois de compactar, só compacta de novo quando o contexto
     * passar do orçamento E crescer 25% sobre o tamanho logo após a última compactação — evita recompactar a cada passo.
     */
    fun shouldCompact(estimate: Int, budget: Int, floorAfterLast: Int?): Boolean =
        estimate >= maxOf(budget, ((floorAfterLast ?: 0) * 1.25).toInt())

    /** Resumo sem LLM quando o resumidor falha: trechos mais recentes citados literalmente dentro de um orçamento. */
    fun excerptSummary(head: List<LlmMessage>, budget: Int = 3_200): String {
        val picked = ArrayList<String>()
        var used = 0
        val candidates = head.filter { (it.role == "user" || it.role == "assistant") && !it.content.isNullOrBlank() }.asReversed()
        for (m in candidates) {
            val ex = (if (m.role == "user") "Usuário: " else "Agente: ") + m.content!!.trim().replace(Regex("\\s+"), " ").take(700)
            if (used + ex.length > budget) break
            picked.add(ex); used += ex.length
        }
        val omitted = candidates.size - picked.size
        return "Trechos literais mais recentes da parte antiga (o resumo automático falhou):\n" +
            picked.asReversed().joinToString("\n") { "> $it" } + if (omitted > 0) "\n[$omitted trecho(s) mais antigo(s) omitido(s)]" else ""
    }

    /**
     * Lembrança de conversas anteriores (recall do OpenMausBot): procura por palavras do pedido em títulos/resumos/respostas
     * de outras conversas. Pedido longo (≥5 palavras de conteúdo) exige ≥2 termos em comum, para não trazer ruído.
     */
    fun pastChats(sessions: List<Session>, currentId: String, query: String, k: Int = 2, maxChars: Int = 1_200): List<String> {
        val q = contentWords(query)
        if (q.isEmpty()) return emptyList()
        val need = if (q.size >= 5) 2 else 1
        val df = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault())
        return sessions.asSequence().filter { it.id != currentId && it.title != "Nova tarefa" }.mapNotNull { s ->
            val answer = s.items.lastOrNull { it is dev.cueopen.app.data.ChatItem.Assistant }?.let { (it as dev.cueopen.app.data.ChatItem.Assistant).text }.orEmpty()
            val doc = (s.title + "\n" + s.summary.take(1_500) + "\n" + answer.take(1_500))
            val w = contentWords(doc)
            val hits = q.count { it in w }
            if (hits < need) null else Triple(s, hits / Math.sqrt(w.size.coerceAtLeast(1).toDouble()), answer.ifBlank { s.summary })
        }.sortedByDescending { it.second }.take(k).map { (s, _, text) ->
            "[${df.format(java.util.Date(s.updatedAt))}] \"${s.title}\": " + text.replace(Regex("\\s+"), " ").take(maxChars / k)
        }.toList()
    }
}
