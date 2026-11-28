package dev.fluwy.app.workflow

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Dados disponíveis para as expressões de um item ([WfExpr]). */
class WfExprCtx(
    val item: WfItem = WfItem.EMPTY,
    val itemIndex: Int = 0,
    val input: List<WfItem> = listOf(item),
    /** Itens de saída de cada nó já executado, por id. */
    val nodeItems: Map<String, List<WfItem>> = emptyMap(),
    /** id → nome (rótulo) dos nós. */
    val labels: Map<String, String> = emptyMap(),
    val executionId: String = "",
    /** "test" (teste/manual) ou "production". */
    val mode: String = "production",
    val workflowId: String = "",
    val workflowName: String = "",
    val workflowActive: Boolean = false,
    val triggerNodeId: String? = null,
    val prevNodeId: String? = null,
    /** Variáveis antigas: trigger.time, trigger.type, workflow.name. */
    val legacyVars: Map<String, String> = emptyMap(),
    val now: Long = System.currentTimeMillis(),
) {
    /** Saídas em texto (formato antigo), com o atalho `trigger` = saída do gatilho. */
    val legacyOutputs: Map<String, String> by lazy {
        val m = LinkedHashMap<String, String>()
        nodeItems.forEach { (id, items) -> m[id] = WfItems.toText(items) }
        val t = triggerNodeId?.let { m[it] }
        if (t != null && !m.containsKey("trigger") && labels.values.none { it.equals("trigger", true) }) m["trigger"] = t
        m
    }

    fun forItem(item: WfItem, index: Int) = WfExprCtx(item, index, input, nodeItems, labels, executionId, mode, workflowId, workflowName,
        workflowActive, triggerNodeId, prevNodeId, legacyVars, now)
}

class WfExprError(msg: String) : Exception(msg)

/**
 * Avaliador de expressões no estilo n8n, seguro (sem eval): só leitura de dados e um conjunto fechado de métodos.
 * Suporta `{{ $json.campo }}`, `{{ $json["a b"][0] }}`, `{{ $node["Nome"].json.x }}`, `{{ $('Nome').item.json.x }}`,
 * `{{ $input.first().json }}`, `{{ $now.toFormat('yyyy-MM-dd') }}`, `{{ $today }}`, `{{ $execution.id }}`, `{{ $workflow.name }}`,
 * `{{ $itemIndex }}`, literais, `+` (concatena/soma), `||` e `??` (valor padrão). Tokens sem `$` usam o formato antigo
 * ([WfLogic.render]: `{{prev}}`, `{{id.campo}}`, `{{trigger.time}}`, filtros `| default:x`).
 */
object WfExpr {
    private val TOKEN = Regex("""\{\{\s*([^\{\}]+?)\s*\}\}""")

    fun isNew(expr: String): Boolean = expr.trimStart().startsWith("$")

    /** Preenche todos os `{{ … }}` do texto numa só passada (dados nunca são reinterpretados como modelo). */
    fun render(template: String, ctx: WfExprCtx, humanize: ((String) -> String)? = null): String {
        if (!template.contains("{{")) return template
        return TOKEN.replace(template) { m ->
            val v = token(m.groupValues[1], m.value, ctx)
            if (humanize != null && WfFormat.isJson(v)) humanize(v) else v
        }
    }

    private fun token(expr: String, whole: String, ctx: WfExprCtx): String =
        if (isNew(expr)) runCatching { stringify(eval(expr, ctx)) }.getOrDefault("")
        else WfLogic.render(whole, ctx.legacyOutputs, ctx.labels, ctx.item.text(), ctx.legacyVars)

    /** Tokens que ficaram vazios (para barrar instrução vazia à IA/agente/e-mail). Ignora `| default:` e `||`/`??`. */
    fun emptyRefs(template: String, ctx: WfExprCtx): List<String> = TOKEN.findAll(template).mapNotNull { m ->
        val e = m.groupValues[1]
        if (e.split('|').drop(1).any { it.trim().startsWith("default") } || e.contains("||") || e.contains("??")) return@mapNotNull null
        if (token(e, m.value, ctx).isBlank()) m.value else null
    }.distinct().toList()

    /** Valor de um campo: se for exatamente um `{{ $… }}`, devolve o valor (lista, objeto…); senão o texto preenchido. */
    fun value(template: String, ctx: WfExprCtx): JsonElement {
        val m = TOKEN.matchEntire(template.trim())
        if (m != null && isNew(m.groupValues[1])) return toJson(runCatching { eval(m.groupValues[1], ctx) }.getOrNull())
        return JsonPrimitive(render(template, ctx))
    }

    /** Avalia uma expressão (sem as chaves). Lança [WfExprError] se for inválida. */
    fun eval(expr: String, ctx: WfExprCtx): Any? = Parser(Lexer(expr).tokens(), ctx).parseAll()

    // ───────── valores ─────────
    internal class NodeRef(val name: String, val items: List<WfItem>, val pairedIndex: Int)
    internal class ItemsRef(val items: List<WfItem>, val current: WfItem?)
    internal class DateVal(val ms: Long)

    fun stringify(v: Any?): String = when (v) {
        null, JsonNull -> ""
        is JsonPrimitive -> v.content
        is JsonObject -> if (v.keys.singleOrNull() == WfItem.TEXT) (v[WfItem.TEXT] as? JsonPrimitive)?.content ?: v.toString() else v.toString()
        is JsonElement -> v.toString()
        is WfItem -> v.text()
        is NodeRef -> v.items.getOrNull(v.pairedIndex)?.text() ?: v.items.firstOrNull()?.text().orEmpty()
        is ItemsRef -> WfItems.toText(v.items)
        is DateVal -> iso(v.ms)
        is Double -> if (v % 1.0 == 0.0 && kotlin.math.abs(v) < 1e15) v.toLong().toString() else v.toString()
        else -> v.toString()
    }

    internal fun toJson(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is JsonElement -> v
        is WfItem -> v.json
        is NodeRef -> v.items.getOrNull(v.pairedIndex)?.json ?: v.items.firstOrNull()?.json ?: JsonNull
        is ItemsRef -> JsonArray(v.items.map { it.json })
        is DateVal -> JsonPrimitive(iso(v.ms))
        is Number -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        else -> JsonPrimitive(v.toString())
    }

    private fun iso(ms: Long) = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date(ms))

    // ───────── léxico ─────────
    private data class Tok(val kind: Char, val text: String) // i=ident, $=var, s=string, n=number, p=pontuação

    private class Lexer(val src: String) {
        fun tokens(): List<Tok> {
            val out = mutableListOf<Tok>()
            var i = 0
            while (i < src.length) {
                val ch = src[i]
                when {
                    ch.isWhitespace() -> i++
                    ch == '$' || ch.isLetter() || ch == '_' -> {
                        val s = i; i++
                        while (i < src.length && (src[i].isLetterOrDigit() || src[i] == '_')) i++
                        out += Tok(if (ch == '$') '$' else 'i', src.substring(s, i))
                    }
                    ch.isDigit() -> {
                        val s = i
                        while (i < src.length && (src[i].isDigit() || src[i] == '.' && i + 1 < src.length && src[i + 1].isDigit())) i++
                        out += Tok('n', src.substring(s, i))
                    }
                    ch == '"' || ch == '\'' || ch == '`' -> {
                        val sb = StringBuilder(); i++
                        while (i < src.length && src[i] != ch) {
                            if (src[i] == '\\' && i + 1 < src.length) { i++; sb.append(when (src[i]) { 'n' -> '\n'; 't' -> '\t'; else -> src[i] }) } else sb.append(src[i])
                            i++
                        }
                        if (i >= src.length) throw WfExprError("Texto sem aspas de fechamento")
                        i++; out += Tok('s', sb.toString())
                    }
                    src.startsWith("||", i) || src.startsWith("??", i) || src.startsWith("?.", i) -> { out += Tok('p', src.substring(i, i + 2)); i += 2 }
                    ch in ".[](),+-" -> { out += Tok('p', ch.toString()); i++ }
                    else -> throw WfExprError("Caractere não permitido: $ch")
                }
            }
            return out
        }
    }

    // ───────── sintaxe + avaliação ─────────
    private class Parser(val t: List<Tok>, val ctx: WfExprCtx) {
        var p = 0
        fun peek(s: String) = p < t.size && t[p].kind == 'p' && t[p].text == s
        fun eat(s: String): Boolean = peek(s).also { if (it) p++ }
        fun expect(s: String) { if (!eat(s)) throw WfExprError("Esperava \"$s\"") }

        fun parseAll(): Any? { val v = coalesce(); if (p < t.size) throw WfExprError("Sobrou \"${t[p].text}\""); return v }

        fun coalesce(): Any? {
            var v = additive()
            while (peek("||") || peek("??")) {
                val op = t[p++].text
                val r = additive()
                val empty = if (op == "??") v == null || v == JsonNull else truthy(v).not()
                if (empty) v = r
            }
            return v
        }

        fun additive(): Any? {
            var v = postfix()
            while (peek("+") || peek("-")) {
                val op = t[p++].text
                val r = postfix()
                v = if (op == "-") (num(v) ?: 0.0) - (num(r) ?: 0.0)
                else if (isNumeric(v) && isNumeric(r)) num(v)!! + num(r)!!
                else JsonPrimitive(stringify(v) + stringify(r))
            }
            return v
        }

        fun postfix(): Any? {
            var v = primary()
            while (true) {
                if (eat(".") || eat("?.")) {
                    val name = t.getOrNull(p++)?.takeIf { it.kind == 'i' || it.kind == '$' }?.text ?: throw WfExprError("Nome esperado depois de \".\"")
                    v = if (peek("(")) call(v, name, args()) else prop(v, name)
                } else if (eat("[")) {
                    val k = coalesce(); expect("]")
                    v = index(v, k)
                } else return v
            }
        }

        fun args(): List<Any?> {
            expect("(")
            val out = mutableListOf<Any?>()
            if (!eat(")")) { do { out += coalesce() } while (eat(",")); expect(")") }
            return out
        }

        fun primary(): Any? {
            val tk = t.getOrNull(p++) ?: throw WfExprError("Expressão incompleta")
            return when (tk.kind) {
                's' -> JsonPrimitive(tk.text)
                'n' -> JsonPrimitive(tk.text.toDouble().let { if (it % 1.0 == 0.0) it.toLong() as Number else it })
                'i' -> when (tk.text) { "true" -> JsonPrimitive(true); "false" -> JsonPrimitive(false); "null", "undefined" -> null
                    else -> throw WfExprError("Nome desconhecido: ${tk.text} (use \$json, \$node…)") }
                'p' -> if (tk.text == "(") coalesce().also { expect(")") } else if (tk.text == "-") num(postfix())?.let { -it } else throw WfExprError("Inesperado: ${tk.text}")
                else -> variable(tk.text)
            }
        }

        fun variable(name: String): Any? = when (name) {
            "\$json" -> ctx.item.json
            "\$binary" -> JsonObject(emptyMap())
            "\$input" -> ItemsRef(ctx.input, ctx.item)
            "\$item" -> ctx.item
            "\$itemIndex" -> JsonPrimitive(ctx.itemIndex)
            "\$runIndex" -> JsonPrimitive(0)
            "\$now" -> DateVal(ctx.now)
            "\$today" -> DateVal(Calendar.getInstance().apply { timeInMillis = ctx.now; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis)
            "\$execution" -> JsonObject(mapOf("id" to JsonPrimitive(ctx.executionId), "mode" to JsonPrimitive(if (ctx.mode == "test") "manual" else "production")))
            "\$workflow" -> JsonObject(mapOf("id" to JsonPrimitive(ctx.workflowId), "name" to JsonPrimitive(ctx.workflowName), "active" to JsonPrimitive(ctx.workflowActive)))
            "\$prevNode" -> JsonObject(mapOf("name" to JsonPrimitive(ctx.prevNodeId?.let { ctx.labels[it] ?: it }.orEmpty())))
            "\$trigger" -> node(ctx.triggerNodeId ?: "")
            "\$node" -> NodeTable
            "\$" -> if (peek("(")) { val a = args(); node(stringify(a.firstOrNull())) } else throw WfExprError("Use \$('Nome do nó')")
            else -> throw WfExprError("Variável desconhecida: $name")
        }

        fun node(name: String): NodeRef {
            val id = when {
                ctx.nodeItems.containsKey(name) -> name
                else -> ctx.labels.entries.firstOrNull { it.value.equals(name, true) }?.key ?: ctx.labels.keys.firstOrNull { it == name }
            } ?: throw WfExprError("Nó \"$name\" não existe")
            val items = ctx.nodeItems[id] ?: throw WfExprError("O nó \"$name\" ainda não rodou nesta execução")
            return NodeRef(name, items, ctx.itemIndex)
        }

        fun prop(v: Any?, name: String): Any? = when (v) {
            NodeTable -> node(name)
            is NodeRef -> when (name) {
                "json" -> (v.items.getOrNull(v.pairedIndex) ?: v.items.firstOrNull())?.json
                "item" -> v.items.getOrNull(v.pairedIndex) ?: v.items.firstOrNull()
                "isExecuted" -> JsonPrimitive(true)
                "length" -> JsonPrimitive(v.items.size)
                else -> throw WfExprError("Propriedade desconhecida: $name")
            }
            is ItemsRef -> when (name) {
                "item" -> v.current
                "json" -> v.current?.json
                "length" -> JsonPrimitive(v.items.size)
                else -> throw WfExprError("Propriedade desconhecida: $name")
            }
            is WfItem -> if (name == "json") v.json else v.json[name]
            is JsonObject -> v[name]
            is JsonArray -> if (name == "length") JsonPrimitive(v.size) else null
            is JsonPrimitive -> if (name == "length" && v.isString) JsonPrimitive(v.content.length) else null
            is DateVal -> when (name) {
                "year" -> JsonPrimitive(cal(v).get(Calendar.YEAR)); "month" -> JsonPrimitive(cal(v).get(Calendar.MONTH) + 1)
                "day" -> JsonPrimitive(cal(v).get(Calendar.DAY_OF_MONTH)); "hour" -> JsonPrimitive(cal(v).get(Calendar.HOUR_OF_DAY))
                "minute" -> JsonPrimitive(cal(v).get(Calendar.MINUTE)); "weekday" -> JsonPrimitive((cal(v).get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1)
                else -> null
            }
            else -> null
        }

        fun index(v: Any?, k: Any?): Any? {
            if (v == NodeTable) return node(stringify(k))
            val key = stringify(k)
            return when (v) {
                is JsonArray -> key.toDoubleOrNull()?.toInt()?.let { if (it < 0) v.getOrNull(v.size + it) else v.getOrNull(it) }
                is ItemsRef -> key.toIntOrNull()?.let { v.items.getOrNull(it) }
                else -> prop(v, key)
            }
        }

        fun call(v: Any?, name: String, a: List<Any?>): Any? {
            fun s(i: Int) = stringify(a.getOrNull(i))
            return when (v) {
                is NodeRef -> when (name) {
                    "first" -> v.items.firstOrNull(); "last" -> v.items.lastOrNull()
                    "all" -> JsonArray(v.items.map { JsonObject(mapOf("json" to it.json)) })
                    "itemMatching" -> v.items.getOrNull(num(a.firstOrNull())?.toInt() ?: 0)
                    else -> throw WfExprError("Método desconhecido: $name")
                }
                is ItemsRef -> when (name) {
                    "first" -> v.items.firstOrNull(); "last" -> v.items.lastOrNull()
                    "all" -> JsonArray(v.items.map { JsonObject(mapOf("json" to it.json)) })
                    else -> throw WfExprError("Método desconhecido: $name")
                }
                is DateVal -> when (name) {
                    "toFormat", "format" -> JsonPrimitive(runCatching { SimpleDateFormat(s(0).ifBlank { "yyyy-MM-dd HH:mm" }, Locale("pt", "BR")).format(Date(v.ms)) }.getOrElse { throw WfExprError("Formato de data inválido") })
                    "toISO", "toString" -> JsonPrimitive(iso(v.ms))
                    "toMillis", "valueOf" -> JsonPrimitive(v.ms)
                    "toSeconds" -> JsonPrimitive(v.ms / 1000)
                    "plus", "minus" -> {
                        val (n, unit) = if (a.firstOrNull() is JsonObject) (a[0] as JsonObject).entries.first().let { (num(it.value) ?: 0.0) to it.key } else ((num(a.getOrNull(0)) ?: 0.0) to s(1).ifBlank { "days" })
                        val c = cal(v)
                        val field = when (unit.trimEnd('s')) { "minute" -> Calendar.MINUTE; "hour" -> Calendar.HOUR_OF_DAY; "week" -> Calendar.WEEK_OF_YEAR; "month" -> Calendar.MONTH; "year" -> Calendar.YEAR; "second" -> Calendar.SECOND; else -> Calendar.DAY_OF_MONTH }
                        c.add(field, (if (name == "minus") -n else n).toInt()); DateVal(c.timeInMillis)
                    }
                    "startOf" -> { val c = cal(v); c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0); DateVal(c.timeInMillis) }
                    else -> throw WfExprError("Método desconhecido: $name")
                }
                is JsonArray -> when (name) {
                    "join" -> JsonPrimitive(v.joinToString(if (a.isEmpty()) "," else s(0)) { stringify(it) })
                    "first" -> v.firstOrNull(); "last" -> v.lastOrNull()
                    "includes" -> JsonPrimitive(v.any { stringify(it) == s(0) })
                    "slice" -> JsonArray(v.toList().subList(clamp(a.getOrNull(0), v.size, 0), clamp(a.getOrNull(1), v.size, v.size).coerceAtLeast(clamp(a.getOrNull(0), v.size, 0))))
                    "reverse" -> JsonArray(v.reversed())
                    "isEmpty" -> JsonPrimitive(v.isEmpty()); "toJsonString" -> JsonPrimitive(v.toString())
                    "pluck" -> JsonArray(v.map { (it as? JsonObject)?.get(s(0)) ?: JsonNull })
                    else -> throw WfExprError("Método desconhecido: $name")
                }
                is JsonObject -> when (name) {
                    "keys" -> JsonArray(v.keys.map { JsonPrimitive(it) }); "values" -> JsonArray(v.values.toList())
                    "isEmpty" -> JsonPrimitive(v.isEmpty()); "toJsonString", "toString" -> JsonPrimitive(v.toString())
                    "hasField" -> JsonPrimitive(v.containsKey(s(0)))
                    else -> throw WfExprError("Método desconhecido: $name")
                }
                else -> {
                    val str = stringify(v)
                    when (name) {
                        "toUpperCase" -> JsonPrimitive(str.uppercase()); "toLowerCase" -> JsonPrimitive(str.lowercase())
                        "trim" -> JsonPrimitive(str.trim()); "toString" -> JsonPrimitive(str)
                        "includes" -> JsonPrimitive(str.contains(s(0))); "startsWith" -> JsonPrimitive(str.startsWith(s(0))); "endsWith" -> JsonPrimitive(str.endsWith(s(0)))
                        "split" -> JsonArray(str.split(s(0)).map { JsonPrimitive(it) })
                        "replace", "replaceAll" -> JsonPrimitive(str.replace(s(0), s(1)))
                        "slice", "substring" -> JsonPrimitive(str.substring(clamp(a.getOrNull(0), str.length, 0), clamp(a.getOrNull(1), str.length, str.length).coerceAtLeast(clamp(a.getOrNull(0), str.length, 0))))
                        "toNumber" -> num(v)?.let { JsonPrimitive(it) }
                        "isEmpty" -> JsonPrimitive(str.isEmpty())
                        "extractEmail" -> Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}").find(str)?.value?.let { JsonPrimitive(it) }
                        "toFixed" -> num(v)?.let { JsonPrimitive(String.format(Locale.US, "%.${(num(a.getOrNull(0)) ?: 0.0).toInt().coerceIn(0, 10)}f", it)) }
                        "round" -> num(v)?.let { JsonPrimitive(Math.round(it)) }
                        else -> throw WfExprError("Método desconhecido: $name")
                    }
                }
            }
        }

        fun clamp(x: Any?, size: Int, def: Int): Int {
            val n = num(x)?.toInt() ?: return def
            return (if (n < 0) size + n else n).coerceIn(0, size)
        }

        fun cal(v: DateVal): Calendar = Calendar.getInstance().apply { timeInMillis = v.ms }
    }

    private object NodeTable

    internal fun num(v: Any?): Double? = when (v) {
        is JsonPrimitive -> if (v is JsonNull) null else v.content.toDoubleOrNull()
        is Number -> v.toDouble()
        is DateVal -> v.ms.toDouble()
        else -> null
    }

    internal fun isNumeric(v: Any?): Boolean = v is Number || (v is JsonPrimitive && v !is JsonNull && !v.isString && v.content.toDoubleOrNull() != null)

    internal fun truthy(v: Any?): Boolean = when (v) {
        null, JsonNull -> false
        is JsonPrimitive -> !(v.content.isEmpty() || (!v.isString && (v.content == "false" || v.content == "0")))
        is Double -> v != 0.0
        else -> true
    }
}
