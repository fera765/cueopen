package dev.cueopen.app.llm

import dev.cueopen.app.data.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OAuthRefreshTest {
    @Test fun `refresh only near expiry and only with refresh token`() {
        assertTrue(OAuthRefresh.needsRefresh(listOf("r", "1000"), 0))
        assertFalse(OAuthRefresh.needsRefresh(listOf("r", (OAuthRefresh.MARGIN_MS * 3).toString()), 0))
        assertFalse(OAuthRefresh.needsRefresh(listOf("", "1000"), 0))
        assertFalse(OAuthRefresh.needsRefresh(null, 0))
    }

    @Test fun `tokens become the provider key active or not`() {
        val s = Settings(activeProvider = "openrouter", baseUrl = "https://openrouter.ai/api/v1", apiKey = "or")
        val t = DeviceFlow.Tokens("hf_tok", "r1", 99L)
        val a = OAuthRefresh.saveTokens(s, "huggingface", t)
        assertEquals("hf_tok", a.savedProviders["huggingface"]!![0]); assertEquals("or", a.apiKey)
        assertEquals(listOf("r1", "99"), a.oauthTokens["huggingface"])
        assertEquals("hf_tok", OAuthRefresh.saveTokens(s.copy(activeProvider = "huggingface"), "huggingface", t).apiKey)
    }
}
