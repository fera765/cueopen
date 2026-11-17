package dev.cueopen.app.connectors

import dev.cueopen.app.CueApp
import dev.cueopen.app.sandbox.SandboxService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
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
    /** Registro em tempo real: vai para a tela e para um arquivo (sobrevive a reinícios e entra no diagnóstico). */
    private fun logLine(s: String) {
        _log.value = (_log.value + s).takeLast(30_000)
        runCatching { File(hostDir(), "install.log").appendText(s) }
    }
    fun installLogFile(): File = File(hostDir(), "install.log")
    fun fullLog(): String = runCatching { installLogFile().readText().takeLast(200_000) }.getOrDefault(_log.value) +
        "\n\n===== hub.log (execução) =====\n" + lastLog() +
        "\n\n===== redis.log =====\n" + runCatching { File(hostDir(), "redis.log").readText().takeLast(5000) }.getOrDefault("")

    /** Wi-Fi/rede sem limite de dados: só então o hub (~1 GB) instala sozinho. */
    fun onUnmeteredNetwork(): Boolean = runCatching {
        val cm = CueApp.instance.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }.getOrDefault(false)

    private val mutex = Mutex()
    private val installMutex = Mutex()
    @Volatile private var proc: Process? = null
    @Volatile private var redisProc: Process? = null
    private const val REDIS_PORT = 6380

    private fun portOpen(port: Int): Boolean = runCatching {
        java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", port), 500); true }
    }.getOrDefault(false)

    /**
     * Redis próprio no Alpine (o redis-memory-server embutido do Activepieces morre com código 1 no Android:
     * o Redis 7 recusa iniciar em kernels arm64 com o aviso ARM64-COW-BUG). Sem persistência, só no loopback.
     */
    private suspend fun ensureRedis(): Boolean {
        if (portOpen(REDIS_PORT)) return true
        val has = sandbox.exec("command -v redis-server >/dev/null || apk add --no-cache redis 2>&1 | tail -n 2; command -v redis-server",
            cwd = "/tmp", timeoutSec = 600, onOutput = { logLine(it) }, idleTimeoutSec = 300)
        if (has.exitCode != 0) { logLine("❌ Não consegui instalar o Redis no Linux do app.\n"); return false }
        redisProc?.destroy()
        redisProc = sandbox.spawn(
            "exec redis-server --bind 127.0.0.1 --port $REDIS_PORT --protected-mode yes --save '' --appendonly no " +
                "--daemonize no --io-threads 1 --ignore-warnings ARM64-COW-BUG",
            File(hostDir(), "redis.log"),
        )
        repeat(40) {
            if (portOpen(REDIS_PORT)) { logLine("Redis no ar (porta $REDIS_PORT).\n"); return true }
            if (redisProc?.isAlive != true) return@repeat
            delay(500)
        }
        val tail = runCatching { File(hostDir(), "redis.log").readText().takeLast(1500) }.getOrDefault("")
        logLine("❌ Redis não iniciou.\n$tail\n")
        dev.cueopen.app.diag.ErrorLog.log("hub", "Redis não iniciou.\n$tail")
        return false
    }
    private val http = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build()
    private val sandbox get() = CueApp.instance.sandbox
    private fun hostDir() = File(sandbox.alpineDir, HOST_DIR.trimStart('/')).apply { mkdirs() }
    private fun rootfs() = File(hostDir(), "rootfs")

    private object LocalSandbox { fun ready() = runCatching { CueApp.instance.sandbox.isInstalled() }.getOrDefault(false) }

    fun sandboxReady(): Boolean = LocalSandbox.ready()

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

    /**
     * Baixa a imagem oficial e monta o rootfs do hub (~1 GB). Roda em primeiro plano (serviço + wake lock) para o
     * Android não congelar o processo, com watchdog de inatividade e retomada: camadas ficam em cache verificado.
     */
    suspend fun install(): Boolean = installMutex.withLock { withContext(Dispatchers.IO) {
        if (installed()) { if (_state.value == State.NOT_INSTALLED) _state.value = State.STOPPED; return@withContext true }
        if (!LocalSandbox.ready()) { logLine("Instale o Linux (Alpine) primeiro.\n"); return@withContext false }
        _state.value = State.INSTALLING
        _log.value = ""
        runCatching { installLogFile().writeText("") }
        val ctx = CueApp.instance
        runCatching { SandboxService.start(ctx, "Instalando o hub de conectores…") }
        val wake = runCatching {
            (ctx.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager)
                .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "cueopen:hub-install").apply { acquire(3 * 60 * 60 * 1000L) }
        }.getOrNull()
        try {
            val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            logLine("== Instalação do hub · $stamp · ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE} · ${android.os.Build.SUPPORTED_ABIS.firstOrNull()} ==\n")
            logLine("Rede: ${if (onUnmeteredNetwork()) "Wi-Fi/sem limite" else "dados móveis"}\n")
            val script = File(hostDir(), "pull_image.py")
            CueApp.instance.assets.open("hub/pull_image.py").use { i -> script.outputStream().use { i.copyTo(it) } }
            logLine("Preparando ferramentas (python3, GNU tar)…\n")
            val prep = sandbox.exec("apk add --no-cache python3 tar gzip redis 2>&1 | tail -n 3", cwd = "/tmp", timeoutSec = 600, onOutput = { logLine(it) }, idleTimeoutSec = 300)
            if (prep.exitCode != 0) error("não consegui instalar python3/tar no Linux (código ${prep.exitCode})")
            logLine("Baixando o Activepieces (imagem oficial, ~1 GB). Se cair, toque em Instalar de novo: o download continua de onde parou.\n")
            val r = sandbox.exec(
                "python3 -u $HOST_DIR/pull_image.py $IMAGE $TAG $HOST_DIR/rootfs $HOST_DIR/cache",
                cwd = "/tmp", timeoutSec = 4 * 3600, onOutput = { logLine(it) }, idleTimeoutSec = 900,
            )
            if (r.exitCode != 0 || !installed()) error(when {
                r.timedOut -> "tempo total esgotado"
                r.output.contains("[watchdog]") -> "instalação travou (15 min sem progresso)"
                else -> r.output.lineSequence().lastOrNull { it.startsWith("ERRO:") }?.removePrefix("ERRO:")?.trim() ?: "código ${r.exitCode}"
            })
            File(rootfs(), "etc/resolv.conf").apply { parentFile?.mkdirs(); delete() }.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
            secret("jwt", 32); secret("enc", 16)
            logLine("✅ Hub instalado.\n")
            _state.value = State.STOPPED
            true
        } catch (t: Throwable) {
            logLine("❌ Falha ao instalar o hub: ${t.message}\n")
            dev.cueopen.app.diag.ErrorLog.log("hub", "Instalação do hub falhou: ${t.message}\n--- últimas linhas ---\n${_log.value.takeLast(3000)}")
            _state.value = State.ERROR
            false
        } finally {
            runCatching { if (wake?.isHeld == true) wake.release() }
        }
    } }

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
                // Passos de código do Activepieces compilam com o binário esbuild: garante que ele existe e executa.
                "if ! esbuild --version >/dev/null 2>&1; then " +
                "E=\$(find /usr/local/lib/node_modules /usr/src/app/node_modules -maxdepth 5 -path '*esbuild/bin/esbuild' -type f 2>/dev/null | head -n1); " +
                "if [ -n \"\$E\" ] && \"\$E\" --version >/dev/null 2>&1; then ln -sf \"\$E\" /usr/local/bin/esbuild; echo '[fluwy] esbuild vinculado'; " +
                "else echo '[fluwy] instalando esbuild…'; npm install -g --no-fund --no-audit esbuild@0.25.0 >/dev/null 2>&1 && echo '[fluwy] esbuild instalado' || echo '[fluwy] ERRO: esbuild não instalou'; fi; fi; " +
                "export AP_DB_TYPE=PGLITE AP_REDIS_TYPE=STANDALONE AP_REDIS_HOST=127.0.0.1 AP_REDIS_PORT=$REDIS_PORT AP_PORT=$PORT AP_FRONTEND_URL=$BASE AP_CONFIG_PATH=/opt/ap-data " +
                "AP_EXECUTION_MODE=UNSANDBOXED AP_TELEMETRY_ENABLED=false AP_WORKER_CONCURRENCY=1 NODE_ENV=production " +
                "NODE_OPTIONS=--max-old-space-size=640; " +
                "export AP_JWT_SECRET=\"\$(cat /opt/ap-secrets/jwt)\" AP_ENCRYPTION_KEY=\"\$(cat /opt/ap-secrets/enc)\"; " +
                // O PM2 avisa a cada 2 s que não lê /proc/uptime (restrição do Android): ruído que só enche o log.
                "sh ./docker-entrypoint.sh 2>&1 | grep --line-buffered -v 'pidusage'"
            logLine("🚀 Iniciando o hub (a primeira vez pode levar alguns minutos)…\n")
            if (!ensureRedis()) { _state.value = State.ERROR; return@withContext false }
            proc = sandbox.spawn(cmd, File(hostDir(), "hub.log"), mapOf("ALPINE_ROOT" to rootfs().absolutePath))
            runCatching { SandboxService.start(CueApp.instance, "Hub de conectores ativo") }
            val deadline = System.currentTimeMillis() + 10 * 60_000
            while (System.currentTimeMillis() < deadline) {
                if (healthy()) { _state.value = State.RUNNING; logLine("✅ Hub no ar em $BASE\n"); return@withContext true }
                if (proc?.isAlive != true) break
                delay(2000)
            }
            logLine("❌ O hub não iniciou.\n${lastLog().takeLast(3000)}\n")
            dev.cueopen.app.diag.ErrorLog.log("hub", "O hub não iniciou.\n${lastLog().takeLast(3000)}")
            _state.value = State.ERROR
            false
        }
    }

    suspend fun stop() = mutex.withLock {
        withContext(Dispatchers.IO) {
            proc?.let { p -> runCatching { p.destroy(); if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroyForcibly() } }
            proc = null
            redisProc?.let { p -> runCatching { p.destroy(); if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly() } }
            redisProc = null
            _state.value = if (installed()) State.STOPPED else State.NOT_INSTALLED
        }
    }

    /** Usado pelo agente: garante o hub no ar antes de chamar ferramentas. */
    suspend fun ensureRunning(): Boolean { touch(); return healthy() || start() }

    // ---------------- Ciclo de vida automático (sem o usuário iniciar à mão) ----------------
    @Volatile private var lastUse = 0L
    private const val IDLE_STOP_MS = 30 * 60_000L
    @Volatile private var watchdogStarted = false

    fun touch() { lastUse = System.currentTimeMillis() }

    /**
     * Chamado quando o app abre/volta ao primeiro plano: sobe o hub em segundo plano se ele está instalado e o
     * conector não foi desligado pelo usuário. Só um início por vez (mutex); se já está no ar, não faz nada.
     */
    fun onAppForeground() {
        touch()
        val app = CueApp.instance
        app.applicationScope.launch(Dispatchers.IO) {
            if (!installed() || !LocalSandbox.ready()) return@launch
            if (app.store.connector(CONNECTOR_ID)?.enabled == false) return@launch
            if (_state.value == State.INSTALLING || _state.value == State.STARTING) return@launch
            if (!healthy()) runCatching { start() } else _state.value = State.RUNNING
        }
        startIdleWatchdog()
    }

    /** Economia de bateria/RAM: desliga o hub após 30 min sem uso; ele volta sozinho no próximo uso. */
    private fun startIdleWatchdog() {
        if (watchdogStarted) return
        watchdogStarted = true
        CueApp.instance.applicationScope.launch(Dispatchers.IO) {
            while (true) {
                delay(5 * 60_000L)
                if (proc != null && System.currentTimeMillis() - lastUse > IDLE_STOP_MS && _state.value == State.RUNNING) {
                    logLine("💤 Hub parado por inatividade (economia de bateria). Volta sozinho no próximo uso.\n")
                    stop()
                }
            }
        }
    }

    fun lastLog(): String = runCatching { File(hostDir(), "hub.log").readText().takeLast(20_000) }.getOrDefault("")

    fun connector() = dev.cueopen.app.data.Connector(
        CONNECTOR_ID, "Activepieces Hub", MCP_URL,
        "Hub local com 700+ apps (Gmail, Instagram, Facebook, GitHub, Supabase…)",
        needsAuth = true, enabled = true, gatewayRef = REF,
    )
}
