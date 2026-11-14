package dev.cueopen.app.agent

/** Distingue a espera do coordenador "pelo time" (pode ser encerrada) do ciclo que o usuário pediu (deve ser respeitado). */
object SectionWaitPolicy {
    private val waitsForTeam = Regex("(?i)(aguard|esper|wait|at[eé] que|conclu|terminar|termine|entreg|finaliz|relat[oó]rio|retorno|resultado)")
    private val userInterval = Regex("(?i)(a cada|every)\\s*(\\d+|uma|um|meia)?\\s*(min|minuto|minutos|h\\b|hora|horas|hour|hours|minute|minutes|dia|dias|day)|de hora em hora|vez por (hora|dia)|todo dia|diariamente|hourly|daily")

    /** O motivo da espera fala em aguardar um agente, uma conclusão ou uma entrega. */
    fun waitsForTeam(reason: String): Boolean = waitsForTeam.containsMatchIn(reason)

    /** A mensagem do usuário pede uma repetição por intervalo ("a cada 30 min", "de hora em hora", "todo dia"). */
    fun userAskedInterval(message: String): Boolean = userInterval.containsMatchIn(message)
}
