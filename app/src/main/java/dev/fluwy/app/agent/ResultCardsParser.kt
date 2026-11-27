package dev.fluwy.app.agent

import dev.fluwy.app.data.CardEntry
import dev.fluwy.app.data.ChatItem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Valida e limpa os cards do show_cards: só dados estruturados, links/imagens http(s), nada de HTML. */
object ResultCardsParser {
    val KINDS = setOf("product", "news", "website", "search", "service")
    private const val MAX = 12

    private fun clean(s: String, max: Int) = s.replace(Regex("<[^>]+>"), "").replace(Regex("\\s+"), " ").trim().take(max)
    private fun link(s: String) = s.trim().takeIf { it.startsWith("https://") || it.startsWith("http://") }?.take(800).orEmpty()

    fun parse(kindRaw: String, title: String, itemsEl: JsonElement?): Result<ChatItem.Cards> = runCatching {
        val kind = kindRaw.trim().lowercase().takeIf { it in KINDS } ?: "search"
        val arr = when (itemsEl) {
            is JsonArray -> itemsEl
            is JsonPrimitive -> itemsEl.contentOrNull?.let { runCatching { Json.parseToJsonElement(it) as? JsonArray }.getOrNull() }
            else -> null
        } ?: error("items precisa ser uma lista de objetos com pelo menos 'title'.")
        fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val items = arr.mapNotNull { it as? JsonObject }.mapNotNull { o ->
            val t = clean(o.s("title"), 160)
            if (t.isBlank()) null else CardEntry(
                title = t, subtitle = clean(o.s("subtitle"), 280), image = link(o.s("image")), price = clean(o.s("price"), 40),
                oldPrice = clean(o.s("oldPrice"), 40), source = clean(o.s("source"), 60), rating = clean(o.s("rating"), 20),
                badge = clean(o.s("badge"), 30), url = link(o.s("url")), date = clean(o.s("date"), 40), ref = clean(o.s("ref"), 80),
            )
        }.take(MAX)
        if (items.isEmpty()) error("nenhum item válido (cada item precisa de 'title').")
        ChatItem.Cards(kind = kind, title = clean(title, 120), items = items)
    }
}
