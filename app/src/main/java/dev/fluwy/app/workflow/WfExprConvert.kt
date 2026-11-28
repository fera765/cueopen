package dev.fluwy.app.workflow

/**
 * Converte expressões do formato antigo para o estilo n8n (usado nos modelos e no botão "Converter expressões"):
 * `{{prev}}` → `{{ $json }}`, `{{prev.a.0}}` → `{{ $json.a[0] }}`, `{{<id>.a}}` → `{{ $node["Nome"].json.a }}`,
 * `{{trigger.payload.x}}` → `{{ $trigger.json.payload.x }}`, `{{trigger.time}}` → `{{ $now.toFormat('yyyy-MM-dd HH:mm') }}`,
 * `{{workflow.name}}` → `{{ $workflow.name }}`, `| default:x` → `|| 'x'`. O que não tem equivalente exato fica como está.
 */
object WfExprConvert {
    private val TOKEN = Regex("""\{\{\s*([^\{\}]+?)\s*\}\}""")

    fun workflow(w: Workflow): Workflow {
        val labels = w.nodes.associate { it.id to WfLogic.label(it) }
        val unique = labels.values.groupingBy { it.lowercase() }.eachCount()
        return w.copy(nodes = w.nodes.map { n -> n.copy(config = n.config.mapValues { (_, v) -> text(v, labels, unique) }) })
    }

    fun text(v: String, labels: Map<String, String>, unique: Map<String, Int> = labels.values.groupingBy { it.lowercase() }.eachCount()): String =
        TOKEN.replace(v) { m -> token(m.groupValues[1].trim(), labels, unique)?.let { "{{ $it }}" } ?: m.value }

    private fun token(expr: String, labels: Map<String, String>, unique: Map<String, Int>): String? {
        if (WfExpr.isNew(expr)) return null
        val parts = expr.split('|').map { it.trim() }
        var def: String? = null
        if (parts.size > 1) {
            if (parts.size > 2 || !parts[1].startsWith("default:")) return null
            def = parts[1].substringAfter("default:").trim()
        }
        val ref = parts[0]
        val head = ref.substringBefore('.')
        val path = if (ref.contains('.')) ref.substringAfter('.') else ""
        val base = when {
            ref == "trigger.time" -> "\$now.toFormat('yyyy-MM-dd HH:mm')"
            ref == "workflow.name" -> "\$workflow.name"
            head == "prev" -> "\$json" + segs(path)
            head == "trigger" && path.startsWith("payload") -> "\$trigger.json" + segs(path)
            head == "trigger" -> return null
            else -> {
                val id = if (labels.containsKey(head)) head else labels.entries.firstOrNull { it.value.equals(head, true) }?.key ?: return null
                val label = labels.getValue(id)
                if ((unique[label.lowercase()] ?: 0) > 1 || label.contains('"') || label.contains('\\')) return null
                "\$node[\"$label\"].json" + segs(path)
            }
        }
        return if (def != null) "$base || '${def.replace("'", "\\'")}'" else base
    }

    private fun segs(path: String) = path.split('.').filter { it.isNotEmpty() }.joinToString("") { s ->
        when {
            s.toIntOrNull() != null -> "[$s]"
            Regex("^[A-Za-z_][A-Za-z0-9_]*$").matches(s) -> ".$s"
            else -> "[\"" + s.replace("\"", "\\\"") + "\"]"
        }
    }
}
