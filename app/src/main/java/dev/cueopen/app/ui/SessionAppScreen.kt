package dev.cueopen.app.ui

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import dev.cueopen.app.CueApp
import dev.cueopen.app.data.Section
import dev.cueopen.app.sessionapp.SessionAppValidator
import dev.cueopen.app.ui.theme.Cue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/** Alternância [CHAT] [APP] — só existe no chat do coordenador de uma seção. */
@Composable
fun ChatAppToggle(appMode: Boolean, hasApp: Boolean, onChange: (Boolean) -> Unit) {
    val c = Cue.c
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clip(RoundedCornerShape(14.dp)).background(c.surface2).padding(4.dp)) {
        listOf(false to "CHAT", true to "APP").forEach { (mode, label) ->
            val selected = appMode == mode
            Row(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (selected) c.surface else androidx.compose.ui.graphics.Color.Transparent)
                .clickable { onChange(mode) }.padding(vertical = 9.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(if (mode) Icons.Rounded.Dashboard else Icons.Rounded.Forum, null, Modifier.size(16.dp), tint = if (selected) c.accent else c.ink3)
                Spacer(Modifier.width(6.dp))
                Text(label, fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, color = if (selected) c.ink else c.ink3, letterSpacing = 1.sp)
                if (mode && hasApp && !selected) { Spacer(Modifier.width(6.dp)); Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(c.ok)) }
            }
        }
    }
}

/**
 * Mantém o WebView do app vivo ao alternar para o Chat (pausado, sem gastar CPU) — voltar ao App não
 * recarrega nem perde o estado. Um app por vez na memória.
 */
private object SessionAppViews {
    var sectionId: String? = null
    var version: Int = -1
    var view: WebView? = null
    fun release() { runCatching { view?.destroy() }; view = null; sectionId = null; version = -1 }
}

/** Ponte controlada: o app só enxerga o retrato da PRÓPRIA seção e só age por estas quatro funções. */
private class SessionAppBridge(private val sectionId: String, private val onError: (String) -> Unit) {
    private val agent get() = CueApp.instance.agent
    @JavascriptInterface fun getState(): String = agent.sessionAppSnapshot(sectionId)
    @JavascriptInterface fun send(action: String?, payload: String?) { agent.sessionAppAction(sectionId, action.orEmpty(), payload.orEmpty()) }
    @JavascriptInterface fun saveState(json: String?): Boolean = json != null && agent.sessionApps.saveState(sectionId, json)
    @JavascriptInterface fun loadState(): String = agent.sessionApps.loadState(sectionId)
    @JavascriptInterface fun reportError(message: String?) { onError(message.orEmpty().take(300)) }
}

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun SessionAppPane(sec: Section, modifier: Modifier = Modifier, onGoChat: () -> Unit) {
    val app = CueApp.instance
    val c = Cue.c
    val tick by app.agent.sessionAppTick.collectAsStateWithLifecycle()
    val meta = remember(sec.id, tick) { app.agent.sessionApps.meta(sec.id) }
    var jsError by remember(sec.id, meta?.version) { mutableStateOf<String?>(null) }
    var versionsOpen by remember { mutableStateOf(false) }
    if (meta == null) {
        Column(modifier, verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            EmptyState(Icons.Rounded.Dashboard, "Esta seção ainda não tem app",
                "O coordenador cria uma interface própria para o objetivo da seção e a alimenta com os resultados reais dos agentes.") {
              Column(horizontalAlignment = Alignment.CenterHorizontally) {
                PillButton("Criar o app agora", Icons.Rounded.AutoAwesome) {
                    app.agent.sessionAppAction(sec.id, "criar o app visual desta seção", "Use app_publish com uma interface sob medida para o objetivo da seção.")
                    onGoChat()
                }
                Spacer(Modifier.height(8.dp))
                TextButton({ app.agent.installBaseSessionApp(sec.id) }) { Text("Usar o app base agora") }
              }
            }
        }
        return
    }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${meta.title.ifBlank { sec.name }} · v${meta.version}", fontSize = 12.sp, color = c.ink3, modifier = Modifier.weight(1f), maxLines = 1)
            TextButton({ versionsOpen = true }) { Text("Versões", fontSize = 12.sp) }
            TextButton({ SessionAppViews.release(); app.agent.sessionAppTick.value = System.currentTimeMillis() }) { Text("Recarregar", fontSize = 12.sp) }
        }
        jsError?.let { Text("Erro no app: $it", fontSize = 11.sp, color = c.accent, modifier = Modifier.padding(horizontal = 16.dp)) }
        AndroidView(modifier = Modifier.weight(1f).fillMaxWidth(), factory = { ctx ->
            if (SessionAppViews.sectionId != sec.id) SessionAppViews.release()
            val existing = SessionAppViews.view
            if (existing != null) { (existing.parent as? ViewGroup)?.removeView(existing); return@AndroidView existing }
            WebView(ctx).apply {
                // Perfil próprio: o app NUNCA enxerga os cookies/logins do browser dos agentes.
                val isolated = runCatching {
                    if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) { WebViewCompat.setProfile(this, "session_apps"); true } else false
                }.getOrDefault(false)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.mediaPlaybackRequiresUserGesture = true
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                addJavascriptInterface(SessionAppBridge(sec.id) { e ->
                    jsError = e
                    app.agent.sessionAppJsError(sec.id, e)
                    runCatching { dev.cueopen.app.diag.ErrorLog.log("app", "Erro de JS no app da seção ${sec.name}: $e", sec.sessionId, "session_app") }
                }, "__sar")
                webViewClient = object : WebViewClient() {
                    // O app não navega para fora: links e redirecionamentos são bloqueados.
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        val u = request.url.toString()
                        val allowed = u.startsWith("https://session-app.invalid/") || u.startsWith("data:") || u.startsWith("blob:") ||
                            (isolated && u.startsWith("https://") && request.method == "GET")   // fontes/bibliotecas de CDN, sem cookies de contas
                        return if (allowed) null
                            else WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)))
                    }
                }
                SessionAppViews.view = this; SessionAppViews.sectionId = sec.id; SessionAppViews.version = -1
            }
        }, update = { v ->
            if (SessionAppViews.version != meta.version) {
                SessionAppViews.version = meta.version
                val html = app.agent.sessionApps.html(sec.id).orEmpty()
                v.loadDataWithBaseURL("https://session-app.invalid/${sec.id}/", SessionAppValidator.withBootstrap(html), "text/html", "utf-8", null)
            }
        })
    }
    // Visível: animações e timers ligados; ao ir para o Chat o WebView é pausado (não gasta CPU).
    DisposableEffect(sec.id) {
        SessionAppViews.view?.onResume()
        onDispose { SessionAppViews.view?.onPause() }
    }
    // Sincronização: qualquer mudança real da sessão (agentes, plano, dados) chega ao app, com folga de 600 ms.
    LaunchedEffect(sec.id) {
        app.store.state.collectLatest { delay(600); SessionAppViews.view?.evaluateJavascript("window.__sarEmit&&window.__sarEmit()", null) }
    }
    LaunchedEffect(tick) { delay(300); SessionAppViews.view?.evaluateJavascript("window.__sarEmit&&window.__sarEmit()", null) }
    if (versionsOpen) {
        val versions = remember(tick) { app.agent.sessionApps.versions(sec.id) }
        AlertDialog(onDismissRequest = { versionsOpen = false }, title = { Text("Versões do app") },
            text = {
                Column {
                    Text("Atual: v${meta.version}. Restaurar não apaga dados nem o estado do app.", fontSize = 12.sp, color = c.ink2)
                    if (versions.isEmpty()) Text("Nenhuma versão anterior.", fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                    versions.forEach { n ->
                        TextButton({ app.agent.sessionApps.rollback(sec.id, n); app.agent.sessionAppTick.value = System.currentTimeMillis(); versionsOpen = false }) { Text("Restaurar v$n") }
                    }
                }
            },
            confirmButton = { TextButton({ versionsOpen = false }) { Text("Fechar") } })
    }
}
