package dev.cueopen.app.browser

/**
 * Cabeçalho de userscript no padrão Tampermonkey/Greasemonkey:
 *
 * // ==UserScript==
 * // @name         Meu script
 * // @version      1.0
 * // @match        https://*.exemplo.com/*
 * // @grant        GM_addStyle
 * // ==/UserScript==
 *
 * Nome, versão, sites etc. saem daqui — o usuário só cola ou importa o arquivo. Puro (JVM).
 */
data class UserScriptMeta(
    val name: String,
    val namespace: String = "",
    val version: String = "",
    val description: String = "",
    val author: String = "",
    val matches: List<String> = emptyList(),
    val includes: List<String> = emptyList(),
    val excludes: List<String> = emptyList(),
    val requires: List<String> = emptyList(),
    val grants: List<String> = emptyList(),
    /** document-start | document-end | document-idle */
    val runAt: String = "document-idle",
    val noframes: Boolean = false,
) {
    /** Resumo legível de onde roda (para a UI). */
    val whereLabel: String get() = (matches + includes).joinToString().ifBlank { "nenhum site" }

    /** Roda em qualquer site (o usuário declarou explicitamente). */
    val allSites: Boolean get() = (matches + includes).any { it == "<all_urls>" || it == "*" || it == "*://*/*" || it == "http*://*/*" }

    companion object {
        private val OPEN = Regex("^\\s*//\\s*==UserScript==\\s*$", RegexOption.MULTILINE)
        private val CLOSE = Regex("^\\s*//\\s*==/UserScript==\\s*$", RegexOption.MULTILINE)
        private val LINE = Regex("^\\s*//\\s*@([A-Za-z][\\w:-]*)(?:\\s+(.*?))?\\s*$")

        val EXAMPLE = """
            // ==UserScript==
            // @name         Nome do script
            // @version      1.0
            // @description  O que ele faz
            // @match        https://*.exemplo.com/*
            // @grant        none
            // ==/UserScript==
        """.trimIndent()

        /** Lê o cabeçalho. Retorna (meta, null) ou (null, erro explicando o formato esperado). */
        fun parse(source: String): Pair<UserScriptMeta?, String?> {
            val open = OPEN.find(source) ?: return null to "Faltou o cabeçalho // ==UserScript== no início do script."
            val close = CLOSE.find(source, open.range.last) ?: return null to "O cabeçalho não foi fechado com // ==/UserScript==."
            val header = source.substring(open.range.last + 1, close.range.first)
            val tags = mutableListOf<Pair<String, String>>()
            header.lines().forEach { l ->
                if (l.isBlank()) return@forEach
                val m = LINE.matchEntire(l) ?: return@forEach
                tags += m.groupValues[1].lowercase() to m.groupValues[2].trim()
            }
            fun first(k: String) = tags.firstOrNull { it.first == k }?.second.orEmpty()
            fun all(k: String) = tags.filter { it.first == k }.map { it.second }.filter { it.isNotEmpty() }
            // @name:pt-BR também vale quando não há @name puro.
            val name = first("name").ifBlank { tags.firstOrNull { it.first.startsWith("name:") }?.second.orEmpty() }
            if (name.isBlank()) return null to "O cabeçalho precisa de // @name com o nome do script."
            val matches = all("match")
            val includes = all("include")
            if (matches.isEmpty() && includes.isEmpty()) return null to "Informe onde o script roda com // @match (ex.: https://*.exemplo.com/*) ou // @include."
            (matches.firstOrNull { !UserScriptMatcher.isValidMatch(it) })?.let { return null to "@match inválido: \"$it\". Formato: esquema://host/caminho, ex.: *://*.exemplo.com/*" }
            val runAt = first("run-at").lowercase().ifBlank { "document-idle" }
                .let { if (it in setOf("document-start", "document-end", "document-idle", "document-body")) it else "document-idle" }
            val body = source.substring(close.range.last + 1)
            if (body.isBlank()) return null to "O script não tem código depois do cabeçalho."
            return UserScriptMeta(
                name = name.take(120), namespace = first("namespace").take(200), version = first("version").take(40),
                description = first("description").ifBlank { tags.firstOrNull { it.first.startsWith("description:") }?.second.orEmpty() }.take(500),
                author = first("author").take(120), matches = matches, includes = includes, excludes = all("exclude") + all("exclude-match"),
                requires = all("require"), grants = all("grant"), runAt = runAt, noframes = tags.any { it.first == "noframes" },
            ) to null
        }
    }
}

/** Correspondência de URL no padrão Tampermonkey: @match (padrão Chrome), @include/@exclude (glob ou /regex/). */
object UserScriptMatcher {
    private val SCHEMES = setOf("*", "http", "https", "http*", "file")

    fun isValidMatch(p: String): Boolean {
        if (p == "<all_urls>" || p == "*") return true
        val scheme = p.substringBefore("://", "")
        if (scheme !in SCHEMES) return false
        val rest = p.substringAfter("://")
        val host = rest.substringBefore('/')
        if (!rest.contains('/') && scheme != "file") return false
        return host == "*" || host.isEmpty() && scheme == "file" || (host.isNotEmpty() && !host.substring(if (host.startsWith("*.")) 2 else 0).contains('*'))
    }

    /** @match: esquema, host (com *. curinga) e caminho (com *). */
    fun matchPattern(pattern: String, url: String): Boolean {
        if (pattern == "<all_urls>" || pattern == "*") return url.startsWith("http://") || url.startsWith("https://")
        val scheme = pattern.substringBefore("://", "")
        val uScheme = url.substringBefore("://", "")
        val schemeOk = when (scheme) { "*", "http*" -> uScheme == "http" || uScheme == "https"; else -> scheme == uScheme }
        if (!schemeOk) return false
        val pRest = pattern.substringAfter("://")
        val uRest = url.substringAfter("://")
        val pHost = pRest.substringBefore('/').lowercase()
        val uHost = uRest.substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':').lowercase()
        val hostOk = when {
            pHost == "*" -> true
            pHost.startsWith("*.") -> uHost == pHost.removePrefix("*.") || uHost.endsWith(pHost.removePrefix("*"))
            else -> uHost == pHost
        }
        if (!hostOk) return false
        val pPath = "/" + pRest.substringAfter('/', "")
        val uPath = "/" + uRest.substringAfter('/', "")
        return glob(pPath, uPath)
    }

    /** Glob com * (qualquer sequência). Sem regex: nada de padrões que quebram no Android. */
    fun glob(pattern: String, text: String): Boolean {
        var p = 0; var t = 0; var star = -1; var mark = 0
        while (t < text.length) {
            if (p < pattern.length && (pattern[p] == text[t])) { p++; t++ }
            else if (p < pattern.length && pattern[p] == '*') { star = p++; mark = t }
            else if (star >= 0) { p = star + 1; t = ++mark }
            else return false
        }
        while (p < pattern.length && pattern[p] == '*') p++
        return p == pattern.length
    }

    /** @include/@exclude: "/regex/" ou glob sobre a URL inteira (o * do Tampermonkey cobre tudo). */
    fun includePattern(pattern: String, url: String): Boolean {
        if (pattern == "*") return true
        if (pattern.length > 2 && pattern.startsWith("/") && pattern.endsWith("/"))
            return runCatching { Regex(pattern.substring(1, pattern.length - 1)).containsMatchIn(url) }.getOrDefault(false)
        return glob(pattern.replace(".tld", ".*"), url)
    }

    fun matches(meta: UserScriptMeta, url: String): Boolean = matches(meta.matches, meta.includes, meta.excludes, url)

    fun matches(matches: List<String>, includes: List<String>, excludes: List<String>, url: String): Boolean {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        val inc = matches.any { matchPattern(it, url) } || includes.any { includePattern(it, url) }
        return inc && excludes.none { (if (isValidMatch(it)) matchPattern(it, url) else includePattern(it, url)) }
    }
}
