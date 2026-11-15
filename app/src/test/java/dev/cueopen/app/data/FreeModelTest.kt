package dev.cueopen.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeModelTest {
    @Test fun `free models are detected by price or id`() {
        assertTrue(ProviderModels.isFree(ProviderModels.Model("x/y", "y", promptPrice = 0.0, completionPrice = 0.0)))
        assertTrue(ProviderModels.isFree(ProviderModels.Model("qwen/qwen3:free", "q")))
        assertTrue(ProviderModels.isFree(ProviderModels.Model("longcat-2.5-free", "l")))
        assertFalse(ProviderModels.isFree(ProviderModels.Model("openai/gpt-5", "g", promptPrice = 1.25, completionPrice = 10.0)))
        assertFalse(ProviderModels.isFree(ProviderModels.Model("freeform-model", "f")))
    }
}
