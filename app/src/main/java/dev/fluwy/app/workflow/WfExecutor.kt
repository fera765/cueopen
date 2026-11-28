package dev.fluwy.app.workflow

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Ganchos do executor para o motor (persistência, pausa). Sem Android: o executor é testável na JVM. */
interface WfExecHooks {
    /** Antes de cada nó (ponto de pausa). */
    suspend fun beforeNode() {}
    /** Um passo começou ([replace]=false) ou terminou ([replace]=true, substitui o último). */
    fun step(step: WfStep, replace: Boolean) {}
    fun status(status: String) {}
}

data class WfExecResult(
    val ok: Boolean,
    val error: String = "",
    val failedNodeId: String? = null,
    val outputs: Map<String, List<WfItem>> = emptyMap(),
    val steps: List<WfStep> = emptyList(),
)

/**
 * Executor no estilo n8n v1: parte do gatilho, ordena os nós alcançáveis (topológica; empate pela posição no canvas),
 * e cada nó roda uma vez com todos os itens que chegaram pelas suas entradas — por item quando o tipo é por item.
 * Nó sem itens de entrada não roda (ramo não seguido). Por nó: retentativas, espera entre elas, tempo máximo,
 * desativado (repassa os itens), dados fixados (pin, só em teste/manual) e "continuar em caso de erro" (o erro vira item
 * na porta "error", ou na principal se "error" não estiver ligada).
 */
class WfExecutor(
    private val w: Workflow,
    private val run: WfRun,
    private val registry: (String) -> WfNodeExec?,
    private val hooks: WfExecHooks = object : WfExecHooks {},
    private val legacyVars: Map<String, String> = emptyMap(),
    private val now: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val labels = w.nodes.associate { it.id to WfLogic.label(it) }
    private val byId = w.nodes.associateBy { it.id }

    suspend fun run(startId: String, startItems: List<WfItem> = listOf(WfItem.EMPTY), until: String? = null, usePins: Boolean = false,
                    seed: Map<String, List<WfItem>> = emptyMap(), triggerId: String? = null): WfExecResult {
        val outputs = LinkedHashMap<String, List<WfItem>>(seed)
        val steps = mutableListOf<WfStep>()
        val (order, edges) = plan(w, startId, until)
        val inbox = HashMap<String, MutableList<WfItem>>()
        val firstParent = HashMap<String, String>()
        inbox[startId] = startItems.toMutableList()
        for (id in order) {
            val node = byId[id] ?: continue
            val input = inbox[id].orEmpty()
            if (id != startId && input.isEmpty()) continue
            hooks.beforeNode()
            val t0 = now()
            val running = WfStep(id, labels.getValue(id), node.type, "running", t0, input = WfItems.toText(input).take(10_000), inputItems = input.size, items = 0)
            hooks.step(running, false)
            val expr = WfExprCtx(input.firstOrNull() ?: WfItem.EMPTY, 0, input, outputs.toMap(), labels, run.id,
                if (run.trigger == "test" || run.trigger == "manual") "test" else "production", w.id, w.name, w.enabled, triggerId ?: startId, firstParent[id], legacyVars, t0)
            val r = runNode(node, input, WfNodeCtx(w, run, node, expr) { hooks.status(it) }, usePins)
            val outItems = r.ports.values.flatten()
            val step = running.copy(status = if (r.fatal == null) "ok" else "error", finishedAt = now(), output = WfItems.toText(outItems).take(20_000),
                error = (r.fatal ?: r.softError).orEmpty().take(1_500), sessionId = r.sessionId, items = outItems.size, attempts = r.attempts, pinned = r.pinned)
            steps += step
            hooks.step(step, true)
            if (r.fatal != null) return WfExecResult(false, "${labels[id]}: ${r.fatal}", id, outputs, steps)
            outputs[id] = outItems
            for (e in edges.filter { it.from == id }) {
                val items = if (e.port == "main") r.ports["main"] ?: r.ports.filterKeys { it != "error" }.values.flatten() else r.ports[e.port].orEmpty()
                if (items.isNotEmpty()) { inbox.getOrPut(e.to) { mutableListOf() } += items; firstParent.putIfAbsent(e.to, id) }
            }
        }
        return WfExecResult(true, outputs = outputs, steps = steps)
    }

    private class NodeRun(val ports: Map<String, List<WfItem>>, val fatal: String? = null, val softError: String? = null,
                          val sessionId: String? = null, val attempts: Int = 1, val pinned: Boolean = false)

    private suspend fun runNode(node: WfNode, input: List<WfItem>, ctx: WfNodeCtx, usePins: Boolean): NodeRun {
        val s = node.settings
        if (s.disabled) return NodeRun(mapOf("main" to input), softError = "Nó desativado: itens repassados sem executar.")
        if (usePins) WfItems.parsePinned(s.pinData)?.let { return NodeRun(mapOf("main" to it), pinned = true) }
        val exec = registry(node.type) ?: return NodeRun(emptyMap(), fatal = "Tipo de nó desconhecido: ${node.type}")
        val units: List<Pair<List<WfItem>, Int>> = if (exec.perItem) input.ifEmpty { listOf(WfItem.EMPTY) }.mapIndexed { i, it -> listOf(it) to i } else listOf(input to 0)
        val ports = LinkedHashMap<String, MutableList<WfItem>>()
        var firstError: String? = null
        var session: String? = null
        var maxAttempts = 1
        for ((items, index) in units) {
            var attempts = 0
            var res: WfNodeOutput
            val tries = s.retries.coerceIn(0, 10)
            while (true) {
                attempts++
                res = attempt(node, s.timeoutSec) { if (exec.perItem) exec.executeItem(items[0], index, ctx) else exec.execute(items, ctx) }
                if (res.error == null || attempts > tries) break
                sleep(s.retryWaitMs.coerceIn(0, 600_000))
            }
            maxAttempts = maxOf(maxAttempts, attempts)
            session = res.sessionId ?: session
            res.ports.forEach { (p, l) -> ports.getOrPut(p) { mutableListOf() } += l }
            val err = res.error ?: continue
            if (!s.continueOnFail) return NodeRun(ports, fatal = err + if (attempts > 1) " (após $attempts tentativas)" else "", sessionId = session, attempts = maxAttempts)
            firstError = firstError ?: err
            val errPort = if (w.edges.any { it.from == node.id && it.port == "error" }) "error" else "main"
            ports.getOrPut(errPort) { mutableListOf() } += items.ifEmpty { listOf(WfItem.EMPTY) }.map { withError(it, err, res.errorData) }
        }
        return NodeRun(ports, softError = firstError?.let { "Erro ignorado (continuar em caso de erro): $it" }, sessionId = session, attempts = maxAttempts)
    }

    private suspend fun attempt(node: WfNode, timeoutSec: Int, block: suspend () -> WfNodeOutput): WfNodeOutput = try {
        if (timeoutSec > 0) withTimeoutOrNull(timeoutSec * 1_000L) { block() } ?: WfNodeOutput.fail("Passou do tempo máximo do nó ($timeoutSec s).")
        else block()
    } catch (c: CancellationException) { throw c } catch (e: Throwable) {
        WfNodeOutput.fail((e.message ?: e.javaClass.simpleName).take(1_500))
    }

    private fun withError(item: WfItem, err: String, data: JsonObject?): WfItem =
        WfItem(JsonObject(item.json + (data ?: JsonObject(emptyMap())) + ("error" to JsonPrimitive(err))))

    companion object {
        /**
         * Plano de execução: nós alcançáveis a partir de [startId] em ordem topológica (ligações de volta — ciclos — são
         * ignoradas, então cada nó roda no máximo uma vez). Com [until], só o nó-alvo e o que vem antes dele.
         */
        fun plan(w: Workflow, startId: String, until: String? = null): Pair<List<String>, List<WfEdge>> {
            val ids = w.nodes.map { it.id }.toSet()
            val out = w.edges.filter { it.from in ids && it.to in ids }.groupBy { it.from }
            val reach = LinkedHashSet<String>(); val onStack = HashSet<String>(); val kept = mutableListOf<WfEdge>()
            fun dfs(id: String) {
                reach += id; onStack += id
                out[id].orEmpty().forEach { e ->
                    if (e.to in onStack) return@forEach
                    kept += e
                    if (e.to !in reach) dfs(e.to)
                }
                onStack -= id
            }
            if (startId in ids) dfs(startId)
            var nodes: Set<String> = reach
            var edges = kept.distinctBy { it.id }
            if (until != null && until in reach) {
                val anc = HashSet<String>(); val q = ArrayDeque(listOf(until))
                while (q.isNotEmpty()) { val x = q.removeFirst(); if (anc.add(x)) edges.filter { it.to == x }.forEach { q.add(it.from) } }
                nodes = reach.filter { it in anc }.toSet()
                edges = edges.filter { it.from in nodes && it.to in nodes }
            }
            val pos = w.nodes.associate { it.id to (it.y to it.x) }
            val indeg = nodes.associateWith { n -> edges.count { it.to == n } }.toMutableMap()
            val order = mutableListOf<String>()
            val ready = nodes.filter { indeg[it] == 0 }.toMutableList()
            while (ready.isNotEmpty()) {
                val next = ready.minWith(compareBy({ if (it == startId) 0 else 1 }, { pos[it]?.second ?: 0f }, { pos[it]?.first ?: 0f }))
                ready.remove(next); order += next
                edges.filter { it.from == next }.forEach { e -> indeg[e.to] = indeg.getValue(e.to) - 1; if (indeg[e.to] == 0) ready += e.to }
            }
            return order to edges
        }
    }
}
