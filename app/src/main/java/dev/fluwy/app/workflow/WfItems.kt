package dev.fluwy.app.workflow

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Item de dados que circula entre nós, como no n8n (`{json: {...}}`). [raw] guarda o texto original quando o item veio
 * de uma saída de texto/JSON de um nó (mantém o formato exato para os modelos antigos `{{prev}}`).
 */
data class WfItem(val json: JsonObject, val raw: String? = null) {
    /** Texto do item: o original, ou `text` de um item só de texto, ou o JSON. */
    fun text(): String = raw ?: (json.keys.singleOrNull()?.takeIf { it == TEXT }?.let { (json[TEXT] as? JsonPrimitive)?.content } ?: json.toString())

    companion object {
        const val TEXT = "text"
        val EMPTY = WfItem(JsonObject(emptyMap()))
        fun of(vararg pairs: Pair<String, JsonElement>) = WfItem(JsonObject(mapOf(*pairs)))
    }
}

object WfItems {
    /** Saída (texto) de um nó → um item. Objeto JSON vira o item; qualquer outra coisa vira `{text: ...}`. */
    fun fromOutput(output: String): WfItem {
        val el = if (WfFormat.isJson(output)) runCatching { Json.parseToJsonElement(output) }.getOrNull() else null
        return when (el) {
            is JsonObject -> WfItem(el, output)
            is JsonArray -> WfItem(JsonObject(mapOf("items" to el, WfItem.TEXT to JsonPrimitive(output))), output)
            else -> WfItem(JsonObject(mapOf(WfItem.TEXT to JsonPrimitive(output))), output)
        }
    }

    /** Lista de itens → texto (formato antigo): 1 item = o texto dele; vários = array JSON. */
    fun toText(items: List<WfItem>): String = when (items.size) {
        0 -> ""
        1 -> items[0].text()
        else -> JsonArray(items.map { it.json }).toString()
    }

    /** Lê de volta o que [toText] gravou num passo do histórico ([count] = quantos itens saíram). */
    fun fromStep(output: String, count: Int): List<WfItem> {
        if (count == 0) return emptyList()
        if (count > 1) runCatching { Json.parseToJsonElement(output) as JsonArray }.getOrNull()?.let { arr ->
            return arr.map { (it as? JsonObject)?.let { o -> WfItem(o) } ?: fromOutput(it.toString()) }
        }
        return listOf(fromOutput(output))
    }

    /** Dados fixados (pin): `[{...}, ...]`, `{...}` ou `[{json: {...}}]`. */
    fun parsePinned(text: String): List<WfItem>? {
        if (text.isBlank()) return null
        val el = runCatching { Json.parseToJsonElement(text) }.getOrNull() ?: return null
        val list = if (el is JsonArray) el.toList() else listOf(el)
        return list.map { e ->
            val o = e as? JsonObject ?: return@map WfItem(JsonObject(mapOf(WfItem.TEXT to e)))
            val inner = o["json"] as? JsonObject
            if (inner != null && o.size == 1) WfItem(inner) else WfItem(o)
        }
    }

    fun pinText(items: List<WfItem>): String = JsonArray(items.map { it.json }).toString()
}
