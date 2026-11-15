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

    @Test fun `previews summarize args without typed text`() {
        val args = mapOf("command" to "ls", "path" to "/w/rel.pdf", "channel" to "telegram")
        val s: (String) -> String = { args[it].orEmpty() }
        val i: (String) -> Int? = { if (it == "element_id") 7 else null }
        assertEquals("ls", ToolActivityLabels.preview("shell", s, i, "/w"))
        assertEquals("telegram · rel.pdf", ToolActivityLabels.preview("send_file", s, i, "/w"))
        assertEquals("element 7 · texto oculto", ToolActivityLabels.preview("device_accessibility_type", s, i, "/w"))
    }
}
