package dev.cueopen.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import dev.cueopen.app.uiconnector.custom.Compute
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cueopen.app.ui.theme.Cue
import kotlin.math.max
import kotlin.math.min

@OptIn(ExperimentalLayoutApi::class)
/** Renderiza um elemento CueUI (SDK de elementos do chat). [onSend] envia uma mensagem ao agente. */
@Composable
fun CueWidgetView(w: CueWidget, onSend: ((String) -> Unit)?) {
    val c = Cue.c
    fun tone(t: String) = when (t.lowercase()) { "good", "success", "ok" -> c.ok; "bad", "error", "danger" -> c.err; "warn", "warning" -> c.warn; "accent", "primary" -> c.accent; else -> c.ink }
    val card = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(14.dp)).padding(14.dp)

    @Composable fun title(t: String) { if (t.isNotBlank()) { Text(t, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = c.ink); Spacer(Modifier.height(8.dp)) } }

    when (w) {
        is CueWidget.Skeleton -> Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(c.accent)); Spacer(Modifier.width(8.dp))
            Text("montando elemento…", color = c.ink3, fontSize = 13.sp)
        }

        is CueWidget.Buttons -> {
            val clip = LocalClipboardManager.current
            val ctx = LocalContext.current
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                w.items.forEach { b ->
                    val bg = if (b.tone == "neutral" || b.tone.isBlank()) c.surface2 else tone(b.tone).copy(alpha = 0.16f)
                    Row(Modifier.clip(RoundedCornerShape(10.dp)).background(bg).border(1.dp, c.line, RoundedCornerShape(10.dp))
                        .clickable {
                            when {
                                b.url.isNotBlank() -> runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(b.url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                                b.copy.isNotBlank() -> clip.setText(AnnotatedString(b.copy))
                                b.send.isNotBlank() -> onSend?.invoke(b.send)
                            }
                        }.padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (b.url.isNotBlank()) { Icon(Icons.Rounded.OpenInNew, null, Modifier.size(15.dp), tint = c.ink2); Spacer(Modifier.width(6.dp)) }
                        else if (b.copy.isNotBlank()) { Icon(Icons.Rounded.ContentCopy, null, Modifier.size(15.dp), tint = c.ink2); Spacer(Modifier.width(6.dp)) }
                        Text(b.label, fontSize = 14.sp, color = c.ink, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }

        is CueWidget.Chart -> Column(card) {
            title(w.title)
            ChartCanvas(w, tone = { tone(it) })
        }

        is CueWidget.Calculator -> Column(card) {
            title(w.title.ifBlank { "Calculadora" })
            val values = remember(w) { mutableStateMapOf<String, Double>().apply { w.vars.forEach { put(it.id, it.default) } } }
            w.vars.forEach { v ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(v.label, Modifier.weight(1f), fontSize = 14.sp, color = c.ink2)
                    IconStep(Icons.Rounded.Remove) { values[v.id] = ((values[v.id] ?: 0.0) - v.step).let { x -> v.min?.let { max(it, x) } ?: x } }
                    Text(Compute.strNum(values[v.id] ?: 0.0), Modifier.widthIn(min = 56.dp).padding(horizontal = 8.dp), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.ink)
                    IconStep(Icons.Rounded.Add) { values[v.id] = ((values[v.id] ?: 0.0) + v.step).let { x -> v.max?.let { min(it, x) } ?: x } }
                }
            }
            Spacer(Modifier.height(8.dp))
            val result = remember(w, values.toMap()) { CueUi.evalCalc(w.expr, values) }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.accent.copy(alpha = 0.14f)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Resultado", Modifier.weight(1f), fontSize = 13.sp, color = c.ink2)
                Text(result + (if (w.unit.isNotBlank()) " ${w.unit}" else ""), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = c.accent)
            }
        }

        is CueWidget.Table -> Column(card) {
            title(w.title)
            val widths = w.columns.indices.map { i -> (max(w.columns.getOrElse(i) { "" }.length, w.rows.maxOfOrNull { it.getOrNull(i)?.length ?: 0 } ?: 0) * 8 + 16).coerceIn(56, 240).dp }
            Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                Row { w.columns.forEachIndexed { i, h -> Text(h, Modifier.width(widths.getOrElse(i) { 100.dp }), fontSize = 11.sp, color = c.ink3, fontWeight = FontWeight.Medium) } }
                w.rows.forEach { r ->
                    Spacer(Modifier.height(6.dp))
                    Row { r.forEachIndexed { i, v -> Text(v, Modifier.width(widths.getOrElse(i) { 100.dp }), fontSize = 13.sp, color = c.ink) } }
                }
            }
        }

        is CueWidget.Stats -> Column(card) {
            title(w.title)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                w.items.forEach { st ->
                    Column(Modifier.clip(RoundedCornerShape(10.dp)).background(c.surface2).padding(12.dp).widthIn(min = 92.dp)) {
                        Text(st.value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = tone(st.tone))
                        Text(st.label, fontSize = 12.sp, color = c.ink3)
                    }
                }
            }
        }

        is CueWidget.Steps -> Column(card) {
            title(w.title)
            w.items.forEachIndexed { i, step ->
                Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
                    Box(Modifier.size(22.dp).clip(RoundedCornerShape(11.dp)).background(c.accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                        Text("${i + 1}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = c.accent)
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(step, fontSize = 14.sp, color = c.ink, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }

        is CueWidget.Progress -> Column(card) {
            title(w.title)
            w.items.forEach { p ->
                if (p.label.isNotBlank() || true) Row(Modifier.fillMaxWidth().padding(bottom = 3.dp)) {
                    Text(p.label, Modifier.weight(1f), fontSize = 13.sp, color = c.ink2)
                    Text("${p.value.toInt()}%", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = tone(p.tone))
                }
                Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(c.surface2)) {
                    Box(Modifier.fillMaxWidth((p.value / 100.0).toFloat().coerceIn(0f, 1f)).height(8.dp).clip(RoundedCornerShape(4.dp)).background(tone(p.tone).let { if (it == c.ink) c.accent else it }))
                }
                Spacer(Modifier.height(8.dp))
            }
        }

        is CueWidget.Callout -> {
            val t = tone(w.tone)
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(t.copy(alpha = 0.12f)).border(1.dp, t.copy(alpha = 0.4f), RoundedCornerShape(12.dp)).padding(12.dp)) {
                Box(Modifier.width(3.dp).heightIn(min = 20.dp).clip(RoundedCornerShape(2.dp)).background(t))
                Spacer(Modifier.width(10.dp))
                Column {
                    if (w.title.isNotBlank()) { Text(w.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = c.ink); Spacer(Modifier.height(2.dp)) }
                    if (w.text.isNotBlank()) Text(w.text, fontSize = 13.sp, color = c.ink2)
                }
            }
        }

        is CueWidget.KeyValue -> Column(card) {
            title(w.title)
            w.items.forEachIndexed { i, (k, v) ->
                if (i > 0) Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth()) {
                    Text(k, Modifier.weight(1f), fontSize = 13.sp, color = c.ink3)
                    Text(v, fontSize = 14.sp, color = c.ink, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun IconStep(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    val c = Cue.c
    Box(Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(c.surface2).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(16.dp), tint = c.ink2)
    }
}

@Composable
private fun ChartCanvas(w: CueWidget.Chart, tone: (String) -> Color) {
    val c = Cue.c
    val palette = listOf(c.accent, c.ok, c.warn, c.err, c.ink2)
    val series = w.series.filter { it.values.isNotEmpty() }
    if (series.isEmpty()) { Text("sem dados", color = c.ink3, fontSize = 13.sp); return }
    val allV = series.flatMap { it.values }
    val maxV = (allV.maxOrNull() ?: 1.0).let { if (it <= 0) 1.0 else it }
    val minV = min(0.0, allV.minOrNull() ?: 0.0)
    val range = (maxV - minV).let { if (it == 0.0) 1.0 else it }
    Canvas(Modifier.fillMaxWidth().height(140.dp)) {
        val w0 = size.width; val h0 = size.height; val pad = 6f
        fun y(v: Double) = (h0 - pad - ((v - minV) / range * (h0 - 2 * pad)).toFloat())
        if (w.style == "line") {
            series.forEachIndexed { si, s ->
                val col = s.color.takeIf { it.isNotBlank() }?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() } ?: palette[si % palette.size]
                val n = s.values.size
                val dx = if (n <= 1) 0f else (w0 - 2 * pad) / (n - 1)
                val path = Path()
                s.values.forEachIndexed { i, v -> val px = pad + dx * i; val py = y(v); if (i == 0) path.moveTo(px, py) else path.lineTo(px, py) }
                drawPath(path, col, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f))
            }
        } else { // bar
            val groups = series.firstOrNull()?.values?.size ?: 0
            if (groups > 0) {
                val gw = (w0 - 2 * pad) / groups
                val bw = gw / (series.size + 0.5f)
                for (g in 0 until groups) series.forEachIndexed { si, s ->
                    val v = s.values.getOrNull(g) ?: return@forEachIndexed
                    val col = s.color.takeIf { it.isNotBlank() }?.let { runCatching { Color(android.graphics.Color.parseColor(it)) }.getOrNull() } ?: palette[si % palette.size]
                    val left = pad + gw * g + bw * si + bw * 0.25f
                    val top = y(v); val base = y(0.0)
                    drawRect(col, topLeft = Offset(left, min(top, base)), size = androidx.compose.ui.geometry.Size(bw, kotlin.math.abs(base - top)))
                }
            }
        }
    }
    if (series.size > 1 || series.firstOrNull()?.name?.isNotBlank() == true) {
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            series.forEachIndexed { si, s ->
                if (s.name.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).clip(RoundedCornerShape(2.dp)).background(palette[si % palette.size])); Spacer(Modifier.width(4.dp))
                    Text(s.name, fontSize = 11.sp, color = c.ink3)
                }
            }
        }
    }
}
