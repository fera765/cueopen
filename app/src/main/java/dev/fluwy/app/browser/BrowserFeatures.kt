package dev.fluwy.app.browser

import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewFeature
import dev.fluwy.app.FluwyApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Lado Android da seção Browser: sessões de login (verificar/revogar cookies do perfil de contas) e
 * injeção de User Scripts nos browsers dos agentes. As regras ficam em [BrowserPolicies].
 *
 * Isolamento: cada agente segue com o próprio browser (abas, histórico, estado). Só o perfil de contas
 * (cookies/armazenamento) é global — é ele que faz um login salvo valer para os agentes autorizados.
 */
object BrowserFeatures {
    /** Chave do browser usado para o usuário fazer login (não é de nenhum agente). */
    const val LOGIN_BROWSER_KEY = "__browser_login__"

    private fun profileCookies(): CookieManager =
        runCatching {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE))
                ProfileStore.getInstance().getOrCreateProfile(BrowserProfileIdentity.SHARED_PROFILE).cookieManager else null
        }.getOrNull() ?: CookieManager.getInstance()

    private fun profileStorage(): WebStorage =
        runCatching {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE))
                ProfileStore.getInstance().getOrCreateProfile(BrowserProfileIdentity.SHARED_PROFILE).webStorage else null
        }.getOrNull() ?: WebStorage.getInstance()

    private fun urlsFor(domain: String) = listOf("https://$domain", "https://www.$domain", "http://$domain")

    /** Quantos cookies o perfil tem para o domínio (0 = sem sessão; não lê nem devolve valores). */
    suspend fun cookieCount(domain: String): Int = withContext(Dispatchers.Main) {
        runCatching {
            val cm = profileCookies()
            urlsFor(domain).flatMap { u -> cm.getCookie(u)?.split(';')?.map { it.substringBefore('=').trim() }.orEmpty() }
                .filter { it.isNotEmpty() }.toSet().size
        }.getOrDefault(0)
    }

    /**
     * Revoga a sessão: expira os cookies do domínio e apaga o armazenamento local das origens dele.
     * Limite do WebView: não há API para apagar por domínio, então cada cookie visível é expirado.
     */
    suspend fun revoke(domain: String) = withContext(Dispatchers.Main) {
        runCatching {
            val cm = profileCookies()
            urlsFor(domain).forEach { u ->
                cm.getCookie(u)?.split(';')?.map { it.substringBefore('=').trim() }?.filter { it.isNotEmpty() }?.forEach { name ->
                    val dead = "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/"
                    cm.setCookie(u, dead)
                    cm.setCookie(u, "$dead; Domain=.$domain")
                    cm.setCookie(u, "$dead; Domain=$domain")
                }
            }
            cm.flush()
            val storage = profileStorage()
            urlsFor(domain).forEach { runCatching { storage.deleteOrigin(it) } }
        }
    }

    // ───────────── Ponte GM_xmlhttpRequest (requisições entre domínios, como no Tampermonkey) ─────────────

    private val tokens = java.util.Collections.synchronizedMap(java.util.WeakHashMap<WebView, String>())
    private val http by lazy {
        okhttp3.OkHttpClient.Builder().followRedirects(true).callTimeout(60, java.util.concurrent.TimeUnit.SECONDS).build()
    }

    /** Liga a ponte num WebView de agente. Só quem tem o token (o envelope do userscript) consegue usá-la. */
    @android.annotation.SuppressLint("JavascriptInterface", "AddJavascriptInterface")
    fun installBridge(view: WebView) {
        val token = java.util.UUID.randomUUID().toString()
        tokens[view] = token
        runCatching { view.addJavascriptInterface(GmBridge(view, token), "__fluwyGMBridge") }
    }

    fun tokenFor(view: WebView): String = tokens[view].orEmpty()

    private class GmBridge(view: WebView, private val token: String) {
        private val ref = java.lang.ref.WeakReference(view)

        @android.webkit.JavascriptInterface
        fun request(t: String?, json: String?, cbId: String?) {
            if (t != token || json == null || cbId == null || !Regex("^c[a-z0-9]{1,20}$").matches(cbId)) return
            Thread {
                val result = runCatching { perform(org.json.JSONObject(json)) }
                    .getOrElse { org.json.JSONObject().put("error", it.message ?: it.javaClass.simpleName) }
                val view = ref.get() ?: return@Thread
                view.post {
                    runCatching { view.evaluateJavascript("(function(){var c=window.__fluwyGMcbs;c&&c['$cbId']&&c['$cbId']($result);})()", null) }
                }
            }.apply { isDaemon = true }.start()
        }

        private fun perform(o: org.json.JSONObject): org.json.JSONObject {
            val url = o.getString("url")
            val method = o.optString("method", "GET").uppercase()
            val denial = BrowserPolicies.gmRequestDenial(url)
            if (denial != null) return org.json.JSONObject().put("error", denial)
            val b = okhttp3.Request.Builder().url(url)
            o.optJSONObject("headers")?.let { h -> h.keys().forEach { k -> runCatching { b.header(k, h.getString(k)) } } }
            // Como no Tampermonkey: leva os cookies do site de destino (salvo anonymous=true).
            if (!o.optBoolean("anonymous", false)) cookieHeader(url)?.let { if (it.isNotBlank()) b.header("Cookie", it) }
            val data = if (o.isNull("data")) null else o.optString("data")
            val body = if (method in setOf("GET", "HEAD")) null else (data ?: "").toByteArray().toRequestBody(null)
            b.method(method, body)
            val timeout = o.optLong("timeout", 30_000).coerceIn(1_000, 120_000)
            val client = http.newBuilder().callTimeout(timeout, java.util.concurrent.TimeUnit.MILLISECONDS).build()
            client.newCall(b.build()).execute().use { r ->
                // Lê no máximo GM_MAX_RESPONSE: uma resposta gigante não derruba o app.
                val text = r.body?.source()?.let { src ->
                    src.request(BrowserPolicies.GM_MAX_RESPONSE)
                    src.buffer.readUtf8(minOf(src.buffer.size, BrowserPolicies.GM_MAX_RESPONSE))
                }.orEmpty()
                val headers = r.headers.joinToString("\r\n") { "${it.first}: ${it.second}" }
                return org.json.JSONObject().put("status", r.code).put("statusText", r.message).put("responseText", text)
                    .put("finalUrl", r.request.url.toString()).put("responseHeaders", headers).put("readyState", 4)
            }
        }

        private fun cookieHeader(url: String): String? {
            val latch = java.util.concurrent.CountDownLatch(1)
            var c: String? = null
            android.os.Handler(android.os.Looper.getMainLooper()).post { c = runCatching { profileCookies().getCookie(url) }.getOrNull(); latch.countDown() }
            latch.await(1, java.util.concurrent.TimeUnit.SECONDS)
            return c
        }
    }

    /** Injeta os User Scripts que valem para esta URL/agente. Chamado na thread principal pelo WebViewClient. */
    fun applyUserScripts(view: WebView, url: String, sessionId: String, early: Boolean = false) {
        if (sessionId == LOGIN_BROWSER_KEY) return
        val app = runCatching { FluwyApp.instance }.getOrNull() ?: return
        val st = app.store.state.value
        if (st.userScripts.isEmpty()) return
        val agentId = st.sessions.firstOrNull { it.id == sessionId }?.agentId
        val host = BrowserPolicies.hostOf(url)
        // early = primeiro quadro da página: só scripts @run-at document-start (os demais esperam a página carregar).
        BrowserPolicies.scriptsFor(url, agentId, st.userScripts).filter { !early || it.runAt == "document-start" }.forEach { script ->
            runCatching {
                view.evaluateJavascript(BrowserPolicies.wrap(script, tokenFor(view))) { raw ->
                    val parsed = BrowserPolicies.parseResult(raw)
                    // null = o script nem compilou (erro de sintaxe).
                    val result = if (parsed.isBlank() || parsed == "null") "err:erro de sintaxe no script" else parsed
                    if (result == "dup") return@evaluateJavascript
                    val clock = java.text.SimpleDateFormat("HH:mm", java.util.Locale.ROOT).format(java.util.Date())
                    app.store.editUserScript(script.id) { BrowserPolicies.record(it, host, result, System.currentTimeMillis(), clock) }
                    if (result != "ok") runCatching {
                        dev.fluwy.app.diag.ErrorLog.log("browser", "User Script \"${script.name}\" falhou em $host: ${result.removePrefix("err:")}", sessionId, "user_script")
                    }
                }
            }
        }
    }
}
