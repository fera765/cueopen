package dev.cueopen.app.llm
import dev.cueopen.app.agent.ToolCallHistoryNormalizer
import dev.cueopen.app.data.LlmMessage
import dev.cueopen.app.data.ProviderModels
import dev.cueopen.app.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import dev.cueopen.app.CueApp
import dev.cueopen.app.proxy.ProxyScope
import dev.cueopen.app.net.await
import dev.cueopen.app.util.runCatchingCancellable

data class ToolCall(val id: String, val name: String, val argsJson: String)
data class Usage(val prompt: Long = 0, val completion: Long = 0, val cached: Long = 0)
data class LlmReply(val content: String?, val toolCalls: List<ToolCall>, val rawToolCallsJson: String?, val usage: Usage = Usage(), val truncated: Boolean = false)
data class LlmModelLimits(val contextTokens: Int? = null, val maxCompletionTokens: Int? = null)

class LlmRequestFailure(
    message: String,
    val retryable: Boolean,
    cause: Throwable? = null,
    val retryAfterMillis: Long? = null,
    val responseTruncated: Boolean = false,
) : IOException(message, cause)

/** Transporte OpenAI-compatible (OpenRouter por padrão). */
class OpenRouter(private val settings: () -> Settings) {
    private val json = Json { ignoreUnknownKeys = true }
    private val baseHttp = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(5, TimeUnit.MINUTES).build()
    private fun http(): OkHttpClient {
        val app = runCatching { CueApp.instance }.getOrElse {
            throw IOException("CueApp ainda não inicializou; recusando conexão Agent/API direta.", it)
        }
        return app.proxyHttpClient(ProxyScope.AGENT_API, baseHttp)
    }

    /** Contexto do modelo para dimensionar tarefas delegadas; catálogo e chat usam o mesmo proxy Agent/API. */
    suspend fun modelLimits(modelId: String?): LlmModelLimits? = withContext(Dispatchers.IO) {
        val s = settings()
        val selected = modelId?.takeIf { it.isNotBlank() } ?: s.model
        if (selected.isBlank()) return@withContext null
        try {
            val model = ProviderModels.all(
                s.baseUrl,
                s.apiKey,
                httpClient = http(),
                cacheScope = s.agentApiProxyId ?: "direct",
            ).firstOrNull { it.id == selected } ?: return@withContext null
            LlmModelLimits(
                contextTokens = model.context.takeIf { it > 0 },
                maxCompletionTokens = model.maxCompletionTokens.takeIf { it > 0 },
            ).takeIf { it.contextTokens != null || it.maxCompletionTokens != null }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            null
        }
    }

    // modelos que recusam reasoning desligado (lembrado em runtime para não repetir o erro)
    private val forceReasoning = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private data class HttpResult(val code: Int, val body: String, val retryAfterMillis: Long?)
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
            runCatchingCancellable {
                val request = Request.Builder().url(endpoint(s.baseUrl, "models")).apply {
                    if (s.apiKey.isNotBlank()) header("Authorization", "Bearer ${KeyPool.current(s.apiKey)}")
                }.get().build()
                http().newCall(request).await().use { response ->
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
        maxTokens: Int? = 8192,
        jsonResponse: Boolean = false,
        conversationId: String? = null,
    ): LlmReply = withContext(Dispatchers.IO) {
        val (s, mdl) = settings().let { base -> ProviderRoute.route(base, model ?: base.model) }
        require(mdl.isNotBlank()) { "Escolha um modelo em Configurações › Provedores." }
        val openRouter = isOpenRouter(s.baseUrl)
        val openCodeSession = OpenCodeProtocol.sessionHeader(s.baseUrl, conversationId)
        val openCodeUserAgent = OpenCodeProtocol.userAgent(s.baseUrl)
        // Nunca envia um assistant.tool_calls pendente: interrompidos recebem marcador explícito,
        // replies órfãos/duplicados são removidos e cada call_id fica respondido exatamente uma vez.
        val safeMessages = ToolCallHistoryNormalizer.normalize(messages)
        fun buildBody(disableReasoning: Boolean, structuredOutput: Boolean) = buildJsonObject {
            put("model", mdl)
            if (maxTokens != null) put("max_tokens", maxTokens)
            put("temperature", 0.2)
            put("messages", buildJsonArray { safeMessages.forEach { add(it.toJson()) } })
            if (tools != null && tools.isNotEmpty()) {
                put("tools", tools)
                put("tool_choice", "auto")
            }
            if (structuredOutput) put("response_format", buildJsonObject { put("type", "json_object") })
            // `reasoning` e headers abaixo são extensões da OpenRouter, não do protocolo OpenAI geral.
            if (openRouter && disableReasoning) put("reasoning", buildJsonObject { put("enabled", false); put("exclude", true) })
        }
        suspend fun once(disableReasoning: Boolean, structuredOutput: Boolean): HttpResult {
            val req = Request.Builder()
                .url(endpoint(s.baseUrl, "chat/completions"))
                .apply {
                    if (s.apiKey.isNotBlank()) header("Authorization", "Bearer ${KeyPool.current(s.apiKey)}")
                    if (openRouter) header("HTTP-Referer", "https://github.com/cueopen").header("X-Title", "CueOpen")
                    openCodeSession?.let { header("x-opencode-session", it) }
                    openCodeUserAgent?.let { header("User-Agent", it) }
                }
                .post(buildBody(disableReasoning, structuredOutput).toString().toRequestBody("application/json".toMediaType()))
                .build()
            return try {
                http().newCall(req).await().use { response ->
                    HttpResult(
                        code = response.code,
                        body = response.body?.string().orEmpty(),
                        retryAfterMillis = LlmRetryPolicy.retryAfterMillis(response.header("Retry-After")),
                    )
                }
            } catch (e: IOException) {
                throw LlmRequestFailure("Falha de comunicação com o provedor: ${e.message ?: "erro de rede"}", retryable = true, cause = e)
            }
        }
        var disable = openRouter && s.disableThinking && mdl !in forceReasoning
        var structuredOutput = jsonResponse
        var code = 0
        var text = ""
        var retryAfterMillis: Long? = null
        var attempts = 0
        while (attempts < 3) {
            attempts++
            val result = once(disable, structuredOutput)
            code = result.code
            text = result.body
            retryAfterMillis = result.retryAfterMillis
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
            val retryable = LlmRetryPolicy.isTransientHttp(code, msg ?: text.take(300))
            throw LlmRequestFailure(
                "HTTP $code — ${msg ?: text.take(160)}",
                retryable = retryable,
                retryAfterMillis = retryAfterMillis,
            )
        }
        val root = json.parseToJsonElement(text).jsonObject
        root["error"]?.let { throw IOException("LLM: $it") }
        val choice0 = root["choices"]!!.jsonArray[0].jsonObject
        val finish = choice0["finish_reason"]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
        val msg = choice0["message"]!!.jsonObject
        val content = msg["content"]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
        val rawCallsElement = msg["tool_calls"]
        if (rawCallsElement != null && rawCallsElement !is JsonArray) {
            throw LlmRequestFailure("O provedor retornou tool_calls em formato inválido; nenhuma ferramenta foi executada.", retryable = true)
        }
        val tcs = rawCallsElement?.jsonArray
        val parsedCalls = tcs.orEmpty().mapNotNull { tc ->
            ToolCallHistoryNormalizer.canonicalizeToolCall(tc)?.let { it to ToolCall(it.id, it.name, it.argsJson) }
        }
        if (parsedCalls.size != tcs.orEmpty().size || parsedCalls.map { it.second.id }.distinct().size != parsedCalls.size) {
            throw LlmRequestFailure(
                "O provedor retornou argumentos de ferramenta incompletos ou JSON inválido; nenhuma chamada foi executada e o histórico não foi alterado.",
                retryable = true,
                responseTruncated = true,
            )
        }
        if (finish == "length" && !tcs.isNullOrEmpty()) {
            throw LlmRequestFailure(
                "A resposta do provedor foi cortada no limite de saída; as chamadas de ferramenta não foram executadas.",
                retryable = true,
                responseTruncated = true,
            )
        }
        val calls = parsedCalls.map { it.second }
        val rawToolCalls = parsedCalls.takeIf { it.isNotEmpty() }?.let { JsonArray(it.map { pair -> pair.first.normalizedJson }).toString() }
        val u = root["usage"]?.let { runCatching { it.jsonObject }.getOrNull() }
        fun num(o: JsonObject?, key: String) = o?.get(key)?.let { runCatching { it.jsonPrimitive.content.toDouble().toLong() }.getOrNull() } ?: 0L
        val cachedTok = u?.get("prompt_tokens_details")?.let { runCatching { it.jsonObject }.getOrNull() }?.let { num(it, "cached_tokens") } ?: 0L
        LlmReply(content, calls, rawToolCalls, Usage(num(u, "prompt_tokens"), num(u, "completion_tokens"), cachedTok), truncated = finish == "length")
    }

    /** Envia um único recorte visual, sem histórico, ferramentas ou persistência no armazenamento local. */
    suspend fun inspectImage(
        prompt: String,
        jpeg: ByteArray,
        model: String? = null,
        conversationId: String? = null,
    ): String = withContext(Dispatchers.IO) {
        try {
            if (jpeg.isEmpty() || jpeg.size > MAX_TRANSIENT_IMAGE_BYTES) {
                throw IOException("O recorte visual está vazio ou excede o limite de envio.")
            }
            require(prompt.isNotBlank()) { "Informe o que deve ser observado no recorte visual." }
            val (s, mdl) = settings().let { base -> ProviderRoute.route(base, model ?: base.model) }
            require(mdl.isNotBlank()) { "Escolha um modelo em Configurações › Provedores." }
            val openRouter = isOpenRouter(s.baseUrl)
            val sessionHeader = OpenCodeProtocol.sessionHeader(s.baseUrl, conversationId)
            val userContent = buildJsonArray {
                add(buildJsonObject { put("type", "text"); put("text", prompt) })
                add(buildJsonObject {
                    put("type", "image_url")
                    put("image_url", buildJsonObject {
                        put("url", "data:image/jpeg;base64,${java.util.Base64.getEncoder().encodeToString(jpeg)}")
                    })
                })
            }
            val body = buildJsonObject {
                put("model", mdl)
                put("max_tokens", 1200)
                put("temperature", 0.1)
                put("messages", buildJsonArray {
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", userContent)
                    })
                })
            }
            val request = Request.Builder()
                .url(endpoint(s.baseUrl, "chat/completions"))
                .apply {
                    if (s.apiKey.isNotBlank()) header("Authorization", "Bearer ${KeyPool.current(s.apiKey)}")
                    if (openRouter) header("HTTP-Referer", "https://github.com/cueopen").header("X-Title", "CueOpen")
                    sessionHeader?.let { header("x-opencode-session", it) }
                    OpenCodeProtocol.userAgent(s.baseUrl)?.let { header("User-Agent", it) }
                }
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            http().newCall(request).await().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val detail = runCatching {
                        json.parseToJsonElement(text).jsonObject["error"]?.jsonObject
                            ?.get("message")?.jsonPrimitive?.content
                    }.getOrNull() ?: text.take(180)
                    throw IOException("O modelo selecionado recusou entrada de imagem ou a solicitação visual falhou (HTTP ${response.code}): $detail")
                }
                val root = json.parseToJsonElement(text).jsonObject
                val message = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
                    ?: throw IOException("O provedor não retornou uma análise visual utilizável.")
                val answer = when (val content = message["content"]) {
                    is kotlinx.serialization.json.JsonPrimitive -> content.contentOrNull.orEmpty()
                    is JsonArray -> content.mapNotNull { part ->
                        runCatching { part.jsonObject["text"]?.jsonPrimitive?.contentOrNull }.getOrNull()
                    }.joinToString("\n")
                    else -> ""
                }.trim()
                answer.takeIf(String::isNotBlank)
                    ?: throw IOException("O modelo não retornou texto ao analisar a imagem.")
            }
        } finally {
            jpeg.fill(0)
        }
    }

    /** Pedido simples sem ferramentas (títulos, resumos), no modelo rápido. */
    suspend fun quick(prompt: String, maxTokens: Int = 200, conversationId: String? = null): String {
        val primary = settings().fastModel
        val fallback = settings().fallbackModel
        val model = ModelFallback.resolve(primary, fallback)
        return try {
            chat(listOf(LlmMessage("user", prompt)), maxTokens = maxTokens, model = model, conversationId = conversationId).content.orEmpty().trim()
                .also { if (model == primary) ModelFallback.onSuccess(primary) }
        } catch (t: LlmRequestFailure) {
            // Limite no modelo rápido: uma nova tentativa no modelo alternativo, se configurado.
            val rateLimited = LlmRecovery.classify(t.message, t.retryable, t.responseTruncated) == LlmRecovery.Category.RATE_LIMIT
            if (!rateLimited) throw t
            val raw = ProviderRoute.route(settings(), model).first.apiKey
            val next = if (KeyPool.rotate(raw)) model
                else if (ModelFallback.onRateLimit(model, fallback, primary = primary)) ModelFallback.resolve(primary, fallback)
                else throw t
            chat(listOf(LlmMessage("user", prompt)), maxTokens = maxTokens, model = next, conversationId = conversationId).content.orEmpty().trim()
        }
    }

    private fun LlmMessage.toJson(): JsonElement = buildJsonObject {
        put("role", role)
        if (content != null) put("content", content) else put("content", "")
        toolCallsJson?.let { put("tool_calls", json.parseToJsonElement(it)) }
        toolCallId?.let { put("tool_call_id", it) }
        name?.let { put("name", it) }
    }

    private companion object {
        const val MAX_TRANSIENT_IMAGE_BYTES = 1_000_000
    }
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
