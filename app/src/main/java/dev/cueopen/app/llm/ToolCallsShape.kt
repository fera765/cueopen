package dev.cueopen.app.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Aceita as variações de "tool_calls" que provedores compatíveis com OpenAI mandam de verdade. */
object ToolCallsShape {
    /** Lista de chamadas (vazia = nenhuma) ou null quando o formato é mesmo irrecuperável. */
    fun normalize(e: JsonElement?): JsonArray? = when (e) {
        null, JsonNull -> JsonArray(emptyList())
        is JsonArray -> e
        is JsonObject -> if (e.isEmpty()) JsonArray(emptyList()) else JsonArray(listOf(e))
        is JsonPrimitive -> {
            val t = e.contentOrNull?.trim().orEmpty()
            if (t.isEmpty() || t == "null" || t == "[]") JsonArray(emptyList())
            else runCatching { normalize(Json.parseToJsonElement(t)).takeIf { t.startsWith("[") || t.startsWith("{") } }.getOrNull()
        }
    }
}
