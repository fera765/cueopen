package dev.cueopen.app.uiconnector.custom

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.w3c.dom.Element
import org.w3c.dom.Node
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Converte respostas para uma árvore JSON navegável pelos modelos `{{$.…}}`:
 *  - JSON → como veio;
 *  - XML/RSS/Atom → `{"rss": {"channel": {"item": [...]}}}` (atributos em `@nome`, texto misto em `#text`,
 *    elementos repetidos viram lista);
 *  - qualquer outra coisa (HTML, texto) → `{"body": "<conteúdo>"}`.
 * O parser XML recusa DOCTYPE/entidades externas (anti-XXE).
 */
object ResponseTree {
    private val json = Json { isLenient = true }

    fun parse(body: String, contentType: String? = null): JsonElement {
        val t = body.trimStart('﻿', ' ', '\n', '\r', '\t')
        if (t.startsWith("{") || t.startsWith("[")) {
            runCatching { return json.parseToJsonElement(t) }
        }
        val ct = contentType?.lowercase().orEmpty()
        if (t.startsWith("<") && !ct.contains("html")) {
            xml(t)?.let { return it }
        }
        return JsonObject(mapOf("body" to JsonPrimitive(body)))
    }

    fun xml(text: String): JsonElement? = runCatching {
        val f = DocumentBuilderFactory.newInstance()
        runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { f.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { f.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        f.isExpandEntityReferences = false
        f.isNamespaceAware = false
        val doc = f.newDocumentBuilder().parse(text.byteInputStream(Charsets.UTF_8))
        val root = doc.documentElement
        JsonObject(mapOf(root.nodeName to element(root)))
    }.getOrNull()

    private fun element(e: Element): JsonElement {
        val attrs = LinkedHashMap<String, JsonElement>()
        val a = e.attributes
        for (i in 0 until a.length) {
            val n = a.item(i)
            attrs["@" + n.nodeName] = JsonPrimitive(n.nodeValue)
        }
        val children = LinkedHashMap<String, MutableList<JsonElement>>()
        val text = StringBuilder()
        val kids = e.childNodes
        for (i in 0 until kids.length) {
            val k = kids.item(i)
            when (k.nodeType) {
                Node.ELEMENT_NODE -> children.getOrPut(k.nodeName) { ArrayList() }.add(element(k as Element))
                Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> text.append(k.nodeValue)
            }
        }
        val txt = text.toString().trim()
        if (attrs.isEmpty() && children.isEmpty()) return JsonPrimitive(txt)
        val out = LinkedHashMap<String, JsonElement>(attrs)
        children.forEach { (name, list) -> out[name] = if (list.size == 1) list[0] else JsonArray(list) }
        if (txt.isNotEmpty()) out["#text"] = JsonPrimitive(txt)
        return JsonObject(out)
    }
}
