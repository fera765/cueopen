package dev.cueopen.app.workflow

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Calendar
import java.util.TimeZone

/** Regras puras do motor (sem Android): validação, templates, condições e próximo horário. Testáveis na JVM. */
object WfLogic {

    // ───────── Validação ─────────
    fun problems(w: Workflow): List<String> {
        val out = mutableListOf<String>()
        val ids = w.nodes.map { it.id }.toSet()
        val triggers = w.nodes.filter { WfCatalog.of(it.type).trigger }
        if (w.nodes.isEmpty()) out += "O fluxo está vazio: adicione um gatilho."
        else if (triggers.isEmpty()) out += "Falta um gatilho (Manual ou Agendamento)."
        w.edges.filter { it.from !in ids || it.to !in ids }.forEach { out += "Ligação aponta para um nó que não existe mais." }
        w.edges.filter { e -> w.nodes.firstOrNull { it.id == e.to }?.let { WfCatalog.of(it.type).trigger } == true }
            .forEach { out += "Um gatilho não pode receber ligação de entrada." }
        w.nodes.forEach { n ->
            val t = WfCatalog.of(n.type)
            t.fields.filter { it.required && n.config[it.key].isNullOrBlank() }.forEach { f -> out += "${label(n)}: preencha \"${f.label}\"." }
            t.fields.filter { it.kind == WfField.Kind.JSON }.forEach { f ->
                val v = n.config[f.key].orEmpty().trim()
                // Só valida JSON sem templates (com {{…}} o texto final só existe na execução).
                if (v.isNotEmpty() && !v.contains("{{") && runCatching { Json.parseToJsonElement(v) }.isFailure) out += "${label(n)}: \"${f.label}\" não é um JSON válido."
            }
            // {{x}} / {{x.campo}} precisa apontar para um nó que existe (senão vira texto vazio na execução).
            val byLabel = w.nodes.associate { label(it).lowercase() to it.id }
            n.config.values.flatMap { v -> TOKEN.findAll(v).map { it.groupValues[1].trim() }.toList() }.distinct().forEach { expr ->
                val head = expr.substringBefore('.')
                if (head == "prev" || head == "trigger" || head == "workflow") return@forEach
                val target = if (head in ids) head else byLabel[head.lowercase()]
                if (target == null) out += "${label(n)}: {{$expr}} não aponta para nenhum nó (ids: ${w.nodes.filter { it.id != n.id }.joinToString { it.id }})."
                else if (target == n.id) out += "${label(n)}: {{$expr}} usa a saída do próprio nó."
            }
            if (n.type == "trigger_schedule") {
                val mode = n.config["mode"] ?: "daily"
                if (mode == "interval" && (n.config["minutes"]?.toIntOrNull() ?: 0) < 1) out += "${label(n)}: intervalo mínimo é 1 minuto."
                if (mode != "interval" && parseTime(n.config["time"] ?: "09:00") == null) out += "${label(n)}: horário inválido (use HH:mm)."
            }
            if (!t.trigger && w.edges.none { it.to == n.id }) out += "${label(n)} não está ligado a nada (não vai rodar)."
        }
        return out.distinct()
    }

    fun label(n: WfNode) = n.label.ifBlank { WfCatalog.of(n.type).title }

    // ───────── Templates {{…}} ─────────
    private val TOKEN = Regex("""\{\{\s*([^\{\}]+?)\s*\}\}""")

    /**
     * Troca {{prev}}, {{trigger.time}}, {{<id>}}, {{<id>.campo.sub}} e {{<rótulo do nó>}} pelos valores da execução.
     * Saída que é JSON vira objeto navegável; texto puro só existe inteiro. Referência desconhecida vira texto vazio.
     */
    fun render(template: String, outputs: Map<String, String>, labels: Map<String, String>, prev: String, vars: Map<String, String> = emptyMap(),
               humanize: ((String) -> String)? = null): String =
        if (humanize != null) TOKEN.replace(template) { m ->
            val v = render(m.value, outputs, labels, prev, vars)
            if (WfFormat.isJson(v)) humanize(v) else v
        } else TOKEN.replace(template) { m ->
            val expr = m.groupValues[1]
            val head = expr.substringBefore('.')
            val path = if (expr.contains('.')) expr.substringAfter('.') else ""
            val base: String? = when {
                head == "prev" -> prev
                vars.containsKey(expr) -> return@replace vars.getValue(expr)
                outputs.containsKey(head) -> outputs[head]
                else -> labels.entries.firstOrNull { it.value.equals(head, ignoreCase = true) }?.key?.let { outputs[it] }
            }
            if (base == null) "" else if (path.isEmpty()) base else pick(base, path)
        }

    /** Navega num JSON por "a.b.0.c"; texto que não é JSON não tem campos. */
    fun pick(raw: String, path: String): String {
        var cur: JsonElement = runCatching { Json.parseToJsonElement(raw) }.getOrNull() ?: return ""
        for (part in path.split('.')) {
            cur = when (val c = cur) {
                is JsonObject -> c[part] ?: return ""
                is JsonArray -> part.toIntOrNull()?.let { c.getOrNull(it) } ?: return ""
                else -> return ""
            }
        }
        return when (val c = cur) {
            is JsonPrimitive -> if (c is JsonNull) "" else c.content
            else -> c.toString()
        }
    }

    /** Texto legível de uma página HTML (sem scripts, estilos e tags), para resumir com IA. */
    fun htmlText(html: String): String = html
        .replace(Regex("(?is)<(script|style|noscript|svg|head)[^>]*>.*?</\\1>"), " ")
        .replace(Regex("(?i)<br\\s*/?>|</(p|div|li|h[1-6]|tr)>"), "\n")
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        .replace(Regex("[ \\t\\x0B\\f\\r]+"), " ").replace(Regex("\\s*\\n\\s*"), "\n").replace(Regex("\\n{3,}"), "\n\n").trim()

    /** Caminhos utilizáveis em {{id.caminho}} de uma saída JSON (até 3 níveis, 40 itens). */
    fun paths(raw: String): List<String> {
        val root = runCatching { Json.parseToJsonElement(raw) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<String>()
        fun walk(e: JsonElement, p: String, depth: Int) {
            if (out.size >= 40) return
            when (e) {
                is JsonObject -> e.forEach { (k, v) -> val np = if (p.isEmpty()) k else "$p.$k"; out += np; if (depth < 3) walk(v, np, depth + 1) }
                is JsonArray -> e.firstOrNull()?.let { v -> val np = if (p.isEmpty()) "0" else "$p.0"; out += np; if (depth < 3) walk(v, np, depth + 1) }
                else -> {}
            }
        }
        walk(root, "", 0)
        return out.distinct()
    }

    /** Organiza os nós em colunas a partir dos gatilhos (esquerda → direita), alinhados à grade do canvas. */
    fun layout(w: Workflow, nodeW: Float = 212f, nodeH: Float = 84f): Workflow {
        val layers = mutableMapOf<String, Int>()
        val q = ArrayDeque(w.nodes.filter { WfCatalog.of(it.type).trigger }.map { it.id }.onEach { layers[it] = 0 })
        while (q.isNotEmpty()) { val cur = q.removeFirst(); w.edges.filter { it.from == cur }.forEach { e -> if (e.to !in layers) { layers[e.to] = layers.getValue(cur) + 1; q.add(e.to) } } }
        val extra = (layers.values.maxOrNull() ?: 0) + 1
        val byLayer = w.nodes.groupBy { layers[it.id] ?: extra }
        return w.copy(nodes = w.nodes.map { n ->
            val l = layers[n.id] ?: extra
            val col = byLayer.getValue(l)
            val i = col.indexOf(n)
            val top = 200f - (col.size - 1) * (nodeH + 60f) / 2
            n.copy(x = 40f + l * (nodeW + 80f), y = (Math.round((top + i * (nodeH + 60f)).coerceAtLeast(20f) / 20f) * 20f).toFloat())
        })
    }

    /** Assinatura do conteúdo (nós + ligações) para saber se o teste ainda vale. */
    fun signature(w: Workflow): String = (w.nodes.sortedBy { it.id }.joinToString("|") { it.id + it.type + it.config.toSortedMap() } +
        "#" + w.edges.sortedBy { it.from + it.to + it.port }.joinToString("|") { it.from + ">" + it.to + ":" + it.port }).hashCode().toString()

    // ───────── Condição ─────────
    fun condition(left: String, op: String, right: String): Boolean {
        val l = left.trim(); val r = right.trim()
        val ln = l.replace(',', '.').toDoubleOrNull(); val rn = r.replace(',', '.').toDoubleOrNull()
        return when (op) {
            "contains" -> l.contains(r, ignoreCase = true)
            "not_contains" -> !l.contains(r, ignoreCase = true)
            "equals" -> if (ln != null && rn != null) ln == rn else l.equals(r, ignoreCase = true)
            "not_equals" -> if (ln != null && rn != null) ln != rn else !l.equals(r, ignoreCase = true)
            "gt" -> if (ln != null && rn != null) ln > rn else l > r
            "lt" -> if (ln != null && rn != null) ln < rn else l < r
            "empty" -> l.isEmpty()
            "not_empty" -> l.isNotEmpty()
            "regex" -> runCatching { Regex(r).containsMatchIn(l) }.getOrDefault(false)
            else -> false
        }
    }

    // ───────── Agendamento ─────────
    fun parseTime(s: String): Pair<Int, Int>? {
        val m = Regex("""^\s*(\d{1,2}):(\d{2})\s*$""").find(s) ?: return null
        val h = m.groupValues[1].toInt(); val min = m.groupValues[2].toInt()
        return if (h in 0..23 && min in 0..59) h to min else null
    }

    /** Próximo disparo (epoch ms) depois de [now] para a config do gatilho de Agendamento. null = config inválida. */
    fun nextFire(config: Map<String, String>, now: Long, zone: TimeZone = TimeZone.getDefault()): Long? {
        return when (config["mode"] ?: "daily") {
            "interval" -> {
                val min = config["minutes"]?.toIntOrNull()?.takeIf { it >= 1 } ?: return null
                now + min * 60_000L
            }
            "daily", "weekly" -> {
                val (h, m) = parseTime(config["time"] ?: "09:00") ?: return null
                val cal = Calendar.getInstance(zone).apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
                if (config["mode"] == "weekly") {
                    // 1 = segunda … 7 = domingo → Calendar (domingo = 1).
                    val wd = (config["weekday"]?.toIntOrNull() ?: 1).coerceIn(1, 7)
                    val target = if (wd == 7) Calendar.SUNDAY else wd + 1
                    while (cal.get(Calendar.DAY_OF_WEEK) != target || cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_MONTH, 1)
                } else if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_MONTH, 1)
                cal.timeInMillis
            }
            else -> null
        }
    }

    /** Resumo legível do agendamento ("Todo dia às 09:00"). */
    fun scheduleLabel(config: Map<String, String>): String = when (config["mode"] ?: "daily") {
        "interval" -> "A cada ${config["minutes"] ?: "?"} min"
        "weekly" -> "Toda ${listOf("segunda", "terça", "quarta", "quinta", "sexta", "sábado", "domingo").getOrElse(((config["weekday"]?.toIntOrNull() ?: 1) - 1).coerceIn(0, 6)) { "segunda" }} às ${config["time"] ?: "09:00"}"
        else -> "Todo dia às ${config["time"] ?: "09:00"}"
    }

    /** Ordem de execução a partir do gatilho: cada nó roda uma vez; [route] diz as portas ativas de quem já rodou. */
    fun nextNodes(w: Workflow, from: String, port: String?): List<WfNode> {
        val outs = w.edges.filter { it.from == from && (port == null || it.port == port || it.port == "main") }
        return outs.mapNotNull { e -> w.nodes.firstOrNull { it.id == e.to } }
    }
}
