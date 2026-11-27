package dev.fluwy.app.pages

/** Exporta a página como um arquivo HTML mobile-first autossuficiente (para compartilhar/abrir fora do app). */
object PageHtml {
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    const val VIEWPORT = "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\">"

    /** Garante viewport e charset numa página HTML livre (renderização correta no celular). */
    fun ensureMobile(html: String): String {
        var h = html
        if (!h.contains("name=\"viewport\"", true) && !h.contains("name='viewport'", true))
            h = if (h.contains("<head>", true)) h.replaceFirst(Regex("(?i)<head>"), "<head>$VIEWPORT") else "<!doctype html><html><head><meta charset=\"utf-8\">$VIEWPORT</head><body>$h</body></html>"
        return h
    }

    private val css = """
        :root{color-scheme:light dark;--bg:#fff;--fg:#151515;--mut:#666;--card:#f3f3f3;--ac:#ff6b3d}
        @media(prefers-color-scheme:dark){:root{--bg:#121212;--fg:#eee;--mut:#999;--card:#1e1e1e}}
        *{box-sizing:border-box}body{margin:0;padding:16px;font:16px/1.5 system-ui,-apple-system,Roboto,sans-serif;background:var(--bg);color:var(--fg)}
        h1{font-size:22px;margin:0 0 4px}.meta{color:var(--mut);font-size:12px;margin-bottom:16px}
        .board{display:flex;gap:12px;overflow-x:auto;scroll-snap-type:x mandatory;padding-bottom:8px}
        .col{min-width:78vw;max-width:320px;scroll-snap-align:start;background:var(--card);border-radius:14px;padding:10px}
        .col h2{font-size:14px;margin:4px 4px 8px;color:var(--mut)}.card{background:var(--bg);border-radius:10px;padding:10px;margin-bottom:8px}
        .card b{display:block}.card small{color:var(--mut)}table{border-collapse:collapse;width:100%;font-size:14px}
        .tw{overflow-x:auto}th,td{border-bottom:1px solid var(--card);padding:8px;text-align:left;white-space:nowrap}
        li.done{color:var(--mut);text-decoration:line-through}pre{white-space:pre-wrap;background:var(--card);padding:12px;border-radius:10px}
    """.trimIndent()

    fun export(p: FluwyPage): String {
        if (p.kind == FluwyPage.KIND_HTML) return ensureMobile(p.html)
        val body = when (p.kind) {
            FluwyPage.KIND_KANBAN -> "<div class=board>" + p.columns.joinToString("") { col ->
                "<div class=col><h2>${esc(col)} (${p.cards.count { it.column == col }})</h2>" + p.cards.filter { it.column == col }.joinToString("") { c ->
                    "<div class=card><b>${esc(c.title)}</b>" + (if (c.assignee.isNotBlank()) "<small>@${esc(c.assignee)}</small>" else "") +
                        (if (c.note.isNotBlank()) "<div>${esc(c.note)}</div>" else "") + "</div>"
                } + "</div>"
            } + "</div>"
            FluwyPage.KIND_CHECKLIST -> "<ul>" + p.cards.joinToString("") { c ->
                val done = c.column == p.columns.lastOrNull(); "<li class=\"${if (done) "done" else ""}\">${if (done) "☑" else "☐"} ${esc(c.title)}</li>"
            } + "</ul>"
            FluwyPage.KIND_TABLE -> "<div class=tw><table><tr>" + p.headers.joinToString("") { "<th>${esc(it)}</th>" } + "</tr>" +
                p.rows.joinToString("") { r -> "<tr>" + r.joinToString("") { "<td>${esc(it)}</td>" } + "</tr>" } + "</table></div>"
            else -> "<pre>${esc(p.markdown)}</pre>"
        }
        return "<!doctype html><html lang=pt-BR><head><meta charset=utf-8>$VIEWPORT<title>${esc(p.title)}</title><style>$css</style></head><body>" +
            "<h1>${esc(p.title)}</h1><div class=meta>Fluwy Pages · versão ${p.version}</div>$body</body></html>"
    }
}
