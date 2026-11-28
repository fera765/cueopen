package dev.fluwy.app.uiconnector.custom

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Subconjunto seguro de JSONPath (puro Kotlin): `$`, `.nome`, `['nome']`, `[n]`, `[-1]`, `[*]`, `.*`.
 * Sem filtros nem scripts — só navegação, para que definições de terceiros não executem nada.
 */
object JsonPath {
    sealed interface Tok {
        data class Key(val k: String) : Tok
        data class Idx(val i: Int) : Tok
        object All : Tok
        /** `[*]`: itens de uma lista; num objeto único (ex.: RSS com 1 item) devolve o próprio objeto. */
        object Items : Tok
    }

    fun parse(path: String): List<Tok>? {
        val p = path.trim()
        if (!p.startsWith("$")) return null
        val out = ArrayList<Tok>()
        var i = 1
        while (i < p.length) {
            when (p[i]) {
                '.' -> {
                    i++
                    if (i < p.length && p[i] == '*') {
                        out.add(Tok.All)
                        i++
                    } else {
                        val st = i
                        while (i < p.length && p[i] != '.' && p[i] != '[') i++
                        if (i == st) return null
                        out.add(Tok.Key(p.substring(st, i)))
                    }
                }
                '[' -> {
                    val end = p.indexOf(']', i)
                    if (end < 0) return null
                    val inner = p.substring(i + 1, end).trim()
                    i = end + 1
                    if (inner == "*") {
                        out.add(Tok.Items)
                    } else if (inner.length >= 2 && (inner[0] == '\'' || inner[0] == '"') && inner.last() == inner[0]) {
                        out.add(Tok.Key(inner.substring(1, inner.length - 1)))
                    } else {
                        val n = inner.toIntOrNull() ?: return null
                        out.add(Tok.Idx(n))
                    }
                }
                else -> return null
            }
        }
        return out
    }

    fun isValid(path: String): Boolean = parse(path) != null

    fun select(root: JsonElement, path: String): List<JsonElement> {
        val toks = parse(path) ?: return emptyList()
        var cur: List<JsonElement> = listOf(root)
        for (t in toks) {
            val next = ArrayList<JsonElement>()
            for (e in cur) {
                when (t) {
                    is Tok.Key -> {
                        val v = (e as? JsonObject)?.get(t.k)
                        if (v != null) next.add(v)
                    }
                    is Tok.Idx -> {
                        if (e is JsonArray) {
                            val ix = if (t.i < 0) e.size + t.i else t.i
                            if (ix >= 0 && ix < e.size) next.add(e[ix])
                        }
                    }
                    Tok.Items -> {
                        if (e is JsonArray) next.addAll(e) else if (e is JsonObject) next.add(e)
                    }
                    Tok.All -> {
                        if (e is JsonArray) next.addAll(e)
                        else if (e is JsonObject) next.addAll(e.values)
                    }
                }
            }
            cur = next
        }
        return cur
    }

    fun first(root: JsonElement, path: String): JsonElement? = select(root, path).firstOrNull()

    /** Texto de um valor: primitivos sem aspas; null → ""; objeto/array → JSON compacto. */
    fun text(e: JsonElement?): String = when (e) {
        null, JsonNull -> ""
        is JsonPrimitive -> e.content
        else -> e.toString()
    }
}
