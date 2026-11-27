package dev.fluwy.app.web

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Metadados confiáveis de uma página (puro, testável): título, descrição, FOTO principal, site, tipo (produto/notícia),
 * preço e data. Ordem de confiança: JSON-LD (schema.org) → Open Graph/Twitter → <link image_src> → maior <img> do conteúdo.
 */
object PageMeta {
    data class Meta(
        val title: String = "", val description: String = "", val image: String = "", val site: String = "",
        val kind: String = "", val price: String = "", val currency: String = "", val published: String = "", val url: String = "",
    ) {
        fun block(): String = buildString {
            if (image.isNotBlank()) append("Imagem: ").append(image).append('\n')
            if (kind.isNotBlank()) append("Tipo: ").append(kind).append('\n')
            if (price.isNotBlank()) append("Preço: ").append(listOf(currency, price).filter { it.isNotBlank() }.joinToString(" ")).append('\n')
            if (published.isNotBlank()) append("Publicado: ").append(published).append('\n')
            if (site.isNotBlank()) append("Site: ").append(site).append('\n')
            if (description.isNotBlank()) append("Resumo: ").append(description.take(300)).append('\n')
        }
    }

    private val BAD_IMG = Regex("(sprite|pixel|spacer|blank|1x1|logo|favicon|icon|avatar|placeholder|loading|transparent|badge|tracking|/ads?/)", RegexOption.IGNORE_CASE)

    /** Foto útil para card: http(s), não SVG/GIF minúsculo, não logo/ícone/pixel. */
    fun isGoodImage(u: String?): Boolean {
        val s = u?.trim().orEmpty()
        if (!Regex("^https?://[^\\s\"'<>]+$").matches(s) || s.length > 2_000) return false
        val path = s.substringBefore('?').lowercase()
        if (path.endsWith(".svg") || path.endsWith(".ico")) return false
        return !BAD_IMG.containsMatchIn(path.substringAfterLast('/')) && !path.contains("/pixel") && !path.contains("/track")
    }

    fun extract(html: String, url: String): Meta {
        val doc = Jsoup.parse(html, url)
        fun meta(vararg keys: String) = keys.firstNotNullOfOrNull { k ->
            doc.selectFirst("meta[property=$k], meta[name=$k], meta[itemprop=$k]")?.attr("content")?.trim()?.takeIf { it.isNotBlank() }
        }.orEmpty()
        val ld = jsonLd(doc)
        val ogImg = listOf(meta("og:image:secure_url"), meta("og:image"), meta("og:image:url"), meta("twitter:image"), meta("twitter:image:src"), meta("image"),
            doc.selectFirst("link[rel=image_src]")?.absUrl("href").orEmpty()).map { abs(url, it) }
        val image = (listOf(ld.image) + ogImg + listOf(bestImg(doc))).firstOrNull { isGoodImage(it) }.orEmpty()
        val ogType = meta("og:type").lowercase()
        val kind = ld.kind.ifBlank {
            when {
                ogType.contains("product") || meta("product:price:amount").isNotBlank() -> "produto"
                ogType == "article" || meta("article:published_time").isNotBlank() -> "notícia"
                else -> ""
            }
        }
        return Meta(
            title = ld.title.ifBlank { meta("og:title", "twitter:title") }.ifBlank { doc.title() }.trim().take(200),
            description = ld.description.ifBlank { meta("og:description", "twitter:description", "description") }.trim().take(500),
            image = image, site = meta("og:site_name").ifBlank { runCatching { java.net.URI(url).host.removePrefix("www.") }.getOrDefault("") },
            kind = kind, price = ld.price.ifBlank { meta("product:price:amount", "og:price:amount") },
            currency = ld.currency.ifBlank { meta("product:price:currency", "og:price:currency") },
            published = ld.published.ifBlank { meta("article:published_time", "datePublished") }.take(25), url = url,
        )
    }

    private fun abs(base: String, u: String): String = if (u.isBlank()) "" else runCatching {
        val s = if (u.startsWith("//")) "https:$u" else u
        java.net.URI(base).resolve(s.replace(" ", "%20")).toString()
    }.getOrDefault("")

    /** Maior imagem do conteúdo (pelos atributos width/height), ignorando logos e ícones. */
    private fun bestImg(doc: Document): String = doc.select("article img, main img, img").asSequence().mapNotNull { el ->
        val src = listOf("data-src", "data-lazy-src", "data-original", "src").map { el.absUrl(it) }.firstOrNull { it.startsWith("http") } ?: return@mapNotNull null
        val w = el.attr("width").filter { it.isDigit() }.toIntOrNull() ?: 0
        val h = el.attr("height").filter { it.isDigit() }.toIntOrNull() ?: 0
        if ((w in 1..119) || (h in 1..79)) return@mapNotNull null
        src to (if (w > 0 && h > 0) w * h else 40_000)
    }.filter { isGoodImage(it.first) }.take(30).maxByOrNull { it.second }?.first.orEmpty()

    private data class Ld(val title: String = "", val description: String = "", val image: String = "", val kind: String = "",
                          val price: String = "", val currency: String = "", val published: String = "")

    private fun jsonLd(doc: Document): Ld {
        val nodes = doc.select("script[type=application/ld+json]").flatMap { s ->
            val el = runCatching { Json.parseToJsonElement(s.data().trim()) }.getOrNull() ?: return@flatMap emptyList()
            flatten(el)
        }
        fun type(o: JsonObject) = when (val t = o["@type"]) { is JsonArray -> t.joinToString(" ") { (it as? JsonPrimitive)?.content.orEmpty() }; is JsonPrimitive -> t.content; else -> "" }
        val main = nodes.firstOrNull { Regex("Product", RegexOption.IGNORE_CASE).containsMatchIn(type(it)) }
            ?: nodes.firstOrNull { Regex("NewsArticle|Article|BlogPosting|Report", RegexOption.IGNORE_CASE).containsMatchIn(type(it)) }
            ?: return Ld()
        val isProduct = type(main).contains("Product", true)
        val offer = when (val of = main["offers"]) { is JsonArray -> of.firstOrNull() as? JsonObject; is JsonObject -> of; else -> null }
        val inner = (offer?.get("offers") as? JsonArray)?.firstOrNull() as? JsonObject
        fun str(o: JsonObject?, k: String) = (o?.get(k) as? JsonPrimitive)?.content.orEmpty()
        return Ld(
            title = str(main, "name").ifBlank { str(main, "headline") },
            description = str(main, "description"),
            image = imageOf(main["image"]),
            kind = if (isProduct) "produto" else "notícia",
            price = str(offer, "price").ifBlank { str(offer, "lowPrice") }.ifBlank { str(inner, "price") },
            currency = str(offer, "priceCurrency").ifBlank { str(inner, "priceCurrency") },
            published = str(main, "datePublished"),
        )
    }

    private fun imageOf(e: JsonElement?): String = when (e) {
        is JsonPrimitive -> e.content
        is JsonArray -> e.firstNotNullOfOrNull { imageOf(it).takeIf { s -> s.isNotBlank() } }.orEmpty()
        is JsonObject -> (e["url"] as? JsonPrimitive)?.content ?: (e["contentUrl"] as? JsonPrimitive)?.content.orEmpty()
        else -> ""
    }

    private fun flatten(e: JsonElement): List<JsonObject> = when (e) {
        is JsonArray -> e.flatMap { flatten(it) }
        is JsonObject -> listOf(e) + ((e["@graph"] as? JsonArray)?.flatMap { flatten(it) } ?: emptyList())
        else -> emptyList()
    }
}
