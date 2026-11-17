package dev.cueopen.app.connectors

import dev.cueopen.app.CueApp
import dev.cueopen.app.sandbox.SandboxService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Hub de conectores 100% local: Activepieces Community Edition (github.com/activepieces/activepieces, MIT).
 *
 * - 700+ apps (Gmail, Google Sheets, Instagram, Facebook Pages, GitHub, Supabase, Slack, Notion, SEO…).
 * - Login OAuth com um clique pelos apps OAuth gerenciados do Activepieces (ligados por padrão também no self-hosted);
 *   apps sem app gerenciado pedem a chave/credencial do próprio serviço.
 * - Um único servidor MCP por projeto em http://127.0.0.1:8791/mcp, com as ferramentas escolhidas no painel.
 *
 * A imagem oficial (Debian) é baixada do Docker Hub pelo Alpine (pull_image.py, sem Docker) e roda como um segundo
 * rootfs no mesmo proot do app, com banco embutido (PGLite) e fila em memória. Nada fica em servidores de terceiros.
 */
object ActivepiecesHub {
    const val PORT = 8791
    const val BASE = "http://127.0.0.1:$PORT"
    const val MCP_URL = "$BASE/mcp"
    const val CONNECTOR_ID = "activepieces_hub"
    const val REF = "hub:activepieces"
    private const val IMAGE = "activepieces/activepieces"
    private const val TAG = "latest"
    private const val HOST_DIR = "/opt/fluwy-hub"

    enum class State { NOT_INSTALLED, INSTALLING, STOPPED, STARTING, RUNNING, ERROR }

    private val _state = MutableStateFlow(State.NOT_INSTALLED)
    val state: StateFlow<State> = _state
    private val _log = MutableStateFlow("")
    val log: StateFlow<String> = _log
    private fun logLine(s: String) { _log.value = (_log.value + s).takeLast(8000) }

    private val mutex = Mutex()
    @Volatile private var proc: Process? = null
    private val http = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build()
    private val sandbox get() = CueApp.instance.sandbox
    private fun hostDir() = File(sandbox.alpineDir, HOST_DIR.trimStart('/')).apply { mkdirs() }
    private fun rootfs() = File(hostDir(), "rootfs")

    fun installed(): Boolean = runCatching { File(rootfs(), "usr/src/app/docker-entrypoint.sh").exists() }.getOrDefault(false)

    /** Atualiza o estado olhando o disco e a porta. */
    suspend fun refresh(): State = withContext(Dispatchers.IO) {
        if (_state.value == State.INSTALLING || _state.value == State.STARTING) return@withContext _state.value
        _state.value = when {
            !installed() -> State.NOT_INSTALLED
            healthy() -> State.RUNNING
            else -> State.STOPPED
        }
        _state.value
    }

    fun healthy(): Boolean = runCatching {
        http.newCall(Request.Builder().url("$BASE/api/v1/health").build()).execute().use { it.isSuccessful }
    }.getOrDefault(false)

    private fun secret(name: String, bytes: Int): String {
        val dir = File(rootfs(), "opt/ap-secrets").apply { mkdirs() }
        val f = File(dir, name)
        f.takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val b = ByteArray(bytes).also { SecureRandom().nextBytes(it) }
        val v = b.joinToString("") { "%02x".format(it) }
        f.writeText(v); f.setReadable(false, false); f.setReadable(true, true)
        return v
    }

    /** Baixa a imagem oficial e monta o rootfs do hub (~1 GB). Precisa do Linux do app instalado. */
    suspend fun install(): Boolean = withContext(Dispatchers.IO) {
        if (!LocalMcpGateway.sandboxReady()) { logLine("Instale o Linux (Alpine) primeiro.\n"); return@withContext false }
        _state.value = State.INSTALLING
        try {
            val script = File(hostDir(), "pull_image.py")
            CueApp.instance.assets.open("hub/pull_image.py").use { i -> script.outputStream().use { i.copyTo(it) } }
            logLine("⬇️ Baixando o Activepieces (imagem oficial, ~1 GB). Use Wi-Fi.\n")
            val r = sandbox.exec(
                "set -e; command -v python3 >/dev/null || apk add --no-cache python3 >/dev/null; " +
                    "python3 $HOST_DIR/pull_image.py $IMAGE $TAG $HOST_DIR/rootfs",
                cwd = "/tmp", timeoutSec = 3 * 3600,
            ) { logLine(it) }
            if (r.exitCode != 0 || !installed()) error(if (r.timedOut) "tempo esgotado" else "código ${r.exitCode}")
            File(rootfs(), "etc/resolv.conf").apply { parentFile?.mkdirs(); delete() }.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
            secret("jwt", 32); secret("enc", 16)
            logLine("✅ Hub instalado.\n")
            _state.value = State.STOPPED
            true
        } catch (t: Throwable) {
            logLine("❌ Falha ao instalar o hub: ${t.message}\n")
            _state.value = State.ERROR
            false
        }
    }

    /** Sobe o hub (app + worker) e espera o /api/v1/health responder. A primeira partida demora alguns minutos. */
    suspend fun start(): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (healthy()) { _state.value = State.RUNNING; return@withContext true }
            if (!installed()) { _state.value = State.NOT_INSTALLED; return@withContext false }
            _state.value = State.STARTING
            proc?.destroy(); proc = null
            secret("jwt", 32); secret("enc", 16)
            val cmd = "export PATH=/usr/local/bin:/usr/local/sbin:/usr/bin:/usr/sbin:/bin:/sbin HOME=/opt/ap-home; " +
                "mkdir -p /opt/ap-home /opt/ap-data; cd /usr/src/app; " +
                "export AP_DB_TYPE=PGLITE AP_REDIS_TYPE=MEMORY AP_PORT=$PORT AP_FRONTEND_URL=$BASE AP_CONFIG_PATH=/opt/ap-data " +
                "AP_EXECUTION_MODE=UNSANDBOXED AP_TELEMETRY_ENABLED=false AP_WORKER_CONCURRENCY=2 NODE_ENV=production; " +
                "export AP_JWT_SECRET=\"\$(cat /opt/ap-secrets/jwt)\" AP_ENCRYPTION_KEY=\"\$(cat /opt/ap-secrets/enc)\"; " +
                "exec sh ./docker-entrypoint.sh"
            logLine("🚀 Iniciando o hub (a primeira vez pode levar alguns minutos)…\n")
            proc = sandbox.spawn(cmd, File(hostDir(), "hub.log"), mapOf("ALPINE_ROOT" to rootfs().absolutePath))
            runCatching { SandboxService.start(CueApp.instance, "Hub de conectores ativo") }
            val deadline = System.currentTimeMillis() + 10 * 60_000
            while (System.currentTimeMillis() < deadline) {
                if (healthy()) { _state.value = State.RUNNING; logLine("✅ Hub no ar em $BASE\n"); return@withContext true }
                if (proc?.isAlive != true) break
                delay(2000)
            }
            logLine("❌ O hub não iniciou.\n${lastLog()}\n")
            _state.value = State.ERROR
            false
        }
    }

    suspend fun stop() = mutex.withLock {
        withContext(Dispatchers.IO) {
            proc?.let { p -> runCatching { p.destroy(); if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly() } }
            proc = null
            _state.value = if (installed()) State.STOPPED else State.NOT_INSTALLED
        }
    }

    /** Usado pelo agente: garante o hub no ar antes de chamar ferramentas. */
    suspend fun ensureRunning(): Boolean = healthy() || start()

    fun lastLog(): String = runCatching { File(hostDir(), "hub.log").readText().takeLast(1500) }.getOrDefault("")

    fun connector() = dev.cueopen.app.data.Connector(
        CONNECTOR_ID, "Activepieces Hub", MCP_URL,
        "Hub local com 700+ apps (Gmail, Instagram, Facebook, GitHub, Supabase…)",
        needsAuth = true, enabled = true, gatewayRef = REF,
    )
}
