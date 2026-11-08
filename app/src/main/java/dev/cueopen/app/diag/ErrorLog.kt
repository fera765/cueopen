package dev.cueopen.app.diag

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
    private const val FILE = "error_log.txt"
    private const val LAST_CRASH_FILE = "last_crash.txt"
    private const val MAX_BYTES = 512 * 1024L
    private val lock = Any()
    @Volatile private var filesDir: File? = null
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun init(context: Context) { filesDir = context.applicationContext.filesDir }

    fun file(context: Context): File = File(context.applicationContext.filesDir, FILE)
    fun lastCrashFile(context: Context): File = File(context.applicationContext.filesDir, LAST_CRASH_FILE)

    /**
     * Acrescenta uma entrada de erro. [source] é a origem (ex.: "tool", "browser", "terminal",
     * "swap", "market", "crash"); [detail] a mensagem real da falha.
     */
    fun log(source: String, detail: String, sessionId: String? = null, tool: String? = null) {
        val dir = filesDir ?: return
        if (detail.isBlank()) return
        runCatching {
            synchronized(lock) {
                val f = File(dir, FILE)
                val header = buildString {
                    append('[').append(stamp.format(Date())).append("] ").append(source)
                    if (!tool.isNullOrBlank()) append(" · tool=").append(tool)
                    if (!sessionId.isNullOrBlank()) append(" · sess=").append(sessionId.take(8))
                }
                // Falhas do browser trazem o diagnóstico completo (linha do tempo, rede, janela): limite maior.
                val body = detail.trim().take(if (source == "browser") 6000 else 2000).replace("\n", "\n    ")
                f.appendText("$header\n    $body\n")
                if (f.length() > MAX_BYTES) {
                    val tail = f.readText().takeLast((MAX_BYTES / 2).toInt())
                    f.writeText("[log truncado — mantidas as entradas mais recentes]\n$tail")
                }
            }
        }
    }

    fun readAll(context: Context): String = synchronized(lock) {
        runCatching { file(context).takeIf { it.exists() }?.readText() }.getOrNull().orEmpty()
    }

    fun sizeBytes(context: Context): Long = synchronized(lock) {
        runCatching { file(context).takeIf { it.exists() }?.length() ?: 0L }.getOrDefault(0L)
    }

    fun clear(context: Context) = clearFiles(context.applicationContext.filesDir)

    /** File-only helper keeps the clear scope testable and never touches app state or connector logs. */
    internal fun clearFiles(directory: File) {
        synchronized(lock) {
            runCatching { File(directory, FILE).delete() }
            runCatching { File(directory, LAST_CRASH_FILE).delete() }
        }
    }
}
