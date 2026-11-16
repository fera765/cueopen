package dev.cueopen.app.agent

import dev.cueopen.app.data.LlmMessage

/**
 * Poda sem LLM: encurta resultados ANTIGOS de ferramentas (antes do ponto de corte), que costumam ser a maior parte do
 * contexto. Se só isso já cabe no orçamento, a compactação com resumo (uma chamada ao modelo) nem acontece.
 */
object ContextPruner {
    const val MIN_LEN = 900
    const val MARK = "[… resultado antigo encurtado para economizar contexto; se precisar dele inteiro, refaça a ferramenta …]"

    fun prune(llm: List<LlmMessage>, cut: Int): List<LlmMessage> {
        var changed = false
        val out = llm.mapIndexed { i, m ->
            val c = m.content
            if (i < cut && m.role == "tool" && c != null && c.length > MIN_LEN && !c.contains(MARK)) {
                changed = true
                m.copy(content = c.take(450) + "\n" + MARK + "\n" + c.takeLast(200))
            } else m
        }
        return if (changed) out else llm
    }
}
