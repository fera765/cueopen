package dev.fluwy.app.agent

/** OpenBot 0.71: compactação que não traz o contexto de volta para baixo do orçamento não deve se repetir sem fim. */
object CompactionProgress {
    const val MAX_USELESS = 3

    /** Inútil: continua acima de 90% do orçamento OU encolheu menos de 10%. */
    fun useless(before: Int, after: Int, budget: Int): Boolean =
        after >= budget * 9 / 10 || (before > 0 && after > before * 9 / 10)

    fun message(after: Int, budget: Int): String =
        "Parei: o contexto continua cheio (~$after de $budget tokens) mesmo depois de compactar $MAX_USELESS vezes. " +
            "Algo grande está ocupando o espaço (um arquivo ou anexo enorme, instruções longas do agente ou muitos conectores ligados). " +
            "Comece um chat novo, encurte as instruções do agente ou aumente o orçamento de contexto em Configurações e envie continuar."
}
