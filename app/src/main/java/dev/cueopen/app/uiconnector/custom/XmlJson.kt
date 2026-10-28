package dev.cueopen.app.uiconnector.custom

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Converte respostas numa árvore JSON navegável pelos modelos `{{$.…}}`:
 *  - JSON → como veio;
 *  - XML/RSS/Atom → `{"rss": {"channel": {"item": [...]}}}` (atributos em `@nome`, texto misto em `#text`,
 *    elementos repetidos viram lista);
 *  - HTML/texto → `{"body": "<conteúdo>"}`.
 * O XML é lido por um parser próprio (puro Kotlin, igual no Android e nos testes): ignora DOCTYPE e não
 * resolve entidades externas (sem XXE), tolera BOM, comentários, CDATA e declarações.
 */
object ResponseTree {
    private val json = Json { isLenient = true }

    fun parse(body: String, contentType: String? = null): JsonElement {
        val t = body.trimStart('﻿', ' ', '\n', '\r', '\t')
        if (t.startsWith("{") || t.startsWith("[")) {
            runCatching { return json.parseToJsonElement(t) }
        }
        val head = t.take(300).lowercase()
        val looksHtml = head.startsWith("<!doctype html") || head.startsWith("<html") || (contentType?.lowercase()?.contains("text/html") == true && !head.contains("<rss") && !head.contains("<feed"))
        if (t.startsWith("<") && !looksHtml) {
            xml(t)?.let { return it }
        }
        return JsonObject(mapOf("body" to JsonPrimitive(body)))
    }

    fun xml(text: String): JsonElement? = runCatching { MiniXml(text).document() }.getOrNull()

    /** Resumo da estrutura (até [depth] níveis) para depurar caminhos `$.…`. */
    fun outline(e: JsonElement?, depth: Int = 4, indent: String = ""): String = buildString {
        when (e) {
            null, JsonNull -> append("null")
            is JsonPrimitive -> append(if (e.isString) "\"" + e.content.take(60).replace("\n", " ") + (if (e.content.length > 60) "…\"" else "\"") else e.content)
            is JsonArray -> {
                append("[lista com ${e.size}]")
                if (depth > 0 && e.isNotEmpty()) { append(" primeiro: "); append(outline(e[0], depth - 1, indent)) }
            }
            is JsonObject -> {
                if (depth <= 0) { append("{" + e.keys.take(8).joinToString() + "}"); return@buildString }
                append("{")
                e.entries.take(12).forEach { (k, v) -> append("\n$indent  $k: "); append(outline(v, depth - 1, "$indent  ")) }
                if (e.size > 12) append("\n$indent  …(+${e.size - 12})")
                append("\n$indent}")
            }
        }
    }
}

private class MiniXml(private val s: String) {
    private var i = 0

    private class Node(val name: String) {
        val attrs = LinkedHashMap<String, String>()
        val children = ArrayList<Node>()
        val text = StringBuilder()
    }

    fun document(): JsonElement {
        skipMisc()
        val root = element()
        return JsonObject(mapOf(root.name to toJson(root)))
    }

    private fun startsWith(p: String) = s.startsWith(p, i)

    private fun skipMisc() {
        while (i < s.length) {
            when {
                s[i].isWhitespace() || s[i] == '﻿' -> i++
                startsWith("<?") -> i = idx("?>") + 2
                startsWith("<!--") -> i = idx("-->") + 3
                startsWith("<!") -> skipDecl()
                else -> return
            }
        }
    }

    private fun idx(p: String): Int {
        val k = s.indexOf(p, i)
        if (k < 0) throw IllegalArgumentException("XML incompleto")
        return k
    }

    private fun skipDecl() { // <!DOCTYPE ... [ ... ]>
        var depth = 0
        while (i < s.length) {
            val ch = s[i++]
            if (ch == '[') depth++
            else if (ch == ']') depth--
            else if (ch == '>' && depth <= 0) return
        }
    }

    private fun name(): String {
        val st = i
        while (i < s.length && !s[i].isWhitespace() && s[i] != '>' && s[i] != '/' && s[i] != '=') i++
        if (i == st) throw IllegalArgumentException("nome XML vazio")
        return s.substring(st, i)
    }

    private fun element(): Node {
        if (!startsWith("<")) throw IllegalArgumentException("esperado <")
        i++
        val n = Node(name())
        while (true) {
            while (i < s.length && s[i].isWhitespace()) i++
            if (i >= s.length) throw IllegalArgumentException("XML incompleto")
            if (startsWith("/>")) { i += 2; return n }
            if (s[i] == '>') { i++; break }
            val an = name()
            while (i < s.length && s[i].isWhitespace()) i++
            var av = ""
            if (i < s.length && s[i] == '=') {
                i++
                while (i < s.length && s[i].isWhitespace()) i++
                val q = s[i]
                if (q == '"' || q == '\'') {
                    val end = s.indexOf(q, i + 1)
                    if (end < 0) throw IllegalArgumentException("atributo sem fim")
                    av = decode(s.substring(i + 1, end)); i = end + 1
                } else av = name()
            }
            n.attrs[an] = av
        }
        // conteúdo
        while (i < s.length) {
            when {
                startsWith("</") -> {
                    i = idx(">") + 1
                    return n
                }
                startsWith("<![CDATA[") -> {
                    val end = s.indexOf("]]>", i)
                    if (end < 0) throw IllegalArgumentException("CDATA sem fim")
                    n.text.append(s, i + 9, end); i = end + 3
                }
                startsWith("<!--") -> i = idx("-->") + 3
                startsWith("<?") -> i = idx("?>") + 2
                startsWith("<!") -> skipDecl()
                s[i] == '<' -> n.children.add(element())
                else -> {
                    val end = s.indexOf('<', i).let { if (it < 0) s.length else it }
                    n.text.append(decode(s.substring(i, end))); i = end
                }
            }
        }
        return n // documento truncado: devolve o que deu para ler
    }

    private fun decode(t: String): String {
        if (!t.contains('&')) return t
        val out = StringBuilder()
        var k = 0
        while (k < t.length) {
            val c = t[k]
            if (c == '&') {
                val semi = t.indexOf(';', k)
                if (semi in (k + 2)..(k + 10)) {
                    val ent = t.substring(k + 1, semi)
                    val rep: String? = when {
                        ent == "amp" -> "&"
                        ent == "lt" -> "<"
                        ent == "gt" -> ">"
                        ent == "quot" -> "\""
                        ent == "apos" -> "'"
                        ent == "nbsp" -> " "
                        ent.startsWith("#x") || ent.startsWith("#X") -> ent.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
                        ent.startsWith("#") -> ent.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) }
                        else -> null
                    }
                    if (rep != null) { out.append(rep); k = semi + 1; continue }
                }
            }
            out.append(c); k++
        }
        return out.toString()
    }

    private fun toJson(n: Node): JsonElement {
        val txt = n.text.toString().trim()
        if (n.attrs.isEmpty() && n.children.isEmpty()) return JsonPrimitive(txt)
        val out = LinkedHashMap<String, JsonElement>()
        n.attrs.forEach { (k, v) -> out["@$k"] = JsonPrimitive(v) }
        n.children.groupBy { it.name }.forEach { (name, list) ->
            out[name] = if (list.size == 1) toJson(list[0]) else JsonArray(list.map { toJson(it) })
        }
        if (txt.isNotEmpty()) out["#text"] = JsonPrimitive(txt)
        return JsonObject(out)
    }
}
