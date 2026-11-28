package dev.fluwy.app.workflow

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WfExecutorTest {
    private var flakyCalls = 0
    private val registry: Map<String, WfNodeExec> = listOf(
        WfItemNode("trigger_manual") { NodeResult("""{"list":[{"n":1},{"n":2},{"n":3}],"word":"oi"}""") },
        WfItemNode("set", fn = ::nodeSet), WfItemNode("if", fn = ::nodeIf), WfSplitNode(), WfAggregateNode(),
        WfItemNode("http") { c -> flakyCalls++; if (c.r("fail").toIntOrNull()?.let { flakyCalls <= it } == true) NodeResult("""{"status":500}""", error = "HTTP 500") else NodeResult("""{"ok":true}""") },
        WfItemNode("shell") { delay(5_000); NodeResult("tarde") },
    ).associateBy { it.type }

    private fun n(id: String, type: String, vararg cfg: Pair<String, String>, s: WfNodeSettings = WfNodeSettings(), x: Float = 0f) =
        WfNode(id = id, type = type, label = id, x = x, config = mapOf(*cfg), settings = s)
    private fun e(a: String, b: String, port: String = "main") = WfEdge(from = a, to = b, port = port)
    private fun exec(w: Workflow, until: String? = null, pins: Boolean = false) = runBlocking {
        WfExecutor(w, WfRun(id = "r1", workflowId = w.id, trigger = "manual"), { registry[it] }, sleep = {}).run("t", until = until, usePins = pins)
    }
    private fun out(r: WfExecResult, id: String) = r.outputs[id].orEmpty()
    private fun field(i: WfItem, k: String) = i.json[k]?.jsonPrimitive?.content

    private val t = n("t", "trigger_manual")

    @Test fun itemsFlowPerItem() {
        val w = Workflow(nodes = listOf(t, n("sp", "split_out", "field" to "{{ \$json.list }}", x = 1f), n("s", "set", "values" to "dobro = {{ \$json.n }}{{ \$json.n }}\nidx = {{ \$itemIndex }}", x = 2f),
            n("ag", "aggregate", x = 3f)), edges = listOf(e("t", "sp"), e("sp", "s"), e("s", "ag")))
        val r = exec(w)
        assertTrue(r.error, r.ok)
        assertEquals(3, out(r, "sp").size)
        assertEquals(listOf("11", "22", "33"), out(r, "s").map { field(it, "dobro") })
        assertEquals(listOf("0", "1", "2"), out(r, "s").map { field(it, "idx") })
        assertEquals("3", field(out(r, "ag").single(), "count"))
        val st = r.steps.first { it.nodeId == "s" }
        assertEquals(3, st.inputItems); assertEquals(3, st.items)
        assertEquals(3, WfItems.fromStep(st.output, st.items).size)
    }

    @Test fun branchingRoutesItemsByPort() {
        val w = Workflow(nodes = listOf(t, n("sp", "split_out", "field" to "{{ \$json.list }}"), n("if", "if", "left" to "{{ \$json.n }}", "op" to "gt", "right" to "1"),
            n("yes", "set", "values" to "v = sim {{ \$json.n }}"), n("no", "set", "values" to "v = não {{ \$json.n }}"), n("never", "set", "values" to "v = x")),
            edges = listOf(e("t", "sp"), e("sp", "if"), e("if", "yes", "true"), e("if", "no", "false")))
        val r = exec(w)
        assertEquals(listOf("sim 2", "sim 3"), out(r, "yes").map { field(it, "v") })
        assertEquals(listOf("não 1"), out(r, "no").map { field(it, "v") })
        assertFalse(r.outputs.containsKey("never"))
    }

    @Test fun branchWithoutItemsIsSkipped() {
        val w = Workflow(nodes = listOf(t, n("if", "if", "left" to "{{ \$json.word }}", "op" to "equals", "right" to "oi"), n("no", "set", "values" to "a = 1"), n("after", "set", "values" to "b = 2")),
            edges = listOf(e("t", "if"), e("if", "no", "false"), e("no", "after")))
        val r = exec(w)
        assertTrue(r.ok); assertFalse(r.outputs.containsKey("no")); assertFalse(r.outputs.containsKey("after"))
    }

    @Test fun retrySucceeds() {
        val w = Workflow(nodes = listOf(t, n("h", "http", "fail" to "2", s = WfNodeSettings(retries = 2, retryWaitMs = 10))), edges = listOf(e("t", "h")))
        val r = exec(w)
        assertTrue(r.error, r.ok)
        assertEquals(3, r.steps.last().attempts)
        assertEquals("true", field(out(r, "h").single(), "ok"))
    }

    @Test fun retryExhaustedStops() {
        val w = Workflow(nodes = listOf(t, n("h", "http", "fail" to "9", s = WfNodeSettings(retries = 1)), n("m", "set", "values" to "a = 1")), edges = listOf(e("t", "h"), e("h", "m")))
        val r = exec(w)
        assertFalse(r.ok); assertEquals("h", r.failedNodeId)
        assertTrue(r.error, r.error.contains("2 tentativas"))
        assertFalse(r.outputs.containsKey("m"))
        assertEquals("error", r.steps.last().status)
    }

    @Test fun continueOnFailUsesErrorPort() {
        val w = Workflow(nodes = listOf(t, n("h", "http", "fail" to "9", s = WfNodeSettings(continueOnFail = true)), n("ok", "set", "values" to "a = 1"),
            n("err", "set", "values" to "msg = {{ \$json.error }} {{ \$json.status }}")), edges = listOf(e("t", "h"), e("h", "ok"), e("h", "err", "error")))
        val r = exec(w)
        assertTrue(r.ok)
        assertFalse(r.outputs.containsKey("ok"))
        assertEquals("HTTP 500 500", field(out(r, "err").single(), "msg"))
        assertTrue(r.steps.first { it.nodeId == "h" }.error.contains("HTTP 500"))
    }

    @Test fun continueOnFailWithoutErrorPortGoesToMain() {
        val w = Workflow(nodes = listOf(t, n("h", "http", "fail" to "9", s = WfNodeSettings(continueOnFail = true)), n("ok", "set", "values" to "e = {{ \$json.error }}")),
            edges = listOf(e("t", "h"), e("h", "ok")))
        assertEquals("HTTP 500", field(out(exec(w), "ok").single(), "e"))
    }

    @Test fun timeout() {
        val w = Workflow(nodes = listOf(t, n("sh", "shell", s = WfNodeSettings(timeoutSec = 1))), edges = listOf(e("t", "sh")))
        val r = exec(w)
        assertFalse(r.ok); assertTrue(r.error, r.error.contains("tempo máximo"))
    }

    @Test fun disabledPassesThroughAndPinReplaces() {
        val w = Workflow(nodes = listOf(t, n("h", "http", "fail" to "9", s = WfNodeSettings(disabled = true)),
            n("p", "set", "values" to "a = 1", s = WfNodeSettings(pinData = """[{"fixo":"sim"},{"fixo":"também"}]"""))), edges = listOf(e("t", "h"), e("h", "p")))
        val r = exec(w, pins = true)
        assertTrue(r.error, r.ok)
        assertEquals("oi", field(out(r, "h").single(), "word"))
        assertEquals(listOf("sim", "também"), out(r, "p").map { field(it, "fixo") })
        assertTrue(r.steps.last().pinned)
        // Sem pin (execução real), o nó roda de verdade.
        assertEquals("1", field(out(exec(w, pins = false), "p").single(), "a"))
    }

    @Test fun partialExecutionStopsAtTarget() {
        val w = Workflow(nodes = listOf(t, n("a", "set", "values" to "a = 1"), n("b", "set", "values" to "b = {{ \$json.a }}"), n("c", "set", "values" to "c = 3"), n("side", "set", "values" to "s = 1")),
            edges = listOf(e("t", "a"), e("a", "b"), e("b", "c"), e("t", "side")))
        val r = exec(w, until = "b")
        assertEquals(setOf("t", "a", "b"), r.outputs.keys)
        assertEquals("1", field(out(r, "b").single(), "b"))
    }

    @Test fun cyclesRunEachNodeOnce() {
        val w = Workflow(nodes = listOf(t, n("a", "set", "values" to "a = 1"), n("b", "set", "values" to "b = 2")), edges = listOf(e("t", "a"), e("a", "b"), e("b", "a")))
        val r = exec(w)
        assertTrue(r.ok); assertEquals(listOf("t", "a", "b"), r.steps.map { it.nodeId })
    }

    @Test fun mergeWaitsForAllParents() {
        val w = Workflow(nodes = listOf(t, n("a", "set", "values" to "v = a", x = 1f), n("b", "set", "values" to "v = b", x = 1f), n("m", "aggregate", x = 2f)),
            edges = listOf(e("t", "a"), e("t", "b"), e("a", "m"), e("b", "m")))
        val r = exec(w)
        assertEquals("2", field(out(r, "m").single(), "count"))
        assertEquals(listOf("t", "a", "b", "m"), r.steps.map { it.nodeId })
    }

    @Test fun nodeReferencesInExpressions() {
        val w = Workflow(nodes = listOf(t, n("a", "set", "values" to "x = 10"), n("b", "set", "values" to "y = {{ \$node[\"a\"].json.x }}-{{ \$('t').item.json.word }}-{{a.x}}")),
            edges = listOf(e("t", "a"), e("a", "b")))
        assertEquals("10-oi-10", field(out(exec(w), "b").single(), "y"))
    }

    @Test fun unknownTypeFails() {
        val w = Workflow(nodes = listOf(t, n("z", "nao_existe")), edges = listOf(e("t", "z")))
        assertTrue(exec(w).error.contains("desconhecido"))
        assertEquals(JsonPrimitive(1), Json.parseToJsonElement("1"))
    }
}
