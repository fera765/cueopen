package dev.cueopen.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolOutputShaperTest {
    private fun email(i: Int) = """
        {"id":"m$i","subject":"Pedido $i","from":"loja@x.com","date":"2026-11-17","snippet":"Seu pedido saiu",
         "headers":{"Received":"${"a".repeat(800)}","DKIM-Signature":"${"b".repeat(600)}"},
         "html":"<html><body>${"<p>oi</p>".repeat(200)}</body></html>",
         "text":"${"linha ".repeat(300)}","labels":[],"cc":"","attachments":[{"filename":"nota.pdf","data":"${"QUJD".repeat(120)}"}]}
    """.trimIndent()

    @Test fun gmailLikeOutputBecomesCompactAndKeepsTheSignal() {
        val raw = "✅ Success:\n[" + (1..30).joinToString(",") { email(it) } + "]"
        val s = ToolOutputShaper.shape(raw)
        assertTrue(s.changed)
        assertTrue(s.text.length < raw.length / 5)
        assertTrue(s.text.startsWith("✅ Success:"))
        assertTrue(s.text.contains("\"subject\":\"Pedido 1\""))
        assertTrue(s.text.contains("\"from\":\"loja@x.com\""))
        assertFalse(s.text.contains("DKIM"))
        assertFalse(s.text.contains("<p>"))
        assertTrue(s.text.contains("base64"))
        assertTrue(s.text.contains("+10 itens"))
        assertTrue(s.text.contains("chars)"))
        assertTrue("headers" in s.omittedKeys && "html" in s.omittedKeys)
        assertFalse(s.text.contains("\"labels\""))
    }

    @Test fun plainTextAndSmallJsonStayUntouched() {
        assertEquals("tudo certo", ToolOutputShaper.shape("tudo certo").text)
        val small = """{"ok":true,"count":2}"""
        val s = ToolOutputShaper.shape(small)
        assertFalse(s.changed)
        assertEquals(small, s.text)
    }

    @Test fun brokenJsonFallsBackToOriginal() {
        val broken = "resultado: {\"a\": [1, 2"
        assertEquals(broken, ToolOutputShaper.shape(broken).text)
    }
}

class HubRecipeTest {
    @Test fun hubPromptUsesGenericConnectorToolsWithoutFlows() {
        val lines = ConnectorRouting.promptLines(emptyList(), true, null, emptyList()).joinToString("\n")
        assertTrue(lines.contains("conector_executar {conector, acao, campos, conexao}"))
        assertTrue(lines.contains("não existem automações"))
    }

    @Test fun busyboxOptionErrorsGetSpecificAdvice() {
        val out = "ls: unrecognized option: time-style=+%H:%M:%S\nBusyBox v1.37.0 (2024-11-19 21:09:16 UTC) multi-call binary."
        assertEquals(ToolOutcomeAdvisor.Reason.BUSYBOX_OPTION, ToolOutcomeAdvisor.classify("shell", out))
    }
}
