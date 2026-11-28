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
 * Índice velho (a página navegou/re-renderizou ou a aba mudou): em vez de só devolver erro — o que
 * custava uma volta extra do modelo e gerava cliques repetidos —, já lê a aba ativa e devolve o
 * snapshot NOVO junto, para o agente escolher o índice certo na próxima ação.
 */
internal suspend fun BrowserHost.staleRefRecovery(index: Int, why: String): String {
    val fresh = runCatching { read(6_000, trackRepeat = false) }.getOrDefault("")
    return "ERRO: o índice $index não vale mais ($why). Nada foi clicado/digitado. " +
        "Snapshot ATUALIZADO abaixo — escolha o índice nele (não repita o $index):\n\n$fresh"
}

internal suspend fun BrowserHost.awaitLoadIfNavigating(tab: BrowserTab) {
    kotlinx.coroutines.delay(800)
    // progress é método do WebView: só pode ser lido na main thread (evita o crash
    // "A WebView method was called on thread 'DefaultDispatcher-worker'").
    val navigating = onMain { tab.webView?.progress?.let { it < 100 } == true }
    if (navigating) {
        onMain { if (tab.pageDone == null || tab.pageDone?.isCompleted == true) tab.pageDone = CompletableDeferred() }
        awaitLoad(tab)
    }
}

internal suspend fun BrowserHost.eval(js: String, timeoutMillis: Long = 15_000, tab: BrowserTab? = null): String {
    val d = CompletableDeferred<String>()
    val view = onMain {
        val target = tab ?: activeTab()
        target?.let { ensureTabView(it, appCtx).also { v -> if (!v.isAttachedToWindow) ensureSized(v) } }
    } ?: return "ERROR: não há aba ativa no browser."
    onMain { view.evaluateJavascript(js) { d.complete(it ?: "null") } }
    val raw = withTimeoutOrNull(timeoutMillis) { d.await() }
        ?: return checkRendererHung(tab) ?: "timeout: evaluateJavascript não respondeu em ${timeoutMillis / 1_000}s"
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

internal suspend fun <T> BrowserHost.onMain(block: () -> T): T = withContext(Dispatchers.Main) { block() }
