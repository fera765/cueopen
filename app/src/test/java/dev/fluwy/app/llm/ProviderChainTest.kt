package dev.fluwy.app.llm

import dev.fluwy.app.data.Settings
import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderChainTest {
    private val s = Settings(activeProvider = "opencode-go", baseUrl = "https://opencode.ai/inference/go/openai/v1", model = "glm-5", fastModel = "glm-5-air",
        savedProviders = mapOf("openrouter" to listOf("k", "https://openrouter.ai/api/v1", "openrouter/free", "", "")),
        fallbackProviders = listOf("openrouter", "opencode-go"))

    @Test fun `fallback uses each provider own model and skips the active one`() {
        assertEquals("openrouter::openrouter/free", ProviderChain.fallback(s))
        assertEquals("openrouter::openrouter/free", ProviderChain.fallback(s, fast = true))
        assertEquals("glm-5-air", ProviderChain.modelOf(s, "opencode-go", fast = true))
    }

    @Test fun `switching the active provider flips the chain`() {
        val other = s.copy(activeProvider = "openrouter", baseUrl = "https://openrouter.ai/api/v1", model = "openrouter/free",
            savedProviders = s.savedProviders + ("opencode-go" to listOf("k2", "https://opencode.ai/inference/go/openai/v1", "glm-5", "", "")))
        assertEquals("opencode-go::glm-5", ProviderChain.fallback(other))
    }
}
