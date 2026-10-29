package dev.cueopen.app.llm

import dev.cueopen.app.data.LlmMessage
import dev.cueopen.app.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class ToolCall(val id: String, val name: String, val argsJson: String)
data class Usage(val prompt: Long = 0, val completion: Long = 0, val cached: Long = 0)
data class LlmReply(val content: String?, val toolCalls: List<ToolCall>, val rawToolCallsJson: String?, val usage: Usage = Usage(), val truncated: Boolean = false)

/** Transporte OpenAI-compatible (OpenRouter por padrão), com suporte opcional ao System One do Jev. */
class OpenRouter(private val settings: () -> Settings) {
    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .build()

    // modelos que recusam reasoning desligado (lembrado em runtime para não repetir o erro)
    private val forceReasoning = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private data class ModelPrice(val prompt: Double, val completion: Double, val cacheRead: Double?)
    @Volatile private var priceCacheAt = 0L
    @Volatile private var priceCache: Map<String, ModelPrice> = emptyMap()

    private fun endpoint(baseUrl: String, path: String): String {
        val uri = runCatching { URI(baseUrl.trim()) }.getOrElse { throw IOException("Base URL inválida.") }
        val scheme = uri.scheme?.lowercase()
        val host = uri.host ?: throw IOException("Base URL inválida.")
        val localHttp = host.equals("localhost", true) || host == "127.0.0.1" || host == "::1" || host == "[::1]"
        if (scheme != "https" && !(scheme == "http" && localHttp)) throw IOException("Use HTTPS para proteger sua chave; HTTP só é aceito em localhost.")
        if (uri.userInfo != null || uri.rawQuery != null || uri.rawFragment != null) throw IOException("A Base URL não pode conter credenciais, query ou fragmento.")
        val basePath = (uri.path ?: "").trimEnd('/')
        val fullPath = if (basePath.endsWith("/$path", ignoreCase = true)) basePath else "$basePath/$path"
        return runCatching { URI(scheme, null, host, uri.port, fullPath, null, null).toASCIIString() }
            .getOrElse { throw IOException("Base URL inválida.") }
    }

    private fun isOpenRouter(baseUrl: String): Boolean =
        runCatching { URI(baseUrl.trim()).host?.lowercase() == "openrouter.ai" }.getOrDefault(false)

    /** Estima o custo em USD com preços publicados em /models; null significa que não há preço confiável. */
    suspend fun estimateCostUsd(modelId: String, usage: Usage): Double? = withContext(Dispatchers.IO) {
        val s = settings()
        if (!isOpenRouter(s.baseUrl)) return@withContext null
        if (priceCache.isEmpty() || System.currentTimeMillis() - priceCacheAt > 6 * 60 * 60 * 1000L) {
            runCatching {
                val request = Request.Builder().url(endpoint(s.baseUrl, "models")).apply {
                    if (s.apiKey.isNotBlank()) header("Authorization", "Bearer ${s.apiKey}")
                }.get().build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val root = json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
                    val entries = root["data"]?.jsonArray ?: return@use
                    val next = entries.mapNotNull { element ->
                        runCatching {
                            val item = element.jsonObject
                            val id = item["id"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
                            val p = item["pricing"]?.jsonObject ?: return@runCatching null
                            val input = p["prompt"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: return@runCatching null
                            val output = p["completion"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: return@runCatching null
                            val cache = p["input_cache_read"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                            id to ModelPrice(input, output, cache)
                        }.getOrNull()
                    }.filterNotNull().toMap()
                    if (next.isNotEmpty()) {
                        priceCache = next
                        priceCacheAt = System.currentTimeMillis()
                    }
                }
            }
        }
        val p = priceCache[modelId] ?: return@withContext null
        val cached = usage.cached.coerceIn(0L, usage.prompt)
        val uncached = usage.prompt - cached
        val inputCost = uncached * p.prompt + cached * (p.cacheRead ?: p.prompt)
        val cost = inputCost + usage.completion * p.completion
        if (!cost.isFinite() || cost < 0.0) null else cost
    }

    suspend fun chat(
        messages: List<LlmMessage>,
        tools: JsonArray? = null,
        model: String? = null,
        maxTokens: Int = 16000,
        jsonResponse: Boolean = false,
    ): LlmReply = withContext(Dispatchers.IO) {
        val s = settings()
        val mdl = model ?: s.model
        require(mdl.isNotBlank()) { "Escolha um modelo nas Configurações." }
        val openRouter = isOpenRouter(s.baseUrl)
        fun buildBody(disableReasoning: Boolean, structuredOutput: Boolean) = buildJsonObject {
            put("model", mdl)
            put("max_tokens", maxTokens)
            put("temperature", 0.2)
            put("messages", buildJsonArray { messages.forEach { add(it.toJson()) } })
            if (tools != null && tools.isNotEmpty()) {
                put("tools", tools)
                put("tool_choice", "auto")
            }
            if (structuredOutput) put("response_format", buildJsonObject { put("type", "json_object") })
            // `reasoning` e headers abaixo são extensões da OpenRouter, não do protocolo OpenAI geral.
            if (openRouter && disableReasoning) put("reasoning", buildJsonObject { put("enabled", false); put("exclude", true) })
        }
        fun once(disableReasoning: Boolean, structuredOutput: Boolean): Pair<Int, String> {
            val req = Request.Builder()
                .url(endpoint(s.baseUrl, "chat/completions"))
                .apply {
                    if (s.apiKey.isNotBlank()) header("Authorization", "Bearer ${s.apiKey}")
                    if (openRouter) header("HTTP-Referer", "https://github.com/cueopen").header("X-Title", "CueOpen")
                }
                .post(buildBody(disableReasoning, structuredOutput).toString().toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { response -> response.code to response.body?.string().orEmpty() }
        }
        var disable = openRouter && s.disableThinking && mdl !in forceReasoning
        var structuredOutput = jsonResponse
        var code = 0
        var text = ""
        var attempts = 0
        while (attempts < 3) {
            attempts++
            val result = once(disable, structuredOutput)
            code = result.first
            text = result.second
            if (code == 400 && structuredOutput && (text.contains("response_format", true) || text.contains("json_object", true))) {
                structuredOutput = false
                continue
            }
            if (code == 400 && disable && text.contains("reasoning", true)) {
                forceReasoning.add(mdl)
                disable = false
                continue
            }
            break
        }
        if (code !in 200..299) {
            val msg = runCatching { json.parseToJsonElement(text).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content }.getOrNull()
            throw IOException("HTTP $code — ${msg ?: text.take(160)}")
        }
        val root = json.parseToJsonElement(text).jsonObject
        root["error"]?.let { throw IOException("LLM: $it") }
        val choice0 = root["choices"]!!.jsonArray[0].jsonObject
        val finish = choice0["finish_reason"]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
        val msg = choice0["message"]!!.jsonObject
        val content = msg["content"]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
        val tcs = msg["tool_calls"]?.takeIf { it is JsonArray }?.jsonArray
        val calls = tcs?.map { tc ->
            val o = tc.jsonObject
            val f = o["function"]!!.jsonObject
            ToolCall(
                id = o["id"]?.jsonPrimitive?.contentOrNull ?: "call_${System.nanoTime()}",
                name = f["name"]!!.jsonPrimitive.content,
                argsJson = f["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}",
            )
        }.orEmpty()
        val u = root["usage"]?.let { runCatching { it.jsonObject }.getOrNull() }
        fun num(o: JsonObject?, key: String) = o?.get(key)?.let { runCatching { it.jsonPrimitive.content.toDouble().toLong() }.getOrNull() } ?: 0L
        val cachedTok = u?.get("prompt_tokens_details")?.let { runCatching { it.jsonObject }.getOrNull() }?.let { num(it, "cached_tokens") } ?: 0L
        LlmReply(content, calls, tcs?.toString(), Usage(num(u, "prompt_tokens"), num(u, "completion_tokens"), cachedTok), truncated = finish == "length")
    }

    /** Chamada nativa System One para Jev: não passa pelo endpoint de geração de texto. */
    suspend fun systemOne(model: String, body: JsonObject): String = withContext(Dispatchers.IO) {
        val s = settings()
        require(body["model"]?.jsonPrimitive?.content == model) { "O modelo do pedido System One não corresponde ao selecionado." }
        val request = Request.Builder()
            .url(endpoint(s.baseUrl, "systemone"))
            .apply { if (s.apiKey.isNotBlank()) header("Authorization", "Bearer ${s.apiKey}") }
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching { json.parseToJsonElement(text).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content }.getOrNull()
                throw IOException("System One respondeu HTTP ${response.code} — ${message ?: text.take(160)}")
            }
            text
        }
    }

    /** Pedido simples sem ferramentas (títulos, resumos), no modelo rápido. */
    suspend fun quick(prompt: String, maxTokens: Int = 200): String =
        chat(listOf(LlmMessage("user", prompt)), maxTokens = maxTokens,
            model = settings().let { dev.cueopen.app.agent.JevOrchestrator.auxModel(it.fastModel, it.jevRouting, it.baseUrl.contains("openrouter", true)) }
        ).content.orEmpty().trim()

    private fun LlmMessage.toJson(): JsonElement = buildJsonObject {
        put("role", role)
        if (content != null) put("content", content) else put("content", "")
        toolCallsJson?.let { put("tool_calls", json.parseToJsonElement(it)) }
        toolCallId?.let { put("tool_call_id", it) }
        name?.let { put("name", it) }
    }
}

suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (!cont.isCancelled) cont.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) { cont.resume(response) }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}

/** Helper para declarar ferramentas no formato function-calling. */
fun tool(name: String, description: String, props: JsonObject, required: List<String> = emptyList()): JsonObject =
    buildJsonObject {
        put("type", "function")
        put("function", buildJsonObject {
            put("name", name)
            put("description", description)
            put("parameters", buildJsonObject {
                put("type", "object")
                put("properties", props)
                put("required", buildJsonArray { required.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
            })
        })
    }

fun str(desc: String) = buildJsonObject { put("type", "string"); put("description", desc) }
fun int(desc: String) = buildJsonObject { put("type", "integer"); put("description", desc) }
fun bool(desc: String) = buildJsonObject { put("type", "boolean"); put("description", desc) }
fun strArray(desc: String) = buildJsonObject {
    put("type", "array"); put("description", desc); put("items", buildJsonObject { put("type", "string") })
}
