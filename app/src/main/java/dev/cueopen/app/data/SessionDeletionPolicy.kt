package dev.cueopen.app.data

/** Resultado determinístico da exclusão de um chat e de todo o trabalho explicitamente ligado a ele. */
internal data class SessionDeletionPlan(
    val state: AppState,
    val removedSessionIds: Set<String>,
    val cancelledScheduledTaskIds: Set<String>,
    val cancelledHeartbeatAgentIds: Set<String>,
)

/**
 * Calcula uma cascata limitada por IDs explícitos: chat de origem, tarefa agendada e sessões
 * de execução dessa tarefa. Não infere propriedade apenas pelo Agent, para preservar outros chats.
 */
internal fun AppState.planSessionDeletion(sessionId: String): SessionDeletionPlan {
    val removedSessions = linkedSetOf(sessionId)
    val cancelledTasks = linkedSetOf<String>()
    sessions.firstOrNull { it.id == sessionId }?.scheduledTaskId?.let(cancelledTasks::add)

    var changed: Boolean
    do {
        changed = false
        scheduled.asSequence()
            .filter { it.sourceSessionId in removedSessions || it.lastSessionId in removedSessions }
            .forEach { if (cancelledTasks.add(it.id)) changed = true }
        sessions.asSequence()
            .filter { it.scheduledTaskId in cancelledTasks }
            .forEach { if (removedSessions.add(it.id)) changed = true }
    } while (changed)

    val heartbeatAgents = agents.asSequence()
        .filter { it.homeSessionId in removedSessions }
        .mapTo(linkedSetOf()) { it.id }
    val next = copy(
        sessions = sessions.filterNot { it.id in removedSessions },
        scheduled = scheduled.filterNot { it.id in cancelledTasks },
        agents = agents.map { agent ->
            if (agent.homeSessionId in removedSessions) agent.copy(
                autonomous = false,
                homeSessionId = null,
                lastBeatAt = null,
            ) else agent
        },
    )
    return SessionDeletionPlan(next, removedSessions, cancelledTasks, heartbeatAgents)
}
