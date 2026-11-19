package dev.cueopen.app.connectors

import dev.cueopen.app.CueApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Cliente REST do hub local (Activepieces CE em 127.0.0.1): catálogo de conectores, login local e criação de
 * conexões — a mesma API que o painel usa, para o app fazer tudo nativamente. O catálogo é público (sem login);
 * criar/apagar conexão exige o login local do hub (guardado criptografado no aparelho; o token vale 7 dias e é
 * renovado sozinho com as credenciais salvas).
 */
object HubApi {
    private val API = ActivepiecesHub.BASE + "/api"
    const val CLOUD_REDIRECT = "https://secrets.activepieces.com/redirect"
    private const val VAULT_ID = "activepieces_hub_login"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val jsonType = "application/json".toMediaType()
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()
    private val web = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    class HubError(message: String, val code: Int = 0) : Exception(message)

    data class Login(val email: String, val password: String, val token: String, val projectId: String, val at: Long)

    private fun vault() = dev.cueopen.app.uiconnector.CredentialVault(CueApp.instance)

    fun savedLogin(): Login? = runCatching {
        val m = vault().load(VAULT_ID) ?: return null
        Login(m["email"].orEmpty(), m["password"].orEmpty(), m["token"].orEmpty(), m["projectId"].orEmpty(), m["at"]?.toLongOrNull() ?: 0L)
            .takeIf { it.email.isNotBlank() && it.password.isNotBlank() }
    }.getOrNull()

    fun hasLogin(): Boolean = savedLogin() != null

    fun forgetLogin() = runCatching { vault().save(VAULT_ID, emptyMap()) }

    private fun store(l: Login) = vault().save(VAULT_ID, mapOf("email" to l.email, "password" to l.password, "token" to l.token, "projectId" to l.projectId, "at" to l.at.toString()))

    // ---------------- HTTP ----------------
    private fun call(method: String, path: String, body: JsonElement? = null, token: String? = null, query: Map<String, String> = emptyMap()): Pair<Int, String> {
        val url = (API + path).toHttpUrl().newBuilder().apply { query.forEach { (k, v) -> addQueryParameter(k, v) } }.build()
        val rb = Request.Builder().url(url)
        token?.let { rb.header("Authorization", "Bearer $it") }
        when (method) {
            "GET" -> rb.get()
            "DELETE" -> rb.delete()
            else -> rb.method(method, (body?.toString() ?: "{}").toRequestBody(jsonType))
        }
        http.newCall(rb.build()).execute().use { r -> return r.code to (r.body?.string().orEmpty()) }
    }

    /** Mensagem legível de um erro da API ({"code":"...","params":{"message"|"error":...}}). */
    fun errorText(code: Int, body: String): String {
        val o = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        val apCode = (o?.get("code") as? JsonPrimitive)?.contentOrNull
        val params = o?.get("params") as? JsonObject
        val detail = listOf("message", "error").firstNotNullOfOrNull { k -> (params?.get(k) as? JsonPrimitive)?.contentOrNull }
            ?: (o?.get("message") as? JsonPrimitive)?.contentOrNull
        return when (apCode) {
            "INVALID_CREDENTIALS", "INVALID_BEARER_TOKEN" -> "E-mail ou senha do hub incorretos."
            "INVALID_APP_CONNECTION" -> "O serviço recusou os dados da conexão" + (detail?.let { ": $it" } ?: ".")
            "EXISTING_USER" -> "Já existe uma conta no hub. Entre com o e-mail e a senha dela."
            "AUTHENTICATION", "SESSION_EXPIRED" -> "Sessão do hub expirada."
            else -> listOfNotNull(apCode, detail).joinToString(": ").ifBlank { "HTTP $code" }
        }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) {
        if (!ActivepiecesHub.ensureRunning()) throw HubError("O hub de conectores não está no ar. Inicie-o em Apps › Conectores.")
        block()
    }

    // ---------------- Login local ----------------
    private fun parseLogin(email: String, password: String, body: String): Login {
        val o = json.parseToJsonElement(body).jsonObject
        val token = (o["token"] as? JsonPrimitive)?.contentOrNull ?: throw HubError("O hub não devolveu o token de acesso.")
        val project = (o["projectId"] as? JsonPrimitive)?.contentOrNull ?: throw HubError("A conta do hub não tem projeto.")
        return Login(email, password, token, project, System.currentTimeMillis())
    }

    suspend fun signIn(email: String, password: String): Login = io {
        val (code, body) = call("POST", "/v1/authentication/sign-in", buildJsonObject { put("email", email.trim()); put("password", password) })
        if (code !in 200..299) throw HubError(errorText(code, body), code)
        parseLogin(email.trim(), password, body).also { store(it) }
    }

    /** Cria a conta local (só funciona quando o hub ainda não tem nenhuma). */
    suspend fun signUp(email: String, password: String, firstName: String, lastName: String): Login = io {
        val (code, body) = call("POST", "/v1/authentication/sign-up", buildJsonObject {
            put("email", email.trim()); put("password", password); put("firstName", firstName.trim().ifBlank { "Fluwy" })
            put("lastName", lastName.trim().ifBlank { "Bot" }); put("trackEvents", false); put("newsLetter", false)
        })
        if (code !in 200..299) throw HubError(errorText(code, body), code)
        parseLogin(email.trim(), password, body).also { store(it) }
    }

    /** Sessão válida: reaproveita o token (vale 7 dias) e entra de novo sozinho antes de vencer. */
    private suspend fun session(): Login {
        val l = savedLogin() ?: throw HubError("Entre no hub uma vez (e-mail e senha da conta local) para conectar apps pelo app.")
        if (l.token.isNotBlank() && System.currentTimeMillis() - l.at < 6L * 24 * 3600_000) return l
        return signIn(l.email, l.password)
    }

    /** Chamada autenticada; em 401/403 entra de novo uma vez e repete. */
    private suspend fun authed(method: String, path: String, body: JsonElement? = null, query: Map<String, String> = emptyMap()): String {
        var l = session()
        var (code, text) = io { call(method, path, body, l.token, query) }
        if (code == 401 || code == 403) {
            l = signIn(l.email, l.password)
            val again = io { call(method, path, body, l.token, query) }; code = again.first; text = again.second
        }
        if (code !in 200..299) throw HubError(errorText(code, text), code)
        return text
    }

    // ---------------- Catálogo (público) ----------------
    data class PieceSummary(val name: String, val displayName: String, val description: String, val logoUrl: String, val actions: Int, val authTypes: List<String>)

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()

    fun authList(el: JsonElement?): List<JsonObject> = when (el) {
        is JsonObject -> listOf(el)
        is JsonArray -> el.mapNotNull { it as? JsonObject }
        else -> emptyList()
    }

    // ---------------- Cache em disco (só dados públicos do catálogo; nunca credenciais) ----------------
    private fun cacheFile(name: String) = java.io.File(CueApp.instance.cacheDir, "hub/" + name.replace(Regex("[^A-Za-z0-9._-]"), "_")).apply { parentFile?.mkdirs() }
    private fun cached(name: String, maxAgeMs: Long): String? = cacheFile(name).takeIf { it.isFile && System.currentTimeMillis() - it.lastModified() < maxAgeMs }?.readText()
    private fun putCache(name: String, body: String) = runCatching { cacheFile(name).writeText(body) }

    /** Catálogo da última vez (para a tela abrir na hora, mesmo antes de o hub subir). */
    fun cachedPieces(): List<PieceSummary> = runCatching { cached("pieces.json", 30L * 24 * 3600_000)?.let { parsePieces(it) } }.getOrNull().orEmpty()

    private fun parsePieces(body: String) = (json.parseToJsonElement(body) as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map { o ->
        PieceSummary(o.s("name"), o.s("displayName"), o.s("description"), o.s("logoUrl"),
            (o["actions"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0, authList(o["auth"]).map { it.s("type") })
    }

    /** Catálogo: sem busca usa o cache de até 24 h; busca filtra o cache localmente quando ele existe. */
    suspend fun pieces(search: String = ""): List<PieceSummary> {
        cached("pieces.json", 24 * 3600_000L)?.let { body ->
            val all = runCatching { parsePieces(body) }.getOrNull()
            if (all != null) {
                val q = search.trim().lowercase()
                return if (q.isBlank()) all else all.filter { (it.displayName + " " + it.name + " " + it.description).lowercase().contains(q) }
            }
        }
        return io {
        val q = buildMap { put("sortBy", "POPULARITY"); put("orderBy", "DESC"); if (search.isNotBlank()) put("searchQuery", search.trim()) }
        val (code, body) = call("GET", "/v1/pieces", query = q)
        if (code !in 200..299) throw HubError(errorText(code, body), code)
        if (search.isBlank()) putCache("pieces.json", body)
        (json.parseToJsonElement(body) as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map { o ->
            PieceSummary(o.s("name"), o.s("displayName"), o.s("description"), o.s("logoUrl"),
                (o["actions"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0, authList(o["auth"]).map { it.s("type") })
        }
        }
    }

    private val pieceCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, JsonObject>>()

    /** "gmail" → "@activepieces/piece-gmail"; nomes completos passam como estão. */
    fun pieceName(raw: String): String {
        val t = raw.trim().lowercase()
        return when {
            t.startsWith("@") -> t
            t.startsWith("piece-") -> "@activepieces/$t"
            else -> "@activepieces/piece-" + t.replace(Regex("[^a-z0-9]+"), "-").trim('-')
        }
    }

    /** Metadados completos de um conector (ações com campos, auth). Cache em memória (30 min) e em disco (7 dias). */
    suspend fun piece(name: String): JsonObject {
        val full = pieceName(name)
        pieceCache[full]?.takeIf { System.currentTimeMillis() - it.first < 30 * 60_000 }?.let { return it.second }
        cached("piece_$full.json", 7L * 24 * 3600_000)?.let { body ->
            runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()?.let { pieceCache[full] = System.currentTimeMillis() to it; return it }
        }
        return io {
        val (code, body) = call("GET", "/v1/pieces/$full")
        if (code == 404) throw HubError("O conector \"$name\" não existe no catálogo do hub.", 404)
        if (code !in 200..299) throw HubError(errorText(code, body), code)
        putCache("piece_$full.json", body)
        json.parseToJsonElement(body).jsonObject.also { pieceCache[full] = System.currentTimeMillis() to it }
        }
    }

    /** Força recarregar o catálogo na próxima abertura (ex.: depois de atualizar o hub). */
    fun invalidateCatalog() { runCatching { java.io.File(CueApp.instance.cacheDir, "hub").deleteRecursively() }; pieceCache.clear() }

    // ---------------- OAuth ----------------
    private var cloudApps: Pair<Long, Map<String, String>>? = null

    /** Apps OAuth oficiais do Activepieces (client_id por conector) usados no login sem configurar nada. */
    suspend fun cloudClientId(pieceName: String): String? = withContext(Dispatchers.IO) {
        val cached = cloudApps?.takeIf { System.currentTimeMillis() - it.first < 6 * 3600_000L }?.second
        val map = cached ?: runCatching {
            web.newCall(Request.Builder().url("https://secrets.activepieces.com/apps?edition=ce").build()).execute().use { r ->
                val o = json.parseToJsonElement(r.body?.string().orEmpty()).jsonObject
                o.mapNotNull { (k, v) -> ((v as? JsonObject)?.get("clientId") as? JsonPrimitive)?.contentOrNull?.let { k to it } }.toMap()
            }
        }.getOrNull()?.also { cloudApps = System.currentTimeMillis() to it } ?: emptyMap()
        map[pieceName]
    }

    /** Endereço de retorno do OAuth com client próprio (o que o painel mostra). */
    suspend fun ownRedirectUrl(): String = io {
        val (code, body) = call("GET", "/v1/flags")
        val o = if (code in 200..299) runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() else null
        (o?.get("THIRD_PARTY_AUTH_PROVIDER_REDIRECT_URL") as? JsonPrimitive)?.contentOrNull ?: (ActivepiecesHub.BASE + "/redirect")
    }

    data class AuthUrl(val url: String, val codeVerifier: String?)

    suspend fun authorizationUrl(pieceName: String, clientId: String, redirectUrl: String, props: Map<String, String>): AuthUrl {
        val l = session()
        val text = authed("POST", "/v1/app-connections/oauth2/authorization-url", buildJsonObject {
            put("pieceName", pieceName); put("projectId", l.projectId); put("clientId", clientId); put("redirectUrl", redirectUrl)
            if (props.isNotEmpty()) put("props", JsonObject(props.mapValues { JsonPrimitive(it.value) }))
        })
        val o = json.parseToJsonElement(text).jsonObject
        return AuthUrl(o.s("authorizationUrl").ifBlank { throw HubError("O hub não gerou o link de login.") }, o.s("codeVerifier").ifBlank { null })
    }

    // ---------------- Conexões ----------------
    private fun newExternalId() = java.util.UUID.randomUUID().toString().replace("-", "").take(21)

    /** Cria a conexão; [value] é o objeto "value" da API (com "type"). O hub valida a credencial no serviço antes de salvar. */
    suspend fun createConnection(pieceName: String, displayName: String, type: String, value: JsonObject): String {
        val l = session()
        val text = authed("POST", "/v1/app-connections", buildJsonObject {
            put("externalId", newExternalId()); put("displayName", displayName.ifBlank { pieceName.substringAfterLast("piece-") })
            put("pieceName", pieceName); put("projectId", l.projectId); put("type", type); put("value", value)
        })
        HubConnectors.invalidate()
        return runCatching { json.parseToJsonElement(text).jsonObject.s("externalId") }.getOrDefault("")
    }

    data class HubConnection(val id: String, val externalId: String, val displayName: String, val pieceName: String, val status: String)

    suspend fun connections(): List<HubConnection> {
        val l = session()
        val out = ArrayList<HubConnection>()
        var cursor: String? = null
        do {
            val text = authed("GET", "/v1/app-connections", query = buildMap { put("projectId", l.projectId); put("limit", "100"); cursor?.let { put("cursor", it) } })
            val o = json.parseToJsonElement(text).jsonObject
            (o["data"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.forEach { c ->
                out += HubConnection(c.s("id"), c.s("externalId"), c.s("displayName"), c.s("pieceName"), c.s("status"))
            }
            cursor = o.s("next").ifBlank { null }
        } while (cursor != null && out.size < 1000)
        return out
    }

    suspend fun deleteConnection(id: String) { authed("DELETE", "/v1/app-connections/$id"); HubConnectors.invalidate() }
}
