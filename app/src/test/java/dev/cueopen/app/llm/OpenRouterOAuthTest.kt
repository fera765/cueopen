package dev.cueopen.app.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterOAuthTest {
    @Test fun `S256 challenge matches the RFC 7636 test vector`() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", OpenRouterOAuth.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }

    @Test fun `verifier is url safe and long enough`() {
        val v = OpenRouterOAuth.newVerifier()
        assertTrue(v.length in 43..128)
        assertTrue(v.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test fun `code is read only from the callback request line`() {
        assertEquals("abc123", OpenRouterOAuth.codeFromRequestLine("GET /callback?code=abc123 HTTP/1.1"))
        assertEquals("a b", OpenRouterOAuth.codeFromRequestLine("GET /callback?x=1&code=a%20b HTTP/1.1"))
        assertNull(OpenRouterOAuth.codeFromRequestLine("GET /favicon.ico HTTP/1.1"))
        assertNull(OpenRouterOAuth.codeFromRequestLine("GET /callback?error=denied HTTP/1.1"))
        assertNull(OpenRouterOAuth.codeFromRequestLine(""))
    }
}
