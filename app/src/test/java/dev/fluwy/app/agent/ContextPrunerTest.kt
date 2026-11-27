package dev.fluwy.app.agent

import dev.fluwy.app.data.LlmMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextPrunerTest {
    @Test fun `only old long tool results are shortened once`() {
        val big = "x".repeat(5_000)
        val llm = listOf(LlmMessage("user", "pedido"), LlmMessage("tool", big), LlmMessage("assistant", big), LlmMessage("tool", big))
        val p = ContextPruner.prune(llm, cut = 3)
        assertTrue(p[1].content!!.length < 800); assertTrue(p[1].content!!.contains(ContextPruner.MARK))
        assertEquals(big, p[2].content); assertEquals(big, p[3].content)
        assertSame(p, ContextPruner.prune(p, cut = 3))
    }

    @Test fun `clear old tool results keeps the latest ones and needs a minimum gain`() {
        val big = "y".repeat(4_000)
        val llm = (1..8).flatMap { listOf(LlmMessage("assistant", "chamada $it"), LlmMessage("tool", "$it:$big")) }
        val c = ContextPruner.clearOld(llm, keepLast = 4, clearAtLeastChars = 8_000)
        val tools = c.filter { it.role == "tool" }
        assertTrue(tools.take(4).all { it.content!!.startsWith(ContextPruner.CLEARED) })
        assertTrue(tools.takeLast(4).none { it.content!!.startsWith(ContextPruner.CLEARED) })
        assertSame(llm, ContextPruner.clearOld(llm, keepLast = 4, clearAtLeastChars = 1_000_000))
    }
}
