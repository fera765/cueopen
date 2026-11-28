package dev.fluwy.app.llm

import dev.fluwy.app.data.Settings
import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderRouteTest {
    private val s = Settings(apiKey = "k-or", baseUrl = "https://openrouter.ai/api/v1",
        savedProviders = mapOf("groq" to listOf("k-groq", "https://api.groq.com/openai/v1", "", "", "")))

    @Test fun `plain model stays on the active provider`() {
        val (r, m) = ProviderRoute.route(s, "deepseek/deepseek-v4-flash")
        assertEquals("https://openrouter.ai/api/v1", r.baseUrl); assertEquals("k-or", r.apiKey); assertEquals("deepseek/deepseek-v4-flash", m)
    }

    @Test fun `prefixed model goes to the saved provider credentials`() {
        val (r, m) = ProviderRoute.route(s, "groq::llama-3.3-70b")
        assertEquals("https://api.groq.com/openai/v1", r.baseUrl); assertEquals("k-groq", r.apiKey); assertEquals("llama-3.3-70b", m)
        assertEquals("Groq · llama-3.3-70b", ProviderRoute.label("groq::llama-3.3-70b"))
    }

    @Test fun `unknown prefix or model ids with colons are not misrouted`() {
        assertEquals(null to "foo::bar", ProviderRoute.split("foo::bar"))
        assertEquals(null to "qwen/qwen3:free", ProviderRoute.split("qwen/qwen3:free"))
        assertEquals("openrouter::x", ProviderRoute.route(s, "openrouter::x").second.let { "openrouter::$it" })
    }
}
