package dev.fluwy.app.agent

/**
 * Sinais de que um agente de Seção desviou ou travou (puros, testáveis). O coordenador recebe um
 * checkpoint imediato quando aparecem, além dos checkpoints periódicos.
 */
object MemberSupervision {
    /** Ação recente do agente: ferramenta, argumento resumido e se falhou. */
    data class Action(val tool: String, val args: String, val failed: Boolean)

    const val CHECKPOINT_EVERY_STEPS = 10
    const val SILENT_ROUNDS_BEFORE_NUDGE = 5

    fun riskSignal(recent: List<Action>): String? {
        val last = recent.takeLast(8)
        if (last.size < 3) return null
        val fails = last.takeLast(6).count { it.failed }
        if (fails >= 3) return "$fails das últimas ${minOf(6, last.size)} ações falharam"
        val repeated = last.groupBy { it.tool + "|" + it.args.take(80) }.maxByOrNull { it.value.size }
        if (repeated != null && repeated.value.size >= 3) return "repetiu ${repeated.value.size}× a mesma ação (${repeated.value.first().tool}: ${repeated.value.first().args.take(60)})"
        if (last.any { it.tool == "shell" && ShellCommandPolicy.isWholeSystemSearch(it.args) }) return "está vasculhando o sistema inteiro em vez do workspace"
        val reads = last.takeLast(6).count { it.tool == "file_read" || it.tool == "file_list" || it.tool == "browser_read" }
        if (reads >= 6) return "6 leituras seguidas sem produzir nada"
        return null
    }
}
