package dev.fluwy.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MentionSuggestTest {
    private val gmail = Mentions.Target("CONECTOR:mcp_gmail", Mentions.Kind.CONNECTOR, "mcp:gmail", "Gmail")
    private val gmailName = Mentions.Target("Gmail", Mentions.Kind.CONNECTOR, "mcp:gmail")
    private val sheets = Mentions.Target("CONECTOR:connector_sheets", Mentions.Kind.CONNECTOR, "connector:sheets", "Planilhas")
    private val api = Mentions.Target("APIHUB:clima", Mentions.Kind.WEBAPI, "clima")
    private val skill = Mentions.Target("Resumo", Mentions.Kind.SKILL, "s1")
    private val all = listOf(gmail, gmailName, sheets, api, skill, Mentions.Target("APIHUB", Mentions.Kind.WEBAPI, ""))

    @Test fun `arroba sozinho mostra so duas opcoes`() {
        val s = MentionSuggest.suggest("@", all, all)
        assertEquals(listOf("Conectores", "API Hub"), s.map { it.label })
        assertEquals(listOf("@CONECTOR:", "@APIHUB:"), s.map { it.insert })
    }

    @Test fun `texto parcial filtra as opcoes iniciais`() {
        assertEquals(listOf("Conectores"), MentionSuggest.suggest("@con", all, all).map { it.label })
        assertEquals(listOf("API Hub"), MentionSuggest.suggest("@api", all, all).map { it.label })
        assertTrue(MentionSuggest.suggest("@Resumo", all, all).isEmpty())
    }

    @Test fun `segunda caixa lista conectores e filtra pelo nome`() {
        val s = MentionSuggest.suggest("@CONECTOR:", all, all)
        assertEquals(listOf("@conectores: Gmail", "@conectores: Planilhas"), s.map { it.label })
        assertEquals("@CONECTOR:mcp_gmail ", s[0].insert)
        assertEquals(listOf("@conectores: Planilhas"), MentionSuggest.suggest("@conector:plan", all, all).map { it.label })
    }

    @Test fun `segunda caixa do api hub`() {
        val s = MentionSuggest.suggest("@APIHUB:cl", all, all) { "Clima" }
        assertEquals(listOf("@APIHUB:clima"), s.map { it.label })
        assertEquals("API Hub · Clima", s[0].description)
    }

    @Test fun `chave do conector e token`() {
        assertEquals("mcp_gmail_v2", MentionSuggest.connectorKey("mcp:gmail.v2"))
        assertEquals("@CONECTOR:connector_x", MentionSuggest.connectorToken("connector:x"))
    }

    @Test fun `find reconhece o token de conector inteiro`() {
        val hits = Mentions.find("use @CONECTOR:mcp_gmail para enviar", all)
        assertEquals(1, hits.size)
        assertEquals("mcp:gmail", hits[0].target.id)
        assertEquals("@CONECTOR:mcp_gmail", "use @CONECTOR:mcp_gmail para enviar".substring(hits[0].start, hits[0].end))
        // Chave incompleta não casa com um prefixo de outra chave.
        assertTrue(Mentions.find("@CONECTOR:mcp_gmailx", listOf(gmail)).isEmpty())
        assertEquals("clima", Mentions.find("@APIHUB:clima hoje", all).single().target.id)
    }
}
