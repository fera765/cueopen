package dev.cueopen.app.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantRecognitionGateTest {
    @Test
    fun `only the first final callback resolves one recognition`() {
        val gate = AssistantRecognitionGate()

        assertFalse(gate.isResolved)
        assertTrue(gate.resolve())
        assertTrue(gate.isResolved)
        assertFalse(gate.resolve())
    }

    @Test
    fun `late error cannot override an already finalized result`() {
        val gate = AssistantRecognitionGate()

        assertTrue(gate.resolve())
        assertFalse(gate.resolve())
    }
}
