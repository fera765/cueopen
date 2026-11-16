package dev.cueopen.app.web

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Extração enxuta de páginas: título + texto principal (sem menus, rodapés, scripts), com títulos e listas marcados.
 * Usada pelo web_fetch e pelo web_search — bem mais barata em tokens que um snapshot completo do browser.
 */
object WebExtract {
    private const val BLOCKS = "h1,h2,h3,h4,p,li,pre,blockquote,td,th,dt,dd,figcaption"

    fun mainText(html: String, url: String, maxChars: Int = 12_000): String {
        val doc = Jsoup.parse(html, url)
        doc.select("script,style,noscript,svg,iframe,form,nav,footer,header,aside,button,[aria-hidden=true],[role=navigation],[role=banner],[role=contentinfo],.cookie,.cookies,#cookie-banner").remove()
        val root = pickRoot(doc)
        val chosen = root.select(BLOCKS)
        val set = chosen.toHashSet()
        val sb = StringBuilder()
        for (el in chosen) {
            if (el.parents().any { it in set }) continue  // o pai já entrou (ex.: <li><p>)
            val text = el.text().trim()
            if (text.length < 2) continue
            val line = when (el.tagName()) {
                "h1" -> "# $text"; "h2" -> "## $text"; "h3", "h4" -> "### $text"
                "li", "dd" -> "- $text"; "pre" -> "```\n${el.wholeText().trim()}\n```"; "blockquote" -> "> $text"
                else -> text
            }
            sb.append(line).append('\n')
            if (sb.length > maxChars) break
        }
        val body = sb.toString().ifBlank { root.text() }.let { collapse(it) }
        val title = doc.title().trim()
        val out = (if (title.isNotBlank()) "Título: $title\n" else "") + "URL: $url\n\n" + body
        return if (out.length > maxChars) out.take(maxChars) + "\n[… cortado em $maxChars caracteres; peça max_chars maior se precisar do resto]" else out
    }

    private fun pickRoot(doc: Document): Element =
        doc.selectFirst("article") ?: doc.selectFirst("main") ?: doc.selectFirst("[role=main]") ?: doc.selectFirst("#content, .content, #main, .post, .entry-content")
            ?: doc.body() ?: doc

    private fun collapse(s: String) = s.lines().map { it.trimEnd() }.fold(StringBuilder()) { acc, l ->
        if (l.isBlank() && acc.endsWith("\n\n")) acc else acc.append(l).append('\n')
    }.toString().trim()

    data class SearchHit(val title: String, val url: String, val snippet: String)

    /** Resultados da página HTML do DuckDuckGo (links de redirecionamento são abertos para a URL real). */
    fun parseDuckDuckGo(html: String): List<SearchHit> {
        val doc = Jsoup.parse(html, "https://html.duckduckgo.com/")
        return doc.select(".result").mapNotNull { r ->
            val a = r.selectFirst("a.result__a") ?: return@mapNotNull null
            val href = a.attr("href").let { realUrl(it) }
            if (href.isBlank() || r.hasClass("result--ad")) return@mapNotNull null
            SearchHit(a.text().trim(), href, r.selectFirst(".result__snippet")?.text()?.trim().orEmpty())
        }.distinctBy { it.url }
    }

    fun realUrl(href: String): String {
        val h = if (href.startsWith("//")) "https:$href" else href
        val u = Regex("[?&]uddg=([^&]+)").find(h)?.groupValues?.get(1) ?: return h
        return java.net.URLDecoder.decode(u, "UTF-8")
    }
}
