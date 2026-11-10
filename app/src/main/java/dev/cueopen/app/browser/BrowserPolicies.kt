package dev.cueopen.app.browser

import dev.cueopen.app.data.BrowserLogin
import dev.cueopen.app.data.UserScript

/**
 * Regras puras (testáveis na JVM) da seção Browser: correspondência de domínio, quem pode usar uma
 * sessão salva, quais User Scripts rodam numa URL e o "envelope" seguro de cada script.
 */
object BrowserPolicies {
    const val MAX_SCRIPT_CHARS = 50_000
    /** Erros seguidos que desligam um script sozinho (protege o browser dos agentes). */
    const val AUTO_DISABLE_AFTER_ERRORS = 3
    const val HISTORY_SIZE = 12

    /** "https://www.Exemplo.com/x" → "exemplo.com"; "*.exemplo.com" fica como está (curinga). */
    fun normalizeDomain(input: String): String {
        var d = input.trim().lowercase()
        if (d.isEmpty()) return ""
        val wildcard = d.startsWith("*.")
        if (wildcard) d = d.removePrefix("*.")
        d = d.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':').removePrefix("www.").trim('.')
        if (d.isEmpty() || !Regex("^[a-z0-9-]+(\\.[a-z0-9-]+)+$").matches(d) && d != "localhost") return ""
        return if (wildcard) "*.$d" else d
    }

    fun hostOf(url: String): String =
        url.substringAfter("://", "").substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':').lowercase().removePrefix("www.")

    /** "exemplo.com" vale para o domínio e subdomínios; "*.exemplo.com" só para subdomínios. */
    fun domainMatches(host: String, pattern: String): Boolean {
        val h = host.lowercase().removePrefix("www.")
        val p = pattern.lowercase().trim()
        if (h.isEmpty() || p.isEmpty()) return false
        return if (p.startsWith("*.")) h.endsWith("." + p.removePrefix("*.")) else h == p || h.endsWith(".$p")
    }

    /** Scripts que rodam nesta URL para este agente, em ordem de prioridade. Sem domínio = nunca roda. */
    fun scriptsFor(url: String, agentId: String?, scripts: List<UserScript>): List<UserScript> {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return emptyList()
        val host = hostOf(url)
        return scripts.filter { s ->
            s.enabled && s.code.isNotBlank() && s.domains.isNotEmpty() && s.domains.any { domainMatches(host, it) } &&
                (s.pathContains.isBlank() || url.contains(s.pathContains)) &&
                (s.agentIds.isEmpty() || (agentId != null && agentId in s.agentIds))
        }.sortedByDescending { it.priority }
    }

    /** Sessão salva que cobre este host (a mais específica). */
    fun loginFor(host: String, logins: List<BrowserLogin>): BrowserLogin? =
        logins.filter { domainMatches(host, it.domain) }.maxByOrNull { it.domain.length }

    /** null = pode usar; senão o motivo. Sessão revogada nunca é usada. */
    fun loginDenial(login: BrowserLogin, agentId: String?): String? = when {
        login.status == "revoked" -> "a sessão salva de ${login.domain} foi revogada pelo usuário"
        login.allowedAgentIds.isNotEmpty() && (agentId == null || agentId !in login.allowedAgentIds) ->
            "a sessão salva de ${login.domain} não está liberada para este agente"
        else -> null
    }

    /** Validação antes de salvar um script. */
    fun validateScript(name: String, code: String, domains: List<String>): String? = when {
        name.isBlank() -> "dê um nome ao script"
        code.isBlank() -> "o script está vazio"
        code.length > MAX_SCRIPT_CHARS -> "script grande demais (máx. ${MAX_SCRIPT_CHARS / 1000} mil caracteres)"
        domains.isEmpty() -> "informe ao menos um domínio — scripts não rodam em todos os sites"
        domains.any { normalizeDomain(it).isEmpty() } -> "domínio inválido: ${domains.first { normalizeDomain(it).isEmpty() }}"
        else -> null
    }

    /**
     * Envelope do script: roda uma vez por documento (sem injeção duplicada), captura erros síncronos e
     * devolve "ok" | "dup" | "err:<mensagem>". O WebView dos agentes não expõe pontes do app ao JS, então
     * o script só enxerga a própria página — sem credenciais do app, arquivos ou outros agentes.
     */
    fun wrap(script: UserScript): String {
        val key = script.id.filter { it.isLetterOrDigit() }
        return """(function(){try{var g=window.__cueUS||(window.__cueUS={});if(g['$key'])return 'dup';g['$key']=1;
(function(){
${script.code}
})();
return 'ok';}catch(e){return 'err:'+String((e&&e.message)||e).slice(0,200);}})()"""
    }

    /** Resultado do evaluateJavascript (vem entre aspas JSON). */
    fun parseResult(raw: String?): String = raw.orEmpty().trim().removeSurrounding("\"").replace("\\\"", "\"")

    /** Aplica o resultado de uma execução ao registro do script (contadores, histórico, auto-desligamento). */
    fun record(s: UserScript, host: String, result: String, now: Long, clock: String): UserScript {
        if (result == "dup") return s
        val ok = result == "ok"
        val consecutive = if (ok) 0 else s.consecutiveErrors + 1
        val line = "$clock $host · " + if (ok) "ok" else "erro: ${result.removePrefix("err:").take(120)}"
        return s.copy(
            runs = s.runs + 1, errors = s.errors + if (ok) 0 else 1, consecutiveErrors = consecutive,
            lastError = if (ok) s.lastError else result.removePrefix("err:").take(200), lastRunAt = now,
            history = (s.history + line).takeLast(HISTORY_SIZE),
            enabled = s.enabled && consecutive < AUTO_DISABLE_AFTER_ERRORS,
        )
    }
}
