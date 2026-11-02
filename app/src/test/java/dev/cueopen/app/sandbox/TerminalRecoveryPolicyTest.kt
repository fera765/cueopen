package dev.cueopen.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class TerminalRecoveryPolicyTest {
    @Test fun restoresOnlyDirectoriesInsideTheChatEnvironment() {
        val parent = Files.createTempDirectory("terminal-recovery").toFile()
        val root = File(parent, "chat").apply { mkdirs() }
        File(root, "workspace/subdir").mkdirs()
        val outside = File(parent, "other-chat").apply { mkdirs() }
        val resolver: (String) -> File = { guest -> File(root, guest.removePrefix("/root/").removePrefix("/root")) }

        assertEquals(
            "/root/workspace/subdir",
            TerminalRecoveryPolicy.safeWorkingDirectory("/root/workspace/subdir", "/root/workspace", root, resolver),
        )
        assertEquals(
            "/root/workspace",
            TerminalRecoveryPolicy.safeWorkingDirectory("/root/../other-chat", "/root/workspace", root) {
                if (it == "/root/../other-chat") outside else resolver(it)
            },
        )
        parent.deleteRecursively()
    }

    @Test fun transcriptReplayIsPlainTextWithLineBreaksAndBounded() {
        val bytes = TerminalRecoveryPolicy.transcriptBytes("resultado anterior")
        assertNotNull(bytes)
        assertEquals("resultado anterior\r\n", String(bytes!!, Charsets.UTF_8))
        assertFalse(String(bytes, Charsets.UTF_8).contains("\u001b"))
        assertEquals(null, TerminalRecoveryPolicy.transcriptBytes(" \n  "))
    }

    @Test fun boundedTranscriptDropsPartialLeadingLine() {
        val snapshot = "prefixo" + "x".repeat(TerminalRecoveryPolicy.MAX_TRANSCRIPT_CHARS) + "\ncomando inteiro\nresultado"
        assertEquals("comando inteiro\nresultado", TerminalRecoveryPolicy.boundedTranscript(snapshot))
    }
}
