package dev.fluwy.app.agent

import dev.fluwy.app.data.AgentProfile

/** Regras (sem Android) da tela de configuração do agent: validação e resumos das seções recolhidas. */
object AgentConfigRules {
    const val MAX_NAME = 40
    val budgets = listOf(100_000L to "100k", 300_000L to "300k", 1_000_000L to "1M", 3_000_000L to "3M", 20_000_000L to "20M")

    /** Erros que impedem salvar (vazio = ok). */
    fun validate(a: AgentProfile, otherNames: Collection<String> = emptyList()): List<String> = buildList {
        val n = a.name.trim()
        if (n.isEmpty()) add("Dê um nome ao agent.")
        if (n.length > MAX_NAME) add("Nome com no máximo $MAX_NAME caracteres.")
        if (n.isNotEmpty() && otherNames.any { it.trim().equals(n, true) }) add("Já existe um agent com esse nome.")
        if (a.activeStartHour !in 0..23 || a.activeEndHour !in 1..24) add("Janela de horário inválida.")
        if (a.activeStartHour == a.activeEndHour) add("A janela de horário precisa ter pelo menos 1 hora.")
        if (a.dailyTokenBudget <= 0) add("Orçamento diário precisa ser maior que zero.")
    }

    fun budgetLabel(v: Long) = budgets.firstOrNull { it.first == v }?.second ?: when {
        v >= 1_000_000 -> "${v / 1_000_000}M"
        v >= 1_000 -> "${v / 1_000}k"
        else -> v.toString()
    }

    fun identity(a: AgentProfile) = a.name.trim().ifEmpty { "Sem nome" }
    fun behavior(a: AgentProfile) = a.instructions.trim().lineSequence().firstOrNull()?.take(60)?.ifBlank { null } ?: "Sem instruções (usa o comportamento padrão)"
    fun model(a: AgentProfile) = a.model?.ifBlank { null } ?: "Padrão do app"
    fun tools(a: AgentProfile): String {
        val on = buildList {
            if (a.useTerminal) add("Terminal")
            if (a.useBrowser) add("Browser")
            if (a.useConnectorHub) add("Hub")
            if (a.uiConnectorIds.isNotEmpty()) add("${a.uiConnectorIds.size} UI conector(es)")
            if (a.deviceCaps.isNotEmpty()) add("${a.deviceCaps.size} acesso(s) Android")
        }
        val confirm = if (a.confirmMode == "auto") "sem confirmações" else "confirma ${a.confirmCategories.size} tipo(s)"
        return (on.ifEmpty { listOf("Nenhuma ferramenta") } + confirm).joinToString(" · ")
    }
    fun knowledge(a: AgentProfile) = "${a.skillIds.size} skill(s) em destaque · ${a.memory.size} memória(s)"
    fun autonomy(a: AgentProfile) = if (!a.autonomous) "Desligada" else
        "Ligada · ${hours(a)} · ${budgetLabel(a.dailyTokenBudget)} tokens/dia"
    fun hours(a: AgentProfile) = if (a.activeStartHour == 0 && a.activeEndHour == 24) "24h" else "%02dh–%02dh".format(a.activeStartHour, a.activeEndHour)
}
