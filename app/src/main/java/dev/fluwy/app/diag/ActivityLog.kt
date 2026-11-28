package dev.fluwy.app.diag

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Log central de ATIVIDADE (não só erros): cada ferramenta executada (com argumentos resumidos, status, duração e
 * começo do resultado), cada chamada de Web API (URL, etapa, tempo) e cada chamada ao modelo. Entra no
 * "diagnóstico completo" junto com um resumo por ferramenta, para achar padrões de falha e lentidão.
 * Segredos já chegam mascarados; conteúdo de tela de acessibilidade não é gravado.
 */
object ActivityLog {
    private const val FILE = "activity_log.txt"
    private const val MAX_BYTES = 1024 * 1024L
    private val lock = Any()
    @Volatile private var dir: File? = null
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun init(context: Context) { dir = context.applicationContext.filesDir }
    fun file(context: Context) = File(context.applicationContext.filesDir, FILE)

    private fun one(s: String, max: Int) = s.replace(Regex("\\s+"), " ").trim().take(max)

    fun write(kind: String, line: String, sessionId: String? = null) {
        val d = dir ?: return
        runCatching {
            synchronized(lock) {
                val f = File(d, FILE)
                f.appendText("[${stamp.format(Date())}] ${kind.padEnd(6)} ${sessionId?.let { "sess=${it.take(8)} " }.orEmpty()}$line\n")
                if (f.length() > MAX_BYTES) { val t = f.readText(); f.writeText(t.substring(t.length / 2).substringAfter('\n')) }
            }
        }
    }

    fun tool(sessionId: String, tool: String, args: String, error: Boolean, ms: Long, result: String) =
        write("tool", "${if (error) "ERRO" else "ok  "} ${tool} ${ms}ms · args=${one(args, 160)} → ${one(result, 260)}", sessionId)

    fun webApi(slug: String, ok: Boolean, ms: Long, url: String, detail: String) =
        write("webapi", "${if (ok) "ok  " else "ERRO"} $slug ${ms}ms · $url · ${one(detail, 260)}")

    fun llm(sessionId: String?, model: String, ok: Boolean, ms: Long, detail: String) =
        write("llm", "${if (ok) "ok  " else "ERRO"} $model ${ms}ms · ${one(detail, 200)}", sessionId)

    fun readAll(context: Context): String = runCatching { file(context).readText() }.getOrDefault("")
    fun clear(context: Context) { runCatching { file(context).delete() } }

    /** Resumo: por ferramenta, quantas execuções, quantas falharam e tempo médio — mostra onde estão os problemas. */
    fun summary(context: Context): String {
        val re = Regex("""\] (tool|webapi|llm)\s+(?:sess=\S+ )?(ok|ERRO)\s+(\S+) (\d+)ms""")
        data class Acc(var n: Int = 0, var err: Int = 0, var ms: Long = 0)
        val m = LinkedHashMap<String, Acc>()
        readAll(context).lineSequence().forEach { l ->
            val g = re.find(l)?.groupValues ?: return@forEach
            val a = m.getOrPut(g[1] + " · " + g[3]) { Acc() }
            a.n++; if (g[2] == "ERRO") a.err++; a.ms += g[4].toLongOrNull() ?: 0
        }
        return m.entries.sortedByDescending { it.value.err * 1000 + it.value.n }.joinToString("\n") { (k, a) ->
            "%-38s %4d exec · %3d falhas (%d%%) · média %d ms".format(Locale.ROOT, k, a.n, a.err, if (a.n == 0) 0 else a.err * 100 / a.n, if (a.n == 0) 0 else a.ms / a.n)
        }
    }
}
