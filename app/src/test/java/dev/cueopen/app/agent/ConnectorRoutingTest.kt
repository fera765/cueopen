package dev.cueopen.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectorRoutingTest {
    private val hubPfx = "mcp_activepieces_hub"
    private val hubKeywords = ConnectorRouting.keywordsFor("Activepieces Hub", "Hub local com 700+ apps", listOf("ap_list_connections", "ap_list_pieces", "ap_run_action"), isHub = true, inventory = "Gmail (lord@exemplo.com)")
    private fun plan(q: String, used: Set<String> = emptySet()) = ToolContextPlanner.plan(ToolContextPlanner.Input(
        "route-" + System.nanoTime(), q, used, builder = false,
        connectors = mapOf("iqoption" to ("IQ Option" to "Opções binárias")),
        mcpServers = mapOf(hubPfx to "Activepieces Hub", "mcp_github" to "github"),
        mcpKeywords = mapOf(hubPfx to hubKeywords),
    ))

    @Test fun useConnectorLoadsConnectorsFirstNotTheBuilder() {
        val p = plan("use o conector para ler email")
        assertTrue(hubPfx in p.mcp)
        assertTrue("iqoption" in p.connectors)
        assertFalse("connector_builder" in p.groups)
    }

    @Test fun appWordsRouteToHubWithoutSayingConnector() {
        assertTrue(hubPfx in plan("leia meus e-mails não lidos do gmail").mcp)
        assertTrue(hubPfx in plan("adicione uma linha na planilha de vendas").mcp)
        assertTrue(hubPfx in plan("publique isso no instagram").mcp)
    }

    @Test fun unrelatedTaskKeepsHubOutOfContext() {
        val p = plan("crie uma página html bonita para minha clínica")
        assertFalse(hubPfx in p.mcp)
    }

    @Test fun buildingAConnectorStillOpensTheBuilder() {
        val p = plan("crie um conector para a api da minha loja")
        assertTrue("connector_builder" in p.groups)
        assertTrue(ConnectorRouting.wantsToBuildConnector(ToolContextPlanner.norm("crie um conector para a api")))
        assertFalse(ConnectorRouting.wantsToUseConnector(ToolContextPlanner.norm("crie um conector para a api")))
    }

    @Test fun promptTellsTheAgentExactlyWhatToCall() {
        val lines = ConnectorRouting.promptLines(listOf(Triple("Activepieces Hub", hubPfx, true)), "Gmail (lord@exemplo.com)",
            listOf("ap_list_connections", "ap_list_pieces", "ap_run_action"), listOf("conector Activepieces Hub"))
        val all = lines.joinToString("\n")
        assertTrue(all.contains("NUNCA procure conectores no sistema de arquivos"))
        assertTrue(all.contains("${hubPfx}__ap_run_action"))
        assertTrue(all.contains("Gmail"))
        assertTrue(all.contains("marcou com @"))
        assertEquals(emptyList<String>(), ConnectorRouting.promptLines(emptyList(), null, emptyList(), emptyList()))
    }
}

class MentionsTest {
    private val targets = listOf(
        Mentions.Target("Activepieces Hub", Mentions.Kind.CONNECTOR, "mcp:activepieces_hub"),
        Mentions.Target("Activepieces", Mentions.Kind.SKILL, "s1"),
        Mentions.Target("Coordenador · Caça Ofertas", Mentions.Kind.AGENT, "a1"),
        Mentions.Target("glasser", Mentions.Kind.SKILL, "s2"),
    )

    @Test fun longestNameWinsAndCaseIsIgnored() {
        val hits = Mentions.find("use o @activepieces hub para ler email", targets)
        assertEquals(1, hits.size)
        assertEquals(Mentions.Kind.CONNECTOR, hits[0].target.kind)
        assertEquals("@activepieces hub", "use o @activepieces hub para ler email".substring(hits[0].start, hits[0].end))
    }

    @Test fun multipleMentionsIncludingNamesWithSymbols() {
        val text = "@Coordenador · Caça Ofertas use a skill @glasser."
        val hits = Mentions.find(text, targets)
        assertEquals(listOf(Mentions.Kind.AGENT, Mentions.Kind.SKILL), hits.map { it.target.kind })
    }

    @Test fun emailsAndPartialWordsAreNotMentions() {
        assertTrue(Mentions.find("mande para lord@glasser.com", targets).isEmpty())
        assertTrue(Mentions.find("@glassers", targets).isEmpty())
    }
}
