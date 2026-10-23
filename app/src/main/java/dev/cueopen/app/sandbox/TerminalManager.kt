package dev.cueopen.app.sandbox

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Terminais persistentes do lado nativo (Kotlin), um PTY do axs por tarefa.
 *
 * - A conexão WebSocket com o axs vive aqui (não na WebView), então o terminal continua rodando
 *   com o app fechado (o SandboxService em foreground mantém o processo vivo).
 * - As telas de terminal (xterm.js) são só "espelhos": recebem o buffer + os novos chunks e
 *   devolvem o que o usuário digita.
 * - O agente executa TUDO dentro deste mesmo terminal: digita o comando no PTY e espera um
 *   marcador de fim com o código de saída. O usuário vê cada comando ao vivo.
 */
class TerminalManager(private val sandbox: Sandbox) {

    inner class Session(val key: String) {
        @Volatile var pid: String? = null
        private var ws: WebSocket? = null
        private val buf = StringBuilder()
        private val listeners = CopyOnWriteArrayList<(String) -> Unit>()
        private val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
        private var pending = ByteArray(0)
        val agentLock = Mutex()
        private val _alive = MutableStateFlow(false)
        val alive: StateFlow<Boolean> = _alive
        @Volatile var lastCols = 100; @Volatile var lastRows = 36

        val length: Int get() = synchronized(buf) { buf.length }
        fun snapshot(): String = synchronized(buf) { buf.toString() }
        fun since(offset: Int): String = synchronized(buf) { if (offset >= buf.length) "" else buf.substring(offset.coerceAtLeast(0)) }

        fun addListener(l: (String) -> Unit) { listeners += l }
        fun removeListener(l: (String) -> Unit) { listeners -= l }

        private fun emit(s: String) {
            if (s.isEmpty()) return
            synchronized(buf) {
                buf.append(s)
                // mantém ~400 KB; offsets antigos são recalculados pelo agente via marcador
                if (buf.length > 400_000) { val cut = buf.length - 300_000; buf.delete(0, cut); trimmed += cut }
            }
            listeners.forEach { runCatching { it(s) } }
        }
        @Volatile var trimmed = 0L

        private fun onBytes(b: ByteArray) {
            val all = if (pending.isEmpty()) b else pending + b
            val inBuf = ByteBuffer.wrap(all)
            val out = CharBuffer.allocate(all.size + 8)
            decoder.decode(inBuf, out, false)
            out.flip()
            pending = if (inBuf.hasRemaining()) ByteArray(inBuf.remaining()).also { inBuf.get(it) } else ByteArray(0)
            emit(out.toString())
        }

        suspend fun connect(cols: Int = lastCols, rows: Int = lastRows): Boolean = withContext(Dispatchers.IO) {
            if (_alive.value) return@withContext true
            val p = sandbox.createTerminal(cols, rows) ?: return@withContext false
            pid = p
            val opened = CompletableDeferred<Boolean>()
            ws = http.newWebSocket(Request.Builder().url("ws://127.0.0.1:${sandbox.axsPort}/terminals/$p").build(),
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) { _alive.value = true; opened.complete(true) }
                    override fun onMessage(webSocket: WebSocket, text: String) = emit(text)
                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) = onBytes(bytes.toByteArray())
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed() }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { closed(); opened.complete(false) }
                    private fun closed() {
                        if (_alive.value) emit("\r\n\u001b[33m[terminal encerrado — toque para reconectar]\u001b[0m\r\n")
                        _alive.value = false
                    }
                })
            withTimeoutOrNull(8000) { opened.await() } ?: false
        }

        fun send(data: String) { ws?.send(data) }

        fun resize(cols: Int, rows: Int) {
            lastCols = cols; lastRows = rows
            val p = pid ?: return
            scope.launch { sandbox.resizeTerminal(p, cols, rows) }
        }

        fun close() {
            runCatching { ws?.close(1000, null) }
            pid?.let { p -> runCatching {
                http.newCall(Request.Builder().url("http://127.0.0.1:${sandbox.axsPort}/terminals/$p/terminate")
                    .post(ByteArray(0).toRequestBody()).build()).execute().close()
            } }
            _alive.value = false
        }

        /**
         * Executa um comando do agente DENTRO do terminal visível.
         * O comando vai para um script (suporta multilinha/aspas) e o fim é detectado por um marcador
         * que não aparece no eco da linha digitada.
         */
        suspend fun runAgentCommand(command: String, cwd: String, timeoutSec: Long, background: Boolean): Sandbox.ExecResult =
            agentLock.withLock {
                if (!connect()) return Sandbox.ExecResult(127, "Não foi possível abrir o terminal do sandbox.", false)
                val id = System.nanoTime().toString(36)
                val dir = File(sandbox.publicDir, ".cue").apply { mkdirs() }
                val header = command.lineSequence().first().take(200).replace("'", "'\\''")
                File(dir, "cmd_$id.sh").writeText(
                    "cd '$cwd' 2>/dev/null || { mkdir -p '$cwd' && cd '$cwd'; }\n" +
                    "printf '\\033[38;5;209m[agente]\\033[0m \\033[1m%s\\033[0m\\n' '$header'\n" +
                    command + "\n")
                val runner = if (background) "nohup sh /root/.cue/cmd_$id.sh > /root/.cue/bg_$id.log 2>&1 &"
                             else "sh /root/.cue/cmd_$id.sh"
                val marker = Regex("__CUE_END_${id}_(\\d+)__")
                val trimmedAtStart = trimmed
                val start = length
                send("$runner; printf '\\n__CUE_END_%s_%s__\\n' '$id' \"\$?\"\r")
                var exit = -1; var timedOut = false
                val ok = withTimeoutOrNull(timeoutSec * 1000) {
                    while (true) {
                        val off = (start - (trimmed - trimmedAtStart)).toInt()
                        val m = marker.find(since(off))
                        if (m != null) { exit = m.groupValues[1].toInt(); break }
                        delay(150)
                    }
                    true
                }
                if (ok == null) { timedOut = true; send("\u0003") ; delay(300) }
                val raw = since((start - (trimmed - trimmedAtStart)).toInt())
                val clean = stripAnsi(raw).lines()
                    .filterNot { it.contains("__CUE_END_") || it.contains("/root/.cue/cmd_") || it.startsWith("[agente] ") }
                    .joinToString("\n").trim()
                File(dir, "cmd_$id.sh").delete()
                Sandbox.ExecResult(exit, if (background) "Iniciado em segundo plano (log: /root/.cue/bg_$id.log)" else clean, timedOut)
            }
    }

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(20, TimeUnit.SECONDS).build()
    private val sessions = java.util.concurrent.ConcurrentHashMap<String, Session>()
    private val _count = MutableStateFlow(0)
    val count: StateFlow<Int> = _count

    fun get(key: String): Session = sessions.getOrPut(key) { Session(key).also { _count.value = sessions.size + 1 } }
    fun existing(key: String): Session? = sessions[key]

    fun closeAll() { sessions.values.forEach { it.close() }; sessions.clear(); _count.value = 0 }

    companion object {
        private val ansi = Regex("\u001B(?:\\[[0-?]*[ -/]*[@-~]|\\][^\u0007\u001B]*(?:\u0007|\u001B\\\\)|[@-Z\\\\-_])")
        fun stripAnsi(s: String) = s.replace(ansi, "").replace("\r", "")
    }
}

