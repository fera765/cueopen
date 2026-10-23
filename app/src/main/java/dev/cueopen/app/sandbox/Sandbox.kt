package dev.cueopen.app.sandbox

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    /** Montado como /root e /home dentro do Alpine. */
    val publicDir = File(prefix, "public").apply { mkdirs() }
    val workspaceRoot = File(publicDir, "workspace").apply { mkdirs() }

    private val _state = MutableStateFlow(if (isInstalled()) State.READY else State.NOT_INSTALLED)
    val state: StateFlow<State> = _state
    private val _log = MutableStateFlow("")
    val log: StateFlow<String> = _log

    private val http = OkHttpClient.Builder().readTimeout(10, TimeUnit.MINUTES).build()
    private var axsProcess: Process? = null

    fun isInstalled() = File(prefix, ".configured").exists() && File(alpineDir, "bin/busybox").exists()

    fun workspaceFor(sessionId: String) = File(workspaceRoot, sessionId).apply { mkdirs() }
    /** Caminho visto de dentro do sandbox. */
    fun guestPath(sessionId: String) = "/root/workspace/$sessionId"

    /** Converte caminho do sandbox (/root/...) para arquivo real do Android. */
    fun hostFile(guestPath: String, sessionId: String): File {
        val p = guestPath.trim()
        return when {
            p.startsWith("/root/") || p == "/root" -> File(publicDir, p.removePrefix("/root").trimStart('/'))
            p.startsWith("/home/") -> File(publicDir, p.removePrefix("/home").trimStart('/'))
            p.startsWith("/") -> File(alpineDir, p.trimStart('/'))
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
    )

    fun prepareScripts() = writeScripts()

    fun envArray(): Array<String> = (env() + mapOf("TERM" to "xterm-256color", "LANG" to "C.UTF-8", "COLORTERM" to "truecolor"))
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
            http.newCall(Request.Builder().url(url).build()).execute().use { r ->
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
            true
        } catch (t: Throwable) {
            appendLog("❌  ${t.message}")
            _state.value = State.ERROR
            false
        }
    }

    private fun runHost(cmd: List<String>, extraEnv: Map<String, String> = emptyMap()): Int {
        val pb = ProcessBuilder(cmd).directory(prefix).redirectErrorStream(true)
        pb.environment().putAll(env() + extraEnv)
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
    ): ExecResult = withContext(Dispatchers.IO) {
        if (!isInstalled()) return@withContext ExecResult(127, "Sandbox não instalado. Abra Configurações > Sandbox.", false)
        writeScripts()
        val wrapped = "mkdir -p '$cwd' && cd '$cwd' && $command"
        val pb = ProcessBuilder(
            "/system/bin/sh", File(prefix, "init-sandbox.sh").absolutePath, "/bin/sh", "-c", wrapped,
        ).directory(prefix).redirectErrorStream(true)
        pb.environment().putAll(env())
        val proc = pb.start()
        val out = StringBuilder()
        val reader = Thread {
            runCatching {
                proc.inputStream.bufferedReader().use { r ->
                    val buf = CharArray(4096)
                    while (true) {
                        val n = r.read(buf); if (n < 0) break
                        val chunk = String(buf, 0, n)
                        synchronized(out) { out.append(chunk) }
                        onOutput(chunk)
                    }
                }
            }
        }.apply { start() }
        val finished = proc.waitFor(timeoutSec, TimeUnit.SECONDS)
        if (!finished) proc.destroyForcibly()
        reader.join(2000)
        val text = synchronized(out) { out.toString() }
        ExecResult(if (finished) proc.exitValue() else -1, text, !finished)
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
        pb.environment().putAll(env())
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
