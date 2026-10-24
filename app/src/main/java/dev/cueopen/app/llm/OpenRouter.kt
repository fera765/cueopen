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
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class ToolCall(val id: String, val name: String, val argsJson: String)
data class Usage(val prompt: Long = 0, val completion: Long = 0, val cached: Long = 0)
data class LlmReply(val content: String?, val toolCalls: List<ToolCall>, val rawToolCallsJson: String?, val usage: Usage = Usage())

/** Cliente OpenAI-compatível (OpenRouter por padrão). Raciocínio/thinking desligado por padrão. */
class OpenRouter(private val settings: () -> Settings) {
    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .build()

    suspend fun chat(
        messages: List<LlmMessage>,
        tools: JsonArray? = null,
        model: String? = null,
        maxTokens: Int = 4096,
    ): LlmReply = withContext(Dispatchers.IO) {
        val s = settings()
        require(s.apiKey.isNotBlank()) { "Configure sua chave da OpenRouter em Configurações." }
        val body = buildJsonObject {
            put("model", model ?: s.model)
            put("max_tokens", maxTokens)
            put("temperature", 0.3)
            put("messages", buildJsonArray { messages.forEach { add(it.toJson()) } })
            if (tools != null && tools.isNotEmpty()) {
                put("tools", tools)
                put("tool_choice", "auto")
            }
            if (s.disableThinking) {
                // zero thinking: mais rápido. OpenRouter normaliza para cada provedor.
                put("reasoning", buildJsonObject { put("enabled", false); put("exclude", true) })
            }
        }
        val req = Request.Builder()
            .url(s.baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer ${s.apiKey}")
            .header("HTTP-Referer", "https://github.com/cueopen")
            .header("X-Title", "CueOpen")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val text = http.newCall(req).await().use { r ->
            val t = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException("LLM HTTP ${r.code}: ${t.take(500)}")
            t
        }
        val root = json.parseToJsonElement(text).jsonObject
        root["error"]?.let { throw IOException("LLM: ${it}") }
        val msg = root["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject
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
        fun num(o: kotlinx.serialization.json.JsonObject?, k: String) = o?.get(k)?.let { runCatching { it.jsonPrimitive.content.toDouble().toLong() }.getOrNull() } ?: 0L
        val cachedTok = u?.get("prompt_tokens_details")?.let { runCatching { it.jsonObject }.getOrNull() }?.let { num(it, "cached_tokens") } ?: 0L
        LlmReply(content, calls, tcs?.toString(), Usage(num(u, "prompt_tokens"), num(u, "completion_tokens"), cachedTok))
    }

    /** Pedido simples sem ferramentas (títulos, resumos), no modelo rápido. */
    suspend fun quick(prompt: String, maxTokens: Int = 200): String =
        chat(listOf(LlmMessage("user", prompt)), model = settings().fastModel, maxTokens = maxTokens).content.orEmpty().trim()

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
