package dev.fluwy.app.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ErrorLogRedactTest {
    @Test fun masksBearerAndKeys() {
        val out = ErrorLog.redact("Authorization: Bearer abcdefghijklmnop123 url=https://x.com/a?key=SECRET123&q=1 sk-or-v1abcdefghijklmnop")
        assertFalse(out.contains("abcdefghijklmnop123"))
        assertFalse(out.contains("SECRET123"))
        assertFalse(out.contains("v1abcdefghijklmnop"))
        assertEquals(true, out.contains("q=1"))
    }

    @Test fun masksJsonSecrets() =
        assertEquals("""{"api_key": "•••"}""", ErrorLog.redact("""{"api_key": "123456"}"""))
}
