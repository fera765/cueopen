package dev.cueopen.app.web

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * OpenBot 0.69: vídeo do YouTube com legenda → a transcrição completa vem da legenda (sem baixar o vídeo).
 * Funções puras (testáveis); o download fica no WebTools.
 */
object YouTubeTranscript {
    private val ID = Regex("""(?:youtube\.com/(?:watch\?(?:[^#\s]*&)?v=|shorts/|embed/|live/)|youtu\.be/)([A-Za-z0-9_-]{11})""")

    data class Track(val baseUrl: String, val lang: String, val name: String, val auto: Boolean)

    fun videoId(url: String): String? = ID.find(url)?.groupValues?.get(1)

    /** Lê "captionTracks" do ytInitialPlayerResponse da página do vídeo. */
    fun tracks(watchHtml: String): List<Track> {
        val key = "\"captionTracks\":"
        val at = watchHtml.indexOf(key).takeIf { it >= 0 } ?: return emptyList()
        val start = watchHtml.indexOf('[', at)
        if (start < 0) return emptyList()
        var depth = 0; var i = start; var inStr = false; var esc = false
        while (i < watchHtml.length) {
            val c = watchHtml[i]
            if (inStr) { if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false }
            else when (c) { '"' -> inStr = true; '[' -> depth++; ']' -> { depth--; if (depth == 0) break } }
            i++
        }
        return runCatching {
            // kotlinx (e não org.json): roda igual no app e nos testes da JVM.
            val arr = Json.parseToJsonElement(watchHtml.substring(start, i + 1)) as JsonArray
            fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
            arr.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val nm = o["name"] as? JsonObject
                val name = nm?.str("simpleText")?.ifBlank { ((nm["runs"] as? JsonArray)?.firstOrNull() as? JsonObject)?.str("text").orEmpty() }.orEmpty()
                Track(o.str("baseUrl"), o.str("languageCode"), name, o.str("kind") == "asr").takeIf { it.baseUrl.isNotBlank() }
            }
        }.getOrDefault(emptyList())
    }

    /** Prefere legenda manual no idioma pedido, depois automática, depois inglês, depois a primeira. */
    fun pick(tracks: List<Track>, preferred: List<String> = listOf("pt", "en")): Track? {
        for (lang in preferred) {
            tracks.firstOrNull { !it.auto && it.lang.startsWith(lang) }?.let { return it }
            tracks.firstOrNull { it.auto && it.lang.startsWith(lang) }?.let { return it }
        }
        return tracks.firstOrNull { !it.auto } ?: tracks.firstOrNull()
    }

    private val TEXT = Regex("""<text start="([\d.]+)"[^>]*>(.*?)</text>""", RegexOption.DOT_MATCHES_ALL)
    private val P = Regex("""<p t="(\d+)"[^>]*>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)

    fun unescape(s: String): String {
        var t = s
        repeat(2) {  // a legenda costuma vir com entidades duplas (&amp;#39;)
            t = t.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
                .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value }
        }
        return t.replace(Regex("<[^>]+>"), "").replace(Regex("\\s+"), " ").trim()
    }

    /** XML do timedtext (formato 1 `<text start>` ou formato 3 `<p t=ms>`) → linhas "[mm:ss] texto". */
    fun parse(xml: String): List<Pair<Double, String>> {
        val a = TEXT.findAll(xml).map { it.groupValues[1].toDouble() to unescape(it.groupValues[2]) }.toList()
        val b = if (a.isEmpty()) P.findAll(xml).map { it.groupValues[1].toDouble() / 1000.0 to unescape(it.groupValues[2]) }.toList() else a
        return b.filter { it.second.isNotBlank() }
    }

    fun format(lines: List<Pair<Double, String>>, maxChars: Int): String {
        val sb = StringBuilder()
        var lastMark = -60.0
        for ((t, txt) in lines) {
            if (t - lastMark >= 30) { sb.append("\n[").append(clock(t)).append("] "); lastMark = t } else sb.append(' ')
            sb.append(txt)
            if (sb.length > maxChars) { sb.append("\n…[transcrição cortada em $maxChars caracteres; peça max_chars maior se precisar]"); break }
        }
        return sb.toString().trim()
    }

    fun clock(sec: Double): String { val s = sec.toInt(); return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60) }
}
