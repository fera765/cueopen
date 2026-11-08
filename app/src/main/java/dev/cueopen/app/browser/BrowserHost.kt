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
 * Browser do agente: múltiplas abas WebView isoladas por conversa. A aba ativa é compartilhada
 * entre a UI e as ferramentas do agente (navigate/read/click/type/scroll); o usuário vê ao vivo
 * o que o agente faz e pode assumir o controle tocando na página.
 */
class BrowserHost(private val appCtx: Context, sessionId: String = "__legacy__") {

    private val persistence = BrowserSessionPersistence(appCtx, sessionId)
    /** Perfil WebView compartilhado (contas logadas); abas/histórico continuam por chat em [persistence]. */
    private val profileName = BrowserProfileIdentity.SHARED_PROFILE

    data class TabInfo(val id: String, val url: String, val title: String)

    data class Ui(val url: String = "", val title: String = "", val loading: Boolean = false, val progress: Int = 0,
                  val canBack: Boolean = false, val canForward: Boolean = false, val agentControl: Boolean = false,
                  val desktop: Boolean = false, val online: Boolean = true, val netType: String = "", val darkPages: Boolean = false,
                  val profileIsolated: Boolean = true, val tabs: List<TabInfo> = emptyList(), val selectedTabId: String = "")

    /** Uma requisição vista pelo WebView (painel Rede + ferramenta browser_network). */
    data class NetEntry(val id: Long, val url: String, val method: String, val type: String, val mainFrame: Boolean,
                        val time: Long, val status: Int? = null, val durationMs: Long? = null, val sizeBytes: Long? = null,
                        val error: String? = null, val requestHeaders: Map<String, String> = emptyMap(),
                        val requestBody: String? = null, val responseHeaders: Map<String, String> = emptyMap(), val responseBody: String? = null,
                        val tabId: String = BrowserSessionPersistence.LEGACY_TAB_ID)
    data class ConsoleEntry(val time: Long, val level: String, val message: String, val source: String,
                            val tabId: String = BrowserSessionPersistence.LEGACY_TAB_ID)

    private data class BrowserTab(
        val id: String,
        var url: String,
        var title: String,
        var webView: WebView? = null,
        var pageDone: CompletableDeferred<String>? = null,
        var navigationStatus: BrowserNavigationStatus = BrowserNavigationStatus(),
        var progress: Int = 0,
    )

    private val savedSession = persistence.load()
    private val tabs = linkedMapOf<String, BrowserTab>().apply {
        savedSession.tabs.forEach { put(it.id, BrowserTab(it.id, it.url, it.title)) }
    }
    @Volatile private var activeTabId: String = savedSession.selectedTabId
    private var lastReadTabId: String? = null

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
        flushNet()
        val selected = activeTabId
        _net.value = _net.value.filterNot { it.tabId == selected }
        _console.value = _console.value.filterNot { it.tabId == selected }
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

    @Synchronized private fun patchNet(url: String, tabId: String, fn: (NetEntry) -> NetEntry) {
        val pendingIndex = pendingNet.indexOfLast { it.url == url && it.tabId == tabId }
        if (pendingIndex >= 0) {
            pendingNet[pendingIndex] = fn(pendingNet[pendingIndex])
            return
        }
        val l = _net.value; val i = l.indexOfLast { it.url == url && it.tabId == tabId }
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

    @Volatile var darkPages: Boolean = savedSession.darkPages
        private set

    // precisa vir DEPOIS de _ui (ordem de inicialização das propriedades)
    init {
        if (tabs.isEmpty()) tabs[BrowserSessionPersistence.LEGACY_TAB_ID] = BrowserTab(BrowserSessionPersistence.LEGACY_TAB_ID, savedSession.url, "")
        if (activeTabId !in tabs) activeTabId = tabs.keys.first()
        _ui.value = _ui.value.copy(
            url = activeTab()?.url.orEmpty(), title = activeTab()?.title.orEmpty(),
            desktop = savedSession.desktop, darkPages = savedSession.darkPages,
            tabs = tabInfo(), selectedTabId = activeTabId,
        )
        runCatching { watchNetwork() }
    }
    @SuppressLint("SetJavaScriptEnabled")
    @Synchronized
    fun view(ctx: Context = appCtx): WebView {
        val tab = activeTab() ?: error("A sessão do browser não tem uma aba ativa.")
        return ensureTabView(tab, ctx)
    }

    private fun activeTab(): BrowserTab? = tabs[activeTabId] ?: tabs.values.firstOrNull()

    private fun tabInfo(): List<TabInfo> = tabs.values.map { TabInfo(it.id, it.webView?.url ?: it.url, it.webView?.title ?: it.title) }

    private fun saveMetadata() {
        val active = activeTab()
        runCatching {
            persistence.saveMetadata(BrowserSavedState(
                url = active?.webView?.url ?: active?.url.orEmpty(),
                desktop = _ui.value.desktop,
                darkPages = darkPages,
                tabs = tabs.values.map { BrowserSavedTab(it.id, it.webView?.url ?: it.url, it.webView?.title ?: it.title) },
                selectedTabId = activeTabId,
            ))
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureTabView(tab: BrowserTab, ctx: Context, restore: Boolean = true): WebView {
        tab.webView?.let { return it }
        return createView(ctx, tab).also { view ->
            tab.webView = view
            ensureSized(view)
            if (_ui.value.desktop) view.settings.userAgentString = DESKTOP_UA
            if (restore) restoreViewState(tab, view)
        }
    }

    /** Restaura navegação/histórico independente da aba, com fallback para URL. */
    private fun restoreViewState(tab: BrowserTab, view: WebView) {
        if (_ui.value.desktop) view.settings.userAgentString = DESKTOP_UA
        applyColorScheme(view, darkPages)
        val restored = runCatching {
            if (!_ui.value.profileIsolated && (tab.url.startsWith("http://") || tab.url.startsWith("https://"))) false
            else persistence.loadWebViewState(tab.id)?.let { view.restoreState(it) != null } ?: false
        }.getOrDefault(false)
        if (restored) sync(view, loading = view.progress < 100)
        else if (!_ui.value.profileIsolated && (tab.url.startsWith("http://") || tab.url.startsWith("https://"))) {
            view.loadDataWithBaseURL(null, errorPageHtml(tab.url, "PROFILE_ISOLATION_UNAVAILABLE"), "text/html", "UTF-8", tab.url)
        } else view.loadUrl(tab.url.ifBlank { HOME })
    }

    /** Abre uma aba dentro deste chat. Todas as abas desta conversa usam o mesmo perfil isolado. */
    fun createTab(ctx: Context = appCtx, initialUrl: String = HOME, deferNavigation: Boolean = false): String? {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) return null
        if (tabs.size >= BrowserSessionPersistence.MAX_TABS) return null
        val id = java.util.UUID.randomUUID().toString().replace('-', '_')
        val tab = BrowserTab(id, initialUrl, "")
        tabs[id] = tab
        activeTabId = id
        lastReadTabId = null
        _ui.value = _ui.value.copy(tabs = tabInfo(), selectedTabId = id, url = "", title = "", loading = false, progress = 0,
            canBack = false, canForward = false)
        saveMetadata()
        ensureTabView(tab, ctx, restore = !deferNavigation)
        return id
    }

    fun selectTab(tabId: String): Boolean {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper() || tabId !in tabs) return false
        activeTabId = tabId
        lastReadTabId = null
        val tab = tabs.getValue(tabId)
        val view = tab.webView
        if (view != null) sync(view, loading = view.progress < 100)
        else _ui.value = _ui.value.copy(url = tab.url, title = tab.title, loading = false, progress = tab.progress,
            canBack = false, canForward = false, tabs = tabInfo(), selectedTabId = tabId)
        saveMetadata()
        return true
    }

    fun closeTab(tabId: String): Boolean {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper() || tabs.size <= 1) return false
        val tab = tabs.remove(tabId) ?: return false
        tab.webView?.let { view ->
            persistWebViewState(tab, view)
            runCatching { view.stopLoading(); (view.parent as? android.view.ViewGroup)?.removeView(view); view.destroy() }
        }
        persistence.clearTabData(tabId)
        if (activeTabId == tabId) activeTabId = tabs.keys.last()
        lastReadTabId = null
        val active = tabs.getValue(activeTabId)
        val view = active.webView
        if (view != null) sync(view, loading = view.progress < 100)
        else _ui.value = _ui.value.copy(url = active.url, title = active.title, loading = false, progress = active.progress,
            canBack = false, canForward = false, tabs = tabInfo(), selectedTabId = activeTabId)
        saveMetadata()
        return true
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
     * O agente e a interface operam no WebView da aba ativa, evitando navegação em segundo plano.
     */
    private fun agentView(): WebView = view(appCtx)

    /** Página de erro própria do CueOpen, no lugar da tela padrão do Chrome/WebView. */
    private fun errorPageHtml(url: String, rawDesc: String): String {
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        val code = rawDesc.substringAfter("net::", rawDesc).trim()
        val friendly = when {
            code.contains("PROFILE_ISOLATION_UNAVAILABLE") -> "Este provedor não oferece perfis isolados. Para proteger outros chats, páginas externas ficam bloqueadas neste browser."
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
    private fun createView(ctx: Context, tab: BrowserTab): WebView {
        val v = WebView(ctx)
        // Perfil persistente de contas. Sem suporte a perfis, o perfil padrão do WebView já é único e
        // persistente — serve igual, então a rede nunca é bloqueada por isso.
        runCatching {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) WebViewCompat.setProfile(v, profileName)
        }
        val isolated = true
        _ui.value = _ui.value.copy(profileIsolated = isolated)
        runCatching {
            val cm = cookieManagerFor(v)
            cm.setAcceptCookie(true)
            // Logins via Google/Facebook/SSO dependem de cookies de terceiros (padrão desligado no targetSdk 28).
            cm.setAcceptThirdPartyCookies(v, true)
        }
        with(v.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            allowFileAccess = true
            // Sem perfis particionados o provedor usaria cookies/storage globais entre chats.
            runCatching {
                // O WebView envia "X-Requested-With: <pacote do app>" em toda requisição; Meta e outros sites
                // usam isso para marcar tráfego de app/robô e responder 403. Lista vazia = não envia para ninguém.
                if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST))
                    androidx.webkit.WebSettingsCompat.setRequestedWithHeaderOriginAllowList(this, emptySet())
            }
            // Nesse caso, bloquear toda rede externa em vez de permitir vazamento entre conversas.
            blockNetworkLoads = !isolated
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
            setSupportMultipleWindows(true)
            if (android.os.Build.VERSION.SDK_INT >= 26) safeBrowsingEnabled = false
        }
        enableLegacyFileAccess(v.settings)
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
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                tab.progress = 0
                if (tab.id == activeTabId) lastReadTabId = null
                sync(view, loading = true)
            }
            override fun onPageCommitVisible(view: WebView, url: String) {
                // Algumas SPAs mantêm requisições abertas e nunca disparam onPageFinished. Um
                // primeiro quadro visível permite inspecionar o DOM sem esperar 45 s, mas não é
                // tratado como prontidão funcional.
                tab.pageDone?.complete(tab.navigationStatus.pageCommitted(url))
                persistWebViewState(tab, view)
            }
            override fun onPageFinished(view: WebView, url: String) {
                sync(view, loading = false)
                tab.pageDone?.complete(tab.navigationStatus.pageFinished())
                collectTimings(tab, view)
                installSnapshotInstrumentation(view)
                installNetworkInstrumentation(view)
                persistWebViewState(tab, view)
                // grava o login no disco a cada página carregada: sobrevive a o app ser morto pelo Android
                runCatching { cookieManagerFor(view).flush() }
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): android.webkit.WebResourceResponse? {
                // só observa (não intercepta): alimenta o painel de rede
                val url = request.url.toString()
                if (!url.startsWith("data:")) addNet(NetEntry(netSeq.incrementAndGet(), url, request.method ?: "GET",
                    guessType(url, request.requestHeaders["Accept"], request.isForMainFrame), request.isForMainFrame, System.currentTimeMillis(),
                    requestHeaders = request.requestHeaders.toMap(), tabId = tab.id))
                return null
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: android.webkit.WebResourceResponse) {
                patchNet(request.url.toString(), tab.id) { it.copy(status = errorResponse.statusCode) }
                // Só registra: vários sites (Meta, Cloudflare) respondem 403/429 e mesmo assim renderizam a página.
                // A decisão (erro x página válida) é tomada em awaitLoad pelo conteúdo realmente visível.
                tab.navigationStatus.recordHttpError(request.isForMainFrame, errorResponse.statusCode)
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                patchNet(request.url.toString(), tab.id) { it.copy(error = error.description?.toString()) }
                if (request.isForMainFrame) {
                    sync(view, loading = false)
                    val desc = error.description?.toString() ?: "Falha ao carregar a página"
                    val failure = tab.navigationStatus.recordNetworkError(true, request.url.toString(), desc)
                    tab.pageDone?.complete(failure ?: "ERROR: $desc")
                    // Página de erro própria (substitui a tela padrão do Chrome/WebView).
                    val failing = request.url.toString()
                    view.loadDataWithBaseURL(null, errorPageHtml(failing, desc), "text/html", "UTF-8", failing)
                }
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val s = request.url.scheme ?: return true
                if (!isolated && (s == "http" || s == "https")) return true
                return s !in setOf("http", "https", "file", "about", "data")
            }
        }
        v.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean {
                if (!isUserGesture) return false
                val id = createTab(appCtx, "about:blank", deferNavigation = true) ?: return false
                val child = tabs[id]?.webView ?: return false
                val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                transport.webView = child
                resultMsg.sendToTarget()
                return true
            }
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                tab.progress = newProgress
                if (tab.id == activeTabId) _ui.value = _ui.value.copy(progress = newProgress)
            }
            override fun onReceivedTitle(view: WebView, title: String?) { sync(view) }
            override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                _console.value = (_console.value + ConsoleEntry(System.currentTimeMillis(), m.messageLevel().name.lowercase(),
                    m.message(), "${m.sourceId().substringAfterLast('/')}:${m.lineNumber()}", tab.id)).takeLast(300)
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
        val tab = onMain { activeTab() } ?: return
        val raw = runCatching { eval("JSON.stringify((window.__cueNet||[]).slice(-200))", tab = tab) }.getOrNull() ?: return
        runCatching {
            val arr = JSONArray(raw)
            val captured = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            val entries = _net.value.toMutableList()
            captured.forEach { o ->
                val url=o.optString("url"); val method=o.optString("method","GET")
                val idx=entries.indexOfLast { it.tabId == tab.id && it.url == url && it.method.equals(method,true) }
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
        val tab = tabs.values.firstOrNull { it.webView === v } ?: return
        tab.url = v.url ?: tab.url
        tab.title = v.title ?: tab.title
        tab.progress = v.progress
        if (tab.id == activeTabId) {
            _ui.value = _ui.value.copy(url = tab.url, title = tab.title, loading = loading,
                progress = v.progress, canBack = v.canGoBack(), canForward = v.canGoForward(),
                tabs = tabInfo(), selectedTabId = tab.id)
        } else {
            _ui.value = _ui.value.copy(tabs = tabInfo())
        }
        saveMetadata()
    }

    fun home() { activeTab()?.webView?.loadUrl(HOME) }

    /** Chamado no onStop da Activity; o perfil e o histórico são persistidos antes de o processo sair. */
    fun persistState() {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            tabs.values.forEach { tab -> tab.webView?.let { persistWebViewState(tab, it) } }
            activeTab()?.webView?.let { v -> runCatching { cookieManagerFor(v).flush() } }
            saveMetadata()
        } else {
            android.os.Handler(android.os.Looper.getMainLooper()).post { persistState() }
        }
    }

    private fun persistWebViewState(tab: BrowserTab, view: WebView) {
        tab.url = view.url ?: tab.url
        tab.title = view.title ?: tab.title
        saveMetadata()
        val bundle = Bundle()
        runCatching {
            if (view.saveState(bundle) != null) persistence.saveWebViewState(tab.id, bundle)
        }
    }

    /** Libera os WebViews (usado ao encerrar uma sessão isolada de subagente). */
    fun dispose(clearPersistentState: Boolean = false) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            tabs.values.forEach { tab ->
                tab.pageDone?.complete("ERROR: a sessão do browser foi encerrada.")
                tab.webView?.let { view ->
                    if (!clearPersistentState) persistWebViewState(tab, view)
                    runCatching { view.stopLoading(); view.destroy() }
                }
            }
            tabs.clear()
            if (clearPersistentState) clearSessionData(appCtx, profileName, persistence)
        }
    }

    companion object {
        const val HOME = "file:///android_asset/start/index.html"
        const val MAX_TABS = BrowserSessionPersistence.MAX_TABS
        private const val DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

        fun clearSessionData(context: Context, sessionId: String) {
            val persistence = BrowserSessionPersistence(context, sessionId)
            clearSessionData(context, persistence.profileName, persistence)
        }

        private fun clearSessionData(context: Context, profileName: String, persistence: BrowserSessionPersistence) {
            // Apaga só abas/histórico deste chat. O perfil de contas é compartilhado e NÃO é apagado aqui;
            // remove apenas o perfil isolado antigo (versões anteriores criavam um por chat).
            persistence.clearFiles()
            runCatching {
                val legacy = persistence.profileName
                if (legacy != BrowserProfileIdentity.SHARED_PROFILE && WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE))
                    ProfileStore.getInstance().deleteProfile(legacy)
            }
        }

        /** CookieManager do perfil compartilhado (ou o global, se o WebView não tiver perfis). */
        internal fun cookieManagerFor(v: WebView): android.webkit.CookieManager =
            runCatching {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) WebViewCompat.getProfile(v).cookieManager else null
            }.getOrNull() ?: android.webkit.CookieManager.getInstance()

        /** "Sair de todas as contas": apaga cookies e armazenamento do perfil de contas. */
        fun signOutEverywhere(context: Context) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching {
                    if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
                        val profile = ProfileStore.getInstance().getOrCreateProfile(BrowserProfileIdentity.SHARED_PROFILE)
                        profile.cookieManager.removeAllCookies(null); profile.cookieManager.flush()
                        profile.webStorage.deleteAllData()
                    } else {
                        android.webkit.CookieManager.getInstance().removeAllCookies(null)
                        android.webkit.CookieManager.getInstance().flush()
                        android.webkit.WebStorage.getInstance().deleteAllData()
                    }
                }
            }
        }
    }

    suspend fun uiBack() = onMain {
        activeTab()?.let { tab -> tab.webView?.let { if (it.canGoBack()) { tab.pageDone = CompletableDeferred(); tab.navigationStatus = BrowserNavigationStatus(); it.goBack() } } }
    }
    suspend fun uiReload() = onMain {
        activeTab()?.let { tab -> tab.webView?.let { tab.pageDone = CompletableDeferred(); tab.navigationStatus = BrowserNavigationStatus(); it.reload() } }
    }

    /** Páginas em tema escuro? Padrão: não (renderiza como o site foi feito). Alternável no menu ⋮. */
    fun setDarkPages(on: Boolean) {
        darkPages = on
        tabs.values.forEach { tab -> tab.webView?.let { applyColorScheme(it, on); it.reload() } }
        _ui.value = _ui.value.copy(darkPages = on)
        saveMetadata()
    }

    @Suppress("DEPRECATION")
    private fun enableLegacyFileAccess(settings: WebSettings) {
        settings.allowFileAccessFromFileURLs = true
        settings.allowUniversalAccessFromFileURLs = true
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
        _ui.value = _ui.value.copy(desktop = on)
        tabs.values.forEach { tab ->
            tab.webView?.let { view ->
                view.settings.userAgentString = if (on) DESKTOP_UA else defaultUa
                view.settings.useWideViewPort = true
                view.settings.loadWithOverviewMode = true
                view.reload()
            }
        }
        _ui.value = _ui.value.copy(desktop = on)
        saveMetadata()
    }

    /** Completa duração/tamanho das requisições com a Resource Timing API da página. */
    private fun collectTimings(tab: BrowserTab, v: WebView) {
        v.evaluateJavascript("""(function(){try{var n=performance.getEntriesByType('navigation')[0];var r=performance.getEntriesByType('resource');
            var o=r.map(function(e){return [e.name,Math.round(e.duration),e.transferSize||e.encodedBodySize||0,e.responseStatus||0]});
            if(n)o.push([location.href,Math.round(n.duration),n.transferSize||0,n.responseStatus||0]);return JSON.stringify(o.slice(-400));}catch(e){return '[]'}})()""") { raw ->
            runCatching {
                val arr = org.json.JSONArray(org.json.JSONTokener(raw).nextValue() as String)
                val map = HashMap<String, org.json.JSONArray>()
                for (i in 0 until arr.length()) arr.getJSONArray(i).let { map[it.getString(0)] = it }
                _net.value = _net.value.map { e ->
                    map[e.url]?.takeIf { e.tabId == tab.id }?.let { t -> e.copy(durationMs = t.getLong(1), sizeBytes = t.getLong(2).takeIf { it > 0 },
                        status = e.status ?: t.getInt(3).takeIf { it > 0 }) } ?: e
                }
            }
        }
    }

    suspend fun networkSummary(filter: String): String {
        mergeCapturedNetwork()
        val l = _net.value.filter { it.tabId == activeTabId && (filter.isBlank() || it.url.contains(filter, true)) }.takeLast(60)
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

    fun consoleSummary(): String = _console.value.filter { it.tabId == activeTabId }.takeLast(60).joinToString("\n") { "[${it.level}] ${it.message.take(300)} (${it.source})" }
        .ifBlank { "Console vazio." }

    fun setAgentControl(on: Boolean) { _ui.value = _ui.value.copy(agentControl = on) }

    // ---------------- API usada pelo agente ----------------

    suspend fun navigate(url: String): String = navigateInTab(url, null)

    private suspend fun navigateInTab(url: String, tabId: String?): String {
        val normalized = when {
            url.startsWith("http://", true) || url.startsWith("https://", true) || url.startsWith("file:") || url.startsWith("about:") -> url
            url.contains(".") && !url.contains(" ") -> "https://$url"
            else -> "https://www.google.com/search?q=" + java.net.URLEncoder.encode(url, "UTF-8")
        }
        val targetTab = onMain {
            val tab = tabId?.let { tabs[it] } ?: activeTab()
            if (tab != null) {
                if (tabId != null) selectTab(tab.id)
                ensureTabView(tab, appCtx)
                tab.navigationStatus = BrowserNavigationStatus()
                tab.pageDone = CompletableDeferred()
                lastReadTabId = null
            }
            tab
        } ?: return "ERROR: a aba do browser não existe mais."
        val blockedByIsolation = onMain {
            !_ui.value.profileIsolated && (normalized.startsWith("http://") || normalized.startsWith("https://"))
        }
        if (blockedByIsolation) {
            onMain {
                targetTab.webView?.loadDataWithBaseURL(null, errorPageHtml(normalized, "PROFILE_ISOLATION_UNAVAILABLE"), "text/html", "UTF-8", normalized)
                if (targetTab.id == activeTabId) _ui.value = _ui.value.copy(loading = false, progress = 0)
            }
            return "ERROR: navegação externa bloqueada porque este WebView não oferece perfil isolado por conversa."
        }
        // Um teste TCP curto evita que um localhost sem servidor vire uma espera opaca de 45 s.
        // É apenas uma sondagem de conexão; readiness HTTP/da aplicação continua sendo validada depois.
        val localFailure = LoopbackEndpointProbe.failureIfUnavailable(normalized)
        if (localFailure != null) {
            return onMain {
                targetTab.navigationStatus = BrowserNavigationStatus()
                val failure = targetTab.navigationStatus.recordNetworkError(true, normalized, "net::ERR_CONNECTION_REFUSED")
                    ?: localFailure
                targetTab.pageDone = CompletableDeferred<String>().also { it.complete(failure) }
                val view = targetTab.webView ?: ensureTabView(targetTab, appCtx)
                if (targetTab.id == activeTabId) _ui.value = _ui.value.copy(loading = false, progress = 0)
                view.loadDataWithBaseURL(normalized, errorPageHtml(normalized, "net::ERR_CONNECTION_REFUSED"), "text/html", "UTF-8", normalized)
                failure
            }
        }
        val host = runCatching { android.net.Uri.parse(normalized).host.orEmpty().removePrefix("www.") }.getOrDefault("")
        var attempt = 0
        while (true) {
            // Ritmo por site: evita rajadas que disparam limite de acesso (rate limit) e bloqueio anti-robô.
            HostPacing.waitTurn(host)
            val target = onMain {
                targetTab.navigationStatus = BrowserNavigationStatus()
                targetTab.pageDone = CompletableDeferred()
                if (targetTab.id == activeTabId) _ui.value = _ui.value.copy(loading = true, progress = 0)
                (targetTab.webView ?: ensureTabView(targetTab, appCtx)).also { ensureSized(it) }.loadUrl(normalized)
                normalized
            }
            val result = awaitLoad(targetTab, target)
            val code = targetTab.navigationStatus.httpStatusCode()
            val blocked = result.startsWith("ERROR: HTTP") && code in setOf(403, 429, 503)
            if (!blocked) { if (!result.startsWith("ERROR")) HostPacing.success(host); return result }
            val cooldown = HostPacing.strike(host)
            // Uma nova tentativa automática depois de esperar; se continuar bloqueado, explica sem induzir repetição.
            if (attempt++ == 0 && cooldown <= 30_000L) continue
            return "ERROR: o site $host está limitando os acessos (HTTP $code) e a página não carregou conteúdo. " +
                "O app já esperou e tentou de novo. Não repita agora: aguarde ~${cooldown / 1000} s (use sleep) ou siga com outra fonte. " +
                "Se o site pedir login, peça ao usuário para entrar uma vez pela aba Browser — o login fica salvo."
        }
    }

    /** Lê readyState e texto visível; usado para esperar páginas que montam o conteúdo depois do load (SPAs). */
    private suspend fun pageState(tab: BrowserTab): Pair<String, Int> {
        val raw = runCatching {
            eval("JSON.stringify({chars:document.body?document.body.innerText.length:0,ready:document.readyState})", 1_500, tab)
        }.getOrDefault("")
        val o = runCatching { JSONObject(raw.trim().removeSurrounding("\"").replace("\\\"", "\"")) }.getOrNull()
            ?: runCatching { JSONObject(raw) }.getOrNull()
        return (o?.optString("ready").orEmpty()) to (o?.optInt("chars", 0) ?: 0)
    }

    /**
     * Espera o conteúdo de verdade: "complete" com texto, ou texto visível que parou de crescer.
     * SPAs como a Biblioteca de Anúncios ficam em "interactive" (conexões abertas) mas já mostram tudo.
     */
    private suspend fun waitForContent(tab: BrowserTab, maxMillis: Long): Pair<String, Int> {
        val deadline = System.currentTimeMillis() + maxMillis
        var last = "" to 0
        var stableRounds = 0
        var completeSince = 0L
        while (System.currentTimeMillis() < deadline) {
            val now = pageState(tab)
            if (now.first == "complete" && completeSince == 0L) completeSince = System.currentTimeMillis()
            stableRounds = if (now.second >= 200 && kotlin.math.abs(now.second - last.second) <= maxOf(20, last.second / 50)) stableRounds + 1 else 0
            last = now
            when {
                now.first == "complete" && now.second > 0 && stableRounds >= 1 -> break
                stableRounds >= 3 -> break                           // ~1,2 s sem crescer: renderizou
                completeSince > 0 && System.currentTimeMillis() - completeSince > 6_000 -> break  // completa, mas sem texto
            }
            kotlinx.coroutines.delay(400)
        }
        return last
    }

    /** Aguarda o callback real do WebView e verifica o DOM; falha e timeout não viram sucesso silencioso. */
    private suspend fun awaitLoad(tab: BrowserTab, target: String = tab.url): String {
        val status = tab.navigationStatus
        val deferred = tab.pageDone ?: return "ERROR: não foi possível acompanhar o carregamento desta navegação."
        val completed = withTimeoutOrNull(45_000) { deferred.await() }
        val result = completed?.let(status::preserveHttpError) ?: status.httpError() ?: run {
            if (tab.id == activeTabId) _ui.value = _ui.value.copy(loading = false)
            // Diagnóstico curto e único: conteúdo visível não significa que a SPA/hydration esteja pronta.
            val raw = runCatching {
                eval("JSON.stringify({chars:document.body?document.body.innerText.length:0,ready:document.readyState,url:location.href})", 1_500, tab)
            }.getOrDefault("")
            val state = runCatching { JSONObject(raw) }.getOrNull()
            val chars = state?.optInt("chars", 0) ?: 0
            val currentUrl = state?.optString("url").orEmpty().ifBlank { tab.url }
            val readyState = state?.optString("ready").orEmpty()
            return status.timeoutMessage(target, currentUrl, readyState, chars)
        }
        val httpCode = status.httpStatusCode()
        if (result.startsWith("ERROR:") && httpCode == null) return result  // falha de rede real
        val (ready, chars) = waitForContent(tab, 20_000)
        if (tab.id == activeTabId) _ui.value = _ui.value.copy(loading = false)
        if (httpCode != null) {
            // HTTP de erro mas a página renderizou de fato: é página válida (o usuário também a vê normal).
            return if (chars >= 200) "OK: página carregada ($target). Aviso: o servidor respondeu HTTP $httpCode, mas a página exibiu $chars caracteres; leia com browser_read antes de concluir que houve bloqueio."
            else result
        }
        return if (ready == "complete" || chars >= 200) "OK: página carregada ($target)" + (if (ready != "complete") " — conteúdo visível ($chars caracteres); a página ainda mantém conexões abertas, o que é normal em sites dinâmicos." else "")
        else {
            val url = runCatching { eval("location.href", 1_500, tab) }.getOrDefault("").trim('"').ifBlank { tab.url }
            status.incompleteMessage(target, url, ready, chars) +
                " Dica: sites dinâmicos às vezes levam mais tempo; chame browser_read uma vez em alguns segundos antes de concluir."
        }
    }

    suspend fun cancelNavigation() = onMain {
        activeTab()?.let { tab ->
            tab.webView?.stopLoading()
            tab.pageDone?.complete("ERROR: navegação cancelada.")
            if (tab.id == activeTabId) _ui.value = _ui.value.copy(loading = false)
        }
    }

    suspend fun back() {
        val tab = onMain { activeTab()?.also { it.navigationStatus = BrowserNavigationStatus(); it.pageDone = CompletableDeferred(); it.webView?.goBack() } }
            ?: return
        awaitLoad(tab)
    }
    suspend fun reload() {
        val tab = onMain { activeTab()?.also { it.navigationStatus = BrowserNavigationStatus(); it.pageDone = CompletableDeferred(); it.webView?.reload() } }
            ?: return
        awaitLoad(tab)
    }

    suspend fun newTab(url: String = ""): String {
        val id = onMain { createTab(appCtx, "about:blank", deferNavigation = true) }
            ?: return "ERROR: limite de ${BrowserSessionPersistence.MAX_TABS} abas por conversa atingido."
        return if (url.isBlank()) "OK: nova aba criada (id=$id)." else navigateInTab(url, id)
    }

    suspend fun selectTabForAgent(tabId: String): String = onMain {
        if (selectTab(tabId)) "OK: aba ativa id=$tabId." else "ERROR: aba não encontrada nesta conversa."
    }

    suspend fun closeTabForAgent(tabId: String): String = onMain {
        if (closeTab(tabId)) "OK: aba id=$tabId fechada." else "ERROR: não foi possível fechar a aba (a última aba não pode ser removida)."
    }

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
        val tab = onMain { activeTab()?.also { lastReadTabId = it.id } }
            ?: return "ERROR: não há aba ativa no browser."
        val raw = eval(js, tab = tab)
        if (raw == "null" || raw == "undefined" || raw.isBlank()) {
            return "Falha ao ler página: o JavaScript de snapshot não retornou dados. Verifique o console do WebView e se o DOM já foi criado."
        }
        return runCatching {
            val o = JSONObject(raw)
            val els = o.getJSONArray("elements")
            buildString {
                append("Aba: ${tab.id}\nURL: ${o.optString("url")}\nTítulo: ${o.optString("title")}\n\n--- Conteúdo ---\n")
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
        val tab = onMain { activeTab()?.takeIf { lastReadTabId == it.id } }
            ?: return "ERRO: leia novamente a aba ativa antes de usar um índice; referências não passam entre abas."
        val r = eval("""(function(){var e=document.querySelector('[data-cue="$index"]');if(!e)return 'elemento $index não encontrado: referência obsoleta; execute browser_read novamente, escolha um índice novo e não repita este';e.scrollIntoView({block:'center'});e.click();return 'ok: clicou em '+(e.innerText||e.value||e.tagName).slice(0,60);})()""", tab = tab)
        awaitLoadIfNavigating(tab)
        return r
    }

    /**
     * Descreve, SEM interagir, o elemento [index] da última leitura: rótulo do controle e, para campos,
     * o texto do botão de envio do formulário. Usado pelo portão de confirmação (comprar/apagar/enviar…).
     * Retorna par (host da aba, rótulo) ou null se não houver aba/elemento.
     */
    suspend fun describe(index: Int): Pair<String, String>? {
        val tab = onMain { activeTab() } ?: return null
        val host = runCatching { android.net.Uri.parse(tab.url).host.orEmpty().removePrefix("www.") }.getOrDefault("")
        val raw = runCatching {
            eval("""(function(){var e=document.querySelector('[data-cue="$index"]');if(!e)return '';
                function L(x){return ((x.innerText||x.value||x.getAttribute('aria-label')||x.title||x.placeholder||x.name||'')+'').trim().replace(/\s+/g,' ').slice(0,90);}
                var t=L(e); if((e.tagName=='INPUT'||e.tagName=='TEXTAREA')&&e.form){var b=e.form.querySelector('button[type=submit],input[type=submit],button:not([type])'); if(b) t=L(b)+' · '+t;}
                return t;})()""".trimIndent(), tab = tab)
        }.getOrNull().orEmpty().trim().removeSurrounding("\"")
        return if (raw.isBlank()) null else host to raw
    }

    suspend fun type(index: Int, text: String, submit: Boolean): String {
        val tab = onMain { activeTab()?.takeIf { lastReadTabId == it.id } }
            ?: return "ERRO: leia novamente a aba ativa antes de usar um índice; referências não passam entre abas."
        val t = JSONObject.quote(text)
        val r = eval("""
            (function(){var e=document.querySelector('[data-cue="$index"]');if(!e)return 'elemento $index não encontrado';
            e.focus(); var setter=Object.getOwnPropertyDescriptor(Object.getPrototypeOf(e),'value');
            if(setter&&setter.set){setter.set.call(e,$t);}else{e.value=$t;}
            e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));
            ${if (submit) "if(e.form){e.form.requestSubmit?e.form.requestSubmit():e.form.submit();}else{e.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',keyCode:13,bubbles:true}));}" else ""}
            return 'ok';})()
        """.trimIndent(), tab = tab)
        if (submit) awaitLoadIfNavigating(tab)
        return r
    }

    suspend fun scroll(down: Boolean): String {
        val tab = onMain { activeTab() } ?: return "ERROR: não há aba ativa no browser."
        return eval("window.scrollBy(0, ${if (down) "" else "-"}window.innerHeight*0.8); 'ok'", tab = tab)
    }

    suspend fun runJs(code: String): String {
        val tab = onMain { activeTab() } ?: return "ERROR: não há aba ativa no browser."
        return eval(code, tab = tab)
    }

    private suspend fun awaitLoadIfNavigating(tab: BrowserTab) {
        kotlinx.coroutines.delay(800)
        // progress é método do WebView: só pode ser lido na main thread (evita o crash
        // "A WebView method was called on thread 'DefaultDispatcher-worker'").
        val navigating = onMain { tab.webView?.progress?.let { it < 100 } == true }
        if (navigating) {
            onMain { if (tab.pageDone == null || tab.pageDone?.isCompleted == true) tab.pageDone = CompletableDeferred() }
            awaitLoad(tab)
        }
    }

    private suspend fun eval(js: String, timeoutMillis: Long = 15_000, tab: BrowserTab? = null): String {
        val d = CompletableDeferred<String>()
        val view = onMain {
            val target = tab ?: activeTab()
            target?.let { ensureTabView(it, appCtx) }
        } ?: return "ERROR: não há aba ativa no browser."
        onMain { view.evaluateJavascript(js) { d.complete(it ?: "null") } }
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

/**
 * Ritmo de navegação por site, compartilhado por todos os chats/agentes (o login também é compartilhado,
 * então o site enxerga um único visitante). Intervalo mínimo entre aberturas e recuo crescente após 403/429.
 */
internal object HostPacing {
    private const val MIN_GAP_MS = 2_500L
    private const val MAX_WAIT_MS = 45_000L
    private data class State(var lastAt: Long = 0, var cooldownUntil: Long = 0, var strikes: Int = 0)
    private val hosts = java.util.concurrent.ConcurrentHashMap<String, State>()

    /** Tempo a esperar antes de abrir o host agora (puro, para testes). */
    fun delayFor(host: String, now: Long = System.currentTimeMillis()): Long {
        val st = hosts[host] ?: return 0
        return (maxOf(st.lastAt + MIN_GAP_MS, st.cooldownUntil) - now).coerceIn(0, MAX_WAIT_MS)
    }

    suspend fun waitTurn(host: String) {
        if (host.isBlank()) return
        val wait = delayFor(host)
        if (wait > 0) kotlinx.coroutines.delay(wait)
        hosts.getOrPut(host) { State() }.lastAt = System.currentTimeMillis()
    }

    /** Registra bloqueio e devolve o recuo aplicado: 10 s, 30 s, 60 s, 120 s… (máx. 5 min). */
    fun strike(host: String, now: Long = System.currentTimeMillis()): Long {
        val st = hosts.getOrPut(host) { State() }
        st.strikes++
        val backoff = when (st.strikes) { 1 -> 10_000L; 2 -> 30_000L; 3 -> 60_000L; else -> minOf(300_000L, 120_000L * (st.strikes - 3)) }
        st.cooldownUntil = now + backoff
        return backoff
    }

    fun success(host: String) { hosts[host]?.let { it.strikes = 0; it.cooldownUntil = 0 } }
}
