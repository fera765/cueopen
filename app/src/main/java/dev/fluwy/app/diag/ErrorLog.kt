package dev.fluwy.app.diag

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Registro de falhas do aplicativo, gravado em disco para o usuário baixar nas Configurações
 * e enviar para diagnóstico. Captura erros REAIS (ferramentas do agente, browser, terminal,
 * swaps, mercado, crashes) — nunca o conteúdo das conversas. Thread-safe e com tamanho limitado.
 */
object ErrorLog {
    private const val FILE = "error_log.jsonl"
    private const val LEGACY_FILE = "error_log.txt"
    private const val LAST_CRASH_FILE = "last_crash.txt"
    private const val MAX_BYTES = 768 * 1024L
    private const val DEDUPE_WINDOW_MS = 30_000L
    private val lock = Any()
    @Volatile private var filesDir: File? = null
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    private val recent = LinkedHashMap<String, Long>()

    /** Uma falha registrada. [count] > 1 quando a mesma falha se repetiu em sequência. */
    @kotlinx.serialization.Serializable
    data class Entry(
        val ts: Long,
        val source: String,
        val message: String,
        val type: String? = null,
        val stack: String? = null,
        val tool: String? = null,
        val session: String? = null,
        val count: Int = 1,
    ) {
        val time: String get() = synchronized(stamp) { stamp.format(Date(ts)) }
    }

    fun init(context: Context) {
        filesDir = context.applicationContext.filesDir
        runCatching { File(filesDir, LEGACY_FILE).delete() }
    }

    fun file(context: Context): File = File(context.applicationContext.filesDir, FILE)
    fun lastCrashFile(context: Context): File = File(context.applicationContext.filesDir, LAST_CRASH_FILE)

    /**
     * Registra uma falha. [source] é a área (ex.: "agent", "browser", "sandbox", "llm", "workflow", "crash");
     * [detail] é a mensagem real. Segredos conhecidos são mascarados; o conteúdo das conversas não entra.
     */
    fun log(source: String, detail: String, sessionId: String? = null, tool: String? = null) {
        if (detail.isBlank()) return
        val limit = if (source == "browser") 6000 else 2000
        val (message, stack) = splitStack(redact(detail.trim()))
        write(Entry(System.currentTimeMillis(), source, message.take(limit), null, stack?.take(4000), tool, sessionId?.take(8)))
    }

    /** Registra uma exceção com tipo e stack trace. */
    fun log(source: String, error: Throwable, context: String? = null, sessionId: String? = null, tool: String? = null) {
        val msg = listOfNotNull(context, error.message).joinToString(": ").ifBlank { error::class.java.simpleName }
        write(Entry(System.currentTimeMillis(), source, redact(msg).take(2000), error::class.java.name,
            redact(error.stackTraceToString()).take(4000), tool, sessionId?.take(8)))
    }

    /** Entradas mais recentes primeiro. */
    fun entries(context: Context, limit: Int = 500): List<Entry> = synchronized(lock) {
        runCatching {
            file(context).takeIf { it.exists() }?.readLines().orEmpty().asReversed().asSequence()
                .mapNotNull { line -> runCatching { json.decodeFromString(Entry.serializer(), line) }.getOrNull() }
                .take(limit).toList()
        }.getOrDefault(emptyList())
    }

    /** Texto legível de todas as entradas, da mais antiga para a mais recente (exportação e diagnóstico). */
    fun readAll(context: Context): String = entries(context, Int.MAX_VALUE).asReversed().joinToString("\n") { render(it) }

    fun render(e: Entry): String = buildString {
        append('[').append(e.time).append("] ").append(e.source)
        if (!e.tool.isNullOrBlank()) append(" · tool=").append(e.tool)
        if (!e.session.isNullOrBlank()) append(" · sess=").append(e.session)
        if (e.count > 1) append(" · ×").append(e.count)
        append("\n    ").append(listOfNotNull(e.type, e.message).joinToString(": ").replace("\n", "\n    "))
        e.stack?.let { append("\n    ").append(it.replace("\n", "\n    ")) }
    }

    fun sizeBytes(context: Context): Long = synchronized(lock) {
        runCatching { file(context).takeIf { it.exists() }?.length() ?: 0L }.getOrDefault(0L)
    }

    private fun write(e: Entry) {
        val dir = filesDir ?: return
        runCatching {
            synchronized(lock) {
                // A mesma falha repetida em sequência vira um contador, em vez de encher o arquivo.
                val fp = e.source + "|" + e.tool + "|" + e.message.take(200)
                val last = recent[fp]
                val f = File(dir, FILE)
                if (last != null && e.ts - last < DEDUPE_WINDOW_MS && f.exists()) {
                    val lines = f.readLines().toMutableList()
                    val idx = lines.indexOfLast { line ->
                        runCatching { json.decodeFromString(Entry.serializer(), line) }.getOrNull()
                            ?.let { it.source + "|" + it.tool + "|" + it.message.take(200) == fp } == true
                    }
                    if (idx >= 0 && idx >= lines.size - 20) {
                        val prev = json.decodeFromString(Entry.serializer(), lines[idx])
                        lines[idx] = json.encodeToString(Entry.serializer(), prev.copy(ts = e.ts, count = prev.count + 1))
                        f.writeText(lines.joinToString("\n", postfix = "\n"))
                        recent[fp] = e.ts
                        return@synchronized
                    }
                }
                recent[fp] = e.ts
                if (recent.size > 64) recent.remove(recent.keys.first())
                f.appendText(json.encodeToString(Entry.serializer(), e) + "\n")
                if (f.length() > MAX_BYTES) {
                    val keep = f.readLines().takeLast(f.readLines().size / 2)
                    f.writeText(keep.joinToString("\n", postfix = "\n"))
                }
            }
        }
    }

    private val stackLine = Regex("""^\s+at [\w.$<>]+\(.*\)$""")
    private fun splitStack(text: String): Pair<String, String?> {
        val lines = text.lines()
        val i = lines.indexOfFirst { stackLine.matches(it) }
        if (i <= 0) return text to null
        return lines.take(i).joinToString("\n") to lines.drop(i).joinToString("\n")
    }

    /** Padrões de segredo: o grupo 1 (prefixo) é mantido e o valor vira •••. */
    private val secretPatterns = listOf(
        Regex("""(?i)(bearer\s+)[A-Za-z0-9._\-]{12,}"""),
        Regex("""(?i)\b((?:sk|pk|rk|ghp|gho|xox[abp])[-_])[A-Za-z0-9_\-]{12,}"""),
        Regex("""(?i)([?&](?:key|api_key|apikey|token|access_token|secret|password)=)[^&\s"']+"""),
        Regex("""(?i)("(?:api_?key|token|secret|password|authorization)"\s*:\s*")[^"]+"""),
    )

    /** Mascara tokens e chaves comuns antes de gravar. */
    fun redact(text: String): String =
        secretPatterns.fold(text) { acc, re -> re.replace(acc) { m -> m.groupValues[1] + "•••" } }

    fun clear(context: Context) = clearFiles(context.applicationContext.filesDir)

    /**
     * Diagnóstico completo num só arquivo para enviar: erros (IA/LLM, agentes, ferramentas, conectores, skills,
     * hub, Linux, browser, crashes), logs de depuração dos conectores e os registros de instalação/execução do hub
     * e do Linux. Segredos já são mascarados na origem; o conteúdo das conversas não entra.
     */
    fun exportAll(context: Context): File {
        val ctx = context.applicationContext
        val sb = StringBuilder()
        val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull()
        sb.append("Fluwy Bot — diagnóstico completo\n")
            .append("Gerado: ").append(stamp.format(Date())).append(" · app ").append(version ?: "?")
            .append(" · ").append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL)
            .append(" · Android ").append(android.os.Build.VERSION.RELEASE)
            .append(" · ").append(android.os.Build.SUPPORTED_ABIS.firstOrNull()).append("\n\n")
        fun section(title: String, body: String?) {
            if (body.isNullOrBlank()) return
            sb.append("===== ").append(title).append(" =====\n").append(body.trim()).append("\n\n")
        }
        section("RESUMO POR FERRAMENTA (execuções · falhas · tempo médio)", ActivityLog.summary(ctx))
        section("ERROS (todas as origens)", readAll(ctx))
        section("ATIVIDADE (ferramentas, Web API e modelo — mais recentes por último)", ActivityLog.readAll(ctx).takeLast(250_000))
        section("ÚLTIMO CRASH", runCatching { lastCrashFile(ctx).takeIf { it.exists() }?.readText() }.getOrNull())
        runCatching {
            // Só conectores usados nos últimos 3 dias: logs velhos repetidos só escondiam o que importa.
            File(ctx.filesDir, "connector-debug").listFiles()?.filter { System.currentTimeMillis() - it.lastModified() < 3 * 86_400_000L }?.sortedBy { it.name }?.forEach { f ->
                section("CONECTOR · ${f.name.removePrefix("connector-").removeSuffix(".debug.log")} (últimas linhas)", f.readText().takeLast(4_000))
            }
        }
        runCatching {
            val sandbox = dev.fluwy.app.FluwyApp.instance.sandbox
            section("LINUX (instalação/estado: ${sandbox.state.value})", sandbox.log.value.takeLast(15_000))
            val hub = File(sandbox.alpineDir, "opt/fluwy-hub")
            section("HUB · instalação", File(hub, "install.log").takeIf { it.exists() }?.readText()?.takeLast(60_000))
            section("HUB · execução", File(hub, "hub.log").takeIf { it.exists() }?.readText()?.takeLast(30_000))
            section("HUB · redis", File(hub, "redis.log").takeIf { it.exists() }?.readText()?.takeLast(5_000))
        }
        val out = File(ctx.filesDir, "diagnostico-completo.txt")
        out.writeText(sb.toString())
        return out
    }

    /** File-only helper keeps the clear scope testable and never touches app state or connector logs. */
    internal fun clearFiles(directory: File) {
        synchronized(lock) {
            runCatching { File(directory, FILE).delete() }
            runCatching { File(directory, LEGACY_FILE).delete() }
            runCatching { File(directory, LAST_CRASH_FILE).delete() }
            runCatching { File(directory, "activity_log.txt").delete() }
        }
    }
}
