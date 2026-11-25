package dev.cueopen.app.browser

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.cueopen.app.data.WebApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.coroutines.resume

/**
 * Motor do Web API Scraper: um WebView SEPARADO dos browsers das sessões (o agente pode estar navegando ao mesmo tempo),
 * mas no mesmo perfil — cookies, logins e armazenamento iguais — e atrás do mesmo proxy (o ProxyController do app vale
 * para todo WebView do processo). Sem tela: mede um tamanho fixo e roda com o app em segundo plano.
 *
 * Fluxo de uma chamada: monta a URL com o template e os args (em JS) → carrega → espera a página (e o waitFor) →
 * roda o código da API com a ajuda `api` → devolve JSON padronizado para a LLM.
 */
object WebApiRunner {
    private val lock = Mutex()
    private var wv: WebView? = null
    @Volatile private var finished = false
    private val json = Json { ignoreUnknownKeys = true }

    @SuppressLint("SetJavaScriptEnabled")
    private fun view(ctx: Context): WebView = wv ?: WebView(ctx.applicationContext).also { v ->
        runCatching { if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) WebViewCompat.setProfile(v, BrowserProfileIdentity.SHARED_PROFILE) }
        runCatching { CookieManager.getInstance().apply { setAcceptCookie(true); setAcceptThirdPartyCookies(v, true) } }
        v.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true; databaseEnabled = true
            loadsImagesAutomatically = false; blockNetworkImage = true
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
            // Mesmo disfarce do browser das sessões: Chrome Android sem o marcador de WebView.
            userAgentString = userAgentString.replace("; wv", "").replace(" Version/4.0", "")
                .replace(Regex("; Android [^;]+; [^;]+ Build/[^;)]+"), "; Android 12")
        }
        v.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView?, req: WebResourceRequest?): WebResourceResponse? {
                val u = req?.url?.toString()?.lowercase().orEmpty()
                // Só texto/DOM interessam: imagens, fontes e mídia não são baixadas (mais rápido e menos dados).
                return if (req?.isForMainFrame != true && Regex("\\.(png|jpe?g|gif|webp|avif|svg|ico|woff2?|ttf|otf|mp4|webm|mp3|m4a)(\\?|$)").containsMatchIn(u))
                    WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0))) else null
            }
            override fun onPageFinished(view: WebView?, url: String?) { finished = true }
        }
        v.layout(0, 0, 1080, 2400)
        wv = v
    }

    private suspend fun eval(v: WebView, js: String): String? = suspendCancellableCoroutine { c ->
        runCatching { v.evaluateJavascript(js) { r -> if (c.isActive) c.resume(r) } }.onFailure { if (c.isActive) c.resume(null) }
    }
    /** evaluateJavascript devolve a string JSON-encodada: desembrulha. */
    private fun unwrap(r: String?): String? = r?.takeIf { it != "null" }?.let { runCatching { json.parseToJsonElement(it).jsonPrimitive.contentOrNull }.getOrNull() ?: it }

    /** Funções `api.*`, `enc` e `sleep` (assets/webapi/helpers.js — o mesmo arquivo usado no teste em Chrome do CI). */
    @Volatile private var helpersJs: String? = null
    private fun helpers(ctx: Context): String = helpersJs ?: ctx.assets.open("webapi/helpers.js").bufferedReader().readText().also { helpersJs = it }

    /** Executa a API. Devolve o JSON final (texto) ou {"ok":false,"error":…}. */
    suspend fun run(ctx: Context, a: WebApi, args: JsonObject, timeoutMs: Long = 30_000): String = lock.withLock {
        withContext(Dispatchers.Main) {
            val HELPERS = helpers(ctx)
            val started = System.currentTimeMillis()
            val v = runCatching { view(ctx) }.getOrElse { return@withContext err(a, args, "WebView indisponível: ${it.message}") }
            // Defaults dos parâmetros entram nos args.
            val full = JsonObject(a.params.filter { it.default.isNotBlank() && it.name !in args }.associate { p ->
                p.name to (p.default.toLongOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(p.default))
            } + args)
            val argsJs = full.toString()
            val out = withTimeoutOrNull(timeoutMs) {
                // 1) URL a partir do template (em JS, para aceitar expressões como (args.page-1)*10).
                if (v.url.isNullOrBlank()) { finished = false; v.loadUrl("about:blank"); var n = 0; while (!finished && n++ < 20) delay(50) }
                val tpl = a.urlTemplate  // template literal confiável (do hub); expressões ${...} rodam como JS
                val url = unwrap(eval(v, "(function(){$HELPERS;const args=$argsJs;try{return `$tpl`}catch(e){return 'ERR:'+e.message}})()"))
                if (url == null || url.startsWith("ERR:") || !Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(url))
                    return@withTimeoutOrNull err(a, full, "URL inválida a partir do template: ${url ?: "(vazia)"}")
                // 2) Carrega e espera o DOM.
                finished = false
                v.loadUrl(url)
                delay(300)
                while (true) {
                    val ready = unwrap(eval(v, "document.readyState+'|'+location.href")).orEmpty()
                    if (finished || (ready.startsWith("interactive") || ready.startsWith("complete")) && !ready.contains("about:blank")) break
                    delay(150)
                }
                if (a.waitFor.isNotBlank()) {
                    val sel = a.waitFor.replace("\\", "\\\\").replace("'", "\\'")
                    var n = 0
                    while (n++ < 60 && unwrap(eval(v, "!!document.querySelector('$sel')")) != "true") delay(150)
                }
                // Página de verificação (captcha/anti-robô): não tenta contornar — avisa para o usuário resolver uma vez no Browser (mesma sessão).
                val blocked = unwrap(eval(v, "(function(){$HELPERS;try{return api.blocked()}catch(e){return ''}})()")).orEmpty()
                if (blocked.isNotBlank()) return@withTimeoutOrNull buildJsonObject {
                    put("api", a.slug); put("ok", false); put("blocked", true); put("args", full); put("url", v.url.orEmpty()); put("error", blocked)
                    put("hint", "O site pediu verificação humana. O usuário pode resolver uma vez em Browser › API Hub › Testar › Resolver verificação (a sessão é a mesma); depois a API volta a funcionar.")
                }.toString()
                // 3) Roda o código da API; o resultado volta por window.__wa (evaluateJavascript não espera Promise).
                eval(v, "window.__wa=null;(async()=>{$HELPERS;const args=$argsJs;try{const r=await (async function(args,api){\n${a.code}\n})(args,api);" +
                    "window.__wa=JSON.stringify({ok:true,url:location.href,data:r===undefined?null:r})}catch(e){window.__wa=JSON.stringify({ok:false,url:location.href,error:String(e&&e.message||e)})}})();1")
                var res: String? = null
                while (res == null) { delay(150); res = unwrap(eval(v, "window.__wa")) }
                val o = runCatching { json.parseToJsonElement(res).jsonObject }.getOrNull() ?: return@withTimeoutOrNull err(a, full, "resposta não é JSON")
                if (o["ok"]?.jsonPrimitive?.contentOrNull != "true") return@withTimeoutOrNull err(a, full, o["error"]?.jsonPrimitive?.contentOrNull ?: "erro no código", o["url"]?.jsonPrimitive?.contentOrNull)
                envelope(a, full, o["url"]?.jsonPrimitive?.contentOrNull.orEmpty(), o["data"], System.currentTimeMillis() - started)
            }
            if (out == null) { runCatching { v.stopLoading() }; err(a, full, "tempo esgotado (${timeoutMs / 1000}s)") } else out
        }
    }

    private fun envelope(a: WebApi, args: JsonObject, url: String, data: JsonElement?, ms: Long): String = buildJsonObject {
        put("api", a.slug); put("ok", true); put("args", args); put("url", url)
        put("updated_at", java.time.Instant.now().toString()); put("took_ms", ms)
        // Objeto devolvido pelo código entra no nível de cima (results, count, has_more, next_page…); outros tipos em "data".
        if (data is JsonObject) data.forEach { (k, v) -> if (k !in setOf("api", "ok", "args", "url")) put(k, v) }
        else put("data", data ?: kotlinx.serialization.json.JsonNull)
    }.toString()

    private fun err(a: WebApi, args: JsonObject, msg: String, url: String? = null): String = buildJsonObject {
        put("api", a.slug); put("ok", false); put("args", args); url?.let { put("url", it) }; put("error", msg.take(500))
    }.toString()

    fun release() { runCatching { wv?.destroy() }; wv = null }
}
