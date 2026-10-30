package dev.cueopen.app.device

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityInputPolicyTest {
    @Test fun `campo comum pode receber texto solicitado`() {
        assertTrue(AccessibilityInputPolicy.mayTypeIntoField(isPassword = false, hasSecureSecret = false))
    }

    @Test fun `campo de senha rejeita valor fora do cofre`() {
        assertFalse(AccessibilityInputPolicy.mayTypeIntoField(isPassword = true, hasSecureSecret = false))
    }

    @Test fun `campo de senha aceita somente marcador resolvido do cofre seguro`() {
        assertTrue(AccessibilityInputPolicy.mayTypeIntoField(isPassword = true, hasSecureSecret = true))
    }

    @Test fun `marcador com texto adicional nao deve ser tratado como segredo resolvido`() {
        assertFalse(AccessibilityInputPolicy.mayTypeIntoField(isPassword = true, hasSecureSecret = false))
    }
}
