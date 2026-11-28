package dev.fluwy.app.agent

import dev.fluwy.app.data.LlmMessage

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

    const val CLEARED = "[resultado antigo removido do contexto para economizar tokens; refaça a ferramenta se precisar dele]"

    /**
     * Limpeza de resultados de ferramentas (context editing da Anthropic, clear_tool_uses): mantém inteiros os
     * [keepLast] resultados mais recentes e troca os mais antigos por um aviso curto. Só aplica se liberar pelo menos
     * [clearAtLeastChars] (limpar pouco não compensa). Devolve a lista original se não valer a pena.
     */
    fun clearOld(llm: List<LlmMessage>, keepLast: Int = 4, clearAtLeastChars: Int = 8_000): List<LlmMessage> {
        val toolIdx = llm.indices.filter { llm[it].role == "tool" }
        val old = toolIdx.dropLast(keepLast).toSet()
        var freed = 0
        val out = llm.mapIndexed { i, m ->
            val c = m.content
            if (i in old && c != null && c.length > 400 && !c.startsWith(CLEARED)) {
                freed += c.length - 260
                m.copy(content = CLEARED + "\n" + c.take(200))
            } else m
        }
        return if (freed >= clearAtLeastChars) out else llm
    }
}
