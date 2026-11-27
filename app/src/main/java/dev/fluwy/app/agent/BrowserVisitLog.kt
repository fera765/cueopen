package dev.fluwy.app.agent

import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Memória de navegação por tarefa: o que o agente já abriu, quantas vezes e com que resultado.
 *
 * Evita o padrão visto nos logs: abrir a MESMA busca/URL dezenas de vezes (às vezes só com os
 * parâmetros em outra ordem) e ver sempre o mesmo conteúdo, em vez de rolar, abrir os anúncios e
 * aprofundar. O histórico entra no prompt e a navegação repetida sem sentido é recusada com orientação.
 * Puro (sem Android) — testável na JVM.
 */
object BrowserVisitLog {
    const val WINDOW_MS = 30 * 60_000L

    data class Visit(val key: String, val url: String, val outcome: String, val count: Int, val at: Long)

    private val bySession = ConcurrentHashMap<String, LinkedHashMap<String, Visit>>()

    /** Mesma página = mesmo host/caminho e mesmos parâmetros, em qualquer ordem. */
    fun normalize(url: String): String = runCatching {
        val u = URI(url.trim())
        val host = hostKey(u.host ?: return url.trim().lowercase())
        val path = (u.rawPath ?: "").trimEnd('/').ifEmpty { "/" }
        val query = (u.rawQuery ?: "").split('&').filter { it.isNotBlank() }
            .map { p -> URLDecoder.decode(p, "UTF-8").replace('+', ' ').lowercase().trim() }
            .filterNot { it.endsWith("=") || it.startsWith("_rdr") || it.startsWith("_rdc") }.sorted().joinToString("&")
        host + path + (if (query.isNotEmpty()) "?$query" else "")
    }.getOrElse { url.trim().lowercase() }

    /** www., m., web., mbasic. e touch. são o mesmo site. */
    fun hostKey(host: String): String = host.lowercase().replace(Regex("^(www|m|web|mbasic|touch|mobile)\\."), "")

    /**
     * URL canônica para abrir: versões móveis/alternativas do Facebook redirecionam em cadeia (_rdr) e
     * caem em páginas diferentes; sempre usa www e remove marcadores de redirecionamento.
     */
    fun canonical(url: String): String = runCatching {
        val u = URI(url.trim())
        val host = u.host ?: return url
        val fb = Regex("^(m|web|mbasic|touch|mobile)\\.facebook\\.com$").matches(host.lowercase())
        val q = u.rawQuery?.split('&')?.filterNot { it.startsWith("_rdr") || it.startsWith("_rdc") }?.joinToString("&")
        if (!fb && q == u.rawQuery) url
        else {
            // monta como texto para não recodificar %xx do caminho
            val base = "${u.scheme}://" + (if (fb) "www.facebook.com" else u.rawAuthority) + (u.rawPath ?: "")
            (if (q.isNullOrEmpty()) base else "$base?$q") + (u.rawFragment?.let { "#$it" } ?: "")
        }
    }.getOrElse { url }

    // ---------- disjuntor por site: falhas seguidas no mesmo site bloqueiam novas tentativas por um tempo
    const val HOST_FAILS_TO_TRIP = 4
    const val HOST_COOLDOWN_MS = 10 * 60_000L
    private data class HostFails(var count: Int = 0, var lastAt: Long = 0, var lastOutcome: String = "")
    private val hostFails = ConcurrentHashMap<String, HostFails>()

    private fun hostOf(url: String) = runCatching { URI(url.trim()).host?.let { hostKey(it) } }.getOrNull()

    fun outcomeOf(result: String): String = when {
        result.startsWith("OK") -> "ok"
        result.startsWith("PARTIAL") -> "parcial"
        result.startsWith("TIMEOUT") -> "timeout"
        result.startsWith("ERROR") || result.startsWith("RECUSADO") -> "erro"
        else -> "ok"
    }

    /**
     * Decide se a navegação deve acontecer. Retorna null para seguir; ou a mensagem para o agente.
     * @param currentUrl URL da aba ativa agora (para não recarregar a página que já está aberta).
     */
    fun check(sessionId: String, url: String, currentUrl: String?, now: Long = System.currentTimeMillis()): String? {
        hostOf(url)?.let { h ->
            val f = hostFails[h]
            if (f != null && f.count >= HOST_FAILS_TO_TRIP && now - f.lastAt < HOST_COOLDOWN_MS)
                return "RECUSADO: o browser falhou ${f.count}× seguidas em $h (último resultado: ${f.lastOutcome}). Repetir agora não resolve. " +
                    "A causa detalhada está no log de erros (Configurações › Logs). Volte a esse site em ~${(HOST_COOLDOWN_MS - (now - f.lastAt)) / 60_000 + 1} min, " +
                    "use outra fonte, ou peça ao usuário para abrir o site na aba Browser e confirmar que carrega."
        }
        val key = normalize(url)
        val v = bySession[sessionId]?.get(key)?.takeIf { now - it.at < WINDOW_MS } ?: return null
        val failed = v.outcome == "timeout" || v.outcome == "erro"
        return when {
            failed && v.count >= 2 ->
                "RECUSADO: você já abriu esta mesma página ${v.count}× nesta tarefa e falhou (${v.outcome}). Repetir não muda o resultado. " +
                    "Use outra abordagem: outra fonte/consulta, browser_read na aba atual, ou peça ao usuário para abrir/entrar no site pela aba Browser."
            !failed && currentUrl != null && normalize(currentUrl) == key ->
                "A página já está aberta (aberta ${v.count}× nesta tarefa). Não recarregue: use browser_read para ler, browser_scroll para ver mais resultados e browser_click para abrir os itens."
            !failed && v.count >= 3 ->
                "RECUSADO: esta mesma página já foi aberta ${v.count}× nesta tarefa. Use o que já leu (veja 'Histórico do browser') ou aprofunde: role a lista, abra cada anúncio/link e analise o conteúdo."
            else -> null
        }
    }

    fun record(sessionId: String, url: String, result: String, now: Long = System.currentTimeMillis()) {
        val key = normalize(url)
        val map = bySession.getOrPut(sessionId) { LinkedHashMap() }
        synchronized(map) {
            val prev = map.remove(key)?.takeIf { now - it.at < WINDOW_MS }
            map[key] = Visit(key, url, outcomeOf(result), (prev?.count ?: 0) + 1, now)
            while (map.size > 40) map.remove(map.keys.first())
        }
        hostOf(url)?.let { h ->
            val outcome = outcomeOf(result)
            if (outcome == "ok" || outcome == "parcial") hostFails.remove(h)
            else hostFails.getOrPut(h) { HostFails() }.apply { count++; lastAt = now; lastOutcome = outcome }
        }
    }

    /** Linhas para o system prompt (mais recentes primeiro). Vazio se não houve navegação. */
    fun summary(sessionId: String, max: Int = 10, now: Long = System.currentTimeMillis()): List<String> {
        val map = bySession[sessionId] ?: return emptyList()
        val recent = synchronized(map) { map.values.toList() }.filter { now - it.at < WINDOW_MS }.takeLast(max).reversed()
        return recent.map { "- [${it.outcome}${if (it.count > 1) " ×${it.count}" else ""}] ${it.url.take(160)}" }
    }

    fun clear(sessionId: String) { bySession.remove(sessionId) }

    /** Testes: zera o disjuntor de um site. */
    internal fun resetHost(host: String) { hostFails.remove(hostKey(host)) }
}
