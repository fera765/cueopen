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
import kotlinx.coroutines.runBlocking
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
            // First launch clones the installed base; it never downloads Alpine per chat.
            check(runBlocking { sandbox.ensureEnvironment(key) }) { "Could not create this chat's Linux environment" }
            File(sandbox.publicFor(key), cwd.removePrefix("/root/")).mkdirs()
            installShellHook()
            val shell = "cd '$cwd' 2>/dev/null; if command -v bash >/dev/null 2>&1; then exec bash --rcfile /root/.bashrc -i; else exec sh -i; fi"
            term = TerminalSession(
                "/system/bin/sh", sandbox.prefix.absolutePath,
                arrayOf("sh", File(sandbox.prefix, "init-sandbox.sh").absolutePath, "/bin/sh", "-c", shell),
                sandbox.envArray(key), 5000, client,
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

        @Volatile private var pending: kotlinx.coroutines.CompletableDeferred<Int>? = null
        internal fun onPromptReturned(exit: Int) { pending?.complete(exit) }

        /**
         * Executa um comando do agente no terminal visível, como uma pessoa digitando:
         * aparece só o comando real. O fim é detectado pelo PROMPT_COMMAND do bash, que emite
         * uma sequência OSC 777 invisível com o código de saída (nada de marcadores na tela).
         */
        suspend fun runAgentCommand(command: String, timeoutSec: Long, background: Boolean): Sandbox.ExecResult =
            agentLock.withLock {
                withContext(Dispatchers.Main) { if (!_alive.value) start() }
                delay(400)
                val id = System.nanoTime().toString(36)
                val dir = File(sandbox.workspaceFor(key), ".cue").apply { mkdirs() }
                val gcue = "$cwd/.cue"
                val cmd = command.trim()
                val simple = !cmd.contains('\n') && cmd.length <= 300 && !cmd.contains("<<") && cmd.count { it == '"' } % 2 == 0 && cmd.count { it == '\'' } % 2 == 0
                val typed = when {
                    background && simple -> "( $cmd ) > $gcue/bg_$id.log 2>&1 &"
                    background -> { File(dir, "run_$id.sh").writeText(cmd + "\n"); "nohup bash $gcue/run_$id.sh > $gcue/bg_$id.log 2>&1 &" }
                    simple -> cmd
                    else -> { File(dir, "run_$id.sh").writeText(cmd + "\n"); "bash $gcue/run_$id.sh" }
                }
                val before = transcript()
                val waiter = kotlinx.coroutines.CompletableDeferred<Int>()
                pending = waiter
                write("\u0015$typed\r") // Ctrl-U limpa algo digitado pela metade
                val exit = withTimeoutOrNull(timeoutSec * 1000) { waiter.await() }
                pending = null
                if (exit == null) { write("\u0003"); delay(500) }
                val t = transcript()
                val added = if (t.length >= before.length && t.startsWith(before.take(200))) t.substring(before.length) else t.takeLast(12_000)
                val lines = added.lines()
                // 1ª linha = eco do comando digitado; última = novo prompt
                val out = lines.drop(1).let { if (it.isNotEmpty() && it.last().contains("@sandbox")) it.dropLast(1) else it }
                    .joinToString("\n").trim()
                File(dir, "run_$id.sh").delete()
                Sandbox.ExecResult(exit ?: -1,
                    if (background) "Iniciado em segundo plano (log: $gcue/bg_$id.log)" else out, exit == null)
            }
    }

    private val main = Handler(Looper.getMainLooper())
    private val sessions = ConcurrentHashMap<String, Session>()
    private val _count = MutableStateFlow(0)
    val count: StateFlow<Int> = _count

    init {
        com.termux.terminal.TerminalEmulator.OSC_LISTENER = com.termux.terminal.TerminalEmulator.OscListener { emu, text ->
            // formato: cue;done;<exit>
            val parts = text.split(';')
            if (parts.size >= 3 && parts[0] == "cue" && parts[1] == "done") {
                val code = parts[2].trim().toIntOrNull() ?: 0
                sessions.values.firstOrNull { s -> runCatching { s.term.emulator === emu }.getOrDefault(false) }?.onPromptReturned(code)
            }
        }
    }

    private fun installShellHook() {
        val cue = File(sandbox.publicFor(key), ".cue").apply { mkdirs() }
        File(cue, "hook.sh").writeText(
            "# CueOpen: avisa o app (sequência OSC invisível) sempre que o prompt volta\n" +
            "set +H 2>/dev/null  # sem expansão de histórico: '!' em HTML/JS não quebra comandos\n" +
            "__cue_pc() { local e=\$?; printf '\\033]777;cue;done;%s\\007' \"\$e\"; return \$e; }\n" +
            "case \"\$PROMPT_COMMAND\" in *__cue_pc*) ;; *) PROMPT_COMMAND=\"__cue_pc\${PROMPT_COMMAND:+;\$PROMPT_COMMAND}\" ;; esac\n")
        val rc = File(sandbox.publicFor(key), ".bashrc")
        val line = "[ -f /root/.cue/hook.sh ] && . /root/.cue/hook.sh"
        val cur = if (rc.exists()) rc.readText() else "cd /root/workspace 2>/dev/null\n"
        if (!cur.contains(line)) rc.writeText(cur.trimEnd() + "\n" + line + "\n")
    }

    fun get(key: String, cwd: String = "/root/workspace"): Session =
        sessions.getOrPut(key) { Session(key, cwd).also { _count.value = sessions.size + 1 } }

    fun close(key: String) { main.post { sessions.remove(key)?.kill(); _count.value = sessions.size } }

    fun closeAll() { main.post { sessions.values.forEach { it.kill() }; sessions.clear(); _count.value = 0 } }

    companion object {
        fun shQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"
    }
}
