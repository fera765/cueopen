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
import dev.fluwy.app.browser.BrowserHost.BrowserTab
import dev.fluwy.app.browser.BrowserHost.ConsoleEntry
import dev.fluwy.app.browser.BrowserHost.NavDiag
import dev.fluwy.app.browser.BrowserHost.NetEntry
import dev.fluwy.app.browser.BrowserHost.PageReadiness
import dev.fluwy.app.browser.BrowserHost.ScriptTestResult
import dev.fluwy.app.browser.BrowserHost.TabInfo
import dev.fluwy.app.browser.BrowserHost.Ui
import dev.fluwy.app.browser.BrowserHost.Companion.DESKTOP_UA
import dev.fluwy.app.browser.BrowserHost.Companion.EMPTY_PAGE_MARK
import dev.fluwy.app.browser.BrowserHost.Companion.HOME
import dev.fluwy.app.browser.BrowserHost.Companion.MAX_LIVE_VIEWS
import dev.fluwy.app.browser.BrowserHost.Companion.MAX_TABS
import dev.fluwy.app.browser.BrowserHost.Companion.READINESS_JS
import dev.fluwy.app.browser.BrowserHost.Companion.clearSessionData
import dev.fluwy.app.browser.BrowserHost.Companion.cookieManagerFor
import dev.fluwy.app.browser.BrowserHost.Companion.signOutEverywhere

/**
 * O agente e a interface operam no WebView da aba ativa, evitando navegação em segundo plano.
 */
internal fun BrowserHost.agentView(): WebView = view(appCtx)

/** Página de erro própria do Fluwy, no lugar da tela padrão do Chrome/WebView. */
internal fun BrowserHost.errorPageHtml(url: String, rawDesc: String): String {
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
internal fun BrowserHost.createView(ctx: Context, tab: BrowserTab): WebView {
    // Nunca "escondido" para o Chromium: a página continua viva com o app em segundo plano.
    val v = AgentWebView(ctx)
    // WebView das Web APIs: ponte de arquivos/stream (só responde com o token da chamada em andamento).
    if (sessionId.startsWith("__webapi")) runCatching { v.addJavascriptInterface(WebApiFs, "__fluwyFs") }
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
            val sb = dev.fluwy.app.FluwyApp.instance.sandbox
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
        mediaPlaybackRequiresUserGesture = !sessionId.startsWith("__webapi") // páginas das Web APIs (ex.: WhatsApp) nunca tocam som
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
            WebViewCompat.addDocumentStartJavaScript(v, READINESS_JS + "\n" + dev.fluwy.app.web.LoadBoost.START_JS, setOf("*")); true
        } else false
    }.getOrDefault(false)
    v.webViewClient = object : WebViewClient() {
        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            tab.diag.add("onPageStarted $url")
            failedTabs.remove(tab.id)
            if (!docStart) runCatching { view.evaluateJavascript(READINESS_JS + "\n" + dev.fluwy.app.web.LoadBoost.START_JS, null) }
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
            if (tab.id !in failedTabs) squadOverlay?.let { j -> runCatching { view.evaluateJavascript(dev.fluwy.app.agent.BuildSquad.overlayScript(j), null) } }
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
            if (dev.fluwy.app.web.LoadBoost.shouldBlock(url, request.isForMainFrame))
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
            runCatching { handleRendererGone(tab, view, crashed) }
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
internal fun BrowserHost.installNetworkInstrumentation(v: WebView) {
    v.evaluateJavascript("""(function(){if(window.__fluwyNetInstalled)return 'ok';window.__fluwyNetInstalled=true;window.__fluwyNet=[];
      function trim(x){try{return String(x||'').slice(0,12000)}catch(e){return ''}}
      function headers(h){var o={};try{if(h&&h.forEach)h.forEach(function(v,k){o[k]=v});else if(h)Object.keys(h).forEach(function(k){o[k]=h[k]})}catch(e){}return o}
      var oldFetch=window.fetch;if(oldFetch)window.fetch=function(input,init){var u=typeof input==='string'?input:(input&&input.url)||'',m=(init&&init.method)||(input&&input.method)||'GET',rh=headers((init&&init.headers)||(input&&input.headers)),rb=trim(init&&init.body),t=Date.now();
        return oldFetch.apply(this,arguments).then(function(r){var row={url:u,method:m,requestHeaders:rh,requestBody:rb,status:r.status,responseHeaders:headers(r.headers),durationMs:Date.now()-t};window.__fluwyNet.push(row);if(window.__fluwyNet.length>300)window.__fluwyNet.shift();r.clone().text().then(function(b){row.responseBody=trim(b)},function(){});return r},function(e){window.__fluwyNet.push({url:u,method:m,requestHeaders:rh,requestBody:rb,error:String(e),durationMs:Date.now()-t});throw e})};
      var X=window.XMLHttpRequest;if(X){var op=X.prototype.open,sp=X.prototype.send,sh=X.prototype.setRequestHeader;X.prototype.open=function(m,u){this.__fluwy={url:String(u),method:m,requestHeaders:{},requestBody:'',start:0};return op.apply(this,arguments)};
        X.prototype.setRequestHeader=function(k,v){if(this.__fluwy)this.__fluwy.requestHeaders[k]=v;return sh.apply(this,arguments)};
        X.prototype.send=function(b){var x=this; if(this.__fluwy){this.__fluwy.requestBody=trim(b);this.__fluwy.start=Date.now();this.addEventListener('loadend',function(){var row={url:x.__fluwy.url,method:x.__fluwy.method,requestHeaders:x.__fluwy.requestHeaders,requestBody:x.__fluwy.requestBody,status:x.status,responseHeaders:{},responseBody:'',durationMs:Date.now()-x.__fluwy.start};try{x.getAllResponseHeaders().trim().split(/[\r\n]+/).forEach(function(s){var p=s.indexOf(':');if(p>0)row.responseHeaders[s.slice(0,p).trim()]=s.slice(p+1).trim()});row.responseBody=trim(x.responseType===''||x.responseType==='text'?x.responseText:'[non-text response]')}catch(e){}window.__fluwyNet.push(row);if(window.__fluwyNet.length>300)window.__fluwyNet.shift()})}return sp.apply(this,arguments)}}return 'ok'})()""", null)
}

/** Snapshot com estrutura acessível, landmarks, headings, links e controles nomeados. */
internal fun BrowserHost.installSnapshotInstrumentation(v: WebView) {
    v.evaluateJavascript("""(function(){try{document.documentElement.setAttribute('data-fluwy-snapshot-ready','1');return 'ok'}catch(e){return 'err'}})()""", null)
}

internal suspend fun BrowserHost.mergeCapturedNetwork() {
    flushNet()
    val tab = onMain { activeTab() } ?: return
    val raw = runCatching { eval("JSON.stringify((window.__fluwyNet||[]).slice(-200))", tab = tab) }.getOrNull() ?: return
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

internal fun BrowserHost.sync(v: WebView, loading: Boolean = _ui.value.loading) {
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

internal fun BrowserHost.persistWebViewState(tab: BrowserTab, view: WebView) {
    tab.url = view.url ?: tab.url
    tab.title = view.title ?: tab.title
    saveMetadata()
    val bundle = Bundle()
    runCatching {
        if (view.saveState(bundle) != null) persistence.saveWebViewState(tab.id, bundle)
    }
}
