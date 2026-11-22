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
class BrowserHost(private val appCtx: Context, private val sessionId: String = "__legacy__") {

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
        /** Hash da última leitura (detecta leituras repetidas sem progresso). */
        var lastSnapshotDigest: Int? = null,
        /** Linha do tempo da navegação atual (para o log de erros). */
        val diag: NavDiag = NavDiag(),
        /** Último uso (para estacionar WebViews ociosos e liberar memória). */
        var lastUsed: Long = System.currentTimeMillis(),
        /** OpenBot 0.71: a aba foi recarregada sem o agente pedir (scripts/formulários perdidos) — vai na próxima leitura. */
        var reloadNote: String? = null,
    )

    /** Linha do tempo de uma navegação: eventos do WebView com o tempo desde o início. */
    internal class NavDiag {
        @Volatile var startedAt = System.currentTimeMillis(); private set
        @Volatile var target = ""; private set
        private val events = java.util.concurrent.ConcurrentLinkedDeque<String>()
        fun reset(url: String) { startedAt = System.currentTimeMillis(); target = url; events.clear() }
        fun add(e: String) {
            events.addLast("+${(System.currentTimeMillis() - startedAt) / 100 / 10.0}s ${e.take(220)}")
            while (events.size > 30) events.pollFirst()
        }
        fun lines(): List<String> = events.toList()
    }

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

    // ───────── Demonstração ("ensine seu agente"): passos do usuário no browser, sem o que ele digita ─────────
    private val _demoSteps = MutableStateFlow<List<String>?>(null)
    /** null = não está gravando; lista = passos gravados até agora. */
    val demoSteps: StateFlow<List<String>?> = _demoSteps
    @Volatile private var demoPrefix: String? = null

    /** Começa a gravar na aba ativa. O prefixo aleatório identifica os eventos desta gravação no console. */
    fun startDemo() {
        val prefix = "__CUE_DEMO_" + java.util.UUID.randomUUID().toString().replace("-", "") + "__"
        demoPrefix = prefix
        _demoSteps.value = emptyList()
        runCatching { activeTab()?.webView?.let { v ->
            v.url?.takeIf { it.startsWith("http") }?.let { addDemoStep(DemoRecorder.navigation(it)) }
            v.evaluateJavascript(DemoRecorder.script(prefix), null)
        } }
    }

    /** Encerra a gravação e devolve os passos. */
    fun stopDemo(): List<String> {
        val steps = _demoSteps.value.orEmpty()
        demoPrefix = null
        _demoSteps.value = null
        return steps
    }

    private fun addDemoStep(step: String) { _demoSteps.value?.let { _demoSteps.value = DemoRecorder.append(it, step) } }
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
        // A UI vai exibir o WebView: tira do palco invisível do agente antes de anexar na tela.
        return ensureTabView(tab, ctx).also { OffscreenStage.release(it) }
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
        tab.lastUsed = System.currentTimeMillis()
        tab.webView?.let { return it }
        return createView(ctx, tab).also { view ->
            tab.webView = view
            ensureSized(view)
            if (_ui.value.desktop) view.settings.userAgentString = DESKTOP_UA
            if (restore) restoreViewState(tab, view)
            trimLiveViews(keep = tab.id)
        }
    }

    /**
     * Mantém no máximo [MAX_LIVE_VIEWS] WebViews vivos por conversa. Cada aba do Facebook é um motor
     * Chromium inteiro: com 12 abas abertas a memória acaba e uma aba trava (o log mostrava uma aba presa
     * em onPageStarted enquanto outras carregavam). Abas ociosas são "estacionadas": o histórico é salvo e
     * o WebView liberado; ao voltar para ela, é recriado e restaurado.
     */
    private fun trimLiveViews(keep: String) {
        val live = tabs.values.filter { it.webView != null }
        if (live.size <= MAX_LIVE_VIEWS) return
        live.filter { it.id != keep && it.id != activeTabId }
            .filter { t -> t.webView?.let { v -> !v.isAttachedToWindow || OffscreenStage.isStaged(v) } == true } // não tira da tela
            .sortedBy { it.lastUsed }
            .take(live.size - MAX_LIVE_VIEWS)
            .forEach { parkTab(it) }
    }

    /** Libera o WebView da aba guardando o histórico (recriado sob demanda). */
    private fun parkTab(tab: BrowserTab) {
        val v = tab.webView ?: return
        runCatching { persistWebViewState(tab, v) }
        runCatching { v.stopLoading(); (v.parent as? android.view.ViewGroup)?.removeView(v); v.destroy() }
        tab.webView = null
        tab.lastSnapshotDigest = null
        tab.reloadNote = "a aba ficou ociosa e foi liberada para economizar memória; ao voltar, a página foi carregada de novo"
    }

    /** Recria o motor de uma aba travada (sem histórico corrompido): a próxima navegação começa limpa. */
    private fun recreateTabView(tab: BrowserTab) {
        val v = tab.webView
        runCatching { v?.stopLoading(); (v?.parent as? android.view.ViewGroup)?.removeView(v); v?.destroy() }
        tab.webView = null
        tab.lastSnapshotDigest = null
        tab.reloadNote = "a aba travou e o motor dela foi recriado"
        tab.pageDone?.complete("ERROR: aba recriada")
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
        val dm = appCtx.resources.displayMetrics
        val w = dm.widthPixels.coerceAtLeast(360)
        val h = (dm.heightPixels - (dm.density * 180).toInt()).coerceAtLeast(480) // menos barras/abas do Computador
        // Fora de uma janela o Chromium trata a página como escondida e SPAs não terminam de montar.
        if (!v.isAttachedToWindow && OffscreenStage.attach(v, w, h)) return
        if (v.width > 0 && v.height > 0) return
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
        // Nunca "escondido" para o Chromium: a página continua viva com o app em segundo plano.
        val v = AgentWebView(ctx)
        runCatching { v.resumeTimers() }
        // Perfil persistente de contas. Sem suporte a perfis, o perfil padrão do WebView já é único e
        // persistente — serve igual, então a rede nunca é bloqueada por isso.
        runCatching {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) WebViewCompat.setProfile(v, profileName)
        }
        val isolated = true
        _ui.value = _ui.value.copy(profileIsolated = isolated)
        // Ponte do GM_xmlhttpRequest para os User Scripts (só responde a quem tem o token do envelope).
        if (sessionId != BrowserFeatures.LOGIN_BROWSER_KEY) BrowserFeatures.installBridge(v)
        // Downloads da página vão para downloads/ do workspace da tarefa e o agente recebe o resultado.
        if (sessionId != BrowserFeatures.LOGIN_BROWSER_KEY) v.setDownloadListener { dUrl, ua, cd, mime, len ->
            runCatching {
                val sb = dev.cueopen.app.CueApp.instance.sandbox
                BrowserDownloads.start(sessionId, sb.workspaceFor(sessionId), sb.guestPath(sessionId), dUrl, ua, cd, mime, len)
            }
        }
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
            setGeolocationEnabled(true)  // o site só recebe a localização se o usuário permitir (BrowserPermissions)
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
        // Rastreador de prontidão (técnica do browser-use/Playwright "networkidle"): instalado ANTES dos
        // scripts da página quando o WebView suporta; senão, injetado no início de cada carregamento.
        val docStart = runCatching {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(v, READINESS_JS + "\n" + dev.cueopen.app.web.LoadBoost.START_JS, setOf("*")); true
            } else false
        }.getOrDefault(false)
        v.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                tab.diag.add("onPageStarted $url")
                failedTabs.remove(tab.id)
                if (!docStart) runCatching { view.evaluateJavascript(READINESS_JS + "\n" + dev.cueopen.app.web.LoadBoost.START_JS, null) }
                tab.progress = 0
                if (tab.id == activeTabId) lastReadTabId = null
                sync(view, loading = true)
            }
            override fun onPageCommitVisible(view: WebView, url: String) {
                tab.diag.add("onPageCommitVisible (primeiro quadro)")
                // Algumas SPAs mantêm requisições abertas e nunca disparam onPageFinished. Um
                // primeiro quadro visível permite inspecionar o DOM sem esperar 45 s, mas não é
                // tratado como prontidão funcional.
                tab.pageDone?.complete(tab.navigationStatus.pageCommitted(url))
                persistWebViewState(tab, view)
                // @run-at document-start: roda no primeiro quadro (o WebView não permite antes disso de forma confiável).
                runCatching { BrowserFeatures.applyUserScripts(view, url, sessionId, early = true) }
            }
            override fun onPageFinished(view: WebView, url: String) {
                demoPrefix?.let { p -> runCatching { view.evaluateJavascript(DemoRecorder.script(p), null) } }
                tab.diag.add("onPageFinished $url")
                sync(view, loading = false)
                tab.pageDone?.complete(tab.navigationStatus.pageFinished())
                collectTimings(tab, view)
                installSnapshotInstrumentation(view)
                installNetworkInstrumentation(view)
                persistWebViewState(tab, view)
                // grava o login no disco a cada página carregada: sobrevive a o app ser morto pelo Android
                runCatching { cookieManagerFor(view).flush() }
                // User Scripts do usuário (seção Browser) que valem para este domínio/agente.
                runCatching { BrowserFeatures.applyUserScripts(view, url, sessionId) }
                // Exército de agentes: cursores voltam após cada recarga do preview.
                if (tab.id !in failedTabs) squadOverlay?.let { j -> runCatching { view.evaluateJavascript(dev.cueopen.app.agent.BuildSquad.overlayScript(j), null) } }
            }
            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                if (demoPrefix != null && !isReload && url.startsWith("http")) addDemoStep(DemoRecorder.navigation(url))
                // SPA (pushState) muda a URL sem recarregar: scripts com filtro de caminho podem passar a valer.
                // O envelope evita injeção duplicada no mesmo documento.
                if (!isReload) runCatching { BrowserFeatures.applyUserScripts(view, url, sessionId) }
            }
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): android.webkit.WebResourceResponse? {
                // alimenta o painel de rede; rastreadores/anúncios respondem vazio (carrega mais rápido)
                val url = request.url.toString()
                if (dev.cueopen.app.web.LoadBoost.shouldBlock(url, request.isForMainFrame))
                    return android.webkit.WebResourceResponse("text/plain", "utf-8", 204, "No Content", emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)))
                if (!url.startsWith("data:")) addNet(NetEntry(netSeq.incrementAndGet(), url, request.method ?: "GET",
                    guessType(url, request.requestHeaders["Accept"], request.isForMainFrame), request.isForMainFrame, System.currentTimeMillis(),
                    requestHeaders = request.requestHeaders.toMap(), tabId = tab.id))
                return null
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: android.webkit.WebResourceResponse) {
                patchNet(request.url.toString(), tab.id) { it.copy(status = errorResponse.statusCode) }
                if (request.isForMainFrame) tab.diag.add("HTTP ${errorResponse.statusCode} na página principal ${request.url}")
                if (request.isForMainFrame && errorResponse.statusCode >= 400) failedTabs.add(tab.id)
                // Só registra: vários sites (Meta, Cloudflare) respondem 403/429 e mesmo assim renderizam a página.
                // A decisão (erro x página válida) é tomada em awaitLoad pelo conteúdo realmente visível.
                tab.navigationStatus.recordHttpError(request.isForMainFrame, errorResponse.statusCode)
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                patchNet(request.url.toString(), tab.id) { it.copy(error = error.description?.toString()) }
                if (request.isForMainFrame) tab.diag.add("erro de rede ${error.errorCode} ${error.description} em ${request.url}")
                if (request.isForMainFrame) failedTabs.add(tab.id)
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
            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                // O processo do Chromium morreu (memória/crash). Sem tratar, o Android encerra o app inteiro.
                val crashed = if (android.os.Build.VERSION.SDK_INT >= 26) detail.didCrash() else true
                synchronized(rendererCrashes) { rendererCrashes.add(System.currentTimeMillis()); while (rendererCrashes.size > 10) rendererCrashes.removeAt(0) }
                tab.diag.add("processo do renderer encerrado (" + (if (crashed) "crash" else "falta de memória") + ")")
                runCatching { dev.cueopen.app.diag.ErrorLog.log("browser", "Renderer do WebView encerrado (" + (if (crashed) "crash" else "memória") + ") na aba ${tab.id}; a aba será recriada.\n" + tab.diag.lines().joinToString("\n")) }
                tab.pageDone?.complete("ERROR: o motor do browser foi encerrado pelo Android (" + (if (crashed) "falha" else "memória") + "). A aba será recriada; abra a página de novo uma vez.")
                runCatching { (view.parent as? android.view.ViewGroup)?.removeView(view); view.destroy() }
                if (tab.webView === view) tab.webView = null
                tab.reloadNote = "o Android encerrou o motor do browser (" + (if (crashed) "falha" else "memória") + ") e a aba foi recriada"
                return true
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.isForMainFrame) tab.diag.add((if (request.isRedirect) "redirecionamento → " else "navegação pela página → ") + request.url)
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
            // Recursos do aparelho pedidos pelo site: o usuário decide (e o Android concede, se preciso).
            override fun onPermissionRequest(request: android.webkit.PermissionRequest) = BrowserPermissions.onWebPermission(appCtx, request)
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: android.webkit.GeolocationPermissions.Callback) =
                BrowserPermissions.onGeolocation(appCtx, origin, callback)
            override fun onShowFileChooser(view: WebView, cb: android.webkit.ValueCallback<Array<android.net.Uri>>, params: FileChooserParams): Boolean =
                BrowserPermissions.onFileChooser(cb, params)
            override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                demoPrefix?.let { p ->
                    val msg = m.message().orEmpty()
                    if (msg.startsWith(p)) { DemoRecorder.describe(msg.removePrefix(p))?.let { addDemoStep(it) }; return true }
                }
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
        /**
         * Rastreador de prontidão instalado no início do documento: conta requisições fetch/XHR em andamento
         * (ignorando rastreadores/beacons e polling com mais de 10 s), guarda o momento da última atividade de
         * rede e da última mudança no DOM. `window.__cueLoadState()` devolve o estado para a espera por estabilidade.
         */
        internal val READINESS_JS = """
            (function(){ if (window.__cueLoad) return;
              var st = { reqs: {}, urls: {}, seq: 0, lastNet: Date.now(), lastMut: Date.now() }; window.__cueLoad = st;
              var ign = /doubleclick|googlesyndication|googletagmanager|google-analytics|analytics\.|hotjar|clarity\.ms|mixpanel|segment\.(io|com)|newrelic|nr-data|sentry|\/beacon|\/collect\b|\/telemetry|\/metrics|\/tracker|\/track\b|\/log(ging)?\b|\/ajax\/bz|\/ajax\/webstorage|\/tr\/?\?/i;
              function start(u){ u = String(u||''); if (ign.test(u)) return null; var id = ++st.seq; st.reqs[id] = Date.now(); st.urls[id] = u.slice(0, 120); st.lastNet = Date.now(); return id; }
              function end(id){ if (id != null) { delete st.reqs[id]; delete st.urls[id]; } st.lastNet = Date.now(); }
              try { var of = window.fetch; if (of) window.fetch = function(i, o){ var id = start((i && i.url) || i);
                return of.apply(this, arguments).then(function(r){ end(id); return r; }, function(e){ end(id); throw e; }); }; } catch(e) {}
              try { var X = XMLHttpRequest.prototype, op = X.open, sd = X.send;
                X.open = function(m, u){ this.__cueU = u; return op.apply(this, arguments); };
                X.send = function(){ var x = this, id = start(x.__cueU); x.addEventListener('loadend', function(){ end(id); }); return sd.apply(this, arguments); }; } catch(e) {}
              function observe(){ try { new MutationObserver(function(){ st.lastMut = Date.now(); })
                .observe(document.documentElement || document, { childList: true, subtree: true, characterData: true }); } catch(e) {} }
              if (document.documentElement) observe(); else document.addEventListener('readystatechange', observe, { once: true });
              window.__cueLoadState = function(){ var now = Date.now(), n = 0;
                for (var k in st.reqs) { if (now - st.reqs[k] < 10000) n++; }   // polling/streaming > 10 s não segura a leitura
                var se = document.scrollingElement || document.documentElement;
                return JSON.stringify({ ready: document.readyState, chars: document.body ? document.body.innerText.length : 0,
                  inflight: n, net: now - st.lastNet, mut: now - st.lastMut, vis: document.visibilityState,
                  pend: Object.keys(st.reqs).slice(0, 4).map(function(k){ return st.urls[k] || ''; }),
                  sh: se ? se.scrollHeight : 0, ch: window.innerHeight, sy: se ? se.scrollTop : 0 }); };
            })();
        """.trimIndent()

        const val HOME = "file:///android_asset/start/index.html"
        /** Marca de leitura sem texto e sem controles (canvas, app JS que não renderizou, página em branco). */
        const val EMPTY_PAGE_MARK = "(Página sem texto e sem controles legíveis.)"
        const val MAX_TABS = BrowserSessionPersistence.MAX_TABS
        /** WebViews vivos ao mesmo tempo por conversa (o resto fica estacionado). */
        const val MAX_LIVE_VIEWS = 3
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
            // O Reddit recusa (403) os endereços .json sem login; a página normal do mesmo endereço carrega.
            Regex("^https?://([a-z0-9-]+\\.)?reddit\\.com/", RegexOption.IGNORE_CASE).containsMatchIn(url) && url.substringBefore('?').substringBefore('#').endsWith(".json", true) ->
                url.substringBefore('?').substringBefore('#').dropLast(5) + (if ('?' in url) "?" + url.substringAfter('?').substringBefore('#') else "")
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
                tab.lastSnapshotDigest = null
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
                targetTab.diag.reset(normalized)
                targetTab.diag.add("loadUrl (tentativa ${attempt + 1})")
                targetTab.navigationStatus = BrowserNavigationStatus()
                targetTab.pageDone = CompletableDeferred()
                if (targetTab.id == activeTabId) _ui.value = _ui.value.copy(loading = true, progress = 0)
                val v = (targetTab.webView ?: ensureTabView(targetTab, appCtx)).also { ensureSized(it) }
                // Recém-colocado no palco, o WebView só reporta "anexado" no próximo quadro: estar no palco já basta.
                if (!v.isAttachedToWindow && !OffscreenStage.isStaged(v)) {
                    targetTab.diag.add("sem janela: display virtual e sobreposição indisponíveis — navegação abortada")
                    null
                } else { v.loadUrl(normalized); normalized }
            }
            if (target == null) {
                if (targetTab.id == activeTabId) onMain { _ui.value = _ui.value.copy(loading = false) }
                runCatching { dev.cueopen.app.diag.ErrorLog.log("browser", "Navegação abortada sem janela: $normalized") }
                return "ERROR: browser indisponível com o app fechado (o aparelho recusou o display virtual e não há permissão de sobreposição). " +
                    "Peça ao usuário para abrir o app ou conceder 'Sobrepor a outros apps'. Para página local use curl no shell. Não tente navegar de novo agora."
            }
            val result = awaitLoad(targetTab, target)
            // Aba travada: nem primeiro quadro nem fim em 30 s (o motor dela parou). Recria e tenta 1×.
            val stuck = result.startsWith("TIMEOUT") &&
                targetTab.diag.lines().none { it.contains("onPageCommitVisible") || it.contains("onPageFinished") }
            if (stuck && attempt == 0) {
                attempt++
                runCatching { dev.cueopen.app.diag.ErrorLog.log("browser", "Aba ${targetTab.id} travada (só onPageStarted em 30 s); motor recriado e nova tentativa.\n" + targetTab.diag.lines().joinToString("\n")) }
                onMain { recreateTabView(targetTab) }
                continue
            }
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

    /** Estado de prontidão da página (rede, DOM, rolagem). */
    internal data class PageReadiness(
        val ready: String = "", val chars: Int = 0, val inflight: Int = 0,
        val netQuietMs: Long = 0, val domQuietMs: Long = 0,
        val scrollHeight: Int = 0, val viewport: Int = 0, val scrollY: Int = 0, val tracked: Boolean = false,
        val visibility: String = "", val pending: List<String> = emptyList(),
    ) {
        val pagesAbove: Double get() = if (viewport > 0) scrollY.toDouble() / viewport else 0.0
        val pagesBelow: Double get() = if (viewport > 0) ((scrollHeight - scrollY - viewport).coerceAtLeast(0)).toDouble() / viewport else 0.0
    }

    private suspend fun readiness(tab: BrowserTab): PageReadiness {
        val raw = runCatching {
            eval("(function(){try{if(window.__cueLoadState)return window.__cueLoadState();}catch(e){}" +
                "var se=document.scrollingElement||document.documentElement;return JSON.stringify({ready:document.readyState,chars:document.body?document.body.innerText.length:0," +
                "sh:se?se.scrollHeight:0,ch:window.innerHeight,sy:se?se.scrollTop:0});})()", 2_000, tab)
        }.getOrDefault("")
        val o = runCatching { JSONObject(raw) }.getOrNull()
            ?: runCatching { JSONObject(raw.trim().removeSurrounding("\"").replace("\\\"", "\"")) }.getOrNull()
            ?: return PageReadiness()
        val tracked = o.has("inflight")
        return PageReadiness(
            ready = o.optString("ready"), chars = o.optInt("chars"), inflight = o.optInt("inflight"),
            netQuietMs = o.optLong("net", if (tracked) 0 else 60_000), domQuietMs = o.optLong("mut", if (tracked) 0 else 60_000),
            scrollHeight = o.optInt("sh"), viewport = o.optInt("ch"), scrollY = o.optInt("sy"), tracked = tracked,
            visibility = o.optString("vis"),
            pending = o.optJSONArray("pend")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty(),
        )
    }

    /**
     * Espera a página ESTABILIZAR antes de qualquer leitura (padrão browser-use/Playwright):
     *  - espera mínima de 500 ms;
     *  - rede ociosa: nenhuma requisição relevante em andamento (rastreadores e polling > 10 s ignorados)
     *    ou 1,5 s sem atividade de rede;
     *  - DOM parado há 700 ms (MutationObserver) e algum texto visível;
     *  - condições verdadeiras em 2 leituras seguidas.
     * Não depende de readyState=complete (Meta e outras SPAs mantêm o documento em streaming).
     */
    private suspend fun waitForStable(tab: BrowserTab, maxMillis: Long): PageReadiness {
        val started = System.currentTimeMillis()
        var last = PageReadiness()
        var okRounds = 0
        while (System.currentTimeMillis() - started < maxMillis) {
            val now = readiness(tab)
            last = now
            val elapsed = System.currentTimeMillis() - started
            val netIdle = now.inflight == 0 || now.netQuietMs >= 1_500
            val domIdle = now.domQuietMs >= 700
            val hasContent = now.chars > 0 || now.ready == "complete"
            val stable = if (now.tracked) netIdle && domIdle && hasContent
                else (now.ready == "complete" && now.chars > 0) // sem rastreador: comportamento conservador
            okRounds = if (stable && elapsed >= 500) okRounds + 1 else 0
            if (okRounds >= 2) break
            kotlinx.coroutines.delay(300)
        }
        return last
    }

    /**
     * Diagnóstico detalhado da aba ativa para o log de erros (Configurações › Logs): linha do tempo,
     * redirecionamentos, estado do documento, requisições pendentes, visibilidade, janela e permissões.
     */
    suspend fun diagnostics(): String {
        val tab = onMain { activeTab() } ?: return "Diagnóstico: sem aba ativa."
        val st = runCatching { readiness(tab) }.getOrDefault(PageReadiness())
        val (placement, window, url, ua) = onMain {
            val v = tab.webView
            listOf(
                v?.let { OffscreenStage.placement(it) } ?: "WebView ainda não criado",
                (v as? AgentWebView)?.windowVisibilityLabel() ?: "-",
                v?.url ?: tab.url,
                v?.settings?.userAgentString?.let { it.substringAfter("Chrome/").substringBefore(' ').let { c -> "Chrome $c" } } ?: "-",
            )
        }
        val webViewPkg = runCatching { WebViewCompat.getCurrentWebViewPackage(appCtx)?.let { "${it.packageName} ${it.versionName}" } }.getOrNull() ?: "?"
        val errors = _console.value.filter { it.tabId == tab.id && (it.level == "error" || it.level == "warning") }.takeLast(3)
        return buildString {
            appendLine("── DIAGNÓSTICO DO BROWSER ──")
            appendLine("Alvo: ${tab.diag.target.take(200)}")
            appendLine("URL atual: ${url.take(200)}")
            appendLine("Tempo desde o início: ${(System.currentTimeMillis() - tab.diag.startedAt) / 1000}s")
            appendLine("Documento: readyState=${st.ready.ifBlank { "?" }} · visibilityState=${st.visibility.ifBlank { "?" }} · texto=${st.chars} caracteres · rolagem=${st.scrollHeight}px")
            if (st.tracked) appendLine("Rede: ${st.inflight} requisição(ões) pendente(s) · ${st.netQuietMs / 1000.0}s sem rede · DOM parado há ${st.domQuietMs / 1000.0}s")
            else appendLine("Rede: rastreador de prontidão ausente nesta página (script não injetado)")
            st.pending.filter { it.isNotBlank() }.forEach { appendLine("  pendente: $it") }
            appendLine("Status HTTP principal: ${tab.navigationStatus.httpStatusCode() ?: "OK/sem erro"}")
            appendLine("App: ${if (OffscreenStage.foreground) "em primeiro plano" else "em SEGUNDO PLANO"} · WebView: $placement · $window")
            appendLine("Permissão de sobreposição: ${if (OffscreenStage.overlayPermission(appCtx)) "concedida" else "NÃO concedida (sem ela o browser pode parar com o app fechado)"}")
            appendLine("Motor: $webViewPkg · UA $ua · perfil de contas compartilhado")
            if (errors.isNotEmpty()) { appendLine("Console (últimos erros):"); errors.forEach { appendLine("  [${it.level}] ${it.message.take(160)} (${it.source})") } }
            appendLine("Linha do tempo:")
            tab.diag.lines().forEach { appendLine("  $it") }
            append("Causa provável: ").append(probableCause(st, placement))
        }
    }

    private fun probableCause(st: PageReadiness, placement: String): String = when {
        placement.startsWith("sem janela") -> "o WebView estava fora de qualquer janela; o Chromium pausa páginas assim. Abra o app ou conceda a permissão de sobreposição."
        st.visibility == "hidden" -> "a página está marcada como escondida pelo sistema (app em segundo plano)."
        st.ready == "loading" && st.chars == 0 -> "o documento principal não terminou de chegar (rede lenta, bloqueio do site ou resposta em streaming)."
        st.inflight > 0 -> "a página ainda aguardava dados (requisições pendentes acima)."
        st.chars < 200 -> "a página carregou quase sem conteúdo: possível parede de login/anti-robô."
        else -> "a página tem conteúdo; o problema é de interpretação/ação, não de carregamento."
    }

    /**
     * Espera um texto aparecer ou sumir (como o browser_wait_for do Playwright MCP), até 30 s.
     * Alternativa correta a reabrir a página quando os dados ainda estão chegando.
     */
    suspend fun waitFor(text: String?, textGone: String?, seconds: Int): String {
        val tab = onMain { activeTab() } ?: return "ERROR: não há aba ativa no browser."
        val limit = seconds.coerceIn(1, 30) * 1000L
        val started = System.currentTimeMillis()
        val t = text?.trim()?.takeIf { it.isNotEmpty() }
        val g = textGone?.trim()?.takeIf { it.isNotEmpty() }
        if (t == null && g == null) {
            kotlinx.coroutines.delay(limit)
            val st = waitForStable(tab, 3_000)
            return "ok: esperou ${limit / 1000}s; página com ${st.chars} caracteres (readyState=${st.ready})."
        }
        while (System.currentTimeMillis() - started < limit) {
            val js = "(function(){var b=(document.body?document.body.innerText:'').toLowerCase();return JSON.stringify({" +
                "has:${if (t != null) "b.indexOf(${JSONObject.quote(t.lowercase())})>=0" else "false"}," +
                "gone:${if (g != null) "b.indexOf(${JSONObject.quote(g.lowercase())})<0" else "false"}});})()"
            val o = runCatching { JSONObject(eval(js, 2_000, tab)) }.getOrNull()
            val secs = (System.currentTimeMillis() - started) / 1000.0
            if (o?.optBoolean("has") == true) return "ok: o texto \"$t\" apareceu em ${secs}s."
            if (o?.optBoolean("gone") == true) return "ok: o texto \"$g\" sumiu em ${secs}s."
            kotlinx.coroutines.delay(400)
        }
        val st = readiness(tab)
        return "TIMEOUT: ${if (t != null) "o texto \"$t\" não apareceu" else "o texto \"$g\" não sumiu"} em ${limit / 1000}s " +
            "(página: readyState=${st.ready}, ${st.chars} caracteres, ${st.inflight} requisição(ões) pendente(s)). Leia o snapshot abaixo antes de decidir; não reabra a página."
    }

    /** Compat: chamadas antigas esperavam (readyState, caracteres). */
    private suspend fun waitForContent(tab: BrowserTab, maxMillis: Long): Pair<String, Int> =
        waitForStable(tab, maxMillis).let { it.ready to it.chars }

    /**
     * Aguarda a navegação no estilo do Playwright MCP:
     *  1. primeiro sinal do WebView (commit/fim/erro) — até 30 s;
     *  2. DOMContentLoaded (readyState ≠ loading) — até 10 s;
     *  3. evento load — no máximo 5 s ("a página já está operacional");
     *  4. página estável (rede ociosa + DOM parado) — até 8 s.
     * Lentidão não vira erro: só falha quando não há sinal NENHUM e nenhum conteúdo visível.
     */
    private suspend fun awaitLoad(tab: BrowserTab, target: String = tab.url): String {
        val status = tab.navigationStatus
        val deferred = tab.pageDone ?: return "ERROR: não foi possível acompanhar o carregamento desta navegação."
        val completed = withTimeoutOrNull(30_000) { deferred.await() }
        val result = completed?.let(status::preserveHttpError) ?: status.httpError() ?: run {
            tab.diag.add("30s sem sinal do WebView; conferindo conteúdo")
            val st = waitForStable(tab, 6_000)
            if (tab.id == activeTabId) _ui.value = _ui.value.copy(loading = false)
            if (st.chars >= 200) return "OK: página carregada ($target) — ${st.chars} caracteres visíveis; o site mantém o carregamento aberto, o que é normal."
            val currentUrl = runCatching { eval("location.href", 1_500, tab) }.getOrDefault("").trim('"').ifBlank { tab.url }
            return status.timeoutMessage(target, currentUrl, st.ready, st.chars) +
                " Detalhes completos no log de erros (Configurações › Logs). Não reabra a mesma página: use browser_wait_for ou outra fonte."
        }
        val httpCode = status.httpStatusCode()
        if (result.startsWith("ERROR:") && httpCode == null) return result  // falha de rede real
        // 2) DOMContentLoaded
        val dclDeadline = System.currentTimeMillis() + 10_000
        var st = readiness(tab)
        while (st.ready == "loading" && System.currentTimeMillis() < dclDeadline) { kotlinx.coroutines.delay(300); st = readiness(tab) }
        tab.diag.add("readyState=${st.ready} (DOMContentLoaded ${if (st.ready != "loading") "ok" else "não chegou"})")
        // 3) load, no máximo 5 s
        val loadDeadline = System.currentTimeMillis() + 5_000
        while (st.ready != "complete" && System.currentTimeMillis() < loadDeadline) { kotlinx.coroutines.delay(300); st = readiness(tab) }
        // 4) estabilidade
        st = waitForStable(tab, 8_000)
        tab.diag.add("estável: readyState=${st.ready}, ${st.chars} caracteres, ${st.inflight} pendente(s)")
        if (tab.id == activeTabId) _ui.value = _ui.value.copy(loading = false)
        if (httpCode != null) {
            return if (st.chars >= 200) "OK: página carregada ($target). Aviso: o servidor respondeu HTTP $httpCode, mas a página exibiu ${st.chars} caracteres; leia o snapshot antes de concluir que houve bloqueio."
            else result
        }
        return when {
            st.ready == "complete" || st.chars >= 200 -> "OK: página carregada ($target)" +
                (if (st.ready != "complete") " — conteúdo visível (${st.chars} caracteres); o site mantém conexões abertas, o que é normal." else "")
            else -> {
                val url = runCatching { eval("location.href", 1_500, tab) }.getOrDefault("").trim('"').ifBlank { tab.url }
                status.incompleteMessage(target, url, st.ready, st.chars) +
                    " Use browser_wait_for (texto esperado) em vez de reabrir; detalhes no log de erros."
            }
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
    /** Estado do exército de agentes desenhado sobre a página (null = sem overlay). */
    @Volatile var squadOverlay: String? = null
        private set
    /** Abas cuja página principal falhou (erro de rede/HTTP): nada de cursores sobre a tela de erro do WebView. */
    private val failedTabs = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** Atualiza (ou remove) os cursores dos agentes na aba ativa, sem esperar resposta. */
    private val rendererCrashes = mutableListOf<Long>()

    /** O motor caiu 2+ vezes nos últimos 5 min: modo leve (sem cursores/animações do time) para não derrubar de novo. */
    fun liteMode(): Boolean = synchronized(rendererCrashes) { rendererCrashes.count { System.currentTimeMillis() - it < 5 * 60_000L } >= 2 }

    fun pushSquadOverlay(json: String?) {
        if (json != null && liteMode()) { if (squadOverlay != null) { squadOverlay = null; mainHandler.post { runCatching { activeTab()?.webView?.evaluateJavascript("(function(){var S=window.__fluwySquad;if(S){S.stop=true;try{S.mo&&S.mo.disconnect();}catch(e){}}var r=document.getElementById('__fluwy_squad');if(r)r.remove();window.__fluwySquad=null;})();", null) } } }; return }
        squadOverlay = json
        mainHandler.post {
            runCatching {
                val js = if (json == null) "(function(){var S=window.__fluwySquad;if(S){S.stop=true;try{S.mo&&S.mo.disconnect();}catch(e){}}var r=document.getElementById('__fluwy_squad');if(r)r.remove();window.__fluwySquad=null;})();"
                    else dev.cueopen.app.agent.BuildSquad.overlayScript(json)
                val tab = activeTab()
                if (json == null || tab?.id !in failedTabs) tab?.webView?.evaluateJavascript(js, null)
            }
        }
    }

    /** Recarrega o preview sem bloquear (arquivos do site mudaram). */
    fun softReload() { mainHandler.post { runCatching { activeTab()?.webView?.reload() } } }

    suspend fun reload() {
        val tab = onMain { activeTab()?.also { it.navigationStatus = BrowserNavigationStatus(); it.pageDone = CompletableDeferred(); it.webView?.reload() } }
            ?: return
        awaitLoad(tab)
    }

    suspend fun newTab(url: String = ""): String {
        // No limite, fecha a aba usada há mais tempo em vez de travar o agente com erro.
        val closed = onMain {
            if (tabs.size < BrowserSessionPersistence.MAX_TABS) null
            else tabs.values.filter { it.id != activeTabId }.minByOrNull { it.lastUsed }?.let { old ->
                val label = (old.webView?.title ?: old.title).ifBlank { old.url }.take(60)
                if (closeTab(old.id)) label else null
            }
        }
        val id = onMain { createTab(appCtx, "about:blank", deferNavigation = true) }
            ?: return "ERROR: não foi possível abrir outra aba. Reuse a aba atual com browser_navigate."
        val note = closed?.let { " A aba mais antiga (\"$it\") foi fechada para liberar espaço. Prefira reusar a aba atual com browser_navigate em vez de abrir uma nova a cada busca." }.orEmpty()
        return if (url.isBlank()) "OK: nova aba criada (id=$id).$note" else navigateInTab(url, id) + note
    }

    suspend fun selectTabForAgent(tabId: String): String = onMain {
        if (selectTab(tabId)) "OK: aba ativa id=$tabId." else "ERROR: aba não encontrada nesta conversa."
    }

    suspend fun closeTabForAgent(tabId: String): String = onMain {
        if (closeTab(tabId)) "OK: aba id=$tabId fechada." else "ERROR: não foi possível fechar a aba (a última aba não pode ser removida)."
    }

    /** Snapshot semântico da página, próximo ao formato Playwright: landmarks, headings, texto e controles nomeados. */
    /** Print da aba ativa em JPEG (para páginas que abrem sem texto: canvas/JS). Null se não houver o que capturar. */
    suspend fun captureJpeg(maxSide: Int = 1024): ByteArray? = onMain {
        val v = activeTab()?.webView ?: return@onMain null
        val w = v.width; val h = v.height
        if (w < 10 || h < 10) return@onMain null
        val scale = minOf(1f, maxSide.toFloat() / maxOf(w, h))
        val bmp = Bitmap.createBitmap((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), Bitmap.Config.RGB_565)
        runCatching {
            val c = android.graphics.Canvas(bmp); c.scale(scale, scale); v.draw(c)
            java.io.ByteArrayOutputStream().use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 70, out); out.toByteArray() }
        }.getOrNull().also { bmp.recycle() }
    }

    suspend fun read(maxChars: Int = 9000, trackRepeat: Boolean = true): String {
        val js = SnapshotFormat.SNAPSHOT_JS
        val tab = onMain { activeTab()?.also { lastReadTabId = it.id } }
            ?: return "ERROR: não há aba ativa no browser."
        // Snapshot só depois que a página parou de carregar/mudar (evita ler só o "esqueleto" do site).
        val state = waitForStable(tab, 8_000)
        val raw = eval(js, tab = tab)
        if (raw == "null" || raw == "undefined" || raw.isBlank()) {
            return "Falha ao ler página: o JavaScript de snapshot não retornou dados. Verifique o console do WebView e se o DOM já foi criado."
        }
        return runCatching {
            val o = JSONObject(raw)
            val els = o.getJSONArray("elements")
            val reload = tab.reloadNote?.also { tab.reloadNote = null }
            val empty = o.optString("text").isBlank() && els.length() == 0
            buildString {
                if (reload != null) append("⚠ PÁGINA RECARREGADA: $reload. Scripts injetados (browser_js), campos preenchidos e o estado da página se perderam; refaça esses passos se precisar deles.\n\n")
                append("Aba: ${tab.id}\nURL: ${o.optString("url")}\nTítulo: ${o.optString("title")}\n")
                o.optString("ogImage").takeIf { dev.cueopen.app.web.PageMeta.isGoodImage(it) }?.let { append("Imagem principal: $it\n") }
                o.optString("price").takeIf { it.isNotBlank() }?.let { append("Preço: $it\n") }
                o.optJSONArray("tiles")?.takeIf { it.length() > 0 }?.let { t ->
                    append("\n--- Itens com foto (para cards: title, image, url, price) ---\n")
                    for (k in 0 until t.length()) { val x = t.getJSONObject(k)
                        if (!dev.cueopen.app.web.PageMeta.isGoodImage(x.optString("i"))) continue
                        append("- ${x.optString("t")}" + (x.optString("p").takeIf { it.isNotBlank() }?.let { " · $it" } ?: "") + "\n  url: ${x.optString("u")}\n  imagem: ${x.optString("i")}\n") }
                }
                if (empty) append("\n$EMPTY_PAGE_MARK\n")
                val parsed = SnapshotFormat.parse(raw)
                if (parsed != null) append("\n").append(SnapshotFormat.body(parsed, maxChars))
                else append("\n--- Conteúdo principal ---\n").append(o.optString("text").take(maxChars))
                // Rolagem (como o browser-use): diz quanto conteúdo ainda existe abaixo — evita reabrir a página
                // para "ver mais" quando bastaria rolar.
                if (state.viewport > 0 && state.scrollHeight > state.viewport) append(
                    "\n--- Rolagem: %.1f página(s) acima · %.1f abaixo%s ---\n".format(java.util.Locale.ROOT, state.pagesAbove, state.pagesBelow,
                        if (state.pagesBelow > 0.2) " (use browser_scroll para ver mais resultados)" else " (fim da página)"))
                if (state.tracked && (state.inflight > 0 && state.netQuietMs < 1_500)) append("\n(Aviso: a página ainda estava carregando dados; se faltar algo, leia de novo em alguns segundos.)\n")
            }.let { snap ->
                // Mesmo conteúdo da leitura anterior nesta aba = o agente não está progredindo.
                // inclui a posição de rolagem: rolar e ler de novo é progresso, não repetição
                val digest = (snap.substringAfter("--- Conteúdo principal") + "|" + state.scrollY / maxOf(1, state.viewport / 2)).hashCode()
                val repeated = trackRepeat && tab.lastSnapshotDigest == digest
                tab.lastSnapshotDigest = if (trackRepeat) digest else null
                if (repeated) "⚠ Conteúdo IGUAL à leitura anterior desta aba. Não leia de novo nem recarregue: role (browser_scroll), clique num item ou mude de abordagem.\n\n$snap" else snap
            }
        }.getOrElse { "Falha ao ler página: $raw" }
    }

    /**
     * Índice velho (a página navegou/re-renderizou ou a aba mudou): em vez de só devolver erro — o que
     * custava uma volta extra do modelo e gerava cliques repetidos —, já lê a aba ativa e devolve o
     * snapshot NOVO junto, para o agente escolher o índice certo na próxima ação.
     */
    private suspend fun staleRefRecovery(index: Int, why: String): String {
        val fresh = runCatching { read(6_000, trackRepeat = false) }.getOrDefault("")
        return "ERRO: o índice $index não vale mais ($why). Nada foi clicado/digitado. " +
            "Snapshot ATUALIZADO abaixo — escolha o índice nele (não repita o $index):\n\n$fresh"
    }

    data class ScriptTestResult(val sync: String, val asyncErrors: List<String>, val logs: List<String>, val verifyValue: String,
                                val verifyError: String?, val verifyTruthy: Boolean)

    /**
     * Testa um userscript na aba ativa (criador de scripts com IA): captura erros assíncronos e console,
     * espera os efeitos e avalia a expressão [verify], que PROVA se o script fez o que devia.
     */
    suspend fun runUserScriptForTest(script: dev.cueopen.app.data.UserScript, verify: String): ScriptTestResult {
        val tab = onMain { activeTab() } ?: return ScriptTestResult("err:não há aba aberta", emptyList(), emptyList(), "", null, false)
        val view = onMain { tab.webView } ?: return ScriptTestResult("err:a aba não tem página carregada", emptyList(), emptyList(), "", null, false)
        eval("""(function(){var T=window.__cueT={errs:[],logs:[]};
            window.addEventListener('error',function(e){T.errs.push(String(e.message||e).slice(0,200));});
            window.addEventListener('unhandledrejection',function(e){T.errs.push('promise: '+String((e.reason&&e.reason.message)||e.reason).slice(0,200));});
            var ce=console.error,cl=console.log;
            console.error=function(){try{T.errs.push('console.error: '+[].slice.call(arguments).join(' ').slice(0,200));}catch(x){}return ce.apply(console,arguments);};
            console.log=function(){try{var m=[].slice.call(arguments).join(' ');if(m.indexOf('[userscript')===0)T.logs.push(m.slice(0,200));}catch(x){}return cl.apply(console,arguments);};
            return 'ok';})()""", tab = tab)
        val wrapped = BrowserPolicies.wrap(script.copy(id = script.id + System.nanoTime().toString(36)), BrowserFeatures.tokenFor(view))
        val sync = BrowserPolicies.parseResult(eval(wrapped, tab = tab)).let { if (it.isBlank() || it == "null") "err:erro de sintaxe no script" else it }
        delay(2_500)  // observers, requisições e timers do script
        val raw = eval("""(function(){var T=window.__cueT||{errs:[],logs:[]},v=null,ve=null;
            try{v=(function(){return ($verify
            );})();}catch(e){ve=String((e&&e.message)||e).slice(0,200);}
            var truthy=!!v&&!(Array.isArray(v)&&!v.length)&&!(typeof v==='object'&&v!==null&&typeof v.length==='number'&&!v.length);
            var show;try{show=(typeof v==='object'&&v!==null)?JSON.stringify(v):String(v);}catch(e){show=String(v);}
            return JSON.stringify({errs:T.errs.slice(0,6),logs:T.logs.slice(-6),value:(show||'').slice(0,400),verr:ve,truthy:truthy});})()""", tab = tab)
        return runCatching {
            val o = JSONObject(raw)
            fun list(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
            ScriptTestResult(sync, list("errs"), list("logs"), o.optString("value"), if (o.isNull("verr")) null else o.optString("verr"), o.optBoolean("truthy"))
        }.getOrElse { ScriptTestResult(sync, emptyList(), emptyList(), "", "a expressão verify tem erro de sintaxe (use UMA expressão JS, sem ponto e vírgula nem return)", false) }
    }

    /**
     * Descoberta para o criador de scripts: fatos do DOM REAL da aba ativa — quantos elementos cada seletor
     * acha (com amostras), onde aparece um texto visível (caminho CSS, atributos estáveis), iframes,
     * shadow DOM e se a página continua mudando sozinha (conteúdo dinâmico).
     */
    suspend fun inspectForScript(selectors: List<String>, text: String): String {
        val tab = onMain { activeTab() } ?: return "ERROR: não há aba aberta; informe url."
        waitForStable(tab, 5_000)
        val sel = selectors.take(8).joinToString(",", "[", "]") { BrowserPolicies.js(it) }
        val raw = eval("""(function(){var S=$sel,T=${BrowserPolicies.js(text.take(80))};
            function cut(h){return (h||'').replace(/\s+/g,' ').slice(0,300);}
            function path(e){var p=[];while(e&&e.nodeType===1&&p.length<5){var s=e.tagName.toLowerCase();if(e.id){p.unshift(s+'#'+e.id);break;}
              var c=(e.getAttribute('class')||'').trim().split(/\s+/).filter(Boolean).slice(0,2).join('.');if(c)s+='.'+c;p.unshift(s);e=e.parentElement;}return p.join(' > ');}
            var all=document.getElementsByTagName('*'),sh=0;for(var i=0;i<all.length&&i<20000;i++)if(all[i].shadowRoot)sh++;
            var out={url:location.href.slice(0,200),title:document.title.slice(0,80),nodes:all.length,iframes:document.querySelectorAll('iframe').length,shadowRoots:sh,selectors:[],text:[]};
            S.forEach(function(s){try{var n=document.querySelectorAll(s);out.selectors.push({sel:s,count:n.length,samples:[].slice.call(n,0,2).map(function(e){return cut(e.outerHTML);})});}
              catch(e){out.selectors.push({sel:s,error:'seletor inválido'});}});
            if(T&&document.body){var w=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT),n,k=0,low=T.toLowerCase();
              while((n=w.nextNode())&&k<5){if(n.nodeValue&&n.nodeValue.toLowerCase().indexOf(low)>=0&&n.parentElement){var e=n.parentElement;
                out.text.push({path:path(e),attrs:[].slice.call(e.attributes).filter(function(a){return /^(data-|aria-|role|href|name|type)/.test(a.name);}).map(function(a){return a.name+'='+a.value.slice(0,60);}).join(' '),
                  html:cut(e.outerHTML),parent:cut(e.parentElement?e.parentElement.outerHTML:'').slice(0,200)});k++;}}}
            return JSON.stringify(out);})()""", tab = tab)
        if (raw == "null" || raw.startsWith("ERROR") || raw.startsWith("timeout")) return "ERROR: não consegui inspecionar a página ($raw)."
        delay(1_500)
        val later = eval("document.getElementsByTagName('*').length", tab = tab).toIntOrNull()
        val before = runCatching { JSONObject(raw).optInt("nodes") }.getOrDefault(0)
        val dyn = if (later != null && later != before) "\nDINÂMICA: o DOM foi de $before para $later nós em 1,5 s — o conteúdo chega/muda depois do carregamento: use MutationObserver (com filtro) ou intervalo com limite."
            else "\nDOM estável em 1,5 s ($before nós)."
        return raw + dyn
    }

    suspend fun click(index: Int): String {
        val tab = onMain { activeTab()?.takeIf { lastReadTabId == it.id } }
            ?: return staleRefRecovery(index, "a página ou a aba mudou desde a última leitura")
        val r = eval("""(function(){var e=document.querySelector('[data-cue="$index"]');if(!e)return 'elemento $index não encontrado: referência obsoleta; execute browser_read novamente, escolha um índice novo e não repita este';e.scrollIntoView({block:'center'});e.click();return 'ok: clicou em '+(e.innerText||e.value||e.tagName).slice(0,60);})()""", tab = tab)
        if (r.contains("não encontrado")) return staleRefRecovery(index, "a página mudou e o elemento sumiu")
        awaitLoadIfNavigating(tab)
        waitForStable(tab, 6_000)  // modais/listas que abrem por JS
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
            ?: return staleRefRecovery(index, "a página ou a aba mudou desde a última leitura")
        val t = JSONObject.quote(text)
        val r = eval("""
            (function(){var e=document.querySelector('[data-cue="$index"]');if(!e)return 'elemento $index não encontrado';
            e.focus(); var setter=Object.getOwnPropertyDescriptor(Object.getPrototypeOf(e),'value');
            if(setter&&setter.set){setter.set.call(e,$t);}else{e.value=$t;}
            e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));
            ${if (submit) "if(e.form){e.form.requestSubmit?e.form.requestSubmit():e.form.submit();}else{e.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',keyCode:13,bubbles:true}));}" else ""}
            return 'ok';})()
        """.trimIndent(), tab = tab)
        if (r.contains("não encontrado")) return staleRefRecovery(index, "a página mudou e o campo sumiu")
        if (submit) awaitLoadIfNavigating(tab)
        return r
    }

    suspend fun scroll(down: Boolean): String {
        val tab = onMain { activeTab() } ?: return "ERROR: não há aba ativa no browser."
        val before = readiness(tab)
        eval("window.scrollBy(0, ${if (down) "" else "-"}window.innerHeight*0.8); 'ok'", tab = tab)
        // Listas com rolagem infinita carregam itens depois do scroll: espera estabilizar.
        val after = waitForStable(tab, 5_000)
        val grew = after.chars - before.chars
        val moved = after.scrollY != before.scrollY
        return buildString {
            append(if (moved) "ok: rolou" else "ok: já estava no ${if (down) "fim" else "topo"} da página")
            if (grew > 50) append("; +$grew caracteres carregados")
            if (after.viewport > 0) append("; %.1f página(s) abaixo".format(java.util.Locale.ROOT, after.pagesBelow))
            append(". Use browser_read para ver o conteúdo atual.")
        }
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
            target?.let { ensureTabView(it, appCtx).also { v -> if (!v.isAttachedToWindow) ensureSized(v) } }
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
