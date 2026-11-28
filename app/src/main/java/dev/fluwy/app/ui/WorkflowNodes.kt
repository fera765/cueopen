package dev.fluwy.app.ui
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.fluwy.app.FluwyApp
import dev.fluwy.app.ui.theme.Fluwy
import dev.fluwy.app.workflow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Paleta: busca + nós prontos + tipos básicos, por categoria. */
@Composable
internal fun NodePalette(onPick: (String, WfPreset?) -> Unit) {
    val c = Fluwy.c
    var q by remember { mutableStateOf("") }
    val norm = { s: String -> java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "") }
    Column(Modifier.padding(16.dp, 0.dp, 16.dp, 28.dp)) {
        Text("Adicionar nó", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), placeholder = { Text("Buscar: gmail, resumo, http, condição…") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true, shape = RoundedCornerShape(14.dp))
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.heightIn(max = 560.dp)) {
            WfCatalog.categories().forEach { cat ->
                val basics = WfCatalog.TYPES.filter { it.category == cat && !it.hidden }.map { Triple(it.type, null as WfPreset?, it.title to it.description) }
                val presets = WfCatalog.PRESETS.filter { it.category == cat }.map { Triple(it.type, it, it.title to it.description) }
                val all = (basics + presets).filter { q.isBlank() || norm(it.third.first + " " + it.third.second).contains(norm(q.trim())) }
                if (all.isNotEmpty()) {
                    item(key = "h_$cat") { SectionLabel(cat) }
                    items(all, key = { cat + it.third.first }) { (type, preset, td) ->
                        val t = WfCatalog.of(type)
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onPick(type, preset) }.padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Color(t.color).copy(alpha = .16f)), contentAlignment = Alignment.Center) {
                                Icon(nodeIcon(type), null, tint = Color(t.color), modifier = Modifier.size(22.dp))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(td.first, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                    if (preset != null) {
                                        Spacer(Modifier.width(6.dp))
                                        Text("pronto", fontSize = 10.sp, color = Color(t.color), modifier = Modifier.clip(RoundedCornerShape(6.dp))
                                            .background(Color(t.color).copy(alpha = .14f)).padding(horizontal = 5.dp, vertical = 1.dp))
                                    }
                                }
                                Text(td.second, fontSize = 12.sp, color = c.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun Banner(text: String, color: Color, onClick: () -> Unit) {
    Text(text, Modifier.clip(RoundedCornerShape(12.dp)).background(Fluwy.c.surface).border(1.dp, color.copy(alpha = .6f), RoundedCornerShape(12.dp))
        .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp), color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium)
}

@Composable
internal fun NodeCard(n: WfNode, status: String?, selected: Boolean, targetable: Boolean, source: Boolean,
                     onTap: () -> Unit, onDrag: (Float, Float) -> Unit, onDragEnd: () -> Unit, onPort: (String) -> Unit) {
    val c = Fluwy.c
    val t = WfCatalog.of(n.type)
    val tint = Color(t.color)
    // Os gestos ficam vivos entre recomposições: sempre chamam a versão atual (posição e fluxo atualizados).
    val tap by rememberUpdatedState(onTap)
    val drag by rememberUpdatedState(onDrag)
    val dragEnd by rememberUpdatedState(onDragEnd)
    val pulse = rememberInfiniteTransition(label = "wfpulse")
    val a by pulse.animateFloat(0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a")
    val st = statusColor(status, c)
    val border = st?.let { if (status == "running" || status == "waiting") it.copy(alpha = a) else it }
        ?: if (selected || source) c.accent else if (targetable) tint.copy(alpha = a) else c.line
    val shape = RoundedCornerShape(if (t.trigger) 22.dp else 16.dp)
    Box(Modifier.size(NODE_W.dp, NODE_H.dp)) {
        Row(Modifier.fillMaxSize().clip(shape).background(c.surface)
            .border(if (selected || st != null || source || targetable) 2.dp else 1.dp, border, shape)
            .pointerInput(n.id) { detectDragGestures(onDragEnd = { dragEnd() }, onDragCancel = { dragEnd() }) { ch, d -> ch.consume(); drag(d.x, d.y) } }
            .pointerInput(n.id) { detectTapGestures { tap() } }) {
            // Faixa de cor do tipo à esquerda.
            Box(Modifier.width(5.dp).fillMaxHeight().background(tint))
            Row(Modifier.weight(1f).fillMaxHeight().padding(start = 10.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = .16f)), contentAlignment = Alignment.Center) {
                    if (status == "running") CircularProgressIndicator(Modifier.size(22.dp), color = tint, strokeWidth = 2.5.dp)
                    else Icon(nodeIcon(n.type), null, tint = tint, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.title.uppercase(), fontSize = 9.sp, color = tint, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp, maxLines = 1)
                    Text(WfLogic.label(n), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(subtitle(n), fontSize = 11.sp, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                when (status) {
                    "ok" -> Icon(Icons.Rounded.CheckCircle, null, Modifier.size(18.dp), tint = c.ok)
                    "error" -> Icon(Icons.Rounded.Error, null, Modifier.size(18.dp), tint = c.err)
                    "waiting" -> Icon(Icons.Rounded.HourglassTop, null, Modifier.size(18.dp), tint = c.warn)
                }
            }
        }
        // Entrada (esquerda) e saídas (direita). Toque numa saída e depois no nó de destino para ligar.
        if (!t.trigger) Box(Modifier.align(Alignment.CenterStart).offset(x = (-7).dp).size(14.dp).clip(CircleShape).background(c.surface).border(2.dp, c.ink3, CircleShape))
        t.ports.forEachIndexed { i, port ->
            val y = if (t.ports.size > 1) (if (i == 0) NODE_H * 0.32f else NODE_H * 0.70f) else NODE_H / 2
            val col = when (port) { "true" -> c.ok; "false" -> c.err; else -> tint }
            Box(Modifier.offset { IntOffset((NODE_W - 15).dp.roundToPx(), (y - 15).dp.roundToPx()) }.size(30.dp).clip(CircleShape)
                .clickable { onPort(port) }, contentAlignment = Alignment.Center) {
                Box(Modifier.size(16.dp).clip(CircleShape).background(col).border(3.dp, c.surface, CircleShape))
            }
            if (port != "main") Text(if (port == "true") "sim" else "não", Modifier.offset { IntOffset((NODE_W + 16).dp.roundToPx(), (y - 8).dp.roundToPx()) }
                .clip(RoundedCornerShape(6.dp)).background(col.copy(alpha = .15f)).padding(horizontal = 5.dp), fontSize = 10.sp, color = col, fontWeight = FontWeight.Bold)
        }
    }
}

internal fun subtitle(n: WfNode): String = when (n.type) {
    "trigger_schedule" -> WfLogic.scheduleLabel(n.config)
    "trigger_manual" -> "Toque em Executar"
    "agent", "ai" -> n.config["prompt"].orEmpty().replace(Regex("\\s+"), " ").ifBlank { "Defina a instrução" }
    "if_new" -> n.config["key"].orEmpty().ifBlank { "Defina a chave" }
    "ai_decide" -> n.config["criteria"].orEmpty().replace(Regex("\\s+"), " ").ifBlank { "Defina o critério" }
    "review" -> if (n.config["mode"] == "auto") "Automático (sem perguntar)" else "Pede sua aprovação"
    "connector" -> listOf(n.config["conector"].orEmpty(), n.config["acao"].orEmpty()).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Escolha conector e ação" }
    "mcp" -> n.config["tool"].orEmpty().ifBlank { "Escolha a ferramenta" }
    "http" -> "${n.config["method"] ?: "GET"} ${n.config["url"].orEmpty()}".trim()
    "shell" -> n.config["command"].orEmpty().ifBlank { "Defina o comando" }
    "set" -> n.config["values"].orEmpty().lines().firstOrNull().orEmpty().ifBlank { "Defina os campos" }
    "if" -> "${n.config["left"].orEmpty()} ${WfCatalog.of("if").fields[1].options.firstOrNull { it.first == n.config["op"] }?.second ?: ""} ${n.config["right"].orEmpty()}".trim()
    "delay" -> "${n.config["seconds"] ?: "10"} s"
    "approval" -> n.config["question"].orEmpty()
    "notify" -> n.config["title"].orEmpty()
    else -> ""
}

// ───────────────────────────── Configuração do nó ─────────────────────────────

@Composable
internal fun NodeEditor(w: Workflow, node: WfNode, lastStep: WfStep?, nav: NavController, onChange: (WfNode) -> Unit, onDeleteEdge: (String) -> Unit,
                       onConnect: (String) -> Unit, onDuplicate: () -> Unit, onDelete: () -> Unit) {
    val c = Fluwy.c
    val t = WfCatalog.of(node.type)
    val app = FluwyApp.instance
    val scope = rememberCoroutineScope()
    var cfg by remember(node.id) { mutableStateOf(node.config) }
    var label by remember(node.id) { mutableStateOf(node.label) }
    var lastKey by remember(node.id) { mutableStateOf(t.fields.firstOrNull { it.kind == WfField.Kind.MULTILINE || it.kind == WfField.Kind.TEXT || it.kind == WfField.Kind.JSON }?.key) }
    var helper by remember(node.id) { mutableStateOf<String?>(null) }
    var helperBusy by remember(node.id) { mutableStateOf(false) }
    fun commit(newCfg: Map<String, String> = cfg, newLabel: String = label) { cfg = newCfg; label = newLabel; onChange(node.copy(config = newCfg, label = newLabel)) }
    fun loadHelper(block: suspend () -> String) { helperBusy = true; scope.launch { helper = runCatching { block() }.getOrElse { "Erro: ${it.message}" }; helperBusy = false } }

    Column(Modifier.padding(16.dp, 0.dp, 16.dp, 28.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Color(t.color).copy(alpha = .18f)), contentAlignment = Alignment.Center) {
                Icon(nodeIcon(node.type), null, tint = Color(t.color))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(t.title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text(t.description, fontSize = 12.sp, color = c.ink3)
            }
        }
        FluwyField(label, { commit(newLabel = it) }, "Nome do nó (${t.title})")
        Text("Id para usar em outros nós: {{${node.id}}}", fontSize = 11.sp, color = c.ink3, fontFamily = FontFamily.Monospace)

        if (node.type == "connector") ConnectorConfig(node, cfg, w.nodes.filter { it.id != node.id && w.edges.any { e -> e.to == node.id && e.from == it.id } }) { commit(it) }
        else t.fields.forEach { f ->
            val v = cfg[f.key] ?: f.default
            val visible = when {
                node.type == "trigger_schedule" && f.key == "minutes" -> (cfg["mode"] ?: "daily") == "interval"
                node.type == "trigger_schedule" && f.key == "time" -> (cfg["mode"] ?: "daily") != "interval"
                node.type == "trigger_schedule" && f.key == "weekday" -> (cfg["mode"] ?: "daily") == "weekly"
                node.type == "if" && f.key == "right" -> (cfg["op"] ?: "contains") !in setOf("empty", "not_empty")
                else -> true
            }
            if (!visible) return@forEach
            when (f.kind) {
                WfField.Kind.SELECT -> {
                    Text(f.label, fontSize = 12.sp, color = c.ink2)
                    Row(Modifier.fillMaxWidth().horizontalScrollIfNeeded(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        f.options.forEach { (key, text) -> Chip(text, selected = v == key) { commit(cfg + (f.key to key)) } }
                    }
                }
                else -> {
                    FluwyField(v, { commit(cfg + (f.key to it)); lastKey = f.key }, f.label + if (f.required) " *" else "",
                        minLines = if (f.kind == WfField.Kind.MULTILINE || f.kind == WfField.Kind.JSON) 3 else 1)
                    if (f.hint.isNotBlank()) Text(f.hint, fontSize = 11.sp, color = c.ink3)
                }
            }
        }

        // Variáveis: toque para inserir no último campo editado.
        val others = w.nodes.filter { it.id != node.id && w.edges.any { e -> e.to == node.id || e.from == it.id } }
        if (node.type != "connector" && lastKey != null && t.fields.any { it.kind != WfField.Kind.SELECT }) {
            Text("Inserir dados de outros nós", fontSize = 12.sp, color = c.ink2)
            Row(Modifier.fillMaxWidth().horizontalScrollIfNeeded(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (listOf("{{prev}}" to "Saída anterior", "{{trigger.time}}" to "Hora do disparo") +
                    others.map { "{{${it.id}}}" to WfLogic.label(it) }).forEach { (token, text) ->
                    Chip(text) { lastKey?.let { k -> commit(cfg + (k to (cfg[k].orEmpty() + token))) } }
                }
            }
        }

        // Ajudas por tipo de nó.
        when (node.type) {
            "agent" -> {
                val agents = app.store.state.value.agents
                if (agents.isNotEmpty()) {
                    Text("Agentes", fontSize = 12.sp, color = c.ink2)
                    Row(Modifier.fillMaxWidth().horizontalScrollIfNeeded(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Chip("Padrão", selected = cfg["agent"].isNullOrBlank()) { commit(cfg + ("agent" to "")) }
                        agents.forEach { a -> Chip(a.name, selected = cfg["agent"].equals(a.name, true)) { commit(cfg + ("agent" to a.name)) } }
                    }
                }
            }
            "mcp" -> {
                val tools = app.store.state.value.connectors.filter { it.enabled }.flatMap { con ->
                    dev.fluwy.app.connectors.Connectors.toolNames(con).map { (name, _) -> dev.fluwy.app.connectors.Connectors.toolFunctionName(con.id, name) to "${con.name} · $name" }
                }
                if (tools.isEmpty()) Text("Nenhum conector MCP ativo. Adicione em Conectores.", fontSize = 12.sp, color = c.warn)
                else {
                    Text("Ferramentas disponíveis", fontSize = 12.sp, color = c.ink2)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        tools.take(40).forEach { (fn, text) -> Chip(text, selected = cfg["tool"] == fn) { commit(cfg + ("tool" to fn)) } }
                    }
                }
            }
        }
        if (helperBusy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.accent)
        helper?.let { h ->
            Text(h.take(6_000), Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface2).padding(10.dp),
                fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = c.ink2)
        }

        // Ligações.
        SectionLabel("Ligações")
        t.ports.forEach { port ->
            val outs = w.edges.filter { it.from == node.id && (it.port == port || (port == "main" && it.port == "main")) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(when (port) { "true" -> "Se sim →"; "false" -> "Se não →"; else -> "Depois →" }, fontSize = 13.sp, color = c.ink2, modifier = Modifier.width(78.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    outs.forEach { e ->
                        val target = w.nodes.firstOrNull { it.id == e.to }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(target?.let { WfLogic.label(it) } ?: "?", Modifier.weight(1f), fontSize = 13.sp)
                            IconButton({ onDeleteEdge(e.id) }, Modifier.size(32.dp)) { Icon(Icons.Rounded.LinkOff, "Desligar", Modifier.size(18.dp), tint = c.err) }
                        }
                    }
                    TextButton({ onConnect(port) }) { Icon(Icons.Rounded.AddLink, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Ligar a outro nó") }
                }
            }
        }

        if (!WfCatalog.of(node.type).trigger && node.type != "approval") NodeTester(w.copy(nodes = w.nodes.map { if (it.id == node.id) node.copy(config = cfg, label = label) else it }), node, lastStep)
        lastStep?.let { s ->
            if (s.status == "error") Text("Última execução: erro — ${s.error}".take(1_500), Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface2).padding(10.dp),
                fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = c.err)
            s.sessionId?.let { sid -> TextButton({ nav.navigate("chat/$sid") }) { Text("Abrir o chat do agente") } }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onDuplicate) { Icon(Icons.Rounded.ContentCopy, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Duplicar") }
            OutlinedButton(onDelete, colors = ButtonDefaults.outlinedButtonColors(contentColor = c.err)) {
                Icon(Icons.Rounded.Delete, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Excluir nó")
            }
        }
    }
}

// ───────────────────────────── Nó Conector (unificado) ─────────────────────────────

internal val prettyJson = kotlinx.serialization.json.Json { prettyPrint = true; prettyPrintIndent = "  " }

internal fun prettyOut(raw: String): String = runCatching {
    prettyJson.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), kotlinx.serialization.json.Json.parseToJsonElement(raw))
}.getOrDefault(raw)
