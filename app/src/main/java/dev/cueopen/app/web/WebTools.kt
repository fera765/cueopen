package dev.cueopen.app.web

import dev.cueopen.app.CueApp
import dev.cueopen.app.proxy.ProxyScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Busca e leitura de páginas por HTTP direto (sem WebView): rápido e barato. Para sites com login/JS, use o browser. */
object WebTools {
    private val base = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).followRedirects(true).build()
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"
    private const val MAX_BYTES = 3L * 1024 * 1024

    private fun http(): OkHttpClient = runCatching { CueApp.instance.proxyHttpClient(ProxyScope.BROWSER, base) }.getOrDefault(base)

    private fun get(url: String): Pair<Int, String> {
        val req = Request.Builder().url(url).header("User-Agent", UA).header("Accept-Language", "pt-BR,pt;q=0.9,en;q=0.8").build()
        http().newCall(req).execute().use { r ->
            val type = r.header("Content-Type").orEmpty()
            if (type.isNotBlank() && !type.contains("html") && !type.contains("text") && !type.contains("json") && !type.contains("xml"))
                return r.code to "[conteúdo não textual: $type — baixe com shell (curl) se precisar do arquivo]"
            val src = r.body?.source() ?: return r.code to ""
            src.request(MAX_BYTES)
            return r.code to src.buffer.readUtf8(minOf(src.buffer.size, MAX_BYTES))
        }
    }

    suspend fun fetch(url: String, maxChars: Int): String = withContext(Dispatchers.IO) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return@withContext "ERRO: informe uma URL http(s) completa."
        YouTubeTranscript.videoId(url)?.let { id -> youtube(id, maxChars)?.let { return@withContext it } }
        runCatching {
            val (code, body) = get(url)
            when {
                code == 403 || code == 429 || code == 503 -> "ERRO: o site bloqueou a leitura direta (HTTP $code). Abra com browser_navigate."
                code >= 400 -> "ERRO: HTTP $code em $url. Não repita esta URL; procure outro endereço." +
                    (if (code == 404) " Não adivinhe caminhos (/contato, /sobre…): abra a página inicial do site e siga os links que ela mostra." else "")
                body.trimStart().startsWith("{") || body.trimStart().startsWith("[") -> body.take(maxChars)
                body.startsWith("[conteúdo") -> body
                else -> WebExtract.mainText(body, url, maxChars).let { t ->
                    if (t.length < 300) "$t\n\n[Pouco texto extraído: a página provavelmente depende de JavaScript ou login. Use browser_navigate + browser_read.]" else t
                }
            }
        }.getOrElse { "ERRO: falha ao abrir $url (${it.javaClass.simpleName}). Tente browser_navigate." }
    }

    /** Transcrição pela legenda do vídeo; null = sem legenda acessível (segue a leitura normal da página). */
    private fun youtube(id: String, maxChars: Int): String? = runCatching {
        val (code, html) = get("https://www.youtube.com/watch?v=$id&hl=pt-BR")
        if (code != 200) return null
        val title = Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)
            ?.let { YouTubeTranscript.unescape(it) }?.removeSuffix(" - YouTube").orEmpty()
        val track = YouTubeTranscript.pick(YouTubeTranscript.tracks(html)) ?: return "Vídeo: $title (https://youtu.be/$id)\n" +
            "Este vídeo não tem legenda disponível, então não há transcrição. Não tente baixar o vídeo; use a descrição (browser_navigate + browser_read) ou outra fonte."
        val (c2, xml) = get(track.baseUrl)
        val lines = if (c2 == 200) YouTubeTranscript.parse(xml) else emptyList()
        if (lines.isEmpty()) return null
        val dur = YouTubeTranscript.clock(lines.last().first)
        "Vídeo: $title (https://youtu.be/$id)\nTranscrição pela legenda ${if (track.auto) "automática" else "do autor"} (${track.lang}${track.name.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""}), duração ~$dur:\n\n" +
            YouTubeTranscript.format(lines, maxChars)
    }.getOrNull()

    suspend fun search(query: String, max: Int): String = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext "ERRO: informe query."
        runCatching {
            val (code, body) = get("https://html.duckduckgo.com/html/?q=" + java.net.URLEncoder.encode(query, "UTF-8") + "&kl=br-pt")
            val hits = if (code == 200) WebExtract.parseDuckDuckGo(body).take(max.coerceIn(1, 15)) else emptyList()
            if (hits.isEmpty()) "Nenhum resultado pela busca direta (HTTP $code). Use browser_navigate com os termos (abre o Google no browser)."
            else "Resultados para \"$query\" (abra os relevantes com web_fetch):\n" + hits.mapIndexed { i, h ->
                "${i + 1}. ${h.title}\n   ${h.url}" + (if (h.snippet.isNotBlank()) "\n   ${h.snippet.take(240)}" else "")
            }.joinToString("\n")
        }.getOrElse { "ERRO: busca indisponível (${it.javaClass.simpleName}). Use browser_navigate com os termos." }
    }
}
