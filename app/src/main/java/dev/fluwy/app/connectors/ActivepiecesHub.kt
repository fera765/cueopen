package dev.fluwy.app.connectors

import dev.fluwy.app.FluwyApp
import dev.fluwy.app.sandbox.SandboxService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Mini-hub de conectores: roda os 600+ conectores ("pieces") do Activepieces (MIT) sem o servidor completo.
 *
 * Um único processo Node (assets/minihub/minihub.js) dentro do Alpine que o app já usa: sem segunda distribuição
 * Linux, sem banco, sem Redis, sem painel. Instalar = `apk add nodejs npm` (~50 MB). Cada conector é um pacote npm
 * baixado só quando é usado; a versão é a verificada pelo CI (catálogo do app) e atualizar troca só a piece, com
 * teste de carga e volta automática para a versão anterior se a nova não funcionar.
 *
 * As contas conectadas ficam no app (criptografadas, HubApi); o mini-hub recebe a conexão a cada pedido e devolve
 * tokens renovados. Escuta só em 127.0.0.1 e exige um token aleatório do app.
 */
object ActivepiecesHub {
    const val PORT = 8791
    const val BASE = "http://127.0.0.1:$PORT"
    const val MCP_URL = "$BASE/"
    const val CONNECTOR_ID = "activepieces_hub"
    const val REF = "hub:activepieces"
    private const val DIR = "/opt/fluwy-minihub"
    private const val OLD_HUB_DIR = "opt/fluwy-hub"
    /** Sem uso por este tempo, o processo Node é parado (volta em ~1 s no próximo uso). */
    private const val IDLE_STOP_MS = 30 * 60_000L

    enum class State { NOT_INSTALLED, INSTALLING, STOPPED, STARTING, RUNNING, ERROR }

    private val _state = MutableStateFlow(State.NOT_INSTALLED)
    val state: StateFlow<State> = _state
    private val _log = MutableStateFlow("")
    val log: StateFlow<String> = _log

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val jsonType = "application/json".toMediaType()
    private val quick = OkHttpClient.Builder().connectTimeout(1, TimeUnit.SECONDS).readTimeout(3, TimeUnit.SECONDS).build()
    private val mutex = Mutex()
    private val installMutex = Mutex()
    @Volatile private var proc: Process? = null
    @Volatile private var lastUse = 0L
    @Volatile private var watchdogStarted = false

    private val sandbox get() = FluwyApp.instance.sandbox
    private fun hostDir() = File(sandbox.alpineDir, DIR.trimStart('/')).apply { mkdirs() }
    private fun logFile() = File(hostDir(), "minihub.log")
    fun installLogFile(): File = File(hostDir(), "install.log")

    private fun logLine(s: String) {
        _log.value = (_log.value + s).takeLast(30_000)
        runCatching { installLogFile().appendText(s) }
    }

    fun fullLog(): String = runCatching { installLogFile().readText().takeLast(100_000) }.getOrDefault(_log.value) +
        "\n\n===== minihub.log (execução) =====\n" + lastLog()

    fun lastLog(): String = runCatching { logFile().readText().takeLast(20_000) }.getOrDefault("")

    fun onUnmeteredNetwork(): Boolean = runCatching {
        val cm = FluwyApp.instance.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }.getOrDefault(false)

    fun sandboxReady(): Boolean = runCatching { sandbox.isInstalled() }.getOrDefault(false)

    private fun nodeInstalled(): Boolean = runCatching {
        File(sandbox.alpineDir, "usr/bin/node").exists() && (File(sandbox.alpineDir, "usr/bin/npm").exists() || File(sandbox.alpineDir, "usr/lib/node_modules/npm/bin/npm-cli.js").exists())
    }.getOrDefault(false)

    fun installed(): Boolean = sandboxReady() && nodeInstalled()

    /** Token do app ↔ mini-hub (arquivo 600 dentro do Alpine; o processo Node lê de lá). */
    private fun token(): String {
        val f = File(hostDir(), "token")
        f.takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.length >= 32 }?.let { return it }
        val v = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        f.writeText(v); f.setReadable(false, false); f.setReadable(true, true)
        return v
    }

    private fun copyScript() {
        val target = File(hostDir(), "minihub.js")
        val bytes = FluwyApp.instance.assets.open("minihub/minihub.js").use { it.readBytes() }
        if (!target.exists() || !target.readBytes().contentEquals(bytes)) {
            target.writeBytes(bytes)
            // Script novo (APK atualizado): o processo antigo precisa reiniciar para usar o código novo.
            proc?.let { p -> runCatching { p.destroy() } }; proc = null
        }
    }

    suspend fun refresh(): State = withContext(Dispatchers.IO) {
        if (_state.value == State.INSTALLING || _state.value == State.STARTING) return@withContext _state.value
        _state.value = when {
            !installed() -> State.NOT_INSTALLED
            healthy() -> State.RUNNING
            else -> State.STOPPED
        }
        _state.value
    }

    private fun health(): JsonObject? = runCatching {
        quick.newCall(Request.Builder().url("$BASE/health").build()).execute().use { r ->
            if (!r.isSuccessful) null else json.parseToJsonElement(r.body?.string().orEmpty()).jsonObject
        }
    }.getOrNull()

    fun healthy(): Boolean = (health()?.get("ok") as? JsonPrimitive)?.contentOrNull == "true"

    private fun assetSha(): String = runCatching {
        val bytes = FluwyApp.instance.assets.open("minihub/minihub.js").use { it.readBytes() }
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }.take(16)
    }.getOrDefault("")

    /** No ar e com o script deste APK (um processo de versão anterior que sobrou é encerrado). */
    private suspend fun healthyCurrent(): Boolean {
        val h = health() ?: return false
        if ((h["ok"] as? JsonPrimitive)?.contentOrNull != "true") return false
        val running = (h["script"] as? JsonPrimitive)?.contentOrNull
        if (running == null || running == assetSha()) return true
        logLine("Reiniciando o mini-hub (versão nova do app).\n")
        proc?.let { p -> runCatching { p.destroy() } }; proc = null
        runCatching { sandbox.exec("pkill -f '$DIR/minihub.js' 2>/dev/null; sleep 1", cwd = "/tmp", timeoutSec = 20) }
        return false
    }

    /** Instala o Node no Alpine (~50 MB) e apaga o hub antigo de ~1 GB, se existir. */
    suspend fun install(): Boolean = installMutex.withLock { withContext(Dispatchers.IO) {
        if (!sandboxReady()) { logLine("Instale o Linux (Alpine) do app primeiro.\n"); _state.value = State.NOT_INSTALLED; return@withContext false }
        if (nodeInstalled()) { removeOldHub(); copyScript(); if (_state.value == State.NOT_INSTALLED) _state.value = State.STOPPED; return@withContext true }
        _state.value = State.INSTALLING
        _log.value = ""
        runCatching { installLogFile().writeText("") }
        runCatching { SandboxService.start(FluwyApp.instance, "Instalando os conectores…") }
        try {
            val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            logLine("== Mini-hub de conectores · $stamp · ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE} ==\n")
            logLine("Instalando o Node.js no Linux do app (~50 MB, uma vez só)…\n")
            val r = sandbox.exec("apk add --no-cache nodejs npm 2>&1 | tail -n 4; node --version && npm --version",
                cwd = "/tmp", timeoutSec = 1200, onOutput = { logLine(it) }, idleTimeoutSec = 600)
            if (r.exitCode != 0 || !nodeInstalled()) error(if (r.timedOut) "tempo esgotado" else "apk add nodejs npm falhou (código ${r.exitCode})")
            removeOldHub()
            copyScript()
            logLine("✅ Pronto. Cada conector é baixado só na primeira vez que for usado.\n")
            _state.value = State.STOPPED
            true
        } catch (t: Throwable) {
            logLine("❌ Falha ao instalar: ${t.message}\n")
            dev.fluwy.app.diag.ErrorLog.log("hub", "Instalação do mini-hub falhou: ${t.message}\n${_log.value.takeLast(2000)}")
            _state.value = State.ERROR
            false
        }
    } }

    /** O hub antigo (imagem completa do Activepieces, ~1 GB) não é mais usado: libera o espaço. */
    private suspend fun removeOldHub() {
        val old = File(sandbox.alpineDir, OLD_HUB_DIR)
        if (!old.exists()) return
        logLine("Removendo o hub antigo (imagem completa do Activepieces) para liberar espaço…\n")
        runCatching { sandbox.exec("rm -rf /$OLD_HUB_DIR", cwd = "/tmp", timeoutSec = 1800, idleTimeoutSec = 900) }
        if (old.exists()) runCatching { old.deleteRecursively() }
        runCatching { dev.fluwy.app.uiconnector.CredentialVault(FluwyApp.instance).clear("activepieces_hub_login") }
    }

    fun installInBackground(): kotlinx.coroutines.Job = FluwyApp.instance.applicationScope.launch(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
        if (install()) start()
    }

    fun updateInBackground(): kotlinx.coroutines.Job = FluwyApp.instance.applicationScope.launch(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
        update()
    }

    /** O mini-hub sempre executa ações direto (não depende da versão do Activepieces). */
    @Suppress("UNUSED_PARAMETER")
    fun supportsDirectActions(store: dev.fluwy.app.data.Store): Boolean? = if (installed()) true else null

    /**
     * Atualiza cada conector instalado para a versão mais nova do Activepieces. O mini-hub testa a versão nova
     * (carrega, mesmas ações) e, se algo falhar, volta sozinho para a anterior — o app nunca fica sem o conector.
     */
    suspend fun update(): Boolean = installMutex.withLock { withContext(Dispatchers.IO) {
        if (!installed()) return@withContext false
        val prev = _state.value
        _state.value = State.INSTALLING
        runCatching { SandboxService.start(FluwyApp.instance, "Atualizando os conectores…") }
        try {
            logLine("== Atualização dos conectores ==\n")
            sandbox.exec("apk upgrade --no-cache nodejs npm 2>&1 | tail -n 2", cwd = "/tmp", timeoutSec = 900, onOutput = { logLine(it) }, idleTimeoutSec = 600)
            copyScript()
            _state.value = State.STOPPED
            if (!startLocked()) error("o mini-hub não iniciou")
            val installedPieces = call("/installed", JsonObject(emptyMap()), 30)["pieces"] as? JsonObject
            val names = installedPieces?.keys.orEmpty()
            if (names.isEmpty()) logLine("Nenhum conector instalado ainda.\n")
            var updated = 0; var failed = 0
            for (n in names) {
                _state.value = State.INSTALLING
                logLine("• ${n.removePrefix("@activepieces/piece-")}: ")
                val r = runCatching { call("/update", JsonObject(mapOf("name" to JsonPrimitive(n))), 1500) }
                r.onSuccess { o ->
                    val err = (o["error"] as? JsonPrimitive)?.contentOrNull
                    val v = (o["version"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                    when {
                        err != null -> { failed++; logLine("mantida a $v ($err)\n") }
                        (o["updated"] as? JsonPrimitive)?.contentOrNull == "true" -> { updated++; logLine("atualizado para $v\n") }
                        else -> logLine("já na mais nova ($v)\n")
                    }
                }.onFailure { failed++; logLine("erro: ${it.message}\n") }
            }
            HubApi.invalidateCatalog()
            logLine("✅ $updated atualizado(s)" + (if (failed > 0) ", $failed mantido(s) na versão anterior" else "") + ".\n")
            _state.value = if (healthy()) State.RUNNING else State.STOPPED
            true
        } catch (t: Throwable) {
            logLine("❌ Falha ao atualizar: ${t.message}. Os conectores continuam na versão atual.\n")
            dev.fluwy.app.diag.ErrorLog.log("hub", "Atualização do mini-hub falhou: ${t.message}")
            _state.value = if (healthy()) State.RUNNING else if (prev == State.ERROR) State.ERROR else State.STOPPED
            false
        }
    } }

    suspend fun start(): Boolean = mutex.withLock { withContext(Dispatchers.IO) { startLocked() } }

    private suspend fun startLocked(): Boolean {
        copyScript()
        if (healthyCurrent()) { _state.value = State.RUNNING; startIdleWatchdog(); return true }
        if (!installed()) { _state.value = State.NOT_INSTALLED; return false }
        _state.value = State.STARTING
        proc?.let { p -> runCatching { p.destroy(); p.waitFor(2, TimeUnit.SECONDS) } }; proc = null
        token()
        val cmd = "export HOME=$DIR/home PATH=/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin MINIHUB_ROOT=$DIR MINIHUB_PORT=$PORT " +
            "MINIHUB_TOKEN_FILE=$DIR/token NODE_OPTIONS=--max-old-space-size=384 NODE_COMPILE_CACHE=$DIR/node-cache; " +
            "mkdir -p $DIR/home; cd $DIR && exec node $DIR/minihub.js"
        val t0 = System.currentTimeMillis()
        proc = runCatching { sandbox.spawn(cmd, logFile()) }.getOrElse { logLine("❌ Não consegui iniciar o Node: ${it.message}\n"); _state.value = State.ERROR; return false }
        val deadline = t0 + 60_000
        while (System.currentTimeMillis() < deadline) {
            if (healthy()) {
                _state.value = State.RUNNING
                logLine("✅ Mini-hub no ar (${(System.currentTimeMillis() - t0) / 100 / 10.0}s)\n")
                startIdleWatchdog()
                return true
            }
            if (proc?.isAlive != true) break
            delay(250)
        }
        val tail = lastLog().takeLast(2500)
        logLine("❌ O mini-hub não iniciou.\n$tail\n")
        dev.fluwy.app.diag.ErrorLog.log("hub", "O mini-hub não iniciou.\n$tail")
        _state.value = State.ERROR
        return false
    }

    suspend fun stop() = mutex.withLock {
        withContext(Dispatchers.IO) {
            proc?.let { p -> runCatching { p.destroy(); if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly() } }
            proc = null
            _state.value = if (installed()) State.STOPPED else State.NOT_INSTALLED
        }
    }

    /** Garante o mini-hub no ar (instala o Node na primeira vez, se o Linux do app já existe). */
    suspend fun ensureRunning(): Boolean {
        touch()
        if (healthy() && proc?.isAlive == true) return true
        if (!installed() && !(sandboxReady() && install())) return false
        return start()
    }

    fun touch() { lastUse = System.currentTimeMillis() }

    /** Ao abrir o app: copia o script novo (se o APK mudou). Não sobe o Node: ele sobe sozinho no primeiro uso. */
    fun onAppForeground() {
        touch()
        FluwyApp.instance.applicationScope.launch(Dispatchers.IO) {
            if (!sandboxReady()) return@launch
            runCatching { if (installed()) { removeOldHub(); copyScript() } }
            refresh()
            HubConnectors.syncConnector(FluwyApp.instance.store)
        }
    }

    private fun startIdleWatchdog() {
        if (watchdogStarted) return
        watchdogStarted = true
        FluwyApp.instance.applicationScope.launch(Dispatchers.IO) {
            while (true) {
                delay(5 * 60_000L)
                if (proc != null && System.currentTimeMillis() - lastUse > IDLE_STOP_MS && _state.value == State.RUNNING) stop()
            }
        }
    }

    class MiniHubError(message: String, val code: Int = 0) : Exception(message)

    private val clients = java.util.concurrent.ConcurrentHashMap<Long, OkHttpClient>()
    private fun client(timeoutSec: Long) = clients.getOrPut(timeoutSec) {
        OkHttpClient.Builder().connectTimeout(3, TimeUnit.SECONDS).readTimeout(timeoutSec, TimeUnit.SECONDS).callTimeout(timeoutSec + 5, TimeUnit.SECONDS).build()
    }

    /** POST JSON no mini-hub (sobe o processo se preciso). Erros HTTP viram [MiniHubError] com a mensagem do mini-hub. */
    suspend fun call(path: String, body: JsonObject, timeoutSec: Long = 240): JsonObject = withContext(Dispatchers.IO) {
        if (!ensureRunning()) throw MiniHubError(if (!sandboxReady()) "O Linux do app ainda não foi instalado (Apps › Terminal)." else "O mini-hub de conectores não iniciou. Veja o registro em Apps › Conectores.")
        touch()
        fun once(): Pair<Int, String> {
            val req = Request.Builder().url(BASE + path).header("x-minihub-token", token()).post(body.toString().toRequestBody(jsonType)).build()
            return client(timeoutSec).newCall(req).execute().use { r -> r.code to r.body?.string().orEmpty() }
        }
        val (code, text) = try { once() } catch (e: java.io.IOException) {
            // Processo reiniciando (ex.: script novo): sobe de novo e tenta uma vez.
            if (e is java.net.SocketTimeoutException || !start()) throw MiniHubError("Mini-hub sem resposta: ${e.message}")
            once()
        }
        touch()
        val o = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
        if (code !in 200..299) throw MiniHubError((o?.get("error") as? JsonPrimitive)?.contentOrNull ?: "HTTP $code", code)
        o ?: throw MiniHubError("Resposta inválida do mini-hub")
    }

    fun connector() = dev.fluwy.app.data.Connector(
        CONNECTOR_ID, "Conectores (Activepieces)", MCP_URL,
        "600+ apps (Gmail, Google Drive, Sheets, Slack, Notion, GitHub…) executados no próprio aparelho",
        needsAuth = false, enabled = true, gatewayRef = REF,
    )
}
