package dev.fluwy.app.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectorEditToolPolicyTest {
    @Test fun `browser-enabled editor permits only public-page browsing and the selected-connector proposal tool`() {
        assertTrue(ConnectorEditToolPolicy.allows(ConnectorEditToolPolicy.PROPOSE_TOOL, browserEnabled = true))
        ConnectorEditToolPolicy.READ_ONLY_BROWSER_TOOLS.forEach { tool ->
            assertTrue("$tool should be available", ConnectorEditToolPolicy.allows(tool, browserEnabled = true))
        }
        listOf("browser_click", "browser_type", "browser_js", "browser_network", "browser_console", "shell", "file_write", "uic_other_action")
            .forEach { tool -> assertFalse("$tool must stay unavailable", ConnectorEditToolPolicy.allows(tool, browserEnabled = true)) }
    }

    @Test fun `disabled browser leaves only the connector proposal tool`() {
        assertTrue(ConnectorEditToolPolicy.allows(ConnectorEditToolPolicy.PROPOSE_TOOL, browserEnabled = false))
        ConnectorEditToolPolicy.READ_ONLY_BROWSER_TOOLS.forEach { tool ->
            assertFalse("$tool should be hidden when browser is disabled", ConnectorEditToolPolicy.allows(tool, browserEnabled = false))
        }
    }
}
