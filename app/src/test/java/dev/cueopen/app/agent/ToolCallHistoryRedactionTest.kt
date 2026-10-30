package dev.cueopen.app.agent

import dev.cueopen.app.data.LlmMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallHistoryRedactionTest {
    private val raw = """[{"id":"call-1","type":"function","function":{"name":"device_accessibility_type","arguments":"{\"snapshot_id\":\"snap-a\",\"element_id\":4,\"text\":\"raw-private-text\"}"}}]"""

    @Test fun `texto digitado e removido sem perder ids ou alvo do campo`() {
        val sanitized = ToolCallHistoryNormalizer.redactSensitiveToolArguments(raw)
        assertNotNull(sanitized)
        assertFalse(sanitized!!.contains("raw-private-text"))
        assertTrue(sanitized.contains("[oculto]"))
        val call = Json.parseToJsonElement(sanitized).jsonArray.single().jsonObject
        assertEquals("call-1", call.getValue("id").jsonPrimitive.content)
        val function = call.getValue("function").jsonObject
        assertEquals("device_accessibility_type", function.getValue("name").jsonPrimitive.content)
        val args = Json.parseToJsonElement(function.getValue("arguments").jsonPrimitive.content).jsonObject
        assertEquals("snap-a", args.getValue("snapshot_id").jsonPrimitive.content)
        assertEquals("4", args.getValue("element_id").jsonPrimitive.content)
        assertEquals("[oculto]", args.getValue("text").jsonPrimitive.content)
    }

    @Test fun `normalizacao tambem limpa argumentos antigos antes de reenviar historico`() {
        val normalized = ToolCallHistoryNormalizer.normalize(listOf(
            LlmMessage("assistant", null, toolCallsJson = raw),
            LlmMessage("tool", "Campo preenchido", toolCallId = "call-1", name = "device_accessibility_type"),
        ))
        val calls = normalized.first { it.role == "assistant" }.toolCallsJson!!
        assertFalse(calls.contains("raw-private-text"))
        assertTrue(calls.contains("[oculto]"))
        assertEquals("Campo preenchido", normalized.last().content)
    }
}
