package dev.cueopen.app.connectors

import android.content.Context
import dev.cueopen.app.CueApp
import dev.cueopen.app.sandbox.Sandbox
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Gateway MCP 100% local, dentro do Alpine do app.
 *
 * Motor: mcp-proxy (github.com/sparfenyuk/mcp-proxy, MIT) num venv em /opt/fluwy-mcp. Ele sobe cada servidor
 * MCP stdio (npx/uvx) e expõe em http://127.0.0.1:8790/servers/<nome>/mcp (Streamable HTTP), protegido por
 * um token Bearer aleatório guardado só no armazenamento privado do app.
 * Catálogo: servers.json do MCPHub (github.com/samanhappy/mcphub, Apache-2.0), embutido em assets/mcp.
 * Nada passa por gateway externo: o tráfego vai do app para o loopback do aparelho.
 */
object LocalMcpGateway {
    const val PORT = 8790
    const val REF_PREFIX = "local:"
    private const val DIR = "/opt/fluwy-mcp"

    /** Ambiente comum (venv, uv e npm com cache/ferramentas dentro de /opt/fluwy-mcp, fora das pastas por chat). */
    private const val ENV = "export PATH=$DIR/venv/bin:$DIR/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin; " +
        "export UV_TOOL_DIR=$DIR/uv-tools UV_TOOL_BIN_DIR=$DIR/bin UV_CACHE_DIR=$DIR/uv-cache UV_PYTHON_PREFERENCE=only-system; " +
        "export npm_config_cache=$DIR/npm-cache npm_config_yes=true npm_config_update_notifier=false; "

    data class Param(val key: String, val desc: String, val required: Boolean, val example: String)
    data class Server(
        val id: String, val name: String, val desc: String, val category: String, val official: Boolean,
        val cmd: String, val args: List<String>, val env: Map<String, String>, val params: List<Param>, val home: String,
    )

    private val _log = MutableStateFlow("")
    val log: StateFlow<String> = _log
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy
    private fun logLine(s: String) { _log.value = (_log.value + s).takeLast(6000) }

    private val mutex = Mutex()
    @Volatile private var proc: Process? = null
    private val http = OkHttpClient.Builder().connectTimeout(2, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS).build()
    private val sandbox get() = CueApp.instance.sandbox
    private fun hostDir() = File(sandbox.alpineDir, DIR.trimStart('/')).apply { mkdirs() }
    private fun configFile() = File(hostDir(), "servers.json")

    fun mcpUrl(name: String) = "http://127.0.0.1:$PORT/servers/$name/mcp"
    fun ref(name: String) = REF_PREFIX + name
    fun nameOf(ref: String?): String? = ref?.takeIf { it.startsWith(REF_PREFIX) }?.removePrefix(REF_PREFIX)?.takeIf { it.isNotBlank() }
    fun safeName(id: String) = id.lowercase().replace(Regex("[^a-z0-9_-]+"), "-").trim('-').take(48).ifBlank { "server" }

    fun sandboxReady(): Boolean = runCatching { sandbox.isInstalled() }.getOrDefault(false)
    fun engineInstalled(): Boolean = sandboxReady() && File(hostDir(), "venv/bin/mcp-proxy").exists()

    /** Token Bearer do gateway (gerado uma vez; arquivo com permissão só do app). */
    fun token(): String {
        val f = File(hostDir(), "token")
        f.takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.length >= 32 }?.let { return it }
        val b = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val t = b.joinToString("") { "%02x".format(it) }
        f.writeText(t); f.setReadable(false, false); f.setReadable(true, true)
        return t
    }

    // ---------------- Catálogo ----------------
    @Volatile private var cached: List<Server>? = null
    fun catalog(ctx: Context): List<Server> = cached ?: runCatching {
        val root = JSONObject(ctx.assets.open("mcp/catalog.json").bufferedReader().use { it.readText() })
        val arr = root.getJSONArray("servers")
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            val env = o.optJSONObject("env")
            val ps = o.optJSONArray("params") ?: JSONArray()
            Server(o.getString("id"), o.optString("name"), o.optString("desc"), o.optString("cat", "Outros"), o.optBoolean("official"),
                o.getString("cmd"), List(o.optJSONArray("args")?.length() ?: 0) { o.getJSONArray("args").getString(it) },
                env?.keys()?.asSequence()?.associateWith { env.getString(it) } ?: emptyMap(),
                List(ps.length()) { j -> ps.getJSONObject(j).let { p -> Param(p.getString("key"), p.optString("desc"), p.optBoolean("required", true), p.optString("example")) } },
                o.optString("home"))
        }
    }.getOrDefault(emptyList()).also { cached = it }

    private val placeholder = Regex("\\$\\{([A-Za-z0-9_]+)\\}")
    private fun fill(s: String, values: Map<String, String>) = placeholder.replace(s) { m -> values[m.groupValues[1]].orEmpty() }

    // ---------------- Instalação do motor ----------------
    /** Instala o motor do gateway no Alpine (python venv + mcp-proxy + uv). Idempotente. */
    suspend fun installEngine(): Boolean = withContext(Dispatchers.IO) {
        if (!sandboxReady()) { logLine("Instale o Linux (Alpine) primeiro.\n"); return@withContext false }
        if (engineInstalled()) return@withContext true
        _busy.value = true
        try {
            logLine("⚙️ Instalando o gateway MCP local (mcp-proxy + uv)…\n")
            val r = sandbox.exec(
                "set -e; $ENV apk add --no-cache python3 py3-pip nodejs npm >/dev/null; " +
                    "mkdir -p $DIR; [ -x $DIR/venv/bin/python ] || python3 -m venv $DIR/venv; " +
                    "$DIR/venv/bin/pip install --disable-pip-version-check -q --upgrade 'mcp-proxy>=0.15' uv; " +
                    "$DIR/venv/bin/mcp-proxy --help >/dev/null && echo OK",
                cwd = "/tmp", timeoutSec = 1500,
            ) { logLine(it) }
            val ok = r.exitCode == 0 && engineInstalled()
            logLine(if (ok) "✅ Gateway MCP local instalado.\n" else "❌ Falha ao instalar o gateway (código ${r.exitCode}${if (r.timedOut) ", tempo esgotado" else ""}).\n")
            ok
        } finally { _busy.value = false }
    }

    // ---------------- Configuração ----------------
    private fun readConfig(): JSONObject = runCatching { JSONObject(configFile().readText()) }.getOrNull()
        ?.takeIf { it.has("mcpServers") } ?: JSONObject().put("mcpServers", JSONObject())

    private fun writeConfig(o: JSONObject) {
        val f = configFile(); val tmp = File(f.parentFile, "servers.json.tmp")
        tmp.writeText(o.toString(2)); tmp.setReadable(false, false); tmp.setReadable(true, true)
        tmp.renameTo(f)
    }

    fun installedNames(): Set<String> = readConfig().getJSONObject("mcpServers").keys().asSequence().toSet()

    // ---------------- Processo ----------------
    private fun alive(): Boolean = runCatching {
        http.newCall(Request.Builder().url("http://127.0.0.1:$PORT/status").header("Authorization", "Bearer ${token()}").build())
            .execute().use { it.isSuccessful }
    }.getOrDefault(false)

    /** Estado de cada servidor no gateway: "configured" ou "failed". */
    fun serverStates(): Map<String, String> = runCatching {
        http.newCall(Request.Builder().url("http://127.0.0.1:$PORT/status").header("Authorization", "Bearer ${token()}").build()).execute().use { r ->
            val inst = JSONObject(r.body?.string().orEmpty()).optJSONObject("server_instances") ?: return@use emptyMap<String, String>()
            inst.keys().asSequence().associateWith { inst.optString(it) }
        }
    }.getOrDefault(emptyMap())

    private suspend fun stopLocked() {
        proc?.let { p -> runCatching { p.destroy(); if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly() } }
        proc = null
        // Instância órfã de uma execução anterior do app.
        runCatching { sandbox.exec("pkill -f 'venv/bin/mcp-proxy' || true", cwd = "/tmp", timeoutSec = 15) }
        repeat(10) { if (!alive()) return; delay(300) }
    }

    /** Garante o gateway no ar. Com [restart] recarrega a configuração (servidor novo/removido). */
    suspend fun ensureRunning(restart: Boolean = false): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!restart && proc?.isAlive == true && alive()) return@withContext true
            if (!restart && alive()) return@withContext true
            stopLocked()
            if (!engineInstalled() || installedNames().isEmpty()) return@withContext false
            val run = File(hostDir(), "run.sh")
            run.writeText(
                "#!/bin/sh\n$ENV\ncd $DIR\n" +
                    "exec $DIR/venv/bin/mcp-proxy --host 127.0.0.1 --port $PORT --pass-environment " +
                    "--initialization-timeout 120 --auth-bearer-token \"\$(cat $DIR/token)\" " +
                    "--named-server-config $DIR/servers.json\n"
            )
            token()
            proc = sandbox.spawn("sh $DIR/run.sh", File(hostDir(), "gateway.log"))
            runCatching { SandboxService.start(CueApp.instance, "Gateway MCP local ativo") }
            // Os servidores sobem antes da porta abrir (npx pode baixar pacotes na primeira vez).
            val deadline = System.currentTimeMillis() + 240_000
            while (System.currentTimeMillis() < deadline) {
                if (alive()) return@withContext true
                if (proc?.isAlive != true) break
                delay(1000)
            }
            false
        }
    }

    fun lastLog(): String = runCatching { File(hostDir(), "gateway.log").readText().takeLast(1500) }.getOrDefault("")

    /** Pré-instala o pacote do servidor (npm global / uv tool) para não baixar a cada início. */
    private suspend fun preinstall(s: Server): Sandbox.ExecResult {
        val first = s.args.firstOrNull { !it.startsWith("-") }
        val cmd = when (s.cmd) {
            "npx" -> first?.let { "npm install -g --no-fund --no-audit '${it.replace("'", "")}'" }
            "uvx" -> {
                val from = s.args.indexOf("--from").takeIf { it >= 0 }?.let { s.args.getOrNull(it + 1) } ?: first
                from?.let { "uv tool install --quiet '${it.replace("'", "")}' || true" }
            }
            else -> null
        } ?: return Sandbox.ExecResult(0, "", false)
        logLine("📦 Instalando ${s.name}…\n")
        val r = sandbox.exec("set -e; $ENV $cmd", cwd = "/tmp", timeoutSec = 900) { logLine(it) }
        return r
    }

    /** Instala um servidor do catálogo, sobe o gateway e devolve a URL MCP local. */
    suspend fun install(s: Server, values: Map<String, String>): String = withContext(Dispatchers.IO) {
        _busy.value = true
        try {
            if (!engineInstalled() && !installEngine()) error("O gateway MCP local não pôde ser instalado. Veja o registro.")
            val name = safeName(s.id)
            val pre = preinstall(s)
            if (pre.exitCode != 0) error("Falha ao instalar o pacote de ${s.name}: ${pre.output.takeLast(300)}")
            val entry = JSONObject().put("command", s.cmd)
                .put("args", JSONArray(s.args.map { fill(it, values) }))
                .put("env", JSONObject(s.env.mapValues { fill(it.value, values) }.filterValues { it.isNotBlank() }))
                .put("enabled", true)
            mutex.withLock { writeConfig(readConfig().also { it.getJSONObject("mcpServers").put(name, entry) }) }
            logLine("🚀 Reiniciando o gateway…\n")
            if (!ensureRunning(restart = true)) error("O gateway não iniciou. ${lastLog().takeLast(400)}")
            if (serverStates()[name] == "failed") error("${s.name} não iniciou no gateway (verifique as chaves). ${lastLog().takeLast(300)}")
            logLine("✅ ${s.name} pronto.\n")
            mcpUrl(name)
        } finally { _busy.value = false }
    }

    /** Remove o servidor da configuração e recarrega o gateway. */
    suspend fun uninstall(name: String) = withContext(Dispatchers.IO) {
        mutex.withLock { writeConfig(readConfig().also { it.getJSONObject("mcpServers").remove(name) }) }
        if (installedNames().isEmpty()) mutex.withLock { stopLocked() } else ensureRunning(restart = true)
    }
}
