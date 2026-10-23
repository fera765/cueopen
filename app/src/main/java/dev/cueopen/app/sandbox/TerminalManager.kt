package dev.cueopen.app.sandbox

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Terminais reais, iguais aos do Termux: cada tarefa tem um TerminalSession (PTY nativo via JNI,
 * libtermux.so) rodando `sh init-sandbox.sh` → proot → Alpine → bash interativo.
 *
 * - Sem WebView: a renderização e o teclado são da TerminalView do Termux.
 * - As sessões vivem no processo do app, mantido vivo pelo SandboxService (foreground + wakelock),
 *   então continuam rodando com o app fechado / removido dos recentes.
 * - O agente executa TUDO dentro desta mesma sessão: escreve o comando no PTY (o usuário vê em
 *   tempo real) e espera um marcador de fim com o código de saída.
 */
class TerminalManager(private val ctx: Context, private val sandbox: Sandbox) {

    inner class Session(val key: String, private val cwd: String) {
        @Volatile var view: TerminalView? = null
        val agentLock = Mutex()
        private val _alive = MutableStateFlow(false)
        val alive: StateFlow<Boolean> = _alive
        lateinit var term: TerminalSession; private set

        private val client = object : TerminalSessionClient {
            override fun onTextChanged(changedSession: TerminalSession) { view?.onScreenUpdated() }
            override fun onTitleChanged(changedSession: TerminalSession) {}
            override fun onSessionFinished(finishedSession: TerminalSession) {
                _alive.value = false
                view?.onScreenUpdated()
            }
            override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
                ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("terminal", text ?: ""))
            }
            override fun onPasteTextFromClipboard(session: TerminalSession?) {
                val t = ctx.getSystemService(ClipboardManager::class.java)?.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()
                if (!t.isNullOrEmpty()) session?.emulator?.paste(t)
            }
            override fun onBell(session: TerminalSession) {}
            override fun onColorsChanged(session: TerminalSession) { view?.onScreenUpdated() }
            override fun onTerminalCursorStateChange(state: Boolean) {}
            override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}
            override fun getTerminalCursorStyle(): Int? = null
            override fun logError(tag: String?, message: String?) { android.util.Log.e(tag, message ?: "") }
            override fun logWarn(tag: String?, message: String?) {}
            override fun logInfo(tag: String?, message: String?) {}
            override fun logDebug(tag: String?, message: String?) {}
            override fun logVerbose(tag: String?, message: String?) {}
            override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { android.util.Log.e(tag, message, e) }
            override fun logStackTrace(tag: String?, e: Exception?) { android.util.Log.e(tag, "erro", e) }
        }

        /** Cria (no main thread) e inicia o processo. Sem view, inicia com 100x36 para o agente. */
        fun start() {
            check(Looper.myLooper() == Looper.getMainLooper())
            if (_alive.value) return
            sandbox.prepareScripts()
            File(sandbox.publicDir, cwd.removePrefix("/root/")).mkdirs()
            val shell = "cd '$cwd' 2>/dev/null; if command -v bash >/dev/null 2>&1; then exec bash --rcfile /root/.bashrc -i; else exec sh -i; fi"
            term = TerminalSession(
                "/system/bin/sh", sandbox.prefix.absolutePath,
                arrayOf("sh", File(sandbox.prefix, "init-sandbox.sh").absolutePath, "/bin/sh", "-c", shell),
                sandbox.envArray(), 5000, client,
            )
            _alive.value = true
            val v = view
            if (v != null) v.attachSession(term) else term.updateSize(100, 36, 12, 24)
        }

        fun write(s: String) = main.post { if (_alive.value) term.write(s) }

        fun attach(v: TerminalView) {
            view = v
            if (!_alive.value) start() else v.attachSession(term)
            v.onScreenUpdated()
        }

        fun detach(v: TerminalView) { if (view === v) view = null }

        fun kill() { if (_alive.value) runCatching { term.finishIfRunning() }; _alive.value = false }

        private suspend fun transcript(): String = withContext(Dispatchers.Main) {
            runCatching { term.emulator?.screen?.transcriptTextWithFullLinesJoined ?: "" }.getOrDefault("")
        }

        /**
         * Executa um comando do agente dentro do terminal visível. O comando vai para um script
         * (multilinha/aspas sem problema); início e fim são marcados com printf, cujo eco na linha
         * digitada não coincide com o texto impresso.
         */
        suspend fun runAgentCommand(command: String, timeoutSec: Long, background: Boolean): Sandbox.ExecResult =
            agentLock.withLock {
                withContext(Dispatchers.Main) { if (!_alive.value) start() }
                delay(300)
                val id = System.nanoTime().toString(36)
                val dir = File(sandbox.publicDir, ".cue").apply { mkdirs() }
                File(dir, "cmd_$id.sh").writeText("cd '$cwd' 2>/dev/null\n$command\n")
                val begin = "__CUE_BEGIN_${id}__"
                val endRe = Regex("__CUE_END_${id}_(\\d+)__")
                val runner = if (background) "nohup sh /root/.cue/cmd_$id.sh > /root/.cue/bg_$id.log 2>&1 &"
                             else "sh /root/.cue/cmd_$id.sh"
                // linha visível no terminal: o usuário vê o comando sendo digitado e executado
                write("printf '\\033[38;5;209m[agente]\\033[0m %s\\n' ${shQuote(command.lineSequence().first().take(160))}; " +
                      "printf '__CUE_%s__\\n' BEGIN_$id; $runner; printf '__CUE_END_%s_%s__\\n' $id \$?\r")
                var exit = -1
                val done = withTimeoutOrNull(timeoutSec * 1000) {
                    while (true) {
                        val m = endRe.find(transcript())
                        if (m != null) { exit = m.groupValues[1].toInt(); break }
                        delay(250)
                    }
                    true
                }
                if (done == null) { write("\u0003"); delay(400) }
                val t = transcript()
                val a = t.lastIndexOf(begin).let { if (it < 0) 0 else it + begin.length }
                val b = endRe.find(t, a)?.range?.first ?: t.length
                val out = t.substring(a.coerceAtMost(t.length), b.coerceAtLeast(a.coerceAtMost(t.length))).trim()
                File(dir, "cmd_$id.sh").delete()
                Sandbox.ExecResult(exit, if (background) "Iniciado em segundo plano (log: /root/.cue/bg_$id.log)" else out, done == null)
            }
    }

    private val main = Handler(Looper.getMainLooper())
    private val sessions = ConcurrentHashMap<String, Session>()
    private val _count = MutableStateFlow(0)
    val count: StateFlow<Int> = _count

    fun get(key: String, cwd: String = "/root/workspace/$key"): Session =
        sessions.getOrPut(key) { Session(key, cwd).also { _count.value = sessions.size + 1 } }

    fun closeAll() { main.post { sessions.values.forEach { it.kill() }; sessions.clear(); _count.value = 0 } }

    companion object {
        fun shQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"
    }
}
