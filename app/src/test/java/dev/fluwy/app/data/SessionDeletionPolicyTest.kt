package dev.fluwy.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionDeletionPolicyTest {
    @Test
    fun deletingSourceChatRemovesItsScheduleAndRunsButPreservesAnotherChatsWork() {
        val source = Session(id = "chat-a", title = "A", agentId = "agent-a")
        val other = Session(id = "chat-b", title = "B", agentId = "agent-a")
        val runA = Session(id = "run-a", title = "A run", agentId = "agent-a", scheduledTaskId = "task-a")
        val runB = Session(id = "run-b", title = "B run", agentId = "agent-a", scheduledTaskId = "task-b")
        val taskA = ScheduledTask(id = "task-a", title = "A task", prompt = "A", agentId = "agent-a", sourceSessionId = source.id, lastSessionId = runA.id)
        val taskB = ScheduledTask(id = "task-b", title = "B task", prompt = "B", agentId = "agent-a", sourceSessionId = other.id, lastSessionId = runB.id)
        val agent = AgentProfile(id = "agent-a", name = "Agent", autonomous = true, homeSessionId = source.id)

        val result = AppState(
            sessions = listOf(source, other, runA, runB),
            agents = listOf(agent),
            scheduled = listOf(taskA, taskB),
        ).planSessionDeletion(source.id)

        assertEquals(setOf("chat-a", "run-a"), result.removedSessionIds)
        assertEquals(setOf("task-a"), result.cancelledScheduledTaskIds)
        assertEquals(listOf(other, runB), result.state.sessions)
        assertEquals(listOf(taskB), result.state.scheduled)
        assertFalse(result.state.agents.single().autonomous)
        assertNull(result.state.agents.single().homeSessionId)
        assertEquals(setOf("agent-a"), result.cancelledHeartbeatAgentIds)
    }

    @Test
    fun deletingScheduledRunCancelsOnlyItsRecurringTaskIncludingLegacyLastSessionLink() {
        val runA = Session(id = "run-a", title = "A run", scheduledTaskId = "task-a")
        val runB = Session(id = "run-b", title = "B run", scheduledTaskId = "task-b")
        val taskA = ScheduledTask(id = "task-a", title = "A task", prompt = "A", lastSessionId = runA.id)
        val taskB = ScheduledTask(id = "task-b", title = "B task", prompt = "B", lastSessionId = runB.id)

        val result = AppState(sessions = listOf(runA, runB), scheduled = listOf(taskA, taskB))
            .planSessionDeletion(runA.id)

        assertEquals(setOf("run-a"), result.removedSessionIds)
        assertEquals(setOf("task-a"), result.cancelledScheduledTaskIds)
        assertEquals(listOf(runB), result.state.sessions)
        assertEquals(listOf(taskB), result.state.scheduled)
        assertTrue(result.cancelledHeartbeatAgentIds.isEmpty())
    }

    @Test
    fun deletingMissingSourceStillRemovesItsOrphanedScheduledWork() {
        val task = ScheduledTask(id = "orphan", title = "Orphan", prompt = "run", sourceSessionId = "deleted-chat")
        val result = AppState(scheduled = listOf(task)).planSessionDeletion("deleted-chat")

        assertTrue(result.state.scheduled.isEmpty())
        assertEquals(setOf("orphan"), result.cancelledScheduledTaskIds)
        assertEquals(setOf("deleted-chat"), result.removedSessionIds)
    }
}
