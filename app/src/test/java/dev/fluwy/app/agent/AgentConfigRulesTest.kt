package dev.fluwy.app.agent

import dev.fluwy.app.data.AgentProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentConfigRulesTest {
    @Test fun `valida nome e janela`() {
        assertTrue(AgentConfigRules.validate(AgentProfile(name = "Dev")).isEmpty())
        assertEquals(1, AgentConfigRules.validate(AgentProfile(name = " ")).size)
        assertEquals(1, AgentConfigRules.validate(AgentProfile(name = "Dev"), listOf("dev")).size)
        assertEquals(1, AgentConfigRules.validate(AgentProfile(name = "Dev", activeStartHour = 5, activeEndHour = 5)).size)
        assertTrue(AgentConfigRules.validate(AgentProfile(name = "Dev", activeStartHour = 22, activeEndHour = 6)).isEmpty())
    }

    @Test fun `resumos`() {
        val a = AgentProfile(name = "Dev", useBrowser = false, autonomous = true, dailyTokenBudget = 1_000_000, activeStartHour = 8, activeEndHour = 18)
        assertEquals("Padrão do app", AgentConfigRules.model(a))
        assertTrue(AgentConfigRules.tools(a).startsWith("Terminal · Hub"))
        assertEquals("Ligada · 08h–18h · 1M tokens/dia", AgentConfigRules.autonomy(a))
        assertEquals("Desligada", AgentConfigRules.autonomy(a.copy(autonomous = false)))
    }
}
