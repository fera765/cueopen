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

@Suppress("DEPRECATION")
internal fun BrowserHost.enableLegacyFileAccess(settings: WebSettings) {
    settings.allowFileAccessFromFileURLs = true
    settings.allowUniversalAccessFromFileURLs = true
}

internal fun BrowserHost.applyColorScheme(v: WebView, dark: Boolean) {
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

/** Completa duração/tamanho das requisições com a Resource Timing API da página. */
internal fun BrowserHost.collectTimings(tab: BrowserTab, v: WebView) {
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

internal suspend fun BrowserHost.navigateInTab(url: String, tabId: String?): String {
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
            runCatching { dev.fluwy.app.diag.ErrorLog.log("browser", "Navegação abortada sem janela: $normalized") }
            return "ERROR: browser indisponível com o app fechado (o aparelho recusou o display virtual e não há permissão de sobreposição). " +
                "Peça ao usuário para abrir o app ou conceder 'Sobrepor a outros apps'. Para página local use curl no shell. Não tente navegar de novo agora."
        }
        val result = awaitLoad(targetTab, target)
        // Aba travada: nem primeiro quadro nem fim em 30 s (o motor dela parou). Recria e tenta 1×.
        val stuck = result.startsWith("TIMEOUT") &&
            targetTab.diag.lines().none { it.contains("onPageCommitVisible") || it.contains("onPageFinished") }
        if (stuck && attempt == 0) {
            attempt++
            runCatching { dev.fluwy.app.diag.ErrorLog.log("browser", "Aba ${targetTab.id} travada (só onPageStarted em 30 s); motor recriado e nova tentativa.\n" + targetTab.diag.lines().joinToString("\n")) }
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

internal suspend fun BrowserHost.readiness(tab: BrowserTab): PageReadiness {
    val raw = runCatching {
        eval("(function(){try{if(window.__fluwyLoadState)return window.__fluwyLoadState();}catch(e){}" +
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
internal suspend fun BrowserHost.waitForStable(tab: BrowserTab, maxMillis: Long): PageReadiness {
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

internal fun BrowserHost.probableCause(st: PageReadiness, placement: String): String = when {
    placement.startsWith("sem janela") -> "o WebView estava fora de qualquer janela; o Chromium pausa páginas assim. Abra o app ou conceda a permissão de sobreposição."
    st.visibility == "hidden" -> "a página está marcada como escondida pelo sistema (app em segundo plano)."
    st.ready == "loading" && st.chars == 0 -> "o documento principal não terminou de chegar (rede lenta, bloqueio do site ou resposta em streaming)."
    st.inflight > 0 -> "a página ainda aguardava dados (requisições pendentes acima)."
    st.chars < 200 -> "a página carregou quase sem conteúdo: possível parede de login/anti-robô."
    else -> "a página tem conteúdo; o problema é de interpretação/ação, não de carregamento."
}

/** Compat: chamadas antigas esperavam (readyState, caracteres). */
internal suspend fun BrowserHost.waitForContent(tab: BrowserTab, maxMillis: Long): Pair<String, Int> =
    waitForStable(tab, maxMillis).let { it.ready to it.chars }

/**
 * Aguarda a navegação no estilo do Playwright MCP:
 *  1. primeiro sinal do WebView (commit/fim/erro) — até 30 s;
 *  2. DOMContentLoaded (readyState ≠ loading) — até 10 s;
 *  3. evento load — no máximo 5 s ("a página já está operacional");
 *  4. página estável (rede ociosa + DOM parado) — até 8 s.
 * Lentidão não vira erro: só falha quando não há sinal NENHUM e nenhum conteúdo visível.
 */
internal suspend fun BrowserHost.awaitLoad(tab: BrowserTab, target: String = tab.url): String {
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
    // Leitura vazia (readyState="" e 0 caracteres) = a medição caiu no meio de um redirecionamento (ex.: /contact → /pt/contact):
    // mede de novo antes de declarar a página incompleta.
    var again = 0
    while (st.ready.isBlank() && st.chars == 0 && again++ < 6) { kotlinx.coroutines.delay(500); st = readiness(tab) }
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
