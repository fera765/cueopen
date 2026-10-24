package dev.cueopen.app.ui

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
import androidx.compose.ui.viewinterop.AndroidView
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.highlightedCodeBlock
import com.mikepenz.markdown.compose.elements.highlightedCodeFence
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import dev.cueopen.app.ui.theme.Cue
import androidx.compose.material3.MaterialTheme

private val mermaidFence = Regex("```mermaid\\s*\\n([\\s\\S]*?)```")

/**
 * Markdown estilo GitHub no chat: títulos, listas, task lists, tabelas, citações, links e blocos de
 * código com syntax highlighting (renderizador nativo, leve para listas longas). Blocos ```mermaid
 * (mapas mentais, fluxogramas, diagramas) são desenhados por um mini WebView com mermaid.js.
 */
@Composable
fun CueMarkdown(text: String, modifier: Modifier = Modifier) {
    val parts = remember(text) {
        val out = mutableListOf<Pair<Boolean, String>>() // (isMermaid, content)
        var last = 0
        mermaidFence.findAll(text).forEach { m ->
            if (m.range.first > last) out += false to text.substring(last, m.range.first)
            out += true to m.groupValues[1]
            last = m.range.last + 1
        }
        if (last < text.length) out += false to text.substring(last)
        out.filter { it.second.isNotBlank() }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        parts.forEach { (mermaid, content) ->
            if (mermaid) MermaidCard(content) else MdBlock(content)
        }
    }
}

@Composable
private fun MdBlock(src: String) {
    val c = Cue.c
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
    val c = Cue.c
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
    val dark = Cue.c.dark
    AndroidView(modifier = modifier, factory = { ctx ->
        WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = true
            setBackgroundColor(if (dark) 0xFF0D1117.toInt() else android.graphics.Color.WHITE)
            addJavascriptInterface(object {
                @JavascriptInterface fun source(): String = source
                @JavascriptInterface fun dark(): Boolean = dark
            }, "CueMd")
            loadUrl("file:///android_asset/md/view.html")
        }
    })
}
