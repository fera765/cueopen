package dev.cueopen.app.sessionapp

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections

/**
 * Teste de renderização antes de publicar: carrega o app num WebView oculto, com o retrato REAL da seção,
 * e devolve os erros de JavaScript (sintaxe ou execução inicial) ou "tela vazia". null = passou.
 * Mesmo isolamento do app em uso (perfil próprio, sem navegação, ponte só de leitura).
 */
object SessionAppTester {
    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    suspend fun test(ctx: Context, html: String, snapshot: String): String? = withContext(Dispatchers.Main) {
        val errors = Collections.synchronizedList(mutableListOf<String>())
        val loaded = CompletableDeferred<Unit>()
        val view = WebView(ctx.applicationContext)
        try {
            val isolated = runCatching {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) { WebViewCompat.setProfile(view, "session_apps"); true } else false
            }.getOrDefault(false)
            view.settings.javaScriptEnabled = true
            view.settings.domStorageEnabled = true
            view.settings.allowFileAccess = false
            view.settings.allowContentAccess = false
            view.addJavascriptInterface(object {
                @JavascriptInterface fun getState(): String = snapshot
                @JavascriptInterface fun send(a: String?, p: String?) {}
                @JavascriptInterface fun saveState(j: String?): Boolean = true
                @JavascriptInterface fun loadState(): String = "null"
                @JavascriptInterface fun reportError(m: String?) { errors.add(m.orEmpty().take(200)) }
            }, "__sar")
            view.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                    if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) errors.add((m.message().take(200) + " (linha ${m.lineNumber()})"))
                    return true
                }
            }
            view.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) = true
                override fun onPageFinished(v: WebView, url: String) { loaded.complete(Unit) }
                override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? {
                    val u = r.url.toString()
                    val ok = u.startsWith("https://session-app.invalid/") || u.startsWith("data:") || u.startsWith("blob:") || (isolated && u.startsWith("https://") && r.method == "GET")
                    return if (ok) null else WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)))
                }
            }
            view.layout(0, 0, 1080, 1920)
            view.loadDataWithBaseURL("https://session-app.invalid/test/", SessionAppValidator.withBootstrap(html), "text/html", "utf-8", null)
            withTimeoutOrNull(8_000) { loaded.await() }
            delay(1_200)  // scripts iniciais e o primeiro onUpdate
            val content = CompletableDeferred<String>()
            view.evaluateJavascript("(function(){try{return String((document.body?document.body.innerText.trim().length:0)+document.querySelectorAll('canvas,svg,img,video').length);}catch(e){return '0';}})()") { content.complete(it ?: "0") }
            val size = withTimeoutOrNull(3_000) { content.await() }?.trim('"')?.toIntOrNull()
            when {
                errors.isNotEmpty() -> errors.distinct().take(4).joinToString(" | ")
                size == 0 -> "a tela ficou vazia depois de carregar (nada foi renderizado)"
                else -> null
            }
        } finally { runCatching { view.stopLoading(); view.destroy() } }
    }
}
