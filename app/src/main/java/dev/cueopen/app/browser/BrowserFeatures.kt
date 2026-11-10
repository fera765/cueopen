package dev.cueopen.app.browser

import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewFeature
import dev.cueopen.app.CueApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lado Android da seção Browser: sessões de login (verificar/revogar cookies do perfil de contas) e
 * injeção de User Scripts nos browsers dos agentes. As regras ficam em [BrowserPolicies].
 *
 * Isolamento: cada agente segue com o próprio browser (abas, histórico, estado). Só o perfil de contas
 * (cookies/armazenamento) é global — é ele que faz um login salvo valer para os agentes autorizados.
 */
object BrowserFeatures {
    /** Chave do browser usado para o usuário fazer login (não é de nenhum agente). */
    const val LOGIN_BROWSER_KEY = "__browser_login__"

    private fun profileCookies(): CookieManager =
        runCatching {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE))
                ProfileStore.getInstance().getOrCreateProfile(BrowserProfileIdentity.SHARED_PROFILE).cookieManager else null
        }.getOrNull() ?: CookieManager.getInstance()

    private fun profileStorage(): WebStorage =
        runCatching {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE))
                ProfileStore.getInstance().getOrCreateProfile(BrowserProfileIdentity.SHARED_PROFILE).webStorage else null
        }.getOrNull() ?: WebStorage.getInstance()

    private fun urlsFor(domain: String) = listOf("https://$domain", "https://www.$domain", "http://$domain")

    /** Quantos cookies o perfil tem para o domínio (0 = sem sessão; não lê nem devolve valores). */
    suspend fun cookieCount(domain: String): Int = withContext(Dispatchers.Main) {
        runCatching {
            val cm = profileCookies()
            urlsFor(domain).flatMap { u -> cm.getCookie(u)?.split(';')?.map { it.substringBefore('=').trim() }.orEmpty() }
                .filter { it.isNotEmpty() }.toSet().size
        }.getOrDefault(0)
    }

    /**
     * Revoga a sessão: expira os cookies do domínio e apaga o armazenamento local das origens dele.
     * Limite do WebView: não há API para apagar por domínio, então cada cookie visível é expirado.
     */
    suspend fun revoke(domain: String) = withContext(Dispatchers.Main) {
        runCatching {
            val cm = profileCookies()
            urlsFor(domain).forEach { u ->
                cm.getCookie(u)?.split(';')?.map { it.substringBefore('=').trim() }?.filter { it.isNotEmpty() }?.forEach { name ->
                    val dead = "$name=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/"
                    cm.setCookie(u, dead)
                    cm.setCookie(u, "$dead; Domain=.$domain")
                    cm.setCookie(u, "$dead; Domain=$domain")
                }
            }
            cm.flush()
            val storage = profileStorage()
            urlsFor(domain).forEach { runCatching { storage.deleteOrigin(it) } }
        }
    }

    /** Injeta os User Scripts que valem para esta URL/agente. Chamado na thread principal pelo WebViewClient. */
    fun applyUserScripts(view: WebView, url: String, sessionId: String) {
        if (sessionId == LOGIN_BROWSER_KEY) return
        val app = runCatching { CueApp.instance }.getOrNull() ?: return
        val st = app.store.state.value
        if (st.userScripts.isEmpty()) return
        val agentId = st.sessions.firstOrNull { it.id == sessionId }?.agentId
        val host = BrowserPolicies.hostOf(url)
        BrowserPolicies.scriptsFor(url, agentId, st.userScripts).forEach { script ->
            runCatching {
                view.evaluateJavascript(BrowserPolicies.wrap(script)) { raw ->
                    val parsed = BrowserPolicies.parseResult(raw)
                    // null = o script nem compilou (erro de sintaxe).
                    val result = if (parsed.isBlank() || parsed == "null") "err:erro de sintaxe no script" else parsed
                    if (result == "dup") return@evaluateJavascript
                    val clock = java.text.SimpleDateFormat("HH:mm", java.util.Locale.ROOT).format(java.util.Date())
                    app.store.editUserScript(script.id) { BrowserPolicies.record(it, host, result, System.currentTimeMillis(), clock) }
                    if (result != "ok") runCatching {
                        dev.cueopen.app.diag.ErrorLog.log("browser", "User Script \"${script.name}\" falhou em $host: ${result.removePrefix("err:")}", sessionId, "user_script")
                    }
                }
            }
        }
    }
}
