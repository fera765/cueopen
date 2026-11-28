package dev.fluwy.app.workflow

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Calendar

class WfExprTest {
    private fun item(json: String) = WfItems.fromOutput(json)
    private val now = Calendar.getInstance().apply { set(2026, Calendar.MARCH, 5, 14, 7, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    private val ctx = WfExprCtx(
        item = item("""{"name":"Ana","tags":["a","b"],"n":3,"addr":{"city":"SP"},"a b":"x"}"""),
        nodeItems = mapOf(
            "t1" to listOf(item("""{"payload":{"chat":"5511@c.us","text":"oi"}}""")),
            "h1" to listOf(item("""{"status":200,"data":{"items":[{"t":"x"},{"t":"y"}]}}""")),
            "a1" to listOf(item("texto puro da IA")),
        ),
        labels = mapOf("t1" to "Gatilho", "h1" to "Buscar", "a1" to "Resumo"),
        executionId = "ex123", workflowId = "wf1", workflowName = "Meu fluxo", triggerNodeId = "t1", now = now,
    )
    private fun r(t: String) = WfExpr.render(t, ctx)

    @Test fun json() {
        assertEquals("Ana", r("{{ \$json.name }}"))
        assertEquals("SP", r("{{\$json.addr.city}}"))
        assertEquals("b", r("{{ \$json.tags[1] }}"))
        assertEquals("b", r("{{ \$json.tags[-1] }}"))
        assertEquals("x", r("{{ \$json[\"a b\"] }}"))
        assertEquals("Olá Ana!", r("Olá {{ \$json.name }}!"))
        assertEquals("", r("{{ \$json.nada.mais }}"))
    }

    @Test fun nodes() {
        assertEquals("200", r("{{ \$node[\"Buscar\"].json.status }}"))
        assertEquals("y", r("{{ \$node[\"buscar\"].json.data.items[1].t }}"))
        assertEquals("x", r("{{ \$('Buscar').item.json.data.items[0].t }}"))
        assertEquals("x", r("{{ \$('Buscar').first().json.data.items.first().t }}"))
        assertEquals("texto puro da IA", r("{{ \$node[\"Resumo\"].json }}"))
        assertEquals("oi", r("{{ \$trigger.json.payload.text }}"))
        assertEquals("", r("{{ \$node[\"Inexistente\"].json }}"))
    }

    @Test fun builtins() {
        assertEquals("ex123", r("{{ \$execution.id }}"))
        assertEquals("Meu fluxo", r("{{ \$workflow.name }}"))
        assertEquals("2026-03-05 14:07", r("{{ \$now.toFormat('yyyy-MM-dd HH:mm') }}"))
        assertEquals("06/03", r("{{ \$now.plus(1, 'days').toFormat('dd/MM') }}"))
        assertEquals("00:00", r("{{ \$today.toFormat('HH:mm') }}"))
        assertEquals("0", r("{{ \$itemIndex }}"))
        assertEquals("Ana", r("{{ \$input.first().json.name }}"))
    }

    @Test fun operatorsAndMethods() {
        assertEquals("ANA", r("{{ \$json.name.toUpperCase() }}"))
        assertEquals("a, b", r("{{ \$json.tags.join(', ') }}"))
        assertEquals("2", r("{{ \$json.tags.length }}"))
        assertEquals("padrão", r("{{ \$json.nada || 'padrão' }}"))
        assertEquals("Ana", r("{{ \$json.name ?? 'x' }}"))
        assertEquals("5", r("{{ \$json.n + 2 }}"))
        assertEquals("Ana Silva", r("{{ \$json.name + ' Silva' }}"))
        assertEquals("true", r("{{ \$json.name.includes('n') }}"))
        assertEquals("An", r("{{ \$json.name.slice(0, 2) }}"))
    }

    @Test fun legacyStillWorks() {
        assertEquals("Ana", r("{{prev.name}}"))
        assertEquals("200", r("{{h1.status}}"))
        assertEquals("200", r("{{Buscar.status}}"))
        assertEquals("5511@c.us", r("{{trigger.payload.chat}}"))
        assertEquals("vazio", r("{{prev.nada | default:vazio}}"))
        assertEquals("200 / Ana", r("{{h1.status}} / {{ \$json.name }}"))
    }

    @Test fun safeNoEval() {
        assertEquals("", r("{{ \$json.constructor.constructor('return 1')() }}"))
        listOf("java.lang.System.exit(0)", "\$json.name; 1", "\$json.name = 1", "\$json.x ? 1 : 2").forEach { e ->
            try { WfExpr.eval(e, ctx); fail("deveria recusar: $e") } catch (_: WfExprError) {}
        }
    }

    @Test fun singlePass() {
        val c = WfExprCtx(item = WfItems.fromOutput("""{"t":"{{ ${'$'}execution.id }} {{prev}}"}"""), executionId = "zz")
        assertEquals("{{ \$execution.id }} {{prev}}", WfExpr.render("{{ \$json.t }}", c))
    }

    @Test fun valueKeepsType() {
        val v = WfExpr.value("{{ \$node[\"Buscar\"].json.data.items }}", ctx)
        assertTrue(v is JsonArray); assertEquals(2, (v as JsonArray).size)
        assertTrue(WfExpr.value("{{ \$json.addr }}", ctx) is JsonObject)
        assertEquals("\"x Ana\"", WfExpr.value("x {{ \$json.name }}", ctx).toString())
    }

    @Test fun emptyRefs() {
        assertEquals(listOf("{{ \$json.nada }}"), WfExpr.emptyRefs("a {{ \$json.nada }} {{ \$json.name }} {{ \$json.x || 'y' }}", ctx))
    }

    @Test fun staticCheck() {
        val w = Workflow(nodes = listOf(WfNode(id = "t", type = "trigger_manual", label = "Início"), WfNode(id = "m", type = "notify", config = mapOf("text" to "{{ \$node[\"Nada\"].json }}"))),
            edges = listOf(WfEdge(from = "t", to = "m")))
        assertTrue(WfLogic.problems(w).joinToString(), WfLogic.problems(w).any { it.contains("não existe") })
        val ok = w.copy(nodes = listOf(w.nodes[0], w.nodes[1].copy(config = mapOf("text" to "{{ \$node[\"Início\"].json.time }} {{ \$json.x }}"))))
        assertTrue(WfLogic.problems(ok).joinToString(), WfLogic.problems(ok).none { it.contains("{{") })
        assertTrue(Json.parseToJsonElement("{}") is JsonObject)
    }
}
