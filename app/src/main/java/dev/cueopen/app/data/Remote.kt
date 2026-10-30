package dev.cueopen.app.data

import dev.cueopen.app.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private val http = OkHttpClient.Builder().callTimeout(25, TimeUnit.SECONDS).build()

/** Catálogo de qualquer provedor com endpoint de modelos OpenAI-compatible. */
object ProviderModels {
    data class Model(
        val id: String,
        val name: String,
        val context: Int = 0,
        val promptPrice: Double? = null,
        val completionPrice: Double? = null,
        val supportedParameters: Set<String> = emptySet(),
        val apiFamily: String? = null,
    )

    private data class Entry(val loadedAt: Long, val models: List<Model>)
    private val cache = ConcurrentHashMap<String, Entry>()
    private val fetchLocks = ConcurrentHashMap<String, Mutex>()
    private val json = Json { ignoreUnknownKeys = true }
    private const val CACHE_MS = 10 * 60 * 1000L
    // O catálogo Go enumera modelos de famílias diferentes, mas esta tela usa o endpoint OpenAI /chat/completions.
    // Lista de famílias incompatíveis publicada na tabela oficial OpenCode Go (atualizada com o app).
    private val openCodeNonChatCompletions = setOf(
        "grok-4.7", "gpt-5.6-luna", "gpt-6-luna", "muse-spark-1.2-contributor", "muse-spark-1.3-contributor",
        "claude-haiku-5-5", "minimax-m3", "qwen3.7-plus", "qwen3.8-flash", "qwen3.8-max", "gemini-4-argon-contributor",
    )

    fun supportsChatCompletions(baseUrl: String, modelId: String): Boolean {
        if (isSystemOneId(modelId)) return false
        val host = runCatching { validatedBase(baseUrl).host.lowercase() }.getOrNull() ?: return true
        return host != "opencode.ai" || modelId.lowercase() !in openCodeNonChatCompletions
    }

    private fun isSystemOneId(modelId: String): Boolean =
        modelId.startsWith("jev-", ignoreCase = true) || modelId.startsWith("typesafe/jev", ignoreCase = true)

    private fun isSystemOneModel(model: Model): Boolean =
        isSystemOneId(model.id) || model.apiFamily?.contains("systemone", ignoreCase = true) == true

    /**
     * OpenAI-compatible providers normally expose GET {baseUrl}/models. OpenCode Console lists
     * Go models on the sibling /inference/go/v1/models route (the OpenAI chat base URL ends in
     * /inference/go/openai/v1), as documented at opencode.ai/v2/docs/console/go/.
     */
    fun modelsEndpoint(baseUrl: String): String {
        val url = validatedBase(baseUrl)
        var path = url.encodedPath.trimEnd('/').removeSuffix("/chat/completions")
        if (path.endsWith("/models")) return url.newBuilder().encodedPath(path).build().toString()
        val isOpenCodeOpenAiV1 = url.host.equals("opencode.ai", ignoreCase = true) &&
            Regex("^/inference/(?:go/)?openai/v1$").matches(path)
        path = if (isOpenCodeOpenAiV1) path.removeSuffix("/openai/v1") + "/v1/models" else "$path/models"
        return url.newBuilder().encodedPath(path).build().toString()
    }

    private fun validatedBase(raw: String): HttpUrl {
        val url = raw.trim().toHttpUrlOrNull() ?: throw IOException("Base URL inválida. Use uma URL http(s) válida.")
        val localHttp = url.host.equals("localhost", true) || url.host == "127.0.0.1" || url.host == "::1"
        if (url.scheme != "https" && !(url.scheme == "http" && localHttp)) throw IOException("Use HTTPS para proteger sua chave; HTTP só é aceito em localhost.")
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) throw IOException("Não coloque credenciais dentro da URL.")
        if (url.encodedQuery != null || url.encodedFragment != null) throw IOException("A Base URL não pode conter query ou fragmento.")
        return url
    }

    private fun fingerprint(key: String): String = MessageDigest.getInstance("SHA-256")
        .digest(key.toByteArray(Charsets.UTF_8)).take(8).joinToString("") { "%02x".format(it) }

    suspend fun all(baseUrl: String, apiKey: String, forceRefresh: Boolean = false, includeSystemOne: Boolean = false): List<Model> {
        val endpoint = modelsEndpoint(baseUrl)
        val cacheKey = "$endpoint|${fingerprint(apiKey)}|systemone=$includeSystemOne"
        val now = System.currentTimeMillis()
        if (!forceRefresh) cache[cacheKey]?.takeIf { now - it.loadedAt < CACHE_MS }?.let { return it.models }
        val requestedAt = now
        return withContext(Dispatchers.IO) {
            val lock = fetchLocks.computeIfAbsent(cacheKey) { Mutex() }
            lock.withLock {
                val beforeFetch = System.currentTimeMillis()
                cache[cacheKey]?.let { entry ->
                    val fresh = beforeFetch - entry.loadedAt < CACHE_MS
                    val refreshedByConcurrentRequest = forceRefresh && entry.loadedAt >= requestedAt
                    if (refreshedByConcurrentRequest || (!forceRefresh && fresh)) return@withLock entry.models
                }
            fun fetch(url: String): List<Model> {
                val parsedUrl = url.toHttpUrl()
                val request = Request.Builder().url(parsedUrl).header("Accept", "application/json").apply {
                    if (parsedUrl.host.equals("opencode.ai", ignoreCase = true)) header("User-Agent", "CueOpen/${BuildConfig.VERSION_NAME}")
                    if (apiKey.isNotBlank()) header("Authorization", "Bearer ${apiKey.trim()}")
                }.get().build()
                val body = http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("Catálogo do provedor respondeu HTTP ${response.code}.")
                    response.body?.string().orEmpty()
                }
                return parseCatalog(body)
            }
            val base = validatedBase(baseUrl)
            val consoleEndpoint = if (includeSystemOne && base.host.equals("opencode.ai", ignoreCase = true))
                base.newBuilder().encodedPath("/inference/v1/models").build().toString() else null
            val consoleModels = consoleEndpoint?.let { url -> async {
                try { fetch(url).filter(::isSystemOneModel).map { it.copy(apiFamily = "systemone") } }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { emptyList() }
            } }
            val primary = fetch(endpoint)
            val models = primary.filter { supportsChatCompletions(baseUrl, it.id) || (includeSystemOne && isSystemOneModel(it)) }.toMutableList()
            consoleModels?.await()?.let(models::addAll)
            val distinctModels = models.distinctBy { it.id }
            if (distinctModels.isEmpty()) throw IOException("O endpoint respondeu, mas não retornou modelos compatíveis.")
            cache[cacheKey] = Entry(System.currentTimeMillis(), distinctModels)
            distinctModels
            }
        }
    }

    /** Parser puro para os formatos OpenAI `data`, `models` e array JSON. */
    fun parseCatalog(body: String): List<Model> {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return emptyList()
        val rows: JsonArray = when (root) {
            is JsonArray -> root
            is JsonObject -> (root["data"] as? JsonArray) ?: (root["models"] as? JsonArray) ?: return emptyList()
            else -> return emptyList()
        }
        fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }
        return rows.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val id = o.text("id") ?: return@mapNotNull null
            val pricing = o["pricing"] as? JsonObject
            val context = o["context_length"]?.let { (it as? JsonPrimitive)?.intOrNull }
                ?: (o["top_provider"] as? JsonObject)?.get("context_length")?.let { (it as? JsonPrimitive)?.intOrNull }
                ?: 0
            val parameters = (o["supported_parameters"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.lowercase() }?.toSet().orEmpty()
            Model(
                id = id,
                name = o.text("name") ?: id,
                context = context,
                promptPrice = pricing?.number("prompt")?.times(1_000_000.0),
                completionPrice = pricing?.number("completion")?.times(1_000_000.0),
                supportedParameters = parameters,
                apiFamily = o.text("api_family") ?: o.text("api") ?: o.text("endpoint"),
            )
        }.distinctBy { it.id }
    }

    fun search(list: List<Model>, q: String): List<Model> {
        val terms = q.lowercase().split(' ').filter { it.isNotBlank() }
        return list.filter { model -> terms.all { term -> model.id.lowercase().contains(term) || model.name.lowercase().contains(term) } }.take(40)
    }
}

/**
 * Marketplace de skills: busca no índice público Skyll (milhares de SKILL.md agregados de repositórios
 * do GitHub). Parser tolerante a variações de formato da resposta.
 */
object SkillHub {
    data class Remote(val id: String, val title: String, val description: String, val source: String, val installs: Int, val content: String)

    suspend fun search(query: String, limit: Int = 25): List<Remote> = withContext(Dispatchers.IO) {
        val url = "https://api.skyll.app/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query).addQueryParameter("limit", limit.toString()).build()
        val body = http.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { r ->
            if (!r.isSuccessful) error("Catálogo respondeu HTTP ${r.code}")
            r.body?.string().orEmpty()
        }
        val arr: JSONArray = runCatching { JSONObject(body).let { it.optJSONArray("skills") ?: it.optJSONArray("results") ?: it.optJSONArray("data") } }
            .getOrNull() ?: runCatching { JSONArray(body) }.getOrDefault(JSONArray())
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val content = o.optString("content", o.optString("skill_md", ""))
            val title = o.optString("title").ifBlank { o.optString("name") }.ifBlank { frontmatter(content, "name") }.ifBlank { o.optString("id") }
            val desc = o.optString("description").ifBlank { frontmatter(content, "description") }
                .ifBlank { content.lineSequence().firstOrNull { it.isNotBlank() && !it.startsWith("---") && !it.contains(":") }.orEmpty() }
            Remote(
                id = o.optString("id", title), title = title, description = desc.take(400),
                source = o.optString("source", o.optString("repo", o.optString("url", ""))),
                installs = o.optInt("install_count", o.optInt("installs", 0)), content = content,
            )
        }
    }

    private fun frontmatter(md: String, key: String): String {
        if (!md.startsWith("---")) return ""
        val fm = md.removePrefix("---").substringBefore("\n---")
        return fm.lines().firstOrNull { it.trim().startsWith("$key:") }?.substringAfter(":")?.trim()?.trim('"', '\'').orEmpty()
    }

    /** Converte a skill remota numa Skill local (corpo do SKILL.md sem o frontmatter). */
    fun toSkill(r: Remote): Skill {
        val body = if (r.content.startsWith("---")) r.content.removePrefix("---").substringAfter("\n---").trim() else r.content.trim()
        return Skill(name = r.title.take(60), description = r.description.ifBlank { "Skill importada de ${r.source}" },
            instructions = body.ifBlank { r.description }, icon = "sparkles")
    }
}
