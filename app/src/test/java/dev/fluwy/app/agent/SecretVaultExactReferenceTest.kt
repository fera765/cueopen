package dev.fluwy.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SecretVaultExactReferenceTest {
    @Test fun `somente a referencia integral existente resolve como segredo`() {
        val sessionId = "exact-ref-test"
        SecretVault.put(sessionId, mapOf("password" to "private-value"))
        try {
            assertEquals("private-value", SecretVault.resolveExact(sessionId, "{{secret:password}}"))
            assertEquals("private-value", SecretVault.resolveExact(sessionId, " {{ secret:password }} "))
            assertNull(SecretVault.resolveExact(sessionId, "{{secret:password}}suffix"))
            assertNull(SecretVault.resolveExact(sessionId, "prefix{{secret:password}}"))
            assertNull(SecretVault.resolveExact(sessionId, "{{secret:missing}}"))
        } finally {
            SecretVault.clear(sessionId)
        }
    }
}
