package dev.fluwy.app.llm

import dev.fluwy.app.FluwyApp
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Renova tokens do login por código perto de expirar (a chave do provedor é o access token). */
object OAuthRefresh {
    private val lock = Mutex()
    const val MARGIN_MS = 5 * 60_000L

    fun needsRefresh(entry: List<String>?, now: Long): Boolean {
        val refresh = entry?.getOrNull(0).orEmpty(); val exp = entry?.getOrNull(1)?.toLongOrNull() ?: 0L
        return refresh.isNotBlank() && exp > 0 && exp - now < MARGIN_MS
    }

    suspend fun ensureFresh(providerId: String) = lock.withLock {
        val store = runCatching { FluwyApp.instance.store }.getOrNull() ?: return@withLock
        val s = store.settings
        val p = ProviderCatalog.byId(providerId) ?: return@withLock
        val auth = p.device ?: return@withLock
        val entry = s.oauthTokens[providerId]
        if (!needsRefresh(entry, System.currentTimeMillis())) return@withLock
        val clientId = s.oauthClients[providerId].orEmpty().ifBlank { return@withLock }
        runCatching { DeviceFlow.refresh(auth, clientId, entry!![0]) }.onSuccess { t -> store.updateSettings { saveTokens(it, providerId, t) } }
    }

    /** Grava o token como chave do provedor (ativo ou não) e guarda a renovação. */
    fun saveTokens(s: dev.fluwy.app.data.Settings, id: String, t: DeviceFlow.Tokens): dev.fluwy.app.data.Settings {
        val withKey = if (ProviderCatalog.activeId(s) == id) s.copy(apiKey = t.accessToken) else {
            val cur = s.savedProviders[id].orEmpty()
            s.copy(savedProviders = s.savedProviders + (id to listOf(t.accessToken, cur.getOrNull(1)?.ifBlank { null } ?: ProviderCatalog.byId(id)?.baseUrl.orEmpty(),
                cur.getOrNull(2).orEmpty(), cur.getOrNull(3).orEmpty(), cur.getOrNull(4).orEmpty())))
        }
        return withKey.copy(oauthTokens = withKey.oauthTokens + (id to listOf(t.refreshToken, t.expiresAtMs.toString())))
    }
}
