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

    @Test fun transcriptReplayPreservesSafeSgrWithLineBreaksAndBounded() {
        val bytes = TerminalRecoveryPolicy.transcriptBytes("\u001b[0;38;5;209mcue\u001b[0m\nresultado")
        assertNotNull(bytes)
        assertEquals("\u001b[0;38;5;209mcue\u001b[0m\r\nresultado\u001b[0m\r\n", String(bytes!!, Charsets.UTF_8))
        assertEquals(null, TerminalRecoveryPolicy.transcriptBytes(" \n  "))
    }

    @Test fun transcriptReplayDropsNonSgrControlsAndKeepsTextNonExecutable() {
        val bytes = TerminalRecoveryPolicy.transcriptBytes("antes\u001b]52;c;clipboard\u0007\u001b[2J\u001b[0;1m<strong>\u001b[0m")
        assertNotNull(bytes)
        val replay = String(bytes!!, Charsets.UTF_8)
        assertEquals("antes\u001b[0;1m<strong>\u001b[0m\u001b[0m\r\n", replay)
        assertFalse(replay.contains("clipboard"))
        assertFalse(replay.contains("\u001b[2J"))
    }

    @Test fun boundedTranscriptDropsPartialLeadingLine() {
        val snapshot = "prefixo" + "x".repeat(TerminalRecoveryPolicy.MAX_TRANSCRIPT_CHARS) + "\ncomando inteiro\nresultado"
        assertEquals("comando inteiro\nresultado", TerminalRecoveryPolicy.boundedTranscript(snapshot))
    }
}
