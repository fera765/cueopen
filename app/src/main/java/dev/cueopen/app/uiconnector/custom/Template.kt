package dev.cueopen.app.uiconnector.custom

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Modelos de texto `{{expressão | filtro:arg | filtro}}` (puro Kotlin).
 * Expressão: nome de variável (`email`, `s.maxValue`) ou caminho `$.a.b[0]` na resposta JSON atual.
 * Filtros: url, json, num[:N], int, upper, lower, trim, default:X, date, pct:N, bool, raw.
 * Não há código executável: só substituição de valores.
 */
object Template {
    class Ctx(val vars: Map<String, String>, val json: JsonElement? = null)
    data class Ref(val expr: String, val filters: List<Pair<String, String?>>)

    private val RE = Regex("\\{\\{(.*?)\\}\\}", RegexOption.DOT_MATCHES_ALL)
    private val VAR = Regex("^[A-Za-z][A-Za-z0-9_.]*$")
    private val FILTERS = setOf("url", "json", "num", "int", "upper", "lower", "trim", "default", "date", "pct", "bool", "raw", "strip", "trunc", "base64")
    private val PARSER = Json { isLenient = true }

    fun parseRef(inner: String): Ref? {
        val parts = inner.split('|').map { it.trim() }
        val expr = parts.firstOrNull().orEmpty()
        if (expr.isEmpty()) return null
        val filters = parts.drop(1).map { f ->
            val i = f.indexOf(':')
            if (i < 0) f to null else f.substring(0, i).trim() to f.substring(i + 1)
        }
        return Ref(expr, filters)
    }

    fun refs(t: String): List<Ref?> = RE.findAll(t).map { parseRef(it.groupValues[1]) }.toList()

    fun hasRefs(t: String): Boolean = RE.containsMatchIn(t)

    /** Problemas estáticos de um modelo: expressão inválida, variável desconhecida, filtro desconhecido. */
    fun problems(t: String, known: Set<String>, allowPath: Boolean): List<String> {
        val out = ArrayList<String>()
        if (t.count { it == '{' } != t.count { it == '}' } && t.contains("{{")) out.add("chaves {{ }} desbalanceadas em \"${t.take(60)}\"")
        for (r in refs(t)) {
            if (r == null) { out.add("expressão vazia em {{ }}"); continue }
            if (r.expr.startsWith("$")) {
                if (!allowPath) out.add("'${r.expr}' (caminho na resposta) não vale aqui")
                else if (!JsonPath.isValid(r.expr)) out.add("caminho inválido '${r.expr}'")
            } else if (!VAR.matches(r.expr)) {
                out.add("expressão inválida '${r.expr}'")
            } else if (r.expr !in known) {
                out.add("variável desconhecida '${r.expr}' (disponíveis aqui: ${known.sorted().joinToString().ifEmpty { "nenhuma" }})")
            }
            for ((f, arg) in r.filters) {
                if (f !in FILTERS) { out.add("filtro desconhecido '$f'"); continue }
                if ((f == "num" || f == "pct") && arg != null && (arg.trim().toIntOrNull() ?: -1) !in 0..8) out.add("filtro $f: use um número de 0 a 8")
                if (f == "trunc" && (arg?.trim()?.toIntOrNull() ?: -1) !in 1..5000) out.add("filtro trunc: use trunc:N (1 a 5000)")
                if (f == "default" && arg == null) out.add("filtro default precisa de valor (default:X)")
            }
        }
        return out
    }

    fun render(t: String, ctx: Ctx): String = RE.replace(t) { m -> evalRef(m.groupValues[1], ctx) }

    private fun fmtNum(v: String, arg: String?): String {
        val d = v.trim().replace(',', '.').toDoubleOrNull() ?: return "-"
        if (d.isNaN() || d.isInfinite()) return "-"
        val n = arg?.trim()?.toIntOrNull()
        return if (n != null) String.format(Locale.US, "%.${n}f", d)
        else BigDecimal(d.toString()).stripTrailingZeros().toPlainString()
    }

    private fun evalRef(inner: String, ctx: Ctx): String {
        val ref = parseRef(inner) ?: return ""
        var v: String = if (ref.expr.startsWith("$")) {
            val j = ctx.json
            if (j == null) "" else JsonPath.text(JsonPath.first(j, ref.expr))
        } else ctx.vars[ref.expr].orEmpty()
        for ((f, arg) in ref.filters) {
            v = when (f) {
                "default" -> if (v.isEmpty()) arg.orEmpty().trim() else v
                "url" -> URLEncoder.encode(v, "UTF-8").replace("+", "%20")
                "json" -> JsonPrimitive(v).toString()
                "num" -> fmtNum(v, arg)
                "pct" -> fmtNum(v, arg).let { if (it == "-") it else "$it%" }
                "int" -> v.trim().toDoubleOrNull()?.toLong()?.toString() ?: v
                "upper" -> v.uppercase()
                "lower" -> v.lowercase()
                "trim" -> v.trim()
                "strip" -> stripHtml(v)
                "trunc" -> { val n = arg?.trim()?.toIntOrNull() ?: 200; if (v.length > n) v.take(n).trimEnd() + "…" else v }
                "base64" -> java.util.Base64.getEncoder().encodeToString(v.toByteArray(Charsets.UTF_8))
                "bool" -> if (v.trim().equals("true", true) || v.trim() == "1") "true" else "false"
                "date" -> v.trim().toDoubleOrNull()?.let { d ->
                    val ms = if (d > 1e11) d.toLong() else (d * 1000).toLong()
                    SimpleDateFormat("dd/MM HH:mm", Locale.US).format(Date(ms))
                } ?: v
                else -> v
            }
        }
        return v
    }

    private val TAG = Regex("<[^>]*>")
    private val WS = Regex("\\s+")

    /** Remove tags HTML e decodifica as entidades mais comuns (para descrições de RSS/HTML). */
    fun stripHtml(v: String): String = TAG.replace(v.replace("<![CDATA[", "").replace("]]>", ""), " ")
        .replace("&nbsp;", " ").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
        .let { WS.replace(it, " ") }.trim()

    /** Percorre um JSON (corpo de requisição) renderizando as strings; tipa o valor se o modelo for um único {{x|num}}. */
    fun renderJson(e: JsonElement, ctx: Ctx): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.mapValues { renderJson(it.value, ctx) })
        is JsonArray -> JsonArray(e.map { renderJson(it, ctx) })
        is JsonPrimitive -> if (e.isString) renderString(e.content, ctx) else e
    }

    private fun renderString(s: String, ctx: Ctx): JsonElement {
        val m = RE.matchEntire(s.trim())
        if (m != null) {
            val ref = parseRef(m.groupValues[1])
            val last = ref?.filters?.lastOrNull()?.first
            if (last == "num" || last == "int" || last == "bool" || last == "raw") {
                val value = evalRef(m.groupValues[1], ctx)
                when (last) {
                    "bool" -> return JsonPrimitive(value == "true")
                    "int", "num" -> {
                        val l = value.toLongOrNull()
                        if (l != null) return JsonPrimitive(l)
                        val d = value.toDoubleOrNull()
                        if (d != null) return JsonPrimitive(d)
                        return JsonPrimitive(value)
                    }
                    else -> return runCatching { PARSER.parseToJsonElement(value) }.getOrElse { JsonPrimitive(value) }
                }
            }
        }
        return JsonPrimitive(render(s, ctx))
    }

    /** Todas as strings de um JSON (para validação estática de modelos no corpo). */
    fun strings(e: JsonElement?): List<String> = when (e) {
        null -> emptyList()
        is JsonObject -> e.values.flatMap { strings(it) }
        is JsonArray -> e.flatMap { strings(it) }
        is JsonPrimitive -> if (e.isString) listOf(e.content) else emptyList()
    }
}
