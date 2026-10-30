package dev.cueopen.app.proxy

import android.content.Context
import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@Serializable
enum class ProxyScheme { HTTP, HTTPS, SOCKS }

@Serializable
data class ProxyProfile(
    val id: String = "proxy-${System.nanoTime()}",
    val name: String = "Proxy",
    val scheme: ProxyScheme = ProxyScheme.HTTP,
    val host: String = "",
    val port: Int = 8080,
    val hasCredentials: Boolean = false,
)

enum class ProxyScope { BROWSER, TERMINAL, AGENT_API }

@Serializable
data class ProxyRotationSettings(
    val enabled: Boolean = false,
    val intervalMinutes: Int = 10,
    val browserProfileIds: List<String> = emptyList(),
    val terminalProfileIds: List<String> = emptyList(),
    val agentApiProfileIds: List<String> = emptyList(),
) {
    fun profileIds(scope: ProxyScope): List<String> = when (scope) {
        ProxyScope.BROWSER -> browserProfileIds
        ProxyScope.TERMINAL -> terminalProfileIds
        ProxyScope.AGENT_API -> agentApiProfileIds
    }
}

/** Pure selection policy; [pick] is injectable so tests never need a live proxy or nondeterministic RNG. */
object ProxyRotationPolicy {
    fun nextProfileId(
        currentId: String?,
        candidates: List<String>,
        profiles: List<ProxyProfile>,
        scope: ProxyScope,
        pick: Int,
    ): String? {
        val byId = profiles.associateBy { it.id }
        val eligible = candidates.distinct().filter { id ->
            val profile = byId[id] ?: return@filter false
            ProxyValidator.validate(profile, scope).valid
        }
        val alternatives = eligible.filterNot { it == currentId }
        if (alternatives.isEmpty()) return null
        return alternatives[Math.floorMod(pick, alternatives.size)]
    }
}

data class ProxyCredentials(val username: String, val password: String) {
    override fun toString(): String = "ProxyCredentials(username=[redacted], password=[redacted])"
}

data class ProxyValidation(val valid: Boolean, val message: String = "")

object ProxyValidator {
    fun validate(profile: ProxyProfile, scope: ProxyScope? = null): ProxyValidation {
        if (profile.name.trim().isEmpty()) return ProxyValidation(false, "Dê um nome ao perfil.")
        val host = profile.host.trim().removePrefix("[").removeSuffix("]")
        val ipv6Literal = host.contains(':') && host.matches(Regex("[0-9a-fA-F:.]+"))
        val dnsOrIpv4 = !host.contains(':') && host.length <= 253 && host.split('.').all {
            it.isNotEmpty() && it.length <= 63 && it.matches(Regex("[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?"))
        }
        if (host.isEmpty() || (!ipv6Literal && !dnsOrIpv4)) return ProxyValidation(false, "Host de proxy inválido.")
        if (profile.port !in 1..65535) return ProxyValidation(false, "A porta deve estar entre 1 e 65535.")
        if (scope == ProxyScope.BROWSER && profile.hasCredentials) return ProxyValidation(false, "WebView não suporta autenticação de proxy com segurança; use um perfil sem credenciais.")
        if (scope == ProxyScope.TERMINAL && profile.hasCredentials) return ProxyValidation(false, "Credenciais não são permitidas no Terminal: não serão expostas em comandos ou histórico.")
        if (scope == ProxyScope.TERMINAL && profile.scheme == ProxyScheme.HTTPS) return ProxyValidation(false, "HTTPS proxy não é configurado pelo cliente de bootstrap Terminal desta versão; use HTTP ou SOCKS.")
        if (scope == ProxyScope.BROWSER && profile.scheme == ProxyScheme.SOCKS) return ProxyValidation(false, "WebView suporta apenas proxy HTTP/HTTPS nesta versão.")
        if (scope == ProxyScope.AGENT_API && profile.scheme == ProxyScheme.HTTPS) return ProxyValidation(false, "HTTPS proxy não é configurado por esta versão do OkHttp; use HTTP CONNECT ou SOCKS.")
        if (scope == ProxyScope.AGENT_API && profile.scheme == ProxyScheme.SOCKS && profile.hasCredentials) return ProxyValidation(false, "Autenticação SOCKS não é suportada por esta conexão OkHttp; use SOCKS sem credenciais ou HTTP CONNECT autenticado.")
        return ProxyValidation(true)
    }
    fun endpoint(profile: ProxyProfile): String {
        val host = profile.host.trim().let { if (it.contains(':') && !it.startsWith("[")) "[$it]" else it }
        return "${profile.scheme.name.lowercase()}://$host:${profile.port}"
    }
    fun okHttpProxy(profile: ProxyProfile): Proxy = Proxy(if (profile.scheme == ProxyScheme.SOCKS) Proxy.Type.SOCKS else Proxy.Type.HTTP, InetSocketAddress(profile.host.trim().removePrefix("[").removeSuffix("]"), profile.port))
}

object ProxyEnvironment {
    private val noProxy = "localhost,127.0.0.1,::1"
    fun variables(profile: ProxyProfile?): Map<String, String> {
        if (profile == null) return emptyMap()
        val endpoint = if (profile.scheme == ProxyScheme.SOCKS) {
            val host = profile.host.trim().let { if (it.contains(':') && !it.startsWith("[")) "[$it]" else it }
            "socks5h://$host:${profile.port}"
        } else ProxyValidator.endpoint(profile)
        return mapOf("HTTP_PROXY" to endpoint, "HTTPS_PROXY" to endpoint, "ALL_PROXY" to endpoint, "NO_PROXY" to noProxy, "http_proxy" to endpoint, "https_proxy" to endpoint, "all_proxy" to endpoint, "no_proxy" to noProxy)
    }
    fun sanitizeForDisplay(value: String): String = value.replace(Regex("(?i)(https?|socks(?:4a?|5h?)?)://[^/@]+@"), "$1://[credentials-redacted]@").replace(Regex("(?i)(password|passwd|proxy_pass)=([^\\s&]+)"), "$1=[redacted]")
}

class ProxyStore(private val context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("proxy_profiles", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val keyAlias = "cueopen.proxy.credentials"
    fun rotationSettings(): ProxyRotationSettings = runCatching {
        json.decodeFromString<ProxyRotationSettings>(prefs.getString("rotation", "") ?: "")
    }.getOrDefault(ProxyRotationSettings()).normalized()
    fun saveRotationSettings(value: ProxyRotationSettings) {
        prefs.edit().putString("rotation", json.encodeToString(value.normalized())).apply()
    }
    fun profiles(): List<ProxyProfile> = runCatching { json.decodeFromString<List<ProxyProfile>>(prefs.getString("profiles", "[]")!!) }.getOrDefault(emptyList())
    fun saveProfiles(value: List<ProxyProfile>) { prefs.edit().putString("profiles", json.encodeToString(value)).apply() }
    fun credentials(id: String): ProxyCredentials? = prefs.getString("credential.$id", null)?.let { decrypt(it) }
    fun saveCredentials(id: String, credentials: ProxyCredentials?) { prefs.edit().apply { if (credentials == null) remove("credential.$id") else putString("credential.$id", encrypt(credentials.username + "\u0000" + credentials.password)) }.apply() }
    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance("AES", "AndroidKeyStore")
        gen.init(android.security.keystore.KeyGenParameterSpec.Builder(keyAlias, android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT).setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return gen.generateKey()
    }
    private fun encrypt(value: String): String { val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, secretKey()); return Base64.encodeToString(c.iv + c.doFinal(value.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP) }
    private fun decrypt(value: String): ProxyCredentials? = runCatching { val raw = Base64.decode(value, Base64.NO_WRAP); val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, raw.copyOfRange(0, 12))); val parts = String(c.doFinal(raw.copyOfRange(12, raw.size)), Charsets.UTF_8).split('\u0000', limit = 2); ProxyCredentials(parts[0], parts[1]) }.getOrNull()
}

private fun ProxyRotationSettings.normalized(): ProxyRotationSettings = copy(
    intervalMinutes = intervalMinutes.coerceIn(1, 1_440),
    browserProfileIds = browserProfileIds.distinct().take(100),
    terminalProfileIds = terminalProfileIds.distinct().take(100),
    agentApiProfileIds = agentApiProfileIds.distinct().take(100),
)
