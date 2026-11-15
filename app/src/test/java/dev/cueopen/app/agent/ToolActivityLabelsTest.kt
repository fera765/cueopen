package dev.cueopen.app.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolActivityLabelsTest {
    @Test fun `known unknown and connector tools get labels`() {
        assertEquals("Navegando" to Activity.Kind.BROWSER, ToolActivityLabels.of("browser_navigate"))
        assertEquals("Enviando arquivo" to Activity.Kind.THINKING, ToolActivityLabels.of("send_file"))
        assertEquals("Conector · listar" to Activity.Kind.THINKING, ToolActivityLabels.of("mcp_gh__listar"))
        assertEquals("cron_add" to Activity.Kind.THINKING, ToolActivityLabels.of("cron_add"))
    }
}
