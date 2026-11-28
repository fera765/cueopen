package dev.fluwy.app.browser

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
class BrowserHost(internal val appCtx: Context, internal val sessionId: String = "__legacy__") {

    internal val persistence = BrowserSessionPersistence(appCtx, sessionId)
    /** Perfil WebView compartilhado (contas logadas); abas/histórico continuam por chat em [persistence]. */
    internal val profileName = BrowserProfileIdentity.SHARED_PROFILE

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

    internal data class BrowserTab(
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
        internal val events = java.util.concurrent.ConcurrentLinkedDeque<String>()
        fun reset(url: String) { startedAt = System.currentTimeMillis(); target = url; events.clear() }
        fun add(e: String) {
            events.addLast("+${(System.currentTimeMillis() - startedAt) / 100 / 10.0}s ${e.take(220)}")
            while (events.size > 30) events.pollFirst()
        }
        fun lines(): List<String> = events.toList()
    }

    internal val savedSession = persistence.load()
    internal val tabs = linkedMapOf<String, BrowserTab>().apply {
        savedSession.tabs.forEach { put(it.id, BrowserTab(it.id, it.url, it.title)) }
    }
    @Volatile internal var activeTabId: String = savedSession.selectedTabId
    internal var lastReadTabId: String? = null
    /** Aba da última leitura, sem zerar em recargas automáticas: o marcador data-fluwy no DOM já diz se o índice vale. */
    internal var lastReadTabAny: String? = null

    internal val _net = MutableStateFlow<List<NetEntry>>(emptyList())
    val net: StateFlow<List<NetEntry>> = _net
    internal val _console = MutableStateFlow<List<ConsoleEntry>>(emptyList())
    val console: StateFlow<List<ConsoleEntry>> = _console

    // ───────── Demonstração ("ensine seu agente"): passos do usuário no browser, sem o que ele digita ─────────
    internal val _demoSteps = MutableStateFlow<List<String>?>(null)
    /** null = não está gravando; lista = passos gravados até agora. */
    val demoSteps: StateFlow<List<String>?> = _demoSteps
    @Volatile internal var demoPrefix: String? = null

    /** Começa a gravar na aba ativa. O prefixo aleatório identifica os eventos desta gravação no console. */
    fun startDemo() {
        val prefix = "__FLUWY_DEMO_" + java.util.UUID.randomUUID().toString().replace("-", "") + "__"
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

    internal fun addDemoStep(step: String) { _demoSteps.value?.let { _demoSteps.value = DemoRecorder.append(it, step) } }
    internal val netSeq = java.util.concurrent.atomic.AtomicLong()
    internal val netScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    internal val pendingNet = ArrayList<NetEntry>()
    internal var netFlushJob: Job? = null
    internal var defaultUa: String = ""

    @Synchronized fun clearNet() {
        flushNet()
        val selected = activeTabId
        _net.value = _net.value.filterNot { it.tabId == selected }
        _console.value = _console.value.filterNot { it.tabId == selected }
    }

    @Synchronized internal fun addNet(e: NetEntry) {
        pendingNet.add(e)
        if (pendingNet.size > 500) pendingNet.removeAt(0)
        if (netFlushJob?.isActive != true) {
            netFlushJob = netScope.launch {
                delay(100)
                flushNet(fromScheduledJob = true)
            }
        }
    }

    @Synchronized internal fun flushNet(fromScheduledJob: Boolean = false) {
        if (!fromScheduledJob) netFlushJob?.cancel()
        if (pendingNet.isNotEmpty()) {
            _net.value = (_net.value + pendingNet).takeLast(500)
            pendingNet.clear()
        }
        netFlushJob = null
    }

    @Synchronized internal fun patchNet(url: String, tabId: String, fn: (NetEntry) -> NetEntry) {
        val pendingIndex = pendingNet.indexOfLast { it.url == url && it.tabId == tabId }
        if (pendingIndex >= 0) {
            pendingNet[pendingIndex] = fn(pendingNet[pendingIndex])
            return
        }
        val l = _net.value; val i = l.indexOfLast { it.url == url && it.tabId == tabId }
        if (i >= 0) _net.value = l.toMutableList().also { it[i] = fn(it[i]) }
    }

    internal fun guessType(url: String, accept: String?, main: Boolean): String {
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
    internal fun watchNetwork() {
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



    internal val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui

    @Volatile var darkPages: Boolean = savedSession.darkPages
        internal set

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

    internal fun activeTab(): BrowserTab? = tabs[activeTabId] ?: tabs.values.firstOrNull()

    internal fun tabInfo(): List<TabInfo> = tabs.values.map { TabInfo(it.id, it.webView?.url ?: it.url, it.webView?.title ?: it.title) }

    internal fun saveMetadata() {
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
    internal fun ensureTabView(tab: BrowserTab, ctx: Context, restore: Boolean = true): WebView {
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
    internal fun trimLiveViews(keep: String) {
        val live = tabs.values.filter { it.webView != null }
        if (live.size <= MAX_LIVE_VIEWS) return
        live.filter { it.id != keep && it.id != activeTabId }
            .filter { t -> t.webView?.let { v -> !v.isAttachedToWindow || OffscreenStage.isStaged(v) } == true } // não tira da tela
            .sortedBy { it.lastUsed }
            .take(live.size - MAX_LIVE_VIEWS)
            .forEach { parkTab(it) }
    }

    /** Libera o WebView da aba guardando o histórico (recriado sob demanda). */
    internal fun parkTab(tab: BrowserTab) {
        val v = tab.webView ?: return
        runCatching { persistWebViewState(tab, v) }
        runCatching { v.stopLoading(); (v.parent as? android.view.ViewGroup)?.removeView(v); v.destroy() }
        tab.webView = null
        tab.lastSnapshotDigest = null
        tab.reloadNote = "a aba ficou ociosa e foi liberada para economizar memória; ao voltar, a página foi carregada de novo"
    }

    /** Recria o motor de uma aba travada (sem histórico corrompido): a próxima navegação começa limpa. */
    internal fun recreateTabView(tab: BrowserTab) {
        val v = tab.webView
        runCatching { v?.stopLoading(); (v?.parent as? android.view.ViewGroup)?.removeView(v); v?.destroy() }
        tab.webView = null
        tab.lastSnapshotDigest = null
        tab.reloadNote = "a aba travou e o motor dela foi recriado"
        tab.pageDone?.complete("ERROR: aba recriada")
    }

    /** Restaura navegação/histórico independente da aba, com fallback para URL. */
    internal fun restoreViewState(tab: BrowserTab, view: WebView) {
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
    internal fun ensureSized(v: WebView) {
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
    internal fun notifyResize(v: WebView) {
        v.evaluateJavascript("(function(){try{window.dispatchEvent(new Event('resize'));}catch(e){}})()", null)
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
         * rede e da última mudança no DOM. `window.__fluwyLoadState()` devolve o estado para a espera por estabilidade.
         */
        internal val READINESS_JS = """
            (function(){ if (window.__fluwyLoad) return;
              var st = { reqs: {}, urls: {}, seq: 0, lastNet: Date.now(), lastMut: Date.now() }; window.__fluwyLoad = st;
              var ign = /doubleclick|googlesyndication|googletagmanager|google-analytics|analytics\.|hotjar|clarity\.ms|mixpanel|segment\.(io|com)|newrelic|nr-data|sentry|\/beacon|\/collect\b|\/telemetry|\/metrics|\/tracker|\/track\b|\/log(ging)?\b|\/ajax\/bz|\/ajax\/webstorage|\/tr\/?\?/i;
              function start(u){ u = String(u||''); if (ign.test(u)) return null; var id = ++st.seq; st.reqs[id] = Date.now(); st.urls[id] = u.slice(0, 120); st.lastNet = Date.now(); return id; }
              function end(id){ if (id != null) { delete st.reqs[id]; delete st.urls[id]; } st.lastNet = Date.now(); }
              try { var of = window.fetch; if (of) window.fetch = function(i, o){ var id = start((i && i.url) || i);
                return of.apply(this, arguments).then(function(r){ end(id); return r; }, function(e){ end(id); throw e; }); }; } catch(e) {}
              try { var X = XMLHttpRequest.prototype, op = X.open, sd = X.send;
                X.open = function(m, u){ this.__fluwyU = u; return op.apply(this, arguments); };
                X.send = function(){ var x = this, id = start(x.__fluwyU); x.addEventListener('loadend', function(){ end(id); }); return sd.apply(this, arguments); }; } catch(e) {}
              function observe(){ try { new MutationObserver(function(){ st.lastMut = Date.now(); })
                .observe(document.documentElement || document, { childList: true, subtree: true, characterData: true }); } catch(e) {} }
              if (document.documentElement) observe(); else document.addEventListener('readystatechange', observe, { once: true });
              window.__fluwyLoadState = function(){ var now = Date.now(), n = 0;
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
        internal const val DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

        fun clearSessionData(context: Context, sessionId: String) {
            val persistence = BrowserSessionPersistence(context, sessionId)
            clearSessionData(context, persistence.profileName, persistence)
        }

        internal fun clearSessionData(context: Context, profileName: String, persistence: BrowserSessionPersistence) {
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

    suspend fun networkSummary(filter: String): String {
        mergeCapturedNetwork()
        val l = _net.value.filter { it.tabId == activeTabId && (filter.isBlank() || it.url.contains(filter, true)) }.takeLast(60)
        if (l.isEmpty()) return "Nenhuma requisição registrada (rede: ${_ui.value.netType}, online=${_ui.value.online})."
        val fails = l.count { (it.status ?: 0) >= 400 || it.error != null }
        return "Rede: ${_ui.value.netType} · ${l.size} requisições · $fails com erro\n" + l.joinToString("\n") { e ->
            buildString {
                append("${e.method} ${e.status ?: e.error ?: "-"} ${e.type} ${e.durationMs?.let { "${it}ms" } ?: ""} ${e.sizeBytes?.let { "${it / 1024}KB" } ?: ""} ${e.url.take(160)}")
                if (e.requestHeaders.isNotEmpty()) append("\n  Request headers: ").append(safeHeaders(e.requestHeaders).take(1500))
                e.requestBody?.let { append("\n  Request payload: ").append(dev.fluwy.app.diag.ErrorLog.redact(it).take(3000)) }
                if (e.responseHeaders.isNotEmpty()) append("\n  Response headers: ").append(safeHeaders(e.responseHeaders).take(1500))
                e.responseBody?.let { append("\n  Response body: ").append(dev.fluwy.app.diag.ErrorLog.redact(it).take(3000)) }
            }
        }
    }

    fun consoleSummary(): String = _console.value.filter { it.tabId == activeTabId }.takeLast(60).joinToString("\n") { "[${it.level}] ${it.message.take(300)} (${it.source})" }
        .ifBlank { "Console vazio." }

    fun setAgentControl(on: Boolean) { _ui.value = _ui.value.copy(agentControl = on) }

    // ---------------- API usada pelo agente ----------------

    suspend fun navigate(url: String): String = navigateInTab(url, null)

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
        // Página de verificação anti-robô (captcha/deslize): esperar não adianta — diz isso direto.
        val wall = eval("(function(){var t=(document.title+' '+(document.body?document.body.innerText.slice(0,1500):'')).toLowerCase();return /captcha|tráfego incomum|unusual traffic|deslize para|slide to verify|verify you are human|are you a robot|você é um robô/.test(t)})()", 2_000, tab) == "true"
        if (wall) return "BLOQUEADO: o site mostrou uma verificação anti-robô (captcha). Esperar não resolve. Não tente contornar: use outra fonte (web_search, outro site) ou peça ao usuário para resolver no Browser."
        return "TIMEOUT: ${if (t != null) "o texto \"$t\" não apareceu" else "o texto \"$g\" não sumiu"} em ${limit / 1000}s " +
            "(página: readyState=${st.ready}, ${st.chars} caracteres, ${st.inflight} requisição(ões) pendente(s)). Leia o snapshot abaixo antes de decidir; não reabra a página."
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
        internal set
    /** Abas cuja página principal falhou (erro de rede/HTTP): nada de cursores sobre a tela de erro do WebView. */
    internal val failedTabs = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    internal val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** Atualiza (ou remove) os cursores dos agentes na aba ativa, sem esperar resposta. */
    internal val rendererCrashes = mutableListOf<Long>()

    /** O motor caiu 2+ vezes nos últimos 5 min: modo leve (sem cursores/animações do time) para não derrubar de novo. */
    fun liteMode(): Boolean = synchronized(rendererCrashes) { rendererCrashes.count { System.currentTimeMillis() - it < 5 * 60_000L } >= 2 }

    fun pushSquadOverlay(json: String?) {
        if (json != null && liteMode()) { if (squadOverlay != null) { squadOverlay = null; mainHandler.post { runCatching { activeTab()?.webView?.evaluateJavascript("(function(){var S=window.__fluwySquad;if(S){S.stop=true;try{S.mo&&S.mo.disconnect();}catch(e){}}var r=document.getElementById('__fluwy_squad');if(r)r.remove();window.__fluwySquad=null;})();", null) } } }; return }
        squadOverlay = json
        mainHandler.post {
            runCatching {
                val js = if (json == null) "(function(){var S=window.__fluwySquad;if(S){S.stop=true;try{S.mo&&S.mo.disconnect();}catch(e){}}var r=document.getElementById('__fluwy_squad');if(r)r.remove();window.__fluwySquad=null;})();"
                    else dev.fluwy.app.agent.BuildSquad.overlayScript(json)
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
        val tab = onMain { activeTab()?.also { lastReadTabId = it.id; lastReadTabAny = it.id } }
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
                o.optString("ogImage").takeIf { dev.fluwy.app.web.PageMeta.isGoodImage(it) }?.let { append("Imagem principal: $it\n") }
                o.optString("price").takeIf { it.isNotBlank() }?.let { append("Preço: $it\n") }
                o.optJSONArray("tiles")?.takeIf { it.length() > 0 }?.let { t ->
                    append("\n--- Itens com foto (para cards: title, image, url, price) ---\n")
                    for (k in 0 until t.length()) { val x = t.getJSONObject(k)
                        if (!dev.fluwy.app.web.PageMeta.isGoodImage(x.optString("i"))) continue
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

    data class ScriptTestResult(val sync: String, val asyncErrors: List<String>, val logs: List<String>, val verifyValue: String,
                                val verifyError: String?, val verifyTruthy: Boolean)

    /**
     * Testa um userscript na aba ativa (criador de scripts com IA): captura erros assíncronos e console,
     * espera os efeitos e avalia a expressão [verify], que PROVA se o script fez o que devia.
     */
    suspend fun runUserScriptForTest(script: dev.fluwy.app.data.UserScript, verify: String): ScriptTestResult {
        val tab = onMain { activeTab() } ?: return ScriptTestResult("err:não há aba aberta", emptyList(), emptyList(), "", null, false)
        val view = onMain { tab.webView } ?: return ScriptTestResult("err:a aba não tem página carregada", emptyList(), emptyList(), "", null, false)
        eval("""(function(){var T=window.__fluwyT={errs:[],logs:[]};
            window.addEventListener('error',function(e){T.errs.push(String(e.message||e).slice(0,200));});
            window.addEventListener('unhandledrejection',function(e){T.errs.push('promise: '+String((e.reason&&e.reason.message)||e.reason).slice(0,200));});
            var ce=console.error,cl=console.log;
            console.error=function(){try{T.errs.push('console.error: '+[].slice.call(arguments).join(' ').slice(0,200));}catch(x){}return ce.apply(console,arguments);};
            console.log=function(){try{var m=[].slice.call(arguments).join(' ');if(m.indexOf('[userscript')===0)T.logs.push(m.slice(0,200));}catch(x){}return cl.apply(console,arguments);};
            return 'ok';})()""", tab = tab)
        val wrapped = BrowserPolicies.wrap(script.copy(id = script.id + System.nanoTime().toString(36)), BrowserFeatures.tokenFor(view))
        val sync = BrowserPolicies.parseResult(eval(wrapped, tab = tab)).let { if (it.isBlank() || it == "null") "err:erro de sintaxe no script" else it }
        delay(2_500)  // observers, requisições e timers do script
        val raw = eval("""(function(){var T=window.__fluwyT||{errs:[],logs:[]},v=null,ve=null;
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
        val tab = onMain { activeTab()?.takeIf { lastReadTabId == it.id || lastReadTabAny == it.id } }
            ?: return staleRefRecovery(index, "a página ou a aba mudou desde a última leitura")
        val r = eval("""(function(){var e=document.querySelector('[data-fluwy="$index"]');if(!e)return 'elemento $index não encontrado: referência obsoleta; execute browser_read novamente, escolha um índice novo e não repita este';e.scrollIntoView({block:'center'});e.click();return 'ok: clicou em '+(e.innerText||e.value||e.tagName).slice(0,60);})()""", tab = tab)
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
            eval("""(function(){var e=document.querySelector('[data-fluwy="$index"]');if(!e)return '';
                function L(x){return ((x.innerText||x.value||x.getAttribute('aria-label')||x.title||x.placeholder||x.name||'')+'').trim().replace(/\s+/g,' ').slice(0,90);}
                var t=L(e); if((e.tagName=='INPUT'||e.tagName=='TEXTAREA')&&e.form){var b=e.form.querySelector('button[type=submit],input[type=submit],button:not([type])'); if(b) t=L(b)+' · '+t;}
                return t;})()""".trimIndent(), tab = tab)
        }.getOrNull().orEmpty().trim().removeSurrounding("\"")
        return if (raw.isBlank()) null else host to raw
    }

    suspend fun type(index: Int, text: String, submit: Boolean): String {
        val tab = onMain { activeTab()?.takeIf { lastReadTabId == it.id || lastReadTabAny == it.id } }
            ?: return staleRefRecovery(index, "a página ou a aba mudou desde a última leitura")
        val t = JSONObject.quote(text)
        val r = eval("""
            (function(){var e=document.querySelector('[data-fluwy="$index"]');if(!e)return 'elemento $index não encontrado';
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

    /**
     * browser_js: roda o código com eval (aceita instruções e expressão final), ESPERA Promise (async/await, fetch)
     * e devolve o erro lançado em vez de "null". Sem valor de retorno, diz isso explicitamente para o agente mudar a abordagem.
     */
    suspend fun runJs(code: String, promiseTimeoutMs: Long = 25_000): String {
        val tab = onMain { activeTab() } ?: return "ERROR: não há aba ativa no browser."
        val key = "__fjs" + System.nanoTime()
        val ser = "function(v){try{return v===undefined?'__UNDEF__':v===null?'__NULL__':(typeof v==='string'?v:JSON.stringify(v))}catch(e){return String(v)}}"
        // Sem eval (sites com CSP rígida — WhatsApp Web, bancos — bloqueiam 'unsafe-eval'): o evaluateJavascript do app
        // não passa pela CSP. 1) código como EXPRESSÃO; 2) se não for expressão (instruções soltas), roda como está e usa
        // o valor de conclusão do próprio WebView.
        val handle = "if(r&&typeof r.then==='function'){window['$key']=null;r.then(function(v){window['$key']={v:S(v)}},function(e){window['$key']={e:String(e&&e.stack||e)}});return '__PROMISE__'}return S(r)"
        var start = eval("(function(){var S=$ser;try{var r=(\n$code\n);$handle}catch(e){return 'ERROR (exceção no script): '+String(e&&e.message||e)}})()", tab = tab)
        if (start == "null") {
            // Erro de sintaxe como expressão = são instruções: roda uma única vez, cru (valor de conclusão do WebView).
            start = eval(code, tab = tab).let { if (it == "null") "__UNDEF__" else it }
        }
        val out = if (start != "__PROMISE__") start else {
            var got: String? = null
            val deadline = System.currentTimeMillis() + promiseTimeoutMs
            while (got == null && System.currentTimeMillis() < deadline) {
                kotlinx.coroutines.delay(120)
                val r = eval("(function(){var x=window['$key'];if(!x)return '__WAIT__';delete window['$key'];return x.e!==undefined?'ERROR (Promise rejeitada): '+x.e:x.v})()", tab = tab)
                if (r != "__WAIT__") got = r
            }
            got ?: "timeout: a Promise não terminou em ${promiseTimeoutMs / 1000}s (algo no código ficou esperando para sempre)"
        }
        return when (out) {
            "__NULL__" -> "null"
            "__UNDEF__", "null", "" -> "(sem valor: o código não devolveu nada. Termine com uma expressão, ex.: JSON.stringify(resultado), ou use return dentro de uma função)"
            else -> out
        }
    }

    /** JS cru na aba ativa (biblioteca grande, sem embrulho). Não passa pela CSP da página. */
    suspend fun evalRaw(js: String, timeoutMillis: Long = 30_000): String {
        val tab = onMain { activeTab() } ?: return "ERROR: não há aba ativa no browser."
        return eval(js, timeoutMillis, tab)
    }

    /** URL atual da aba ativa (vazio se não há aba). */
    suspend fun currentUrl(): String = onMain { activeTab()?.webView?.url ?: activeTab()?.url }.orEmpty()

}

/**
 * Ritmo de navegação por site, compartilhado por todos os chats/agentes (o login também é compartilhado,
 * então o site enxerga um único visitante). Intervalo mínimo entre aberturas e recuo crescente após 403/429.
 */
internal object HostPacing {
    internal const val MIN_GAP_MS = 2_500L
    internal const val MAX_WAIT_MS = 45_000L
    internal data class State(var lastAt: Long = 0, var cooldownUntil: Long = 0, var strikes: Int = 0)
    internal val hosts = java.util.concurrent.ConcurrentHashMap<String, State>()

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

private val SENSITIVE_HEADERS = setOf("authorization", "proxy-authorization", "cookie", "set-cookie", "x-api-key", "x-auth-token", "x-csrf-token")

/** Cabeçalhos para o modelo: credenciais e cookies viram ••• (o agente só precisa saber que existem). */
internal fun safeHeaders(h: Map<String, String>): String =
    h.entries.joinToString { (k, v) -> if (k.lowercase() in SENSITIVE_HEADERS) "$k=•••" else "$k=${dev.fluwy.app.diag.ErrorLog.redact(v)}" }
