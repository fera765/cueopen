package dev.cueopen.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolContextPlannerTest {
    private val conns = mapOf("iqoption" to ("IQ Option" to "Opções binárias e forex"), "eduzz" to ("Eduzz" to "Vendas e produtos digitais"))
    private fun sid() = "ctx-" + System.nanoTime()
    private fun input(q: String, used: Set<String> = emptySet(), sid: String = sid(), builder: Boolean = false, hist: Int = 0) =
        ToolContextPlanner.Input(sid, q, used, builder, connectors = conns, mcpServers = mapOf("mcp_github" to "github"), historyTokens = hist, contextBudget = 24_000)
    private val connOf = { n: String -> conns.keys.firstOrNull { n.startsWith("uic_${it}_") } }
    private val mcpOf = { n: String -> n.substringBefore("__").takeIf { n.contains("__") } }

    @Test fun simpleTaskSendsOnlyCore() {
        val p = ToolContextPlanner.plan(input("crie uma página html bonita para minha clínica"))
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
        val p = ToolContextPlanner.plan(input("faça day trade na IQ Option a cada 5 minutos"))
        assertTrue(p.groups.containsAll(listOf("schedule", "wallet")))
        assertTrue(p.keepTool("uic_iqoption_buy", connOf, mcpOf))
        assertFalse(p.keepTool("uic_eduzz_vendas", connOf, mcpOf))
    }

    @Test fun polymarketAnalysisRequestLoadsReadOnlyWalletToolGroup() {
        val p = ToolContextPlanner.plan(input("analise carteiras lucrativas da Polymarket nos últimos 90 dias"))
        assertTrue(p.groups.contains("wallet"))
        assertTrue(p.keepTool("wallet_polymarket_insights", connOf, mcpOf))
    }

    @Test fun contactAddressBookBluetoothAndCallsLoadDeviceTools() {
        listOf(
            "liste meus contatos" to "device_contacts",
            "procure na minha agenda telefônica" to "device_contacts",
            "qual telefone da Ana?" to "device_contacts",
            "mostre dispositivos pareados" to "device_bluetooth",
            "fazer uma chamada para a Ana" to "device_dial",
        ).forEach { (q, tool) ->
            val p = ToolContextPlanner.plan(input(q))
            assertTrue("query=$q", p.groups.contains("device"))
            assertTrue("query=$q", p.keepTool(tool, connOf, mcpOf))
        }
        assertFalse(ToolContextPlanner.plan(input("ler SMS")).groups.contains("device"))
    }

    @Test fun shortTriggersNeedWholeWord() {
        assertFalse(ToolContextPlanner.plan(input("me lembra no domingo de ver o relatório")).groups.contains("web_dev"))
    }

    @Test fun usedAndLoadedToolsAreSticky() {
        val s = sid()
        assertTrue(ToolContextPlanner.plan(input("ok continue", used = setOf("device_screenshot"), sid = s)).groups.contains("device"))
        val (ok, bad) = ToolContextPlanner.resolveLoad(listOf("web_dev", "eduzz", "mcp:github", "xyz"), conns.keys, setOf("github"))
        assertEquals(listOf("web_dev", "connector:eduzz", "mcp:github"), ok)
        assertEquals(listOf("xyz"), bad)
        ToolContextPlanner.load(s, ok)
        val p = ToolContextPlanner.plan(input("ok continue", sid = s))
        assertTrue(p.groups.contains("web_dev"))
        assertTrue(p.keepTool("uic_eduzz_vendas", connOf, mcpOf))
        assertTrue(p.keepTool("mcp_github__create_issue", connOf, mcpOf))
        ToolContextPlanner.forget(s)
    }

    @Test fun builderSessionGetsBuilderTools() {
        assertTrue(ToolContextPlanner.plan(input("integra com a api x", builder = true)).groups.contains("connector_builder"))
    }

    @Test fun budgetsShrinkAsHistoryGrows() {
        val small = ToolContextPlanner.plan(input("x", hist = 1_000))
        val big = ToolContextPlanner.plan(input("x", hist = 20_000))
        assertTrue(big.knowledgeChars < small.knowledgeChars && big.memoryChars < small.memoryChars)
    }
}
