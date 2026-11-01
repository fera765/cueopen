package dev.cueopen.app.browser

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
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
class BrowserHost(private val appCtx: Context, sessionId: String = "__legacy__") {

    private val persistence = BrowserSessionPersistence(appCtx, sessionId)
    private val profileName = persistence.profileName

    data class Ui(val url: String = "", val title: String = "", val loading: Boolean = false, val progress: Int = 0,
                  val canBack: Boolean = false, val canForward: Boolean = false, val agentControl: Boolean = false,
                  val desktop: Boolean = false, val online: Boolean = true, val netType: String = "", val darkPages: Boolean = false,
                  val profileIsolated: Boolean = true)

    /** Uma requisição vista pelo WebView (painel Rede + ferramenta browser_network). */
    data class NetEntry(val id: Long, val url: String, val method: String, val type: String, val mainFrame: Boolean,
                        val time: Long, val status: Int? = null, val durationMs: Long? = null, val sizeBytes: Long? = null,
                        val error: String? = null, val requestHeaders: Map<String, String> = emptyMap(),
                        val requestBody: String? = null, val responseHeaders: Map<String, String> = emptyMap(), val responseBody: String? = null)
    data class ConsoleEntry(val time: Long, val level: String, val message: String, val source: String)

    private val _net = MutableStateFlow<List<NetEntry>>(emptyList())
    val net: StateFlow<List<NetEntry>> = _net
    private val _console = MutableStateFlow<List<ConsoleEntry>>(emptyList())
    val console: StateFlow<List<ConsoleEntry>> = _console
    private val netSeq = java.util.concurrent.atomic.AtomicLong()
    private val netScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pendingNet = ArrayList<NetEntry>()
    private var netFlushJob: Job? = null
    private var defaultUa: String = ""

    @Synchronized fun clearNet() {
        pendingNet.clear()
        netFlushJob?.cancel()
        netFlushJob = null
        _net.value = emptyList()
        _console.value = emptyList()
    }

    @Synchronized private fun addNet(e: NetEntry) {
        pendingNet.add(e)
        if (pendingNet.size > 500) pendingNet.removeAt(0)
        if (netFlushJob?.isActive != true) {
            netFlushJob = netScope.launch {
                delay(100)
                flushNet(fromScheduledJob = true)
            }
        }
    }

    @Synchronized private fun flushNet(fromScheduledJob: Boolean = false) {
        if (!fromScheduledJob) netFlushJob?.cancel()
        if (pendingNet.isNotEmpty()) {
            _net.value = (_net.value + pendingNet).takeLast(500)
            pendingNet.clear()
        }
        netFlushJob = null
    }

    @Synchronized private fun patchNet(url: String, fn: (NetEntry) -> NetEntry) {
        val pendingIndex = pendingNet.indexOfLast { it.url == url }
        if (pendingIndex >= 0) {
            pendingNet[pendingIndex] = fn(pendingNet[pendingIndex])
            return
        }
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
    private var pageDone: CompletableDeferred<String>? = null
    private var navigationStatus = BrowserNavigationStatus()
    private var _view: WebView? = null
    private var _agentView: WebView? = null
    @SuppressLint("SetJavaScriptEnabled")
    @Synchronized
    fun view(ctx: Context = appCtx): WebView {
        // Nunca recriar o WebView ao abrir/focar a aba Browser. A mesma instância pertence
        // à sessão do agente e à UI; destruí-la durante uma navegação cancelava o carregamento
        // e fazia o site parecer travado até o usuário abrir o Browser.
        _view?.let { return it }
        return createView(ctx, false).also { view ->
            _view = view
            ensureSized(view)
            restoreViewState(view)
        }
    }

    /** Restaura navegação e preferências deste chat; usa a URL simples se o Bundle não puder ser lido. */
    private fun restoreViewState(view: WebView) {
        val saved = persistence.load()
        if (saved.desktop) view.settings.userAgentString = DESKTOP_UA
        darkPages = saved.darkPages
        applyColorScheme(view, darkPages)
        _ui.value = _ui.value.copy(desktop = saved.desktop, darkPages = saved.darkPages)
        val restored = runCatching {
            persistence.loadWebViewState()?.let { view.restoreState(it) != null } ?: false
        }.getOrDefault(false)
        if (restored) sync(view, loading = view.progress < 100)
        else view.loadUrl(saved.url.ifBlank { HOME })
    }

    /**
     * O agente navega com a aba Browser FECHADA: o WebView fica sem janela e com 0×0. Páginas que
     * calculam o layout pelo tamanho da janela (slides com scale = innerWidth/1920, 100vh, canvas)
     * carregavam com escala 0 e ficavam em BRANCO — só elementos fixos (barra, timer) apareciam.
     * Fora da tela, damos ao WebView o tamanho real da tela antes de carregar.
     */
    private fun ensureSized(v: WebView) {
        if (v.isAttachedToWindow && v.width > 0 && v.height > 0) return
        if (v.width > 0 && v.height > 0) return
        val dm = appCtx.resources.displayMetrics
        val w = dm.widthPixels.coerceAtLeast(360)
        val h = (dm.heightPixels - (dm.density * 180).toInt()).coerceAtLeast(480) // menos barras/abas do Computador
        v.measure(android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(h, android.view.View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
    }

    /** Avisa a página que a janela mudou (aba aberta/rotação): layouts calculados em JS se refazem. */
    private fun notifyResize(v: WebView) {
        v.evaluateJavascript("(function(){try{window.dispatchEvent(new Event('resize'));}catch(e){}})()", null)
    }

    /**
     * O agente e a aba Browser precisam operar no MESMO WebView.
     * Um WebView separado fazia o chat navegar em segundo plano enquanto a UI ficava na HOME.
     */
    private fun agentView(): WebView = _view ?: view(appCtx)

    /** Página de erro própria do CueOpen, no lugar da tela padrão do Chrome/WebView. */
    private fun errorPageHtml(url: String, rawDesc: String): String {
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        val code = rawDesc.substringAfter("net::", rawDesc).trim()
        val friendly = when {
            code.contains("CONNECTION_REFUSED") -> "O servidor recusou a conexão. Se for um site local (localhost), confirme que ele está rodando."
            code.contains("NAME_NOT_RESOLVED") -> "Não foi possível encontrar esse endereço. Verifique se o domínio está certo."
            code.contains("INTERNET_DISCONNECTED") || code.contains("NETWORK_CHANGED") -> "Sem conexão com a internet no momento."
            code.contains("TIMED_OUT") -> "O servidor demorou demais para responder."
            code.contains("FILE_NOT_FOUND") -> "O arquivo ou página não foi encontrado."
            code.contains("SSL") || code.contains("CERT") -> "Houve um problema de segurança (certificado) com este site."
            else -> "Não foi possível abrir esta página."
        }
        val safeUrl = esc(url); val safeCode = esc(code.ifBlank { rawDesc })
        return """
<!doctype html><html lang="pt-br"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<style>
  :root{color-scheme:dark}
  *{box-sizing:border-box}
  body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;
    background:#0F1115;color:#E6E6E6;font-family:-apple-system,Roboto,system-ui,sans-serif;padding:28px}
  .card{max-width:480px;width:100%;text-align:center}
  .emoji{font-size:54px;line-height:1;margin-bottom:12px}
  h1{font-size:22px;font-weight:700;margin:0 0 10px}
  p{font-size:15px;line-height:1.5;color:#AFAFAF;margin:0 0 18px}
  .url{font-family:ui-monospace,Menlo,monospace;font-size:12px;color:#8A8A8A;
    background:#171A20;border-radius:10px;padding:10px 12px;word-break:break-all;margin-bottom:18px}
  .code{font-family:ui-monospace,Menlo,monospace;font-size:12px;color:#FF8A63;margin-bottom:22px}
  button{appearance:none;border:0;cursor:pointer;background:#FF8A63;color:#0F1115;font-weight:700;
    font-size:15px;border-radius:999px;padding:13px 26px}
  .brand{margin-top:26px;font-size:12px;color:#5A5A5A}
</style></head>
<body><div class="card">
  <div class="emoji">🛰️</div>
  <h1>Página indisponível</h1>
  <p>$friendly</p>
  <div class="url">$safeUrl</div>
  <button onclick="location.href='$safeUrl'">Tentar de novo</button>
  <div class="code">$safeCode</div>
  <div class="brand">Fluwy Bot · navegador do agente</div>
</div></body></html>
        """.trimIndent()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createView(ctx: Context, agentMode: Boolean): WebView {
        val v = WebView(ctx)
        val isolated = runCatching {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) false
            else {
                WebViewCompat.setProfile(v, profileName)
                WebViewCompat.getProfile(v)?.name == profileName
            }
        }.getOrDefault(false)
        _ui.value = _ui.value.copy(profileIsolated = isolated)
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
            // O WebView padrão anuncia "Version/4.0" e "wv", identificadores que alguns sites
            // modernos (incluindo portais de notícias) recusam como navegador incompatível.
            // Manter o motor real do WebView, mas anunciar Chrome Android sem o marcador de WebView.
            userAgentString = userAgentString
                .replace("; wv", "")
                .replace(" Version/4.0", "")
                .replace(Regex("; Android [^;]+; [^;]+ Build/[^;)]+"), "; Android 12")
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(false)
            if (android.os.Build.VERSION.SDK_INT >= 26) safeBrowsingEnabled = false
        }
        // TEMA: páginas renderizam como foram escritas (claro), igual a um navegador comum. Com o
        // aparelho em modo escuro o WebView podia escurecer a página ou anunciar prefers-color-scheme
        // dark e esconder o conteúdo (texto escuro sobre fundo escurecido / CSS escuro incompleto).
        applyColorScheme(v, darkPages)
        v.setBackgroundColor(android.graphics.Color.WHITE)
        // Ao abrir a aba (WebView ganha tamanho) a página recalcula o layout.
        v.addOnLayoutChangeListener { view, l, t, r, b, ol, ot, or, ob ->
            if ((r - l) != (or - ol) || (b - t) != (ob - ot)) notifyResize(view as WebView)
        }
        defaultUa = v.settings.userAgentString
        v.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
        if (android.os.Build.VERSION.SDK_INT >= 26) v.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        if (android.os.Build.VERSION.SDK_INT >= 23) v.settings.offscreenPreRaster = true
        v.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) { sync(view, loading = true) }
            override fun onPageCommitVisible(view: WebView, url: String) {
                // Algumas SPAs mantêm requisições abertas e nunca disparam onPageFinished. Um
                // primeiro quadro visível permite inspecionar o DOM sem esperar 45 s, mas não é
                // tratado como prontidão funcional.
                pageDone?.complete(navigationStatus.pageCommitted(url))
                persistWebViewState(view)
            }
            override fun onPageFinished(view: WebView, url: String) {
                sync(view, loading = false)
                pageDone?.complete(navigationStatus.pageFinished())
                collectTimings(view)
                installSnapshotInstrumentation(view)
                installNetworkInstrumentation(view)
                persistWebViewState(view)
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): android.webkit.WebResourceResponse? {
                // só observa (não intercepta): alimenta o painel de rede
                val url = request.url.toString()
                if (!url.startsWith("data:")) addNet(NetEntry(netSeq.incrementAndGet(), url, request.method ?: "GET",
                    guessType(url, request.requestHeaders["Accept"], request.isForMainFrame), request.isForMainFrame, System.currentTimeMillis(), requestHeaders = request.requestHeaders.toMap()))
                return null
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: android.webkit.WebResourceResponse) {
                patchNet(request.url.toString()) { it.copy(status = errorResponse.statusCode) }
                navigationStatus.recordHttpError(request.isForMainFrame, errorResponse.statusCode)?.let { failure ->
                    sync(view, loading = false)
                    pageDone?.complete(failure)
                }
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                patchNet(request.url.toString()) { it.copy(error = error.description?.toString()) }
                if (request.isForMainFrame) {
                    sync(view, loading = false)
                    val desc = error.description?.toString() ?: "Falha ao carregar a página"
                    val failure = navigationStatus.recordNetworkError(true, request.url.toString(), desc)
                    pageDone?.complete(failure ?: "ERROR: $desc")
                    // Página de erro própria (substitui a tela padrão do Chrome/WebView).
                    val failing = request.url.toString()
                    view.loadDataWithBaseURL(null, errorPageHtml(failing, desc), "text/html", "UTF-8", failing)
                }
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
        return v
    }


    /** Captura sem depender de proxy/VPN: instrumenta fetch/XHR da página após cada navegação. */
    private fun installNetworkInstrumentation(v: WebView) {
        v.evaluateJavascript("""(function(){if(window.__cueNetInstalled)return 'ok';window.__cueNetInstalled=true;window.__cueNet=[];
          function trim(x){try{return String(x||'').slice(0,12000)}catch(e){return ''}}
          function headers(h){var o={};try{if(h&&h.forEach)h.forEach(function(v,k){o[k]=v});else if(h)Object.keys(h).forEach(function(k){o[k]=h[k]})}catch(e){}return o}
          var oldFetch=window.fetch;if(oldFetch)window.fetch=function(input,init){var u=typeof input==='string'?input:(input&&input.url)||'',m=(init&&init.method)||(input&&input.method)||'GET',rh=headers((init&&init.headers)||(input&&input.headers)),rb=trim(init&&init.body),t=Date.now();
            return oldFetch.apply(this,arguments).then(function(r){var row={url:u,method:m,requestHeaders:rh,requestBody:rb,status:r.status,responseHeaders:headers(r.headers),durationMs:Date.now()-t};window.__cueNet.push(row);if(window.__cueNet.length>300)window.__cueNet.shift();r.clone().text().then(function(b){row.responseBody=trim(b)},function(){});return r},function(e){window.__cueNet.push({url:u,method:m,requestHeaders:rh,requestBody:rb,error:String(e),durationMs:Date.now()-t});throw e})};
          var X=window.XMLHttpRequest;if(X){var op=X.prototype.open,sp=X.prototype.send,sh=X.prototype.setRequestHeader;X.prototype.open=function(m,u){this.__cue={url:String(u),method:m,requestHeaders:{},requestBody:'',start:0};return op.apply(this,arguments)};
            X.prototype.setRequestHeader=function(k,v){if(this.__cue)this.__cue.requestHeaders[k]=v;return sh.apply(this,arguments)};
            X.prototype.send=function(b){var x=this; if(this.__cue){this.__cue.requestBody=trim(b);this.__cue.start=Date.now();this.addEventListener('loadend',function(){var row={url:x.__cue.url,method:x.__cue.method,requestHeaders:x.__cue.requestHeaders,requestBody:x.__cue.requestBody,status:x.status,responseHeaders:{},responseBody:'',durationMs:Date.now()-x.__cue.start};try{x.getAllResponseHeaders().trim().split(/[\r\n]+/).forEach(function(s){var p=s.indexOf(':');if(p>0)row.responseHeaders[s.slice(0,p).trim()]=s.slice(p+1).trim()});row.responseBody=trim(x.responseType===''||x.responseType==='text'?x.responseText:'[non-text response]')}catch(e){}window.__cueNet.push(row);if(window.__cueNet.length>300)window.__cueNet.shift()})}return sp.apply(this,arguments)}}return 'ok'})()""", null)
    }

    /** Snapshot com estrutura acessível, landmarks, headings, links e controles nomeados. */
    private fun installSnapshotInstrumentation(v: WebView) {
        v.evaluateJavascript("""(function(){try{document.documentElement.setAttribute('data-cue-snapshot-ready','1');return 'ok'}catch(e){return 'err'}})()""", null)
    }

    private suspend fun mergeCapturedNetwork() {
        flushNet()
        val raw = runCatching { eval("JSON.stringify((window.__cueNet||[]).slice(-200))") }.getOrNull() ?: return
        runCatching {
            val arr = JSONArray(raw)
            val captured = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            val entries = _net.value.toMutableList()
            captured.forEach { o ->
                val url=o.optString("url"); val method=o.optString("method","GET")
                val idx=entries.indexOfLast { it.url == url && it.method.equals(method,true) }
                if(idx>=0) {
                    val old=entries[idx]
                    fun objMap(key:String):Map<String,String> { val jo=o.optJSONObject(key)?:return emptyMap(); val m=linkedMapOf<String,String>(); jo.keys().forEach { k->m[k]=jo.optString(k).take(2000) }; return m }
                    entries[idx]=old.copy(status=o.optInt("status",old.status?:0).takeIf{it>0}?:old.status,
                        durationMs=o.optLong("durationMs",old.durationMs?:0).takeIf{it>0}?:old.durationMs,
                        requestHeaders=objMap("requestHeaders"), requestBody=o.optString("requestBody").takeIf{it.isNotBlank()},
                        responseHeaders=objMap("responseHeaders"), responseBody=o.optString("responseBody").takeIf{it.isNotBlank()},
                        error=o.optString("error").takeIf{it.isNotBlank()}?:old.error)
                }
            }
            _net.value=entries.takeLast(500)
        }
    }

    private fun sync(v: WebView, loading: Boolean = _ui.value.loading) {
        val next = _ui.value.copy(url = v.url ?: "", title = v.title ?: "", loading = loading,
            canBack = v.canGoBack(), canForward = v.canGoForward())
        _ui.value = next
        runCatching { persistence.saveMetadata(BrowserSavedState(next.url, next.desktop, next.darkPages)) }
    }

    fun home() { _view?.loadUrl(HOME) }

    /** Chamado no onStop da Activity; o perfil e o histórico são persistidos antes de o processo sair. */
    fun persistState() {
        val v = _view ?: return
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            persistWebViewState(v)
            runCatching { WebViewCompat.getProfile(v).cookieManager.flush() }
        } else {
            android.os.Handler(android.os.Looper.getMainLooper()).post { persistState() }
        }
    }

    private fun persistWebViewState(view: WebView) {
        val state = BrowserSavedState(view.url ?: _ui.value.url, _ui.value.desktop, _ui.value.darkPages)
        runCatching { persistence.saveMetadata(state) }
        val bundle = Bundle()
        runCatching {
            if (view.saveState(bundle) != null) persistence.saveWebViewState(bundle)
        }
    }

    /** Libera os WebViews (usado ao encerrar uma sessão isolada de subagente). */
    fun dispose(clearPersistentState: Boolean = false) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (!clearPersistentState) _view?.let { persistWebViewState(it) }
            runCatching { _view?.destroy() }
            runCatching { _agentView?.destroy() }
            _view = null; _agentView = null
            if (clearPersistentState) clearSessionData(appCtx, profileName, persistence)
        }
    }

    companion object {
        const val HOME = "file:///android_asset/start/index.html"
        private const val DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

        fun clearSessionData(context: Context, sessionId: String) {
            val persistence = BrowserSessionPersistence(context, sessionId)
            clearSessionData(context, persistence.profileName, persistence)
        }

        private fun clearSessionData(context: Context, profileName: String, persistence: BrowserSessionPersistence) {
            persistence.clearFiles()
            runCatching {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE))
                    ProfileStore.getInstance().deleteProfile(profileName)
            }
        }
    }

    suspend fun uiBack() = onMain { if (_view?.canGoBack() == true) { pageDone = CompletableDeferred(); _view?.goBack() } }
    suspend fun uiReload() = onMain { pageDone = CompletableDeferred(); _view?.reload() }

    /** Páginas em tema escuro? Padrão: não (renderiza como o site foi feito). Alternável no menu ⋮. */
    @Volatile var darkPages: Boolean = false
        private set

    fun setDarkPages(on: Boolean) {
        darkPages = on
        _view?.let { applyColorScheme(it, on); it.reload() }
        _ui.value = _ui.value.copy(darkPages = on)
        runCatching { persistence.saveMetadata(BrowserSavedState(_ui.value.url, _ui.value.desktop, on)) }
    }

    private fun applyColorScheme(v: WebView, dark: Boolean) {
        runCatching {
            if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.ALGORITHMIC_DARKENING))
                androidx.webkit.WebSettingsCompat.setAlgorithmicDarkeningAllowed(v.settings, false)
        }
        @Suppress("DEPRECATION")
        runCatching {
            if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.FORCE_DARK)) {
                // ON só faz o site usar o próprio tema escuro (prefers-color-scheme); nunca inverte cores.
                androidx.webkit.WebSettingsCompat.setForceDark(v.settings,
                    if (dark) androidx.webkit.WebSettingsCompat.FORCE_DARK_ON else androidx.webkit.WebSettingsCompat.FORCE_DARK_OFF)
                if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.FORCE_DARK_STRATEGY))
                    androidx.webkit.WebSettingsCompat.setForceDarkStrategy(v.settings, androidx.webkit.WebSettingsCompat.DARK_STRATEGY_WEB_THEME_DARKENING_ONLY)
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= 29) runCatching { v.isForceDarkAllowed = false }
    }

    /** Alterna entre versão mobile e desktop dos sites. */
    fun setDesktop(on: Boolean) {
        val v = _view ?: return
        v.settings.userAgentString = if (on) DESKTOP_UA else defaultUa
        v.settings.useWideViewPort = true
        v.settings.loadWithOverviewMode = true
        _ui.value = _ui.value.copy(desktop = on)
        runCatching { persistence.saveMetadata(BrowserSavedState(_ui.value.url, on, _ui.value.darkPages)) }
        v.reload()
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

    suspend fun networkSummary(filter: String): String {
        mergeCapturedNetwork()
        val l = _net.value.filter { filter.isBlank() || it.url.contains(filter, true) }.takeLast(60)
        if (l.isEmpty()) return "Nenhuma requisição registrada (rede: ${_ui.value.netType}, online=${_ui.value.online})."
        val fails = l.count { (it.status ?: 0) >= 400 || it.error != null }
        return "Rede: ${_ui.value.netType} · ${l.size} requisições · $fails com erro\n" + l.joinToString("\n") { e ->
            buildString {
                append("${e.method} ${e.status ?: e.error ?: "-"} ${e.type} ${e.durationMs?.let { "${it}ms" } ?: ""} ${e.sizeBytes?.let { "${it / 1024}KB" } ?: ""} ${e.url.take(160)}")
                if (e.requestHeaders.isNotEmpty()) append("\n  Request headers: ").append(e.requestHeaders.entries.joinToString { "${it.key}=${it.value}" }.take(1500))
                e.requestBody?.let { append("\n  Request payload: ").append(it.take(3000)) }
                if (e.responseHeaders.isNotEmpty()) append("\n  Response headers: ").append(e.responseHeaders.entries.joinToString { "${it.key}=${it.value}" }.take(1500))
                e.responseBody?.let { append("\n  Response body: ").append(it.take(3000)) }
            }
        }
    }

    fun consoleSummary(): String = _console.value.takeLast(60).joinToString("\n") { "[${it.level}] ${it.message.take(300)} (${it.source})" }
        .ifBlank { "Console vazio." }

    fun setAgentControl(on: Boolean) { _ui.value = _ui.value.copy(agentControl = on) }

    // ---------------- API usada pelo agente ----------------

    suspend fun navigate(url: String): String {
        val normalized = when {
            url.startsWith("http://", true) || url.startsWith("https://", true) || url.startsWith("file:") || url.startsWith("about:") -> url
            url.contains(".") && !url.contains(" ") -> "https://$url"
            else -> "https://www.google.com/search?q=" + java.net.URLEncoder.encode(url, "UTF-8")
        }
        // Um teste TCP curto evita que um localhost sem servidor vire uma espera opaca de 45 s.
        // É apenas uma sondagem de conexão; readiness HTTP/da aplicação continua sendo validada depois.
        val localFailure = LoopbackEndpointProbe.failureIfUnavailable(normalized)
        if (localFailure != null) {
            return onMain {
                navigationStatus = BrowserNavigationStatus()
                val failure = navigationStatus.recordNetworkError(true, normalized, "net::ERR_CONNECTION_REFUSED")
                    ?: localFailure
                pageDone = CompletableDeferred<String>().also { it.complete(failure) }
                val view = agentView().also { ensureSized(it) }
                _ui.value = _ui.value.copy(loading = false, progress = 0)
                view.loadDataWithBaseURL(normalized, errorPageHtml(normalized, "net::ERR_CONNECTION_REFUSED"), "text/html", "UTF-8", normalized)
                failure
            }
        }
        val target = onMain {
            navigationStatus = BrowserNavigationStatus()
            pageDone = CompletableDeferred()
            _ui.value = _ui.value.copy(loading = true, progress = 0)
            agentView().also { ensureSized(it) }.loadUrl(normalized)
            normalized
        }
        return awaitLoad(target)
    }

    /** Aguarda o callback real do WebView e verifica o DOM; falha e timeout não viram sucesso silencioso. */
    private suspend fun awaitLoad(target: String = _ui.value.url): String {
        val status = navigationStatus
        val deferred = pageDone ?: return "ERROR: não foi possível acompanhar o carregamento desta navegação."
        val completed = withTimeoutOrNull(45_000) { deferred.await() }
        val result = completed?.let(status::preserveHttpError) ?: status.httpError() ?: run {
            _ui.value = _ui.value.copy(loading = false)
            // Diagnóstico curto e único: conteúdo visível não significa que a SPA/hydration esteja pronta.
            val raw = runCatching {
                eval("JSON.stringify({chars:document.body?document.body.innerText.length:0,ready:document.readyState,url:location.href})", timeoutMillis = 1_500)
            }.getOrDefault("")
            val state = runCatching { JSONObject(raw) }.getOrNull()
            val chars = state?.optInt("chars", 0) ?: 0
            val currentUrl = state?.optString("url").orEmpty().ifBlank { _ui.value.url }
            val readyState = state?.optString("ready").orEmpty()
            return status.timeoutMessage(target, currentUrl, readyState, chars)
        }
        if (result.startsWith("ERROR:")) return result
        val deadline = System.currentTimeMillis() + 8_000
        var ready = ""
        while (System.currentTimeMillis() < deadline) {
            ready = runCatching { eval("document.readyState") }.getOrDefault("")
            if (ready == "complete") break
            kotlinx.coroutines.delay(250)
        }
        kotlinx.coroutines.delay(500)
        status.httpError()?.let { return it }
        return if (ready == "complete") "OK: página carregada ($target)"
        else {
            _ui.value = _ui.value.copy(loading = false)
            val raw = runCatching {
                eval("JSON.stringify({chars:document.body?document.body.innerText.length:0,url:location.href})", timeoutMillis = 1_500)
            }.getOrDefault("")
            val state = runCatching { JSONObject(raw) }.getOrNull()
            status.incompleteMessage(target, state?.optString("url").orEmpty().ifBlank { _ui.value.url }, ready,
                state?.optInt("chars", 0) ?: 0)
        }
    }

    suspend fun cancelNavigation() = onMain {
        _view?.stopLoading()
        pageDone?.complete("ERROR: navegação cancelada.")
        _ui.value = _ui.value.copy(loading = false)
    }

    suspend fun back() { onMain { navigationStatus = BrowserNavigationStatus(); pageDone = CompletableDeferred(); agentView().goBack() }; awaitLoad(_ui.value.url) }
    suspend fun reload() { onMain { navigationStatus = BrowserNavigationStatus(); pageDone = CompletableDeferred(); agentView().reload() }; awaitLoad(_ui.value.url) }

    /** Snapshot semântico da página, próximo ao formato Playwright: landmarks, headings, texto e controles nomeados. */
    suspend fun read(maxChars: Int = 9000): String {
        val js = """
            (function(){
              var els=[].slice.call(document.querySelectorAll('a,button,input,textarea,select,[role=button],[onclick],summary,[role=link],[role=checkbox],[role=tab]'));
              var out=[],i=0, headings=[];
              [].slice.call(document.querySelectorAll("h1,h2,h3,h4,[role=heading]")).forEach(function(h){var t=(h.innerText||h.getAttribute("aria-label")||"").trim().replace(/\s+/g," ");if(t)headings.push({level:h.tagName.toLowerCase(),text:t.slice(0,180)});});
              els.forEach(function(e){
                var r=e.getBoundingClientRect(); if(r.width<2||r.height<2) return;
                var st=getComputedStyle(e); if(st.visibility=='hidden'||st.display=='none') return;
                e.setAttribute('data-cue',i);
                var label=(e.innerText||e.value||e.placeholder||e.getAttribute('aria-label')||e.title||e.name||'').trim().replace(/\s+/g,' ').slice(0,80);
                var tag=e.tagName.toLowerCase(); if(tag=='input') tag+='['+(e.type||'text')+']';
                var href=e.href?(' -> '+String(e.href).slice(0,120)):'';
                var role=e.getAttribute("role")||({A:"link",BUTTON:"button",INPUT:"textbox",TEXTAREA:"textbox",SELECT:"combobox"}[e.tagName]||tag);
                var extra=e.disabled?" [disabled]":""; if(e.getAttribute("aria-expanded"))extra+=" [expanded="+e.getAttribute("aria-expanded")+"]";
                out.push("["+i+"] "+role+" \""+label+"\""+extra+href); i++;
              });
              var text=(document.body?document.body.innerText:'').replace(/\n{3,}/g,'\n\n');
              var main=document.querySelector("main,[role=main],article")||document.body;
              var links=[].slice.call(document.querySelectorAll("a[href]")).filter(function(a){return a.innerText.trim()}).slice(0,80).map(function(a){return {text:a.innerText.trim().replace(/\s+/g," ").slice(0,100),href:a.href}});
              return JSON.stringify({url:location.href,title:document.title,text:text,headings:headings.slice(0,80),landmark:main?main.tagName.toLowerCase():"body",links:links,elements:out.slice(0,180)});
            })()
        """.trimIndent()
        val raw = eval(js)
        if (raw == "null" || raw == "undefined" || raw.isBlank()) {
            return "Falha ao ler página: o JavaScript de snapshot não retornou dados. Verifique o console do WebView e se o DOM já foi criado."
        }
        return runCatching {
            val o = JSONObject(raw)
            val els = o.getJSONArray("elements")
            buildString {
                append("URL: ${o.optString("url")}\nTítulo: ${o.optString("title")}\n\n--- Conteúdo ---\n")
                append("--- Estrutura (headings) ---\n")
                val hs = o.optJSONArray("headings") ?: JSONArray()
                for (i in 0 until hs.length()) { val h=hs.optJSONObject(i) ?: continue; append("${h.optString("level")} ${h.optString("text")}\n") }
                append("\n--- Conteúdo principal (${o.optString("landmark", "body")}) ---\n")
                append(o.optString("text").take(maxChars))
                append("\n\n--- Elementos interativos ---\n")
                for (i in 0 until els.length()) append(els.getString(i)).append('\n')
            }
        }.getOrElse { "Falha ao ler página: $raw" }
    }

    suspend fun click(index: Int): String {
        val r = eval("""(function(){var e=document.querySelector('[data-cue="$index"]');if(!e)return 'elemento $index não encontrado: referência obsoleta; execute browser_read novamente, escolha um índice novo e não repita este';e.scrollIntoView({block:'center'});e.click();return 'ok: clicou em '+(e.innerText||e.value||e.tagName).slice(0,60);})()""")
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
        // progress é método do WebView: só pode ser lido na main thread (evita o crash
        // "A WebView method was called on thread 'DefaultDispatcher-worker'").
        val navigating = onMain { agentView().progress < 100 }
        if (navigating) { onMain { pageDone = CompletableDeferred() }; awaitLoad() }
    }

    private suspend fun eval(js: String, timeoutMillis: Long = 15_000): String {
        val d = CompletableDeferred<String>()
        onMain { agentView().evaluateJavascript(js) { d.complete(it ?: "null") } }
        val raw = withTimeoutOrNull(timeoutMillis) { d.await() } ?: return "timeout: evaluateJavascript não respondeu em ${timeoutMillis / 1_000}s"
        // evaluateJavascript devolve um literal JSON (string entre aspas); null/undefined
        // geralmente indica que o script não retornou valor. Preserve esse estado explicitamente.
        if (raw == "null") return "null"
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
