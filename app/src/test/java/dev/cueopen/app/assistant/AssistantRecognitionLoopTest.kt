package dev.cueopen.app.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantRecognitionLoopTest {
    @Test
    fun `onEndOfSpeech aguarda callback terminal antes de permitir nova escuta`() {
        val loop = AssistantRecognitionLoop()

        assertTrue(loop.beginAttempt())
        loop.onEndOfSpeech()
        assertTrue(loop.isActive)
        assertFalse(loop.beginAttempt())

        assertTrue(loop.onTerminalCallback()) // onResults ou onError
        assertFalse(loop.isActive)
        assertTrue(loop.beginAttempt())
    }

    @Test
    fun `callback terminal so libera uma tentativa e cancelamento limpa estado`() {
        val loop = AssistantRecognitionLoop()
        assertTrue(loop.beginAttempt())
        assertTrue(loop.onTerminalCallback())
        assertFalse(loop.onTerminalCallback())

        assertTrue(loop.beginAttempt())
        loop.cancel()
        assertFalse(loop.isActive)
        assertTrue(loop.beginAttempt())
    }
}
