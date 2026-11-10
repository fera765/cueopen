package dev.cueopen.app.browser

import dev.cueopen.app.data.BrowserLogin
import dev.cueopen.app.data.UserScript

/**
 * Regras puras (testáveis na JVM) da seção Browser: correspondência de domínio, quem pode usar uma
 * sessão salva, quais User Scripts rodam numa URL e o "envelope" seguro de cada script.
 */
object BrowserPolicies {
    const val MAX_SCRIPT_CHARS = 500_000
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

    /** Script com cabeçalho Tampermonkey usa @match/@include/@exclude; scripts antigos usam a lista de domínios. */
    fun runsOn(s: UserScript, url: String): Boolean =
        if (s.matches.isNotEmpty() || s.includes.isNotEmpty()) UserScriptMatcher.matches(s.matches, s.includes, s.excludes, url)
        else (url.startsWith("http://") || url.startsWith("https://")) && s.domains.isNotEmpty() && s.domains.any { domainMatches(hostOf(url), it) } &&
            (s.pathContains.isBlank() || url.contains(s.pathContains))

    /** Scripts que rodam nesta URL para este agente, em ordem de prioridade. Sem @match/domínio = nunca roda. */
    fun scriptsFor(url: String, agentId: String?, scripts: List<UserScript>): List<UserScript> =
        scripts.filter { s ->
            s.enabled && s.code.isNotBlank() && runsOn(s, url) && (s.agentIds.isEmpty() || (agentId != null && agentId in s.agentIds))
        }.sortedByDescending { it.priority }

    const val GM_MAX_RESPONSE = 5L * 1024 * 1024

    /**
     * GM_xmlhttpRequest pode ir a qualquer site da internet (liberal, como no Tampermonkey), mas nunca à rede
     * do próprio aparelho/local (localhost, 10.x, 192.168.x…), nem a esquemas que não sejam http(s).
     */
    fun gmRequestDenial(url: String): String? {
        val scheme = url.substringBefore("://", "").lowercase()
        if (scheme != "http" && scheme != "https") return "só http/https"
        val host = url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
            .let { if (it.startsWith("[")) it.substringBefore(']') + "]" else it.substringBefore(':') }.lowercase().trim('[', ']')
        if (host.isEmpty() || host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") || host == "0.0.0.0") return "rede local bloqueada"
        val parts = host.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size == 4 && host.count { it == '.' } == 3) {
            val (a, b) = parts[0] to parts[1]
            if (a == 127 || a == 10 || a == 0 || (a == 169 && b == 254) || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 100 && b in 64..127))
                return "rede local bloqueada"
        }
        if (host.contains(':') && (host == "::1" || host.startsWith("fe80") || host.startsWith("fc") || host.startsWith("fd"))) return "rede local bloqueada"
        return null
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

    /**
     * Monta o registro a partir do cabeçalho. Mesmo @name + @namespace de um script instalado = ATUALIZAÇÃO
     * (como no Tampermonkey): mantém id, ligado/desligado, agentes e estatísticas.
     */
    fun fromMeta(meta: UserScriptMeta, code: String, requireCode: String, source: String, existing: UserScript?): UserScript =
        (existing ?: UserScript(name = meta.name, code = code)).copy(
            name = meta.name, description = meta.description, code = code, namespace = meta.namespace, version = meta.version,
            author = meta.author, matches = meta.matches, includes = meta.includes, excludes = meta.excludes, grants = meta.grants,
            requires = meta.requires, requireCode = requireCode, runAt = meta.runAt, source = source,
            domains = emptyList(), pathContains = "", consecutiveErrors = 0, lastError = "",
            enabled = existing?.enabled ?: true,
        )

    fun findExisting(meta: UserScriptMeta, scripts: List<UserScript>): UserScript? =
        scripts.firstOrNull { it.name.equals(meta.name, true) && it.namespace == meta.namespace }

    /** Validação antes de salvar um script. */
    fun validateScript(name: String, code: String, domains: List<String>): String? = when {
        name.isBlank() -> "dê um nome ao script"
        code.isBlank() -> "o script está vazio"
        code.length > MAX_SCRIPT_CHARS -> "script grande demais (máx. ${MAX_SCRIPT_CHARS / 1000} mil caracteres)"
        domains.isEmpty() -> "informe ao menos um domínio — scripts não rodam em todos os sites"
        domains.any { normalizeDomain(it).isEmpty() } -> "domínio inválido: ${domains.first { normalizeDomain(it).isEmpty() }}"
        else -> null
    }

    /** Literal de string JS seguro (sem org.json, para rodar também nos testes JVM). */
    fun js(v: String): String = buildString {
        append('"')
        for (ch in v) when (ch) {
            '"' -> append("\\\""); '\\' -> append("\\\\"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
            '\u2028' -> append("\\u2028"); '\u2029' -> append("\\u2029")
            else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
        }
        append('"')
    }

    private fun jsArray(items: List<String>) = items.joinToString(",", "[", "]") { js(it) }

    /**
     * Envelope do script: roda uma vez por documento (sem injeção duplicada), captura erros síncronos e
     * devolve "ok" | "dup" | "err:<mensagem>". Expõe a API do Tampermonkey (GM_* e GM.*): estilos, valores
     * persistentes, GM_xmlhttpRequest entre domínios (pela ponte do app, protegida por [token]), abas,
     * clipboard e GM_info. Os @require baixados na instalação rodam antes, no mesmo escopo.
     */
    fun wrap(script: UserScript, token: String = ""): String {
        val key = script.id.filter { it.isLetterOrDigit() }
        val info = "{script:{name:${js(script.name)},version:${js(script.version)},namespace:${js(script.namespace)},description:${js(script.description)}," +
            "matches:${jsArray(script.matches)},includes:${jsArray(script.includes)}},scriptHandler:'Fluwy Bot',version:'1.0'}"
        return """(function(){try{var g=window.__cueUS||(window.__cueUS={});if(g['$key'])return 'dup';g['$key']=1;
var GM_info=$info,unsafeWindow=window,__B=window.__cueGMBridge,__T=${js(token)},__P='__cueGM_${key}_';
function GM_addStyle(c){var s=document.createElement('style');s.textContent=c;(document.head||document.documentElement).appendChild(s);return s;}
function GM_setValue(k,v){try{localStorage.setItem(__P+k,JSON.stringify(v));}catch(e){}}
function GM_getValue(k,d){try{var v=localStorage.getItem(__P+k);return v===null?d:JSON.parse(v);}catch(e){return d;}}
function GM_deleteValue(k){try{localStorage.removeItem(__P+k);}catch(e){}}
function GM_listValues(){var r=[];try{for(var i=0;i<localStorage.length;i++){var k=localStorage.key(i);if(k&&k.indexOf(__P)===0)r.push(k.slice(__P.length));}}catch(e){}return r;}
function GM_log(){try{console.log.apply(console,['[userscript '+GM_info.script.name+']'].concat([].slice.call(arguments)));}catch(e){}}
function GM_openInTab(u){return window.open(u,'_blank');}
function GM_setClipboard(t){try{navigator.clipboard.writeText(String(t));}catch(e){}}
function GM_notification(d){GM_log('notificação:',typeof d==='string'?d:(d&&d.text)||'');}
function GM_registerMenuCommand(){return 0;}function GM_unregisterMenuCommand(){}
function GM_getResourceText(){return '';}function GM_getResourceURL(){return '';}
function GM_xmlhttpRequest(d){d=d||{};var h={abort:function(){}};var url;try{url=new URL(d.url,location.href).href;}catch(e){d.onerror&&d.onerror({error:'URL inválida'});return h;}
 function done(r){if(!r.error&&d.responseType==='json'){try{r.response=JSON.parse(r.responseText);}catch(e){}}else if(!r.error){r.response=r.responseText;}
  if(r.error){d.onerror&&d.onerror(r);}else{d.onload&&d.onload(r);}d.onloadend&&d.onloadend(r);}
 if(!__B){fetch(url,{method:d.method||'GET',headers:d.headers||{},body:d.data}).then(function(x){return x.text().then(function(t){done({status:x.status,statusText:x.statusText,responseText:t,finalUrl:x.url,responseHeaders:''});});}).catch(function(e){done({error:String(e)});});return h;}
 var cb=window.__cueGMcbs||(window.__cueGMcbs={});var id='c'+Math.random().toString(36).slice(2);cb[id]=function(r){delete cb[id];done(r);};
 __B.request(__T,JSON.stringify({url:url,method:d.method||'GET',headers:d.headers||{},data:d.data==null?null:String(d.data),timeout:d.timeout||30000,anonymous:!!d.anonymous}),id);return h;}
var GM={info:GM_info,addStyle:function(c){return Promise.resolve(GM_addStyle(c));},setValue:function(k,v){GM_setValue(k,v);return Promise.resolve();},
 getValue:function(k,d){return Promise.resolve(GM_getValue(k,d));},deleteValue:function(k){GM_deleteValue(k);return Promise.resolve();},listValues:function(){return Promise.resolve(GM_listValues());},
 xmlHttpRequest:function(d){return new Promise(function(res,rej){var o=Object.assign({},d);o.onload=function(r){d.onload&&d.onload(r);res(r);};o.onerror=function(r){d.onerror&&d.onerror(r);rej(r);};GM_xmlhttpRequest(o);});},
 openInTab:GM_openInTab,setClipboard:GM_setClipboard,notification:GM_notification,log:GM_log,registerMenuCommand:GM_registerMenuCommand};
${script.requireCode}
;(function(){
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
