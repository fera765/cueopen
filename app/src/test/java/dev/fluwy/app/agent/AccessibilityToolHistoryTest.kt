package dev.fluwy.app.agent

import dev.fluwy.app.data.ChatItem
import dev.fluwy.app.data.LlmMessage
import dev.fluwy.app.data.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AccessibilityToolHistoryTest {
    @Test
    fun screenTreeIsRedactedForPersistenceButAvailableOnlyDuringTheActiveLoop() {
        val rawScreen = "snapshot=abc\n[1] Publicação: texto privado visível"
        val durable = AccessibilityToolHistory.durableResult("device_accessibility_read_screen", rawScreen)
        assertEquals(AccessibilityToolHistory.REDACTED_RESULT, durable)

        val persistedMessage = LlmMessage(
            role = "tool",
            content = durable,
            toolCallId = "call-1",
            name = "device_accessibility_read_screen",
        )
        val activeMessage = AccessibilityToolHistory.forActiveModel(
            persistedMessage,
            mapOf("call-1" to rawScreen),
        )
        assertEquals(rawScreen, activeMessage.content)
        assertEquals(AccessibilityToolHistory.REDACTED_RESULT, persistedMessage.content)
    }

    @Test
    fun oldPersistedScreenTreesAreSanitizedWhenTaskResumes() {
        val rawScreen = "snapshot=old\n[1] Texto de tela"
        val original = Session(
            llm = listOf(LlmMessage("tool", rawScreen, toolCallId = "call-old", name = "device_accessibility_read_screen")),
            items = listOf(ChatItem.Step(tool = "device_accessibility_read_screen", title = "Lendo controles", args = "", result = rawScreen)),
        )

        val sanitized = AccessibilityToolHistory.sanitizePersistedSession(original)
        assertEquals(AccessibilityToolHistory.REDACTED_RESULT, sanitized.llm.single().content)
        assertEquals(AccessibilityToolHistory.REDACTED_RESULT, (sanitized.items.single() as ChatItem.Step).result)
        assertFalse(sanitized.llm.single().content.orEmpty().contains("Texto de tela"))
    }

    @Test
    fun nonAccessibilityToolOutputRemainsUnchanged() {
        val message = LlmMessage("tool", "ordinary result", toolCallId = "call-2", name = "browser_read")
        assertEquals(message, AccessibilityToolHistory.forActiveModel(message, emptyMap()))
        assertEquals("ordinary result", AccessibilityToolHistory.durableResult("browser_read", "ordinary result"))
    }
}
