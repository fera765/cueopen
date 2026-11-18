package dev.cueopen.app.agent

import dev.cueopen.app.data.ChatItem

/**
 * Várias mensagens do usuário em sequência = vários pedidos. Garante que o agente atenda cada um em separado
 * (nenhum some porque chegou outro) e confira todos antes de encerrar.
 */
object MultiRequest {
    const val PROMPT_LINE = "Várias mensagens seguidas do usuário são pedidos separados: atenda TODAS, uma a uma, sem abandonar as anteriores " +
        "(só deixe de fazer uma se outra mensagem cancelar ou corrigir aquela). Se forem independentes, faça uma de cada vez e, na resposta final, " +
        "confirme cada pedido em um item numerado (feito / não deu e por quê)."

    /** Junta mensagens que estavam na fila num único envio, numeradas para não se misturarem. */
    fun combine(texts: List<String>): String =
        if (texts.size <= 1) texts.firstOrNull().orEmpty()
        else "[O usuário enviou ${texts.size} mensagens seguidas. Trate cada uma como um pedido separado e atenda todas]\n\n" +
            texts.mapIndexed { i, t -> "Mensagem ${i + 1}:\n${t.trim()}" }.joinToString("\n\n")

    /**
     * Pedidos do usuário que esta rodada precisa atender: as mensagens desde a última resposta FINAL do agente
     * anterior ao início da rodada (inclui as que chegaram durante o trabalho).
     */
    fun pending(items: List<ChatItem>, itemsAtRunStart: Int): List<String> {
        val start = itemsAtRunStart.coerceIn(0, items.size)
        val lastFinal = items.subList(0, start).indexOfLast { it is ChatItem.Assistant && it.final }
        return items.drop(lastFinal + 1).filterIsInstance<ChatItem.User>().map { it.text.trim() }
            .filter { it.isNotBlank() && !it.startsWith("[") && !ContinuationPolicy.isContinueIntent(it) }.takeLast(8)
    }

    fun checkPrompt(reqs: List<String>): String =
        "[Sistema] Antes de encerrar, confira: o usuário fez ${reqs.size} pedidos nesta conversa desde a sua última entrega:\n" +
            reqs.mapIndexed { i, r -> "${i + 1}. ${r.replace('\n', ' ').take(180)}" }.joinToString("\n") +
            "\nSe algum ainda não foi feito, faça agora (chame as ferramentas). Se todos já foram, responda com o resultado final confirmando cada pedido pelo número. " +
            "Pedidos que uma mensagem posterior cancelou ou substituiu não precisam ser feitos — só diga isso."
}
