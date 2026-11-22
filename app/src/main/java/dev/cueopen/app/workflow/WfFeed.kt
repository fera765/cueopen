package dev.cueopen.app.workflow

import kotlinx.serialization.json.*

/**
 * Feeds RSS/Atom em respostas HTTP: viram uma lista enxuta (título, link, data, fonte, imagem),
 * em vez de 100–900 KB de XML que estouram o nó de IA. Sem dependências de Android.
 */
object WfFeed {
    data class Item(val title: String, val link: String, val date: String = "", val source: String = "", val image: String = "", val summary: String = "")

    fun isFeed(text: String): Boolean {
        val head = text.trimStart().take(800)
        return Regex("(?i)<rss[\\s>]|<feed[\\s>][^>]*xmlns=\"http://www\\.w3\\.org/2005/Atom|<rdf:RDF").containsMatchIn(head)
    }

    private fun unescape(s: String): String = s.replace(Regex("(?s)<!\\[CDATA\\[(.*?)\\]\\]>"), "$1")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        .replace("&nbsp;", " ").replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: "" }
        .replace("&amp;", "&")
        .replace(Regex("<[^>]+>"), " ")
        .replace(Regex("\\s+"), " ").trim()

    private fun tag(block: String, vararg names: String): String {
        for (n in names) {
            val m = Regex("(?is)<$n(?:\\s[^>]*)?>(.*?)</$n>").find(block) ?: continue
            val v = unescape(m.groupValues[1]); if (v.isNotBlank()) return v
        }
        return ""
    }
    private fun attr(block: String, tagName: String, attr: String): String =
        Regex("(?is)<$tagName\\b[^>]*\\b$attr=\"([^\"]+)\"").find(block)?.groupValues?.get(1)?.replace("&amp;", "&").orEmpty()

    fun parse(xml: String, max: Int = 30): List<Item> {
        val blocks = Regex("(?is)<item\\b[^>]*>(.*?)</item>|<entry\\b[^>]*>(.*?)</entry>").findAll(xml).take(max)
        return blocks.mapNotNull { m ->
            val b = m.groupValues[1].ifEmpty { m.groupValues[2] }
            var title = tag(b, "title")
            val link = tag(b, "link").takeIf { it.startsWith("http") } ?: attr(b, "link", "href")
            val source = tag(b, "source")
            if (source.isNotBlank() && title.endsWith(" - $source")) title = title.removeSuffix(" - $source").trim()
            val image = attr(b, "media:content", "url").ifBlank { attr(b, "media:thumbnail", "url") }.ifBlank { attr(b, "enclosure", "url").takeIf { Regex("(?i)\\.(jpe?g|png|webp)").containsMatchIn(it) }.orEmpty() }
            val summary = tag(b, "description", "summary").take(280)
            if (title.isBlank() || link.isBlank()) null
            else Item(title.take(200), link, tag(b, "pubDate", "published", "updated", "dc:date"), source, image,
                summary.takeUnless { it.equals(title, true) || it.startsWith(title) }.orEmpty())
        }.toList()
    }

    fun toJson(items: List<Item>): JsonArray = buildJsonArray {
        items.forEach { i -> add(buildJsonObject {
            put("title", i.title); put("link", i.link)
            if (i.date.isNotBlank()) put("date", i.date); if (i.source.isNotBlank()) put("source", i.source)
            if (i.image.isNotBlank()) put("image", i.image); if (i.summary.isNotBlank()) put("summary", i.summary)
        }) }
    }

    /** Texto compacto para IA: uma notícia por linha. */
    fun toText(items: List<Item>): String = items.mapIndexed { n, i ->
        "${n + 1}. ${i.title}" + (if (i.source.isNotBlank()) " — ${i.source}" else "") + (if (i.date.isNotBlank()) " (${i.date})" else "") +
            "\n   ${i.link}" + (if (i.summary.isNotBlank()) "\n   ${i.summary}" else "")
    }.joinToString("\n")
}
