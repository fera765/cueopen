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
    private val TOKEN = Regex("""\{\{\s*([^{}]+?)\s*}}""")

    /**
     * Troca {{prev}}, {{trigger.time}}, {{<id>}}, {{<id>.campo.sub}} e {{<rótulo do nó>}} pelos valores da execução.
     * Saída que é JSON vira objeto navegável; texto puro só existe inteiro. Referência desconhecida vira texto vazio.
     */
    fun render(template: String, outputs: Map<String, String>, labels: Map<String, String>, prev: String, vars: Map<String, String> = emptyMap()): String =
        TOKEN.replace(template) { m ->
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
