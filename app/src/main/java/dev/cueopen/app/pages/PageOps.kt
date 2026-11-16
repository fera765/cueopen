package dev.cueopen.app.pages

import dev.cueopen.app.data.Section
import dev.cueopen.app.data.TaskStatus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Lógica pura das páginas: criar, aplicar lotes de operações, resumir em texto e sincronizar o quadro do plano. */
object PageOps {
    const val MAX_MARKDOWN = 120_000
    const val MAX_HTML = 400_000
    const val MAX_CARDS = 300
    const val MAX_ROWS = 500

    fun newId(prefix: String = "pg"): String = prefix + "_" + java.util.UUID.randomUUID().toString().replace("-", "").take(10)

    fun create(spaceId: String, title: String, kind: String, by: String, markdown: String = "", html: String = "",
               columns: List<String> = emptyList(), headers: List<String> = emptyList()): Result<FluwyPage> {
        val k = kind.trim().lowercase().ifBlank { FluwyPage.KIND_DOC }
        if (k !in FluwyPage.KINDS) return Result.failure(IllegalArgumentException("kind inválido: use ${FluwyPage.KINDS.joinToString()}"))
        val t = title.trim().take(120)
        if (t.isBlank()) return Result.failure(IllegalArgumentException("informe title"))
        val cols = when (k) {
            FluwyPage.KIND_KANBAN -> columns.map { it.trim() }.filter { it.isNotBlank() }.distinct().ifEmpty { FluwyPage.DEFAULT_COLUMNS }
            FluwyPage.KIND_CHECKLIST -> FluwyPage.CHECKLIST_COLUMNS
            else -> emptyList()
        }
        if (markdown.length > MAX_MARKDOWN || html.length > MAX_HTML) return Result.failure(IllegalArgumentException("conteúdo grande demais"))
        return Result.success(FluwyPage(id = newId(), spaceId = spaceId, title = t, kind = k, markdown = markdown, html = html,
            columns = cols, headers = headers.map { it.trim() }.filter { it.isNotBlank() }, updatedBy = by))
    }

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
    private fun JsonObject.i(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
    private fun JsonObject.list(k: String) = (this[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } .orEmpty()

    private fun findCard(p: FluwyPage, o: JsonObject): PageCard? {
        val id = o.s("card_id"); val title = o.s("title")
        return p.cards.firstOrNull { id.isNotBlank() && it.id == id }
            ?: p.cards.firstOrNull { id.isBlank() && title.isNotBlank() && it.title.equals(title, ignoreCase = true) }
    }

    private fun column(p: FluwyPage, name: String): String? =
        p.columns.firstOrNull { it.equals(name.trim(), ignoreCase = true) }

    /**
     * Aplica um lote de operações. Tudo ou nada: se uma operação falha, a página fica como estava e o erro diz qual foi.
     * Operações: rename, set_markdown, append_markdown, replace_text, set_html, add_column, add_card, move_card,
     * update_card, remove_card, check, uncheck, set_headers, add_row, update_row, remove_row.
     */
    fun apply(page: FluwyPage, ops: List<JsonObject>, by: String, now: Long = System.currentTimeMillis()): Result<FluwyPage> {
        if (ops.isEmpty()) return Result.failure(IllegalArgumentException("ops vazio"))
        var p = page
        ops.forEachIndexed { n, o ->
            val op = o.s("op")
            fun fail(msg: String): Result<FluwyPage> = Result.failure(IllegalArgumentException("operação ${n + 1} ($op): $msg — nada foi alterado"))
            p = when (op) {
                "rename" -> o.s("title").takeIf { it.isNotBlank() }?.let { p.copy(title = it.take(120)) } ?: return fail("informe title")
                "set_markdown" -> p.copy(markdown = o.s("markdown").takeIf { it.length <= MAX_MARKDOWN } ?: return fail("markdown grande demais"))
                "append_markdown" -> (p.markdown.trimEnd() + "\n\n" + o.s("markdown")).trim().takeIf { it.length <= MAX_MARKDOWN }?.let { p.copy(markdown = it) } ?: return fail("página grande demais")
                "replace_text" -> {
                    val find = o.s("find"); if (find.isBlank()) return fail("informe find")
                    val c = p.markdown.split(find).size - 1
                    if (c != 1) return fail(if (c == 0) "trecho não encontrado" else "trecho aparece $c vezes; inclua mais contexto")
                    p.copy(markdown = p.markdown.replace(find, o.s("replace")))
                }
                "set_html" -> {
                    if (p.kind != FluwyPage.KIND_HTML) return fail("set_html só vale para páginas kind=html")
                    p.copy(html = o.s("html").takeIf { it.isNotBlank() && it.length <= MAX_HTML } ?: return fail("html vazio ou grande demais"))
                }
                "add_column" -> {
                    if (p.kind != FluwyPage.KIND_KANBAN) return fail("só em kanban")
                    val name = o.s("name").ifBlank { return fail("informe name") }
                    if (column(p, name) != null) p else p.copy(columns = p.columns + name.take(40))
                }
                "add_card", "add_item" -> {
                    if (p.kind != FluwyPage.KIND_KANBAN && p.kind != FluwyPage.KIND_CHECKLIST) return fail("só em kanban/checklist")
                    if (p.cards.size >= MAX_CARDS) return fail("limite de $MAX_CARDS cartões")
                    val title = o.s("title").ifBlank { return fail("informe title") }
                    val col = if (o.s("column").isBlank()) p.columns.first() else column(p, o.s("column")) ?: return fail("coluna '${o.s("column")}' não existe (${p.columns.joinToString()})")
                    p.copy(cards = p.cards + PageCard(newId("c"), title.take(200), col, o.s("assignee").take(60), o.s("note").take(2_000), by, now))
                }
                "move_card", "check", "uncheck" -> {
                    val card = findCard(p, o) ?: return fail("cartão não encontrado (use card_id do page_read)")
                    val target = when (op) {
                        "check" -> p.columns.last()
                        "uncheck" -> p.columns.first()
                        else -> column(p, o.s("column")) ?: return fail("coluna '${o.s("column")}' não existe (${p.columns.joinToString()})")
                    }
                    p.copy(cards = p.cards.map { if (it.id == card.id) it.copy(column = target, updatedBy = by, updatedAt = now) else it })
                }
                "update_card" -> {
                    val card = findCard(p, o) ?: return fail("cartão não encontrado")
                    p.copy(cards = p.cards.map {
                        if (it.id != card.id) it else it.copy(
                            title = o.s("new_title").ifBlank { it.title }.take(200),
                            note = if (o.containsKey("note")) o.s("note").take(2_000) else it.note,
                            assignee = if (o.containsKey("assignee")) o.s("assignee").take(60) else it.assignee,
                            column = o.s("column").takeIf { c -> c.isNotBlank() }?.let { c -> column(p, c) ?: return fail("coluna '$c' não existe") } ?: it.column,
                            updatedBy = by, updatedAt = now)
                    })
                }
                "remove_card" -> {
                    val card = findCard(p, o) ?: return fail("cartão não encontrado")
                    p.copy(cards = p.cards.filterNot { it.id == card.id })
                }
                "set_headers" -> {
                    if (p.kind != FluwyPage.KIND_TABLE) return fail("só em table")
                    p.copy(headers = o.list("headers").ifEmpty { return fail("informe headers") })
                }
                "add_row" -> {
                    if (p.kind != FluwyPage.KIND_TABLE) return fail("só em table")
                    if (p.rows.size >= MAX_ROWS) return fail("limite de $MAX_ROWS linhas")
                    p.copy(rows = p.rows + listOf(o.list("cells").ifEmpty { return fail("informe cells") }))
                }
                "update_row" -> {
                    val idx = (o.i("index") ?: return fail("informe index (1 = primeira linha)")) - 1
                    if (idx !in p.rows.indices) return fail("linha ${idx + 1} não existe")
                    p.copy(rows = p.rows.mapIndexed { i, r -> if (i == idx) o.list("cells") else r })
                }
                "remove_row" -> {
                    val idx = (o.i("index") ?: return fail("informe index")) - 1
                    if (idx !in p.rows.indices) return fail("linha ${idx + 1} não existe")
                    p.copy(rows = p.rows.filterIndexed { i, _ -> i != idx })
                }
                else -> return fail("operação desconhecida")
            }
        }
        return Result.success(p.copy(version = page.version + 1, updatedAt = now, updatedBy = by))
    }

    /** Versão compacta para o agente (barata em tokens): cartões com id, linhas numeradas, texto inteiro só do documento. */
    fun render(p: FluwyPage, maxChars: Int = 12_000): String = buildString {
        appendLine("Página ${p.id} · \"${p.title}\" · ${p.kind} · versão ${p.version} · atualizada por ${p.updatedBy.ifBlank { "?" }}")
        when (p.kind) {
            FluwyPage.KIND_KANBAN, FluwyPage.KIND_CHECKLIST -> p.columns.forEach { col ->
                val cs = p.cards.filter { it.column == col }
                appendLine("## $col (${cs.size})")
                cs.forEach { c -> appendLine("- [${c.id}] ${c.title}" + (if (c.assignee.isNotBlank()) " · @${c.assignee}" else "") + (if (c.note.isNotBlank()) " — ${c.note.take(160)}" else "")) }
            }
            FluwyPage.KIND_TABLE -> {
                appendLine("| # | " + p.headers.joinToString(" | ") + " |")
                p.rows.forEachIndexed { i, r -> appendLine("| ${i + 1} | " + r.joinToString(" | ") + " |") }
            }
            FluwyPage.KIND_HTML -> appendLine("(HTML com ${p.html.length} caracteres; para mudar, envie a página inteira com set_html)\n" + p.html.take(maxChars / 2))
            else -> append(p.markdown)
        }
    }.let { if (it.length > maxChars) it.take(maxChars) + "\n[… cortado]" else it }

    private fun columnFor(s: TaskStatus, cols: List<String>): String = when (s) {
        TaskStatus.PENDING -> cols[0]
        TaskStatus.RUNNING -> cols.getOrElse(1) { cols[0] }
        TaskStatus.REVIEW -> cols.getOrElse(2) { cols.last() }
        TaskStatus.DONE -> cols.last()
        TaskStatus.FAILED, TaskStatus.BLOCKED -> "Bloqueado"
        TaskStatus.CANCELLED -> ""
    }

    fun boardId(sectionId: String) = "board_$sectionId"

    /**
     * Quadro do time: espelha o plano da seção (team_plan) num kanban. Cartões "task_*" seguem o estado real das tarefas;
     * cartões que os agentes criaram à mão ficam como estão.
     */
    fun syncBoard(pages: List<FluwyPage>, sec: Section, now: Long = System.currentTimeMillis()): List<FluwyPage> {
        if (sec.plan.isEmpty()) return pages
        val id = boardId(sec.id)
        val existing = pages.firstOrNull { it.id == id }
        val base = existing ?: FluwyPage(id = id, spaceId = sec.sessionId, title = "Quadro do time · ${sec.name}", kind = FluwyPage.KIND_KANBAN,
            columns = FluwyPage.DEFAULT_COLUMNS, sectionId = sec.id, updatedBy = "plano", pinned = true)
        var cols = base.columns
        val planCards = sec.plan.mapNotNull { t ->
            val col = columnFor(t.status, cols).ifBlank { return@mapNotNull null }
            if (col !in cols) cols = cols + col
            val old = base.cards.firstOrNull { it.id == "task_${t.id}" }
            val note = (t.note.ifBlank { t.result }).take(300)
            if (old != null && old.column == col && old.note == note && old.assignee == t.agentName) old
            else PageCard("task_${t.id}", t.task.take(200), col, t.agentName, note, "plano", now)
        }
        val manual = base.cards.filterNot { it.id.startsWith("task_") }
        val cards = planCards + manual
        if (existing != null && existing.cards == cards && existing.columns == cols) return pages
        val updated = base.copy(columns = cols, cards = cards, version = if (existing == null) 1 else existing.version + 1, updatedAt = now, updatedBy = "plano")
        return if (existing == null) pages + updated else pages.map { if (it.id == id) updated else it }
    }
}
