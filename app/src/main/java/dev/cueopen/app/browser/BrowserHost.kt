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
                  val canBack: Boolean = false, val canForward: Boolean = false, val agentControl: Boolean = false)

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui
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
        }
        v.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) { sync(view, loading = true) }
            override fun onPageFinished(view: WebView, url: String) {
                sync(view, loading = false); pageDone?.complete(Unit)
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val s = request.url.scheme ?: return true
                return s !in setOf("http", "https", "file", "about", "data")
            }
        }
        v.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) { _ui.value = _ui.value.copy(progress = newProgress) }
            override fun onReceivedTitle(view: WebView, title: String?) { sync(view) }
        }
        _view = v
        v.loadUrl("https://duckduckgo.com/")
        return v
    }

    private fun sync(v: WebView, loading: Boolean = _ui.value.loading) {
        _ui.value = _ui.value.copy(url = v.url ?: "", title = v.title ?: "", loading = loading,
            canBack = v.canGoBack(), canForward = v.canGoForward())
    }

    fun setAgentControl(on: Boolean) { _ui.value = _ui.value.copy(agentControl = on) }

    // ---------------- API usada pelo agente ----------------

    suspend fun navigate(url: String): String = onMain {
        val target = when {
            url.startsWith("http") || url.startsWith("file:") || url.startsWith("about:") -> url
            url.contains(".") && !url.contains(" ") -> "https://$url"
            else -> "https://duckduckgo.com/html/?q=" + java.net.URLEncoder.encode(url, "UTF-8")
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
