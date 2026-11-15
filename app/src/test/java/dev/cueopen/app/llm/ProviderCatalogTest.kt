package dev.cueopen.app.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderCatalogTest {
    @Test fun `presets are unique remote ones use https and local ones need no key`() {
        assertEquals(ProviderCatalog.all.size, ProviderCatalog.all.map { it.id }.toSet().size)
        assertTrue(ProviderCatalog.all.size >= 15)
        ProviderCatalog.all.forEach { p ->
            if (p.needsKey) assertTrue(p.id, p.baseUrl.startsWith("https://"))
            else assertTrue(p.id, p.baseUrl.startsWith("http://127.0.0.1"))
            assertTrue(p.id, p.keyUrl.startsWith("https://"))
        }
    }

    @Test fun `explicit active provider wins and oauth providers come first`() {
        val s = dev.cueopen.app.data.Settings(baseUrl = "http://192.168.0.10:11434/v1", activeProvider = "ollama")
        assertEquals("ollama", ProviderCatalog.activeId(s))
        assertEquals("custom", ProviderCatalog.activeId(s.copy(activeProvider = "")))
        assertTrue(ProviderCatalog.ordered.first().oauth)
    }

    @Test fun `base urls are matched ignoring case and trailing slash`() {
        assertEquals("groq", ProviderCatalog.byBaseUrl("https://API.groq.com/openai/v1/")?.id)
        assertNull(ProviderCatalog.byBaseUrl("https://meu-servidor.com/v1"))
        assertEquals("custom", ProviderCatalog.storageKey("https://meu-servidor.com/v1"))
        assertEquals("openrouter", ProviderCatalog.storageKey("https://openrouter.ai/api/v1"))
    }
}
