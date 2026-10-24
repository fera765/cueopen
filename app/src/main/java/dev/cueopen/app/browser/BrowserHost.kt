package dev.cueopen.app.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Browser do agente: UM WebView compartilhado entre a UI (aba Browser do computador) e as
 * ferramentas do agente (navigate/read/click/type/scroll). O usuário vê ao vivo o que o agente faz
 * e pode assumir o controle tocando na página.
 */
class BrowserHost(private val appCtx: Context) {

    data class Ui(val url: String = "", val title: String = "", val loading: Boolean = false, val progress: Int = 0,
                  val canBack: Boolean = false, val canForward: Boolean = false, val agentControl: Boolean = false,
                  val desktop: Boolean = false, val online: Boolean = true, val netType: String = "")

    /** Uma requisição vista pelo WebView (painel Rede + ferramenta browser_network). */
    data class NetEntry(val id: Long, val url: String, val method: String, val type: String, val mainFrame: Boolean,
                        val time: Long, val status: Int? = null, val durationMs: Long? = null, val sizeBytes: Long? = null,
                        val error: String? = null)
    data class ConsoleEntry(val time: Long, val level: String, val message: String, val source: String)

    private val _net = MutableStateFlow<List<NetEntry>>(emptyList())
    val net: StateFlow<List<NetEntry>> = _net
    private val _console = MutableStateFlow<List<ConsoleEntry>>(emptyList())
    val console: StateFlow<List<ConsoleEntry>> = _console
    private val netSeq = java.util.concurrent.atomic.AtomicLong()
    private var defaultUa: String = ""

    fun clearNet() { _net.value = emptyList(); _console.value = emptyList() }

    private fun addNet(e: NetEntry) { _net.value = (_net.value + e).takeLast(500) }
    private fun patchNet(url: String, fn: (NetEntry) -> NetEntry) {
        val l = _net.value; val i = l.indexOfLast { it.url == url }
        if (i >= 0) _net.value = l.toMutableList().also { it[i] = fn(it[i]) }
    }

    private fun guessType(url: String, accept: String?, main: Boolean): String {
        val u = url.substringBefore('?').lowercase()
        return when {
            main -> "doc"
            u.endsWith(".js") || u.endsWith(".mjs") -> "js"
            u.endsWith(".css") -> "css"
            Regex("\\.(png|jpe?g|gif|webp|svg|ico|avif)$").containsMatchIn(u) || accept?.startsWith("image") == true -> "img"
            Regex("\\.(woff2?|ttf|otf)$").containsMatchIn(u) -> "font"
            Regex("\\.(mp4|webm|m3u8|mp3)$").containsMatchIn(u) -> "media"
            accept?.contains("json") == true || u.contains("/api/") -> "xhr"
            else -> "other"
        }
    }

    /** Monitor de conectividade (online/offline, Wi-Fi/dados). */
    private fun watchNetwork() {
        val cm = appCtx.getSystemService(android.net.ConnectivityManager::class.java) ?: return
        fun update() {
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            val online = caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            val type = when {
                caps == null -> "offline"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "Dados móveis"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                else -> "Rede"
            }
            _ui.value = _ui.value.copy(online = online, netType = type)
        }
        update()
        runCatching {
            cm.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) = update()
                override fun onLost(network: android.net.Network) = update()
                override fun onCapabilitiesChanged(network: android.net.Network, c: android.net.NetworkCapabilities) = update()
            })
        }
    }



    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui

    // precisa vir DEPOIS de _ui (ordem de inicialização das propriedades)
    init { runCatching { watchNetwork() } }
    private var pageDone: CompletableDeferred<Unit>? = null
    private var _view: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    fun view(ctx: Context = appCtx): WebView {
        _view?.let { v -> (v.parent as? ViewGroup)?.removeView(v); return v }
        val v = WebView(ctx.applicationContext)
        with(v.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            allowFileAccess = true
            @Suppress("DEPRECATION") allowFileAccessFromFileURLs = true
            @Suppress("DEPRECATION") allowUniversalAccessFromFileURLs = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = userAgentString.replace("; wv", "")
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(false)
            if (android.os.Build.VERSION.SDK_INT >= 26) safeBrowsingEnabled = false
        }
        defaultUa = v.settings.userAgentString
        v.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
        if (android.os.Build.VERSION.SDK_INT >= 26) v.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        if (android.os.Build.VERSION.SDK_INT >= 23) v.settings.offscreenPreRaster = true
        v.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) { sync(view, loading = true) }
            override fun onPageFinished(view: WebView, url: String) {
                sync(view, loading = false); pageDone?.complete(Unit)
                collectTimings(view)
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): android.webkit.WebResourceResponse? {
                // só observa (não intercepta): alimenta o painel de rede
                val url = request.url.toString()
                if (!url.startsWith("data:")) addNet(NetEntry(netSeq.incrementAndGet(), url, request.method ?: "GET",
                    guessType(url, request.requestHeaders["Accept"], request.isForMainFrame), request.isForMainFrame, System.currentTimeMillis()))
                return null
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: android.webkit.WebResourceResponse) {
                patchNet(request.url.toString()) { it.copy(status = errorResponse.statusCode) }
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                patchNet(request.url.toString()) { it.copy(error = error.description?.toString()) }
                if (request.isForMainFrame) pageDone?.complete(Unit)
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val s = request.url.scheme ?: return true
                return s !in setOf("http", "https", "file", "about", "data")
            }
        }
        v.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) { _ui.value = _ui.value.copy(progress = newProgress) }
            override fun onReceivedTitle(view: WebView, title: String?) { sync(view) }
            override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                _console.value = (_console.value + ConsoleEntry(System.currentTimeMillis(), m.messageLevel().name.lowercase(),
                    m.message(), "${m.sourceId().substringAfterLast('/')}:${m.lineNumber()}")).takeLast(300)
                return true
            }
        }
        _view = v
        v.loadUrl(HOME)
        return v
    }

    private fun sync(v: WebView, loading: Boolean = _ui.value.loading) {
        _ui.value = _ui.value.copy(url = v.url ?: "", title = v.title ?: "", loading = loading,
            canBack = v.canGoBack(), canForward = v.canGoForward())
    }

    fun home() { _view?.loadUrl(HOME) }

    /** Alterna entre versão mobile e desktop dos sites. */
    fun setDesktop(on: Boolean) {
        val v = _view ?: return
        v.settings.userAgentString = if (on) "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36" else defaultUa
        v.settings.useWideViewPort = true; v.settings.loadWithOverviewMode = true
        _ui.value = _ui.value.copy(desktop = on); v.reload()
    }

    /** Completa duração/tamanho das requisições com a Resource Timing API da página. */
    private fun collectTimings(v: WebView) {
        v.evaluateJavascript("""(function(){try{var n=performance.getEntriesByType('navigation')[0];var r=performance.getEntriesByType('resource');
            var o=r.map(function(e){return [e.name,Math.round(e.duration),e.transferSize||e.encodedBodySize||0,e.responseStatus||0]});
            if(n)o.push([location.href,Math.round(n.duration),n.transferSize||0,n.responseStatus||0]);return JSON.stringify(o.slice(-400));}catch(e){return '[]'}})()""") { raw ->
            runCatching {
                val arr = org.json.JSONArray(org.json.JSONTokener(raw).nextValue() as String)
                val map = HashMap<String, org.json.JSONArray>()
                for (i in 0 until arr.length()) arr.getJSONArray(i).let { map[it.getString(0)] = it }
                _net.value = _net.value.map { e ->
                    map[e.url]?.let { t -> e.copy(durationMs = t.getLong(1), sizeBytes = t.getLong(2).takeIf { it > 0 },
                        status = e.status ?: t.getInt(3).takeIf { it > 0 }) } ?: e
                }
            }
        }
    }

    fun networkSummary(filter: String): String {
        val l = _net.value.filter { filter.isBlank() || it.url.contains(filter, true) }.takeLast(60)
        if (l.isEmpty()) return "Nenhuma requisição registrada (rede: ${_ui.value.netType}, online=${_ui.value.online})."
        val fails = l.count { (it.status ?: 0) >= 400 || it.error != null }
        return "Rede: ${_ui.value.netType} · ${l.size} requisições · $fails com erro\n" + l.joinToString("\n") { e ->
            "${e.method} ${e.status ?: e.error ?: "-"} ${e.type} ${e.durationMs?.let { "${it}ms" } ?: ""} ${e.sizeBytes?.let { "${it / 1024}KB" } ?: ""} ${e.url.take(160)}"
        }
    }

    fun consoleSummary(): String = _console.value.takeLast(60).joinToString("\n") { "[${it.level}] ${it.message.take(300)} (${it.source})" }
        .ifBlank { "Console vazio." }

    companion object { const val HOME = "file:///android_asset/start/index.html" }

    fun setAgentControl(on: Boolean) { _ui.value = _ui.value.copy(agentControl = on) }

    // ---------------- API usada pelo agente ----------------

    suspend fun navigate(url: String): String = onMain {
        val target = when {
            url.startsWith("http") || url.startsWith("file:") || url.startsWith("about:") -> url
            url.contains(".") && !url.contains(" ") -> "https://$url"
            else -> "https://www.google.com/search?q=" + java.net.URLEncoder.encode(url, "UTF-8")
        }
        pageDone = CompletableDeferred()
        view().loadUrl(target)
        target
    }.also { awaitLoad() }

    private suspend fun awaitLoad() {
        withTimeoutOrNull(25_000) { pageDone?.await() }
        kotlinx.coroutines.delay(600) // deixa JS da página renderizar
    }

    suspend fun back() { onMain { pageDone = CompletableDeferred(); view().goBack() }; awaitLoad() }
    suspend fun reload() { onMain { pageDone = CompletableDeferred(); view().reload() }; awaitLoad() }

    /** Texto legível + lista numerada de elementos interativos (o agente usa o índice para clicar). */
    suspend fun read(maxChars: Int = 9000): String {
        val js = """
            (function(){
              var els=[].slice.call(document.querySelectorAll('a,button,input,textarea,select,[role=button],[onclick],summary'));
              var out=[],i=0;
              els.forEach(function(e){
                var r=e.getBoundingClientRect(); if(r.width<2||r.height<2) return;
                var st=getComputedStyle(e); if(st.visibility=='hidden'||st.display=='none') return;
                e.setAttribute('data-cue',i);
                var label=(e.innerText||e.value||e.placeholder||e.getAttribute('aria-label')||e.title||e.name||'').trim().replace(/\s+/g,' ').slice(0,80);
                var tag=e.tagName.toLowerCase(); if(tag=='input') tag+='['+(e.type||'text')+']';
                var href=e.href?(' -> '+String(e.href).slice(0,120)):'';
                out.push('['+i+'] '+tag+' "'+label+'"'+href); i++;
              });
              var text=(document.body?document.body.innerText:'').replace(/\n{3,}/g,'\n\n');
              return JSON.stringify({url:location.href,title:document.title,text:text,elements:out.slice(0,150)});
            })()
        """.trimIndent()
        val raw = eval(js)
        return runCatching {
            val o = JSONObject(raw)
            val els = o.getJSONArray("elements")
            buildString {
                append("URL: ${o.optString("url")}\nTítulo: ${o.optString("title")}\n\n--- Conteúdo ---\n")
                append(o.optString("text").take(maxChars))
                append("\n\n--- Elementos interativos ---\n")
                for (i in 0 until els.length()) append(els.getString(i)).append('\n')
            }
        }.getOrElse { "Falha ao ler página: $raw" }
    }

    suspend fun click(index: Int): String {
        val r = eval("""(function(){var e=document.querySelector('[data-cue="$index"]');if(!e)return 'elemento $index não encontrado — chame browser_read de novo';e.scrollIntoView({block:'center'});e.click();return 'ok: clicou em '+(e.innerText||e.value||e.tagName).slice(0,60);})()""")
        awaitLoadIfNavigating()
        return r
    }

    suspend fun type(index: Int, text: String, submit: Boolean): String {
        val t = JSONObject.quote(text)
        val r = eval("""
            (function(){var e=document.querySelector('[data-cue="$index"]');if(!e)return 'elemento $index não encontrado';
            e.focus(); var setter=Object.getOwnPropertyDescriptor(Object.getPrototypeOf(e),'value');
            if(setter&&setter.set){setter.set.call(e,$t);}else{e.value=$t;}
            e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));
            ${if (submit) "if(e.form){e.form.requestSubmit?e.form.requestSubmit():e.form.submit();}else{e.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',keyCode:13,bubbles:true}));}" else ""}
            return 'ok';})()
        """.trimIndent())
        if (submit) awaitLoadIfNavigating()
        return r
    }

    suspend fun scroll(down: Boolean): String =
        eval("window.scrollBy(0, ${if (down) "" else "-"}window.innerHeight*0.8); 'ok'")

    suspend fun runJs(code: String): String = eval(code)

    private suspend fun awaitLoadIfNavigating() {
        kotlinx.coroutines.delay(800)
        if (_ui.value.loading) { pageDone = CompletableDeferred(); awaitLoad() }
    }

    private suspend fun eval(js: String): String {
        val d = CompletableDeferred<String>()
        onMain { view().evaluateJavascript(js) { d.complete(it ?: "null") } }
        val raw = withTimeoutOrNull(15_000) { d.await() } ?: return "timeout"
        // evaluateJavascript devolve um literal JSON (string entre aspas)
        return runCatching {
            when (val v = JSONTokener(raw).nextValue()) {
                is String -> v
                is JSONObject, is JSONArray -> v.toString()
                else -> v.toString()
            }
        }.getOrDefault(raw)
    }

    private suspend fun <T> onMain(block: () -> T): T = withContext(Dispatchers.Main) { block() }
}
