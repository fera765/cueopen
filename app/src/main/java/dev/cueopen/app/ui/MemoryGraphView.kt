package dev.cueopen.app.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cueopen.app.agent.MemoryGraph
import dev.cueopen.app.data.AgentProfile
import dev.cueopen.app.data.MemoryItem
import dev.cueopen.app.ui.theme.Cue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Cor de cada categoria, tirada da paleta do app. */
@Composable
fun memoryKindColor(kind: String): Color {
    val c = Cue.c
    return when (kind) {
        "pessoa" -> c.accent
        "projeto" -> Color(0xFFD94E8F)
        "trabalho" -> c.ok
        "preferencia" -> c.warn
        "ferramenta" -> c.err
        "lugar" -> c.ink2
        "rotina" -> c.ink3
        else -> c.ink
    }
}

fun memoryKindLabel(kind: String) = when (kind) {
    "pessoa" -> "Pessoas"; "projeto" -> "Projetos"; "trabalho" -> "Trabalho"; "preferencia" -> "Preferências"
    "ferramenta" -> "Ferramentas"; "lugar" -> "Lugares"; "rotina" -> "Rotina"; else -> "Outros"
}

/**
 * Segundo cérebro: grafo de notas (memórias) com ligações, aglomerados por categoria e os agentes
 * orbitando o mapa ligados às notas deles. Pinça = zoom, arrastar = mover, toque = abrir a nota.
 */
@Composable
fun MemoryBrainView(memories: List<MemoryItem>, agents: List<AgentProfile>, modifier: Modifier = Modifier, onDelete: (String) -> Unit) {
    val c = Cue.c
    val edges = remember(memories) { MemoryGraph.edges(memories) }
    val kinds = remember(memories) { memories.associate { it.id to MemoryGraph.kindOf(it) } }
    val degree = remember(edges) { MemoryGraph.degrees(edges) }
    var pos by remember { mutableStateOf<Map<String, MemoryGraph.P>>(emptyMap()) }
    LaunchedEffect(memories.map { it.id }, edges.size) {
        pos = withContext(Dispatchers.Default) { MemoryGraph.layout(memories.map { it.id }, kinds, edges) }
    }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var selected by remember { mutableStateOf<String?>(null) }
    var filterKind by remember { mutableStateOf<String?>(null) }
    val byId = remember(memories) { memories.associateBy { it.id } }
    val sel = selected?.let { byId[it] }
    val selNeighbors = remember(selected, edges) { selected?.let { MemoryGraph.neighbors(it, edges) }.orEmpty() }
    val pulse by rememberInfiniteTransition(label = "live").animateFloat(0.3f, 1f, infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "p")

    val kindColors = MemoryGraph.KINDS.associateWith { memoryKindColor(it) }
    val agentsWithNotes = remember(memories, agents) {
        agents.filter { a -> memories.any { it.agentId == a.id } }.ifEmpty { agents.take(6) }.take(6)
    }

    fun toScreen(p: MemoryGraph.P): Offset {
        val r = minOf(size.width, size.height) * 0.40f
        return Offset(size.width / 2f + p.x * r * scale + pan.x, size.height / 2f + p.y * r * scale + pan.y)
    }
    fun radius(id: String): Float {
        val m = byId[id]
        return (3.5f + sqrt((degree[id] ?: 0).toFloat()) * 2.6f + (m?.uses ?: 0).coerceAtMost(20) * 0.25f) * scale.coerceIn(0.7f, 2f)
    }

    Column(modifier.fillMaxSize().background(c.bg)) {
        Box(Modifier.weight(1f).fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(20.dp)).background(c.surface)
            .border(1.dp, c.line, RoundedCornerShape(20.dp)).onSizeChanged { size = it }) {
            Canvas(Modifier.fillMaxSize()
                .pointerInput(Unit) { detectTransformGestures { centroid, p, zoom, _ ->
                    val ns = (scale * zoom).coerceIn(0.4f, 6f)
                    val f = ns / scale
                    val center = Offset(size.width / 2f, size.height / 2f)
                    pan = (pan + center - centroid) * f - (center - centroid) + p
                    scale = ns
                } }
                .pointerInput(memories, pos) { detectTapGestures(onDoubleTap = { scale = 1f; pan = Offset.Zero }) { t ->
                    val hit = pos.entries.minByOrNull { (toScreen(it.value) - t).getDistance() }
                    selected = if (hit != null && (toScreen(hit.value) - t).getDistance() < radius(hit.key) + 22f) hit.key else null
                } }) {
                if (pos.isEmpty()) return@Canvas
                val focus = selected
                // ligações
                for (e in edges) {
                    val a = pos[e.a] ?: continue; val b = pos[e.b] ?: continue
                    val on = focus != null && (e.a == focus || e.b == focus)
                    val dim = (focus != null && !on) || (filterKind != null && kinds[e.a] != filterKind && kinds[e.b] != filterKind)
                    val col = when {
                        on -> c.accent
                        e.explicit -> (kindColors[kinds[e.a]] ?: c.ink3).copy(alpha = 0.45f)
                        else -> c.ink3.copy(alpha = 0.28f)
                    }
                    drawLine(if (dim) col.copy(alpha = 0.06f) else col, toScreen(a), toScreen(b), strokeWidth = if (on) 2.4f else 1f,
                        pathEffect = if (on && !e.explicit) PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null)
                }
                // nós
                for ((id, p) in pos) {
                    val k = kinds[id] ?: "outro"
                    val dim = (focus != null && id != focus && id !in selNeighbors) || (filterKind != null && k != filterKind)
                    val col = kindColors[k] ?: c.ink
                    val r = radius(id)
                    val o = toScreen(p)
                    if (o.x < -40 || o.y < -40 || o.x > size.width + 40 || o.y > size.height + 40) continue
                    val recent = byId[id]?.let { System.currentTimeMillis() - it.createdAt < 86_400_000L } == true
                    if (recent && !dim) drawCircle(col.copy(alpha = 0.25f * pulse), r + 7f, o)
                    drawCircle(if (dim) col.copy(alpha = 0.12f) else col, r, o)
                    if (id == focus) drawCircle(c.ink, r + 4f, o, style = Stroke(2f))
                }
                // agentes: linhas tracejadas até o centro das notas de cada um
                agentsWithNotes.forEachIndexed { i, a ->
                    val anchor = agentAnchor(i, agentsWithNotes.size, size)
                    val own = memories.filter { it.agentId == a.id }.mapNotNull { pos[it.id] }
                    val target = if (own.isEmpty()) Offset(size.width / 2f, size.height / 2f)
                        else toScreen(MemoryGraph.P(own.map { it.x }.average().toFloat(), own.map { it.y }.average().toFloat()))
                    drawLine(c.ink3.copy(alpha = 0.5f), anchor, target, 1.2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 8f)))
                }
            }
            // rótulos dos aglomerados (centro de cada categoria)
            if (pos.isNotEmpty()) MemoryGraph.KINDS.forEach { k ->
                val ps = pos.filterKeys { kinds[it] == k }.values
                if (ps.size >= 3) {
                    val o = toScreen(MemoryGraph.P(ps.map { it.x }.average().toFloat(), ps.map { it.y }.average().toFloat()))
                    Text(memoryKindLabel(k).uppercase(), Modifier.offset { IntOffset(o.x.roundToInt() - 40, o.y.roundToInt() - 8) },
                        fontSize = 9.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp, color = (kindColors[k] ?: c.ink3).copy(alpha = 0.85f))
                }
            }
            // rótulo da nota selecionada e dos hubs quando ampliado
            pos.forEach { (id, p) ->
                val show = id == selected || (scale > 1.8f && (degree[id] ?: 0) >= 3) || (selected == null && filterKind == null && (degree[id] ?: 0) >= maxOf(4, (degree.values.maxOrNull() ?: 0) - 1))
                if (show) {
                    val o = toScreen(p)
                    Text(MemoryGraph.titleOf(byId[id] ?: return@forEach), Modifier.offset { IntOffset(o.x.roundToInt() + 10, o.y.roundToInt() - 12) }
                        .clip(RoundedCornerShape(8.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
                        fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = c.ink, maxLines = 1)
                }
            }
            // agentes em volta do mapa
            agentsWithNotes.forEachIndexed { i, a ->
                val o = agentAnchor(i, agentsWithNotes.size, size)
                Column(Modifier.offset { IntOffset(o.x.roundToInt() - 36, o.y.roundToInt() - 18) }.width(72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(c.ink), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.SmartToy, null, Modifier.size(17.dp), tint = c.bg)
                    }
                    Text(a.name, fontSize = 10.sp, fontWeight = FontWeight.Medium, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            // cabeçalho "MAPA DE MEMÓRIA · AO VIVO"
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(c.ok.copy(alpha = pulse)))
                Spacer(Modifier.width(6.dp))
                Text("MAPA DE MEMÓRIA · AO VIVO", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = c.ink3, letterSpacing = 1.sp)
                Spacer(Modifier.weight(1f))
                Text("${memories.size} NOTAS · ${edges.size} LIGAÇÕES", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = c.accent, letterSpacing = 1.sp)
            }
            if (memories.isEmpty()) Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.Hub, null, Modifier.size(40.dp), tint = c.ink3)
                Text("Seu segundo cérebro está vazio", fontWeight = FontWeight.SemiBold, color = c.ink)
                Text("As notas aparecem aqui conforme os agentes aprendem sobre você.", fontSize = 12.sp, color = c.ink2)
            }
        }
        // legenda/filtro por categoria
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MemoryGraph.KINDS.filter { k -> kinds.values.any { it == k } }.forEach { k ->
                val on = filterKind == k
                Row(Modifier.clip(CircleShape).background(if (on) c.accentSoft else c.surface2).clickable { filterKind = if (on) null else k }
                    .padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(kindColors[k] ?: c.ink))
                    Spacer(Modifier.width(5.dp))
                    Text("${memoryKindLabel(k)} ${kinds.values.count { it == k }}", fontSize = 11.sp, color = c.ink2)
                }
            }
        }
        // nota aberta
        if (sel != null) Column(Modifier.fillMaxWidth().heightIn(max = 260.dp).padding(12.dp).clip(RoundedCornerShape(18.dp)).background(c.surface)
            .border(1.dp, c.line, RoundedCornerShape(18.dp)).verticalScroll(rememberScrollState()).padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(kindColors[kinds[sel.id]] ?: c.ink))
                Spacer(Modifier.width(8.dp))
                Text(MemoryGraph.titleOf(sel), fontWeight = FontWeight.Bold, fontSize = 16.sp, color = c.ink, modifier = Modifier.weight(1f))
                IconButton({ onDelete(sel.id); selected = null }, Modifier.size(32.dp)) { Icon(Icons.Rounded.DeleteOutline, "Excluir", Modifier.size(18.dp), tint = c.ink3) }
                IconButton({ selected = null }, Modifier.size(32.dp)) { Icon(Icons.Rounded.Close, "Fechar", Modifier.size(18.dp), tint = c.ink3) }
            }
            Text(MemoryGraph.plain(sel.text), fontSize = 14.sp, lineHeight = 20.sp, color = c.ink)
            Spacer(Modifier.height(6.dp))
            val agentName = sel.agentId?.let { id -> agents.firstOrNull { it.id == id }?.name }
            Text(listOfNotNull(memoryKindLabel(kinds[sel.id] ?: "outro"), agentName?.let { "⬡ $it" } ?: "Global",
                sel.tags.takeIf { it.isNotEmpty() }?.joinToString(" · "), if (sel.uses > 0) "${sel.uses}× usada" else null).joinToString("  ·  "), fontSize = 11.sp, color = c.ink3)
            if (selNeighbors.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("LIGAÇÕES", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = c.ink3, letterSpacing = 1.sp)
                Spacer(Modifier.height(4.dp))
                selNeighbors.mapNotNull { byId[it] }.forEach { n ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { selected = n.id }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(kindColors[kinds[n.id]] ?: c.ink))
                        Spacer(Modifier.width(8.dp))
                        Text("[[${MemoryGraph.titleOf(n)}]]", fontSize = 13.sp, color = c.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** Posição de cada agente em volta do mapa (cantos e laterais, como satélites). */
private fun agentAnchor(i: Int, n: Int, size: IntSize): Offset {
    val slots = listOf(0.10f to 0.14f, 0.90f to 0.18f, 0.08f to 0.70f, 0.92f to 0.76f, 0.18f to 0.92f, 0.82f to 0.94f)
    val (fx, fy) = slots[i % slots.size]
    return Offset(size.width * fx, size.height * fy)
}
