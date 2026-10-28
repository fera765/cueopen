package dev.cueopen.app.uiconnector.custom

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/*
 * MOTOR DE COMPUTAÇÃO dos UI Connectors (bloco "compute").
 *
 * Linguagem de expressões pura e isolada (sem I/O, sem reflexão, sem acesso a credenciais): aritmética,
 * lógica, listas, objetos, lambdas (map/filter/reduce/sort_by…) e uma biblioteca de análise técnica no
 * padrão TradingView (SMA, EMA, RSI, MACD, Bollinger, Estocástico, ATR, ADX, Ichimoku, Supertrend, PSAR…).
 * Cada avaliação tem orçamento de operações e de memória: uma definição maliciosa não trava o app.
 *
 * Valores: Double, String, Boolean, null, List<Any?>, Map<String, Any?>, Lambda.
 */
object Compute {
    const val MAX_STEPS = 3_000_000L
    const val MAX_LIST = 200_000
    private const val MAX_DEPTH = 80

    class ComputeError(msg: String) : RuntimeException(msg)

    // ------------------------------------------------------------------ AST
    sealed class Node {
        data class Num(val v: Double) : Node()
        data class Str(val v: String) : Node()
        data class Bool(val v: Boolean) : Node()
        object Null : Node()
        data class Ident(val name: String) : Node()
        data class Member(val obj: Node, val key: String) : Node()
        data class Index(val obj: Node, val idx: Node) : Node()
        data class Call(val fn: String, val args: List<Node>) : Node()
        data class Unary(val op: String, val e: Node) : Node()
        data class Binary(val op: String, val a: Node, val b: Node) : Node()
        data class Ternary(val c: Node, val a: Node, val b: Node) : Node()
        data class ListLit(val items: List<Node>) : Node()
        data class ObjLit(val entries: List<Pair<String, Node>>) : Node()
        data class Lambda(val params: List<String>, val body: Node) : Node()
    }

    class Fn(val params: List<String>, val body: Node, val env: Map<String, Any?>)

    // ------------------------------------------------------------------ léxico
    private data class Tok(val k: Char, val s: String, val pos: Int) // k: n=num, s=str, i=ident, o=op, e=end

    private fun lex(src: String): List<Tok> {
        val out = ArrayList<Tok>()
        var i = 0
        val ops3 = listOf("&&", "||", "==", "!=", "<=", ">=", "->")
        while (i < src.length) {
            val ch = src[i]
            when {
                ch.isWhitespace() -> i++
                ch.isDigit() || (ch == '.' && i + 1 < src.length && src[i + 1].isDigit()) -> {
                    val st = i
                    while (i < src.length && (src[i].isDigit() || src[i] == '.')) i++
                    if (i < src.length && (src[i] == 'e' || src[i] == 'E')) {
                        i++; if (i < src.length && (src[i] == '+' || src[i] == '-')) i++
                        while (i < src.length && src[i].isDigit()) i++
                    }
                    out.add(Tok('n', src.substring(st, i), st))
                }
                ch == '"' || ch == '\'' -> {
                    val q = ch; val st = i; i++
                    val sb = StringBuilder()
                    while (i < src.length && src[i] != q) {
                        if (src[i] == '\\' && i + 1 < src.length) {
                            i++
                            sb.append(when (src[i]) { 'n' -> '\n'; 't' -> '\t'; else -> src[i] })
                        } else sb.append(src[i])
                        i++
                    }
                    if (i >= src.length) throw ComputeError("texto sem aspas de fechamento (posição $st)")
                    i++
                    out.add(Tok('s', sb.toString(), st))
                }
                ch.isLetter() || ch == '_' || ch == '$' -> {
                    val st = i
                    if (ch == '$') { i++ } else { while (i < src.length && (src[i].isLetterOrDigit() || src[i] == '_')) i++ }
                    out.add(Tok('i', src.substring(st, i), st))
                }
                else -> {
                    val two = if (i + 1 < src.length) src.substring(i, i + 2) else ""
                    if (two in ops3) { out.add(Tok('o', two, i)); i += 2 }
                    else if (ch in "+-*/%^<>!?:,()[]{}.") { out.add(Tok('o', ch.toString(), i)); i++ }
                    else throw ComputeError("caractere inesperado '$ch' (posição $i)")
                }
            }
        }
        out.add(Tok('e', "", src.length))
        return out
    }

    // ------------------------------------------------------------------ sintaxe
    private class Parser(val t: List<Tok>) {
        var p = 0
        var depth = 0
        fun peek(o: Int = 0) = t[min(p + o, t.size - 1)]
        fun isOp(s: String, o: Int = 0) = peek(o).k == 'o' && peek(o).s == s
        fun expect(s: String) { if (!isOp(s)) throw ComputeError("esperado '$s' na posição ${peek().pos}, encontrado '${peek().s.ifEmpty { "fim" }}'"); p++ }

        fun parseAll(): Node { val n = expr(); if (peek().k != 'e') throw ComputeError("sobrou '${peek().s}' na posição ${peek().pos}"); return n }

        fun expr(): Node {
            if (++depth > MAX_DEPTH) throw ComputeError("expressão aninhada demais")
            try {
                // lambda: x -> …  |  (a, b) -> …
                if (peek().k == 'i' && isOp("->", 1)) { val n = peek().s; p += 2; return Node.Lambda(listOf(n), expr()) }
                if (isOp("(")) {
                    var j = 1; val names = ArrayList<String>(); var ok = true
                    if (isOp(")", j)) { j++ } else {
                        while (true) {
                            if (peek(j).k != 'i') { ok = false; break }
                            names.add(peek(j).s); j++
                            if (isOp(",", j)) { j++; continue }
                            if (isOp(")", j)) { j++; break }
                            ok = false; break
                        }
                    }
                    if (ok && isOp("->", j)) { p += j + 1; return Node.Lambda(names, expr()) }
                }
                val c = or()
                if (isOp("?")) { p++; val a = expr(); expect(":"); val b = expr(); return Node.Ternary(c, a, b) }
                return c
            } finally { depth-- }
        }
        fun or(): Node { var a = and(); while (isOp("||")) { p++; a = Node.Binary("||", a, and()) }; return a }
        fun and(): Node { var a = eq(); while (isOp("&&")) { p++; a = Node.Binary("&&", a, eq()) }; return a }
        fun eq(): Node { var a = cmp(); while (isOp("==") || isOp("!=")) { val o = peek().s; p++; a = Node.Binary(o, a, cmp()) }; return a }
        fun cmp(): Node { var a = add(); while (isOp("<") || isOp("<=") || isOp(">") || isOp(">=")) { val o = peek().s; p++; a = Node.Binary(o, a, add()) }; return a }
        fun add(): Node { var a = mul(); while (isOp("+") || isOp("-")) { val o = peek().s; p++; a = Node.Binary(o, a, mul()) }; return a }
        fun mul(): Node { var a = unary(); while (isOp("*") || isOp("/") || isOp("%")) { val o = peek().s; p++; a = Node.Binary(o, a, unary()) }; return a }
        fun unary(): Node {
            if (isOp("-") || isOp("!")) { val o = peek().s; p++; return Node.Unary(o, unary()) }
            val b = postfix()
            if (isOp("^")) { p++; return Node.Binary("^", b, unary()) }
            return b
        }
        fun postfix(): Node {
            var n = primary()
            while (true) {
                n = when {
                    isOp(".") -> { p++; val k = peek(); if (k.k != 'i') throw ComputeError("esperado nome após '.' (posição ${k.pos})"); p++; Node.Member(n, k.s) }
                    isOp("[") -> { p++; val i = expr(); expect("]"); Node.Index(n, i) }
                    else -> return n
                }
            }
        }
        fun args(): List<Node> {
            expect("(")
            val a = ArrayList<Node>()
            if (!isOp(")")) { while (true) { a.add(expr()); if (isOp(",")) { p++; continue }; break } }
            expect(")")
            return a
        }
        fun primary(): Node {
            val k = peek()
            return when {
                k.k == 'n' -> { p++; Node.Num(k.s.toDoubleOrNull() ?: throw ComputeError("número inválido '${k.s}'")) }
                k.k == 's' -> { p++; Node.Str(k.s) }
                k.k == 'i' -> {
                    p++
                    when {
                        k.s == "true" -> Node.Bool(true)
                        k.s == "false" -> Node.Bool(false)
                        k.s == "null" -> Node.Null
                        isOp("(") -> Node.Call(k.s, args())
                        else -> Node.Ident(k.s)
                    }
                }
                isOp("(") -> { p++; val e = expr(); expect(")"); e }
                isOp("[") -> {
                    p++
                    val items = ArrayList<Node>()
                    if (!isOp("]")) { while (true) { items.add(expr()); if (isOp(",")) { p++; continue }; break } }
                    expect("]"); Node.ListLit(items)
                }
                isOp("{") -> {
                    p++
                    val es = ArrayList<Pair<String, Node>>()
                    if (!isOp("}")) {
                        while (true) {
                            val key = peek(); if (key.k != 'i' && key.k != 's') throw ComputeError("chave inválida no objeto (posição ${key.pos})")
                            p++; expect(":"); es.add(key.s to expr())
                            if (isOp(",")) { p++; continue }; break
                        }
                    }
                    expect("}"); Node.ObjLit(es)
                }
                else -> throw ComputeError("expressão inesperada '${k.s.ifEmpty { "fim" }}' (posição ${k.pos})")
            }
        }
    }

    fun parse(src: String): Node {
        if (src.length > 20_000) throw ComputeError("expressão longa demais (máx. 20000 caracteres)")
        return Parser(lex(src)).parseAll()
    }

    /** Problemas estáticos: sintaxe, função desconhecida/aridade, identificador desconhecido. */
    fun problems(src: String, known: Set<String>): List<String> {
        val n = try { parse(src) } catch (e: ComputeError) { return listOf(e.message ?: "sintaxe inválida") }
        val out = ArrayList<String>()
        fun walk(x: Node, scope: Set<String>) {
            when (x) {
                is Node.Ident -> if (x.name !in scope && x.name !in known) out.add("identificador desconhecido '${x.name}' (disponíveis: ${known.sorted().joinToString().ifEmpty { "nenhum" }})")
                is Node.Call -> {
                    val spec = Lib.ARITY[x.fn]
                    if (spec == null) out.add("função desconhecida '${x.fn}'")
                    else if (x.args.size < spec.first || x.args.size > spec.second) out.add("${x.fn}: espera ${if (spec.first == spec.second) "${spec.first}" else "${spec.first}–${if (spec.second > 50) "n" else spec.second.toString()}"} argumento(s), recebeu ${x.args.size}")
                    x.args.forEach { walk(it, scope) }
                }
                is Node.Member -> walk(x.obj, scope)
                is Node.Index -> { walk(x.obj, scope); walk(x.idx, scope) }
                is Node.Unary -> walk(x.e, scope)
                is Node.Binary -> { walk(x.a, scope); walk(x.b, scope) }
                is Node.Ternary -> { walk(x.c, scope); walk(x.a, scope); walk(x.b, scope) }
                is Node.ListLit -> x.items.forEach { walk(it, scope) }
                is Node.ObjLit -> x.entries.forEach { walk(it.second, scope) }
                is Node.Lambda -> walk(x.body, scope + x.params)
                else -> {}
            }
        }
        walk(n, emptySet())
        return out.distinct()
    }

    // ------------------------------------------------------------------ avaliação
    class Ctx(val env: Map<String, Any?>, val root: JsonElement?) {
        var steps = 0L
        fun charge(n: Long = 1) { steps += n; if (steps > MAX_STEPS) throw ComputeError("cálculo excedeu o limite de operações") }
    }

    fun eval(src: String, env: Map<String, Any?>, root: JsonElement?): Any? = eval(parse(src), Ctx(env, root))

    fun eval(n: Node, ctx: Ctx, local: Map<String, Any?> = emptyMap()): Any? {
        ctx.charge()
        return when (n) {
            is Node.Num -> n.v
            is Node.Str -> n.v
            is Node.Bool -> n.v
            Node.Null -> null
            is Node.Ident -> when {
                local.containsKey(n.name) -> local[n.name]
                ctx.env.containsKey(n.name) -> ctx.env[n.name]
                else -> throw ComputeError("identificador desconhecido '${n.name}'")
            }
            is Node.Member -> member(eval(n.obj, ctx, local), n.key)
            is Node.Index -> index(eval(n.obj, ctx, local), eval(n.idx, ctx, local))
            is Node.Unary -> { val v = eval(n.e, ctx, local); if (n.op == "!") !truthy(v) else numOrNull(v)?.let { -it } }
            is Node.Binary -> binary(n, ctx, local)
            is Node.Ternary -> if (truthy(eval(n.c, ctx, local))) eval(n.a, ctx, local) else eval(n.b, ctx, local)
            is Node.ListLit -> n.items.map { eval(it, ctx, local) }
            is Node.ObjLit -> LinkedHashMap<String, Any?>().apply { n.entries.forEach { (k, v) -> put(k, eval(v, ctx, local)) } }
            is Node.Lambda -> Fn(n.params, n.body, local)
            is Node.Call -> Lib.call(n.fn, n.args.map { eval(it, ctx, local) }, ctx)
        }
    }

    fun callFn(f: Any?, args: List<Any?>, ctx: Ctx): Any? {
        val fn = f as? Fn ?: throw ComputeError("esperava uma função (ex.: x -> x * 2)")
        val loc = HashMap(fn.env)
        fn.params.forEachIndexed { i, p -> loc[p] = args.getOrNull(i) }
        return eval(fn.body, ctx, loc)
    }

    private fun binary(n: Node.Binary, ctx: Ctx, local: Map<String, Any?>): Any? {
        if (n.op == "&&") return truthy(eval(n.a, ctx, local)) && truthy(eval(n.b, ctx, local))
        if (n.op == "||") return truthy(eval(n.a, ctx, local)) || truthy(eval(n.b, ctx, local))
        val a = eval(n.a, ctx, local); val b = eval(n.b, ctx, local)
        when (n.op) {
            "==" -> return eq(a, b)
            "!=" -> return !eq(a, b)
            "+" -> if (a is String || b is String) return str(a) + str(b) // listas: soma elemento a elemento (junte com concat)
        }
        // operações elemento a elemento entre séries (lista × lista ou lista × número)
        if (a is List<*> || b is List<*>) {
            val la = a as? List<*>; val lb = b as? List<*>
            val size = max(la?.size ?: 0, lb?.size ?: 0)
            ctx.charge(size.toLong())
            return (0 until size).map { i -> arith(n.op, if (la != null) la.getOrNull(i) else a, if (lb != null) lb.getOrNull(i) else b) }
        }
        return arith(n.op, a, b)
    }

    private fun arith(op: String, a: Any?, b: Any?): Any? {
        val x = numOrNull(a); val y = numOrNull(b)
        if (op in setOf("<", "<=", ">", ">=")) {
            if (x == null || y == null) {
                if (a is String && b is String) { val c = a.compareTo(b); return when (op) { "<" -> c < 0; "<=" -> c <= 0; ">" -> c > 0; else -> c >= 0 } }
                return false
            }
            return when (op) { "<" -> x < y; "<=" -> x <= y; ">" -> x > y; else -> x >= y }
        }
        if (op == "==") return eq(a, b)
        if (op == "!=") return !eq(a, b)
        if (x == null || y == null) return null
        val r = when (op) {
            "+" -> x + y; "-" -> x - y; "*" -> x * y
            "/" -> if (y == 0.0) Double.NaN else x / y
            "%" -> if (y == 0.0) Double.NaN else x % y
            "^" -> Math.pow(x, y)
            else -> throw ComputeError("operador '$op'")
        }
        return if (r.isNaN() || r.isInfinite()) null else r
    }

    // ------------------------------------------------------------------ conversões
    fun truthy(v: Any?): Boolean = when (v) {
        null -> false; is Boolean -> v; is Double -> v != 0.0 && !v.isNaN(); is String -> v.isNotEmpty() && v != "false" && v != "0"
        is List<*> -> v.isNotEmpty(); is Map<*, *> -> v.isNotEmpty(); else -> true
    }

    fun numOrNull(v: Any?): Double? = when (v) {
        is Double -> if (v.isNaN()) null else v
        is Number -> v.toDouble()
        is Boolean -> if (v) 1.0 else 0.0
        is String -> v.trim().replace(',', '.').toDoubleOrNull()
        else -> null
    }

    fun eq(a: Any?, b: Any?): Boolean {
        val x = numOrNull(a); val y = numOrNull(b)
        if (a !is String && b !is String && x != null && y != null) return x == y
        if (a is String && b is String) return a == b
        if ((a is String) != (b is String) && x != null && y != null) return x == y
        return a == b
    }

    fun member(o: Any?, key: String): Any? = when (o) {
        is Map<*, *> -> o[key]
        is List<*> -> when (key) { "length", "size" -> o.size.toDouble(); else -> o.map { member(it, key) } }
        is String -> if (key == "length") o.length.toDouble() else null
        else -> null
    }

    fun index(o: Any?, i: Any?): Any? = when (o) {
        is List<*> -> numOrNull(i)?.toInt()?.let { ix -> val j = if (ix < 0) o.size + ix else ix; o.getOrNull(j) }
        is Map<*, *> -> o[str(i)]
        is String -> numOrNull(i)?.toInt()?.let { ix -> val j = if (ix < 0) o.length + ix else ix; o.getOrNull(j)?.toString() }
        else -> null
    }

    fun fromJson(e: JsonElement?): Any? = when (e) {
        null, JsonNull -> null
        is JsonPrimitive -> if (e.isString) e.content else e.booleanOrNull ?: e.doubleOrNull ?: e.content
        is JsonArray -> e.map { fromJson(it) }
        is JsonObject -> LinkedHashMap<String, Any?>().apply { e.forEach { (k, v) -> put(k, fromJson(v)) } }
    }

    fun toJson(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is Double -> if (v.isNaN() || v.isInfinite()) JsonNull else if (v == Math.rint(v) && abs(v) < 1e15) JsonPrimitive(v.toLong()) else JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is String -> JsonPrimitive(v)
        is List<*> -> JsonArray(v.map { toJson(it) })
        is Map<*, *> -> JsonObject(v.entries.associate { it.key.toString() to toJson(it.value) })
        else -> JsonPrimitive(v.toString())
    }

    /** Texto para modelos {{c.nome}}: número sem zeros sobrando (até 8 casas), listas/objetos como JSON. */
    fun str(v: Any?): String = when (v) {
        null -> ""
        is Double -> if (v.isNaN() || v.isInfinite()) "" else BigDecimal(v).setScale(8, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        is Boolean -> v.toString()
        is String -> v
        is Fn -> "<função>"
        else -> toJson(v).toString()
    }

    // ------------------------------------------------------------------ biblioteca
    object Lib {
        private const val N = 99
        /** nome → (mín, máx) de argumentos. */
        val ARITY: Map<String, Pair<Int, Int>> = mapOf(
            // matemática
            "abs" to (1 to 1), "round" to (1 to 2), "floor" to (1 to 1), "ceil" to (1 to 1), "sqrt" to (1 to 1),
            "ln" to (1 to 1), "log" to (1 to 1), "log10" to (1 to 1), "exp" to (1 to 1), "pow" to (2 to 2),
            "min" to (1 to N), "max" to (1 to N), "clamp" to (3 to 3), "sign" to (1 to 1),
            // conversão / lógica
            "num" to (1 to 1), "int" to (1 to 1), "str" to (1 to 1), "bool" to (1 to 1), "isnull" to (1 to 1),
            "coalesce" to (1 to N), "if" to (3 to 3), "fixed" to (2 to 2), "type" to (1 to 1),
            // texto
            "upper" to (1 to 1), "lower" to (1 to 1), "trim" to (1 to 1), "contains" to (2 to 2), "startswith" to (2 to 2),
            "endswith" to (2 to 2), "replace" to (3 to 3), "split" to (2 to 2), "join" to (2 to 2), "substr" to (2 to 3),
            "len" to (1 to 1), "date" to (1 to 2), "now" to (0 to 0), "parse_num" to (1 to 1), "regex" to (2 to 2),
            // listas
            "range" to (1 to 3), "sum" to (1 to 1), "avg" to (1 to 1), "mean" to (1 to 1), "median" to (1 to 1),
            "stdev" to (1 to 1), "variance" to (1 to 1), "first" to (1 to 1), "last" to (1 to 1), "at" to (2 to 2),
            "slice" to (2 to 3), "take" to (2 to 2), "tail" to (2 to 2), "reverse" to (1 to 1), "sort" to (1 to 1),
            "sort_by" to (2 to 2), "unique" to (1 to 1), "map" to (2 to 2), "filter" to (2 to 2), "reduce" to (3 to 3),
            "find" to (2 to 2), "count" to (1 to 2), "any" to (2 to 2), "all" to (2 to 2), "flatten" to (1 to 1),
            "concat" to (2 to N), "zip" to (2 to N), "zip_rows" to (1 to 1), "pluck" to (2 to 2), "keys" to (1 to 1),
            "values" to (1 to 1), "get" to (2 to 3), "index_of" to (2 to 2), "group_by" to (2 to 2), "compact" to (1 to 1),
            "diff" to (1 to 1), "pct_change" to (1 to 1), "cumsum" to (1 to 1), "shift" to (2 to 2), "fill" to (1 to 1),
            "path" to (1 to 2), "correl" to (2 to 2), "argmax" to (1 to 1), "argmin" to (1 to 1), "repeat" to (2 to 2),
            // séries / janelas
            "highest" to (2 to 2), "lowest" to (2 to 2), "rsum" to (2 to 2), "rstdev" to (2 to 2), "linreg" to (2 to 2),
            "slope" to (2 to 2), "rolling" to (3 to 3), "crossover" to (2 to 2), "crossunder" to (2 to 2),
            "cross_series" to (2 to 2), "change" to (1 to 2), "barssince" to (1 to 1),
            // indicadores (padrão TradingView)
            "sma" to (2 to 2), "ema" to (2 to 2), "rma" to (2 to 2), "wma" to (2 to 2), "dema" to (2 to 2), "tema" to (2 to 2),
            "hma" to (2 to 2), "vwma" to (3 to 3), "alma" to (2 to 4), "rsi" to (2 to 2), "stoch" to (3 to 6),
            "stochrsi" to (1 to 5), "macd" to (1 to 4), "bollinger" to (1 to 3), "keltner" to (3 to 5), "donchian" to (2 to 3),
            "tr" to (3 to 3), "atr" to (3 to 4), "adx" to (3 to 4), "cci" to (3 to 4), "willr" to (3 to 4),
            "mfi" to (4 to 5), "obv" to (2 to 2), "vwap" to (4 to 4), "adl" to (4 to 4), "cmf" to (4 to 5),
            "roc" to (1 to 2), "mom" to (1 to 2), "trix" to (1 to 2), "aroon" to (2 to 3), "psar" to (2 to 4),
            "supertrend" to (3 to 5), "ichimoku" to (2 to 5), "pivots" to (3 to 3), "ao" to (2 to 2), "uo" to (3 to 6),
            "cmo" to (1 to 2), "dpo" to (1 to 2), "kama" to (1 to 4), "chop" to (3 to 4), "bbw" to (1 to 3),
            "ppo" to (1 to 4), "tsi" to (1 to 3), "fisher" to (2 to 3), "elder" to (3 to 4), "zscore" to (2 to 2),
            "heikin" to (4 to 4), "call" to (1 to N), "hl2" to (2 to 2), "hlc3" to (3 to 3), "ohlc4" to (4 to 4), "signal" to (1 to 1),
        )

        private fun dbl(v: Any?, name: String): Double = numOrNull(v) ?: throw ComputeError("$name: esperava número, recebeu ${str(v).take(40).ifEmpty { "nulo" }}")
        private fun intArg(v: Any?, name: String, lo: Int = 1, hi: Int = 5000): Int {
            val i = dbl(v, name).toInt()
            if (i < lo || i > hi) throw ComputeError("$name: período $i fora de $lo–$hi")
            return i
        }
        private fun list(v: Any?, name: String): List<Any?> = v as? List<*> ?: throw ComputeError("$name: esperava lista, recebeu ${str(v).take(40).ifEmpty { "nulo" }}")
        private fun ser(v: Any?, name: String): DoubleArray {
            val l = list(v, name)
            if (l.size > MAX_LIST) throw ComputeError("$name: lista grande demais")
            return DoubleArray(l.size) { numOrNull(l[it]) ?: Double.NaN }
        }
        private fun out(a: DoubleArray): List<Any?> = a.map { if (it.isNaN() || it.isInfinite()) null else it }
        private fun nums(v: Any?, name: String): List<Double> = list(v, name).mapNotNull { numOrNull(it) }

        // ---------- primitivas de série
        fun sma(a: DoubleArray, n: Int): DoubleArray {
            val r = DoubleArray(a.size) { Double.NaN }
            var s = 0.0; var cnt = 0
            for (i in a.indices) {
                if (a[i].isNaN()) { s = 0.0; cnt = 0; continue }
                s += a[i]; cnt++
                if (cnt > n) { s -= a[i - n]; cnt = n }
                if (cnt == n) r[i] = s / n
            }
            return r
        }
        private fun emaAlpha(a: DoubleArray, n: Int, alpha: Double): DoubleArray {
            val r = DoubleArray(a.size) { Double.NaN }
            var prev = Double.NaN; var seed = 0.0; var cnt = 0
            for (i in a.indices) {
                val x = a[i]
                if (x.isNaN()) { if (!prev.isNaN()) r[i] = Double.NaN; continue }
                if (prev.isNaN()) {
                    seed += x; cnt++
                    if (cnt == n) { prev = seed / n; r[i] = prev }
                } else { prev = alpha * x + (1 - alpha) * prev; r[i] = prev }
            }
            return r
        }
        fun ema(a: DoubleArray, n: Int) = emaAlpha(a, n, 2.0 / (n + 1))
        fun rma(a: DoubleArray, n: Int) = emaAlpha(a, n, 1.0 / n)
        fun wma(a: DoubleArray, n: Int): DoubleArray {
            val r = DoubleArray(a.size) { Double.NaN }
            val den = n * (n + 1) / 2.0
            for (i in n - 1 until a.size) {
                var s = 0.0; var ok = true
                for (j in 0 until n) { val x = a[i - j]; if (x.isNaN()) { ok = false; break }; s += x * (n - j) }
                if (ok) r[i] = s / den
            }
            return r
        }
        private fun rolling(a: DoubleArray, n: Int, f: (DoubleArray) -> Double): DoubleArray {
            val r = DoubleArray(a.size) { Double.NaN }
            for (i in n - 1 until a.size) {
                val w = a.copyOfRange(i - n + 1, i + 1)
                if (w.any { it.isNaN() }) continue
                r[i] = f(w)
            }
            return r
        }
        fun highest(a: DoubleArray, n: Int) = rolling(a, n) { it.max() }
        fun lowest(a: DoubleArray, n: Int) = rolling(a, n) { it.min() }
        fun rstdev(a: DoubleArray, n: Int) = rolling(a, n) { w -> val m = w.average(); sqrt(w.sumOf { (it - m) * (it - m) } / w.size) }
        fun rsum(a: DoubleArray, n: Int) = rolling(a, n) { it.sum() }
        private fun zip(a: DoubleArray, b: DoubleArray, f: (Double, Double) -> Double) = DoubleArray(max(a.size, b.size)) { i ->
            val x = a.getOrElse(i) { Double.NaN }; val y = b.getOrElse(i) { Double.NaN }
            if (x.isNaN() || y.isNaN()) Double.NaN else f(x, y)
        }
        private fun lag(a: DoubleArray, n: Int) = DoubleArray(a.size) { i -> if (i - n >= 0 && i - n < a.size) a[i - n] else Double.NaN }
        fun trueRange(h: DoubleArray, l: DoubleArray, c: DoubleArray) = DoubleArray(c.size) { i ->
            if (i == 0) h[0] - l[0] else max(h[i] - l[i], max(abs(h[i] - c[i - 1]), abs(l[i] - c[i - 1])))
        }
        fun rsi(c: DoubleArray, n: Int): DoubleArray {
            val up = DoubleArray(c.size) { i -> if (i == 0 || c[i].isNaN() || c[i - 1].isNaN()) Double.NaN else max(c[i] - c[i - 1], 0.0) }
            val dn = DoubleArray(c.size) { i -> if (i == 0 || c[i].isNaN() || c[i - 1].isNaN()) Double.NaN else max(c[i - 1] - c[i], 0.0) }
            val au = rma(up, n); val ad = rma(dn, n)
            return DoubleArray(c.size) { i -> if (au[i].isNaN() || ad[i].isNaN()) Double.NaN else if (ad[i] == 0.0) 100.0 else 100 - 100 / (1 + au[i] / ad[i]) }
        }
        private fun stochRaw(h: DoubleArray, l: DoubleArray, c: DoubleArray, n: Int): DoubleArray {
            val hh = highest(h, n); val ll = lowest(l, n)
            return DoubleArray(c.size) { i -> val d = hh[i] - ll[i]; if (d.isNaN() || c[i].isNaN()) Double.NaN else if (d == 0.0) 50.0 else 100 * (c[i] - ll[i]) / d }
        }
        private fun linregAt(w: DoubleArray): Pair<Double, Double> { // (valor no fim, inclinação)
            val n = w.size; val xm = (n - 1) / 2.0; val ym = w.average()
            var num = 0.0; var den = 0.0
            for (i in 0 until n) { num += (i - xm) * (w[i] - ym); den += (i - xm) * (i - xm) }
            val slope = if (den == 0.0) 0.0 else num / den
            return (ym + slope * (n - 1 - xm)) to slope
        }
        private fun obj(vararg p: Pair<String, DoubleArray>): Map<String, Any?> = linkedMapOf(*p.map { it.first to out(it.second) }.toTypedArray())

        fun call(fn: String, a: List<Any?>, ctx: Ctx): Any? {
            val sizes = a.sumOf { (it as? List<*>)?.size ?: 1 }.toLong()
            ctx.charge(sizes)
            fun d(i: Int, def: Double? = null) = if (i < a.size) dbl(a[i], fn) else def ?: throw ComputeError("$fn: falta o argumento ${i + 1}")
            fun n(i: Int, def: Int? = null) = if (i < a.size) intArg(a[i], fn) else def ?: throw ComputeError("$fn: falta o período")
            fun s(i: Int) = ser(a[i], fn)
            fun l(i: Int) = list(a[i], fn)
            fun numsOrVar(): List<Double> = if (a.size == 1 && a[0] is List<*>) nums(a[0], fn) else a.mapNotNull { numOrNull(it) }
            return when (fn) {
                "abs" -> numOrNull(a[0])?.let { abs(it) }
                "round" -> numOrNull(a[0])?.let { x -> val p = if (a.size > 1) intArg(a[1], fn, 0, 12) else 0; BigDecimal(x).setScale(p, RoundingMode.HALF_UP).toDouble() }
                "floor" -> numOrNull(a[0])?.let { Math.floor(it) }
                "ceil" -> numOrNull(a[0])?.let { Math.ceil(it) }
                "sqrt" -> numOrNull(a[0])?.takeIf { it >= 0 }?.let { sqrt(it) }
                "ln", "log" -> numOrNull(a[0])?.takeIf { it > 0 }?.let { Math.log(it) }
                "log10" -> numOrNull(a[0])?.takeIf { it > 0 }?.let { Math.log10(it) }
                "exp" -> numOrNull(a[0])?.let { Math.exp(it) }?.takeIf { !it.isInfinite() }
                "pow" -> arith("^", a[0], a[1])
                "min" -> numsOrVar().minOrNull()
                "max" -> numsOrVar().maxOrNull()
                "clamp" -> numOrNull(a[0])?.let { x -> min(max(x, d(1)), d(2)) }
                "sign" -> numOrNull(a[0])?.let { Math.signum(it) }
                "num", "parse_num" -> numOrNull(a[0])
                "int" -> numOrNull(a[0])?.let { it.toLong().toDouble() }
                "str" -> str(a[0])
                "bool" -> truthy(a[0])
                "isnull" -> a[0] == null
                "coalesce" -> a.firstOrNull { it != null }
                "if" -> if (truthy(a[0])) a[1] else a[2]
                "call" -> Compute.callFn(a[0], a.drop(1), ctx)
                "fixed" -> numOrNull(a[0])?.let { String.format(Locale.US, "%.${intArg(a[1], fn, 0, 12)}f", it) } ?: ""
                "type" -> when (a[0]) { null -> "null"; is Double -> "number"; is String -> "string"; is Boolean -> "boolean"; is List<*> -> "list"; is Map<*, *> -> "object"; else -> "function" }
                "upper" -> str(a[0]).uppercase()
                "lower" -> str(a[0]).lowercase()
                "trim" -> str(a[0]).trim()
                "contains" -> if (a[0] is List<*>) (a[0] as List<*>).any { eq(it, a[1]) } else str(a[0]).contains(str(a[1]), ignoreCase = true)
                "startswith" -> str(a[0]).startsWith(str(a[1]))
                "endswith" -> str(a[0]).endsWith(str(a[1]))
                "replace" -> str(a[0]).replace(str(a[1]), str(a[2]))
                "split" -> str(a[0]).split(str(a[1])).also { if (it.size > MAX_LIST) throw ComputeError("split: resultado grande demais") }
                "join" -> l(0).joinToString(str(a[1])) { str(it) }
                "substr" -> str(a[0]).let { t -> val st = d(1).toInt().coerceIn(0, t.length); val en = if (a.size > 2) (st + d(2).toInt()).coerceIn(st, t.length) else t.length; t.substring(st, en) }
                "len" -> when (val x = a[0]) { is List<*> -> x.size.toDouble(); is Map<*, *> -> x.size.toDouble(); null -> 0.0; else -> str(x).length.toDouble() }
                "date" -> numOrNull(a[0])?.let { t ->
                    val ms = if (t > 1e11) t.toLong() else (t * 1000).toLong()
                    val pat = if (a.size > 1) str(a[1]) else "dd/MM HH:mm"
                    if (!Regex("^[yMdHhmsaE /:.\\-T,']{1,40}$").matches(pat)) throw ComputeError("date: padrão inválido")
                    SimpleDateFormat(pat, Locale.US).apply { timeZone = TimeZone.getDefault() }.format(Date(ms))
                } ?: ""
                "now" -> System.currentTimeMillis().toDouble()
                "regex" -> {
                    val pat = str(a[1]); if (pat.length > 200) throw ComputeError("regex: padrão longo demais")
                    val m = Regex(pat).find(str(a[0]).take(100_000)) ?: return null
                    if (m.groupValues.size > 1) m.groupValues[1] else m.value
                }
                "range" -> {
                    val (st, en, stp) = when (a.size) { 1 -> Triple(0.0, d(0), 1.0); 2 -> Triple(d(0), d(1), 1.0); else -> Triple(d(0), d(1), d(2)) }
                    if (stp == 0.0) throw ComputeError("range: passo zero")
                    val cnt = ((en - st) / stp).let { if (it <= 0) 0 else Math.ceil(it).toInt() }
                    if (cnt > MAX_LIST) throw ComputeError("range: grande demais")
                    ctx.charge(cnt.toLong()); List(cnt) { st + it * stp }
                }
                "sum" -> nums(a[0], fn).sum()
                "avg", "mean" -> nums(a[0], fn).takeIf { it.isNotEmpty() }?.average()
                "median" -> nums(a[0], fn).sorted().takeIf { it.isNotEmpty() }?.let { s -> if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }
                "variance", "stdev" -> nums(a[0], fn).takeIf { it.size > 1 }?.let { s -> val m = s.average(); val v = s.sumOf { (it - m) * (it - m) } / (s.size - 1); if (fn == "stdev") sqrt(v) else v }
                "first" -> l(0).firstOrNull { it != null }
                "last" -> l(0).lastOrNull { it != null }
                "at" -> index(a[0], a[1])
                "slice" -> l(0).let { x -> val sz = x.size
                    fun norm(v: Int) = (if (v < 0) sz + v else v).coerceIn(0, sz)
                    val st = norm(d(1).toInt()); val en = if (a.size > 2) norm(d(2).toInt()) else sz
                    if (st >= en) emptyList() else x.subList(st, en).toList() }
                "take" -> l(0).take(d(1).toInt().coerceAtLeast(0))
                "tail" -> l(0).takeLast(d(1).toInt().coerceAtLeast(0))
                "reverse" -> l(0).reversed()
                "sort" -> l(0).sortedWith { x, y -> val p = numOrNull(x); val q = numOrNull(y); if (p != null && q != null) p.compareTo(q) else str(x).compareTo(str(y)) }
                "sort_by" -> { val keyd = l(0).map { it to Compute.callFn(a[1], listOf(it), ctx) }
                    keyd.sortedWith { x, y -> val p = numOrNull(x.second); val q = numOrNull(y.second); if (p != null && q != null) p.compareTo(q) else str(x.second).compareTo(str(y.second)) }.map { it.first } }
                "unique" -> l(0).distinct()
                "map" -> l(0).mapIndexed { i, x -> Compute.callFn(a[1], listOf(x, i.toDouble()), ctx) }
                "filter" -> l(0).filterIndexed { i, x -> truthy(Compute.callFn(a[1], listOf(x, i.toDouble()), ctx)) }
                "reduce" -> l(0).fold(a[2]) { acc, x -> Compute.callFn(a[1], listOf(acc, x), ctx) }
                "find" -> l(0).firstOrNull { truthy(Compute.callFn(a[1], listOf(it), ctx)) }
                "count" -> (if (a.size > 1) l(0).count { truthy(Compute.callFn(a[1], listOf(it), ctx)) } else l(0).size).toDouble()
                "any" -> l(0).any { truthy(Compute.callFn(a[1], listOf(it), ctx)) }
                "all" -> l(0).all { truthy(Compute.callFn(a[1], listOf(it), ctx)) }
                "flatten" -> l(0).flatMap { if (it is List<*>) it else listOf(it) }
                "compact" -> l(0).filterNotNull()
                "concat" -> a.flatMap { list(it, fn) }
                "zip" -> { val ls = a.map { list(it, fn) }; val sz = ls.minOf { it.size }; List(sz) { i -> ls.map { it[i] } } }
                "zip_rows" -> { val m = a[0] as? Map<*, *> ?: throw ComputeError("zip_rows: esperava objeto {coluna: lista}")
                    val cols = m.entries.map { it.key.toString() to (it.value as? List<*>) }
                    val sz = cols.mapNotNull { it.second?.size }.maxOrNull() ?: 0
                    List(sz) { i -> linkedMapOf(*cols.map { (k, v) -> k to (if (v != null) v.getOrNull(i) else m[k]) }.toTypedArray()) } }
                "pluck" -> l(0).map { member(it, str(a[1])) }
                "keys" -> (a[0] as? Map<*, *>)?.keys?.map { it.toString() } ?: emptyList<String>()
                "values" -> (a[0] as? Map<*, *>)?.values?.toList() ?: emptyList<Any?>()
                "get" -> (if (a[0] is List<*>) index(a[0], a[1]) else member(a[0], str(a[1]))) ?: a.getOrNull(2)
                "index_of" -> l(0).indexOfFirst { eq(it, a[1]) }.toDouble()
                "group_by" -> LinkedHashMap<String, MutableList<Any?>>().apply { l(0).forEach { getOrPut(str(Compute.callFn(a[1], listOf(it), ctx))) { ArrayList() }.add(it) } }
                "diff" -> out(s(0).let { x -> DoubleArray(x.size) { i -> if (i == 0) Double.NaN else x[i] - x[i - 1] } })
                "change" -> out(s(0).let { x -> val k = n(1, 1); DoubleArray(x.size) { i -> if (i < k) Double.NaN else x[i] - x[i - k] } })
                "pct_change" -> out(s(0).let { x -> DoubleArray(x.size) { i -> if (i == 0 || x[i - 1] == 0.0) Double.NaN else 100 * (x[i] - x[i - 1]) / x[i - 1] } })
                "cumsum" -> out(s(0).let { x -> var acc = 0.0; DoubleArray(x.size) { i -> if (!x[i].isNaN()) acc += x[i]; acc } })
                "shift" -> l(0).let { x -> val k = d(1).toInt(); List(x.size) { i -> x.getOrNull(i - k) } }
                "fill" -> l(0).let { x -> var prev: Any? = null; x.map { v -> if (v != null) prev = v; prev } }
                "repeat" -> { val k = n(1).coerceAtMost(MAX_LIST); List(k) { a[0] } }
                "path" -> {
                    val (src, p) = if (a.size == 2) toJson(a[0]) to str(a[1]) else (ctx.root ?: JsonNull) to str(a[0])
                    if (!JsonPath.isValid(p)) throw ComputeError("path: caminho inválido '$p'")
                    JsonPath.select(src, p).map { fromJson(it) }
                }
                "correl" -> { val x = s(0); val y = s(1); val idx = x.indices.filter { it < y.size && !x[it].isNaN() && !y[it].isNaN() }
                    if (idx.size < 2) null else { val mx = idx.map { x[it] }.average(); val my = idx.map { y[it] }.average()
                        val cov = idx.sumOf { (x[it] - mx) * (y[it] - my) }; val vx = idx.sumOf { (x[it] - mx) * (x[it] - mx) }; val vy = idx.sumOf { (y[it] - my) * (y[it] - my) }
                        if (vx == 0.0 || vy == 0.0) null else cov / sqrt(vx * vy) } }
                "argmax" -> s(0).let { x -> x.indices.filter { !x[it].isNaN() }.maxByOrNull { x[it] }?.toDouble() }
                "argmin" -> s(0).let { x -> x.indices.filter { !x[it].isNaN() }.minByOrNull { x[it] }?.toDouble() }
                "highest" -> out(highest(s(0), n(1)))
                "lowest" -> out(lowest(s(0), n(1)))
                "rsum" -> out(rsum(s(0), n(1)))
                "rstdev" -> out(rstdev(s(0), n(1)))
                "linreg" -> out(rolling(s(0), n(1, 2)) { linregAt(it).first })
                "slope" -> out(rolling(s(0), n(1, 2)) { linregAt(it).second })
                "zscore" -> { val x = s(0); val m = sma(x, n(1)); val sd = rstdev(x, n(1)); out(DoubleArray(x.size) { i -> if (sd[i].isNaN() || sd[i] == 0.0) Double.NaN else (x[i] - m[i]) / sd[i] }) }
                "rolling" -> { val x = l(0); val k = n(1); List(x.size) { i -> if (i < k - 1) null else Compute.callFn(a[2], listOf(x.subList(i - k + 1, i + 1).toList()), ctx) } }
                "crossover", "crossunder" -> {
                    val x = if (a[0] is List<*>) s(0) else DoubleArray(2) { d(0) }
                    val y = if (a[1] is List<*>) s(1) else DoubleArray(2) { d(1) }
                    val xi = x.indices.filter { !x[it].isNaN() }; val yi = y.indices.filter { !y[it].isNaN() }
                    if (xi.size < 2 || yi.size < 2) false else {
                        val x1 = x[xi.last()]; val x0 = x[xi[xi.size - 2]]; val y1 = y[yi.last()]; val y0 = y[yi[yi.size - 2]]
                        if (fn == "crossover") x0 <= y0 && x1 > y1 else x0 >= y0 && x1 < y1
                    }
                }
                "cross_series" -> { val x = s(0); val y = if (a[1] is List<*>) s(1) else DoubleArray(x.size) { d(1) }
                    List(x.size) { i -> if (i == 0 || x[i].isNaN() || x[i - 1].isNaN() || y.getOrElse(i) { Double.NaN }.isNaN() || y.getOrElse(i - 1) { Double.NaN }.isNaN()) 0.0
                        else if (x[i - 1] <= y[i - 1] && x[i] > y[i]) 1.0 else if (x[i - 1] >= y[i - 1] && x[i] < y[i]) -1.0 else 0.0 } }
                "barssince" -> l(0).let { x -> val i = x.indexOfLast { truthy(it) }; if (i < 0) null else (x.size - 1 - i).toDouble() }
                "signal" -> l(0).lastOrNull { it != null }.let { v -> val x = numOrNull(v) ?: return "neutro"; if (x > 0) "compra" else if (x < 0) "venda" else "neutro" }

                // ---------- médias
                "sma" -> out(sma(s(0), n(1)))
                "ema" -> out(ema(s(0), n(1)))
                "rma" -> out(rma(s(0), n(1)))
                "wma" -> out(wma(s(0), n(1)))
                "dema" -> { val e1 = ema(s(0), n(1)); val e2 = ema(e1, n(1)); out(zip(e1, e2) { x, y -> 2 * x - y }) }
                "tema" -> { val e1 = ema(s(0), n(1)); val e2 = ema(e1, n(1)); val e3 = ema(e2, n(1)); out(DoubleArray(e1.size) { i -> 3 * e1[i] - 3 * e2[i] + e3[i] }) }
                "hma" -> { val x = s(0); val k = n(1, 2).coerceAtLeast(2); val w = zip(wma(x, max(1, k / 2)), wma(x, k)) { p, q -> 2 * p - q }; out(wma(w, max(1, sqrt(k.toDouble()).toInt()))) }
                "vwma" -> { val c = s(0); val v = s(1); val k = n(2); val pv = sma(zip(c, v) { x, y -> x * y }, k); val sv = sma(v, k); out(zip(pv, sv) { x, y -> if (y == 0.0) Double.NaN else x / y }) }
                "alma" -> { val x = s(0); val k = n(1); val off = d(2, 0.85); val sig = d(3, 6.0)
                    val m = off * (k - 1); val sd = k / sig; val w = DoubleArray(k) { Math.exp(-((it - m) * (it - m)) / (2 * sd * sd)) }; val ws = w.sum()
                    out(rolling(x, k) { win -> var acc = 0.0; for (j in 0 until k) acc += win[j] * w[j]; acc / ws }) }
                "kama" -> { val x = s(0); val k = n(1, 10); val fast = 2.0 / (n(2, 2) + 1); val slow = 2.0 / (n(3, 30) + 1)
                    val r = DoubleArray(x.size) { Double.NaN }; var prev = Double.NaN
                    for (i in x.indices) { if (i < k || x[i].isNaN()) continue
                        val chg = abs(x[i] - x[i - k]); var vol = 0.0; for (j in i - k + 1..i) vol += abs(x[j] - x[j - 1])
                        val er = if (vol == 0.0) 0.0 else chg / vol; val sc = (er * (fast - slow) + slow).let { it * it }
                        prev = if (prev.isNaN()) x[i - 1] + sc * (x[i] - x[i - 1]) else prev + sc * (x[i] - prev); r[i] = prev }
                    out(r) }

                // ---------- osciladores
                "rsi" -> out(rsi(s(0), n(1)))
                "stoch" -> { val h = s(0); val lo = s(1); val c = s(2); val raw = stochRaw(h, lo, c, n(3, 14)); val k = sma(raw, n(5, 1)); val dd = sma(k, n(4, 3)); obj("k" to k, "d" to dd) }
                "stochrsi" -> { val r = rsi(s(0), n(1, 14)); val raw = stochRaw(r, r, r, n(2, 14)); val k = sma(raw, n(3, 3)); obj("k" to k, "d" to sma(k, n(4, 3))) }
                "macd" -> { val c = s(0); val m = zip(ema(c, n(1, 12)), ema(c, n(2, 26))) { x, y -> x - y }; val sg = ema(m, n(3, 9)); obj("macd" to m, "signal" to sg, "hist" to zip(m, sg) { x, y -> x - y }) }
                "ppo" -> { val c = s(0); val f = ema(c, n(1, 12)); val sl = ema(c, n(2, 26)); val p = zip(f, sl) { x, y -> if (y == 0.0) Double.NaN else 100 * (x - y) / y }; val sg = ema(p, n(3, 9)); obj("ppo" to p, "signal" to sg, "hist" to zip(p, sg) { x, y -> x - y }) }
                "cci" -> { val h = s(0); val lo = s(1); val c = s(2); val tp = DoubleArray(c.size) { i -> (h[i] + lo[i] + c[i]) / 3 }; val k = n(3, 20); val m = sma(tp, k)
                    val md = rolling(tp, k) { w -> val mm = w.average(); w.sumOf { abs(it - mm) } / w.size }
                    out(DoubleArray(tp.size) { i -> if (md[i].isNaN() || md[i] == 0.0) Double.NaN else (tp[i] - m[i]) / (0.015 * md[i]) }) }
                "willr" -> { val h = s(0); val lo = s(1); val c = s(2); val k = n(3, 14); val hh = highest(h, k); val ll = lowest(lo, k)
                    out(DoubleArray(c.size) { i -> val dd = hh[i] - ll[i]; if (dd.isNaN() || dd == 0.0) Double.NaN else -100 * (hh[i] - c[i]) / dd }) }
                "mfi" -> { val h = s(0); val lo = s(1); val c = s(2); val v = s(3); val k = n(4, 14)
                    val tp = DoubleArray(c.size) { (h[it] + lo[it] + c[it]) / 3 }
                    val pos = DoubleArray(c.size) { i -> if (i == 0) Double.NaN else if (tp[i] > tp[i - 1]) tp[i] * v[i] else 0.0 }
                    val neg = DoubleArray(c.size) { i -> if (i == 0) Double.NaN else if (tp[i] < tp[i - 1]) tp[i] * v[i] else 0.0 }
                    val ps = rsum(pos, k); val ns = rsum(neg, k)
                    out(DoubleArray(c.size) { i -> if (ps[i].isNaN()) Double.NaN else if (ns[i] == 0.0) 100.0 else 100 - 100 / (1 + ps[i] / ns[i]) }) }
                "roc" -> { val c = s(0); val k = n(1, 9); out(DoubleArray(c.size) { i -> if (i < k || c[i - k] == 0.0) Double.NaN else 100 * (c[i] - c[i - k]) / c[i - k] }) }
                "mom" -> { val c = s(0); val k = n(1, 10); out(DoubleArray(c.size) { i -> if (i < k) Double.NaN else c[i] - c[i - k] }) }
                "trix" -> { val k = n(1, 18); val e3 = ema(ema(ema(s(0), k), k), k); out(DoubleArray(e3.size) { i -> if (i == 0 || e3[i - 1].isNaN() || e3[i - 1] == 0.0) Double.NaN else 100 * (e3[i] - e3[i - 1]) / e3[i - 1] }) }
                "cmo" -> { val c = s(0); val k = n(1, 9); val up = DoubleArray(c.size) { i -> if (i == 0) Double.NaN else max(c[i] - c[i - 1], 0.0) }
                    val dn = DoubleArray(c.size) { i -> if (i == 0) Double.NaN else max(c[i - 1] - c[i], 0.0) }; val su = rsum(up, k); val sd = rsum(dn, k)
                    out(DoubleArray(c.size) { i -> val t = su[i] + sd[i]; if (t.isNaN() || t == 0.0) Double.NaN else 100 * (su[i] - sd[i]) / t }) }
                "dpo" -> { val c = s(0); val k = n(1, 21); val m = sma(c, k); val back = k / 2 + 1; out(DoubleArray(c.size) { i -> if (i < back) Double.NaN else c[i] - m[i - back] }) }
                "tsi" -> { val c = s(0); val m = DoubleArray(c.size) { i -> if (i == 0) Double.NaN else c[i] - c[i - 1] }; val a1 = DoubleArray(m.size) { abs(m[it]) }
                    val r = n(1, 25); val sh = n(2, 13); val num = ema(ema(m, r), sh); val den = ema(ema(a1, r), sh)
                    out(zip(num, den) { x, y -> if (y == 0.0) Double.NaN else 100 * x / y }) }
                "ao" -> { val h = s(0); val lo = s(1); val m = DoubleArray(h.size) { (h[it] + lo[it]) / 2 }; out(zip(sma(m, 5), sma(m, 34)) { x, y -> x - y }) }
                "uo" -> { val h = s(0); val lo = s(1); val c = s(2); val p1 = n(3, 7); val p2 = n(4, 14); val p3 = n(5, 28)
                    val bp = DoubleArray(c.size) { i -> if (i == 0) Double.NaN else c[i] - min(lo[i], c[i - 1]) }
                    val trr = DoubleArray(c.size) { i -> if (i == 0) Double.NaN else max(h[i], c[i - 1]) - min(lo[i], c[i - 1]) }
                    fun av(p: Int) = zip(rsum(bp, p), rsum(trr, p)) { x, y -> if (y == 0.0) Double.NaN else x / y }
                    val a1 = av(p1); val a2 = av(p2); val a3 = av(p3)
                    out(DoubleArray(c.size) { i -> 100 * (4 * a1[i] + 2 * a2[i] + a3[i]) / 7 }) }
                "fisher" -> { val h = s(0); val lo = s(1); val k = n(2, 9); val m = DoubleArray(h.size) { (h[it] + lo[it]) / 2 }; val hh = highest(m, k); val ll = lowest(m, k)
                    val r = DoubleArray(m.size) { Double.NaN }; var v = 0.0; var f = 0.0
                    for (i in m.indices) { if (hh[i].isNaN()) continue; val rg = hh[i] - ll[i]
                        v = (0.66 * ((if (rg == 0.0) 0.0 else (m[i] - ll[i]) / rg) - 0.5) + 0.67 * v).coerceIn(-0.999, 0.999)
                        f = 0.5 * Math.log((1 + v) / (1 - v)) + 0.5 * f; r[i] = f }
                    out(r) }

                // ---------- volatilidade / tendência
                "tr" -> out(trueRange(s(0), s(1), s(2)))
                "atr" -> out(rma(trueRange(s(0), s(1), s(2)), n(3, 14)))
                "bollinger", "bbw" -> { val c = s(0); val k = n(1, 20); val mult = d(2, 2.0); val m = sma(c, k); val sd = rstdev(c, k)
                    val up = zip(m, sd) { x, y -> x + mult * y }; val lo = zip(m, sd) { x, y -> x - mult * y }
                    val width = DoubleArray(c.size) { i -> if (m[i] == 0.0) Double.NaN else (up[i] - lo[i]) / m[i] }
                    if (fn == "bbw") out(width) else obj("upper" to up, "middle" to m, "lower" to lo, "width" to width,
                        "pctb" to DoubleArray(c.size) { i -> val dd = up[i] - lo[i]; if (dd == 0.0) Double.NaN else (c[i] - lo[i]) / dd }) }
                "keltner" -> { val h = s(0); val lo = s(1); val c = s(2); val k = n(3, 20); val mult = d(4, 2.0); val m = ema(c, k); val at = rma(trueRange(h, lo, c), k)
                    obj("upper" to zip(m, at) { x, y -> x + mult * y }, "middle" to m, "lower" to zip(m, at) { x, y -> x - mult * y }) }
                "donchian" -> { val k = n(2, 20); val up = highest(s(0), k); val lo = lowest(s(1), k); obj("upper" to up, "middle" to zip(up, lo) { x, y -> (x + y) / 2 }, "lower" to lo) }
                "adx" -> { val h = s(0); val lo = s(1); val c = s(2); val k = n(3, 14)
                    val pdm = DoubleArray(h.size) { i -> if (i == 0) Double.NaN else { val u = h[i] - h[i - 1]; val dn = lo[i - 1] - lo[i]; if (u > dn && u > 0) u else 0.0 } }
                    val mdm = DoubleArray(h.size) { i -> if (i == 0) Double.NaN else { val u = h[i] - h[i - 1]; val dn = lo[i - 1] - lo[i]; if (dn > u && dn > 0) dn else 0.0 } }
                    val trr = trueRange(h, lo, c).also { it[0] = Double.NaN }; val at = rma(trr, k)
                    val pdi = zip(rma(pdm, k), at) { x, y -> if (y == 0.0) Double.NaN else 100 * x / y }
                    val mdi = zip(rma(mdm, k), at) { x, y -> if (y == 0.0) Double.NaN else 100 * x / y }
                    val dx = zip(pdi, mdi) { x, y -> if (x + y == 0.0) 0.0 else 100 * abs(x - y) / (x + y) }
                    obj("adx" to rma(dx, k), "pdi" to pdi, "mdi" to mdi) }
                "aroon" -> { val h = s(0); val lo = s(1); val k = n(2, 14)
                    val up = DoubleArray(h.size) { Double.NaN }; val dn = DoubleArray(h.size) { Double.NaN }
                    for (i in k until h.size) { var hi = i - k; var li = i - k
                        for (j in i - k..i) { if (h[j] >= h[hi]) hi = j; if (lo[j] <= lo[li]) li = j }
                        up[i] = 100.0 * (k - (i - hi)) / k; dn[i] = 100.0 * (k - (i - li)) / k }
                    obj("up" to up, "down" to dn, "osc" to zip(up, dn) { x, y -> x - y }) }
                "psar" -> { val h = s(0); val lo = s(1); val step = d(2, 0.02); val mx = d(3, 0.2)
                    val r = DoubleArray(h.size) { Double.NaN }
                    if (h.size >= 2) { var up = h[1] >= h[0]; var sar = if (up) lo[0] else h[0]; var ep = if (up) h[1] else lo[1]; var af = step
                        for (i in 1 until h.size) {
                            sar += af * (ep - sar)
                            if (up) { sar = min(sar, min(lo[i - 1], if (i > 1) lo[i - 2] else lo[i - 1]))
                                if (lo[i] < sar) { up = false; sar = ep; ep = lo[i]; af = step } else if (h[i] > ep) { ep = h[i]; af = min(af + step, mx) }
                            } else { sar = max(sar, max(h[i - 1], if (i > 1) h[i - 2] else h[i - 1]))
                                if (h[i] > sar) { up = true; sar = ep; ep = h[i]; af = step } else if (lo[i] < ep) { ep = lo[i]; af = min(af + step, mx) } }
                            r[i] = sar } }
                    out(r) }
                "supertrend" -> { val h = s(0); val lo = s(1); val c = s(2); val k = n(3, 10); val mult = d(4, 3.0); val at = rma(trueRange(h, lo, c), k)
                    val line = DoubleArray(c.size) { Double.NaN }; val dir = DoubleArray(c.size) { Double.NaN }
                    var fu = Double.NaN; var fl = Double.NaN; var trendUp = true
                    for (i in c.indices) { if (at[i].isNaN()) continue
                        val mid = (h[i] + lo[i]) / 2; val bu = mid + mult * at[i]; val bl = mid - mult * at[i]
                        val pc = if (i > 0) c[i - 1] else c[i]
                        fu = if (fu.isNaN() || bu < fu || pc > fu) bu else fu
                        fl = if (fl.isNaN() || bl > fl || pc < fl) bl else fl
                        trendUp = if (trendUp) c[i] >= fl else c[i] > fu
                        line[i] = if (trendUp) fl else fu; dir[i] = if (trendUp) 1.0 else -1.0 }
                    obj("line" to line, "dir" to dir) }
                "ichimoku" -> { val h = s(0); val lo = s(1); fun mid(p: Int) = zip(highest(h, p), lowest(lo, p)) { x, y -> (x + y) / 2 }
                    val t = mid(n(2, 9)); val k = mid(n(3, 26)); obj("tenkan" to t, "kijun" to k, "spanA" to zip(t, k) { x, y -> (x + y) / 2 }, "spanB" to mid(n(4, 52))) }
                "chop" -> { val h = s(0); val lo = s(1); val c = s(2); val k = n(3, 14); val st = rsum(trueRange(h, lo, c), k); val hh = highest(h, k); val ll = lowest(lo, k)
                    out(DoubleArray(c.size) { i -> val rg = hh[i] - ll[i]; if (rg.isNaN() || rg == 0.0 || st[i].isNaN()) Double.NaN else 100 * Math.log10(st[i] / rg) / Math.log10(k.toDouble()) }) }
                "elder" -> { val h = s(0); val lo = s(1); val c = s(2); val e = ema(c, n(3, 13)); obj("bull" to zip(h, e) { x, y -> x - y }, "bear" to zip(lo, e) { x, y -> x - y }) }
                "pivots" -> { val h = numOrNull(if (a[0] is List<*>) l(0).lastOrNull { it != null } else a[0]) ?: return null
                    val lo = numOrNull(if (a[1] is List<*>) l(1).lastOrNull { it != null } else a[1]) ?: return null
                    val c = numOrNull(if (a[2] is List<*>) l(2).lastOrNull { it != null } else a[2]) ?: return null
                    val p = (h + lo + c) / 3
                    linkedMapOf("p" to p, "r1" to 2 * p - lo, "s1" to 2 * p - h, "r2" to p + (h - lo), "s2" to p - (h - lo), "r3" to h + 2 * (p - lo), "s3" to lo - 2 * (h - p)) }

                // ---------- volume
                "obv" -> { val c = s(0); val v = s(1); var acc = 0.0; out(DoubleArray(c.size) { i -> if (i > 0) acc += if (c[i] > c[i - 1]) v[i] else if (c[i] < c[i - 1]) -v[i] else 0.0; acc }) }
                "vwap" -> { val h = s(0); val lo = s(1); val c = s(2); val v = s(3); var pv = 0.0; var vv = 0.0
                    out(DoubleArray(c.size) { i -> val tp = (h[i] + lo[i] + c[i]) / 3; if (!tp.isNaN() && !v[i].isNaN()) { pv += tp * v[i]; vv += v[i] }; if (vv == 0.0) Double.NaN else pv / vv }) }
                "adl", "cmf" -> { val h = s(0); val lo = s(1); val c = s(2); val v = s(3)
                    val mfv = DoubleArray(c.size) { i -> val rg = h[i] - lo[i]; if (rg == 0.0) 0.0 else ((c[i] - lo[i]) - (h[i] - c[i])) / rg * v[i] }
                    if (fn == "adl") { var acc = 0.0; out(DoubleArray(c.size) { i -> if (!mfv[i].isNaN()) acc += mfv[i]; acc }) }
                    else { val k = n(4, 20); out(zip(rsum(mfv, k), rsum(v, k)) { x, y -> if (y == 0.0) Double.NaN else x / y }) } }

                // ---------- preços derivados
                "hl2" -> out(zip(s(0), s(1)) { x, y -> (x + y) / 2 })
                "hlc3" -> { val h = s(0); val lo = s(1); val c = s(2); out(DoubleArray(c.size) { (h[it] + lo[it] + c[it]) / 3 }) }
                "ohlc4" -> { val o = s(0); val h = s(1); val lo = s(2); val c = s(3); out(DoubleArray(c.size) { (o[it] + h[it] + lo[it] + c[it]) / 4 }) }
                "heikin" -> { val o = s(0); val h = s(1); val lo = s(2); val c = s(3)
                    val ho = DoubleArray(c.size); val hc = DoubleArray(c.size) { (o[it] + h[it] + lo[it] + c[it]) / 4 }
                    for (i in c.indices) ho[i] = if (i == 0) (o[0] + c[0]) / 2 else (ho[i - 1] + hc[i - 1]) / 2
                    obj("open" to ho, "high" to DoubleArray(c.size) { max(h[it], max(ho[it], hc[it])) }, "low" to DoubleArray(c.size) { min(lo[it], min(ho[it], hc[it])) }, "close" to hc) }
                else -> throw ComputeError("função desconhecida '$fn'")
            }.also { r -> if (r is List<*> && r.size > MAX_LIST) throw ComputeError("$fn: resultado grande demais") }
        }
    }

    /**
     * Avalia uma lista ordenada de cálculos (nome → expressão). Cada um vê os anteriores em `c`.
     * [env] traz `s` (configurações), `rt` (tempo real) e os parâmetros da ferramenta; `$` é a resposta.
     */
    fun runAll(defs: List<Pair<String, Node>>, env: Map<String, Any?>, root: JsonElement?): Map<String, Any?> {
        val computed = LinkedHashMap<String, Any?>()
        val ctx = Ctx(HashMap(env).apply { put("$", fromJson(root)); put("c", computed) }, root)
        for ((name, node) in defs) {
            try { computed[name] = eval(node, ctx) }
            catch (e: ComputeError) { throw ComputeError("cálculo '$name': ${e.message}") }
            catch (e: StackOverflowError) { throw ComputeError("cálculo '$name': recursão profunda demais") }
        }
        return computed
    }
}
