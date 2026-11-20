package dev.cueopen.app.sandbox

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import dev.cueopen.app.CueApp
import dev.cueopen.app.proxy.ProxyEnvironment
import dev.cueopen.app.proxy.ProxyScope
import dev.cueopen.app.proxy.ProxyValidator

/**
 * Sandbox Linux local — mesma arquitetura do terminal do Acode:
 *
 *  Kotlin (ProcessBuilder) ──► sh init-sandbox.sh ──► proot (libproot-xed.so, em jniLibs)
 *        └─ rootfs Alpine minirootfs em filesDir/alpine
 *              └─ init-alpine.sh ──► comando único   (ferramenta shell do agente)
 *                                └─► axs (libaxs.so) servidor PTY em 127.0.0.1:8767
 *                                       POST /terminals {cols,rows} -> pid
 *                                       WS   /terminals/{pid}       (stdin/stdout)
 *                                       POST /terminals/{pid}/resize | /terminate
 *  Frontend do terminal: xterm.js num WebView (assets/terminal/index.html).
 */
class Sandbox(private val ctx: Context) {

    enum class State { NOT_INSTALLED, INSTALLING, READY, ERROR }

    val prefix: File = ctx.filesDir
    val nativeDir: String = ctx.applicationInfo.nativeLibraryDir
    val alpineDir = File(prefix, "alpine")
    /** Dados separados por chat; a base Alpine nunca deve receber escritas de sessões. */
    private val environmentsDir = File(prefix, "chat-environments")
    /**
     * Raiz COMPARTILHADA dos UI Connectors: cada conector tem `connectors/<id>` aqui. Esta pasta é
     * montada como `/root/connectors` em TODA sessão de chat, então o agente lê/copia os arquivos que
     * um conector gerou (ex.: vídeo baixado em `/root/connectors/ytbaixar/workspace`).
     */
    val connectorsDir = File(prefix, "connectors").apply { mkdirs() }
    /** Compatibilidade para chamadas legadas sem sessionId. */
    val publicDir = File(prefix, "public").apply { mkdirs() }
    val workspaceRoot = File(publicDir, "workspace").apply { mkdirs() }

    private fun safeId(sessionId: String): String = sessionId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(80).ifBlank { "default" }

    /** Sessões de UI Connector usam o prefixo `__uic__`; ganham raiz própria em connectors/<id>. */
    fun isConnectorSession(sessionId: String): Boolean = sessionId.startsWith("__uic__")
    fun connectorId(sessionId: String): String = safeId(sessionId.removePrefix("__uic__"))

    /**
     * Ambiente compartilhado: chats de uma mesma Seção apontam para o mesmo workspace, para que o arquivo
     * criado por um agente (ex.: copy_francês.md do copywriter) exista para os outros (ex.: o construtor).
     * Antes cada agente tinha um workspace isolado e passava horas procurando arquivos "inexistentes".
     */
    @Volatile var environmentAlias: ((String) -> String?)? = null

    fun environmentRoot(sessionId: String): File =
        if (isConnectorSession(sessionId)) File(connectorsDir, connectorId(sessionId))
        else File(environmentsDir, safeId(runCatching { environmentAlias?.invoke(sessionId) }.getOrNull() ?: sessionId))
    // Todos os chats reutilizam o Alpine já instalado; dados de usuário continuam separados por chat.
    // Não copiar centenas/milhares de arquivos no primeiro comando ou ao criar uma conversa.
    fun rootfsFor(sessionId: String): File = alpineDir
    // Conector: a própria raiz é o "public" (/root = connectors/<id>); chat: subpasta "public".
    fun publicFor(sessionId: String): File =
        (if (isConnectorSession(sessionId)) environmentRoot(sessionId) else File(environmentRoot(sessionId), "public")).apply { mkdirs() }
    fun environmentReady(sessionId: String): Boolean =
        File(environmentRoot(sessionId), ".ready").isFile && File(alpineDir, "bin/busybox").isFile

    suspend fun ensureEnvironment(sessionId: String): Boolean = withContext(Dispatchers.IO) {
        if (!isInstalled()) return@withContext false
        if (environmentReady(sessionId)) return@withContext true
        try {
            val root = environmentRoot(sessionId)
            val pub = publicFor(sessionId)
            File(pub, "workspace").mkdirs()
            File(pub, ".cue").mkdirs()
            // Usar quebras de linha reais: "\n" literal fazia o bash interpretar o caminho /dev/nulln[.
            File(pub, ".bashrc").writeText("cd /root/workspace 2>/dev/null\n[ -f /root/.cue/hook.sh ] && . /root/.cue/hook.sh\n")
            File(root, ".ready").writeText("v2")
            true
        } catch (t: Throwable) {
            android.util.Log.e("CueOpenSandbox", "Falha ao preparar dados do chat ${safeId(sessionId)}", t)
            appendLog("❌ Ambiente do chat: ${t.javaClass.simpleName}: ${t.message ?: "erro sem detalhes"}")
            false
        }
    }

    fun deleteEnvironment(sessionId: String) {
        runCatching { dev.cueopen.app.CueApp.instance.terminals.close(sessionId) }
        environmentRoot(sessionId).deleteRecursively()
    }

    private val _state = MutableStateFlow(if (isInstalled()) State.READY else State.NOT_INSTALLED)
    val state: StateFlow<State> = _state
    private val _log = MutableStateFlow("")
    val log: StateFlow<String> = _log

    private val http = OkHttpClient.Builder().readTimeout(10, TimeUnit.MINUTES).build()
    private var axsProcess: Process? = null

    /** Downloads externos do bootstrap usam o escopo Terminal; AXS localhost mantém [http] direto. */
    private fun terminalHttp(): OkHttpClient = CueApp.instance.proxyHttpClient(ProxyScope.TERMINAL, http)

    fun isInstalled() = File(prefix, ".configured").exists() && File(alpineDir, "bin/busybox").exists()

    fun workspaceFor(sessionId: String) = File(publicFor(sessionId), "workspace").apply { mkdirs() }
    /** Caminho visto de dentro do sandbox. */
    fun guestPath(sessionId: String) = "/root/workspace"

    /** Converte caminho do sandbox (/root/...) para arquivo real do Android. */
    fun hostFile(guestPath: String, sessionId: String): File {
        val p = guestPath.trim()
        return when {
            p.startsWith("/root/") || p == "/root" -> File(publicFor(sessionId), p.removePrefix("/root").trimStart('/'))
            p.startsWith("/home/") -> File(publicFor(sessionId), p.removePrefix("/home").trimStart('/'))
            // /public, /home e /root são o MESMO bind no proot; tracebacks do Python mostram /public/workspace/...
            p.startsWith("/public/") || p == "/public" -> File(publicFor(sessionId), p.removePrefix("/public").trimStart('/'))
            p.startsWith("/") -> File(rootfsFor(sessionId), p.trimStart('/'))
            else -> File(workspaceFor(sessionId), p)
        }
    }

    private fun appendLog(s: String) { _log.value = (_log.value + s + "\n").takeLast(20_000) }

    private val abi: String get() = Build.SUPPORTED_ABIS.first()
    private val alpineArch get() = when {
        abi.startsWith("arm64") -> "aarch64"
        abi.startsWith("armeabi") -> "armhf"
        abi.startsWith("x86_64") -> "x86_64"
        else -> error("Arquitetura não suportada: $abi")
    }

    private fun env(): MutableMap<String, String> = mutableMapOf(
        "PREFIX" to prefix.absolutePath,
        "NATIVE_DIR" to nativeDir,
        "FDROID" to "false",
        "HOME" to prefix.absolutePath,
        "TMPDIR" to ctx.cacheDir.absolutePath,
        "ANDROID_TZ" to TimeZone.getDefault().id,
        "PATH" to "/system/bin:/system/xbin",
        "CONNECTORS_ROOT" to connectorsDir.absolutePath,
    )
    private fun terminalProxyEnv(): Map<String, String> {
        val app = CueApp.instance
        val selectedId = app.store.settings.terminalProxyId ?: return emptyMap()
        val profile = app.proxyStore.profiles().firstOrNull { it.id == selectedId }
            ?: throw IllegalStateException("O perfil de proxy selecionado para o Terminal não existe mais.")
        val validation = ProxyValidator.validate(profile, ProxyScope.TERMINAL)
        check(validation.valid) { "Perfil de proxy inválido para o Terminal: ${validation.message}" }
        return ProxyEnvironment.variables(profile)
    }

    fun prepareScripts() = writeScripts()

    fun envArray(sessionId: String? = null): Array<String> = (env() + terminalProxyEnv() + mapOf(
        "TERM" to "xterm-256color", "LANG" to "C.UTF-8", "COLORTERM" to "truecolor",
        "ALPINE_ROOT" to (sessionId?.let { rootfsFor(it).absolutePath } ?: alpineDir.absolutePath),
        "SESSION_PUBLIC_ROOT" to (sessionId?.let { publicFor(it).absolutePath } ?: publicDir.absolutePath),
        // Monta os workspaces dos conectores (/root/connectors) só nas sessões de CHAT, para o agente
        // ler/copiar os arquivos. A própria sessão de um conector não precisa (já está dentro dela).
        "MOUNT_CONNECTORS" to (if (sessionId != null && isConnectorSession(sessionId)) "0" else "1"),
    ))
        .map { (k, v) -> "$k=$v" }.toTypedArray()

    private fun writeScripts() {
        listOf("init-sandbox.sh", "init-alpine.sh").forEach { name ->
            ctx.assets.open("sandbox/$name").use { i -> File(prefix, name).outputStream().use { i.copyTo(it) } }
        }
        // axs fica em jniLibs (libaxs.so) e é exposto como $PREFIX/axs, como no Acode
        val axs = File(prefix, "axs")
        axs.delete()
        runCatching { android.system.Os.symlink("$nativeDir/libaxs.so", axs.absolutePath) }
    }

    /** Instala o Alpine (download do minirootfs oficial) e pacotes básicos. */
    suspend fun install(packages: String): Boolean = withContext(Dispatchers.IO) {
        _state.value = State.INSTALLING
        _log.value = ""
        try {
            writeScripts()
            val url = "https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/$alpineArch/alpine-minirootfs-3.21.0-$alpineArch.tar.gz"
            val tar = File(ctx.cacheDir, "alpine.tar.gz")
            appendLog("⬇️  Baixando sistema de arquivos ($alpineArch)…")
            terminalHttp().newCall(Request.Builder().url(url).build()).execute().use { r ->
                require(r.isSuccessful) { "HTTP ${r.code} ao baixar Alpine" }
                val body = r.body!!
                val total = body.contentLength()
                var done = 0L; var lastPct = -1
                body.byteStream().use { input -> tar.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); done += n
                        val pct = if (total > 0) (done * 100 / total).toInt() else -1
                        if (pct / 10 != lastPct / 10) { appendLog("   $pct%"); lastPct = pct }
                    }
                } }
            }
            appendLog("📦  Extraindo…")
            alpineDir.deleteRecursively(); alpineDir.mkdirs()
            val ex = runHost(listOf("/system/bin/tar", "-xzf", tar.absolutePath, "-C", alpineDir.absolutePath))
            if (ex != 0 && !File(alpineDir, "bin/busybox").exists()) error("Falha ao extrair (tar=$ex)")
            tar.delete()
            File(alpineDir, "etc/resolv.conf").writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
            File(alpineDir, "tmp").mkdirs()
            appendLog("⚙️  Configurando pacotes…")
            val code = runHost(
                listOf("/system/bin/sh", File(prefix, "init-sandbox.sh").absolutePath, "--installing"),
                extraEnv = mapOf("CUE_PACKAGES" to packages),
            )
            if (code != 0) error("Configuração terminou com código $code")
            File(prefix, ".configured").mkdirs()
            if (!isInstalled()) error("Sistema de arquivos incompleto (busybox ausente)")
            appendLog("✅  Sandbox pronto")
            _state.value = State.READY
            // Os conectores vêm junto com o Linux: o Node (~50 MB) instala sozinho em Wi-Fi; no 4G, no primeiro uso.
            CueApp.instance.applicationScope.launch(Dispatchers.IO) {
                runCatching { if (dev.cueopen.app.connectors.ActivepiecesHub.onUnmeteredNetwork()) dev.cueopen.app.connectors.ActivepiecesHub.install() }
                runCatching { dev.cueopen.app.connectors.HubConnectors.syncConnector(CueApp.instance.store) }
            }
            true
        } catch (t: Throwable) {
            appendLog("❌  ${t.message}")
            dev.cueopen.app.diag.ErrorLog.log("sandbox", "Instalação do Linux falhou: ${t.message}\n${_log.value.takeLast(1500)}")
            _state.value = State.ERROR
            false
        }
    }

    private fun runHost(cmd: List<String>, extraEnv: Map<String, String> = emptyMap()): Int {
        val pb = ProcessBuilder(cmd).directory(prefix).redirectErrorStream(true)
        pb.environment().putAll(env() + extraEnv + terminalProxyEnv())
        val p = pb.start()
        p.inputStream.bufferedReader().forEachLine { appendLog(it) }
        return p.waitFor()
    }

    data class ExecResult(val exitCode: Int, val output: String, val timedOut: Boolean)

    /**
     * Executa um comando dentro do Alpine (não interativo). Usado pela ferramenta `shell` do agente.
     * O stdout/stderr é transmitido em [onOutput] para a aba Terminal do "computador".
     */
    suspend fun exec(
        command: String,
        cwd: String = "/root/workspace",
        timeoutSec: Long = 180,
        onOutput: (String) -> Unit = {},
        idleTimeoutSec: Long = 0,
        /** Monta /root (/public) da pasta DESTA conversa; sem isso, caminhos /root/workspace/... são de outro ambiente. */
        sessionId: String? = null,
    ): ExecResult = withContext(Dispatchers.IO) {
        if (!isInstalled()) return@withContext ExecResult(127, "Sandbox não instalado. Abra Configurações > Sandbox.", false)
        writeScripts()
        val wrapped = "mkdir -p '$cwd' && cd '$cwd' && $command"
        val pb = ProcessBuilder(
            "/system/bin/sh", File(prefix, "init-sandbox.sh").absolutePath, "/bin/sh", "-c", wrapped,
        ).directory(prefix).redirectErrorStream(true)
        pb.environment().putAll(env() + terminalProxyEnv() + sessionMounts(sessionId))
        val proc = pb.start()
        val out = StringBuilder()
        val lastOutputAt = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
        val reader = Thread {
            runCatching {
                proc.inputStream.bufferedReader().use { r ->
                    val buf = CharArray(4096)
                    while (true) {
                        val n = r.read(buf); if (n < 0) break
                        val chunk = String(buf, 0, n)
                        synchronized(out) { out.append(chunk) }
                        lastOutputAt.set(System.currentTimeMillis())
                        onOutput(chunk)
                    }
                }
            }
        }.apply { start() }
        // Watchdog: além do tempo total, mata o processo se ficar [idleTimeoutSec] sem nenhuma saída (travado).
        val deadline = System.currentTimeMillis() + timeoutSec * 1000
        var finished = false
        while (true) {
            if (proc.waitFor(1, TimeUnit.SECONDS)) { finished = true; break }
            val now = System.currentTimeMillis()
            if (now > deadline) break
            if (idleTimeoutSec > 0 && now - lastOutputAt.get() > idleTimeoutSec * 1000) {
                val msg = "\n[watchdog] sem saída há ${idleTimeoutSec}s — processo encerrado como travado.\n"
                synchronized(out) { out.append(msg) }; onOutput(msg)
                break
            }
        }
        if (!finished) proc.destroyForcibly()
        reader.join(2000)
        val text = synchronized(out) { out.toString() }
        ExecResult(if (finished) proc.exitValue() else -1, text, !finished)
    }

    /** Inicia um processo de longa duração dentro do Alpine (ex.: gateway MCP local), com saída no [logFile]. */
    /** Variáveis que fazem o proot montar /root e /public na pasta da conversa (como no terminal dela). */
    fun sessionMounts(sessionId: String?): Map<String, String> = if (sessionId == null) emptyMap() else mapOf(
        "ALPINE_ROOT" to rootfsFor(sessionId).absolutePath,
        "SESSION_PUBLIC_ROOT" to publicFor(sessionId).absolutePath,
        "MOUNT_CONNECTORS" to (if (isConnectorSession(sessionId)) "0" else "1"),
    )

    fun spawn(command: String, logFile: File, extraEnv: Map<String, String> = emptyMap(), sessionId: String? = null): Process {
        check(isInstalled()) { "Sandbox não instalado." }
        writeScripts()
        logFile.parentFile?.mkdirs()
        val pb = ProcessBuilder("/system/bin/sh", File(prefix, "init-sandbox.sh").absolutePath, "/bin/sh", "-c", command)
            .directory(prefix).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.to(logFile))
        pb.environment().putAll(env() + terminalProxyEnv() + sessionMounts(sessionId) + extraEnv)
        return pb.start()
    }

    // ---------------- Servidor AXS (terminal interativo) ----------------
    val axsPort = 8767

    suspend fun axsAlive(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            http.newBuilder().callTimeout(1, TimeUnit.SECONDS).build()
                .newCall(Request.Builder().url("http://127.0.0.1:$axsPort/status").build()).execute().use { it.code < 500 }
        }.getOrDefault(false)
    }

    /** Sobe o axs dentro do proot (se ainda não estiver de pé) e espera ficar pronto. */
    suspend fun ensureAxs(): Boolean = withContext(Dispatchers.IO) {
        if (!isInstalled()) return@withContext false
        if (axsAlive()) return@withContext true
        writeScripts()
        SandboxService.start(ctx, "Terminal ativo")
        val pb = ProcessBuilder("/system/bin/sh", File(prefix, "init-sandbox.sh").absolutePath)
            .directory(prefix).redirectErrorStream(true)
        pb.environment().putAll(env() + terminalProxyEnv())
        axsProcess = pb.start().also { p ->
            Thread { runCatching { p.inputStream.bufferedReader().forEachLine { appendLog("[axs] $it") } } }.start()
        }
        withTimeoutOrNull(15_000) {
            while (!axsAlive()) delay(300)
            true
        } ?: false
    }

    suspend fun createTerminal(cols: Int, rows: Int): String? = withContext(Dispatchers.IO) {
        if (!ensureAxs()) return@withContext null
        val body = """{"cols":$cols,"rows":$rows}""".toRequestBody("application/json".toMediaType())
        runCatching {
            http.newCall(Request.Builder().url("http://127.0.0.1:$axsPort/terminals").post(body).build())
                .execute().use { if (it.isSuccessful) it.body?.string()?.trim() else null }
        }.getOrNull()
    }

    fun stopAxs() {
        runCatching { dev.cueopen.app.CueApp.instance.terminals.closeAll() }
        axsProcess?.destroy(); axsProcess = null
        runCatching {
            val pid = File(prefix, "pid").readText().trim()
            Runtime.getRuntime().exec(arrayOf("kill", "-9", pid)).waitFor()
        }
        SandboxService.stop(ctx)
    }

    fun uninstall() {
        stopAxs()
        alpineDir.deleteRecursively()
        environmentsDir.deleteRecursively()
        connectorsDir.deleteRecursively()
        File(prefix, ".configured").deleteRecursively()
        _state.value = State.NOT_INSTALLED
    }
}

suspend fun Sandbox.resizeTerminal(pid: String, cols: Int, rows: Int) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    runCatching {
        okhttp3.OkHttpClient().newCall(
            okhttp3.Request.Builder().url("http://127.0.0.1:$axsPort/terminals/$pid/resize")
                .post("""{"cols":$cols,"rows":$rows}""".toRequestBody("application/json".toMediaType())).build()
        ).execute().close()
    }
}
