package dev.cueopen.app.uiconnector.custom

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/*
 * Conexão em TEMPO REAL (WebSocket) dos conectores JSON — a mesma arquitetura do conector IQ Option,
 * só que declarativa: conecta numa URL wss:// de um host declarado, envia mensagens de abertura
 * (autenticação/assinaturas), mantém heartbeat, reconecta sozinha e aplica REGRAS a cada mensagem
 * recebida para manter um ESTADO (rt.saldo, rt.preco…) e COLEÇÕES (lista de cotações, ordens…).
 * Ferramentas podem ENVIAR uma mensagem e AGUARDAR a resposta correspondente (request_id).
 * Nada é executado: só envio/recebimento de texto/JSON, com o mesmo isolamento de rede do HTTP.
 */

/** Regra aplicada a cada mensagem recebida: se casar com [match], grava [set] no estado e/ou empurra numa coleção. */
data class RtRule(
    val match: Map<String, String>,
    val set: Map<String, String>,
    val pushTo: String = "",
    val pushItem: String = "$",
    val pushKey: String = "",
    val pushMax: Int = 100,
)

data class RtDef(
    val url: String,
    val headers: Map<String, String>,
    val onOpen: List<JsonElement>,
    val heartbeatSec: Int,
    val heartbeat: JsonElement?,
    val rules: List<RtRule>,
    /** "login" = conecta ao entrar; "auto" = conecta no primeiro uso (painel/ferramenta). */
    val connect: String,
) {
    val stateKeys: Set<String> get() = rules.flatMap { it.set.keys }.toSet()
    val collections: Set<String> get() = rules.mapNotNull { it.pushTo.ifEmpty { null } }.toSet()
}

data class AwaitDef(val match: Map<String, String>, val timeoutSec: Int)
data class SendDef(val message: JsonElement, val await: AwaitDef?)

// ---------------------------------------------------------------- transporte (substituível nos testes)

interface WsListener {
    fun onOpen()
    fun onText(text: String)
    fun onClosed(reason: String)
}

interface WsConn {
    fun send(text: String): Boolean
    fun close()
}

interface WsExec {
    fun open(url: String, headers: Map<String, String>, listener: WsListener): WsConn
}

/** WebSocket real (OkHttp): DNS anti-rede-privada, sem redirecionamentos, ping a cada 20 s. */
class OkHttpWs(permissive: Boolean = false) : WsExec {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .apply { if (!permissive) dns(SafeDns()) }
        .build()

    override fun open(url: String, headers: Map<String, String>, listener: WsListener): WsConn {
        val req = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        val ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = listener.onOpen()
            override fun onMessage(webSocket: WebSocket, text: String) = listener.onText(text)
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = listener.onText(bytes.utf8())
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = listener.onClosed("fechado ($code${if (reason.isNotBlank()) ": $reason" else ""})")
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                listener.onClosed(response?.let { "HTTP ${it.code}" } ?: (t.message ?: t.javaClass.simpleName))
        })
        return object : WsConn {
            override fun send(text: String) = ws.send(text)
            override fun close() { ws.close(1000, null) }
        }
    }
}

object RtGuard {
    /** URL final do WebSocket: wss://, host declarado, porta 443, sem credenciais. */
    fun check(url: String, hosts: Set<String>, permissive: Boolean = false): String? {
        val u = try { URI(url) } catch (t: Exception) { return "URL inválida" }
        if (u.userInfo != null) return "URL com credenciais não é permitida"
        val h = u.host?.lowercase() ?: return "URL sem host"
        if (hosts.none { it.equals(h, ignoreCase = true) }) return "host '$h' não está entre os declarados (${hosts.joinToString()})"
        if (permissive) return null
        if (u.scheme?.lowercase() != "wss") return "tempo real exige wss:// (WebSocket seguro)"
        if (u.port != -1 && u.port != 443) return "somente a porta 443"
        return null
    }
}

// ---------------------------------------------------------------- sessão

class RealtimeSession(
    private val def: RtDef,
    private val hosts: Set<String>,
    private val ws: WsExec,
    private val permissive: Boolean = false,
) {
    companion object {
        const val MAX_MSG = 1024 * 1024
        private val RID = AtomicLong(System.currentTimeMillis() % 1_000_000_000L)
        fun nextRid(): String = RID.incrementAndGet().toString()
    }

    private class Waiter(val match: Map<String, String>, val result: CompletableDeferred<JsonElement>)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val state = ConcurrentHashMap<String, String>()
    private val cols = ConcurrentHashMap<String, LinkedHashMap<String, JsonElement>>()
    private val waiters = CopyOnWriteArrayList<Waiter>()
    @Volatile private var conn: WsConn? = null
    @Volatile private var heartbeatJob: Job? = null
    @Volatile private var lastVars: Map<String, String> = emptyMap()
    @Volatile private var wanted = false
    @Volatile private var generation = 0
    @Volatile var connected = false
        private set
    @Volatile var lastError: String? = null
        private set
    val received = AtomicLong(0)
    private var seq = 0L

    /** Variáveis de estado para os modelos: rt.<chave>. */
    fun vars(): Map<String, String> = state.entries.associate { "rt." + it.key to it.value } +
        mapOf("rt.connected" to connected.toString(), "rt.messages" to received.get().toString())

    /** Itens de uma coleção, MAIS RECENTES PRIMEIRO, como {"items":[…]}. */
    fun collection(name: String): JsonElement = JsonObject(mapOf("items" to JsonArray(synchronized(cols) { cols[name]?.values?.toList().orEmpty() }.asReversed())))

    fun stateSnapshot(): Map<String, String> = HashMap(state)
    fun collectionSizes(): Map<String, Int> = synchronized(cols) { def.collections.associateWith { cols[it]?.size ?: 0 } }

    /** Garante a conexão aberta (conecta se preciso). Devolve null se ok, senão o motivo. */
    suspend fun ensure(vars: Map<String, String>): String? = lock.withLock {
        lastVars = vars
        wanted = true
        if (connected && conn != null) return@withLock null
        connectLocked()
    }

    private suspend fun connectLocked(): String? {
        val myGen = ++generation
        conn?.runCatching { close() }
        connected = false
        val ctx = Template.Ctx(lastVars + mapOf("rid" to nextRid(), "now" to System.currentTimeMillis().toString()))
        val url = Template.render(def.url, ctx)
        RtGuard.check(url, hosts, permissive)?.let { lastError = "Conexão bloqueada: $it"; return lastError }
        val headers = def.headers.mapValues { Template.render(it.value, ctx) }
        val gate = CompletableDeferred<String?>()
        conn = try {
            ws.open(url, headers, object : WsListener {
                override fun onOpen() { if (myGen == generation) gate.complete(null) }
                override fun onText(text: String) { if (myGen == generation) handle(text) }
                override fun onClosed(reason: String) {
                    if (myGen != generation) return
                    gate.complete(reason)
                    dropped(reason)
                }
            })
        } catch (t: Throwable) {
            lastError = t.message ?: t.javaClass.simpleName
            return lastError
        }
        val outcome = withTimeoutOrNull(12_000) { listOf(gate.await()) }
        val err = if (outcome == null) "tempo esgotado ao conectar" else outcome[0]
        if (err != null) {
            conn?.runCatching { close() }
            conn = null
            lastError = err
            return err
        }
        connected = true
        lastError = null
        for (m in def.onOpen) sendRendered(m, emptyMap())
        startHeartbeat(myGen)
        return null
    }

    private fun startHeartbeat(myGen: Int) {
        heartbeatJob?.cancel()
        val hb = def.heartbeat ?: return
        if (def.heartbeatSec <= 0) return
        heartbeatJob = scope.launch {
            while (isActive && myGen == generation && connected) {
                delay(def.heartbeatSec * 1000L)
                if (myGen == generation && connected) sendRendered(hb, emptyMap())
            }
        }
    }

    private fun dropped(reason: String) {
        connected = false
        lastError = reason
        heartbeatJob?.cancel()
        waiters.forEach { it.result.completeExceptionally(IllegalStateException("conexão caiu: $reason")) }
        waiters.clear()
        if (!wanted) return
        // reconexão automática com espera crescente (até 5 tentativas); depois, no próximo uso.
        scope.launch {
            var wait = 2_000L
            repeat(5) {
                delay(wait)
                if (!wanted || connected) return@launch
                val err = lock.withLock { if (connected) null else connectLocked() }
                if (err == null) return@launch
                wait = (wait * 2).coerceAtMost(30_000L)
            }
        }
    }

    private fun render(m: JsonElement, extra: Map<String, String>): String {
        val ctx = Template.Ctx(lastVars + vars() + mapOf("now" to System.currentTimeMillis().toString()) + extra)
        return if (m is JsonPrimitive && m.isString) Template.render(m.content, ctx) else Template.renderJson(m, ctx).toString()
    }

    private fun sendRendered(m: JsonElement, extra: Map<String, String>): Boolean {
        val c = conn ?: return false
        val withRid = if ("rid" in extra) extra else extra + ("rid" to nextRid())
        return c.send(render(m, withRid))
    }

    /**
     * Envia uma mensagem (modelada com [vars]) e, se [await] existir, espera a primeira mensagem que
     * casa com o filtro. Devolve a mensagem recebida (ou {} sem await).
     */
    suspend fun request(message: JsonElement, await: AwaitDef?, vars: Map<String, String>): JsonElement {
        if (!connected) throw IllegalStateException("tempo real desconectado: ${lastError ?: "não conectado"}")
        val rid = nextRid()
        val extra = vars + mapOf("rid" to rid)
        val waiter = await?.let {
            val ctx = Template.Ctx(lastVars + vars() + extra)
            Waiter(it.match.mapValues { (_, v) -> Template.render(v, ctx) }, CompletableDeferred())
        }
        if (waiter != null) waiters.add(waiter)
        try {
            if (!sendRendered(message, extra)) throw IllegalStateException("não foi possível enviar (conexão fechada)")
            if (waiter == null) return JsonObject(emptyMap())
            return withTimeoutOrNull(await!!.timeoutSec * 1000L) { waiter.result.await() }
                ?: throw IllegalStateException("sem resposta em ${await.timeoutSec}s (filtro ${await.match.keys.joinToString()})")
        } finally {
            if (waiter != null) waiters.remove(waiter)
        }
    }

    /** Espera até [ms] por mensagens (usado no teste do criador). */
    suspend fun settle(ms: Long, minMessages: Long = 1) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (received.get() >= minMessages && (def.stateKeys.isEmpty() || def.stateKeys.all { state.containsKey(it) }) &&
                collectionSizes().values.all { it > 0 }) return
            delay(150)
        }
    }

    private fun matches(msg: JsonElement, match: Map<String, String>): Boolean = match.all { (path, expected) ->
        val v = JsonPath.first(msg, path) ?: return@all false
        expected == "*" || JsonPath.text(v) == expected
    }

    internal fun handle(text: String) {
        if (text.length > MAX_MSG) return
        received.incrementAndGet()
        val msg = ResponseTree.parse(text, null) ?: JsonObject(mapOf("body" to JsonPrimitive(text)))
        for (w in waiters) if (!w.result.isCompleted && matches(msg, w.match)) { w.result.complete(msg); break }
        for (r in def.rules) {
            if (!matches(msg, r.match)) continue
            for ((k, path) in r.set) {
                val v = JsonPath.text(JsonPath.first(msg, path))
                if (v.isNotEmpty()) state[k] = v.take(4000)
            }
            if (r.pushTo.isNotEmpty()) {
                val items = if (r.pushItem == "$") listOf(msg) else JsonPath.select(msg, r.pushItem)
                synchronized(cols) {
                    val col = cols.getOrPut(r.pushTo) { LinkedHashMap() }
                    for (item in items) {
                        val key = if (r.pushKey.isNotEmpty()) JsonPath.text(JsonPath.first(item, r.pushKey)) else ""
                        val k = key.ifEmpty { "#" + (++seq) }
                        col.remove(k)
                        col[k] = item
                        while (col.size > r.pushMax) col.remove(col.keys.first())
                    }
                }
            }
        }
    }

    fun close() {
        wanted = false
        generation++
        connected = false
        heartbeatJob?.cancel()
        conn?.runCatching { close() }
        conn = null
        waiters.forEach { it.result.cancel() }
        waiters.clear()
        state.clear()
        synchronized(cols) { cols.clear() }
        received.set(0)
    }

    fun dispose() {
        close()
        scope.cancel()
    }
}
