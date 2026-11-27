package dev.fluwy.app.workflow

import kotlinx.serialization.json.*

/**
 * Transforma saídas técnicas (JSON de conectores, HTTP, etc.) em texto claro para pessoas: listas de e-mails viram
 * "assunto · remetente · data", objetos viram "Campo: valor" sem ids/cabeçalhos/metadados. Usado em notificações,
 * histórico e testes; os dados brutos só aparecem quando o usuário pede.
 */
object WfFormat {
    private val TECH = setOf(
        "id", "_id", "threadid", "thread_id", "historyid", "labelids", "label_ids", "headers", "raw", "version", "duration_ms", "durationms",
        "internaldate", "sizeestimate", "etag", "kind", "mimetype", "payload", "parts", "attachments", "body_html", "html", "bodyhtml",
        "connector", "action", "note", "found", "status", "trigger", "workflow", "nextpagetoken", "resultsizeestimate", "cursor", "token",
        "messageid", "message_id", "in_reply_to", "references", "reply_to", "x_gm_thrid", "uid", "flags", "selfLink", "self_link"
    ).map { it.lowercase() }.toSet()
    private val TITLE_KEYS = listOf("subject", "title", "name", "nome", "titulo", "label", "assunto", "headline", "text")
    private val FROM_KEYS = listOf("from", "sender", "de", "author", "remetente")
    private val DATE_KEYS = listOf("date", "data", "created_at", "createdAt", "time", "datetime", "timestamp")
    private val BODY_KEYS = listOf("snippet", "body_text", "bodyText", "text_body", "text", "body", "plain", "content", "description", "summary", "resumo")

    private fun parse(raw: String): JsonElement? = raw.trim().takeIf { it.startsWith("{") || it.startsWith("[") }?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }

    fun isJson(raw: String) = parse(raw)?.let { it is JsonObject || it is JsonArray } == true

    private fun JsonElement.str(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.field(keys: List<String>): String? {
        for (k in keys) {
            entries.firstOrNull { it.key.equals(k, true) }?.value?.let { v ->
                v.str()?.let { return it }
                // {"from": {"name": .., "address": ..}} / [{"address": ..}]
                (v as? JsonObject)?.let { o -> listOfNotNull(o["name"]?.str(), o["address"]?.str() ?: o["email"]?.str()).joinToString(" ").takeIf { it.isNotBlank() }?.let { return it } }
                (v as? JsonArray)?.mapNotNull { it.str() ?: (it as? JsonObject)?.let { o -> o["address"]?.str() ?: o["email"]?.str() ?: o["name"]?.str() } }
                    ?.takeIf { it.isNotEmpty() }?.let { return it.joinToString(", ") }
            }
        }
        (this["headers"] as? JsonObject)?.let { h -> for (k in keys) h.entries.firstOrNull { it.key.equals(k, true) }?.value?.str()?.let { return it } }
        return null
    }

    /** "\"Ana Souza\" <ana@x.com>" → "Ana Souza"; objetos {name,address}/{value:[…]}/{text} também. */
    internal fun person(v: JsonElement?): String? {
        fun fromText(t: String): String? {
            val s0 = t.trim()
            val m = Regex("^\\s*\"?([^\"<]*?)\"?\\s*<([^>]+)>").find(s0)
            val out = if (m != null) m.groupValues[1].trim().ifBlank { m.groupValues[2].trim() } else s0.trim('"', ' ')
            return out.takeIf { it.isNotBlank() }
        }
        return when (v) {
            null, JsonNull -> null
            is JsonPrimitive -> v.contentOrNull?.let { fromText(it) }
            is JsonArray -> v.mapNotNull { person(it) }.takeIf { it.isNotEmpty() }?.joinToString(", ")
            is JsonObject -> (v["value"] as? JsonArray)?.let { person(it) }
                ?: v["name"]?.str()?.let { fromText(it) } ?: v["address"]?.str() ?: v["email"]?.str() ?: v["text"]?.str()?.let { fromText(it) }
        }
    }

    private val ptBR = java.util.Locale("pt", "BR")

    /** ISO, RFC 2822 (e-mail) ou epoch → "hoje 21:24" / "ontem 08:10" / "21/11 00:24" / "21/11/2025". */
    internal fun date(raw: String?): String? {
        val t = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val zone = java.time.ZoneId.systemDefault()
        val inst: java.time.Instant? = runCatching { java.time.Instant.parse(t) }.getOrNull()
            ?: runCatching { java.time.OffsetDateTime.parse(t).toInstant() }.getOrNull()
            ?: runCatching { java.time.ZonedDateTime.parse(t.replace(Regex("\\s*\\([^)]*\\)$"), ""), java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull()
            ?: t.toLongOrNull()?.let { n -> if (n > 100_000_000_000L) java.time.Instant.ofEpochMilli(n) else if (n > 1_000_000_000L) java.time.Instant.ofEpochSecond(n) else null }
            ?: runCatching { java.time.LocalDate.parse(t.take(10)).atStartOfDay(zone).toInstant() }.getOrNull()
        inst ?: return t.take(40)
        val d = inst.atZone(zone)
        val today = java.time.LocalDate.now(zone)
        val hm = java.time.format.DateTimeFormatter.ofPattern("HH:mm", ptBR).format(d)
        return when (d.toLocalDate()) {
            today -> "hoje $hm"
            today.minusDays(1) -> "ontem $hm"
            else -> if (d.year == today.year) java.time.format.DateTimeFormatter.ofPattern("dd/MM HH:mm", ptBR).format(d)
                else java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy", ptBR).format(d)
        }
    }

    internal fun JsonObject.raw(keys: List<String>): JsonElement? {
        for (k in keys) entries.firstOrNull { it.key.equals(k, true) }?.value?.takeIf { it !is JsonNull }?.let { return it }
        (this["headers"] as? JsonObject)?.let { h -> for (k in keys) h.entries.firstOrNull { it.key.equals(k, true) }?.value?.takeIf { it !is JsonNull }?.let { return it } }
        return null
    }

    private fun isHttp(s: String?) = s != null && Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(s)
    private val IMG_KEYS = listOf("image", "imageUrl", "image_url", "urlToImage", "thumbnail", "thumbnailUrl", "thumb", "picture", "photo", "cover", "avatar", "icon", "logo", "media")
    private val URL_KEYS = listOf("url", "link", "href", "webLink", "web_link", "permalink", "htmlUrl", "html_url", "webViewLink")

    /** Primeira imagem do item: campos de imagem ou anexos de imagem. */
    internal fun imageOf(o: JsonObject): String? {
        o.raw(IMG_KEYS)?.let { v ->
            (v.str() ?: (v as? JsonObject)?.let { it["url"]?.str() ?: it["src"]?.str() } ?: (v as? JsonArray)?.firstOrNull()?.let { it.str() ?: (it as? JsonObject)?.get("url")?.str() })
                ?.takeIf { isHttp(it) }?.let { return it }
        }
        (o["attachments"] as? JsonArray)?.filterIsInstance<JsonObject>()?.firstOrNull { (it["mimeType"]?.str() ?: it["contentType"]?.str()).orEmpty().startsWith("image/") }
            ?.let { a -> (a["data"]?.str() ?: a["url"]?.str())?.takeIf { isHttp(it) }?.let { return it } }
        return null
    }

    internal fun urlOf(o: JsonObject): String? = o.raw(URL_KEYS)?.str()?.takeIf { isHttp(it) }

    /** Nomes dos anexos que não são imagem. */
    internal fun attachmentsOf(o: JsonObject): List<String> = (o["attachments"] as? JsonArray)?.filterIsInstance<JsonObject>()
        ?.filterNot { (it["mimeType"]?.str() ?: it["contentType"]?.str()).orEmpty().startsWith("image/") }
        ?.mapNotNull { it["fileName"]?.str() ?: it["filename"]?.str() ?: it["name"]?.str() }.orEmpty()

    /** Texto vindo de fora não pode quebrar o Markdown (ex.: "** Delivery incomplete **"). */
    internal fun esc(s: String) = s.replace(Regex("([*`\\[\\]])"), "\\\\$1")

    internal fun titleOf(o: JsonObject) = o.field(TITLE_KEYS.filter { it != "text" }) ?: o.field(listOf("text"))
    internal fun fromOf(o: JsonObject) = person(o.raw(FROM_KEYS))
    internal fun dateOf(o: JsonObject) = date(o.field(DATE_KEYS))
    internal fun bodyOf(o: JsonObject, max: Int) = o.field(BODY_KEYS)?.let { clean(it, max) }
    internal fun isMail(o: JsonObject) = o.raw(FROM_KEYS) != null && o.field(listOf("subject", "assunto")) != null
    internal fun unwrapData(e: JsonElement) = unwrap(e)
    internal fun listIn(e: JsonElement) = findList(e)
    internal fun parseJson(raw: String) = parse(raw)
    internal fun cleanText(s: String, max: Int) = clean(s, max)
    internal fun keyLabel(k: String) = humanKey(k)
    internal fun isTech(k: String) = k.lowercase() in TECH

    /** Tira o envelope do nó (conector/HTTP/notificação) e devolve o que interessa. */
    private fun unwrap(e: JsonElement): JsonElement {
        val o = e as? JsonObject ?: return e
        if (o.containsKey("data") && (o.containsKey("connector") || o.containsKey("status"))) {
            val d = o["data"]!!
            if (d is JsonPrimitive && d.isString) return parse(d.content) ?: d
            if (d !is JsonNull) return d
        }
        if (o.containsKey("body") && o.containsKey("status") && o.size <= 4) return o["text"] ?: o["body"]!!
        if (o.keys == setOf("title", "text") || o.keys == setOf("title", "text", "detail")) return o["detail"] ?: o["text"]!!
        return o
    }

    /** Lista de itens (e-mails, linhas, resultados) mais provável dentro do JSON. */
    private fun findList(e: JsonElement, depth: Int = 0): JsonArray? {
        if (depth > 4) return null
        if (e is JsonArray && e.isNotEmpty() && e.all { it is JsonObject }) return e
        val o = e as? JsonObject ?: return null
        val pref = listOf("messages", "emails", "items", "results", "rows", "records", "data", "values", "entries", "list")
        for (k in pref) o[k]?.let { v -> findList(v, depth + 1)?.let { return it } }
        for ((_, v) in o) findList(v, depth + 1)?.let { return it }
        return null
    }

    private fun clean(s: String, max: Int): String = s.replace(Regex("<[^>]+>"), " ").replace(Regex("[\\r\\t ]+"), " ").replace(Regex("\\n{3,}"), "\n\n").trim()
        .let { if (it.length > max) it.take(max).trimEnd() + "…" else it }

    private fun humanKey(k: String) = k.replace(Regex("([a-z])([A-Z])"), "$1 $2").replace('_', ' ').replace('-', ' ').trim()
        .replaceFirstChar { it.uppercase() }

    private fun item(o: JsonObject, n: Int, withBody: Boolean): String {
        val title = titleOf(o)?.let { clean(it, 120) }
        val from = fromOf(o)
        val date = dateOf(o)
        val body = if (withBody) bodyOf(o, 220)?.takeIf { it != title } else null
        if (title == null && from == null) {
            val pairs = o.entries.filter { it.key.lowercase() !in TECH }.mapNotNull { (k, v) -> v.str()?.let { "${humanKey(k)}: ${esc(clean(it, 80))}" } }.take(4)
            return "$n. " + pairs.joinToString(" · ").ifBlank { "(item)" }
        }
        return buildString {
            append("$n. **").append(esc(title ?: "(sem assunto)")).append("**")
            val meta = listOfNotNull(from?.let { esc(clean(it, 80)) }, date).joinToString(" · ")
            if (meta.isNotBlank()) append("  \n   ").append(meta)
            if (!body.isNullOrBlank()) append("  \n   ").append(esc(body.replace("\n", " ")))
        }
    }

    private fun obj(o: JsonObject, depth: Int): String = o.entries.filter { it.key.lowercase() !in TECH && it.value !is JsonNull }.mapNotNull { (k, v) ->
        when (v) {
            is JsonPrimitive -> v.str()?.let { "**${humanKey(k)}:** ${esc(clean(it, 400))}" }
            is JsonArray -> if (v.all { it is JsonPrimitive }) v.mapNotNull { it.str() }.takeIf { it.isNotEmpty() }?.let { "**${humanKey(k)}:** ${it.take(15).joinToString(", ")}" }
                else if (depth < 2) "**${humanKey(k)}:**\n" + v.filterIsInstance<JsonObject>().take(15).mapIndexed { i, it -> item(it, i + 1, false) }.joinToString("\n") else null
            is JsonObject -> if (depth < 2) obj(v, depth + 1).takeIf { it.isNotBlank() }?.let { "**${humanKey(k)}**\n$it" } else null
        }
    }.take(25).joinToString("\n")

    /** Texto claro (Markdown simples) de qualquer saída. Texto que não é JSON volta como está. */
    fun readable(raw: String, maxItems: Int = 20): String {
        val root = parse(raw) ?: return raw.trim()
        val e = unwrap(root)
        if (e is JsonPrimitive) return e.str()?.let { parse(it)?.let { p -> readable(p.toString(), maxItems) } ?: it } ?: ""
        val list = findList(e)
        if (list != null) {
            val shown = list.take(maxItems).filterIsInstance<JsonObject>()
            return shown.mapIndexed { i, o -> item(o, i + 1, shown.size <= 10) }.joinToString("\n\n") +
                (if (list.size > maxItems) "\n\n… e mais ${list.size - maxItems}" else "")
        }
        return when (e) {
            is JsonObject -> obj(e, 0).ifBlank { "(sem dados para mostrar)" }
            is JsonArray -> e.mapNotNull { it.str() }.joinToString("\n") { "• $it" }.ifBlank { "(lista vazia)" }
            else -> raw.trim()
        }
    }

    /** Uma linha curta para títulos, cartões e notificações. */
    fun oneLine(raw: String): String {
        val root = parse(raw) ?: return clean(raw, 140).lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        val e = unwrap(root)
        if (e is JsonPrimitive) return clean(e.str().orEmpty(), 140)
        findList(e)?.let { list ->
            val first = (list.firstOrNull() as? JsonObject)?.field(TITLE_KEYS)?.let { clean(it, 80) }
            val mail = (list.firstOrNull() as? JsonObject)?.let { it.field(FROM_KEYS) != null && it.field(listOf("subject", "assunto")) != null } == true
            val noun = if (mail) (if (list.size == 1) "e-mail" else "e-mails") else (if (list.size == 1) "item" else "itens")
            return "${list.size} $noun" + (first?.let { " · $it" } ?: "")
        }
        return clean(readable(raw).replace("**", "").lineSequence().firstOrNull { it.isNotBlank() }.orEmpty(), 140)
    }
}
