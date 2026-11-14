package dev.cueopen.app.agent

/**
 * Memória de lições (inspirada em Reflexion, Shinn et al., 2023): quando o coordenador pede para refazer ou bloqueia
 * uma entrega, o motivo vira uma lição curta guardada na memória do agente, que aparece no prompt das próximas tarefas.
 */
object AgentLessons {
    const val PREFIX = "Lição aprendida"
    const val MAX = 8

    fun lesson(task: String, note: String): String =
        "$PREFIX (tarefa \"${task.replace(Regex("\\s+"), " ").trim().take(80)}\"): ${note.replace(Regex("\\s+"), " ").trim().take(240)}"

    /** Acrescenta a lição sem repetir uma igual e mantendo só as [MAX] mais recentes; outros fatos da memória ficam intactos. */
    fun add(memory: List<String>, task: String, note: String): List<String> {
        val l = lesson(task, note)
        val others = memory.filterNot { it.startsWith(PREFIX) }
        val lessons = (memory.filter { it.startsWith(PREFIX) && it != l } + l).takeLast(MAX)
        return others + lessons
    }
}
