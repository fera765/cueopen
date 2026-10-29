package dev.cueopen.app.trading

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalEvaluationAdapterTest {
    private val input = SignalInput("EURUSD", "call", "iqoption", 5, hasEvidence = true)

    @Test fun `detecta System One nativo somente nos provedores com rota dedicada`() {
        assertTrue(SignalEvaluationAdapter.usesSystemOne("typesafe/jev-router", "https://openrouter.ai/api/v1"))
        assertTrue(SignalEvaluationAdapter.usesSystemOne("jev-latest", "https://api.typesafe.ai/v1"))
        assertTrue(SignalEvaluationAdapter.usesSystemOne("jev-1.13", "https://opencode.ai/inference/go/openai/v1"))
        assertEquals("jev-1.13", SignalEvaluationAdapter.resolveModel("typesafe/jev-router", "https://opencode.ai/inference/go/openai/v1"))
        assertEquals("jev-1.13", SignalEvaluationAdapter.resolveModel("jev-latest", "https://opencode.ai/inference/go/openai/v1"))
        assertEquals("typesafe/jev-router", SignalEvaluationAdapter.resolveModel("typesafe/jev-router", "https://openrouter.ai/api/v1"))
        assertEquals("jev-1.13-free", SignalEvaluationAdapter.resolveModel("jev-1.13-free", "https://opencode.ai/inference/go/openai/v1"))
        assertFalse(SignalEvaluationAdapter.usesSystemOne("glm-5.3", "https://opencode.ai/inference/go/openai/v1"))
        assertFalse(SignalEvaluationAdapter.usesSystemOne("typesafe/jev-router", "https://example.com/v1"))
    }

    @Test fun `System One request tem state e pergunta Noul tipados`() {
        val body = SignalEvaluationAdapter.systemOneRequest(
            "typesafe/jev-router",
            """[{"id":"EURUSD","direction":"call","venue":"iqoption","horizon_minutes":5,"data":{"price":1.2}}]""",
            "indicadores reais",
            listOf(input),
        )!!
        assertEquals("typesafe/jev-router", body["model"]!!.jsonPrimitive.content)
        assertEquals("noul", body["questions"]!!.jsonObject["signal_0"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val state = Json.parseToJsonElement(body["state"]!!.jsonPrimitive.content).jsonObject
        assertTrue(state.containsKey("signals"))
        assertTrue(state.containsKey("broker_context"))
        val openCode = SignalEvaluationAdapter.systemOneRequest(
            "jev-1.13",
            """[{"id":"EURUSD","direction":"call","venue":"iqoption","horizon_minutes":5,"data":{"price":1.2}}]""",
            "",
            listOf(input),
        )!!
        assertEquals("jev-1.13", openCode["model"]!!.jsonPrimitive.content)
        assertTrue(openCode.containsKey("state"))
        assertTrue(openCode.containsKey("questions"))
    }

    @Test fun `Noul probability vira accuracy e confidence com minimo conservador`() {
        val parsed = SignalEvaluationAdapter.parseSystemOneResponse(
            """{"answers":{"signal_0":{"noul":0.82,"type":"noul"}}}""",
            listOf(input),
        )!!.single()
        assertEquals(82.0, parsed.accuracy, 0.0001)
        assertEquals(82.0, parsed.confidence, 0.0001)
        val uncertain = SignalEvaluationAdapter.parseSystemOneResponse(
            """{"answers":{"signal_0":{"noul":0.52,"type":"noul"}}}""",
            listOf(input),
        )!!.single()
        assertFalse(SignalEvaluationAdapter.confidenceAllowsApproval(uncertain.confidence))
        assertTrue(SignalEvaluationAdapter.confidenceAllowsApproval(parsed.confidence))
    }

    @Test fun `resposta System One ausente malformada ou fora de faixa e rejeitada`() {
        assertNull(SignalEvaluationAdapter.parseSystemOneResponse("{}", listOf(input)))
        assertNull(SignalEvaluationAdapter.parseSystemOneResponse("""{"answers":{"signal_0":{"noul":1.2,"type":"noul"}}}""", listOf(input)))
        assertNull(SignalEvaluationAdapter.parseSystemOneResponse("""{"answers":{"signal_0":{"choice":"yes","type":"choice"}}}""", listOf(input)))
    }

    @Test fun `LLM comum exige um resultado JSON completo por identificador`() {
        val raw = """[{"id":"EURUSD","accuracy":78,"confidence":74,"reason":"preço e candles informados"}]"""
        assertEquals(78.0, SignalEvaluationAdapter.parseGenericResponse(raw, listOf(input))!!.single().accuracy, 0.0)
        assertNull(SignalEvaluationAdapter.parseGenericResponse("Resposta: $raw", listOf(input)))
        assertNull(SignalEvaluationAdapter.parseGenericResponse("[]", listOf(input)))
    }

    @Test fun `contexto de erro ou sem mercado da corretora nao e evidencia`() {
        assertTrue(SignalEvaluationAdapter.hasUsableBrokerContext("Resumo técnico (35 indicadores, M1): sinais alinhados"))
        assertFalse(SignalEvaluationAdapter.hasUsableBrokerContext("[EURUSD] contexto da IQ Option indisponível: timeout"))
        assertFalse(SignalEvaluationAdapter.hasUsableBrokerContext("[EURUSD] ativo não encontrado na IQ Option."))
        assertFalse(SignalEvaluationAdapter.hasUsableBrokerContext("Gerenciamento: perdas seguidas 2"))
    }
}
