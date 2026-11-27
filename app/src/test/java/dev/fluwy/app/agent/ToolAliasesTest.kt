package dev.fluwy.app.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolAliasesTest {
    @Test fun `aliases map only to exposed tools`() {
        val exposed = setOf("file_search", "file_read", "web_fetch")
        assertEquals("file_search", ToolAliases.resolve("grep", exposed))
        assertEquals("file_read", ToolAliases.resolve("read_file", exposed))
        assertEquals("web_fetch", ToolAliases.resolve("fetch", exposed))
        assertEquals("ls", ToolAliases.resolve("ls", exposed))  // file_list não exposta
        assertEquals("shell", ToolAliases.resolve("shell", exposed))
    }
}
