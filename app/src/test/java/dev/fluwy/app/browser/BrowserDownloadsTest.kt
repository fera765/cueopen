package dev.fluwy.app.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class BrowserDownloadsTest {
    @Test fun uniqueNamesNeverOverwrite() {
        val dir = Files.createTempDirectory("dl").toFile()
        assertEquals("relatorio.pdf", BrowserDownloads.uniqueFile(dir, "relatorio.pdf").name)
        java.io.File(dir, "relatorio.pdf").writeText("x")
        assertEquals("relatorio (2).pdf", BrowserDownloads.uniqueFile(dir, "relatorio.pdf").name)
        assertEquals("a_b_c.txt", BrowserDownloads.uniqueFile(dir, "a/b\\c.txt").name)
        assertEquals("download", BrowserDownloads.uniqueFile(dir, "  ..  ").name)
    }

    @Test fun sizeLimit() {
        assertFalse(BrowserDownloads.tooBig(-1))
        assertFalse(BrowserDownloads.tooBig(BrowserDownloads.MAX_BYTES))
        assertTrue(BrowserDownloads.tooBig(BrowserDownloads.MAX_BYTES + 1))
    }
}
