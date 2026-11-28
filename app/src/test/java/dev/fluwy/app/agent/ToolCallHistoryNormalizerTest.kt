package dev.fluwy.app.agent

import dev.fluwy.app.data.LlmMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallHistoryNormalizerTest {
    private fun assistant(vararg ids: Pair<String, String>) = LlmMessage(
        role = "assistant",
        content = null,
        toolCallsJson = ids.joinToString(prefix = "[", postfix = "]") { (id, name) ->
            """{"id":"$id","type":"function","function":{"name":"$name","arguments":"{}"}}"""
        },
    )

    private fun tool(id: String, name: String = "tool", content: String = "ok") =
        LlmMessage(role = "tool", content = content, toolCallId = id, name = name)

    @Test fun `multiplas chamadas tem exatamente um resultado por call id na ordem original`() {
        val input = listOf(
            LlmMessage("user", "faça duas coisas"),
            assistant("c1" to "first", "c2" to "second", "c3" to "third"),
            tool("c3", content = "r3"), tool("c1", content = "r1"), tool("c2", content = "r2"),
            LlmMessage("assistant", "pronto"),
        )
        val result = ToolCallHistoryNormalizer.normalize(input)
        assertEquals(listOf("user", "assistant", "c1", "c2", "c3", "assistant"), result.map { it.toolCallId ?: it.role })
        assertEquals(listOf("r1", "r2", "r3"), result.filter { it.role == "tool" }.map { it.content })
    }

    @Test fun `resultado parcial recebe marcador interruption sem afirmar que a ferramenta executou`() {
        val result = ToolCallHistoryNormalizer.normalize(listOf(
            assistant("c1" to "one", "c2" to "two"),
            tool("c1", content = "feito"),
            LlmMessage("user", "continue"),
        ))
        val tools = result.filter { it.role == "tool" }
        assertEquals(listOf("c1", "c2"), tools.map { it.toolCallId })
        assertEquals("feito", tools[0].content)
        assertTrue(tools[1].content!!.contains("interrupted/cancelled"))
        assertTrue(tools[1].content!!.contains("não assuma sucesso"))
        assertEquals("user", result.last().role)
    }

    @Test fun `cancelamento entre assistant tool calls e qualquer resultado fecha todas as chamadas`() {
        val result = ToolCallHistoryNormalizer.normalize(listOf(
            assistant("a" to "buy", "b" to "notify"),
            LlmMessage("assistant", "nova resposta após resume"),
        ))
        assertEquals(2, result.count { it.role == "tool" })
        assertTrue(result.filter { it.role == "tool" }.all { it.content!!.contains("interrupted/cancelled") })
        assertEquals(listOf("assistant", "tool", "tool", "assistant"), result.map { it.role })
    }

    @Test fun `replay e normalizacao repetida sao idempotentes e duplicatas nao viram nova execucao`() {
        val input = listOf(
            assistant("c1" to "one", "c1" to "duplicate"),
            tool("c1", content = "resultado original"),
            tool("c1", content = "resultado duplicado"),
            tool("orphan", content = "sem chamada correspondente"),
        )
        val first = ToolCallHistoryNormalizer.normalize(input)
        val second = ToolCallHistoryNormalizer.normalize(first)
        assertEquals(first, second)
        assertEquals(1, first.count { it.role == "tool" && it.toolCallId == "c1" })
        assertEquals("resultado original", first.single { it.role == "tool" }.content)
        assertFalse(first.any { it.toolCallId == "orphan" })
        val callIds = Json.parseToJsonElement(first.single { it.role == "assistant" }.toolCallsJson!!).jsonArray
            .map { it.jsonObject.getValue("id").jsonPrimitive.content }
        assertEquals(listOf("c1"), callIds)
    }

    @Test fun `corte de contexto nao deixa resultado orfao e fecha envelope parcial`() {
        val cutHistory = listOf(
            tool("call-from-truncated-away-prefix", content = "órfão após corte"),
            assistant("kept1" to "one", "kept2" to "two"),
            tool("kept1", content = "resultado já persistido"),
        )
        val result = ToolCallHistoryNormalizer.normalize(cutHistory)
        assertFalse(result.any { it.toolCallId == "call-from-truncated-away-prefix" })
        assertEquals(listOf("kept1", "kept2"), result.filter { it.role == "tool" }.map { it.toolCallId })
        assertTrue(result.last().content!!.contains("interrupted/cancelled"))
    }

    @Test fun `malformed call ids sao removidos em vez de inventar uma chamada ou resultado`() {
        val bad = LlmMessage("assistant", "texto", toolCallsJson = """[{"type":"function","function":{"name":"x","arguments":"{}"}}]""")
        val result = ToolCallHistoryNormalizer.normalize(listOf(bad, LlmMessage("user", "próximo")))
        assertEquals(2, result.size)
        assertEquals(null, result.first().toolCallsJson)
        assertFalse(result.any { it.role == "tool" })
    }

    @Test fun `argumento JSON truncado dentro de envelope valido e removido do historico`() {
        val truncated = """[{"id":"broken","type":"function","function":{"name":"shell","arguments":"{\"command\":\"echo\""}}]"""
        val callElement = Json.parseToJsonElement(truncated).jsonArray.single()
        assertNull(ToolCallHistoryNormalizer.canonicalizeToolCall(callElement))

        val normalized = ToolCallHistoryNormalizer.normalize(listOf(
            LlmMessage("assistant", null, toolCallsJson = truncated),
            tool("broken", content = "ERRO: argumentos descartados"),
            LlmMessage("user", "tente de novo"),
        ))

        assertEquals(listOf("user"), normalized.map { it.role })
        assertFalse(normalized.any { it.toolCallId == "broken" })
        assertFalse(normalized.joinToString { it.toolCallsJson.orEmpty() }.contains("command"))
    }

    @Test fun `argumentos validos aceitam string ou objeto e canonizam para string JSON`() {
        val raw = """[{"id":"string","type":"function","function":{"name":"lookup","arguments":"{\"nested\":{\"n\":1},\"label\":\"ação\"}"}},{"id":"object","type":"function","function":{"name":"lookup","arguments":{"enabled":true}}}]"""
        val normalized = ToolCallHistoryNormalizer.normalize(listOf(
            LlmMessage("assistant", null, toolCallsJson = raw),
            tool("string"), tool("object"),
        ))
        val calls = Json.parseToJsonElement(normalized.first { it.role == "assistant" }.toolCallsJson!!).jsonArray
        assertEquals(2, calls.size)
        val stringArgs = calls[0].jsonObject.getValue("function").jsonObject.getValue("arguments").jsonPrimitive.content
        val objectArgs = calls[1].jsonObject.getValue("function").jsonObject.getValue("arguments").jsonPrimitive.content
        assertEquals("1", Json.parseToJsonElement(stringArgs).jsonObject.getValue("nested").jsonObject.getValue("n").jsonPrimitive.content)
        assertEquals("true", Json.parseToJsonElement(objectArgs).jsonObject.getValue("enabled").jsonPrimitive.content)
        assertEquals(listOf("ok", "ok"), normalized.filter { it.role == "tool" }.map { it.content })
    }
}
