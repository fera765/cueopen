package dev.cueopen.app.workflow

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** Fluxos e execuções em arquivos próprios (gravação atômica), fora do estado principal do app. */
object WorkflowStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private const val MAX_RUNS = 300

    @Volatile private var dir: File? = null
    private val _flows = MutableStateFlow<List<Workflow>>(emptyList())
    val flows: StateFlow<List<Workflow>> = _flows
    private val _runs = MutableStateFlow<List<WfRun>>(emptyList())
    val runs: StateFlow<List<WfRun>> = _runs

    @Synchronized fun init(filesDir: File) {
        if (dir != null) return
        val d = File(filesDir, "workflows").apply { mkdirs() }
        dir = d
        _flows.value = read(File(d, "workflows.json"), ListSerializer(Workflow.serializer()))
        // Execução que estava rodando quando o app foi encerrado não volta sozinha: fica registrada como interrompida.
        _runs.value = read(File(d, "runs.json"), ListSerializer(WfRun.serializer())).map {
            if (it.status == "running" || it.status == "waiting") it.copy(status = "cancelled", finishedAt = it.finishedAt ?: System.currentTimeMillis(),
                error = it.error.ifBlank { "Interrompida: o app foi encerrado durante a execução." }) else it
        }
    }

    private fun <T> read(f: File, s: kotlinx.serialization.KSerializer<List<T>>): List<T> =
        runCatching { if (f.isFile) json.decodeFromString(s, f.readText()) else emptyList() }.getOrDefault(emptyList())

    private fun <T> write(name: String, s: kotlinx.serialization.KSerializer<List<T>>, v: List<T>) {
        val d = dir ?: return
        runCatching {
            val tmp = File(d, "$name.tmp")
            tmp.writeText(json.encodeToString(s, v))
            if (!tmp.renameTo(File(d, name))) { File(d, name).writeText(tmp.readText()); tmp.delete() }
        }
    }

    fun get(id: String): Workflow? = _flows.value.firstOrNull { it.id == id }

    @Synchronized fun upsert(w: Workflow): Workflow {
        val v = w.copy(updatedAt = System.currentTimeMillis())
        _flows.update { list -> if (list.any { it.id == v.id }) list.map { if (it.id == v.id) v else it } else list + v }
        write("workflows.json", ListSerializer(Workflow.serializer()), _flows.value)
        return v
    }

    @Synchronized fun delete(id: String) {
        _flows.update { it.filterNot { w -> w.id == id } }
        _runs.update { it.filterNot { r -> r.workflowId == id } }
        write("workflows.json", ListSerializer(Workflow.serializer()), _flows.value)
        write("runs.json", ListSerializer(WfRun.serializer()), _runs.value)
    }

    @Synchronized fun putRun(r: WfRun) {
        _runs.update { list -> (if (list.any { it.id == r.id }) list.map { if (it.id == r.id) r else it } else list + r).takeLast(MAX_RUNS) }
        write("runs.json", ListSerializer(WfRun.serializer()), _runs.value)
    }

    fun runsOf(workflowId: String): List<WfRun> = _runs.value.filter { it.workflowId == workflowId }.sortedByDescending { it.startedAt }

    @Synchronized fun clearRuns(workflowId: String) {
        _runs.update { it.filterNot { r -> r.workflowId == workflowId && r.status != "running" && r.status != "waiting" } }
        write("runs.json", ListSerializer(WfRun.serializer()), _runs.value)
    }
}
