package dev.fluwy.app.llm

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeProtocolTest {
    private val goUrl = "https://opencode.ai/inference/go/openai/v1"

    @Test fun `usa exatamente o id persistido da conversa como sessao OpenCode`() {
        assertEquals("session-42", OpenCodeProtocol.sessionHeader(goUrl, " session-42 "))
        assertTrue(OpenCodeProtocol.userAgent(goUrl)!!.startsWith("Fluwy/"))
    }

    @Test(expected = IOException::class)
    fun `OpenCode Go rejeita chamada sem id de conversa`() {
        OpenCodeProtocol.sessionHeader(goUrl, null)
    }

    @Test fun `outros provedores nao recebem cabecalho OpenCode`() {
        assertNull(OpenCodeProtocol.sessionHeader("https://openrouter.ai/api/v1", null))
        assertNull(OpenCodeProtocol.userAgent("https://openrouter.ai/api/v1"))
    }
}
