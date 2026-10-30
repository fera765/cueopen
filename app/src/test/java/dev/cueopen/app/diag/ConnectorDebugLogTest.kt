package dev.cueopen.app.diag

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectorDebugLogTest {
    private fun tempDir(): File = Files.createTempDirectory("cue-connector-debug-test").toFile()

    @Test fun `redige segredos falsos antes de persistir e ler`() {
        val dir = tempDir()
        try {
            val log = ConnectorDebugLog(dir)
            val fake = "fake-secret-abc-987"
            log.rememberSecrets("gmail", listOf(fake, "mateus@example.test"))
            log.recordHttp(
                connectorId = "gmail",
                method = "POST",
                url = "https://api.example.test/v1/messages?access_token=$fake",
                headerNames = listOf("Authorization", "Cookie"),
                durationMs = 42,
                statusCode = 401,
                summary = "auth failed token=$fake",
            )
            log.recordFailure("gmail", "oauth", IllegalStateException("authorization=$fake"))

            val visible = log.read("gmail")
            assertTrue(visible.contains("method=POST"))
            assertTrue(visible.contains("auth=authorization"))
            assertTrue(visible.contains("status=401"))
            assertTrue(visible.contains("duration_ms=42"))
            assertFalse(visible.contains(fake))
            assertFalse(visible.contains("mateus@example.test"))
            assertFalse(visible.contains("access_token="))
            assertFalse(log.read("other-connector").contains(fake))
            assertTrue(log.read("other-connector").isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun `endpoint remove query e corpo nao e uma entrada aceita`() {
        val dir = tempDir()
        try {
            val log = ConnectorDebugLog(dir)
            log.recordHttp("custom1", "GET", "https://api.example.test/v1/users/person@example.test?email=person@example.test", emptyList(), 7, 200, "resposta; corpo omitido")
            log.recordHttp("custom1", "GET", "https://gmail.googleapis.com/gmail/v1/users/me/messages/abcdef1234567890?format=metadata", emptyList(), 8, 200, "resposta; corpo omitido")
            val output = log.read("custom1")
            assertTrue(output.contains("https://api.example.test/v1/users/[redigido]"))
            assertTrue(output.contains("/messages/[redigido]"))
            assertFalse(output.contains("?email="))
            assertFalse(output.contains("person@example.test"))
            assertFalse(output.contains("abcdef1234567890"))
            assertFalse(output.contains("body="))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun `arquivo e limitado e rotaciona`() {
        val dir = tempDir()
        try {
            val log = ConnectorDebugLog(dir)
            repeat(1_500) { log.recordEvent("iqoption", "request", "x".repeat(180)) }
            val file = dir.listFiles()!!.single()
            assertTrue(file.length() <= 128 * 1024L + 512)
            assertTrue(log.read("iqoption").isNotEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun `limpar um conector preserva os logs dos demais e arquivos nao relacionados`() {
        val dir = tempDir()
        try {
            val log = ConnectorDebugLog(dir)
            log.recordEvent("selected", "test", "a")
            log.recordEvent("other", "test", "b")
            val unrelatedData = File(dir, "state.json").apply { writeText("preservado") }

            log.clear("selected")

            assertTrue(log.read("selected").isEmpty())
            assertTrue(log.read("other").contains("summary=b"))
            assertTrue(unrelatedData.exists())
            assertTrue(unrelatedData.readText() == "preservado")
        } finally {
            dir.deleteRecursively()
        }
    }
}
