package dev.fluwy.app.workflow

import kotlinx.serialization.json.*

/**
 * Encolhe um JSON grande mantendo-o VÁLIDO (cortar o texto no meio gerava JSON quebrado que o agente não lia):
 * cabeçalhos técnicos de e-mail viram só os úteis, textos longos e listas grandes são aparados aos poucos.
 */
object JsonCompact {
    private val KEEP_HEADERS = setOf("from", "to", "cc", "subject", "date", "reply-to", "message-id")
    private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }

    fun shrink(el: JsonElement, maxChars: Int): JsonElement {
        var cur = slimHeaders(el)
        if (size(cur) <= maxChars) return cur
        for ((str, arr) in listOf(4_000 to 50, 1_500 to 25, 600 to 12, 250 to 6, 120 to 3)) {
            cur = trim(cur, str, arr)
            if (size(cur) <= maxChars) return cur
        }
        return cur
    }

    fun shrinkText(text: String, maxChars: Int): String {
        val el = runCatching { Json.parseToJsonElement(text) }.getOrNull() ?: return if (text.length <= maxChars) text else text.take(maxChars) + "…"
        val s = shrink(el, maxChars)
        return pretty.encodeToString(JsonElement.serializer(), s)
    }

    private fun size(e: JsonElement) = pretty.encodeToString(JsonElement.serializer(), e).length

    private fun slimHeaders(e: JsonElement): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.mapValues { (k, v) ->
            if (k.equals("headers", true) && v is JsonObject && v.size > 8) JsonObject(v.filterKeys { it.lowercase() in KEEP_HEADERS })
            else slimHeaders(v)
        })
        is JsonArray -> JsonArray(e.map { slimHeaders(it) })
        else -> e
    }

    private fun trim(e: JsonElement, maxStr: Int, maxArr: Int): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.mapValues { trim(it.value, maxStr, maxArr) })
        is JsonArray -> JsonArray(e.take(maxArr).map { trim(it, maxStr, maxArr) } +
            (if (e.size > maxArr) listOf(JsonPrimitive("… +${e.size - maxArr} itens omitidos")) else emptyList()))
        is JsonPrimitive -> if (e.isString && e.content.length > maxStr) JsonPrimitive(e.content.take(maxStr) + "…[cortado]") else e
    }
}
