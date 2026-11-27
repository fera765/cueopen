package dev.fluwy.app.uiconnector.custom

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Guarda de rede dos conectores criados pelo usuário: só HTTPS, só o host declarado em baseUrl,
 * porta 443, sem IP literal, sem redes privadas/locais, sem redirecionamentos. Impede que uma
 * definição de terceiros use o app como ponte para a rede local do usuário (SSRF).
 */
object UrlGuard {
    private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
    private val HOST = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$")
    private val BAD_SUFFIX = listOf(".local", ".internal", ".localdomain", ".lan", ".home", ".corp", ".intranet", ".localhost")

    /** null = host aceitável para ser declarado; senão o motivo. */
    fun hostProblem(raw: String): String? {
        val h = raw.trim().lowercase()
        if (h.isEmpty() || h.length > 253) return "host vazio ou longo demais"
        if (h.contains(':') || h.startsWith("[") || IPV4.matches(h)) return "IP literal não é permitido; use um domínio"
        if (!HOST.matches(h)) return "host '$h' inválido (precisa ser um domínio completo, ex.: api.exemplo.com)"
        if (BAD_SUFFIX.any { h.endsWith(it) } || h == "localhost") return "host '$h' é de rede local/interna"
        if (h.substringAfterLast('.').all { it.isDigit() }) return "host '$h' inválido"
        return null
    }

    /** Extrai o host de uma baseUrl `https://host` (sem caminho/porta/credenciais) ou devolve o erro. */
    fun baseHost(baseUrl: String): Pair<String?, String?> {
        val u = try { URI(baseUrl.trim()) } catch (t: Exception) { return null to "baseUrl inválida" }
        if (u.scheme?.lowercase() != "https") return null to "baseUrl deve começar com https://"
        if (u.userInfo != null) return null to "baseUrl não pode conter usuário/senha"
        if (u.port != -1 && u.port != 443) return null to "somente a porta 443 é permitida"
        if (!u.query.isNullOrEmpty() || !u.fragment.isNullOrEmpty()) return null to "baseUrl não pode ter query/fragmento"
        val host = u.host?.lowercase() ?: return null to "baseUrl sem host"
        val p = hostProblem(host)
        return if (p != null) null to p else host to null
    }

    /** Verificação da URL FINAL de cada requisição. [permissive] só nos testes (servidor local). */
    fun check(url: String, host: String, permissive: Boolean = false): String? = check(url, setOf(host), permissive)

    /** Hosts permitidos: o de baseUrl + os de allowedHosts. */
    fun check(url: String, hosts: Set<String>, permissive: Boolean = false): String? {
        val u = try { URI(url) } catch (t: Exception) { return "URL inválida" }
        if (u.userInfo != null) return "URL com credenciais não é permitida"
        val h = u.host?.lowercase() ?: return "URL sem host"
        if (hosts.none { it.equals(h, ignoreCase = true) }) return "host '$h' não está entre os declarados (${hosts.joinToString()})"
        if (permissive) return null
        if (u.scheme?.lowercase() != "https") return "somente HTTPS"
        if (u.port != -1 && u.port != 443) return "somente a porta 443"
        return null
    }
}

/** DNS que recusa qualquer endereço de rede privada/local (anti-SSRF e anti DNS-rebinding). */
class SafeDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val all = Dns.SYSTEM.lookup(hostname)
        if (all.any { blocked(it) }) throw UnknownHostException("endereço bloqueado (rede privada/local): $hostname")
        return all
    }

    companion object {
        fun blocked(a: InetAddress): Boolean {
            if (a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isMulticastAddress) return true
            val b = a.address
            if (b.size == 4) {
                val b0 = b[0].toInt() and 0xFF
                val b1 = b[1].toInt() and 0xFF
                if (b0 == 100 && b1 in 64..127) return true // CGNAT
                if (b0 == 0) return true
            }
            if (b.size == 16 && (b[0].toInt() and 0xFE) == 0xFC) return true // ULA fc00::/7
            return false
        }
    }
}

data class HttpReq(val method: String, val url: String, val headers: Map<String, String>, val body: String?, val contentType: String = "application/json; charset=utf-8")
data class HttpRes(val code: Int, val headers: Map<String, List<String>>, val body: String)

interface HttpExec {
    suspend fun exec(r: HttpReq): HttpRes
}

/** Executor real (OkHttp): sem redirecionamentos, 20 s, resposta limitada a 2 MB (feeds RSS grandes). */
class OkHttpExec(
    permissive: Boolean = false,
    private val clientProvider: ((OkHttpClient) -> OkHttpClient)? = null,
) : HttpExec {
    companion object { const val MAX_BODY = 2L * 1024 * 1024 }
    private val baseClient: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .apply { if (!permissive) dns(SafeDns()) }
        .build()

    private fun client(): OkHttpClient = clientProvider?.invoke(baseClient) ?: baseClient

    override suspend fun exec(r: HttpReq): HttpRes = withContext(Dispatchers.IO) {
        val m = r.method.uppercase()
        val hasBody = m == "POST" || m == "PUT" || m == "PATCH" || (m == "DELETE" && r.body != null)
        val body = if (hasBody) (r.body ?: "").toRequestBody(r.contentType.toMediaType()) else null
        val req = Request.Builder().url(r.url).method(m, body).apply {
            r.headers.forEach { (k, v) -> header(k, v) }
        }.build()
        client().newCall(req).execute().use { res ->
            HttpRes(res.code, res.headers.toMultimap(), res.peekBody(MAX_BODY).string())
        }
    }
}
