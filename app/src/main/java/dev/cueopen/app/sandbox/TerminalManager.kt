package dev.cueopen.app.sandbox

import dev.cueopen.app.agent.ShellOutput
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Terminais reais, iguais aos do Termux: cada tarefa tem um TerminalSession (PTY nativo via JNI,
 * libtermux.so) rodando `sh init-sandbox.sh` → proot → Alpine → bash interativo.
 *
 * - Sem WebView: a renderização e o teclado são da TerminalView do Termux.
 * - O serviço foreground tenta manter sessões vivas em background. Se o processo Android for
 *   encerrado, o PTY não sobrevive; o workspace, o cwd conhecido e o transcript são recuperados.
 * - O agente executa TUDO dentro desta mesma sessão: escreve o comando no PTY (o usuário vê em
 *   tempo real) e espera um marcador de fim com o código de saída.
 */
class TerminalManager(private val ctx: Context, private val sandbox: Sandbox) {
    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scriptsLock = Mutex()

    inner class Session(val key: String, private val cwd: String) {
        private val snapshotFile = File(sandbox.publicFor(key), ".cue/terminal_snapshot.txt")
        private val savedTranscript = runCatching { snapshotFile.takeIf { it.isFile }?.readText().orEmpty() }
            .getOrDefault("").let { TerminalRecoveryPolicy.boundedTranscript(it) }
        private var transcriptReplayPending = savedTranscript.isNotBlank()
        private val restoreFile = File(sandbox.publicFor(key), ".cue/terminal_restore.txt")
        private var snapshotJob: Job? = null
        private val snapshotLock = Any()
        private val snapshotVersion = AtomicLong(0)
        @Volatile var view: TerminalView? = null
        val agentLock = Mutex()
        private val _alive = MutableStateFlow(false)
        val alive: StateFlow<Boolean> = _alive
        lateinit var term: TerminalSession; private set

        private val client = object : TerminalSessionClient {
            override fun onTextChanged(changedSession: TerminalSession) {
                view?.onScreenUpdated()
                queueTranscriptSnapshot(changedSession)
            }
            override fun onTitleChanged(changedSession: TerminalSession) {}
            override fun onSessionFinished(finishedSession: TerminalSession) {
                _alive.value = false
                view?.onScreenUpdated()
                persistTranscriptNow(finishedSession)
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

        /** Prepara arquivos em IO e cria a sessão nativa na Main. Sem view, inicia com 100x36 para o agente. */
        fun start() {
            check(Looper.myLooper() == Looper.getMainLooper())
            if (_alive.value || startJob?.isActive == true) return
            val replayTranscript = transcriptReplayPending
            val result = kotlinx.coroutines.CompletableDeferred<Boolean>()
            startResult = result
            promptReady = kotlinx.coroutines.CompletableDeferred()
            startJob = startupScope.launch {
                val prepared = runCatching {
                    scriptsLock.withLock { sandbox.prepareScripts() }
                    // Reuses the installed Alpine base; this only prepares the per-session workspace.
                    if (!sandbox.ensureEnvironment(key)) false else {
                        File(sandbox.publicFor(key), cwd.removePrefix("/root/")).mkdirs()
                        installShellHook(key)
                        if (replayTranscript) runCatching { stageTranscriptForReplay() }
                            .onFailure { android.util.Log.e("CueOpenTerminal", "Falha ao preparar o histórico visual.", it) }
                        true
                    }
                }.getOrElse {
                    android.util.Log.e("CueOpenTerminal", "Falha ao preparar o ambiente Linux do chat.", it)
                    false
                }
                if (!prepared) {
                    android.util.Log.e("CueOpenTerminal", "Ambiente Linux do chat não foi criado; consulte o log do Sandbox nas Configurações.")
                    result.complete(false)
                    return@launch
                }
                try {
                    val launchCwd = savedWorkingDirectory()
                    withContext(Dispatchers.Main) {
                        if (!_alive.value) {
                            val replay = if (replayTranscript) "if [ -s /root/.cue/terminal_restore.txt ]; then cat /root/.cue/terminal_restore.txt; rm -f /root/.cue/terminal_restore.txt; fi; " else ""
                            val shell = "cd ${shQuote(launchCwd)} 2>/dev/null || cd ${shQuote(cwd)} 2>/dev/null; $replay if command -v bash >/dev/null 2>&1; then exec bash --rcfile /root/.bashrc -i; else exec sh -i; fi"
                            term = TerminalSession(
                                "/system/bin/sh", sandbox.prefix.absolutePath,
                                arrayOf("sh", File(sandbox.prefix, "init-sandbox.sh").absolutePath, "/bin/sh", "-c", shell),
                                sandbox.envArray(key), 5000, client,
                            )
                            transcriptReplayPending = false
                            _alive.value = true
                            val v = view
                            if (v != null) v.attachSession(term) else term.updateSize(100, 36, 12, 24)
                        }
                    }
                    result.complete(_alive.value)
                } catch (t: Throwable) {
                    android.util.Log.e("CueOpenTerminal", "Falha ao iniciar a sessão de terminal.", t)
                    result.complete(false)
                }
            }
        }

        fun write(s: String) = main.post { if (_alive.value) term.write(s) }

        fun attach(v: TerminalView) {
            view = v
            if (!_alive.value) start() else v.attachSession(term)
            v.onScreenUpdated()
        }

        fun detach(v: TerminalView) { if (view === v) view = null }

        fun cancelAgentCommand() {
            pending?.cancel(kotlinx.coroutines.CancellationException("Comando interrompido pelo usuário"))
            main.post { if (_alive.value) term.write("\u0003") }
        }

        fun kill() {
            startJob?.cancel()
            if (!startResult.isCompleted) startResult.complete(false)
            snapshotJob?.cancel()
            if (_alive.value) persistTranscriptNow(term)
            cancelAgentCommand()
            if (_alive.value) runCatching { term.finishIfRunning() }
            _alive.value = false
        }

        val hasSavedTranscript: Boolean get() = snapshotFile.isFile && snapshotFile.length() > 0

        fun persistNow() {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                if (::term.isInitialized) persistTranscriptNow(term)
            } else main.post { if (::term.isInitialized) persistTranscriptNow(term) }
        }

        private fun savedWorkingDirectory(): String {
            val file = File(sandbox.publicFor(key), ".cue/terminal_cwd")
            val saved = runCatching { file.readText().trim() }.getOrNull()
            return TerminalRecoveryPolicy.safeWorkingDirectory(
                savedPath = saved,
                fallback = cwd,
                sessionRoot = sandbox.publicFor(key),
                resolveGuestPath = { sandbox.hostFile(it, key) },
            )
        }

        private fun stageTranscriptForReplay() {
            val bytes = TerminalRecoveryPolicy.transcriptBytes(savedTranscript) ?: return
            restoreFile.parentFile?.mkdirs()
            val atomic = AtomicFile(restoreFile)
            val output = atomic.startWrite()
            try {
                output.write(bytes)
                output.fd.sync()
                atomic.finishWrite(output)
            } catch (t: Throwable) {
                atomic.failWrite(output)
                throw t
            }
        }

        private fun queueTranscriptSnapshot(session: TerminalSession) {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                main.post { queueTranscriptSnapshot(session) }
                return
            }
            val text = runCatching { session.emulator?.screen?.transcriptTextWithFullLinesJoinedAndStyles.orEmpty() }
                .getOrDefault("").let { TerminalRecoveryPolicy.boundedTranscript(it) }
            if (text.isBlank()) return
            snapshotJob?.cancel()
            val version = snapshotVersion.incrementAndGet()
            snapshotJob = startupScope.launch {
                delay(250)
                runCatching { writeTranscriptSnapshot(text, version) }
                    .onFailure { android.util.Log.e("CueOpenTerminal", "Falha ao salvar o histórico do terminal.", it) }
            }
        }

        private fun persistTranscriptNow(session: TerminalSession) {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                main.post { persistTranscriptNow(session) }
                return
            }
            snapshotVersion.incrementAndGet()
            snapshotJob?.cancel()
            snapshotJob = null
            val text = runCatching { session.emulator?.screen?.transcriptTextWithFullLinesJoinedAndStyles.orEmpty() }
                .getOrDefault("").let { TerminalRecoveryPolicy.boundedTranscript(it) }
            if (text.isNotBlank()) runCatching { writeTranscriptSnapshot(text) }
                .onFailure { android.util.Log.e("CueOpenTerminal", "Falha ao salvar o histórico do terminal.", it) }
        }

        private fun writeTranscriptSnapshot(text: String, version: Long? = null) {
            synchronized(snapshotLock) {
                if (version != null && version != snapshotVersion.get()) return
                snapshotFile.parentFile?.mkdirs()
                val atomic = AtomicFile(snapshotFile)
                val output = atomic.startWrite()
                try {
                    output.write(TerminalRecoveryPolicy.boundedTranscript(text).toByteArray(Charsets.UTF_8))
                    output.fd.sync()
                    atomic.finishWrite(output)
                } catch (t: Throwable) {
                    atomic.failWrite(output)
                    throw t
                }
            }
        }

        private suspend fun transcript(): String = withContext(Dispatchers.Main) {
            runCatching { term.emulator?.screen?.transcriptTextWithFullLinesJoined ?: "" }.getOrDefault("")
        }

        @Volatile private var pending: kotlinx.coroutines.CompletableDeferred<Int>? = null
        @Volatile private var promptReady = kotlinx.coroutines.CompletableDeferred<Unit>()
        private var startJob: Job? = null
        private var startResult = kotlinx.coroutines.CompletableDeferred<Boolean>().also { it.complete(false) }
        internal fun onPromptReturned(exit: Int) {
            promptReady.complete(Unit)
            pending?.complete(exit)
        }

        /**
         * Executa um comando do agente no terminal visível, como uma pessoa digitando:
         * aparece só o comando real. O fim é detectado pelo PROMPT_COMMAND do bash, que emite
         * uma sequência OSC 777 invisível com o código de saída (nada de marcadores na tela).
         */
        suspend fun runAgentCommand(command: String, timeoutSec: Long, background: Boolean): Sandbox.ExecResult =
            agentLock.withLock {
                val startup = withContext(Dispatchers.Main) { if (!_alive.value) start(); startResult }
                // A preparação é assíncrona; iniciar o processo não significa que o prompt do bash já apareceu.
                val ready = withTimeoutOrNull(12_000) {
                    if (!startup.await()) false else { promptReady.await(); true }
                } ?: false
                if (!ready || !_alive.value) {
                    return@withLock Sandbox.ExecResult(-1, "Shell não ficou pronto: prompt do bash não foi detectado.", true)
                }
                val id = System.nanoTime().toString(36)
                val dir = File(sandbox.workspaceFor(key), ".cue").apply { mkdirs() }
                val gcue = "$cwd/.cue"
                val cmd = command.trim()
                val simple = !cmd.contains('\n') && cmd.length <= 300 && !cmd.contains("<<") && cmd.count { it == '"' } % 2 == 0 && cmd.count { it == '\'' } % 2 == 0
                val outputFile = File(dir, "out_$id.log")
                val typed = when {
                    background && simple -> "( $cmd ) > $gcue/bg_$id.log 2>&1 &"
                    background -> { File(dir, "run_$id.sh").writeText(cmd + "\n"); "nohup bash $gcue/run_$id.sh > $gcue/bg_$id.log 2>&1 &" }
                    else -> {
                        if (!simple) File(dir, "run_$id.sh").writeText(cmd + "\n")
                        val invocation = if (simple) cmd else "bash $gcue/run_$id.sh"
                        // Captura stdout/stderr num arquivo no filesystem do Android. A saída
                        // não depende do tamanho da tela, do scrollback ou de parsing do transcript.
                        // PROMPT_COMMAND é a única fonte do exit code. O segundo OSC aqui emitia
                        // outro marcador (geralmente 0 do printf), que podia ser atribuído ao próximo comando.
                        "$invocation > $gcue/out_$id.log 2>&1"
                    }
                }
                val waiter = kotlinx.coroutines.CompletableDeferred<Int>()
                pending = waiter
                // IMPORTANTE: apenas \u0015 (mata a linha) + o comando + \r. NÃO enviar '\n' antes
                // do comando: um '\n' submete uma linha vazia, o bash retorna o prompt e dispara o
                // OSC de fim (cue;done;0) ANTES do comando rodar — completando o waiter com exit 0 e
                // saída vazia. Era a causa de "executou mas não retornou saída".
                write("\u0015$typed\r")
                val exit = try {
                    withTimeoutOrNull(timeoutSec * 1000) { waiter.await() }
                } finally {
                    // Se o agente for pausado/cancelado, interrompe também o processo no PTY.
                    if (!waiter.isCompleted) {
                        withContext(kotlinx.coroutines.NonCancellable) {
                            withContext(Dispatchers.Main) { write("\u0003") }
                            delay(150)
                        }
                    }
                    if (pending === waiter) pending = null
                }
                if (exit == null) { write("\u0003"); delay(500) }
                val out = if (background) {
                    // Mostra já o começo do log: erro imediato (porta ocupada, arquivo inexistente) aparece na hora.
                    val bgLog = File(dir, "bg_$id.log")
                    delay(1_500)
                    val head = runCatching { if (bgLog.isFile) bgLog.readText().take(1_500).trim() else "" }.getOrDefault("")
                    "Iniciado em segundo plano. Log: $gcue/bg_$id.log (use `cat $gcue/bg_$id.log`; não use outro caminho de log)." +
                        if (head.isNotEmpty()) "\n[Primeiras linhas do log]\n$head" else "\n(sem saída no log ainda)"
                } else {
                    // O out.log é escrito no filesystem do Android via bind-mount do proot; aguarda
                    // brevemente a visibilidade do arquivo antes de ler (evita leitura prematura vazia).
                    var waited = 0
                    while (!outputFile.isFile && waited < 500) { delay(40); waited += 40 }
                    ShellOutput.readCapturedFile(outputFile)
                }
                // Em segundo plano o nohup ainda vai ler o script: apagar aqui causava
                // "bash: …/run_x.sh: No such file or directory" e o servidor nunca subia.
                if (!background) File(dir, "run_$id.sh").delete()
                outputFile.delete()
                Sandbox.ExecResult(exit ?: -1, out, exit == null)
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

    private fun installShellHook(key: String) {
        val cue = File(sandbox.publicFor(key), ".cue").apply { mkdirs() }
        File(cue, "hook.sh").writeText(
            "# CueOpen: avisa o app (sequência OSC invisível) sempre que o prompt volta\n" +
            "set +H 2>/dev/null  # sem expansão de histórico: '!' em HTML/JS não quebra comandos\n" +
            "__cue_pc() { local e=\$?; (umask 077; printf '%s\\n' \"\$PWD\" > /root/.cue/terminal_cwd.tmp && mv -f /root/.cue/terminal_cwd.tmp /root/.cue/terminal_cwd) 2>/dev/null; printf '\\033]777;cue;done;%s\\007' \"\$e\"; return \$e; }\n" +
            "case \"\$PROMPT_COMMAND\" in *__cue_pc*) ;; *) PROMPT_COMMAND=\"__cue_pc\${PROMPT_COMMAND:+;\$PROMPT_COMMAND}\" ;; esac\n")
        val rc = File(sandbox.publicFor(key), ".bashrc")
        val line = "[ -f /root/.cue/hook.sh ] && . /root/.cue/hook.sh"
        val cur = if (rc.exists()) rc.readText() else "cd /root/workspace 2>/dev/null\n"
        if (!cur.contains(line)) rc.writeText(cur.trimEnd() + "\n" + line + "\n")
    }

    fun get(key: String, cwd: String = "/root/workspace"): Session =
        sessions.getOrPut(key) { Session(key, cwd).also { _count.value = sessions.size + 1 } }

    fun close(key: String) { main.post { sessions.remove(key)?.kill(); _count.value = sessions.size } }

    fun persistAll() {
        val action = { sessions.values.forEach { it.persistNow() } }
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post { action() }
    }

    fun closeAll() { main.post { sessions.values.forEach { it.kill() }; sessions.clear(); _count.value = 0 } }

    companion object {
        fun shQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"
    }
}
