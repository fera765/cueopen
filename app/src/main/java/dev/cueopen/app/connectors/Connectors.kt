package dev.cueopen.app.connectors

import android.content.Context
import android.net.Uri
import android.util.Base64
import dev.cueopen.app.data.Connector
import dev.cueopen.app.data.Store
import dev.cueopen.app.llm.tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.net.ServerSocket
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Conectores = servidores MCP remotos (Model Context Protocol, transporte "streamable HTTP").
 *
 * Autenticação 100% OAuth público, sem API key nem client_id pré-cadastrado:
 *  1. descoberta: 401 + WWW-Authenticate(resource_metadata) → /.well-known/oauth-protected-resource
 *     → servidor de autorização → /.well-known/oauth-authorization-server
 *  2. registro dinâmico de cliente (RFC 7591) → client_id público (token_endpoint_auth_method=none)
 *  3. Authorization Code + PKCE (S256) no navegador; redirect para um servidor loopback local
 *     (http://localhost:<porta>/callback, RFC 8252) que captura o código
 *  4. troca por access/refresh token; refresh automático em 401/expiração
 * As ferramentas do servidor viram funções do agente: mcp_<conector>__<ferramenta>.
 */
object Connectors {

    // Catálogo fixo e Registro MCP aberto foram substituídos pelo gateway Smithery Connect (SmitheryGateway).

    private val baseHttp = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).retryOnConnectionFailure(true).build()
    private fun httpClient() = dev.cueopen.app.CueApp.instance.proxyHttpClient(dev.cueopen.app.proxy.ProxyScope.AGENT_API, baseHttp)
    private fun debugLog() = runCatching { dev.cueopen.app.CueApp.instance.connectorDebugLog }.getOrNull()
    private fun httpCall(connectorId: String, request: Request, summary: String): Response {
        val started = System.nanoTime()
        try {
            val response = httpClient().newCall(request).execute()
            debugLog()?.recordHttp(connectorId, request.method, request.url.toString(), request.headers.names(),
                (System.nanoTime() - started) / 1_000_000L, response.code, summary)
            return response
        } catch (t: Throwable) {
            if (t !is kotlinx.coroutines.CancellationException)
                debugLog()?.recordFailure(connectorId, "http", t, request.url.toString(), request.method, (System.nanoTime() - started) / 1_000_000L)
            throw t
        }
    }
    private val sessions = java.util.concurrent.ConcurrentHashMap<String, String>() // connectorId -> Mcp-Session-Id
    private val jsonType = "application/json".toMediaType()
    private var rpcId = 1

    // ------------------------------------------------------------------ OAuth
    class AuthRequired(val resourceMetadata: String?) : Exception("Autenticação necessária")

    private fun b64url(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun getJson(url: String): JSONObject? = runCatching {
        val req = Request.Builder().url(url).header("Accept", "application/json").build()
        httpCall("mcp-oauth", req, "OAuth discovery response; body omitted").use { r ->
            if (r.isSuccessful) JSONObject(r.body!!.string()) else null
        }
    }.getOrNull()

    private fun origin(u: String): String = URI(u).let { "${it.scheme}://${it.authority}" }

    /** Faz todo o fluxo OAuth e salva os tokens no conector. Abre o navegador para o login. */
    suspend fun connect(ctx: Context, store: Store, c: Connector, openBrowser: (String) -> Unit): Connector = withContext(Dispatchers.IO) {
        if (!c.needsAuth) {
            val ok = c.copy(connectedAt = System.currentTimeMillis(), lastError = null)
            store.upsertConnector(ok)
            return@withContext refreshTools(store, ok)
        }
        // 1) descoberta do recurso protegido
        var resourceMeta: String? = null
        runCatching { initialize(c) }.onFailure { if (it is AuthRequired) resourceMeta = it.resourceMetadata }
        val prm = resourceMeta?.let { getJson(it) }
            ?: getJson(origin(c.url) + "/.well-known/oauth-protected-resource" + URI(c.url).path.trimEnd('/'))
            ?: getJson(origin(c.url) + "/.well-known/oauth-protected-resource")
        val asBase = prm?.optJSONArray("authorization_servers")?.optString(0)?.trimEnd('/') ?: origin(c.url)
        val asPath = URI(asBase).path.trimEnd('/')
        val asMeta = getJson(origin(asBase) + "/.well-known/oauth-authorization-server" + asPath)
            ?: getJson("$asBase/.well-known/oauth-authorization-server")
            ?: getJson("$asBase/.well-known/openid-configuration")
            ?: error("Servidor de autorização não encontrado")
        val authEp = asMeta.getString("authorization_endpoint")
        val tokenEp = asMeta.getString("token_endpoint")
        val regEp = asMeta.optString("registration_endpoint").ifBlank { null } ?: error("Este servidor não permite registro dinâmico (OAuth público)")

        // 2) servidor loopback para o redirect
        val server = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val redirect = "http://localhost:${server.localPort}/callback"

        // 3) registro dinâmico de cliente
        val reg = JSONObject().put("client_name", "CueOpen").put("redirect_uris", JSONArray().put(redirect))
            .put("grant_types", JSONArray().put("authorization_code").put("refresh_token"))
            .put("response_types", JSONArray().put("code")).put("application_type", "native")
            .put("token_endpoint_auth_method", "none")
            .put("scope", prm?.optJSONArray("scopes_supported")?.let { a -> (0 until a.length()).joinToString(" ") { a.getString(it) } } ?: "")
        val registrationRequest = Request.Builder().url(regEp).post(reg.toString().toRequestBody(jsonType)).build()
        val clientId = httpCall(c.id, registrationRequest, "OAuth dynamic client registration; body omitted").use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) error("Registro dinâmico de cliente falhou (HTTP ${r.code}): ${body.take(200)}. O servidor pode exigir cadastro prévio ou não aceitar clientes públicos.")
            JSONObject(body).getString("client_id")
        }

        // 4) PKCE + autorização no navegador
        val verifier = b64url(ByteArray(48).also { SecureRandom().nextBytes(it) })
        val challenge = b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val state = b64url(ByteArray(16).also { SecureRandom().nextBytes(it) })
        val scope = prm?.optJSONArray("scopes_supported")?.let { a -> (0 until a.length()).joinToString(" ") { a.getString(it) } }
        val authUrl = Uri.parse(authEp).buildUpon()
            .appendQueryParameter("response_type", "code").appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", redirect).appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256").appendQueryParameter("state", state)
            .appendQueryParameter("resource", c.url)
            .apply { if (!scope.isNullOrBlank()) appendQueryParameter("scope", scope) }
            .build().toString()
        withContext(Dispatchers.Main) { openBrowser(authUrl) }

        val code = try {
            withTimeout(5 * 60_000) { awaitCallback(server, state) }
        } finally { runCatching { server.close() } }

        // 5) troca do código por tokens
        val form = FormBody.Builder().add("grant_type", "authorization_code").add("code", code)
            .add("redirect_uri", redirect).add("client_id", clientId).add("code_verifier", verifier).add("resource", c.url)
            .build()
        val tokenRequest = Request.Builder().url(tokenEp).post(form).header("Accept", "application/json").build()
        val tok = httpCall(c.id, tokenRequest, "OAuth token exchange; body omitted").use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) error("Troca de token falhou (HTTP ${r.code}): ${body.take(200)}")
            JSONObject(body)
        }
        val connected = c.copy(
            clientId = clientId, clientSecret = null, tokenEndpoint = tokenEp,
            accessToken = tok.getString("access_token"), refreshToken = tok.optString("refresh_token").ifBlank { null },
            expiresAt = tok.optLong("expires_in", 0).takeIf { it > 0 }?.let { System.currentTimeMillis() + it * 1000 },
            connectedAt = System.currentTimeMillis(), lastError = null,
        )
        store.upsertConnector(connected)
        sessions.remove(c.id)
        refreshTools(store, connected)
    }

    /** Espera o navegador chamar http://localhost:<porta>/callback?code=…&state=… */
    private suspend fun awaitCallback(server: ServerSocket, state: String): String = withContext(Dispatchers.IO) {
        while (true) {
            server.accept().use { sock ->
                val line = sock.getInputStream().bufferedReader().readLine().orEmpty() // GET /callback?code=..&state=.. HTTP/1.1
                val path = line.split(' ').getOrNull(1).orEmpty()
                val q = Uri.parse("http://localhost$path")
                val ok = q.path == "/callback" && q.getQueryParameter("state") == state && q.getQueryParameter("code") != null
                val err = q.getQueryParameter("error_description") ?: q.getQueryParameter("error")
                val html = if (ok) "<h2 style='font-family:sans-serif'>✅ Conectado ao CueOpen</h2><p style='font-family:sans-serif'>Pode voltar para o app.</p>"
                           else "<h2 style='font-family:sans-serif'>Falha na conexão</h2><p>${err ?: ""}</p>"
                sock.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nConnection: close\r\n\r\n$html".toByteArray()); flush()
                }
                if (ok) return@withContext q.getQueryParameter("code")!!
                if (err != null) error("Autorização negada: $err")
            }
        }
        @Suppress("UNREACHABLE_CODE") ""
    }

    private fun refreshToken(store: Store, c: Connector): Connector? {
        val rt = c.refreshToken ?: return null
        val ep = c.tokenEndpoint ?: return null
        val form = FormBody.Builder().add("grant_type", "refresh_token").add("refresh_token", rt).add("client_id", c.clientId ?: "").add("resource", c.url)
            .apply { c.clientSecret?.let { add("client_secret", it) } }.build()
        return runCatching {
            val req = Request.Builder().url(ep).post(form).header("Accept", "application/json").build()
            httpCall(c.id, req, "OAuth token refresh; body omitted").use { r ->
                if (!r.isSuccessful) return null
                val t = JSONObject(r.body!!.string())
                c.copy(accessToken = t.getString("access_token"), refreshToken = t.optString("refresh_token").ifBlank { rt },
                    expiresAt = t.optLong("expires_in", 0).takeIf { it > 0 }?.let { System.currentTimeMillis() + it * 1000 })
                    .also { store.upsertConnector(it) }
            }
        }.getOrNull()
    }

    // ------------------------------------------------------------------ MCP JSON-RPC
    private fun parseResponse(r: Response, id: Int): JSONObject {
        val body = r.body?.string().orEmpty()
        val ct = r.header("Content-Type").orEmpty()
        val msgs = if (ct.contains("text/event-stream")) {
            body.lines().filter { it.startsWith("data:") }.mapNotNull { runCatching { JSONObject(it.removePrefix("data:").trim()) }.getOrNull() }
        } else listOfNotNull(runCatching { JSONObject(body) }.getOrNull())
        val m = msgs.firstOrNull { it.optInt("id", -1) == id } ?: msgs.lastOrNull() ?: error("Resposta MCP vazia (HTTP ${r.code})")
        m.optJSONObject("error")?.let { error(it.optString("message", it.toString())) }
        return m.optJSONObject("result") ?: JSONObject()
    }

    private fun rpc(c: Connector, method: String, params: JSONObject?, notify: Boolean = false): JSONObject {
        debugLog()?.rememberSecrets(c.id, listOfNotNull(c.accessToken, c.refreshToken, c.clientSecret))
        val id = synchronized(this) { rpcId++ }
        val payload = JSONObject().put("jsonrpc", "2.0").put("method", method).apply {
            if (!notify) put("id", id); if (params != null) put("params", params)
        }
        val rb = Request.Builder().url(c.url).post(payload.toString().toRequestBody(jsonType))
            .header("Accept", "application/json, text/event-stream").header("MCP-Protocol-Version", "2025-06-18")
        c.accessToken?.let { rb.header("Authorization", "Bearer $it") }
        sessions[c.id]?.let { rb.header("Mcp-Session-Id", it) }
        val request = rb.build()
        httpCall(c.id, request, "MCP JSON-RPC method=$method; bodies and session IDs omitted").use { r ->
            if (r.code == 401) {
                val www = r.header("WWW-Authenticate").orEmpty()
                throw AuthRequired(Regex("resource_metadata=\"([^\"]+)\"").find(www)?.groupValues?.get(1))
            }
            r.header("Mcp-Session-Id")?.let { sessions[c.id] = it }
            if (notify) return JSONObject()
            if (r.code == 404 && sessions.containsKey(c.id)) { sessions.remove(c.id); error("Sessão MCP expirada") }
            if (!r.isSuccessful && r.code != 202) error("MCP HTTP ${r.code}: ${r.body?.string()?.take(200)}")
            return parseResponse(r, id)
        }
    }

    private fun initialize(c: Connector) {
        rpc(c, "initialize", JSONObject().put("protocolVersion", "2025-06-18").put("capabilities", JSONObject())
            .put("clientInfo", JSONObject().put("name", "CueOpen").put("version", "3.0")))
        runCatching { rpc(c, "notifications/initialized", null, notify = true) }
    }

    /** Executa com sessão inicializada, renovando token em 401. */
    private fun <T> withSession(store: Store, c0: Connector, block: (Connector) -> T): T {
        var c = c0
        if (c.expiresAt != null && System.currentTimeMillis() > c.expiresAt!! - 60_000) refreshToken(store, c)?.let { c = it }
        return try {
            if (!sessions.containsKey(c.id)) initialize(c)
            block(c)
        } catch (e: AuthRequired) {
            val r = refreshToken(store, c) ?: throw IllegalStateException("Conector ${c.name} precisa ser reconectado (login expirado).")
            sessions.remove(c.id); initialize(r); block(r)
        } catch (e: IllegalStateException) {
            if (e.message == "Sessão MCP expirada") { initialize(c); block(c) } else throw e
        }
    }

    /** Conecta usando um token colado pelo usuário (para servidores sem OAuth público). */
    suspend fun connectWithToken(store: Store, c: Connector, token: String): Connector = withContext(Dispatchers.IO) {
        sessions.remove(c.id)
        val cc = c.copy(accessToken = token.trim(), connectedAt = System.currentTimeMillis(), lastError = null)
        store.upsertConnector(cc)
        refreshTools(store, cc)
    }

    suspend fun refreshTools(store: Store, c: Connector): Connector = withContext(Dispatchers.IO) {
        try {
            val tools = JSONArray()
            withSession(store, c) { cc ->
                var cursor: String? = null
                do {
                    val res = rpc(cc, "tools/list", cursor?.let { JSONObject().put("cursor", it) } ?: JSONObject())
                    res.optJSONArray("tools")?.let { a -> for (i in 0 until a.length()) tools.put(a.getJSONObject(i)) }
                    cursor = res.optString("nextCursor").ifBlank { null }
                } while (cursor != null)
            }
            val updated = (store.connector(c.id) ?: c).copy(toolsJson = tools.toString(), lastError = null, connectedAt = c.connectedAt ?: System.currentTimeMillis())
            store.upsertConnector(updated); updated
        } catch (t: Throwable) {
            if (t !is kotlinx.coroutines.CancellationException)
                debugLog()?.recordFailure(c.id, "tools-refresh", t, c.url, "POST")
            val updated = (store.connector(c.id) ?: c).copy(lastError = t.message)
            store.upsertConnector(updated); updated
        }
    }

    private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9_]"), "_")

    /** Definições de função (OpenAI tools) para todas as ferramentas dos conectores ativos. */
    fun toolDefinitions(store: Store): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        store.state.value.connectors.filter { it.enabled && it.connectedAt != null && it.toolsJson != null }.forEach { c ->
            val arr = runCatching { JSONArray(c.toolsJson) }.getOrNull() ?: return@forEach
            for (i in 0 until minOf(arr.length(), 40)) {
                val t = arr.getJSONObject(i)
                if (c.toolPolicy[t.getString("name")] == POLICY_BLOCK) continue  // bloqueada: o modelo nem vê a ferramenta
                val name = toolFunctionName(c.id, t.getString("name"))
                val schema = t.optJSONObject("inputSchema")?.toString() ?: "{\"type\":\"object\",\"properties\":{}}"
                val schemaEl = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(schema) as JsonObject }.getOrNull()
                out += buildJsonObject {
                    put("type", "function")
                    put("function", buildJsonObject {
                        put("name", name)
                        put("description", "[${c.name}] " + t.optString("description").take(600))
                        put("parameters", schemaEl ?: buildJsonObject { put("type", "object") })
                    })
                }
            }
        }
        return out
    }

    const val POLICY_ALLOW = "allow"
    const val POLICY_ASK = "ask"
    const val POLICY_BLOCK = "block"

    fun toolFunctionName(connectorId: String, tool: String): String = ("mcp_${safe(connectorId)}__${safe(tool)}").take(64)

    /** Política da ferramenta chamada pelo modelo (mcp_<conector>__<ferramenta>), ou null = padrão do app. */
    fun policyFor(store: Store, fnName: String): String? {
        for (c in store.state.value.connectors) {
            if (c.toolPolicy.isEmpty() || !fnName.startsWith("mcp_${safe(c.id)}__")) continue
            c.toolPolicy.entries.firstOrNull { (tool, _) -> toolFunctionName(c.id, tool) == fnName }?.let { return it.value }
        }
        return null
    }

    /** Nomes das ferramentas de um conector (para a tela de permissões). */
    fun toolNames(c: Connector): List<Pair<String, String>> = runCatching {
        val arr = JSONArray(c.toolsJson ?: "[]")
        (0 until arr.length()).map { arr.getJSONObject(it).let { t -> t.getString("name") to t.optString("description") } }
    }.getOrDefault(emptyList())

    /** Executa mcp_<conector>__<ferramenta> com os argumentos do modelo. */
    suspend fun callTool(store: Store, fnName: String, args: JsonObject): String = withContext(Dispatchers.IO) {
        if (policyFor(store, fnName) == POLICY_BLOCK) return@withContext "RECUSADO: esta ferramenta está bloqueada pelo usuário em Conectores › Permissões das ferramentas."
        val c = store.state.value.connectors.firstOrNull { fnName.startsWith("mcp_${safe(it.id)}__") }
            ?: return@withContext "Conector não encontrado para $fnName"
        val arr = runCatching { JSONArray(c.toolsJson) }.getOrNull() ?: JSONArray()
        val toolName = (0 until arr.length()).map { arr.getJSONObject(it).getString("name") }
            .firstOrNull { ("mcp_${safe(c.id)}__${safe(it)}").take(64) == fnName } ?: fnName.substringAfter("__")
        try {
            val res = withSession(store, c) { cc -> rpc(cc, "tools/call", JSONObject().put("name", toolName).put("arguments", JSONObject(args.toString()))) }
            val content = res.optJSONArray("content") ?: JSONArray()
            val text = (0 until content.length()).joinToString("\n") { i ->
                val p = content.getJSONObject(i)
                when (p.optString("type")) {
                    "text" -> p.optString("text")
                    "resource" -> p.optJSONObject("resource")?.optString("text").orEmpty()
                    else -> "[${p.optString("type")}]"
                }
            }
            if (res.optBoolean("isError")) debugLog()?.recordEvent(c.id, "tool-call", "MCP reported an error; response content omitted")
            (if (res.optBoolean("isError")) "ERRO do conector: " else "") + text.ifBlank { res.toString().take(4000) }
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            debugLog()?.recordFailure(c.id, "tool-call", t, c.url, "POST")
            "Falha no conector ${c.name}: ${t.message}"
        }
    }

    @Suppress("unused") private fun JsonElement.str() = toString()
}
