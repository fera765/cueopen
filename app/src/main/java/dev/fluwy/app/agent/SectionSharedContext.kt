package dev.fluwy.app.agent

/**
 * Contexto compartilhado de uma Seção: tudo o que o usuário informou (no chat de origem ou no chat do
 * coordenador), respostas a perguntas, dados seguros disponíveis e entregas do time. Vai no prompt de TODOS
 * os agentes da seção a cada passo, então uma informação dada ao coordenador vale na hora para qualquer agente.
 * Puro e testável: o AgentRunner só coleta os dados.
 */
object SectionSharedContext {
    data class Delivery(val agent: String, val task: String, val result: String)

    const val MAX_CHARS = 5_000
    private const val PER_MESSAGE = 600
    private const val MAX_MESSAGES = 14

    /** Mensagens internas do app (relatórios, checkpoints, retomadas) não são informação do usuário. */
    fun isUserInfo(text: String): Boolean {
        val t = text.trim()
        return t.isNotEmpty() && !t.startsWith("[")
    }

    fun render(
        originalRequest: String,
        userMessages: List<String>,
        answers: List<Pair<String, String>>,
        secretNames: List<String>,
        deliveries: List<Delivery>,
    ): String {
        val msgs = userMessages.filter(::isUserInfo).map { it.trim() }.distinct()
            .filter { it != originalRequest.trim() }.takeLast(MAX_MESSAGES)
        if (originalRequest.isBlank() && msgs.isEmpty() && answers.isEmpty() && secretNames.isEmpty() && deliveries.isEmpty()) return ""
        val out = buildString {
            appendLine("## Contexto compartilhado da seção (vale para todos os agentes; use sem pedir de novo)")
            if (originalRequest.isNotBlank()) appendLine("- Pedido original: " + clip(originalRequest, 900))
            if (msgs.isNotEmpty()) {
                appendLine("- Informações do usuário (em ordem):")
                msgs.forEach { appendLine("  • " + clip(it, PER_MESSAGE)) }
            }
            answers.takeLast(10).forEach { (q, a) -> appendLine("- Resposta do usuário — " + clip(q, 160) + " → " + clip(a, 300)) }
            if (secretNames.isNotEmpty()) appendLine("- Dados seguros da seção (e-mail, senha, chaves…): " + secretNames.joinToString { "{{secret:$it}}" } +
                ". Use o marcador nos argumentos (browser_type, shell, http…) e o app o troca pelo valor real. Esses dados já foram informados; não peça de novo ao usuário.")
            deliveries.takeLast(8).forEach { appendLine("- Entrega de ${it.agent} (${clip(it.task, 120)}): " + clip(it.result, 400)) }
            appendLine("- Se a informação de que você precisa está aqui, use-a diretamente. Se não está, diga ao coordenador exatamente o que falta.")
        }
        return if (out.length <= MAX_CHARS) out.trimEnd() else out.take(MAX_CHARS).trimEnd() + "\n…"
    }

    private fun clip(s: String, n: Int): String {
        val one = s.replace(Regex("\\s+"), " ").trim()
        return if (one.length <= n) one else one.take(n) + "…"
    }
}
