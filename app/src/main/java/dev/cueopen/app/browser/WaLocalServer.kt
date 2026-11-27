package dev.cueopen.app.browser

import android.content.Context
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Webhook local do WhatsApp: servidor HTTP mínimo em 127.0.0.1 (só este aparelho; nada exposto à rede).
 *   GET  /whatsapp/<token>                    → últimas mensagens (?since=<ms>&limit=N; ?wait=25 espera até chegar algo)
 *   GET  /whatsapp/<token>/schema             → esquema do evento
 *   POST /whatsapp/<token>                    → injeta um evento (teste / outro app local) e dispara os fluxos
 * Token errado → 403. Eventos ficam só em memória (últimos 200).
 */
object WaLocalServer {
    const val DEFAULT_PORT = 8787
    @Volatile var port = DEFAULT_PORT; private set
    @Volatile private var server: ServerSocket? = null
    private val events = ArrayDeque<Pair<Long, JsonObject>>()
    private val lock = Object()

    fun running() = server?.isClosed == false

    fun publish(m: JsonObject) {
        synchronized(lock) { events.addLast(System.currentTimeMillis() to m); while (events.size > 200) events.removeFirst(); lock.notifyAll() }
    }

    @Synchronized fun start(ctx: Context) {
        if (running()) return
        val app = ctx.applicationContext
        for (p in DEFAULT_PORT..DEFAULT_PORT + 10) {
            val s = runCatching { ServerSocket(p, 16, InetAddress.getByName("127.0.0.1")) }.getOrNull() ?: continue
            server = s; port = p
            Thread({ loop(app, s) }, "wa-webhook-local").apply { isDaemon = true }.start()
            return
        }
    }

    @Synchronized fun stop() { runCatching { server?.close() }; server = null }

    private fun loop(ctx: Context, s: ServerSocket) {
        while (!s.isClosed) {
            val c = runCatching { s.accept() }.getOrNull() ?: continue
            Thread({ runCatching { handle(ctx, c) }; runCatching { c.close() } }, "wa-webhook-req").apply { isDaemon = true }.start()
        }
    }

    private fun handle(ctx: Context, c: Socket) {
        c.soTimeout = 15_000
        val inp = c.getInputStream().buffered()
        fun line(): String { val sb = StringBuilder(); while (true) { val b = inp.read(); if (b < 0 || b == '\n'.code) break; if (b != '\r'.code) sb.append(b.toChar()) }; return sb.toString() }
        val req = line().split(' ')
        if (req.size < 2) return
        val method = req[0]; val target = req[1]
        var len = 0
        while (true) { val h = line(); if (h.isEmpty()) break; if (h.startsWith("content-length:", true)) len = h.substringAfter(':').trim().toIntOrNull() ?: 0 }
        val body = if (len in 1..1_000_000) ByteArray(len).also { var r = 0; while (r < len) { val n = inp.read(it, r, len - r); if (n < 0) break; r += n } }.decodeToString() else ""
        val path = target.substringBefore('?').trimEnd('/')
        val q = target.substringAfter('?', "").split('&').filter { '=' in it }.associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        val cfg = WhatsAppHub.config(ctx)
        val parts = path.trim('/').split('/')
        if (method == "OPTIONS") return send(c, 204, "")
        if (parts.size >= 2 && parts[0] == "webapi") return webApi(ctx, c, method, parts, body, cfg)
        if (parts.size < 2 || parts[0] != "whatsapp") return send(c, 404, """{"ok":false,"error":"use /whatsapp/<token> ou /webapi/<token>"}""")
        if (parts[1] != cfg.token) return send(c, 403, """{"ok":false,"error":"token inválido"}""")
        if (!cfg.webhook) return send(c, 503, """{"ok":false,"error":"webhook desligado no app (API Hub › WhatsApp)"}""")
        when {
            parts.size == 3 && parts[2] == "schema" && method == "GET" -> send(c, 200, schemaJson().toString())
            parts.size == 2 && method == "GET" -> {
                val since = q["since"]?.toLongOrNull() ?: 0L
                val limit = (q["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 200)
                val wait = (q["wait"]?.toLongOrNull() ?: 0L).coerceIn(0, 25) * 1000
                val deadline = System.currentTimeMillis() + wait
                var list: List<Pair<Long, JsonObject>>
                synchronized(lock) {
                    while (true) {
                        list = events.filter { it.first > since }
                        val left = deadline - System.currentTimeMillis()
                        if (list.isNotEmpty() || left <= 0) break
                        lock.wait(left)
                    }
                }
                val out = list.takeLast(limit)
                send(c, 200, buildJsonObject {
                    put("ok", true); put("next_since", out.lastOrNull()?.first ?: since)
                    put("events", JsonArray(out.map { JsonObject(it.second + ("received_ms" to JsonPrimitive(it.first))) }))
                }.toString())
            }
            parts.size == 2 && method == "POST" -> {
                val o = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(body) as JsonObject }.getOrNull()
                    ?: return send(c, 400, """{"ok":false,"error":"corpo precisa ser um objeto JSON (veja /schema)"}""")
                WhatsAppHub.dispatch(ctx, cfg, JsonObject(WhatsAppHub.sample(cfg) + o))
                send(c, 200, """{"ok":true}""")
            }
            else -> send(c, 405, """{"ok":false,"error":"método não suportado"}""")
        }
    }

    /**
     * Web APIs do hub pelo endereço local (mesmo token):
     *   GET  /webapi/<token>         → catálogo com o esquema de cada API
     *   POST /webapi/<token>/<slug>  → corpo = args JSON; resposta em stream (text/event-stream):
     *        "event: chunk" a cada api.emit(...) e "event: result" com o JSON final.
     */
    private fun webApi(ctx: Context, c: Socket, method: String, parts: List<String>, body: String, cfg: WhatsAppHub.Config) {
        if (parts[1] != cfg.token) return send(c, 403, """{"ok":false,"error":"token inválido"}""")
        if (parts.size == 2 && method == "GET") return send(c, 200, buildJsonObject {
            put("ok", true)
            put("apis", JsonArray(WebApiHub.enabled(ctx).map { a -> buildJsonObject {
                put("slug", a.slug); put("name", a.name); put("description", a.description); put("sensitive", a.sensitive)
                put("params", JsonArray(a.params.map { p -> buildJsonObject { put("name", p.name); put("type", p.type); put("required", p.required); put("default", p.default); put("description", p.description) } }))
            } }))
        }.toString())
        if (parts.size != 3 || method != "POST") return send(c, 405, """{"ok":false,"error":"use POST /webapi/<token>/<slug>"}""")
        val api = WebApiHub.find(ctx, parts[2])?.takeIf { it.enabled } ?: return send(c, 404, """{"ok":false,"error":"API não existe ou está desligada"}""")
        val args = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(body.ifBlank { "{}" }) as JsonObject }.getOrNull()
            ?: return send(c, 400, """{"ok":false,"error":"corpo precisa ser um objeto JSON com os args"}""")
        if (api.sensitive && (args["confirmed"] as? JsonPrimitive)?.content != "true") return send(c, 403, """{"ok":false,"error":"ação externa: envie confirmed=true só quando o usuário pediu"}""")
        val out = c.getOutputStream()
        val w = Object()
        fun ev(name: String, data: String) = synchronized(w) { runCatching { out.write(("event: $name\ndata: " + data.replace("\n", "\ndata: ") + "\n\n").toByteArray()); out.flush() } }
        out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream; charset=utf-8\r\nCache-Control: no-cache\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n".toByteArray()); out.flush()
        val result = kotlinx.coroutines.runBlocking { WebApiHub.call(ctx, api, JsonObject(args - "__wa_policy" - "__dry_run")) { ev("chunk", it) } }
        ev("result", result)
    }

    fun schemaJson() = buildJsonObject {
        put("event", "whatsapp.message")
        put("fields", JsonArray(WhatsAppHub.SCHEMA.map { (k, t, d) -> buildJsonObject { put("name", k); put("type", t); put("description", d) } }))
    }

    private fun send(c: Socket, code: Int, body: String) {
        val b = body.toByteArray()
        val status = mapOf(200 to "OK", 204 to "No Content", 400 to "Bad Request", 403 to "Forbidden", 404 to "Not Found", 405 to "Method Not Allowed", 503 to "Service Unavailable")[code] ?: "OK"
        c.getOutputStream().apply {
            write(("HTTP/1.1 $code $status\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${b.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\nAccess-Control-Allow-Headers: *\r\nConnection: close\r\n\r\n").toByteArray())
            write(b); flush()
        }
    }
}
