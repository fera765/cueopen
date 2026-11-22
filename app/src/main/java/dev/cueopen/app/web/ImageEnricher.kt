package dev.cueopen.app.web

import dev.cueopen.app.CueApp
import dev.cueopen.app.proxy.ProxyScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Foto de cada resultado/card: lê só o começo da página (onde ficam og:image e JSON-LD), em paralelo, com teto de tempo
 * e cache. Segurança: só http(s), sem cookies, sem seguir para IP local, corpo limitado a 400 KB.
 */
object ImageEnricher {
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"
    private val base = OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).followRedirects(true).build()
    private fun http(): OkHttpClient = runCatching { CueApp.instance.proxyHttpClient(ProxyScope.BROWSER, base) }.getOrDefault(base)
    private val gate = Semaphore(6)
    private val cache = object : LinkedHashMap<String, PageMeta.Meta>(256, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, PageMeta.Meta>?) = size > 300
    }

    private fun isPublic(url: String): Boolean = runCatching {
        val h = java.net.URI(url).host?.lowercase() ?: return false
        !(h == "localhost" || h.endsWith(".local") || Regex("^(10|127|0)\\.|^192\\.168\\.|^172\\.(1[6-9]|2\\d|3[01])\\.|^169\\.254\\.|^\\[").containsMatchIn(h))
    }.getOrDefault(false)

    suspend fun meta(url: String): PageMeta.Meta? {
        if (!url.startsWith("http") || !isPublic(url)) return null
        synchronized(cache) { cache[url] }?.let { return it }
        val m = gate.withPermit {
            withContext(Dispatchers.IO) {
                runCatching {
                    val req = Request.Builder().url(url).header("User-Agent", UA).header("Accept", "text/html,application/xhtml+xml").header("Accept-Language", "pt-BR,pt;q=0.9").build()
                    http().newCall(req).execute().use { r ->
                        if (!r.isSuccessful || !r.header("Content-Type").orEmpty().contains("html")) return@use null
                        val src = r.body?.source() ?: return@use null
                        src.request(400_000L)
                        PageMeta.extract(src.buffer.readUtf8(minOf(src.buffer.size, 400_000L)), r.request.url.toString())
                    }
                }.getOrNull()
            }
        } ?: return null
        synchronized(cache) { cache[url] = m }
        return m
    }

    suspend fun imageFor(url: String): String? = meta(url)?.image?.takeIf { PageMeta.isGoodImage(it) }

    /** Completa a foto dos resultados que vieram sem (teto total [budgetMs]). */
    suspend fun fill(hits: List<WebExtract.SearchHit>, budgetMs: Long = 4_500): List<WebExtract.SearchHit> = coroutineScope {
        val found = withTimeoutOrNull(budgetMs) {
            hits.map { h -> async { if (PageMeta.isGoodImage(h.image)) h.image else runCatching { imageFor(h.url) }.getOrNull().orEmpty() } }.awaitAll()
        }
        if (found == null) hits else hits.mapIndexed { i, h -> if (found[i].isNotBlank()) h.copy(image = found[i]) else h }
    }

    /**
     * Cards de um JSON (UI kit/cueui): todo objeto com url/link e sem foto válida ganha a og:image da página;
     * os que continuarem sem foto saem da lista (nenhum card sem imagem).
     */
    suspend fun enrichCardsJson(json: String, budgetMs: Long = 6_000): String {
        val root = runCatching { Json.parseToJsonElement(json) }.getOrNull() ?: return json
        val urls = mutableSetOf<String>()
        fun collect(e: JsonElement) { when (e) {
            is JsonObject -> { if (CardRules.needsImage(e)) CardRules.link(e)?.let { urls += it }; e.values.forEach { collect(it) } }
            is JsonArray -> e.forEach { collect(it) }
            else -> {}
        } }
        collect(root)
        if (urls.isEmpty()) return json
        val imgs = withTimeoutOrNull(budgetMs) { coroutineScope { urls.take(16).map { u -> async { u to runCatching { imageFor(u) }.getOrNull() } }.awaitAll() } }
            ?.mapNotNull { (u, i) -> i?.let { u to it } }?.toMap() ?: emptyMap()
        return CardRules.apply(root, imgs).toString()
    }
}

