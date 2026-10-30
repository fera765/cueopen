package dev.cueopen.app.agent

import dev.cueopen.app.data.LlmMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
            val sanitizedJson = if (unique.isEmpty()) null else JsonArray(unique.map { it.raw }).toString()
            output += message.copy(toolCallsJson = sanitizedJson)
            pending = unique.map { it.ref }
        }
        flushPending()
        return output
    }

    private data class ParsedCall(val ref: CallRef, val raw: JsonElement)

    private fun parseCalls(raw: String): List<ParsedCall> = runCatching {
        json.parseToJsonElement(raw).jsonArray.mapNotNull { element ->
            runCatching {
                val call = element.jsonObject
                val id = call["id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: return@runCatching null
                val name = call["function"]?.jsonObject?.get("name")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                    ?: return@runCatching null
                ParsedCall(CallRef(id, name), element)
            }.getOrNull()
        }
    }.getOrDefault(emptyList())
}
