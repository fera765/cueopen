package dev.cueopen.app.agent

import dev.cueopen.app.data.LlmMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Repara o protocolo antes de qualquer POST: cada assistant.tool_calls é seguido por exatamente
 * um resultado para cada ID, em ordem. Resultado ausente vira marcador explícito de interrupção
 * (não de sucesso); resultados órfãos/duplicados são descartados.
 */
object ToolCallHistoryNormalizer {
    private data class CallRef(val id: String, val name: String?)
    private val json = Json { ignoreUnknownKeys = true }

    fun normalize(messages: List<LlmMessage>): List<LlmMessage> {
        val output = ArrayList<LlmMessage>(messages.size)
        val globallySeenCallIds = HashSet<String>()
        var pending = emptyList<CallRef>()
        var results = emptyMap<String, LlmMessage>()

        fun flushPending() {
            if (pending.isEmpty()) return
            pending.forEach { call ->
                output += results[call.id] ?: LlmMessage(
                    role = "tool",
                    content = "interrupted/cancelled: nenhum resultado foi gravado para esta chamada antes da continuação da conversa. " +
                        "A execução da ferramenta pode não ter começado ou pode ter sido interrompida; não assuma sucesso nem repita sem verificar.",
                    toolCallId = call.id,
                    name = call.name,
                )
            }
            pending = emptyList()
            results = emptyMap()
        }

        for (message in messages) {
            if (message.role == "tool") {
                val id = message.toolCallId?.takeIf { it.isNotBlank() } ?: continue
                if (pending.any { it.id == id } && id !in results) results = results + (id to message)
                // Tool result órfão ou duplicado é descartado.
                continue
            }

            // Um novo user/assistant/system não pode ultrapassar chamadas anteriores sem resultado.
            flushPending()
            if (message.role != "assistant" || message.toolCallsJson.isNullOrBlank()) {
                output += message
                continue
            }

            val parsed = parseCalls(message.toolCallsJson)
            val unique = parsed.filter { globallySeenCallIds.add(it.ref.id) }
            if (unique.isEmpty()) {
                if (!message.content.isNullOrBlank()) output += message.copy(toolCallsJson = null)
                continue
            }
            val sanitizedJson = JsonArray(unique.map { it.raw }).toString()
            output += message.copy(toolCallsJson = sanitizedJson)
            pending = unique.map { it.ref }
        }
        flushPending()
        return output
    }

    /** Redacts typed UI text before assistant tool calls are persisted or sent again to a provider. */
    fun redactSensitiveToolArguments(raw: String?): String? {
        if (raw.isNullOrBlank()) return raw
        return runCatching {
            val calls = json.parseToJsonElement(raw).jsonArray
            calls.mapNotNull { canonicalizeToolCall(it)?.normalizedJson }
                .takeIf { it.isNotEmpty() }?.let { JsonArray(it).toString() }
        }.getOrNull()
    }

    /** Validates nested function arguments and canonicalizes them to the OpenAI-compatible string form. */
    data class CanonicalToolCall(
        val id: String,
        val name: String,
        val argsJson: String,
        val normalizedJson: JsonElement,
    )

    fun canonicalizeToolCall(element: JsonElement): CanonicalToolCall? = runCatching {
        val call = element.jsonObject
        val id = call["id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return@runCatching null
        val function = call["function"]?.jsonObject ?: return@runCatching null
        val name = function["name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return@runCatching null
        val rawArguments = function["arguments"] ?: return@runCatching null
        val arguments = if (rawArguments is JsonPrimitive && rawArguments.isString) {
            json.parseToJsonElement(rawArguments.content)
        } else rawArguments
        if (arguments !is JsonObject) return@runCatching null
        val argsJson = arguments.toString()
        val normalizedFunction = JsonObject(function.toMutableMap().apply {
            put("arguments", JsonPrimitive(argsJson))
        })
        val normalizedCall = JsonObject(call.toMutableMap().apply { put("function", normalizedFunction) })
        CanonicalToolCall(id, name, argsJson, redactToolCall(normalizedCall))
    }.getOrNull()

    private fun redactToolCall(element: JsonElement): JsonElement {
        val call = runCatching { element.jsonObject }.getOrNull() ?: return element
        val function = runCatching { call["function"]?.jsonObject }.getOrNull() ?: return element
        val name = runCatching { function["name"]?.jsonPrimitive?.content }.getOrNull()
        if (name != "device_accessibility_type") return element

        val originalArguments = function["arguments"]
        val argumentsAreString = originalArguments is JsonPrimitive && originalArguments.isString
        val parsedArguments = runCatching {
            if (argumentsAreString) json.parseToJsonElement(originalArguments!!.jsonPrimitive.content).jsonObject
            else originalArguments?.jsonObject
        }.getOrNull()
        val safeArguments = JsonObject((parsedArguments?.toMutableMap() ?: mutableMapOf()).apply {
            put("text", JsonPrimitive("[oculto]"))
        })
        val safeFunction = JsonObject(function.toMutableMap().apply {
            put("arguments", if (argumentsAreString || originalArguments == null) JsonPrimitive(safeArguments.toString()) else safeArguments)
        })
        return JsonObject(call.toMutableMap().apply { put("function", safeFunction) })
    }

    private data class ParsedCall(val ref: CallRef, val raw: JsonElement)

    private fun parseCalls(raw: String): List<ParsedCall> = runCatching {
        json.parseToJsonElement(raw).jsonArray.mapNotNull { element ->
            canonicalizeToolCall(element)?.let { call -> ParsedCall(CallRef(call.id, call.name), call.normalizedJson) }
        }
    }.getOrDefault(emptyList())
}
