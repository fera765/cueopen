package dev.fluwy.app.browser

import android.webkit.WebView
import dev.fluwy.app.browser.BrowserHost.BrowserTab
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/*
 * Resiliência do browser: renderer que morre/trava e pressão de memória.
 * Regras puras em BrowserResiliencePolicy.kt; aqui só a aplicação no WebView (thread principal).
 */

/** Chamado por onRenderProcessGone. Nunca deixa o app cair: destrói a view e recria a aba se a política deixar. */
internal fun BrowserHost.handleRendererGone(tab: BrowserTab, view: WebView, crashed: Boolean) {
    val why = if (crashed) "falha" else "memória"
    synchronized(rendererCrashes) { rendererCrashes.add(System.currentTimeMillis()); while (rendererCrashes.size > 10) rendererCrashes.removeAt(0) }
    tab.diag.add("processo do renderer encerrado (" + (if (crashed) "crash" else "falta de memória") + ")")
    runCatching { (view.parent as? android.view.ViewGroup)?.removeView(view); view.destroy() }
    if (tab.webView === view) tab.webView = null
    tab.lastSnapshotDigest = null
    val decision = rendererRecovery.onFailure(tab.id, System.currentTimeMillis())
    runCatching { dev.fluwy.app.diag.ErrorLog.log("browser", "Renderer do WebView encerrado ($why) · browser $sessionId · aba ${tab.id}; decisão: $decision.\n" + tab.diag.lines().joinToString("\n")) }
    val pending = tab.pageDone?.takeIf { !it.isCompleted }
    when (decision) {
        RendererRecoveryPolicy.Decision.RECOVER_AND_RETRY -> {
            tab.reloadNote = "o Android encerrou o motor do browser ($why); a aba foi recriada e a página recarregada automaticamente"
            // A navegação pendente continua aguardando: a recarga abaixo completa o mesmo pageDone.
            val retryUrl = tab.diag.target.takeIf { pending != null && _ui.value.profileIsolated && (it.startsWith("http://") || it.startsWith("https://")) }
            // O renderer é compartilhado: todas as abas de todos os chats caem juntas. Só recria agora o que está
            // em uso (navegação pendente ou agente executando nesta aba), espaçado; o resto volta quando for usado.
            val app = runCatching { dev.fluwy.app.FluwyApp.instance }.getOrNull()
            val neededNow = pending != null || (app != null && BrowserFleet.isRunning(app, sessionId) && tab.id == activeTabId) || BrowserFleet.isPinned(sessionId)
            val delayMs = app?.let { BrowserFleet.onRendererDeath(it, sessionId, neededNow) } ?: 0L
            if (delayMs != null) BrowserFleet.post(delayMs) {
                runCatching {
                    val v = ensureTabView(tab, appCtx, restore = retryUrl == null)
                    retryUrl?.let { v.loadUrl(it) }
                }.onFailure {
                    pending?.complete("ERROR: o motor do browser foi encerrado ($why) e não foi possível recriar a aba.")
                }
            }
        }
        RendererRecoveryPolicy.Decision.RECOVER_ONLY -> {
            tab.reloadNote = "o motor do browser caiu de novo ($why); a aba foi recriada sem repetir a navegação"
            pending?.complete("ERROR: o motor do browser caiu de novo nesta página ($why). A aba foi recriada; a página é pesada demais — tente uma versão mais leve (ex.: mobile) ou outra fonte.")
        }
        RendererRecoveryPolicy.Decision.GIVE_UP -> {
            tab.reloadNote = "a página derrubou o motor do browser repetidas vezes; a aba ficou vazia para proteger o app"
            tab.url = "about:blank"
            pending?.complete("ERROR: esta página derrubou o motor do browser várias vezes seguidas ($why). A recuperação automática foi suspensa para esta aba; não abra a mesma URL de novo — use outra fonte.")
        }
    }
}

/**
 * Watchdog do evaluateJavascript: depois de um timeout, testa se o renderer ainda responde a um script
 * trivial. Se não responde, a thread do renderer está congelada — recria a aba e avisa o agente.
 * Devolve a mensagem para o agente, ou null se o renderer está vivo (só o script era lento).
 */
internal suspend fun BrowserHost.checkRendererHung(tab: BrowserTab?): String? {
    val target = onMain { tab ?: activeTab() } ?: return null
    val ping = CompletableDeferred<String>()
    val sent = onMain { target.webView?.let { v -> runCatching { v.evaluateJavascript("1") { ping.complete(it ?: "") } }.isSuccess } == true }
    if (!sent) return null
    if (withTimeoutOrNull(PING_TIMEOUT_MS) { ping.await() } != null) return null
    val decision = rendererRecovery.onFailure(target.id, System.currentTimeMillis())
    runCatching { dev.fluwy.app.diag.ErrorLog.log("browser", "Renderer travado (não respondeu a ping) · browser $sessionId · aba ${target.id}; decisão: $decision") }
    onMain {
        recreateTabView(target)
        if (decision != RendererRecoveryPolicy.Decision.GIVE_UP) runCatching { ensureTabView(target, appCtx, restore = true) }
        else target.url = "about:blank"
    }
    return if (decision == RendererRecoveryPolicy.Decision.GIVE_UP)
        "ERROR: a página travou o motor do browser repetidas vezes; a aba foi esvaziada. Use outra fonte."
    else "ERROR: a página travou o motor do browser (JavaScript não respondia). A aba foi recriada e recarregada; leia a página de novo."
}

/** Resposta a onTrimMemory/onLowMemory. Chamar na thread principal. */
internal fun BrowserHost.onMemoryPressure(level: Int, now: Long = System.currentTimeMillis()) {
    val states = tabs.values.map { t ->
        val v = t.webView
        MemoryPressurePolicy.TabState(
            id = t.id, live = v != null, active = t.id == activeTabId,
            visible = v != null && v.isAttachedToWindow && !OffscreenStage.isStaged(v),
            busy = t.pageDone?.isCompleted == false, lastUsed = t.lastUsed,
        )
    }
    val park = MemoryPressurePolicy.tabsToPark(states, level, now).toSet()
    park.forEach { id -> tabs[id]?.let { parkTab(it) } }
    if (MemoryPressurePolicy.shouldPauseHidden(level)) states.filter { it.live && it.id !in park && !it.active && !it.visible && !it.busy }
        .forEach { s -> tabs[s.id]?.webView?.let { pauseHiddenView(it) } }
    if (MemoryPressurePolicy.shouldClearCache(level)) tabs.values.firstNotNullOfOrNull { it.webView }?.let { runCatching { it.clearCache(false) } }
    if (park.isNotEmpty()) runCatching { dev.fluwy.app.diag.ErrorLog.log("browser", "Pressão de memória (nível $level): ${park.size} aba(s) estacionada(s) na sessão $sessionId") }
}

/** Aba oculta e ociosa: pausa a página (timers/mídia da própria view) e deixa o Android matar o renderer dela primeiro. */
internal fun pauseHiddenView(v: WebView) {
    runCatching { v.onPause() }
    if (android.os.Build.VERSION.SDK_INT >= 26) runCatching { v.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_WAIVED, true) }
}

/** Aba voltou a ser usada: retoma e restaura a prioridade alta do renderer. Inócuo se não estava pausada. */
internal fun resumeView(v: WebView) {
    runCatching { v.onResume() }
    if (android.os.Build.VERSION.SDK_INT >= 26) runCatching { v.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false) }
}

private const val PING_TIMEOUT_MS = 4_000L

/** Estado das abas deste browser para a frota global. Chamar na thread principal. */
internal fun BrowserHost.fleetStates(running: Boolean, pinned: Boolean): List<BrowserFleetPolicy.Tab> = tabs.values.map { t ->
    val v = t.webView
    BrowserFleetPolicy.Tab(
        hostId = sessionId, tabId = t.id, live = v != null,
        visible = v != null && v.isAttachedToWindow && !OffscreenStage.isStaged(v),
        busy = t.pageDone?.isCompleted == false,
        inUse = running && t.id == activeTabId,
        pinned = pinned, lastUsed = t.lastUsed, liveSince = t.liveSince,
    )
}

/** Estaciona uma aba pelo id (a aba ativa também: ela é recriada e restaurada no próximo uso). */
internal fun BrowserHost.parkById(tabId: String) { tabs[tabId]?.takeIf { it.webView != null }?.let { parkTab(it) } }
