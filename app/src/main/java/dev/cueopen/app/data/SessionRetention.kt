package dev.cueopen.app.data

/**
 * Limite de crescimento de um chat que roda 24 h. Sem isso, a lista de itens e o histórico do modelo cresciam sem fim,
 * o estado salvo passava de dezenas de MB e o app caía por falta de memória ao gravar.
 * - Itens da tela: guarda os mais recentes; cards que ainda esperam o usuário nunca são descartados.
 * - Passos antigos: o resultado longo é encurtado (o recente fica inteiro).
 * - Histórico do modelo: teto de segurança (a compactação normal já o mantém bem abaixo disso).
 */
object SessionRetention {
    const val MAX_ITEMS = 1_500
    const val KEEP_ITEMS = 1_200
    const val FULL_RECENT_STEPS = 300
    const val OLD_STEP_RESULT = 600
    const val MAX_LLM = 900
    const val KEEP_LLM = 700

    private fun pending(i: ChatItem): Boolean = when (i) {
        is ChatItem.Question -> i.state == "pending"
        is ChatItem.SecureForm -> i.state == "pending"
        is ChatItem.Approval -> i.state == "pending"
        is ChatItem.SectionSetup -> i.state == "pending"
        is ChatItem.LlmRecovery -> i.state == "waiting"
        is ChatItem.Step -> i.status == StepStatus.RUNNING
        else -> false
    }

    fun needsTrim(s: Session): Boolean = s.items.size > MAX_ITEMS || s.llm.size > MAX_LLM

    fun trim(s: Session): Session {
        if (!needsTrim(s)) return s
        var items = s.items
        if (items.size > MAX_ITEMS) {
            val cut = items.size - KEEP_ITEMS
            val old = items.take(cut).filter { pending(it) }
            items = old + items.drop(cut)
            val fullFrom = (items.size - FULL_RECENT_STEPS).coerceAtLeast(0)
            items = items.mapIndexed { idx, it ->
                if (idx < fullFrom && it is ChatItem.Step && it.result.length > OLD_STEP_RESULT) it.copy(result = it.result.take(OLD_STEP_RESULT) + "…") else it
            }
        }
        // Histórico do modelo: começa num pedido do usuário para não deixar respostas de ferramenta órfãs no início.
        var llm = s.llm
        if (llm.size > MAX_LLM) {
            var start = llm.size - KEEP_LLM
            while (start < llm.size && llm[start].role != "user") start++
            llm = if (start < llm.size) llm.drop(start) else llm.takeLast(KEEP_LLM)
        }
        return s.copy(items = items, llm = llm)
    }
}
