package dev.fluwy.app.agent

import dev.fluwy.app.data.SectionTask
import dev.fluwy.app.data.TaskStatus

/**
 * Máquina de estados do plano de uma Seção (puro, testável na JVM).
 *
 * Tarefa: PENDING → RUNNING → REVIEW → DONE (aprovada) | PENDING (refazer, até MAX_ATTEMPTS) | FAILED | BLOCKED.
 * Seção (derivada, nunca "chutada"): IDLE, READY, RUNNING, WAITING_FOR_AGENT, VALIDATING, BLOCKED, COMPLETED, FAILED.
 * Ausência de atividade NÃO é conclusão: só COMPLETED quando toda tarefa está DONE/CANCELLED.
 */
object SectionPlanEngine {
    enum class State { IDLE, READY, RUNNING, WAITING_FOR_AGENT, VALIDATING, BLOCKED, COMPLETED, FAILED }

    const val MAX_ATTEMPTS = 3
    const val MAX_TASKS = 30

    data class Draft(val id: String, val agent: String, val task: String, val dependsOn: List<String>)

    /** Valida o rascunho: ids únicos, dependências existentes, agentes do time e SEM ciclos. Retorna o erro ou null. */
    fun validate(drafts: List<Draft>, team: Set<String>): String? {
        if (drafts.isEmpty()) return "o plano precisa de ao menos 1 tarefa"
        if (drafts.size > MAX_TASKS) return "no máximo $MAX_TASKS tarefas"
        val ids = drafts.map { it.id }
        ids.groupBy { it }.filter { it.value.size > 1 }.keys.firstOrNull()?.let { return "id repetido: $it" }
        drafts.firstOrNull { it.id.isBlank() || it.task.isBlank() }?.let { return "toda tarefa precisa de id e descrição" }
        drafts.firstOrNull { d -> team.none { it.equals(d.agent, true) } }?.let { return "agente \"${it.agent}\" não existe no time (${team.joinToString()})" }
        drafts.forEach { d -> d.dependsOn.firstOrNull { it !in ids }?.let { return "a tarefa ${d.id} depende de \"$it\", que não existe" } }
        drafts.firstOrNull { it.id in it.dependsOn }?.let { return "a tarefa ${it.id} depende dela mesma" }
        cycle(drafts.associate { it.id to it.dependsOn })?.let { return "dependência circular: " + it.joinToString(" → ") }
        return null
    }

    /** Ciclo de dependências (DFS), ou null. */
    fun cycle(deps: Map<String, List<String>>): List<String>? {
        val state = HashMap<String, Int>()  // 1 = visitando, 2 = pronto
        val stack = ArrayList<String>()
        fun dfs(n: String): List<String>? {
            state[n] = 1; stack.add(n)
            for (m in deps[n].orEmpty()) {
                if (state[m] == 1) return stack.subList(stack.indexOf(m), stack.size) + m
                if (state[m] == null) dfs(m)?.let { return it }
            }
            stack.removeAt(stack.size - 1); state[n] = 2
            return null
        }
        for (n in deps.keys) if (state[n] == null) dfs(n)?.let { return it }
        return null
    }

    /** Tarefas que podem começar agora: pendentes com todas as dependências aprovadas. */
    fun ready(plan: List<SectionTask>): List<SectionTask> {
        val done = plan.filter { it.status == TaskStatus.DONE }.map { it.id }.toSet()
        return plan.filter { it.status == TaskStatus.PENDING && it.dependsOn.all { d -> d in done } }
    }

    /** Pendentes que nunca vão poder começar (dependência falhou/bloqueou/cancelou). */
    fun starved(plan: List<SectionTask>): List<SectionTask> {
        val dead = plan.filter { it.status in setOf(TaskStatus.FAILED, TaskStatus.BLOCKED, TaskStatus.CANCELLED) }.map { it.id }.toSet()
        fun deadDeep(t: SectionTask, seen: Set<String> = emptySet()): Boolean = t.dependsOn.any { d ->
            d in dead || (d !in seen && plan.firstOrNull { it.id == d }?.let { it.status == TaskStatus.PENDING && deadDeep(it, seen + t.id) } == true)
        }
        return plan.filter { it.status == TaskStatus.PENDING && deadDeep(it) }
    }

    fun state(plan: List<SectionTask>): State = when {
        plan.isEmpty() -> State.IDLE
        plan.all { it.status == TaskStatus.DONE || it.status == TaskStatus.CANCELLED } -> State.COMPLETED
        plan.any { it.status == TaskStatus.REVIEW } -> State.VALIDATING
        plan.any { it.status == TaskStatus.RUNNING } -> State.WAITING_FOR_AGENT
        ready(plan).isNotEmpty() -> State.READY
        plan.any { it.status == TaskStatus.FAILED } -> State.FAILED
        else -> State.BLOCKED
    }

    fun update(plan: List<SectionTask>, id: String, fn: (SectionTask) -> SectionTask) = plan.map { if (it.id == id) fn(it) else it }

    fun started(plan: List<SectionTask>, id: String, now: Long) =
        update(plan, id) { it.copy(status = TaskStatus.RUNNING, startedAt = now, endedAt = null) }

    /** O agente terminou a execução. Sucesso vai para validação; erro conta tentativa. */
    fun finished(plan: List<SectionTask>, id: String, ok: Boolean, result: String, now: Long) = update(plan, id) {
        if (it.status != TaskStatus.RUNNING) it
        else if (ok) it.copy(status = TaskStatus.REVIEW, result = result.take(2_000), endedAt = now)
        else {
            val attempts = it.attempts + 1
            it.copy(status = if (attempts >= MAX_ATTEMPTS) TaskStatus.FAILED else TaskStatus.PENDING, attempts = attempts,
                result = result.take(2_000), note = "falhou (tentativa $attempts/$MAX_ATTEMPTS)", endedAt = now)
        }
    }

    enum class Verdict { APPROVE, REDO, BLOCK, CANCEL }

    /** Decisão do coordenador. REDO volta a pendente com a orientação (ou FAILED após MAX_ATTEMPTS). */
    fun review(plan: List<SectionTask>, id: String, verdict: Verdict, note: String): Pair<List<SectionTask>, String?> {
        val t = plan.firstOrNull { it.id == id } ?: return plan to "tarefa \"$id\" não existe no plano"
        if (verdict == Verdict.APPROVE && t.status != TaskStatus.REVIEW && t.status != TaskStatus.DONE)
            return plan to "a tarefa $id está ${t.status}; só dá para aprovar depois que o agente entregar (REVIEW)"
        val next = when (verdict) {
            Verdict.APPROVE -> t.copy(status = TaskStatus.DONE, note = note.take(300))
            Verdict.BLOCK -> t.copy(status = TaskStatus.BLOCKED, note = note.take(300))
            Verdict.CANCEL -> t.copy(status = TaskStatus.CANCELLED, note = note.take(300))
            Verdict.REDO -> {
                val attempts = t.attempts + 1
                if (attempts >= MAX_ATTEMPTS) t.copy(status = TaskStatus.FAILED, attempts = attempts, note = "refeita $attempts× sem aprovação: ${note.take(200)}")
                else t.copy(status = TaskStatus.PENDING, attempts = attempts, note = note.take(300))
            }
        }
        return update(plan, id) { next } to null
    }

    /** Resultados aprovados das dependências: vão junto com a tarefa seguinte (handoff entre agentes). */
    fun handoff(plan: List<SectionTask>, task: SectionTask): String {
        val deps = task.dependsOn.mapNotNull { d -> plan.firstOrNull { it.id == d } }.filter { it.status == TaskStatus.DONE }
        if (deps.isEmpty()) return ""
        return "Entregas aprovadas das etapas anteriores (use como insumo; não refaça):\n" +
            deps.joinToString("\n") { "- [${it.id} · ${it.agentName}] " + it.result.replace(Regex("\\s+"), " ").take(700) }
    }

    /** Assinatura do progresso: muda sempre que algum estado/tentativa muda (para o vigia detectar estagnação). */
    fun signature(plan: List<SectionTask>): String = plan.joinToString("|") { "${it.id}:${it.status}:${it.attempts}" }

    fun icon(s: TaskStatus) = when (s) {
        TaskStatus.PENDING -> "○"; TaskStatus.RUNNING -> "▶"; TaskStatus.REVIEW -> "⏳"; TaskStatus.DONE -> "✓"
        TaskStatus.FAILED -> "✕"; TaskStatus.BLOCKED -> "⛔"; TaskStatus.CANCELLED -> "–"
    }

    fun label(s: State) = when (s) {
        State.IDLE -> "sem plano"; State.READY -> "pronto para iniciar"; State.RUNNING -> "executando"
        State.WAITING_FOR_AGENT -> "agentes trabalhando"; State.VALIDATING -> "validando entregas"
        State.BLOCKED -> "bloqueado"; State.COMPLETED -> "concluído"; State.FAILED -> "com falha"
    }

    fun describe(plan: List<SectionTask>): String = buildString {
        appendLine("Estado do plano: ${label(state(plan))}")
        plan.forEach { t ->
            append("${icon(t.status)} ${t.id} [${t.status}] ${t.agentName}: ${t.task.lineSequence().first().take(100)}")
            if (t.dependsOn.isNotEmpty()) append(" (depende de ${t.dependsOn.joinToString()})")
            if (t.attempts > 0) append(" · tentativas ${t.attempts}")
            if (t.note.isNotBlank()) append(" · ${t.note.take(120)}")
            appendLine()
        }
    }.trimEnd()
}
