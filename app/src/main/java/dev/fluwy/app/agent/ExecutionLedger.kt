package dev.fluwy.app.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Registro de execução do orquestrador: cada rodada de trabalho de uma sessão vira uma execução com id, tarefa,
 * objetivo, ferramentas autorizadas, operações feitas (com duração e resultado), estado e conclusão. Serve para:
 *  - reaproveitar resultados de ferramentas de leitura idempotentes dentro da mesma execução (sem repetir chamadas);
 *  - mostrar ao usuário o que foi feito (menu da conversa › Execuções);
 *  - manter o histórico entre reinícios do app (arquivo .fluwy/runs.json no workspace da sessão).
 */
object ExecutionLedger {
    @Serializable
    data class Op(val at: Long, val tool: String, val argsHash: String, val ok: Boolean, val ms: Long, val summary: String, val reused: Boolean = false)

    @Serializable
    data class Run(
        val runId: String, val sessionId: String, val taskId: String, val goal: String,
        val allowedTools: List<String> = emptyList(), val state: String = "RUNNING",
        val startedAt: Long, val endedAt: Long? = null, val result: String = "", val ops: List<Op> = emptyList(),
    )

    /** Ferramentas de leitura cujo resultado pode ser reaproveitado na mesma execução (mesmos argumentos). */
    val IDEMPOTENT = setOf("web_fetch", "web_search", "conectores_listar", "conector_acoes", "memory_search", "file_search", "trigger_list", "team_status")
    private const val MAX_RUNS = 20
    private const val MAX_OPS = 300
    private val json = Json { ignoreUnknownKeys = true }
    private val live = java.util.concurrent.ConcurrentHashMap<String, Run>()
    private val cache = java.util.concurrent.ConcurrentHashMap<String, String>()  // "$runId|$tool|$hash" → saída

    @Volatile var dirResolver: ((String) -> File)? = null

    private fun file(sessionId: String): File? = dirResolver?.invoke(sessionId)?.let { File(it, ".fluwy/runs.json").apply { parentFile?.mkdirs() } }

    fun history(sessionId: String): List<Run> = runCatching {
        file(sessionId)?.takeIf { it.isFile }?.readText()?.let { json.decodeFromString(ListSerializer(Run.serializer()), it) }
    }.getOrNull().orEmpty().let { saved -> live[sessionId]?.let { cur -> saved.filterNot { it.runId == cur.runId } + cur } ?: saved }

    private fun persist(run: Run) = runCatching {
        val f = file(run.sessionId) ?: return@runCatching
        val list = (history(run.sessionId).filterNot { it.runId == run.runId } + run).takeLast(MAX_RUNS)
        f.writeText(json.encodeToString(ListSerializer(Run.serializer()), list))
    }

    fun hash(s: String): String = Integer.toHexString(s.hashCode()) + "_" + s.length

    /** Abre (ou retoma, se for a mesma tarefa) a execução da sessão. */
    fun begin(sessionId: String, taskId: String, goal: String, allowedTools: Collection<String> = emptyList()): Run {
        live[sessionId]?.takeIf { it.taskId == taskId && it.state == "RUNNING" }?.let { return it }
        val r = Run("run_" + java.util.UUID.randomUUID().toString().take(10), sessionId, taskId, goal.take(500), allowedTools.sorted().take(200), startedAt = System.currentTimeMillis())
        live[sessionId] = r; persist(r); return r
    }

    fun current(sessionId: String): Run? = live[sessionId]

    fun setAllowed(sessionId: String, tools: Collection<String>) { live[sessionId]?.let { if (it.allowedTools.isEmpty()) live[sessionId] = it.copy(allowedTools = tools.sorted().take(200)) } }

    fun cached(sessionId: String, tool: String, argsHash: String): String? {
        if (tool !in IDEMPOTENT) return null
        val r = live[sessionId] ?: return null
        return cache["${r.runId}|$tool|$argsHash"]
    }

    fun record(sessionId: String, tool: String, argsHash: String, ok: Boolean, ms: Long, output: String, reused: Boolean = false) {
        val r = live[sessionId] ?: return
        if (ok && !reused && tool in IDEMPOTENT) cache["${r.runId}|$tool|$argsHash"] = output
        val next = r.copy(ops = (r.ops + Op(System.currentTimeMillis(), tool, argsHash, ok, ms, output.replace(Regex("\\s+"), " ").take(160), reused)).takeLast(MAX_OPS))
        live[sessionId] = next
        if (next.ops.size % 5 == 0) persist(next)
    }

    /** Fecha a execução com o estado final da sessão. */
    fun end(sessionId: String, state: String, result: String) {
        val r = live[sessionId] ?: return
        val done = r.copy(state = state, endedAt = System.currentTimeMillis(), result = result.take(400))
        persist(done)
        if (state == "RUNNING") live[sessionId] = done else { live.remove(sessionId); cache.keys.removeIf { it.startsWith(r.runId + "|") } }
    }
}
