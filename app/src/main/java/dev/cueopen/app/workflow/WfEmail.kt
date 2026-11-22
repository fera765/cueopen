package dev.cueopen.app.workflow

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * E-mail sempre sai em HTML bem formatado (estilos inline, que é o que Gmail/Outlook respeitam), com versão em texto.
 * Markdown (títulos, negrito, listas, tabelas, links, código, citações) vira HTML; HTML pronto é só embrulhado.
 */
object WfEmail {
    private val BODY_KEYS = listOf("body_text", "body", "corpo", "text", "body_html")

    fun looksHtml(s: String) = Regex("<(html|body|p|div|table|br|h[1-6]|ul|ol|a |span|strong)\\b", RegexOption.IGNORE_CASE).containsMatchIn(s)

    /** Ajusta os campos do envio ao formato da ação ([keys] = campos que a ação aceita; vazio = desconhecido). */
    fun prepare(campos: JsonObject, keys: Set<String>): JsonObject {
        val src = BODY_KEYS.firstNotNullOfOrNull { k -> (campos[k] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } } ?: return campos
        val subject = (campos["subject"] as? JsonPrimitive)?.content.orEmpty()
        val html = if (looksHtml(src)) wrap(if (Regex("<html", RegexOption.IGNORE_CASE).containsMatchIn(src)) null else src, subject, raw = src) else wrap(toHtml(src), subject)
        val plain = if (looksHtml(src)) src.replace(Regex("<br\\s*/?>|</p>|</div>|</tr>|</h[1-6]>", RegexOption.IGNORE_CASE), "\n").replace(Regex("<[^>]+>"), "").trim() else plainText(src)
        val m = campos.toMutableMap()
        when {
            "body_type" in keys && "body" in keys -> { m["body_type"] = JsonPrimitive("html"); m["body"] = JsonPrimitive(html); m.remove("body_text") }
            "body_html" in keys -> { m["body_html"] = JsonPrimitive(html); if ("body_text" in keys) m["body_text"] = JsonPrimitive(plain) }
            "html" in keys -> m["html"] = JsonPrimitive(html)
            else -> { m["body_html"] = JsonPrimitive(html); if (campos.containsKey("body_text")) m["body_text"] = JsonPrimitive(plain); if (campos.containsKey("body")) { m["body"] = JsonPrimitive(html); m["body_type"] = JsonPrimitive("html") } }
        }
        return JsonObject(m)
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun inline(s0: String): String {
        var s = esc(s0)
        val codes = mutableListOf<String>()
        s = Regex("`([^`]+)`").replace(s) { codes += it.groupValues[1]; "\u0000${codes.size - 1}\u0000" }
        s = Regex("!\\[([^\\]]*)\\]\\((https?://[^)\\s]+)\\)").replace(s) { "<img src=\"${it.groupValues[2]}\" alt=\"${it.groupValues[1]}\" style=\"display:block;width:100%;max-width:560px;height:auto;border-radius:10px;margin:4px 0 8px\">" }
        s = Regex("\\[([^\\]]+)\\]\\((https?://[^)\\s]+|mailto:[^)\\s]+)\\)").replace(s) { "<a href=\"${it.groupValues[2]}\" style=\"color:#2563eb;text-decoration:underline\">${it.groupValues[1]}</a>" }
        s = Regex("&lt;(https?://[^\\s&]+)&gt;").replace(s) { "<a href=\"${it.groupValues[1]}\" style=\"color:#2563eb\">${it.groupValues[1]}</a>" }
        s = Regex("(?<![\"=>])\\bhttps?://[^\\s<]+[^\\s<.,;:!?)]").replace(s) { "<a href=\"${it.value}\" style=\"color:#2563eb\">${it.value}</a>" }
        s = Regex("\\*\\*([^*]+)\\*\\*|__([^_]+)__").replace(s) { "<strong>${it.groupValues[1].ifEmpty { it.groupValues[2] }}</strong>" }
        s = Regex("(?<![*\\w])\\*([^*\\s][^*]*)\\*(?![*\\w])").replace(s) { "<em>${it.groupValues[1]}</em>" }
        s = Regex("~~([^~]+)~~").replace(s) { "<s>${it.groupValues[1]}</s>" }
        s = Regex("\u0000(\\d+)\u0000").replace(s) { "<code style=\"background:#f1f5f9;padding:1px 5px;border-radius:4px;font-family:monospace;font-size:13px\">${codes[it.groupValues[1].toInt()]}</code>" }
        return s
    }

    private fun cells(line: String) = line.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }

    /** Markdown → HTML com estilos inline. */
    fun toHtml(md: String): String {
        val lines = md.replace("\r\n", "\n").split('\n')
        val out = StringBuilder()
        var i = 0
        val para = mutableListOf<String>()
        fun flush() { if (para.isNotEmpty()) { out.append("<p style=\"margin:0 0 14px\">").append(para.joinToString("<br>") { inline(it) }).append("</p>"); para.clear() } }
        while (i < lines.size) {
            val l = lines[i]; val t = l.trim()
            when {
                t.startsWith("```") -> {
                    flush(); val code = mutableListOf<String>(); i++
                    while (i < lines.size && !lines[i].trim().startsWith("```")) { code += lines[i]; i++ }
                    out.append("<pre style=\"background:#0f172a;color:#e2e8f0;padding:12px;border-radius:8px;overflow:auto;font-size:13px\"><code>").append(esc(code.joinToString("\n"))).append("</code></pre>")
                }
                t.isEmpty() -> flush()
                Regex("^#{1,6} ").containsMatchIn(t) -> {
                    flush(); val lvl = t.takeWhile { it == '#' }.length; val size = listOf(24, 20, 17, 15, 14, 13)[lvl - 1]
                    out.append("<h$lvl style=\"margin:18px 0 8px;font-size:${size}px;color:#0f172a\">").append(inline(t.drop(lvl).trim())).append("</h$lvl>")
                }
                Regex("^(-{3,}|\\*{3,}|_{3,})$").matches(t) -> { flush(); out.append("<hr style=\"border:none;border-top:1px solid #e2e8f0;margin:18px 0\">") }
                t.startsWith("|") && i + 1 < lines.size && Regex("^\\|?\\s*:?-{2,}").containsMatchIn(lines[i + 1].trim()) -> {
                    flush()
                    val head = cells(t); i += 2
                    out.append("<table cellpadding=\"0\" cellspacing=\"0\" style=\"border-collapse:collapse;width:100%;margin:0 0 14px;font-size:14px\"><tr>")
                    head.forEach { out.append("<th style=\"text-align:left;padding:8px 10px;background:#f1f5f9;border:1px solid #e2e8f0\">").append(inline(it)).append("</th>") }
                    out.append("</tr>")
                    var odd = false
                    while (i < lines.size && lines[i].trim().startsWith("|")) {
                        out.append("<tr>"); cells(lines[i]).forEach { out.append("<td style=\"padding:8px 10px;border:1px solid #e2e8f0").append(if (odd) ";background:#fafafa" else "").append("\">").append(inline(it)).append("</td>") }
                        out.append("</tr>"); odd = !odd; i++
                    }
                    out.append("</table>"); continue
                }
                Regex("^([-*+]|\\d+[.)]) ").containsMatchIn(t) -> {
                    flush(); val ordered = t.first().isDigit(); val tag = if (ordered) "ol" else "ul"
                    out.append("<$tag style=\"margin:0 0 14px;padding-left:22px\">")
                    while (i < lines.size && Regex("^([-*+]|\\d+[.)]) ").containsMatchIn(lines[i].trim())) {
                        var item = lines[i].trim().replaceFirst(Regex("^([-*+]|\\d+[.)]) "), "")
                        item = item.replaceFirst(Regex("^\\[ \\] "), "☐ ").replaceFirst(Regex("^\\[[xX]\\] "), "☑ ")
                        out.append("<li style=\"margin:0 0 6px\">").append(inline(item)).append("</li>"); i++
                    }
                    out.append("</$tag>"); continue
                }
                t.startsWith(">") -> {
                    flush(); val q = mutableListOf<String>()
                    while (i < lines.size && lines[i].trim().startsWith(">")) { q += lines[i].trim().removePrefix(">").trim(); i++ }
                    out.append("<blockquote style=\"margin:0 0 14px;padding:8px 14px;border-left:4px solid #cbd5e1;color:#475569\">").append(q.joinToString("<br>") { inline(it) }).append("</blockquote>"); continue
                }
                else -> para += t
            }
            i++
        }
        flush()
        return out.toString()
    }

    /** Documento de e-mail completo (largura fixa, fonte do sistema). [raw] = HTML completo já pronto. */
    fun wrap(inner: String?, subject: String, raw: String = ""): String {
        if (inner == null) return raw
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>${esc(subject)}</title></head>" +
            "<body style=\"margin:0;padding:0;background:#f6f7f9\"><table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"background:#f6f7f9\"><tr><td align=\"center\" style=\"padding:24px 12px\">" +
            "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"max-width:640px;background:#ffffff;border-radius:12px;border:1px solid #e5e7eb\"><tr><td style=\"padding:24px 26px;font-family:-apple-system,Segoe UI,Roboto,Helvetica,Arial,sans-serif;font-size:15px;line-height:1.55;color:#1f2937\">" +
            inner + "</td></tr></table></td></tr></table></body></html>"
    }

    /** Versão em texto (sem marcações) para clientes que não mostram HTML. */
    fun plainText(md: String): String = md.replace(Regex("```\\w*\\n?"), "").replace(Regex("\\*\\*|__|~~|`"), "")
        .replace(Regex("\\[([^\\]]+)\\]\\(([^)]+)\\)"), "$1 ($2)").replace(Regex("(?m)^#{1,6} "), "").replace(Regex("(?m)^\\|?\\s*:?-{2,}.*$\\n?"), "").trim()

    private val ITEM_LINK = Regex("\\[([^\\]]+)\\]\\((https?://[^)\\s]+)\\)")

    /** Links de itens (lista, título ou parágrafo) que ainda não têm foto na mesma linha. */
    fun linkedItemsWithoutImage(md: String): List<String> = md.lines().filter { !it.contains("![") && !it.trimStart().startsWith("|") }
        .mapNotNull { l -> ITEM_LINK.find(l)?.groupValues?.get(2) }.distinct().take(12)

    /** Coloca a foto antes do texto de cada item com link (e-mail de notícias/produtos nunca sem imagem). */
    fun withImages(md: String, imgs: Map<String, String>): String = md.lines().joinToString("\n") { l ->
        val m = ITEM_LINK.find(l)
        val img = m?.let { imgs[it.groupValues[2]] }
        if (img == null || l.contains("![") || l.trimStart().startsWith("|")) l
        else {
            val lead = Regex("^(\\s*(?:[-*+]|\\d+[.)]|#{1,6})\\s+)").find(l)?.value.orEmpty()
            lead + "![" + m.groupValues[1].replace("]", "") + "](" + img + ") " + l.removePrefix(lead)
        }
    }
}
