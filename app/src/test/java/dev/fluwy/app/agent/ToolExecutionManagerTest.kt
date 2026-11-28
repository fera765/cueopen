package dev.fluwy.app.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolExecutionManagerTest {
    @Test
    fun lateCompletionCannotOverwriteWatchdogCancellation() {
        val manager = ToolExecutionManager()
        val id = manager.begin("session", "market_discover", "{}")

        manager.cancelSession("session")
        manager.finish(id, "late response")

        val execution = manager.state.value.single()
        assertEquals(ToolExecutionManager.Status.CANCELLED, execution.status)
        assertEquals("Execution cancelled", execution.error)
    }
}
