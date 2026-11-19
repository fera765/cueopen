package dev.cueopen.app.backup

import dev.cueopen.app.data.AppState
import dev.cueopen.app.data.Connector
import dev.cueopen.app.data.Settings
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.random.Random

class DataTransferTest {
    @Test fun secureBoxRoundTripAndWrongPassword() {
        val data = Random(7).nextBytes(300_000) // maior que o buffer: testa o fluxo em partes
        val f = File.createTempFile("box", ".bin")
        try {
            f.outputStream().use { SecureBox.encrypt("segredo123".toCharArray(), data.inputStream(), it) }
            val enc = f.readBytes()
            assertFalse("não pode conter o texto puro", String(enc, Charsets.ISO_8859_1).contains(String(data.copyOfRange(0, 64), Charsets.ISO_8859_1)))
            val out = ByteArrayOutputStream()
            SecureBox.decrypt("segredo123".toCharArray(), f, out)
            assertArrayEquals(data, out.toByteArray())
            try { SecureBox.decrypt("errada".toCharArray(), f, ByteArrayOutputStream()); fail("senha errada passou") } catch (_: SecureBox.WrongPassword) { }
            // adulteração de 1 byte no meio é detectada antes de decifrar
            enc[enc.size / 2] = (enc[enc.size / 2].toInt() xor 1).toByte(); f.writeBytes(enc)
            val leak = ByteArrayOutputStream()
            try { SecureBox.decrypt("segredo123".toCharArray(), f, leak); fail("arquivo alterado passou") } catch (_: SecureBox.WrongPassword) { }
            assertEquals(0, leak.size())
        } finally { f.delete() }
    }

    @Test fun sanitizeRemovesSecretsButKeepsTheList() {
        val st = AppState(
            connectors = listOf(
                Connector("gh", "GitHub", "https://x/mcp", needsAuth = true, accessToken = "tok", refreshToken = "ref", clientSecret = "cs", connectedAt = 1L),
                Connector("pub", "Público", "https://y/mcp", needsAuth = false, connectedAt = 2L),
            ),
            settings = Settings(apiKey = "sk-1", telegramBotToken = "tg", slackBotToken = "sl", discordWebhookUrl = "https://discord/w",
                oauthTokens = mapOf("p" to listOf("r", "1")), savedProviders = mapOf("openrouter" to listOf("k"))),
        )
        val s = DataTransfer.sanitize(st)
        assertEquals(2, s.connectors.size)
        val gh = s.connectors[0]
        assertNull(gh.accessToken); assertNull(gh.refreshToken); assertNull(gh.clientSecret)
        assertNull("exige login: volta desconectado", gh.connectedAt)
        assertEquals(2L, s.connectors[1].connectedAt)
        assertEquals("", s.settings.apiKey); assertEquals("", s.settings.telegramBotToken); assertEquals("", s.settings.slackBotToken)
        assertEquals("", s.settings.discordWebhookUrl); assertTrue(s.settings.oauthTokens.isEmpty()); assertTrue(s.settings.savedProviders.isEmpty())
        assertEquals(st.settings.model, s.settings.model)
    }

    @Test fun mergeSettingsPrefersEncryptedThenCurrentSecrets() {
        val imported = Settings(apiKey = "", model = "m-import")
        val secure = Settings(apiKey = "sk-backup", model = "m-import")
        val current = Settings(apiKey = "sk-atual", model = "m-atual")
        assertEquals("sk-backup", DataTransfer.mergeSettings(imported, secure, current).apiKey)
        val noSecure = DataTransfer.mergeSettings(imported, null, current)
        assertEquals("sk-atual", noSecure.apiKey)
        assertEquals("m-import", noSecure.model)
    }

    @Test fun prefsAreTyped() {
        val o = DataTransfer.prefsToJson(mapOf("a" to "x", "b" to true, "i" to 3, "l" to 4L, "f" to 1.5f, "ss" to setOf("p", "q")))
        assertEquals("s", (o["a"]!!.jsonObject["t"] as JsonPrimitive).content)
        assertEquals("l", (o["l"]!!.jsonObject["t"] as JsonPrimitive).content)
        assertEquals("ss", (o["ss"]!!.jsonObject["t"] as JsonPrimitive).content)
    }

    @Test fun zipEntriesCannotEscape() {
        assertNull(DataTransfer.safeEntry("../../etc/passwd"))
        assertNull(DataTransfer.safeEntry("data/../../x"))
        assertEquals("data/state.json", DataTransfer.safeEntry("/data/state.json"))
        assertTrue(DataTransfer.webviewSkipped("Default/Code Cache/js/index"))
        assertFalse(DataTransfer.webviewSkipped("Default/Cookies"))
    }
}
