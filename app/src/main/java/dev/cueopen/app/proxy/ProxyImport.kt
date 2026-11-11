package dev.cueopen.app.proxy

/** Importa perfis de proxy de uma lista de texto: um proxy por linha, sem login e senha. */
object ProxyImport {
    data class Result(val profiles: List<ProxyProfile>, val duplicates: Int, val withCredentials: Int, val invalid: Int)

    const val MAX_PROFILES = 500
    private val hostPattern = Regex("^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$")

    /**
     * Formatos aceitos por linha: `ip:porta`, `host:porta`, `http://ip:porta`, `https://…`, `socks://…`, `socks4://…`,
     * `socks5://…`. Linhas vazias e comentários (# ou //) são ignorados. Linhas com usuário/senha
     * (`user:pass@host:porta` ou `host:porta:user:pass`) não são importadas: os perfis não guardam credenciais.
     */
    fun parse(text: String, existing: List<ProxyProfile> = emptyList()): Result {
        val seen = existing.map { key(it.scheme, it.host, it.port) }.toMutableSet()
        val out = mutableListOf<ProxyProfile>()
        var duplicates = 0; var withCredentials = 0; var invalid = 0
        val base = System.nanoTime()
        for (raw in text.lineSequence()) {
            val line = raw.trim().trim(',', ';').trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue
            var rest = line
            var scheme = ProxyScheme.HTTP
            val sep = rest.indexOf("://")
            if (sep > 0) {
                scheme = when (rest.substring(0, sep).lowercase()) {
                    "http" -> ProxyScheme.HTTP
                    "https" -> ProxyScheme.HTTPS
                    "socks", "socks4", "socks4a", "socks5", "socks5h" -> ProxyScheme.SOCKS
                    else -> { invalid++; continue }
                }
                rest = rest.substring(sep + 3)
            }
            rest = rest.substringBefore('/').substringBefore(' ').substringBefore('\t')
            if ('@' in rest) { withCredentials++; continue }
            val parts = rest.split(':')
            if (parts.size > 2) { withCredentials++; continue }
            val host = parts[0].trim()
            val port = parts.getOrNull(1)?.trim()?.toIntOrNull()
            if (parts.size != 2 || port == null || port !in 1..65535 || host.length > 253 || !hostPattern.matches(host)) { invalid++; continue }
            if (!seen.add(key(scheme, host, port))) { duplicates++; continue }
            if (out.size >= MAX_PROFILES) break
            out += ProxyProfile(id = "proxy-$base-${out.size}", name = "$host:$port", scheme = scheme, host = host, port = port, hasCredentials = false)
        }
        return Result(out, duplicates, withCredentials, invalid)
    }

    private fun key(scheme: ProxyScheme, host: String, port: Int) = scheme.name + "|" + host.lowercase() + "|" + port
}
