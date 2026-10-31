package dev.cueopen.app.data

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderModelsTest {
    @Test fun `catalogo OpenAI aceita data, metadata e parametros`() {
        val models = ProviderModels.parseCatalog(
            """{"object":"list","data":[{"id":"glm-5.3","object":"model","owned_by":"opencode"},{"id":"acme/agent","name":"Agent","context_length":64000,"top_provider":{"max_completion_tokens":8192},"pricing":{"prompt":"0.000001","completion":"0.000002"},"supported_parameters":["tools","json"]}]}"""
        )
        assertEquals(2, models.size)
        assertEquals("glm-5.3", models[0].id)
        assertEquals("Agent", models[1].name)
        assertEquals(64_000, models[1].context)
        assertEquals(8_192, models[1].maxCompletionTokens)
        assertEquals(1.0, models[1].promptPrice!!, 0.00001)
        assertEquals(2.0, models[1].completionPrice!!, 0.00001)
        assertTrue("tools" in models[1].supportedParameters)
    }

    @Test fun `catalogo aceita array ou campo models e ignora ids duplicados`() {
        val array = ProviderModels.parseCatalog("""[{"id":"a"},{"id":"a"},{"name":"sem-id"}]""")
        assertEquals(listOf("a"), array.map { it.id })
        assertEquals("b", ProviderModels.parseCatalog("""{"models":[{"id":"b"}]}""").single().id)
        assertTrue(ProviderModels.parseCatalog("not json").isEmpty())
        assertNull(array.single().promptPrice)
        val topProvider = ProviderModels.parseCatalog("""[{"id":"nested","top_provider":{"context_length":32000,"max_completion_tokens":4096}}]""").single()
        assertEquals(32_000, topProvider.context)
        assertEquals(4_096, topProvider.maxCompletionTokens)
    }

    @Test fun `OpenCode Go usa endpoint de modelos documentado fora do base OpenAI`() {
        assertEquals(
            "https://opencode.ai/inference/go/v1/models",
            ProviderModels.modelsEndpoint("https://opencode.ai/inference/go/openai/v1"),
        )
        assertEquals(
            "https://opencode.ai/inference/v1/models",
            ProviderModels.modelsEndpoint("https://opencode.ai/inference/openai/v1/"),
        )
        assertEquals("https://openrouter.ai/api/v1/models", ProviderModels.modelsEndpoint("https://openrouter.ai/api/v1"))
        assertEquals("http://localhost:11434/v1/models", ProviderModels.modelsEndpoint("http://localhost:11434/v1"))
        assertThrows(IOException::class.java) { ProviderModels.modelsEndpoint("http://example.com/v1") }
    }

    @Test fun `OpenCode Go omite familias que nao atendem Chat Completions`() {
        assertTrue(ProviderModels.supportsChatCompletions("https://opencode.ai/inference/go/openai/v1", "glm-5.3"))
        assertTrue(ProviderModels.supportsChatCompletions("https://openrouter.ai/api/v1", "gpt-6-luna"))
        assertEquals(false, ProviderModels.supportsChatCompletions("https://opencode.ai/inference/go/openai/v1", "gpt-6-luna"))
        assertEquals(false, ProviderModels.supportsChatCompletions("https://opencode.ai/inference/go/openai/v1", "claude-haiku-5-5"))
    }
}
