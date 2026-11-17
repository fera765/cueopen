package dev.cueopen.app.connectors

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Gateway MCP: Smithery Connect (smithery.ai). Conta gratuita + API key dão acesso ao registro com
 * milhares de servidores MCP; o Connect hospeda a conexão, guarda as credenciais/OAuth de cada serviço
 * e expõe um endpoint MCP único por conexão: https://api.smithery.ai/connect/{namespace}/{id}/mcp.
 * O app só fala com esse endpoint usando a API key como Bearer — nenhum segredo de terceiros fica no app.
 */
object SmitheryGateway {
    const val API = "https://api.smithery.ai"
    const val REGISTRY = "https://registry.smithery.ai"
    const val KEYS_URL = "https://smithery.ai/account/api-keys"
    const val REF_PREFIX = "smithery:"

    data class Server(
        val qualifiedName: String,
        val displayName: String,
        val description: String,
        val iconUrl: String?,
        val useCount: Long,
        val verified: Boolean,
    )

    data class Page(val servers: List<Server>, val page: Int, val totalPages: Int, val totalCount: Int)

    data class Conn(val id: String, val state: String, val setupUrl: String?, val message: String?)

    private val jsonType = "application/json".toMediaType()
    private val base = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS).retryOnConnectionFailure(true).build()
    private fun http() = runCatching {
        dev.cueopen.app.CueApp.instance.proxyHttpClient(dev.cueopen.app.proxy.ProxyScope.AGENT_API, base)
    }.getOrDefault(base)

    private fun call(key: String, method: String, url: String, body: JSONObject? = null): Pair<Int, String> {
        val rb = Request.Builder().url(url).header("Authorization", "Bearer ${key.trim()}").header("Accept", "application/json")
        when (method) {
            "GET" -> rb.get()
            "DELETE" -> rb.delete()
            else -> rb.method(method, (body ?: JSONObject()).toString().toRequestBody(jsonType))
        }
        http().newCall(rb.build()).execute().use { r -> return r.code to (r.body?.string() ?: "") }
    }

    private fun fail(what: String, code: Int, body: String): Nothing = error(
        when (code) {
            401, 403 -> "Smithery recusou a API key (HTTP $code). Gere outra em smithery.ai › API keys."
            429 -> "Limite do plano gratuito do Smithery atingido (HTTP 429). Tente mais tarde."
            else -> "$what falhou (HTTP $code): ${body.take(160)}"
        }
    )

    /** Busca no registro (busca semântica). Query vazia = servidores mais usados. */
    suspend fun search(key: String, query: String, page: Int = 1, pageSize: Int = 30): Page = withContext(Dispatchers.IO) {
        val url = "$REGISTRY/servers".toHttpUrl().newBuilder()
            .addQueryParameter("q", query.trim())
            .addQueryParameter("page", page.toString())
            .addQueryParameter("pageSize", pageSize.toString()).build().toString()
        val (code, body) = call(key, "GET", url)
        if (code !in 200..299) fail("Busca no Smithery", code, body)
        val o = JSONObject(body)
        val arr = o.optJSONArray("servers")
        val list = buildList {
            for (i in 0 until (arr?.length() ?: 0)) {
                val s = arr!!.optJSONObject(i) ?: continue
                // Só servidores hospedados (remote) funcionam pelo Connect sem instalar nada no aparelho.
                if (s.has("remote") && !s.optBoolean("remote", true)) continue
                val qn = s.optString("qualifiedName")
                if (qn.isBlank()) continue
                add(Server(qn, s.optString("displayName").ifBlank { qn }, s.optString("description"),
                    s.optString("iconUrl").takeIf { it.startsWith("https://") }, s.optLong("useCount"), s.optBoolean("verified")))
            }
        }
        val p = o.optJSONObject("pagination")
        Page(list, p?.optInt("currentPage", page) ?: page, p?.optInt("totalPages", page) ?: page, p?.optInt("totalCount", list.size) ?: list.size)
    }

    /** Namespace do usuário: usa o primeiro existente ou cria um. */
    suspend fun namespace(key: String): String = withContext(Dispatchers.IO) {
        val (code, body) = call(key, "GET", "$API/namespaces")
        if (code !in 200..299) fail("Leitura do namespace", code, body)
        JSONObject(body).optJSONArray("namespaces")?.optJSONObject(0)?.optString("name")?.takeIf { it.isNotBlank() }?.let { return@withContext it }
        val name = "fluwy-" + java.util.UUID.randomUUID().toString().take(8)
        val (c2, b2) = call(key, "POST", "$API/namespaces", JSONObject().put("name", name))
        if (c2 !in 200..299) fail("Criação do namespace", c2, b2)
        JSONObject(b2).optString("name").ifBlank { name }
    }

    fun connectionId(qualifiedName: String): String =
        qualifiedName.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(60).ifBlank { "conn" }

    fun mcpUrl(namespace: String, id: String) = "$API/connect/$namespace/$id/mcp"
    fun ref(namespace: String, id: String) = "$REF_PREFIX$namespace/$id"
    fun parseRef(ref: String?): Pair<String, String>? = ref?.takeIf { it.startsWith(REF_PREFIX) }?.removePrefix(REF_PREFIX)
        ?.split('/', limit = 2)?.takeIf { it.size == 2 && it.all(String::isNotBlank) }?.let { it[0] to it[1] }

    private fun parseConn(body: String, fallbackId: String): Conn {
        val o = JSONObject(body)
        val st = o.optJSONObject("status")
        val setup = st?.optString("setupUrl")?.ifBlank { null } ?: st?.optString("authorizationUrl")?.ifBlank { null }
        return Conn(o.optString("connectionId").ifBlank { fallbackId }, st?.optString("state").orEmpty().lowercase(),
            setup?.takeIf { it.startsWith("https://") }, st?.optString("message")?.ifBlank { null })
    }

    /** Cria (ou reaproveita) a conexão no Connect para um servidor do registro. */
    suspend fun connect(key: String, namespace: String, server: Server): Conn = withContext(Dispatchers.IO) {
        val id = connectionId(server.qualifiedName)
        val (code, body) = call(key, "PUT", "$API/connect/$namespace/$id",
            JSONObject().put("server", server.qualifiedName).put("name", server.displayName.take(255)))
        if (code !in 200..299) fail("Conexão com ${server.displayName}", code, body)
        parseConn(body, id)
    }

    suspend fun status(key: String, namespace: String, id: String): Conn = withContext(Dispatchers.IO) {
        val (code, body) = call(key, "GET", "$API/connect/$namespace/$id")
        if (code !in 200..299) fail("Status da conexão", code, body)
        parseConn(body, id)
    }

    suspend fun delete(key: String, namespace: String, id: String) = withContext(Dispatchers.IO) {
        runCatching { call(key, "DELETE", "$API/connect/$namespace/$id") }
        Unit
    }
}
