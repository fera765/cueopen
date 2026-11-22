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
            n.config.values.flatMap { v -> TOKEN.findAll(v).map { it.groupValues[1].substringBefore('|').trim() }.toList() }.distinct().forEach { expr ->
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
            // Filtros: {{g1.data.from | email}} · | name · | text · | oneline · | lower · | upper · | trim · | default:valor
            val parts = m.groupValues[1].split('|').map { it.trim() }
            if (parts.size > 1) return@replace applyFilters(render("{{" + parts[0] + "}}", outputs, labels, prev, vars), parts.drop(1))
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

    private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")

    fun applyFilters(value: String, filters: List<String>): String = filters.fold(value) { v, f ->
        val name = f.substringBefore(':').trim().lowercase(); val arg = f.substringAfter(':', "").trim()
        when (name) {
            "email" -> EMAIL.find(v)?.value.orEmpty()
            "name" -> WfFormat.person(kotlinx.serialization.json.JsonPrimitive(v)).orEmpty()
            "text", "readable" -> WfFormat.readable(v).replace("**", "")
            "oneline" -> WfFormat.oneLine(v)
            "lower" -> v.lowercase()
            "upper" -> v.uppercase()
            "trim" -> v.trim()
            "first" -> v.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
            "default" -> v.ifBlank { arg }
            "json" -> kotlinx.serialization.json.JsonPrimitive(v).toString().removeSurrounding("\"")
            else -> v
        }
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
    fun nextFire(config: Map<String, String>, now: Long, zone: TimeZone = TimeZone.getDefault(), lastFire: Long? = null): Long? {
        return when (config["mode"] ?: "daily") {
            "interval" -> {
                val min = config["minutes"]?.toIntOrNull()?.takeIf { it >= 1 } ?: return null
                val step = min * 60_000L
                // Ancorado no último disparo: o intervalo não "reinicia" ao abrir o app. Atrasado = roda já (uma vez).
                if (lastFire == null || lastFire > now) now + step
                else (lastFire + step).let { if (it <= now) now + 1_000L else it }
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

    /**
     * Interpreta a frequência escrita livremente ("a cada 2 minutos", "1x por dia às 8h", "toda segunda 18:30",
     * "de hora em hora", "every 5 min") → config do gatilho de Agendamento. null = não entendeu.
     */
    fun parseSchedule(text: String): Map<String, String>? {
        val t = java.text.Normalizer.normalize(text.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").trim()
        if (t.isBlank()) return null
        val time = Regex("""(\d{1,2})\s*(?::|h)\s*(\d{2})?""").find(t.replace(Regex("""a cada \d+\s*h\w*"""), ""))?.let {
            val h = it.groupValues[1].toInt(); val m = it.groupValues[2].ifBlank { "0" }.toInt()
            if (h in 0..23 && m in 0..59) "%02d:%02d".format(h, m) else null
        }
        val days = listOf("segunda", "terca", "quarta", "quinta", "sexta", "sabado", "domingo")
        val en = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
        val wd = days.indexOfFirst { Regex("""\b$it""").containsMatchIn(t) }.takeIf { it >= 0 }
            ?: en.indexOfFirst { t.contains(it) }.takeIf { it >= 0 }
        if (wd != null && !Regex("""todo dia|diari|every day|daily""").containsMatchIn(t))
            return mapOf("mode" to "weekly", "weekday" to (wd + 1).toString(), "time" to (time ?: "09:00"))
        val num = Regex("""(?:a cada|cada|de|every|each)\s+(\d+)\s*(min|m\b|minuto|minute|h\b|hr|hora|hour|dia|day)""").find(t)
        if (num != null) {
            val n = num.groupValues[1].toInt().takeIf { it >= 1 } ?: return null
            val u = num.groupValues[2]
            return when {
                u.startsWith("d") -> if (n == 1) mapOf("mode" to "daily", "time" to (time ?: "09:00")) else mapOf("mode" to "interval", "minutes" to (n * 1440).toString())
                u.startsWith("h") -> mapOf("mode" to "interval", "minutes" to (n * 60).toString())
                else -> mapOf("mode" to "interval", "minutes" to n.toString())
            }
        }
        if (Regex("""\d+\s*(min|minuto|minute)""").containsMatchIn(t))
            return Regex("""(\d+)\s*(min|minuto|minute)""").find(t)!!.groupValues[1].toInt().takeIf { it >= 1 }?.let { mapOf("mode" to "interval", "minutes" to it.toString()) }
        if (Regex("""a cada minuto|todo minuto|every minute""").containsMatchIn(t)) return mapOf("mode" to "interval", "minutes" to "1")
        if (Regex("""a cada hora|toda hora|de hora em hora|1x por hora|uma vez por hora|hourly|every hour|por hora""").containsMatchIn(t)) return mapOf("mode" to "interval", "minutes" to "60")
        if (Regex("""todo dia|todos os dias|diari|1x (por|ao) dia|uma vez (por|ao) dia|daily|every day|por dia""").containsMatchIn(t) || time != null)
            return mapOf("mode" to "daily", "time" to (time ?: "09:00"))
        return null
    }

    /**
     * Este disparo deve rodar? [due] = horário que ele representa. Não roda se já houve disparo depois de
     * (due − 30 s) — o alarme e a reserva do WorkManager chegam para o mesmo horário — nem se ainda falta
     * mais de 30 s para o próximo agendado (disparo velho, já substituído).
     */
    fun shouldFire(due: Long, last: Long?, next: Long?, now: Long): Boolean {
        if (last != null && last >= due - 30_000L) return false
        if (next != null && next - 30_000L > now && next > due + 30_000L) return false
        return true
    }

    /**
     * Config final do gatilho de Agendamento: frase livre em when/text/schedule/frequency (ou em mode) é interpretada;
     * config estruturada válida é mantida. null = inválida/incompreensível.
     */
    fun normalizeSchedule(cfg: Map<String, String>): Map<String, String>? {
        val free = listOf("when", "text", "schedule", "frequency", "frequencia").firstNotNullOfOrNull { k -> cfg[k]?.takeIf { it.isNotBlank() } }
            ?: cfg["mode"]?.takeIf { it.isNotBlank() && it !in setOf("interval", "daily", "weekly") }
        val rest = cfg - setOf("when", "text", "schedule", "frequency", "frequencia")
        if (free != null) {
            val p = parseSchedule(free) ?: return null
            return (rest - setOf("mode", "minutes", "time", "weekday")) + p
        }
        val c = if (rest["mode"].isNullOrBlank()) rest + ("mode" to "daily") else rest
        return if (nextFire(c, 0L) != null) c else null
    }

    /** "agora", "em 2 min", "hoje 14:05", "amanhã 09:00", "ontem 18:30", "24/11 08:00". */
    fun whenLabel(ts: Long, now: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val d = ts - now
        if (kotlin.math.abs(d) < 45_000) return "agora"
        if (d > 0 && d < 60 * 60_000L) return "em ${(d + 59_999) / 60_000} min"
        if (d < 0 && -d < 60 * 60_000L) return "há ${-d / 60_000} min"
        val cal = Calendar.getInstance(zone).apply { timeInMillis = ts }
        val hm = "%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
        val diff = run { val a = Calendar.getInstance(zone).apply { timeInMillis = now; set(Calendar.HOUR_OF_DAY, 12) }; val b = Calendar.getInstance(zone).apply { timeInMillis = ts; set(Calendar.HOUR_OF_DAY, 12) }; Math.round((b.timeInMillis - a.timeInMillis) / 86_400_000.0).toInt() }
        return when (diff) { 0 -> "hoje $hm"; 1 -> "amanhã $hm"; -1 -> "ontem $hm"
            else -> "%02d/%02d %s".format(cal.get(Calendar.DAY_OF_MONTH), cal.get(Calendar.MONTH) + 1, hm) }
    }

    /** Resumo legível do agendamento ("Todo dia às 09:00"). */
    fun scheduleLabel(config: Map<String, String>): String = when (config["mode"] ?: "daily") {
        "interval" -> config["minutes"]?.toIntOrNull()?.let { m ->
            when { m % 1440 == 0 -> if (m == 1440) "A cada 1 dia" else "A cada ${m / 1440} dias"; m % 60 == 0 -> if (m == 60) "A cada hora" else "A cada ${m / 60} h"; else -> "A cada $m min" }
        } ?: "A cada ? min"
        "weekly" -> "Toda ${listOf("segunda", "terça", "quarta", "quinta", "sexta", "sábado", "domingo").getOrElse(((config["weekday"]?.toIntOrNull() ?: 1) - 1).coerceIn(0, 6)) { "segunda" }} às ${config["time"] ?: "09:00"}"
        else -> "Todo dia às ${config["time"] ?: "09:00"}"
    }

    /** Ordem de execução a partir do gatilho: cada nó roda uma vez; [route] diz as portas ativas de quem já rodou. */
    fun nextNodes(w: Workflow, from: String, port: String?): List<WfNode> {
        val outs = w.edges.filter { it.from == from && (port == null || it.port == port || it.port == "main") }
        return outs.mapNotNull { e -> w.nodes.firstOrNull { it.id == e.to } }
    }
}
