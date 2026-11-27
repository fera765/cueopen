package dev.fluwy.app.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanUsageTest {
    @Test fun `go usage meters are parsed`() {
        val m = PlanUsage.parseGo("""{"usage":{"rolling":{"status":"ok","percent":12.5,"resetsAt":"2026-11-16T15:00:00Z"},"weekly":{"status":"rate-limited","percent":100,"resetsAt":"x"},"monthly":{"status":"ok","percent":40,"resetsAt":"y"}}}""")
        assertEquals(3, m.size); assertEquals(12.5, m[0].percent, 0.0); assertTrue(m[1].limited); assertEquals("Mês", m[2].label)
    }

    @Test fun `openrouter key usage lines and org id capture`() {
        val l = PlanUsage.parseOpenRouter("""{"data":{"usage_daily":0.5,"usage_weekly":1,"usage_monthly":2,"limit":null,"is_free_tier":true,"free_model_daily_requests":{"used":12,"limit":50,"remaining":38}}}""")
        assertTrue(l.any { it.contains("12 de 50") }); assertTrue(l.any { it.contains("sem limite") })
        assertEquals("org_01ABC", PlanUsage.orgIdFrom("Turn on at https://opencode.ai/console/org_01ABC/settings/privacy"))
    }
}
