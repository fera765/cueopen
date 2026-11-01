package dev.cueopen.app.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * Lifecycle registry for agent tool calls. The executor owns the work; this registry records
 * identity, status, timestamps and bounded output so UI/model code never guesses completion.
 */
class ToolExecutionManager {
    enum class Status { QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED }

    data class Execution(
        val id: String,
        val sessionId: String,
        val tool: String,
        val arguments: String,
        val status: Status,
        val startedAt: Long,
        val updatedAt: Long,
        val output: String = "",
        val error: String? = null
    )

    private val lock = Any()
    private val records = LinkedHashMap<String, Execution>()
    private val _state = MutableStateFlow<List<Execution>>(emptyList())
    val state: StateFlow<List<Execution>> = _state

    fun begin(sessionId: String, tool: String, arguments: String): String {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val execution = Execution(id, sessionId, tool, arguments.take(1000), Status.RUNNING, now, now)
        synchronized(lock) {
            records[id] = execution
            publishLocked()
        }
        return id
    }

    fun finish(id: String, output: String) = update(id, Status.COMPLETED, output.takeLast(12_000), null)
    fun fail(id: String, message: String) = update(id, Status.FAILED, "", message.take(2000))
    fun cancel(id: String) {
        update(id, Status.CANCELLED, "", "Execution cancelled")
    }

    fun cancelSession(sessionId: String) {
        val ids = synchronized(lock) { records.values.filter { it.sessionId == sessionId && it.status in setOf(Status.QUEUED, Status.RUNNING) }.map { it.id } }
        ids.forEach(::cancel)
    }

    private fun update(id: String, status: Status, output: String, error: String?) {
        synchronized(lock) {
            val old = records[id] ?: return
            if (old.status !in setOf(Status.QUEUED, Status.RUNNING)) return
            records[id] = old.copy(status = status, updatedAt = System.currentTimeMillis(), output = output, error = error)
            publishLocked()
        }
    }

    private fun publishLocked() {
        // Keep recent terminal records for diagnostics without unbounded growth.
        while (records.size > 200) {
            val firstTerminal = records.entries.firstOrNull { it.value.status !in setOf(Status.QUEUED, Status.RUNNING) } ?: break
            records.remove(firstTerminal.key)
        }
        _state.value = records.values.toList()
    }
}
