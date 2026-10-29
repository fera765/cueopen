package dev.cueopen.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JevOrchestratorTest {
    private val conns = mapOf("iqoption" to ("IQ Option" to "Opções binárias e forex"), "eduzz" to ("Eduzz" to "Vendas e produtos digitais"))
    private fun sid() = "j-" + System.nanoTime()
    private fun input(q: String, used: Set<String> = emptySet(), sid: String = sid(), builder: Boolean = false, hist: Int = 0) =
        JevOrchestrator.Input(sid, q, used, builder, connectors = conns, mcpServers = mapOf("mcp_github" to "github"), historyTokens = hist, contextBudget = 24_000)
    private val connOf = { n: String -> conns.keys.firstOrNull { n.startsWith("uic_${it}_") } }
    private val mcpOf = { n: String -> n.substringBefore("__").takeIf { n.contains("__") } }

    @Test fun simpleTaskSendsOnlyCore() {
        val p = JevOrchestrator.plan(input("crie uma página html bonita para minha clínica"))
        assertEquals(setOf("core"), p.groups)
        assertTrue(p.keepTool("shell", connOf, mcpOf))
        assertFalse(p.keepTool("wallet_swap", connOf, mcpOf))
        assertFalse(p.keepTool("uic_iqoption_buy", connOf, mcpOf))
        assertFalse(p.keepTool("mcp_github__create_issue", connOf, mcpOf))
        assertTrue(p.keepTool("ferramenta_nova_sem_grupo", connOf, mcpOf)) // nunca esconde o que não conhece
        val idx = p.index(mapOf("iqoption" to "IQ Option"), emptyMap())
        assertTrue(idx, idx.contains("wallet") && idx.contains("connector:iqoption") && idx.contains("tools_load"))
    }

    @Test fun requestActivatesGroupsAndConnectors() {
        val p = JevOrchestrator.plan(input("faça day trade na IQ Option a cada 5 minutos"))
        assertTrue(p.groups.containsAll(listOf("schedule", "wallet")))
        assertTrue(p.keepTool("uic_iqoption_buy", connOf, mcpOf))
        assertFalse(p.keepTool("uic_eduzz_vendas", connOf, mcpOf))
    }

    @Test fun shortTriggersNeedWholeWord() {
        assertFalse(JevOrchestrator.plan(input("me lembra no domingo de ver o relatório")).groups.contains("web_dev"))
    }

    @Test fun usedAndLoadedToolsAreSticky() {
        val s = sid()
        assertTrue(JevOrchestrator.plan(input("ok continue", used = setOf("device_screenshot"), sid = s)).groups.contains("device"))
        val (ok, bad) = JevOrchestrator.resolveLoad(listOf("web_dev", "eduzz", "mcp:github", "xyz"), conns.keys, setOf("github"))
        assertEquals(listOf("web_dev", "connector:eduzz", "mcp:github"), ok); assertEquals(listOf("xyz"), bad)
        JevOrchestrator.load(s, ok)
        val p = JevOrchestrator.plan(input("ok continue", sid = s))
        assertTrue(p.groups.contains("web_dev"))
        assertTrue(p.keepTool("uic_eduzz_vendas", connOf, mcpOf))
        assertTrue(p.keepTool("mcp_github__create_issue", connOf, mcpOf))
        JevOrchestrator.forget(s)
    }

    @Test fun builderSessionGetsBuilderTools() {
        assertTrue(JevOrchestrator.plan(input("integra com a api x", builder = true)).groups.contains("connector_builder"))
    }

    @Test fun budgetsShrinkAsHistoryGrows() {
        val small = JevOrchestrator.plan(input("x", hist = 1_000))
        val big = JevOrchestrator.plan(input("x", hist = 20_000))
        assertTrue(big.knowledgeChars < small.knowledgeChars && big.memoryChars < small.memoryChars)
    }

    @Test fun disabledKeepsEverything() {
        val p = JevOrchestrator.plan(input("oi").copy(enabled = false))
        assertTrue(p.keepTool("wallet_swap", connOf, mcpOf) && p.keepTool("uic_eduzz_x", connOf, mcpOf))
    }

    @Test fun modelRouting() {
        assertEquals("a/b", JevOrchestrator.routeModel("a/b", "d/m", true, true))
        assertEquals(JevOrchestrator.JEV_ROUTER, JevOrchestrator.routeModel(null, "d/m", true, true))
        assertEquals("d/m", JevOrchestrator.routeModel("", "d/m", true, false)) // fora da OpenRouter não roteia
        assertEquals("f/m", JevOrchestrator.auxModel("f/m", false, true))
    }
}
