package dev.cueopen.app.llm

import org.junit.Assert.assertEquals
import org.junit.Test

class UsageLedgerTest {
    @Test fun `counts per model and agent and sums periods`() {
        val now = 1_763_000_000_000L
        val days = mutableMapOf<String, UsageLedger.Day>()
        UsageLedger.add(days, UsageLedger.day(now), "Pesquisador", "OpenRouter · x", 100, 10)
        UsageLedger.add(days, UsageLedger.day(now), "Pesquisador", "OpenRouter · x", 50, 5)
        UsageLedger.add(days, UsageLedger.day(now - 86_400_000L), "Redator", "Groq · y", 7, 1)
        val today = UsageLedger.total(days, 1, now)
        assertEquals(UsageLedger.Counts(2, 150, 15), today.models["OpenRouter · x"])
        assertEquals(null, today.agents["Redator"])
        assertEquals(1, UsageLedger.total(days, 7, now).agents["Redator"]!!.calls)
    }
}
