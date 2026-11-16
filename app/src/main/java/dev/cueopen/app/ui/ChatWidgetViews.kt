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
import kotlinx.coroutines.launch
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
import dev.cueopen.app.CueApp
import dev.cueopen.app.uiconnector.UiBlock
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.Refresh
import kotlinx.coroutines.delay
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

        is CueWidget.ConnectorPanel -> {
            val hub = CueApp.instance.uiConnectors
            val conn = hub.get(w.id)
            var expanded by remember(w.id) { mutableStateOf(!w.collapsed) }
            var blocks by remember(w.id) { mutableStateOf<List<UiBlock>>(emptyList()) }
            var loading by remember(w.id) { mutableStateOf(false) }
            val scope = rememberCoroutineScope()
            suspend fun load() {
                if (conn == null) return
                loading = true
                blocks = runCatching { conn.snapshot(hub.settings(conn)) }.getOrElse { listOf(UiBlock.Note(it.message ?: "erro", dev.cueopen.app.uiconnector.Tone.BAD)) }
                loading = false
            }
            // atualiza ao vivo enquanto está aberto (usa o intervalo do conector)
            LaunchedEffect(w.id, expanded) { if (expanded && conn != null) { while (true) { load(); delay(conn.refreshMs().coerceIn(1000L, 60000L)) } } }
            Column(card) {
                Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
                    Text(w.title.ifBlank { conn?.spec?.name ?: w.id }, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = c.ink)
                    if (expanded && conn != null) Icon(Icons.Rounded.Refresh, "Atualizar", Modifier.size(18.dp).clickable { scope.launch { load() } }, tint = c.ink3)
                    Spacer(Modifier.width(6.dp))
                    Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (expanded) "Recolher" else "Expandir", Modifier.size(22.dp), tint = c.ink2)
                }
                if (conn == null) { Spacer(Modifier.height(6.dp)); Text("Conector '${w.id}' não encontrado ou desativado.", fontSize = 13.sp, color = c.err) }
                else if (expanded) {
                    Spacer(Modifier.height(10.dp))
                    if (blocks.isEmpty() && loading) Text("carregando…", fontSize = 13.sp, color = c.ink3)
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        blocks.forEach { b -> UiBlockView(b) { actionId, args -> scope.launch { runCatching { conn.onAction(actionId, args, hub.settings(conn)); load() } } } }
                    }
                }
            }
        }

        is CueWidget.Checklist -> Column(card) {
            title(w.title)
            w.items.forEach { (text, done) ->
                Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
                    Text(if (done) "☑" else "☐", color = if (done) c.ok else c.ink3, fontSize = 15.sp); Spacer(Modifier.width(8.dp))
                    Text(text, fontSize = 14.sp, color = if (done) c.ink2 else c.ink)
                }
            }
        }
        is CueWidget.Timeline -> Column(card) {
            title(w.title)
            w.items.forEachIndexed { i, (whenLabel, t, text) ->
                Row {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.padding(top = 5.dp).size(9.dp).clip(RoundedCornerShape(5.dp)).background(c.accent))
                        if (i < w.items.lastIndex) Box(Modifier.width(2.dp).height(34.dp).background(c.line))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.padding(bottom = 8.dp)) {
                        if (whenLabel.isNotBlank()) Text(whenLabel, fontSize = 11.sp, color = c.ink3)
                        if (t.isNotBlank()) Text(t, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = c.ink)
                        if (text.isNotBlank()) Text(text, fontSize = 13.sp, color = c.ink2)
                    }
                }
            }
        }
        is CueWidget.Badges -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            w.items.forEach { b ->
                val col = if (b.tone == "neutral" || b.tone.isBlank()) c.ink2 else tone(b.tone)
                Box(Modifier.clip(RoundedCornerShape(50)).background(col.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 4.dp)) {
                    Text(b.label, fontSize = 12.sp, color = col, fontWeight = FontWeight.Medium)
                }
            }
        }
        is CueWidget.Compare -> Column(card) {
            title(w.title)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                w.options.forEach { o ->
                    val col = if (o.tone == "neutral" || o.tone.isBlank()) c.line else tone(o.tone)
                    Column(Modifier.width(190.dp).clip(RoundedCornerShape(12.dp)).background(c.surface2).border(1.dp, col, RoundedCornerShape(12.dp)).padding(12.dp)) {
                        Text(o.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = c.ink)
                        Spacer(Modifier.height(6.dp))
                        o.items.forEach { Text("• $it", fontSize = 13.sp, color = c.ink2, lineHeight = 18.sp) }
                    }
                }
            }
        }
        is CueWidget.Files -> {
            val clip = LocalClipboardManager.current
            val open = LocalOpenFile.current
            Column(card) {
                title(w.title.ifBlank { "Arquivos" })
                w.items.forEach { (path, note) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(10.dp)).background(c.surface2)
                        .clickable { if (open != null) open(path) else clip.setText(AnnotatedString(path)) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("📄", fontSize = 16.sp); Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(path.substringAfterLast('/'), fontSize = 14.sp, color = c.ink, fontWeight = FontWeight.Medium)
                            Text(note.ifBlank { path }, fontSize = 11.sp, color = c.ink3, maxLines = 2)
                        }
                        Icon(Icons.Rounded.ContentCopy, "Copiar caminho", Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).clickable { clip.setText(AnnotatedString(path)) }.padding(6.dp), tint = c.ink3)
                    }
                }
            }
        }
        is CueWidget.Accordion -> Column(card) {
            w.items.forEachIndexed { i, (t, text) ->
                var open by remember(i, t) { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(t, Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = c.ink)
                    Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, Modifier.size(18.dp), tint = c.ink3)
                }
                if (open) Text(text, fontSize = 13.sp, color = c.ink2, lineHeight = 19.sp, modifier = Modifier.padding(bottom = 8.dp))
                if (i < w.items.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(c.line))
            }
        }
        is CueWidget.Tabs -> Column(card) {
            var sel by remember(w.tabs.size) { mutableIntStateOf(0) }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                w.tabs.forEachIndexed { i, (t, _) ->
                    Box(Modifier.clip(RoundedCornerShape(50)).background(if (sel == i) c.ink else c.surface2).clickable { sel = i }.padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Text(t, fontSize = 12.sp, color = if (sel == i) c.bg else c.ink2, fontWeight = FontWeight.Medium)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(w.tabs.getOrNull(sel)?.second.orEmpty(), fontSize = 14.sp, color = c.ink, lineHeight = 20.sp)
        }
        is CueWidget.Summary -> Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.accent.copy(alpha = 0.10f)).border(1.dp, c.accent.copy(alpha = 0.35f), RoundedCornerShape(14.dp)).padding(14.dp)) {
            Text(w.title.uppercase(), fontSize = 11.sp, color = c.accent, fontWeight = FontWeight.Bold)
            if (w.text.isNotBlank()) { Spacer(Modifier.height(6.dp)); Text(w.text, fontSize = 14.sp, color = c.ink, lineHeight = 20.sp) }
            w.bullets.forEach { Spacer(Modifier.height(4.dp)); Text("• $it", fontSize = 13.sp, color = c.ink2, lineHeight = 18.sp) }
        }
        is CueWidget.Divider -> Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).height(1.dp).background(c.line))
            if (w.label.isNotBlank()) { Text(w.label.uppercase(), fontSize = 10.sp, color = c.ink3, modifier = Modifier.padding(horizontal = 10.dp)); Box(Modifier.weight(1f).height(1.dp).background(c.line)) }
        }
        is CueWidget.Group -> Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            if (w.title.isNotBlank()) Text(w.title, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = c.ink)
            w.blocks.forEach { CueWidgetView(it, onSend) }
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
@OptIn(ExperimentalLayoutApi::class)
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
