package dev.fluwy.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRetentionTest {
    @Test fun `small sessions are untouched`() {
        val s = Session(items = List(10) { ChatItem.Assistant(text = "a$it") })
        assertSame(s, SessionRetention.trim(s))
    }

    @Test fun `old items are dropped but pending cards survive and old step results are shortened`() {
        val pendingCard = ChatItem.Approval(tool = "x", callKey = "k", category = "pay", summary = "comprar")
        val items = listOf<ChatItem>(pendingCard) + List(2_000) { ChatItem.Step(tool = "shell", title = "t$it", args = "", result = "x".repeat(2_000), status = StepStatus.OK) }
        val out = SessionRetention.trim(Session(items = items))
        assertEquals(SessionRetention.KEEP_ITEMS + 1, out.items.size)
        assertTrue(out.items.first() === pendingCard)
        assertEquals("t1999", (out.items.last() as ChatItem.Step).title)
        assertEquals(2_000, (out.items.last() as ChatItem.Step).result.length)
        assertTrue((out.items[1] as ChatItem.Step).result.length <= SessionRetention.OLD_STEP_RESULT + 1)
    }

    @Test fun `llm history is capped and starts at a user message`() {
        val llm = List(1_000) { i -> LlmMessage(if (i % 3 == 0) "user" else if (i % 3 == 1) "assistant" else "tool", "m$i") }
        val out = SessionRetention.trim(Session(llm = llm))
        assertTrue(out.llm.size <= SessionRetention.KEEP_LLM)
        assertEquals("user", out.llm.first().role)
        assertEquals("m999", out.llm.last().content)
    }
}
