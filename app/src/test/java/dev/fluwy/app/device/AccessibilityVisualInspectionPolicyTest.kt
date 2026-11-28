package dev.fluwy.app.device

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityVisualInspectionPolicyTest {
    @Test
    fun explicitPostReadOrImageRequestAllowsVisualFallback() {
        assertTrue(AccessibilityVisualInspectionPolicy.explicitlyRequestsPostOrImage("Abra o Instagram e leia o post principal"))
        assertTrue(AccessibilityVisualInspectionPolicy.explicitlyRequestsPostOrImage("Analise a foto deste post no Instagram"))
        assertTrue(AccessibilityVisualInspectionPolicy.explicitlyRequestsPostOrImage("Read the main post and describe the image"))
    }

    @Test
    fun openingAnAppOrMentioningAnImageWithoutReadIntentDoesNotAllowCapture() {
        assertFalse(AccessibilityVisualInspectionPolicy.explicitlyRequestsPostOrImage("Abra o Instagram"))
        assertFalse(AccessibilityVisualInspectionPolicy.explicitlyRequestsPostOrImage("A imagem estava no feed"))
        assertFalse(AccessibilityVisualInspectionPolicy.explicitlyRequestsPostOrImage("Abra a página de perfil"))
        assertFalse(AccessibilityVisualInspectionPolicy.explicitlyRequestsPostOrImage("Abra o Instagram e leia a bio do perfil"))
        assertFalse(AccessibilityVisualInspectionPolicy.explicitlyRequestsPostOrImage("Analise esta foto que anexei"))
    }

    @Test
    fun visualFallbackRequiresCurrentAccessibleReadAndIsOneShot() {
        val request = "Abra o Instagram e leia o post principal"
        assertTrue(AccessibilityVisualInspectionPolicy.canInspectImage(request, currentScreenWasRead = true, inspectionAlreadyUsed = false))
        assertFalse(AccessibilityVisualInspectionPolicy.canInspectImage(request, currentScreenWasRead = false, inspectionAlreadyUsed = false))
        assertFalse(AccessibilityVisualInspectionPolicy.canInspectImage(request, currentScreenWasRead = true, inspectionAlreadyUsed = true))
        assertFalse(AccessibilityVisualInspectionPolicy.canInspectImage("Abra o Instagram", currentScreenWasRead = true, inspectionAlreadyUsed = false))
    }
}
