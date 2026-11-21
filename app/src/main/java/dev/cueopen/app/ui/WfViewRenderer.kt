package dev.cueopen.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import dev.cueopen.app.ui.theme.Cue
import kotlinx.serialization.json.*

/** Renderizador nativo do UI kit de notificações (ver WfView). [compact] = quadro; senão página inteira. */
@Composable
fun WfViewContent(viewJson: String, compact: Boolean = false, onRoute: (String) -> Unit = {}) {
    val blocks = remember(viewJson) { dev.cueopen.app.workflow.WfView.blocksOf(viewJson) }
    Column(verticalArrangement = Arrangement.spacedBy(if (compact) 10.dp else 14.dp)) {
        blocks.forEach { b -> WfBlock(b, compact, onRoute) }
    }
}

private fun JsonObject.s(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.trim()?.takeIf { it.isNotEmpty() }
private fun JsonObject.arr(k: String): List<JsonElement> = (this[k] as? JsonArray).orEmpty()
private fun JsonElement.txt(): String = (this as? JsonPrimitive)?.contentOrNull ?: toString()

private val palette = listOf(0xFF7C5CFF, 0xFF2FBF71, 0xFFFF6B3D, 0xFF4D8DFF, 0xFFFF5C8A, 0xFFFFB020, 0xFF2EC4C4, 0xFF9B7BFF)
private fun colorFor(seed: String) = Color(palette[(seed.hashCode() and Int.MAX_VALUE) % palette.size])

@Composable
private fun toneColor(t: String?) = when (t) { "ok", "success" -> Cue.c.ok; "warn", "warning" -> Cue.c.warn; "err", "error", "danger" -> Cue.c.err; else -> Cue.c.accent }

@Composable
private fun openUrl(): (String) -> Unit {
    val ctx = LocalContext.current
    return { u -> runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
}

@Composable
private fun Img(url: String, modifier: Modifier, desc: String? = null) {
    SubcomposeAsyncImage(url, desc, modifier, contentScale = ContentScale.Crop,
        loading = { Box(Modifier.fillMaxSize().background(Cue.c.surface2)) },
        error = { Box(Modifier.fillMaxSize().background(Cue.c.surface2), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.BrokenImage, null, tint = Cue.c.ink3) } })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WfBlock(b: JsonObject, compact: Boolean, onRoute: (String) -> Unit) {
    val c = Cue.c
    val open = openUrl()
    when (b.s("type")) {
        "header" -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            b.s("image")?.let { Img(it, Modifier.fillMaxWidth().height(if (compact) 120.dp else 180.dp).clip(RoundedCornerShape(18.dp))) }
            b.s("title")?.let { Text(it, fontSize = if (compact) 19.sp else 24.sp, fontWeight = FontWeight.Bold, lineHeight = if (compact) 24.sp else 30.sp) }
            b.s("subtitle")?.let { Text(it, fontSize = 13.sp, color = c.ink3) }
        }
        "text", "markdown" -> b.s("text")?.let { CueMarkdown(it) }
        "card" -> WfCard(b, compact, open)
        "list" -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            b.s("title")?.let { SectionLabel(it) }
            b.arr("items").filterIsInstance<JsonObject>().forEach { WfCard(it, compact, open) }
        }
        "image" -> b.s("url")?.let { u ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Img(u, Modifier.fillMaxWidth().heightIn(min = 120.dp, max = if (compact) 200.dp else 280.dp).clip(RoundedCornerShape(16.dp)).clickable { open(u) }, b.s("caption"))
                b.s("caption")?.let { Text(it, fontSize = 12.sp, color = c.ink3) }
            }
        }
        "gallery" -> Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            b.arr("images").mapNotNull { (it as? JsonPrimitive)?.contentOrNull ?: (it as? JsonObject)?.s("url") }.forEach { u ->
                Img(u, Modifier.size(if (compact) 96.dp else 128.dp).clip(RoundedCornerShape(14.dp)).clickable { open(u) })
            }
        }
        "kv" -> Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface2).padding(horizontal = 14.dp, vertical = 6.dp)) {
            b.s("title")?.let { Text(it, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 6.dp)) }
            val items = b.arr("items").filterIsInstance<JsonObject>()
            items.forEachIndexed { i, it ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
                    Text(it.s("label").orEmpty(), fontSize = 13.sp, color = c.ink3, modifier = Modifier.weight(0.42f))
                    Text(it.s("value").orEmpty(), fontSize = 14.sp, modifier = Modifier.weight(0.58f))
                }
                if (i < items.lastIndex) HorizontalDivider(color = c.line)
            }
        }
        "stats" -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            b.arr("items").filterIsInstance<JsonObject>().take(4).forEach { st ->
                Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(c.surface2).padding(12.dp)) {
                    Text(st.s("value").orEmpty(), fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(st.s("label").orEmpty(), fontSize = 11.sp, color = c.ink3, maxLines = 2)
                    st.s("delta")?.let { d -> Text(d, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = if (d.trim().startsWith("-")) c.err else c.ok) }
                }
            }
        }
        "table" -> {
            val cols = b.arr("columns").map { it.txt() }
            val rows = b.arr("rows").mapNotNull { (it as? JsonArray)?.map { x -> x.txt() } }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).border(1.dp, c.line, RoundedCornerShape(14.dp)).horizontalScroll(rememberScrollState())) {
                Row(Modifier.background(c.surface2)) { cols.forEach { Text(it, Modifier.width(130.dp).padding(10.dp), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = c.ink2) } }
                rows.forEachIndexed { i, r ->
                    Row(Modifier.background(if (i % 2 == 1) c.surface2.copy(alpha = .45f) else Color.Transparent)) {
                        r.forEach { Text(it, Modifier.width(130.dp).padding(10.dp), fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
        }
        "chips" -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            b.arr("items").forEach { Text(it.txt(), Modifier.clip(RoundedCornerShape(10.dp)).background(c.surface2).padding(horizontal = 10.dp, vertical = 5.dp), fontSize = 12.sp, color = c.ink2) }
        }
        "quote" -> Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(c.accent))
            Column(Modifier.padding(start = 12.dp)) {
                Text(b.s("text").orEmpty(), fontStyle = FontStyle.Italic, fontSize = 15.sp, color = c.ink2)
                b.s("author")?.let { Text("— $it", fontSize = 12.sp, color = c.ink3) }
            }
        }
        "alert" -> {
            val col = toneColor(b.s("tone"))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(col.copy(alpha = .12f)).border(1.dp, col.copy(alpha = .4f), RoundedCornerShape(14.dp)).padding(12.dp)) {
                Icon(when (b.s("tone")) { "ok", "success" -> Icons.Rounded.CheckCircle; "warn", "warning" -> Icons.Rounded.Warning; "err", "error", "danger" -> Icons.Rounded.Error; else -> Icons.Rounded.Info }, null, tint = col)
                Spacer(Modifier.width(10.dp))
                Column {
                    b.s("title")?.let { Text(it, fontWeight = FontWeight.SemiBold, color = col) }
                    b.s("text")?.let { Text(it, fontSize = 14.sp) }
                }
            }
        }
        "button" -> {
            val url = b.s("url"); val route = b.s("route")
            Button({ if (route != null) onRoute(route) else url?.let(open) }, colors = ButtonDefaults.buttonColors(containerColor = toneColor(b.s("tone"))), modifier = Modifier.fillMaxWidth()) {
                Text(b.s("label") ?: "Abrir")
                if (url != null) { Spacer(Modifier.width(6.dp)); Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(16.dp)) }
            }
        }
        "section" -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            b.s("title")?.let { SectionLabel(it) }
            b.arr("blocks").filterIsInstance<JsonObject>().forEach { WfBlock(it, compact, onRoute) }
        }
        "progress" -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val v = b.s("value")?.replace(',', '.')?.toFloatOrNull()?.let { if (it > 1f) it / 100f else it }?.coerceIn(0f, 1f) ?: 0f
            Row { Text(b.s("label").orEmpty(), fontSize = 13.sp, modifier = Modifier.weight(1f)); Text("${(v * 100).toInt()}%", fontSize = 12.sp, color = c.ink3) }
            LinearProgressIndicator(progress = { v }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = toneColor(b.s("tone")), trackColor = c.line)
        }
        "divider" -> HorizontalDivider(color = c.line)
        else -> b.s("text")?.let { CueMarkdown(it) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WfCard(o: JsonObject, compact: Boolean, open: (String) -> Unit) {
    val c = Cue.c
    var expanded by remember { mutableStateOf(false) }
    val url = o.s("url")
    val tone = o.s("tone")
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface)
        .border(1.dp, if (tone != null) toneColor(tone).copy(alpha = .5f) else c.line, RoundedCornerShape(18.dp))
        .clickable { expanded = !expanded }) {
        o.s("image")?.let { Img(it, Modifier.fillMaxWidth().height(if (compact) 130.dp else 170.dp)) }
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                o.s("avatar")?.let { a ->
                    if (a.startsWith("http")) Img(a, Modifier.size(38.dp).clip(CircleShape))
                    else Box(Modifier.size(38.dp).clip(CircleShape).background(colorFor(a).copy(alpha = .18f)), contentAlignment = Alignment.Center) {
                        Text(a.trim().take(1).uppercase(), fontWeight = FontWeight.Bold, color = colorFor(a))
                    }
                    Spacer(Modifier.width(10.dp))
                }
                Column(Modifier.weight(1f)) {
                    if (o.s("avatar") != null && o.s("subtitle") != null) {
                        // E-mail: remetente em cima, assunto em destaque embaixo.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(o.s("subtitle")!!, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            o.s("meta")?.let { Spacer(Modifier.width(8.dp)); Text(it, fontSize = 11.sp, color = c.ink3, maxLines = 1) }
                        }
                    } else {
                        o.s("meta")?.let { Text(it, fontSize = 11.sp, color = c.ink3, maxLines = 1) }
                    }
                }
                o.s("badge")?.let { Text(it, Modifier.clip(RoundedCornerShape(8.dp)).background(toneColor(tone).copy(alpha = .14f)).padding(horizontal = 7.dp, vertical = 2.dp),
                    fontSize = 11.sp, color = toneColor(tone), fontWeight = FontWeight.Medium) }
            }
            o.s("title")?.let { Text(it, fontSize = if (compact) 15.sp else 16.sp, fontWeight = FontWeight.SemiBold, maxLines = if (expanded) 6 else 2, overflow = TextOverflow.Ellipsis) }
            if (o.s("avatar") == null) o.s("subtitle")?.let { Text(it, fontSize = 13.sp, color = c.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            o.s("body")?.let { Text(it, fontSize = 13.sp, color = c.ink2, maxLines = if (expanded) 40 else 3, overflow = TextOverflow.Ellipsis, lineHeight = 19.sp) }
            val chips = o.arr("chips").map { it.txt() }
            if (chips.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                chips.forEach { Text(it, Modifier.clip(RoundedCornerShape(8.dp)).background(c.surface2).padding(horizontal = 8.dp, vertical = 3.dp), fontSize = 11.sp, color = c.ink2) }
            }
            if (url != null) Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable { open(url) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Abrir", fontSize = 13.sp, color = c.accent, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(4.dp)); Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(14.dp), tint = c.accent)
            }
        }
    }
}
