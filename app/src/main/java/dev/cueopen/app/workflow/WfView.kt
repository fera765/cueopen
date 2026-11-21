package dev.cueopen.app.workflow

import kotlinx.serialization.json.*

/**
 * UI kit dos quadros/páginas de notificação. Uma "view" é um JSON {"blocks":[…]} renderizado nativamente.
 *
 * Blocos (campo "type"):
 *  header   {title, subtitle?, image?, icon?}                 · text     {text}  (Markdown)
 *  card     {title, subtitle?, meta?, body?, image?, avatar?, badge?, url?, chips?[], tone?}
 *  list     {title?, items:[card…]}                           · image    {url, caption?}
 *  gallery  {images:[url]}                                    · kv       {title?, items:[{label, value}]}
 *  stats    {items:[{label, value, delta?}]}                  · table    {columns:[…], rows:[[…]]}
 *  chips    {items:[…]}                                       · quote    {text, author?}
 *  alert    {tone: info|ok|warn|err, title?, text}            · button   {label, url? | route?}
 *  section  {title, blocks:[…]}                               · progress {label, value 0..1}
 *  divider  {}
 * Num modelo do usuário, {"type":"auto","data":"{{id}}"} vira os blocos gerados a partir dos dados do nó.
 */
object WfView {
    private val json = Json { ignoreUnknownKeys = true }
    val TYPES = setOf("header", "text", "card", "list", "image", "gallery", "kv", "stats", "table", "chips", "quote", "alert", "button", "section", "progress", "divider")

    fun isView(raw: String?): Boolean {
        val t = raw?.trim() ?: return false
        if (!t.startsWith("{") || !t.contains("\"blocks\"")) return false
        return runCatching { (Json.parseToJsonElement(t) as JsonObject)["blocks"] is JsonArray }.getOrDefault(false)
    }

    private fun JsonElement.s(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.trim()?.takeIf { it.isNotEmpty() }

    fun view(blocks: List<JsonObject>): String = JsonObject(mapOf("blocks" to JsonArray(blocks))).toString()

    fun block(type: String, vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
        put("type", type)
        pairs.forEach { (k, v) ->
            when (v) {
                null -> {}
                is String -> if (v.isNotBlank()) put(k, v)
                is Number -> put(k, v)
                is Boolean -> put(k, v)
                is JsonElement -> put(k, v)
                is List<*> -> put(k, JsonArray(v.mapNotNull { x -> when (x) { is JsonElement -> x; null -> null; else -> JsonPrimitive(x.toString()) } }))
                else -> put(k, v.toString())
            }
        }
    }

    // ───────── Geração automática a partir de dados ─────────

    private fun card(o: JsonObject, mail: Boolean, withBody: Boolean): JsonObject {
        val title = WfFormat.titleOf(o)?.let { WfFormat.cleanText(it, 140) }
        val from = WfFormat.fromOf(o)?.let { WfFormat.cleanText(it, 80) }
        val date = WfFormat.dateOf(o)
        val body = if (withBody) WfFormat.bodyOf(o, 320)?.takeIf { it != title } else null
        val atts = WfFormat.attachmentsOf(o)
        if (title == null && from == null) {
            val pairs = o.entries.filter { !WfFormat.isTech(it.key) }.mapNotNull { (k, v) -> v.s()?.let { WfFormat.keyLabel(k) to WfFormat.cleanText(it, 120) } }.take(5)
            return block("card", "title" to (pairs.firstOrNull()?.second ?: "Item"),
                "body" to pairs.drop(1).joinToString("\n") { "${it.first}: ${it.second}" }, "image" to WfFormat.imageOf(o), "url" to WfFormat.urlOf(o))
        }
        return if (mail) block("card", "title" to (title ?: "(sem assunto)"), "subtitle" to from, "meta" to date, "avatar" to (from ?: "?"),
            "body" to body, "image" to WfFormat.imageOf(o), "url" to WfFormat.urlOf(o), "chips" to atts.take(4).map { "📎 $it" })
        else block("card", "title" to (title ?: from ?: "Item"), "subtitle" to (if (title != null) from else null), "meta" to date,
            "body" to body, "image" to WfFormat.imageOf(o), "url" to WfFormat.urlOf(o), "chips" to atts.take(4).map { "📎 $it" })
    }

    /** Lista de objetos "planos" e homogêneos (sem título) fica melhor em tabela. */
    private fun asTable(list: List<JsonObject>): JsonObject? {
        if (list.size < 2 || list.any { WfFormat.titleOf(it) != null }) return null
        val keys = list.first().keys.filter { !WfFormat.isTech(it) }.take(6)
        if (keys.size < 2 || list.any { o -> keys.any { k -> o[k] != null && o[k] !is JsonPrimitive } }) return null
        return block("table", "columns" to keys.map { WfFormat.keyLabel(it) },
            "rows" to list.take(50).map { o -> JsonArray(keys.map { k -> JsonPrimitive(o[k]?.s()?.let { WfFormat.cleanText(it, 60) } ?: "") }) })
    }

    private fun objectBlocks(o: JsonObject, depth: Int = 0): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        val kv = mutableListOf<JsonObject>()
        o.entries.filter { !WfFormat.isTech(it.key) && it.value !is JsonNull }.take(30).forEach { (k, v) ->
            when (v) {
                is JsonPrimitive -> v.s()?.let { kv += buildJsonObject { put("label", WfFormat.keyLabel(k)); put("value", WfFormat.date(it)?.takeIf { _ -> Regex("^\\d{4}-\\d{2}-\\d{2}T").containsMatchIn(it) } ?: WfFormat.cleanText(it, 500)) } }
                is JsonArray -> if (v.all { it is JsonPrimitive }) v.mapNotNull { it.s() }.takeIf { it.isNotEmpty() }?.let { out += block("section", "title" to WfFormat.keyLabel(k), "blocks" to listOf(block("chips", "items" to it.take(20)))) }
                    else if (depth < 2) out += block("section", "title" to WfFormat.keyLabel(k), "blocks" to listBlocks(v.filterIsInstance<JsonObject>(), v.size))
                is JsonObject -> if (depth < 2) objectBlocks(v, depth + 1).takeIf { it.isNotEmpty() }?.let { out += block("section", "title" to WfFormat.keyLabel(k), "blocks" to it) }
            }
        }
        return (if (kv.isNotEmpty()) listOf(block("kv", "items" to kv)) else emptyList()) + out
    }

    private fun listBlocks(items: List<JsonObject>, total: Int, max: Int = 30): List<JsonObject> {
        asTable(items)?.let { return listOf(it) }
        val mail = items.firstOrNull()?.let { WfFormat.isMail(it) } == true
        val shown = items.take(max)
        return listOf(block("list", "items" to shown.map { card(it, mail, true) })) +
            (if (total > max) listOf(block("text", "text" to "… e mais ${total - max}")) else emptyList())
    }

    /** Blocos a partir de qualquer saída de nó (JSON de conector, HTTP, texto). */
    fun autoBlocks(raw: String): List<JsonObject> {
        if (isView(raw)) return blocksOf(raw)
        val root = WfFormat.parseJson(raw) ?: return raw.trim().takeIf { it.isNotEmpty() }?.let { listOf(block("text", "text" to it)) } ?: emptyList()
        val e = WfFormat.unwrapData(root)
        if (e is JsonPrimitive) return e.s()?.let { autoBlocks(it) } ?: emptyList()
        WfFormat.listIn(e)?.let { list -> return listBlocks(list.filterIsInstance<JsonObject>(), list.size) }
        return when (e) {
            is JsonObject -> objectBlocks(e)
            is JsonArray -> listOf(block("chips", "items" to e.mapNotNull { it.s() }.take(30)))
            else -> emptyList()
        }
    }

    /** Resumo numérico para o topo: "10 e-mails", "5 itens". */
    fun countLabel(raw: String): String? {
        val root = WfFormat.parseJson(raw) ?: return null
        val list = WfFormat.listIn(WfFormat.unwrapData(root)) ?: return null
        val mail = (list.firstOrNull() as? JsonObject)?.let { WfFormat.isMail(it) } == true
        return "${list.size} " + if (mail) (if (list.size == 1) "e-mail" else "e-mails") else (if (list.size == 1) "item" else "itens")
    }

    fun blocksOf(viewJson: String): List<JsonObject> = runCatching {
        ((Json.parseToJsonElement(viewJson) as JsonObject)["blocks"] as JsonArray).filterIsInstance<JsonObject>()
    }.getOrDefault(emptyList())

    /** View padrão da notificação: cabeçalho + texto (opcional) + blocos dos dados. */
    fun build(title: String, subtitle: String, markdown: String?, data: String?, time: String): String {
        val blocks = mutableListOf<JsonObject>()
        val count = data?.let { countLabel(it) }
        blocks += block("header", "title" to title, "subtitle" to listOfNotNull(subtitle.takeIf { it.isNotBlank() && it != title }, count?.let { "$it · $time" } ?: time).joinToString("\n"))
        markdown?.takeIf { it.isNotBlank() }?.let { blocks += block("text", "text" to it) }
        data?.let { blocks += autoBlocks(it) }
        return view(blocks)
    }

    /**
     * Modelo do usuário/IA: strings passam por [render] (com {{…}} legíveis); {"type":"auto","data":"{{id}}"} e
     * {"type":"list","data":"{{id}}"} viram blocos gerados a partir dos dados brutos ([raw]).
     */
    fun fromTemplate(template: String, render: (String) -> String, raw: (String) -> String): String {
        val root = runCatching { Json.parseToJsonElement(template) as JsonObject }.getOrNull() ?: return view(listOf(block("text", "text" to render(template))))
        fun walk(e: JsonElement): JsonElement = when (e) {
            is JsonPrimitive -> if (e.isString) JsonPrimitive(render(e.content)) else e
            is JsonArray -> JsonArray(e.flatMap { x ->
                val o = x as? JsonObject
                val t = o?.get("type")?.s()
                val data = o?.get("data")?.s()
                if (o != null && data != null && (t == "auto" || t == "list")) autoBlocks(raw(data)) else listOf(walk(x))
            })
            is JsonObject -> JsonObject(e.mapValues { (k, v) -> if (k == "type") v else walk(v) })
        }
        return JsonObject(mapOf("blocks" to walk(root["blocks"] ?: JsonArray(emptyList())))).toString()
    }

    /** Texto simples da view (para busca, notificação do sistema e Markdown de reserva). */
    fun plainText(viewJson: String): String = blocksOf(viewJson).joinToString("\n") { b ->
        listOfNotNull(b["title"]?.s(), b["subtitle"]?.s(), b["text"]?.s(), b["body"]?.s()).joinToString(" · ") +
            ((b["items"] as? JsonArray)?.joinToString("\n") { i -> (i as? JsonObject)?.let { o -> listOfNotNull(o["title"]?.s(), o["subtitle"]?.s(), o["label"]?.s(), o["value"]?.s()).joinToString(" · ") } ?: i.s().orEmpty() }?.let { "\n$it" } ?: "")
    }.trim()
}
