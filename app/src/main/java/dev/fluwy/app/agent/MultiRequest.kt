package dev.fluwy.app.agent

import dev.fluwy.app.data.ChatItem

/**
 * Várias mensagens do usuário em sequência = vários pedidos. Garante que o agente atenda cada um em separado
 * (nenhum some porque chegou outro) e confira todos antes de encerrar.
 */
object MultiRequest {
    val PROMPT_LINE = dev.fluwy.app.prompts.Prompts.render("multi_request.5", mapOf<String, Any?>("lb" to "{{")) +
        "(só deixe de fazer uma se outra mensagem cancelar ou corrigir aquela). Se forem independentes, faça uma de cada vez e, na resposta final, " +
        "confirme cada pedido em um item numerado (feito / não deu e por quê)."

    /** Junta mensagens que estavam na fila num único envio, numeradas para não se misturarem. */
    fun combine(texts: List<String>): String =
        if (texts.size <= 1) texts.firstOrNull().orEmpty()
        else dev.fluwy.app.prompts.Prompts.render("multi_request.1", mapOf<String, Any?>("texts" to (texts.size), "lb" to "{{")) +
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
        dev.fluwy.app.prompts.Prompts.render("multi_request.2", mapOf<String, Any?>("reqs" to (reqs.size), "lb" to "{{")) +
            reqs.mapIndexed { i, r -> "${i + 1}. ${r.replace('\n', ' ').take(180)}" }.joinToString("\n") +
            dev.fluwy.app.prompts.Prompts.render("multi_request.3", mapOf<String, Any?>("lb" to "{{")) +
            dev.fluwy.app.prompts.Prompts.render("multi_request.4", mapOf<String, Any?>("lb" to "{{"))
}
