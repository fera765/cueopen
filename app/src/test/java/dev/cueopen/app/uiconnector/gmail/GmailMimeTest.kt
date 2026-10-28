package dev.cueopen.app.uiconnector.gmail

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class GmailMimeTest {
    private fun b64(s: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())

    @Test fun `headers e corpo multipart preferem texto puro`() {
        val m = Json.parseToJsonElement("""{"payload":{"mimeType":"multipart/alternative","headers":[{"name":"From","value":"Ana <a@x.com>"},{"name":"Subject","value":"Oi"}],
          "parts":[{"mimeType":"text/html","body":{"data":"${b64("<p>Olá <b>mundo</b></p>")}"}},{"mimeType":"text/plain","body":{"data":"${b64("Olá mundo")}"}}]}}""") as JsonObject
        assertEquals("Oi", GmailMime.headers(m)["subject"])
        assertEquals("Olá mundo", GmailMime.bodyText(m["payload"]))
        assertEquals("Ana", GmailMime.shortFrom("Ana <a@x.com>"))
    }

    @Test fun `so html vira texto sem tags`() {
        val p = Json.parseToJsonElement("""{"mimeType":"text/html","body":{"data":"${b64("<style>x{}</style><div>A</div><script>z()</script>B &amp; C")}"}}""")
        val t = GmailMime.bodyText(p)
        assertTrue(t.contains("A")); assertTrue(t.contains("B & C")); assertFalse(t.contains("z()"))
    }

    @Test fun `mensagem raw sem injecao de cabecalho`() {
        val raw = GmailMime.build("eu@x.com", "voce@y.com", "Assunto\r\nBcc: evil@z.com", "Corpo ç")
        val msg = String(Base64.getUrlDecoder().decode(raw))
        assertFalse(msg.contains("\r\nBcc:"))
        assertTrue(msg.contains("To: voce@y.com"))
        assertTrue(GmailMime.validEmail("a@b.co")); assertFalse(GmailMime.validEmail("a@b")); assertFalse(GmailMime.validEmail("a b@c.com"))
    }
}
