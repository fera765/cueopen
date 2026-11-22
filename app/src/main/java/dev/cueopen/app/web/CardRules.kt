package dev.cueopen.app.web

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Regras puras dos cards com foto (testáveis). */
object CardRules {
    private val IMG_KEYS = listOf("image", "img", "thumbnail", "photo", "foto", "imagem", "picture")
    private val CARD_LISTS = setOf("items", "cards", "products", "news", "results", "produtos", "noticias")

    fun link(o: JsonObject): String? = listOf("url", "link", "href").firstNotNullOfOrNull { (o[it] as? JsonPrimitive)?.content?.takeIf { s -> s.startsWith("http") } }
    fun image(o: JsonObject): String? = IMG_KEYS.firstNotNullOfOrNull { (o[it] as? JsonPrimitive)?.content?.takeIf { s -> PageMeta.isGoodImage(s) } }
    /** É um card (tem título e link) sem foto válida. */
    fun needsImage(o: JsonObject) = link(o) != null && image(o) == null && (o["title"] != null || o["name"] != null || o["titulo"] != null)

    /** Coloca as fotos encontradas e tira dos arrays de cards os itens que seguem sem foto. */
    fun apply(e: JsonElement, imgs: Map<String, String>): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.mapValues { (k, v) ->
            val fixed = apply(v, imgs)
            if (k in CARD_LISTS && fixed is JsonArray) JsonArray(fixed.filter { it !is JsonObject || link(it) == null || image(it) != null }) else fixed
        }.let { m ->
            if (needsImage(e)) link(e)?.let { imgs[it] }?.let { m + ("image" to JsonPrimitive(it)) } ?: m else m
        })
        is JsonArray -> JsonArray(e.map { apply(it, imgs) })
        else -> e
    }
}
