package dev.cueopen.app.web

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import dev.cueopen.app.web.SearchApi.Engine
import dev.cueopen.app.web.WebExtract.SearchHit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Buscador como "API": abre a página de resultados numa WebView invisível (sem tela, funciona com o app em
 * segundo plano ou fechado), espera os resultados aparecerem e lê tudo com um script — título, link e trecho.
 * Imagens, fontes e mídia são bloqueadas para carregar rápido. Uma WebView por buscador, reaproveitada entre buscas.
 */
object WebViewSearch {
    private val views = HashMap<Engine, WebView>()
    private val locks = Engine.values().associateWith { kotlinx.coroutines.sync.Mutex() }

    @SuppressLint("SetJavaScriptEnabled")
    private fun view(ctx: Context, e: Engine): WebView = views.getOrPut(e) {
        WebView(ctx.applicationContext).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = false
            settings.blockNetworkImage = true
            settings.mediaPlaybackRequiresUserGesture = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"
            // Sem layout na tela: mede um tamanho fixo para o site renderizar a lista normalmente.
            layout(0, 0, 1080, 2400)
        }
    }

    /** Resultados de um buscador; vazio = falhou, bloqueou (captcha) ou estourou o tempo. */
    suspend fun search(ctx: Context, e: Engine, query: String, timeoutMs: Long = 9_000): List<SearchHit> =
        locks.getValue(e).withLock { searchLocked(ctx, e, query, timeoutMs) }

    private suspend fun searchLocked(ctx: Context, e: Engine, query: String, timeoutMs: Long): List<SearchHit> = withContext(Dispatchers.Main) {
        val wv = runCatching { view(ctx, e) }.getOrNull() ?: return@withContext emptyList()
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Unit> { cont ->
                wv.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView?, req: WebResourceRequest?): android.webkit.WebResourceResponse? {
                        val u = req?.url?.toString().orEmpty().lowercase()
                        // Corta peso: imagens, fontes, vídeo e rastreadores não são necessários para ler a lista.
                        return if (Regex("""\.(png|jpe?g|gif|webp|svg|woff2?|ttf|mp4|webm)(\?|$)|/gen_204|doubleclick|googletagmanager|/log\?""").containsMatchIn(u))
                            android.webkit.WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0))) else null
                    }
                    override fun onPageFinished(view: WebView?, url: String?) { if (cont.isActive) cont.resume(Unit) }
                }
                cont.invokeOnCancellation { runCatching { wv.stopLoading() } }
                wv.loadUrl(SearchApi.url(e, query))
            }
            // A lista pode chegar depois do onPageFinished (JS do buscador): tenta algumas vezes.
            var page = SearchApi.Page(emptyList(), false)
            repeat(6) { i ->
                page = SearchApi.parse(eval(wv, SearchApi.EXTRACT_JS), e)
                if (page.hits.size >= 3 || page.blocked) return@withTimeoutOrNull page.hits
                delay(if (i == 0) 150 else 350)
            }
            page.hits
        } ?: emptyList()
    }

    private suspend fun eval(wv: WebView, js: String): String? = suspendCancellableCoroutine { c ->
        runCatching { wv.evaluateJavascript(js) { r -> if (c.isActive) c.resume(r) } }.onFailure { if (c.isActive) c.resume(null) }
    }
}
