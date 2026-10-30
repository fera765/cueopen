package dev.cueopen.app.agent

import java.util.concurrent.ConcurrentHashMap

/**
 * Cofre de dados SEGUROS enviados pelo usuário no formulário do `ask_secure` (API keys, tokens,
 * e-mail/senha, campos de formulário). Kotlin puro — testável na JVM.
 *
 * Regras:
 *  - Os valores ficam SÓ na memória do aparelho, por tarefa (sessão). Nunca entram no histórico da
 *    conversa, no JSON salvo da sessão, no log de erros, nem em nada enviado ao modelo.
 *  - O modelo só conhece os NOMES. Para usar um valor ele escreve o marcador `{{secret:nome}}` nos
 *    argumentos de uma ferramenta; o app troca pelo valor real NA EXECUÇÃO ([resolve]).
 *  - Toda saída de ferramenta passa por [redact] antes de voltar ao modelo: se o valor aparecer
 *    (eco, URL, cabeçalho, base64), vira `‹secret:nome›`.
 *  - Ao fechar o app os valores somem (o agente pede de novo pelo formulário seguro se precisar).
 */
object SecretVault {
    val NAME = Regex("^[a-z][a-z0-9_]{0,31}$")
    private val REF = Regex("\\{\\{\\s*secret:([a-z][a-z0-9_]{0,31})\\s*\\}\\}")
    private val EXACT_REF = Regex("^\\s*\\{\\{\\s*secret:([a-z][a-z0-9_]{0,31})\\s*\\}\\}\\s*$")
    private val vault = ConcurrentHashMap<String, LinkedHashMap<String, String>>()

    fun put(sessionId: String, values: Map<String, String>) {
        val m = vault.getOrPut(sessionId) { LinkedHashMap() }
        synchronized(m) { values.forEach { (k, v) -> if (NAME.matches(k) && v.isNotEmpty()) m[k] = v } }
    }

    fun names(sessionId: String): List<String> = vault[sessionId]?.let { synchronized(it) { it.keys.toList() } }.orEmpty()

    fun get(sessionId: String, name: String): String? = vault[sessionId]?.let { synchronized(it) { it[name] } }

    fun has(sessionId: String, name: String) = get(sessionId, name) != null

    fun clear(sessionId: String) { vault.remove(sessionId) }

    /** Marcadores `{{secret:x}}` do texto que ainda NÃO têm valor no cofre desta tarefa. */
    fun missing(sessionId: String, text: String): List<String> =
        REF.findAll(text).map { it.groupValues[1] }.distinct().filter { !has(sessionId, it) }.toList()

    fun references(text: String): List<String> = REF.findAll(text).map { it.groupValues[1] }.distinct().toList()

    /** Returns a vault value only when the entire input is one exact, existing secure reference. */
    fun resolveExact(sessionId: String, text: String): String? {
        val name = EXACT_REF.matchEntire(text)?.groupValues?.get(1) ?: return null
        return get(sessionId, name)
    }

    /** Troca `{{secret:nome}}` pelo valor real (só na execução; nunca devolva o resultado ao modelo sem [redact]). */
    fun resolve(sessionId: String, text: String): String {
        if (!text.contains("secret:")) return text
        return REF.replace(text) { m -> get(sessionId, m.groupValues[1]) ?: m.value }
    }

    /** Esconde qualquer valor do cofre que apareça no texto (cru, URL-encoded ou base64). */
    fun redact(sessionId: String, text: String): String {
        val m = vault[sessionId] ?: return text
        val entries = synchronized(m) { m.entries.map { it.key to it.value } }
        var out = text
        for ((k, v) in entries.sortedByDescending { it.second.length }) {
            if (v.length < 3) continue
            val tag = "‹secret:$k›"
            out = out.replace(v, tag)
            val enc = runCatching { java.net.URLEncoder.encode(v, "UTF-8") }.getOrNull()
            if (enc != null && enc != v) out = out.replace(enc, tag)
            if (v.length >= 6) {
                val b64 = java.util.Base64.getEncoder().encodeToString(v.toByteArray())
                out = out.replace(b64, tag).replace(b64.trimEnd('='), tag)
            }
        }
        return out
    }

    /** Todos os valores desta tarefa (para o Redactor dos conectores). */
    fun values(sessionId: String): List<String> = vault[sessionId]?.let { synchronized(it) { it.values.toList() } }.orEmpty()
}
