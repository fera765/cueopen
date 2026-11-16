package dev.cueopen.app.agent

import dev.cueopen.app.data.LlmMessage
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
}
