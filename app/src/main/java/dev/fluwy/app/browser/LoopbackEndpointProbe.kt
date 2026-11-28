package dev.fluwy.app.browser

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.Locale

/**
 * Diagnóstico rápido para URLs locais: localhost resolve no dispositivo que hospeda o WebView,
 * não necessariamente no computador onde um servidor de desenvolvimento foi iniciado.
 */
internal object LoopbackEndpointProbe {
    data class Endpoint(val scheme: String, val host: String, val port: Int) {
        val authority: String
            get() = (if (host.contains(':')) "[$host]" else host) + ":$port"
    }

    fun endpoint(url: String): Endpoint? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.trim('[', ']')?.trimEnd('.') ?: return null
        if (!isLoopbackHost(host)) return null
        val port = uri.port.takeIf { it in 1..65535 } ?: if (scheme == "https") 443 else 80
        return Endpoint(scheme, host, port)
    }

    /** Blocking TCP-only probe; callers should run this off the main thread. */
    fun isReachable(endpoint: Endpoint, timeoutMillis: Int = 500): Boolean {
        val addresses = runCatching { InetAddress.getAllByName(endpoint.host) }
            .getOrDefault(emptyArray())
            .filter { it.isLoopbackAddress }
        return addresses.any { address ->
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(address, endpoint.port), timeoutMillis)
                }
                true
            } catch (_: IOException) {
                false
            }
        }
    }

    suspend fun failureIfUnavailable(url: String): String? {
        val endpoint = endpoint(url) ?: return null
        val reachable = withContext(Dispatchers.IO) { isReachable(endpoint) }
        if (reachable) return null
        return "ERROR: servidor local inacessível em ${endpoint.authority}. `localhost` aponta para o próprio dispositivo/ambiente do WebView. Verifique se o processo está ativo, se escuta nessa porta e se o navegador e o servidor compartilham o mesmo ambiente; corrija isso antes de tentar navegar novamente."
    }

    private fun isLoopbackHost(host: String): Boolean {
        val normalized = host.lowercase(Locale.ROOT).trimEnd('.')
        if (normalized == "localhost" || normalized.endsWith(".localhost")) return true
        // Não faça resolução DNS de nomes remotos durante o preflight.
        val isIpLiteral = normalized.contains(':') ||
            normalized.matches(Regex("""\d{1,3}(?:\.\d{1,3}){3}"""))
        if (!isIpLiteral) return false
        return runCatching { InetAddress.getByName(normalized).isLoopbackAddress }.getOrDefault(false)
    }
}
