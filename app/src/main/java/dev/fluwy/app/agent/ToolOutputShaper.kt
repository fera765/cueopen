package dev.fluwy.app.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * Formata saídas de conectores (MCP/UI) antes de irem para o modelo — "alto sinal, poucos tokens".
 *
 * Mesmo padrão do OpenBot (compact(): tira vazios; textos longos "… (cut)"; listas com limite padrão) e da
 * orientação da Anthropic para ferramentas (devolver só o que importa, truncar com instrução útil, paginar):
 *  - remove campos ruidosos (cabeçalhos SMTP, HTML, payload bruto, base64, assinaturas DKIM/ARC…);
 *  - remove valores vazios; corta textos longos; limita listas; limita a profundidade;
 *  - o JSON original completo é arquivado pelo chamador e só um ponteiro vai junto.
 * Saídas que não são JSON passam intactas (o truncamento comum do ContextManager continua valendo).
 */
object ToolOutputShaper {
    const val STRING_MAX = 500
    const val ARRAY_MAX = 20
    const val DEPTH_MAX = 8

    /** Chaves que quase nunca ajudam o agente e explodem o tamanho (e-mail, HTTP, mídia). Comparação sem caixa. */
    private val NOISY_KEYS = setOf(
        "headers", "rawheaders", "raw", "rawbody", "html", "texthtml", "textashtml", "bodyhtml", "htmlbody", "html_body",
        "payload", "parts", "inlineimages", "attachmentsdata", "dkim-signature", "received", "arc-seal", "arc-message-signature",
        "arc-authentication-results", "authentication-results", "x-received", "x-google-smtp-source", "x-gm-message-state",
        "list-unsubscribe", "list-unsubscribe-post", "headerlines", "mime", "eml", "source",
    )

    data class Shaped(val text: String, val omittedKeys: Set<String>, val changed: Boolean)

    private val json = Json { prettyPrint = false }

    fun shape(output: String): Shaped {
        val start = output.indexOfFirst { it == '{' || it == '[' }
        if (start < 0) return Shaped(output, emptySet(), false)
        val end = output.lastIndexOfAny(charArrayOf('}', ']'))
        if (end <= start) return Shaped(output, emptySet(), false)
        val el = runCatching { Json.parseToJsonElement(output.substring(start, end + 1)) }.getOrNull() ?: return Shaped(output, emptySet(), false)
        val omitted = LinkedHashSet<String>()
        val compact = compact(el, 0, omitted) ?: JsonNull
        val body = json.encodeToString(JsonElement.serializer(), compact)
        val prefix = output.substring(0, start).trim()
        val suffix = output.substring(end + 1).trim()
        val text = listOf(prefix, body, suffix).filter { it.isNotEmpty() }.joinToString("\n")
        val changed = omitted.isNotEmpty() || text.length < output.length * 0.9
        return if (changed) Shaped(text, omitted, true) else Shaped(output, emptySet(), false)
    }

    private fun compact(el: JsonElement, depth: Int, omitted: MutableSet<String>): JsonElement? = when (el) {
        is JsonNull -> null
        is JsonPrimitive -> if (el.isString) compactString(el.content)?.let { JsonPrimitive(it) } else el
        is JsonArray -> {
            if (depth >= DEPTH_MAX) JsonPrimitive("[lista com ${el.size} itens omitida: muito aninhada]")
            else {
                val items = el.mapNotNull { compact(it, depth + 1, omitted) }
                if (items.isEmpty()) null else buildJsonArray {
                    items.take(ARRAY_MAX).forEach { add(it) }
                    if (items.size > ARRAY_MAX) add(JsonPrimitive("… +${items.size - ARRAY_MAX} itens (peça menos resultados ou filtre)"))
                }
            }
        }
        is JsonObject -> {
            if (depth >= DEPTH_MAX) JsonPrimitive("[objeto omitido: muito aninhado]")
            else {
                val out = buildJsonObject {
                    for ((k, v) in el) {
                        if (k.lowercase() in NOISY_KEYS || k.lowercase().startsWith("x-")) { omitted += k; continue }
                        compact(v, depth + 1, omitted)?.let { put(k, it) }
                    }
                }
                if (out.isEmpty()) null else out
            }
        }
    }

    private val BASE64 = Regex("^[A-Za-z0-9+/=_-]{200,}$")
    private val TAGS = Regex("<[^>]{1,400}>")

    private fun compactString(s: String): String? {
        if (s.isEmpty()) return null
        if (s.length >= 200 && BASE64.matches(s)) return "[binário/base64 ${s.length} chars omitido]"
        var t = s
        if (t.contains("</") && TAGS.findAll(t).take(5).count() >= 3) t = TAGS.replace(t, " ").replace(Regex("\\s+"), " ").trim()
        return if (t.length > STRING_MAX) t.take(STRING_MAX) + "… (+${t.length - STRING_MAX} chars)" else t
    }
}
