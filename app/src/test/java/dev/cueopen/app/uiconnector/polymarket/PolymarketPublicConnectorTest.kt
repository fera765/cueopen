package dev.cueopen.app.uiconnector.polymarket

import dev.cueopen.app.uiconnector.ConnectorSettings
import dev.cueopen.app.uiconnector.SpecValidator
import dev.cueopen.app.uiconnector.UiConnectorCallContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PolymarketPublicConnectorTest {
    private val settings = ConnectorSettings { null }
    private val source = "import subprocess\nsubprocess.run(['curl', 'https://data-api.polymarket.com'])"

    @Test fun specIsValidPublicAndReadOnly() {
        val connector = PolymarketPublicConnector(source) { _, _ -> "ok" }
        assertTrue(SpecValidator.validate(connector.spec).toString(), SpecValidator.validate(connector.spec).isEmpty())
        assertTrue(connector.spec.loginFields.isEmpty())
        assertTrue(connector.spec.tools.map { it.name }.toSet().containsAll(setOf("list_wallets", "analyze_wallets")))
        assertFalse(connector.spec.tools.any { it.risky || it.opensOrder })
        assertTrue(connector.secrets().isEmpty())
    }

    @Test fun toolRunsInTheCallersTerminalSessionWithBoundedLimit() = runBlocking {
        var invokedSession: String? = null
        var command = ""
        val connector = PolymarketPublicConnector(source) { session, shell ->
            invokedSession = session
            command = shell
            "Polymarket summary"
        }
        val result = connector.callTool(
            "list_wallets", mapOf("limit" to "3"), settings, UiConnectorCallContext("session-42"),
        )
        assertEquals("Polymarket summary", result)
        assertEquals("session-42", invokedSession)
        assertTrue(command.startsWith("python3 -c "))
        assertTrue(command.contains("curl"))
        assertTrue(command.contains("--limit 3"))
        assertTrue(command.contains("list_wallets"))
    }

    @Test fun invalidLimitAndMissingSessionFailClosedBeforeExecution() = runBlocking {
        var executed = false
        val connector = PolymarketPublicConnector(source) { _, _ -> executed = true; "unexpected" }
        val invalid = connector.callTool("analyze_wallets", mapOf("limit" to "11"), settings, UiConnectorCallContext("session"))
        val missing = connector.callTool("analyze_wallets", emptyMap(), settings, UiConnectorCallContext())
        assertTrue(invalid.startsWith("ERRO:"))
        assertTrue(missing.startsWith("ERRO:"))
        assertFalse(executed)
    }
}
