package dev.fluwy.app.ui

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.highlightedCodeBlock
import com.mikepenz.markdown.compose.elements.highlightedCodeFence
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import dev.fluwy.app.ui.theme.Fluwy
import androidx.compose.material3.MaterialTheme

private val fencedBlock = Regex("```([a-zA-Z0-9_+.-]*)[ \\t]*\\n([\\s\\S]*?)```")

/**
 * Markdown estilo GitHub no chat: títulos, listas, task lists, tabelas, citações, links e blocos de
 * código com syntax highlighting (renderizador nativo, leve para listas longas). Blocos ```mermaid
 * (mapas mentais, fluxogramas, diagramas) são desenhados por um mini WebView com mermaid.js.
 */
// bloco fluwyui (SDK de elementos), fechado OU ainda aberto no fim (stream): grupo 1 = conteúdo, 2 = fechado?
private val openFence = Regex("```[a-zA-Z0-9_+.-]*[ \\t]*\\n?[\\s\\S]*$")

@Composable
fun FluwyMarkdown(text: String, modifier: Modifier = Modifier, onSend: ((String) -> Unit)? = null) {
    val parts = remember(text) {
        val text = GfmPrep.normalize(text)
        val out = mutableListOf<Triple<String, String, String>>() // tipo, linguagem, conteúdo
        var last = 0
        fencedBlock.findAll(text).forEach { m ->
            if (m.range.first > last) out += Triple("text", "", text.substring(last, m.range.first))
            val language = m.groupValues[1].trim()
            val code = m.groupValues[2].removeSuffix("\n")
            val kind = when { language.equals("mermaid", true) -> "mermaid"; language.equals("fluwyui", true) -> "fluwyui"; else -> "code" }
            out += Triple(kind, language, code)
            last = m.range.last + 1
        }
        if (last < text.length) {
            val tail = text.substring(last)
            // bloco fluwyui ainda chegando (abertura sem fechamento) → render progressivo, nunca mostra o JSON cru
            val openFluwy = Regex("```fluwyui[ \\t]*\\n?([\\s\\S]*)$", RegexOption.IGNORE_CASE).find(tail)
            if (openFluwy != null) {
                val before = tail.substring(0, openFluwy.range.first)
                if (before.isNotBlank()) out += Triple("text", "", before)
                out += Triple("fluwyui-open", "fluwyui", openFluwy.groupValues[1])
            } else out += Triple("text", "", tail)
        }
        out.filter { it.first.startsWith("fluwyui") || it.third.isNotBlank() }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        parts.forEach { (kind, language, content) ->
            when (kind) {
                "mermaid" -> MermaidCard(content)
                "fluwyui" -> FluwyWidgetView(FluwyUi.parse(content, complete = true), onSend)
                "files" -> FluwyWidgetView(FluwyWidget.Files("Arquivos", content.lines().mapNotNull { FileLinks.parseAttachmentLine(it) }), onSend)
                "fluwyui-open" -> FluwyWidgetView(FluwyUi.parse(content, complete = false), onSend)
                "code" -> CodeFenceCard(language, content)
                else -> {
                    // Linhas "📎 arquivo" (entrega do agente) viram cartões clicáveis que abrem o arquivo.
                    val lines = content.lines()
                    if (lines.none { FileLinks.parseAttachmentLine(it) != null }) { if (hasTable(content)) renderWithTables(content) else MdBlock(content) }
                    else {
                        val chunks = mutableListOf<Pair<Boolean, MutableList<String>>>()
                        lines.forEach { l -> val isFile = FileLinks.parseAttachmentLine(l) != null
                            if (chunks.lastOrNull()?.first == isFile) chunks.last().second.add(l) else chunks.add(isFile to mutableListOf(l)) }
                        chunks.forEach { (isFile, ls) ->
                            val txt = ls.joinToString("\n")
                            if (isFile) FluwyWidgetView(FluwyWidget.Files("Arquivos", ls.mapNotNull { FileLinks.parseAttachmentLine(it) }), onSend)
                            else if (txt.isNotBlank()) { if (hasTable(txt)) renderWithTables(txt) else MdBlock(txt) }
                        }
                    }
                }
            }
        }
    }
}

/** Bloco de código com linguagem à esquerda e cópia explícita no cabeçalho à direita. */
@Composable
private fun CodeFenceCard(language: String, code: String) {
    val c = Fluwy.c
    val clipboard = LocalClipboardManager.current
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, c.line, RoundedCornerShape(12.dp))
            .background(c.surface2)
    ) {
        Row(
            Modifier.fillMaxWidth().background(c.surface2).padding(start = 12.dp, end = 4.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Text(
                text = language.ifBlank { "Código" },
                modifier = Modifier.weight(1f),
                color = c.ink2,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            IconButton(onClick = { clipboard.setText(AnnotatedString(code)) }) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = "Copiar código", tint = c.ink2)
            }
        }
        androidx.compose.material3.HorizontalDivider(color = c.line)
        SelectionContainer {
            Text(
                text = code,
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(12.dp),
                color = c.ink,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                softWrap = false
            )
        }
    }
}

/** Separa um bloco em trechos de texto e tabelas GFM, renderizando cada tabela nativamente. */
@Composable
private fun renderWithTables(md: String) {
    val lines = md.lines()
    val segments = remember(md) {
        val segs = mutableListOf<Pair<Boolean, List<String>>>() // (isTable, lines)
        var i = 0
        val buf = mutableListOf<String>()
        fun flush() { if (buf.isNotEmpty()) { segs += false to buf.toList(); buf.clear() } }
        while (i < lines.size) {
            val isHeader = i + 1 < lines.size && lines[i].contains('|') &&
                lines[i + 1].trim().let { it.contains('-') && it.all { ch -> ch == '|' || ch == '-' || ch == ':' || ch == ' ' } && it.contains('|') }
            if (isHeader) {
                flush()
                val t = mutableListOf(lines[i], lines[i + 1]); i += 2
                while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) { t += lines[i]; i++ }
                segs += true to t.toList()
            } else { buf += lines[i]; i++ }
        }
        flush()
        segs
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        segments.forEach { (isTable, ls) ->
            if (isTable) NativeTable(ls) else { val t = ls.joinToString("\n").trim(); if (t.isNotBlank()) MdBlock(t) }
        }
    }
}

/** Tabela GFM nativa: cabeçalho destacado, linhas zebradas, rolagem horizontal própria. */
@Composable
private fun NativeTable(rows: List<String>) {
    val c = Fluwy.c
    fun cells(line: String) = line.trim().trim('|').split("|").map { it.trim() }
    val header = cells(rows.first())
    val body = rows.drop(2).map { cells(it) }
    val cols = header.size
    val colW = 150.dp
    Column(Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).border(1.dp, c.line, androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
        .horizontalScroll(rememberScrollState())) {
        Row(Modifier.background(c.surface2)) {
            header.forEachIndexed { i, h -> TableCell(h, colW, bold = true) }
        }
        body.forEachIndexed { ri, row ->
            Row(Modifier.background(if (ri % 2 == 0) c.surface else c.surface2.copy(alpha = 0.4f))) {
                for (ci in 0 until cols) TableCell(row.getOrElse(ci) { "" }, colW)
            }
        }
    }
}

@Composable
private fun TableCell(text: String, width: androidx.compose.ui.unit.Dp, bold: Boolean = false) {
    val c = Fluwy.c
    androidx.compose.material3.Text(
        text.replace("**", ""), modifier = Modifier.width(width).padding(horizontal = 10.dp, vertical = 8.dp),
        fontSize = 13.sp, color = c.ink,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
    )
}

/** Detecta uma tabela GFM (linha com | e linha separadora ---|---). */
private fun hasTable(md: String): Boolean {
    val lines = md.lines()
    for (i in 1 until lines.size) {
        val sep = lines[i].trim()
        if (sep.contains('|') && sep.contains('-') && sep.all { it == '|' || it == '-' || it == ':' || it == ' ' } && lines[i - 1].contains('|'))
            return true
    }
    return false
}

/** Markdown estilo GitHub via WebView (tabelas, checklists, highlight), com altura automática. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun GithubMarkdown(src: String) {
    val dark = Fluwy.c.dark
    val height = remember(src) { androidx.compose.runtime.mutableStateOf(80.dp) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    AndroidView(
        modifier = Modifier.fillMaxWidth().height(height.value),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.allowFileAccess = true
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                addJavascriptInterface(object {
                    @android.webkit.JavascriptInterface fun source() = src
                    @android.webkit.JavascriptInterface fun dark() = dark
                    @android.webkit.JavascriptInterface fun setHeight(px: Int) {
                        post { height.value = with(density) { (px + 8).toDp() } }
                    }
                }, "FluwyMd")
                loadUrl("file:///android_asset/md/view.html")
            }
        },
    )
}

@Composable
private fun MdBlock(src: String) {
    val c = Fluwy.c
    val t = MaterialTheme.typography
    SelectionContainer {
        com.mikepenz.markdown.m3.Markdown(
            content = src.trim(),
            colors = markdownColor(text = c.ink, codeText = c.ink, linkText = c.accent, codeBackground = c.surface2,
                inlineCodeBackground = c.surface2, dividerColor = c.line),
            typography = markdownTypography(
                h1 = t.headlineSmall.copy(fontSize = 22.sp), h2 = t.titleLarge.copy(fontSize = 19.sp), h3 = t.titleMedium,
                h4 = t.titleMedium.copy(fontSize = 15.sp), h5 = t.bodyLarge.copy(fontWeight = FontWeight.SemiBold), h6 = t.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                text = t.bodyLarge, paragraph = t.bodyLarge, bullet = t.bodyLarge, list = t.bodyLarge, ordered = t.bodyLarge,
                quote = t.bodyLarge.copy(color = c.ink2),
                code = t.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                inlineCode = t.bodyLarge.copy(fontFamily = FontFamily.Monospace, fontSize = 14.sp),
            ),
            components = markdownComponents(codeBlock = highlightedCodeBlock, codeFence = highlightedCodeFence),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Diagrama Mermaid (mindmap, flowchart, sequence…) renderizado localmente. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun MermaidCard(code: String) {
    val c = Fluwy.c
    val dark = c.dark
    AndroidView(
        modifier = Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(14.dp)).background(c.surface2),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                val html = """
                    <html><head><meta name="viewport" content="width=device-width,initial-scale=1"><script src="file:///android_asset/md/mermaid.min.js"></script>
                    <style>body{margin:0;display:flex;justify-content:center;align-items:center;min-height:100vh;background:transparent}</style></head>
                    <body><pre class="mermaid">${code.replace("<", "&lt;")}</pre>
                    <script>mermaid.initialize({startOnLoad:true,theme:'${if (dark) "dark" else "default"}',securityLevel:'loose'});</script></body></html>
                """.trimIndent()
                settings.allowFileAccess = true
                loadDataWithBaseURL("file:///android_asset/md/", html, "text/html", "utf-8", null)
            }
        },
    )
}

/** Preview de documento Markdown (artefatos .md): visual idêntico ao GitHub, com highlight e mermaid. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MarkdownDocView(source: String, modifier: Modifier = Modifier) {
    val dark = Fluwy.c.dark
    AndroidView(modifier = modifier, factory = { ctx ->
        WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = true
            setBackgroundColor(if (dark) 0xFF0D1117.toInt() else android.graphics.Color.WHITE)
            addJavascriptInterface(object {
                @JavascriptInterface fun source(): String = source
                @JavascriptInterface fun dark(): Boolean = dark
            }, "FluwyMd")
            loadUrl("file:///android_asset/md/view.html")
        }
    })
}
