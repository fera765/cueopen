package dev.fluwy.app.assistant

import android.text.InputType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistTextPolicyTest {
    @Test fun collectsVisibleTextAndSanitizesControlCharacters() {
        val result = AssistTextPolicy.collect(listOf(
            AssistTextPolicy.NodeText(text = "  Título\nprincipal  "),
            AssistTextPolicy.NodeText(hint = "Buscar\u0001 agora"),
            AssistTextPolicy.NodeText(text = "Título principal"),
        ))
        assertEquals("Título principal\nBuscar agora", result)
    }

    @Test fun omitsTextHintsAndDescriptionsFromPasswordInputs() {
        val password = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        val result = AssistTextPolicy.collect(listOf(
            AssistTextPolicy.NodeText(
                text = "segredo",
                hint = "senha",
                contentDescription = "campo privado",
                inputType = password,
            ),
            AssistTextPolicy.NodeText(text = "conteúdo público"),
        ))
        assertFalse(result.contains("segredo"))
        assertFalse(result.contains("senha"))
        assertFalse(result.contains("campo privado"))
        assertTrue(result.contains("conteúdo público"))
    }

    @Test fun capsPayloadAndSkipsPasswordVariations() {
        val password = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        assertTrue(AssistTextPolicy.isPasswordInput(password))
        assertTrue(AssistTextPolicy.isPasswordInput(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        ))
        val result = AssistTextPolicy.collect(listOf(
            *List(20) { index -> AssistTextPolicy.NodeText(text = "$index:" + "x".repeat(1000)) }.toTypedArray(),
        ))
        assertEquals(AssistTextPolicy.MAX_CHARS, result.length)
    }
}
