package dev.cueopen.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FileLinksTest {
    @Test fun `attachment lines are parsed`() {
        assertEquals("relatorio_final.md" to "entrega final", FileLinks.parseAttachmentLine("📎 relatorio_final.md — entrega final"))
        assertEquals("/root/workspace/a b.csv" to "", FileLinks.parseAttachmentLine("📎 `/root/workspace/a b.csv`"))
        assertNull(FileLinks.parseAttachmentLine("texto comum"))
    }
}
