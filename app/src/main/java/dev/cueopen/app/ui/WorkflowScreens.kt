package dev.cueopen.app.ui

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
import dev.cueopen.app.CueApp
import dev.cueopen.app.ui.theme.Cue
import dev.cueopen.app.workflow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private const val NODE_W = 212f
private const val NODE_H = 84f
private const val WORLD = 4000f

private fun nodeIcon(type: String): ImageVector = when (WfCatalog.of(type).icon) {
    "play" -> Icons.Rounded.PlayArrow
    "schedule" -> Icons.Rounded.Schedule
    "agent" -> Icons.Rounded.SmartToy
    "apps" -> Icons.Rounded.Apps
    "mcp" -> Icons.Rounded.Extension
    "http" -> Icons.Rounded.Http
    "terminal" -> Icons.Rounded.Terminal
    "set" -> Icons.Rounded.Tune
    "branch" -> Icons.Rounded.CallSplit
    "timer" -> Icons.Rounded.Timer
    "approval" -> Icons.Rounded.ThumbUp
    "notify" -> Icons.Rounded.Notifications
    else -> Icons.Rounded.Circle
}

private fun statusColor(status: String?, c: dev.cueopen.app.ui.theme.CueColors): Color? = when (status) {
    "running" -> c.accent
    "ok" -> c.ok
    "error" -> c.err
    "waiting" -> c.warn
    else -> null
}

private val timeFmt = SimpleDateFormat("dd/MM HH:mm", Locale("pt", "BR"))

/** Modelos prontos para começar rápido. */
private fun templates(): List<Pair<String, () -> Workflow>> = listOf(
    "Em branco (manual)" to { Workflow(name = "Novo fluxo", nodes = listOf(WfNode(type = "trigger_manual", label = "Início", x = 60f, y = 160f))) },
    "Resumo diário com IA" to {
        val t = WfNode(type = "trigger_schedule", label = "Todo dia 8h", x = 40f, y = 160f, config = mapOf("mode" to "daily", "time" to "08:00"))
        val a = WfNode(type = "agent", label = "Pesquisar", x = 320f, y = 160f, config = mapOf("prompt" to "Pesquise as 5 notícias mais importantes de hoje sobre tecnologia e resuma cada uma em uma linha, com o link."))
        val n = WfNode(type = "notify", label = "Avisar", x = 600f, y = 160f, config = mapOf("title" to "Resumo do dia", "text" to "{{prev}}"))
        Workflow(name = "Resumo diário com IA", nodes = listOf(t, a, n), edges = listOf(WfEdge(from = t.id, to = a.id), WfEdge(from = a.id, to = n.id)))
    },
    "Monitorar site" to {
        val t = WfNode(type = "trigger_schedule", label = "A cada 30 min", x = 40f, y = 200f, config = mapOf("mode" to "interval", "minutes" to "30"))
        val h = WfNode(type = "http", label = "Baixar página", x = 320f, y = 200f, config = mapOf("method" to "GET", "url" to "https://example.com", "timeout" to "20", "onError" to "continue"))
        val i = WfNode(type = "if", label = "Está no ar?", x = 600f, y = 200f, config = mapOf("left" to "{{prev.status}}", "op" to "equals", "right" to "200"))
        val ok = WfNode(type = "notify", label = "Site no ar", x = 900f, y = 100f, config = mapOf("title" to "✅ Site no ar", "text" to "Status {{${h.id}.status}} em {{trigger.time}}"))
        val bad = WfNode(type = "notify", label = "Site com problema", x = 900f, y = 300f, config = mapOf("title" to "⚠ Site com problema", "text" to "Status {{${h.id}.status}} em {{trigger.time}}"))
        Workflow(name = "Monitorar site", nodes = listOf(t, h, i, ok, bad), edges = listOf(WfEdge(from = t.id, to = h.id), WfEdge(from = h.id, to = i.id),
            WfEdge(from = i.id, to = ok.id, port = "true"), WfEdge(from = i.id, to = bad.id, port = "false")))
    },
    "Agente + aprovação + app" to {
        val t = WfNode(type = "trigger_manual", label = "Início", x = 40f, y = 160f)
        val a = WfNode(type = "agent", label = "Escrever e-mail", x = 320f, y = 160f, config = mapOf("prompt" to "Escreva um e-mail curto de follow-up para um cliente que pediu orçamento ontem."))
        val ap = WfNode(type = "approval", label = "Aprovar envio", x = 600f, y = 160f, config = mapOf("question" to "Enviar este e-mail?\n\n{{prev}}"))
        val g = WfNode(type = "connector", label = "Enviar (Gmail)", x = 900f, y = 100f, config = mapOf("conector" to "gmail", "acao" to "send_email", "campos" to "{\"body\":\"{{${a.id}}}\"}"))
        Workflow(name = "E-mail com aprovação", nodes = listOf(t, a, ap, g), edges = listOf(WfEdge(from = t.id, to = a.id), WfEdge(from = a.id, to = ap.id), WfEdge(from = ap.id, to = g.id, port = "true")))
    },
)

// ───────────────────────────── Lista ─────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowsScreen(nav: NavController) {
    val c = Cue.c
    val flows by WorkflowStore.flows.collectAsStateWithLifecycle()
    val runs by WorkflowStore.runs.collectAsStateWithLifecycle()
    val ctx = nav.context
    var newSheet by remember { mutableStateOf(false) }
    Scaffold(containerColor = c.bg, topBar = { CueTopBar("Fluxos", { nav.popBackStack() }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { newSheet = true }, containerColor = c.accent, contentColor = Color.White,
                icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("Novo fluxo") })
        }) { pad ->
        if (flows.isEmpty()) Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(Icons.Rounded.AccountTree, "Nenhum fluxo ainda",
                "Monte automações ligando nós no canvas: gatilho por horário, agentes de IA, apps conectados, HTTP, condições e notificações.")
        } else LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(flows.sortedByDescending { it.updatedAt }, key = { it.id }) { w ->
                val last = runs.filter { it.workflowId == w.id }.maxByOrNull { it.startedAt }
                val sched = w.nodes.firstOrNull { it.type == "trigger_schedule" }
                CueCard(onClick = { nav.navigate("workflow/${w.id}") }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(c.accent.copy(alpha = .14f)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.AccountTree, null, tint = c.accent)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(w.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${w.nodes.size} nós · " + (sched?.let { WfLogic.scheduleLabel(it.config) } ?: "manual"), fontSize = 12.sp, color = c.ink3)
                            if (last != null) Text(runLabel(last), fontSize = 12.sp, color = statusColor(last.status, c) ?: c.ink3, maxLines = 1)
                        }
                        if (sched != null) Switch(checked = w.enabled, onCheckedChange = { on ->
                            if (on && WfLogic.problems(w).any { !it.contains("não está ligado") }) nav.navigate("workflow/${w.id}")
                            else { val v = WorkflowStore.upsert(w.copy(enabled = on)); WorkflowScheduler.sync(ctx, v) }
                        })
                    }
                }
            }
        }
    }
    if (newSheet) ModalBottomSheet(onDismissRequest = { newSheet = false }, containerColor = c.surface) {
        Column(Modifier.padding(16.dp, 0.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Começar de", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            templates().forEach { (title, make) ->
                CueCard(onClick = {
                    val w = WorkflowStore.upsert(make())
                    newSheet = false
                    nav.navigate("workflow/${w.id}")
                }) { Text(title, fontWeight = FontWeight.Medium) }
            }
        }
    }
}

private fun runLabel(r: WfRun): String = when (r.status) {
    "running" -> "Executando agora…"
    "waiting" -> "Aguardando sua aprovação"
    "ok" -> "Última execução ok · ${timeFmt.format(Date(r.startedAt))}"
    "error" -> "Falhou · ${timeFmt.format(Date(r.startedAt))} · ${r.error.take(60)}"
    else -> "Parada · ${timeFmt.format(Date(r.startedAt))}"
}

// ───────────────────────────── Editor (canvas) ─────────────────────────────

private const val GRID = 20f

private fun snap(v: Float) = (Math.round(v / GRID) * GRID).toFloat()

/** Lugar livre perto de (x, y): desce até não encostar em nenhum nó. */
private fun freeSpot(w: Workflow, x: Float, y: Float, except: String? = null): Pair<Float, Float> {
    var ny = snap(y); val nx = snap(x).coerceIn(0f, WORLD - NODE_W)
    fun hit(yy: Float) = w.nodes.any { it.id != except && kotlin.math.abs(it.x - nx) < NODE_W + 20f && kotlin.math.abs(it.y - yy) < NODE_H + 20f }
    var guard = 0
    while (hit(ny) && guard++ < 40) ny += NODE_H + 30f
    return nx to ny.coerceIn(0f, WORLD - NODE_H)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowEditorScreen(nav: NavController, id: String) {
    val c = Cue.c
    val ctx = nav.context
    val flows by WorkflowStore.flows.collectAsStateWithLifecycle()
    val runs by WorkflowStore.runs.collectAsStateWithLifecycle()
    val w = flows.firstOrNull { it.id == id }
    if (w == null) {
        Scaffold(containerColor = c.bg, topBar = { CueTopBar("Fluxo", { nav.popBackStack() }) }) { pad ->
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) { Text("Fluxo não encontrado.", color = c.ink3) }
        }
        return
    }
    val density = LocalDensity.current
    val dens = density.density
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset(0f, 0f)) }
    var viewport by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    var fitted by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<String?>(null) }
    var connectFrom by remember { mutableStateOf<Pair<String, String>?>(null) }
    // Arrasto e posição recém-gravada ficam na memória até o fluxo salvo refletir (o nó não "pula" ao soltar).
    val dragOff = remember { mutableStateMapOf<String, Offset>() }
    val pending = remember { mutableStateMapOf<String, Offset>() }
    var palette by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var problemsOpen by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    var watchRun by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<WfRun?>(null) }
    val problems = remember(w) { WfLogic.problems(w) }
    val myRuns = runs.filter { it.workflowId == w.id }.sortedByDescending { it.startedAt }
    val active = myRuns.firstOrNull { it.status == "running" || it.status == "waiting" }
    val shown = active ?: myRuns.firstOrNull()
    val nodeStatus = shown?.steps?.associate { it.nodeId to it.status }.orEmpty()

    LaunchedEffect(w.nodes) { pending.keys.toList().forEach { k -> val n = w.nodes.firstOrNull { it.id == k }; if (n == null || (n.x == pending[k]?.x && n.y == pending[k]?.y)) pending.remove(k) } }
    // Execução disparada daqui terminou: mostra o resultado na tela.
    LaunchedEffect(myRuns.firstOrNull { it.id == watchRun }?.status) {
        val r = myRuns.firstOrNull { it.id == watchRun } ?: return@LaunchedEffect
        if (r.status != "running" && r.status != "waiting") { result = r; watchRun = null }
    }

    fun pos(n: WfNode): Offset { val p = pending[n.id] ?: Offset(n.x, n.y); val d = dragOff[n.id] ?: Offset.Zero; return Offset(p.x + d.x, p.y + d.y) }
    fun fit() {
        if (viewport.width == 0 || w.nodes.isEmpty()) { scale = 1f; offset = Offset(40f, 40f); return }
        val d = dens
        val minX = w.nodes.minOf { it.x }; val minY = w.nodes.minOf { it.y }
        val maxX = w.nodes.maxOf { it.x + NODE_W + 40f }; val maxY = w.nodes.maxOf { it.y + NODE_H }
        val pad = 48f
        val s = minOf(viewport.width / ((maxX - minX + pad * 2) * d), (viewport.height - 160 * d) / ((maxY - minY + pad * 2) * d), 1.15f).coerceIn(0.35f, 1.15f)
        scale = s
        val contentW = (maxX - minX) * d * s; val contentH = (maxY - minY) * d * s
        offset = Offset((viewport.width - contentW) / 2 - minX * d * s, (viewport.height - 120 * d - contentH) / 2 - minY * d * s)
    }
    LaunchedEffect(viewport, w.id) { if (!fitted && viewport.width > 0) { fit(); fitted = true } }

    fun save(v: Workflow) { val s = WorkflowStore.upsert(v); if (s.enabled) WorkflowScheduler.sync(ctx, s) }
    fun connect(to: String) {
        val (from, port) = connectFrom ?: return
        connectFrom = null
        val target = w.nodes.firstOrNull { it.id == to } ?: return
        when {
            from == to -> toast = "Um nó não pode ligar nele mesmo."
            WfCatalog.of(target.type).trigger -> toast = "Gatilhos não recebem ligação de entrada."
            w.edges.any { it.from == from && it.to == to && it.port == port } -> toast = "Essa ligação já existe."
            else -> { save(w.copy(edges = w.edges + WfEdge(from = from, to = to, port = port))); toast = "Ligado" }
        }
    }
    fun addNode(type: String, preset: WfPreset? = null) {
        val t = WfCatalog.of(type)
        val anchor = w.nodes.firstOrNull { it.id == selected } ?: w.nodes.maxByOrNull { it.x }
        val d = dens
        val center = Offset((viewport.width / 2f - offset.x) / (d * scale) - NODE_W / 2, (viewport.height / 2.5f - offset.y) / (d * scale))
        val (x, y) = if (anchor != null && !t.trigger) freeSpot(w, anchor.x + NODE_W + 60f, anchor.y) else freeSpot(w, center.x, center.y)
        val cfg = t.fields.filter { it.default.isNotEmpty() }.associate { it.key to it.default } + preset?.config.orEmpty()
        val n = WfNode(type = type, label = preset?.title.orEmpty(), x = x, y = y, config = cfg)
        val edge = anchor?.takeIf { !t.trigger }?.let { a -> WfEdge(from = a.id, to = n.id, port = WfCatalog.of(a.type).ports.first()) }
        save(w.copy(nodes = w.nodes + n, edges = w.edges + listOfNotNull(edge)))
        selected = n.id
    }
    fun runNow() {
        result = null
        WorkflowEngine.start(w.id).onFailure { toast = it.message?.lineSequence()?.firstOrNull(); problemsOpen = problems.isNotEmpty() }
            .onSuccess { watchRun = it; toast = "Executando…" }
    }

    Scaffold(containerColor = c.bg, topBar = {
        TopAppBar(colors = TopAppBarDefaults.topAppBarColors(containerColor = c.bg),
            navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.Rounded.ArrowBack, "Voltar") } },
            title = {
                Column(Modifier.clickable { rename = true }) {
                    Text(w.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    val next = WorkflowScheduler.nextRuns(w).minOrNull()
                    val done = active?.steps?.count { it.status == "ok" } ?: 0
                    Text(when {
                        active?.status == "waiting" -> "Aguardando sua aprovação"
                        active != null -> "Executando · $done de ${w.nodes.size} nós"
                        next != null -> "Ativo · próxima ${timeFmt.format(Date(next))}"
                        w.nodes.any { it.type == "trigger_schedule" } -> "Agendamento desligado"
                        else -> "Manual · ${w.nodes.size} nós"
                    }, fontSize = 12.sp, color = if (active != null) c.accent else c.ink3)
                }
            },
            actions = {
                if (w.nodes.any { it.type == "trigger_schedule" }) Switch(checked = w.enabled, onCheckedChange = { on ->
                    if (on && problems.any { !it.contains("não está ligado") }) { problemsOpen = true }
                    else { val v = WorkflowStore.upsert(w.copy(enabled = on)); WorkflowScheduler.sync(ctx, v); toast = if (on) "Agendamento ligado" else "Agendamento desligado" }
                }, modifier = Modifier.padding(end = 4.dp))
                IconButton({ history = true }) {
                    BadgedBox(badge = { if (active?.status == "waiting") Badge(containerColor = c.warn) }) { Icon(Icons.Rounded.History, "Execuções") }
                }
                Box {
                    IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, "Mais") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("Renomear") }, { menu = false; rename = true }, leadingIcon = { Icon(Icons.Rounded.Edit, null) })
                        DropdownMenuItem({ Text("Organizar nós") }, {
                            menu = false
                            // Layout automático por camadas a partir do gatilho (esquerda → direita), alinhado à grade.
                            val layers = mutableMapOf<String, Int>()
                            val q = ArrayDeque(w.nodes.filter { WfCatalog.of(it.type).trigger }.map { it.id }.onEach { layers[it] = 0 })
                            while (q.isNotEmpty()) { val cur = q.removeFirst(); w.edges.filter { it.from == cur }.forEach { e -> if (e.to !in layers) { layers[e.to] = layers.getValue(cur) + 1; q.add(e.to) } } }
                            val byLayer = w.nodes.groupBy { layers[it.id] ?: (layers.values.maxOrNull() ?: 0) + 1 }
                            val placed = w.nodes.map { n ->
                                val l = layers[n.id] ?: ((layers.values.maxOrNull() ?: 0) + 1)
                                val i = byLayer.getValue(l).indexOf(n)
                                n.copy(x = 40f + l * (NODE_W + 80f), y = 120f + i * (NODE_H + 60f))
                            }
                            save(w.copy(nodes = placed)); fitted = false
                        }, leadingIcon = { Icon(Icons.Rounded.AutoFixHigh, null) })
                        DropdownMenuItem({ Text("Duplicar fluxo") }, {
                            menu = false
                            val idMap = w.nodes.associate { it.id to WfIds.new() }
                            val copy = WorkflowStore.upsert(Workflow(name = w.name + " (cópia)", nodes = w.nodes.map { it.copy(id = idMap.getValue(it.id)) },
                                edges = w.edges.mapNotNull { e -> val f = idMap[e.from]; val t = idMap[e.to]; if (f != null && t != null) e.copy(id = WfIds.new(), from = f, to = t) else null }))
                            nav.navigate("workflow/${copy.id}")
                        }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) })
                        DropdownMenuItem({ Text("Ver ajustes pendentes") }, { menu = false; problemsOpen = true }, leadingIcon = { Icon(Icons.Rounded.Rule, null) })
                        DropdownMenuItem({ Text("Excluir fluxo", color = c.err) }, { menu = false; confirmDelete = true }, leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = c.err) })
                    }
                }
            })
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            val minor = c.line.copy(alpha = .35f); val major = c.line.copy(alpha = .75f)
            Box(Modifier.fillMaxSize().background(c.surface2)
                .onSizeChanged { viewport = it }
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val ns = (scale * zoom).coerceIn(0.35f, 2.2f)
                        offset = (offset - centroid) * (ns / scale) + centroid + pan
                        scale = ns
                    }
                }
                .pointerInput(Unit) { detectTapGestures { connectFrom = null; selected = null } }) {
                // Canvas quadriculado (linhas finas a cada 20 e marcadas a cada 100), acompanhando pan e zoom.
                Canvas(Modifier.fillMaxSize()) {
                    val step = GRID * dens * scale
                    if (step >= 6f) {
                        var i = kotlin.math.floor(-offset.x / step).toInt()
                        var x = offset.x + i * step
                        while (x < size.width) { drawLine(if (i % 5 == 0) major else minor, Offset(x, 0f), Offset(x, size.height), if (i % 5 == 0) 1.2f else 0.8f); x += step; i++ }
                        var j = kotlin.math.floor(-offset.y / step).toInt()
                        var y = offset.y + j * step
                        while (y < size.height) { drawLine(if (j % 5 == 0) major else minor, Offset(0f, y), Offset(size.width, y), if (j % 5 == 0) 1.2f else 0.8f); y += step; j++ }
                    }
                }
                // Ligações: curvas suaves; o caminho que a execução percorreu fica sólido e, enquanto roda, anima.
                val flow = rememberInfiniteTransition(label = "wfflow")
                val phase by flow.animateFloat(0f, 36f, infiniteRepeatable(tween(900, easing = androidx.compose.animation.core.LinearEasing)), label = "phase")
                val edgeColor = c.ink3
                val okColor = c.ok; val errColor = c.err; val accent = c.accent
                Canvas(Modifier.fillMaxSize()) {
                    val k = dens * scale
                    fun scr(xDp: Float, yDp: Float) = Offset(offset.x + xDp * k, offset.y + yDp * k)
                    w.edges.forEach { e ->
                        val a = w.nodes.firstOrNull { it.id == e.from } ?: return@forEach
                        val b = w.nodes.firstOrNull { it.id == e.to } ?: return@forEach
                        val pa = pos(a); val pb = pos(b)
                        val ports = WfCatalog.of(a.type).ports
                        val py = if (ports.size > 1) (if (e.port == "false") NODE_H * 0.70f else NODE_H * 0.32f) else NODE_H / 2
                        val s0 = scr(pa.x + NODE_W, pa.y + py)
                        val t0 = scr(pb.x, pb.y + NODE_H / 2)
                        val dx = (kotlin.math.abs(t0.x - s0.x) / 2).coerceAtLeast(50f * k)
                        val path = Path().apply { moveTo(s0.x, s0.y); cubicTo(s0.x + dx, s0.y, t0.x - dx, t0.y, t0.x, t0.y) }
                        val base = when (e.port) { "true" -> okColor; "false" -> errColor; else -> edgeColor }
                        val ran = nodeStatus[e.from] == "ok" && nodeStatus.containsKey(e.to)
                        val live = ran && nodeStatus[e.to] == "running"
                        drawPath(path, (if (live) accent else base).copy(alpha = if (ran) 1f else .55f), style = Stroke(width = (if (ran) 2.6f else 1.8f) * k,
                            cap = androidx.compose.ui.graphics.StrokeCap.Round,
                            pathEffect = if (live) PathEffect.dashPathEffect(floatArrayOf(12f * k, 6f * k), -phase * k) else if (ran) null else PathEffect.dashPathEffect(floatArrayOf(7f * k, 6f * k))))
                        // Seta de chegada.
                        val ah = 6f * k
                        drawPath(Path().apply { moveTo(t0.x, t0.y); lineTo(t0.x - ah * 1.4f, t0.y - ah); lineTo(t0.x - ah * 1.4f, t0.y + ah); close() }, base.copy(alpha = if (ran) 1f else .7f))
                    }
                }
                w.nodes.forEach { n ->
                    val p = pos(n)
                    Box(Modifier.offset { IntOffset((offset.x + p.x * dens * scale).roundToInt(), (offset.y + p.y * dens * scale).roundToInt()) }
                        .graphicsLayer { scaleX = scale; scaleY = scale; transformOrigin = TransformOrigin(0f, 0f) }) {
                        NodeCard(n, nodeStatus[n.id], selected == n.id, connectFrom != null && connectFrom?.first != n.id, connectFrom?.first == n.id,
                            onTap = { if (connectFrom != null) connect(n.id) else selected = n.id },
                            onDrag = { dx, dy ->
                                val base = pending[n.id] ?: Offset(n.x, n.y)
                                val d = dragOff[n.id] ?: Offset.Zero
                                val nx = (base.x + d.x + dx / dens).coerceIn(0f, WORLD - NODE_W) - base.x
                                val ny = (base.y + d.y + dy / dens).coerceIn(0f, WORLD - NODE_H) - base.y
                                dragOff[n.id] = Offset(nx, ny)
                            },
                            onDragEnd = {
                                val d = dragOff.remove(n.id) ?: return@NodeCard
                                val base = pending[n.id] ?: Offset(n.x, n.y)
                                // Encaixa na grade e evita ficar em cima de outro nó.
                                val (fx, fy) = freeSpot(w, base.x + d.x, base.y + d.y, except = n.id)
                                pending[n.id] = Offset(fx, fy)
                                save(w.copy(nodes = w.nodes.map { if (it.id == n.id) it.copy(x = fx, y = fy) else it }))
                            },
                            onPort = { port -> connectFrom = if (connectFrom == n.id to port) null else n.id to port })
                    }
                }
                if (w.nodes.isEmpty()) Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.AccountTree, null, Modifier.size(40.dp), tint = c.ink3)
                    Spacer(Modifier.height(8.dp))
                    Text("Comece adicionando um gatilho", color = c.ink2, fontWeight = FontWeight.Medium)
                    TextButton({ palette = true }) { Text("Adicionar nó") }
                }
            }
            // Avisos.
            Column(Modifier.align(Alignment.TopCenter).padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (connectFrom != null) Banner("Toque no nó de destino · toque no fundo para cancelar", c.accent) { connectFrom = null }
                else if (active?.status == "waiting") Banner("Aguardando sua aprovação — toque para responder", c.warn) { history = true }
                else if (problems.isNotEmpty()) Banner("${problems.size} ajuste(s) pendente(s)", c.warn) { problemsOpen = true }
            }
            // Resultado da execução disparada aqui.
            result?.let { r ->
                val col = statusColor(r.status, c) ?: c.ink3
                Row(Modifier.align(Alignment.BottomCenter).padding(start = 14.dp, end = 14.dp, bottom = 84.dp).fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp)).background(c.surface).border(1.dp, col.copy(alpha = .6f), RoundedCornerShape(18.dp))
                    .clickable { history = true; result = null }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (r.status == "ok") Icons.Rounded.CheckCircle else if (r.status == "error") Icons.Rounded.Error else Icons.Rounded.StopCircle, null, tint = col)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(when (r.status) { "ok" -> "Execução concluída"; "error" -> "A execução falhou"; else -> "Execução parada" }, fontWeight = FontWeight.SemiBold)
                        val lastOut = r.steps.lastOrNull { it.status == "ok" }?.output.orEmpty()
                        Text((if (r.status == "error") r.error else "${r.steps.size} passos · ${(((r.finishedAt ?: r.startedAt) - r.startedAt) / 100) / 10.0} s" +
                            (lastOut.takeIf { it.isNotBlank() }?.let { " · " + it.replace(Regex("\\s+"), " ").take(70) } ?: "")),
                            fontSize = 12.sp, color = if (r.status == "error") c.err else c.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton({ result = null }) { Icon(Icons.Rounded.Close, "Fechar", tint = c.ink3) }
                }
            }
            // Barra de ações (alcance do polegar).
            Row(Modifier.align(Alignment.BottomCenter).padding(14.dp).clip(RoundedCornerShape(26.dp)).background(c.surface)
                .border(1.dp, c.line, RoundedCornerShape(26.dp)).padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ scale = (scale / 1.25f).coerceAtLeast(0.35f) }) { Icon(Icons.Rounded.Remove, "Diminuir") }
                Text("${(scale * 100).roundToInt()}%", fontSize = 12.sp, color = c.ink3, modifier = Modifier.width(40.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                IconButton({ scale = (scale * 1.25f).coerceAtMost(2.2f) }) { Icon(Icons.Rounded.Add, "Aumentar") }
                IconButton({ fit() }) { Icon(Icons.Rounded.FitScreen, "Ajustar à tela") }
                Spacer(Modifier.width(2.dp))
                FilledTonalButton({ palette = true }, contentPadding = PaddingValues(horizontal = 12.dp)) { Icon(Icons.Rounded.AddBox, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Nó") }
                Spacer(Modifier.width(6.dp))
                if (active != null) Button({ WorkflowEngine.cancel(active.id) }, colors = ButtonDefaults.buttonColors(containerColor = c.err), contentPadding = PaddingValues(horizontal = 14.dp)) {
                    Icon(Icons.Rounded.Stop, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Parar")
                } else Button({ runNow() }, colors = ButtonDefaults.buttonColors(containerColor = c.accent), contentPadding = PaddingValues(horizontal = 14.dp)) {
                    Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Executar")
                }
            }
            toast?.let { msg ->
                LaunchedEffect(msg) { kotlinx.coroutines.delay(2_200); toast = null }
                Text(msg, Modifier.align(Alignment.TopCenter).padding(top = 56.dp).clip(RoundedCornerShape(12.dp))
                    .background(c.ink.copy(alpha = .92f)).padding(horizontal = 14.dp, vertical = 8.dp), color = c.bg, fontSize = 13.sp)
            }
        }
    }

    if (palette) ModalBottomSheet(onDismissRequest = { palette = false }, containerColor = c.surface, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        NodePalette(onPick = { type, preset -> addNode(type, preset); palette = false })
    }

    selected?.let { nid ->
        val node = w.nodes.firstOrNull { it.id == nid }
        if (node != null) ModalBottomSheet(onDismissRequest = { selected = null }, containerColor = c.surface, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            NodeEditor(w, node, shown?.steps?.lastOrNull { it.nodeId == node.id }, nav,
                onChange = { nn -> save(w.copy(nodes = w.nodes.map { if (it.id == nn.id) nn else it })) },
                onDeleteEdge = { eid -> save(w.copy(edges = w.edges.filterNot { it.id == eid })) },
                onConnect = { port -> connectFrom = node.id to port; selected = null },
                onDuplicate = {
                    val (x, y) = freeSpot(w, node.x, node.y + NODE_H + 40f)
                    val copy = node.copy(id = WfIds.new(), x = x, y = y)
                    save(w.copy(nodes = w.nodes + copy)); selected = copy.id
                },
                onDelete = { save(w.copy(nodes = w.nodes.filterNot { it.id == nid }, edges = w.edges.filterNot { it.from == nid || it.to == nid })); selected = null })
        }
    }

    if (history) ModalBottomSheet(onDismissRequest = { history = false }, containerColor = c.surface, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        RunsPanel(w, myRuns, nav) { history = false }
    }

    if (rename) {
        var name by remember { mutableStateOf(w.name) }
        AlertDialog(onDismissRequest = { rename = false }, containerColor = c.surface,
            title = { Text("Nome do fluxo") }, text = { CueField(name, { name = it }, "Nome") },
            confirmButton = { TextButton({ if (name.isNotBlank()) save(w.copy(name = name.trim())); rename = false }) { Text("Salvar") } },
            dismissButton = { TextButton({ rename = false }) { Text("Cancelar") } })
    }
    if (problemsOpen) AlertDialog(onDismissRequest = { problemsOpen = false }, containerColor = c.surface,
        title = { Text(if (problems.isEmpty()) "Tudo certo" else "Ajustes pendentes") },
        text = { Text(if (problems.isEmpty()) "O fluxo está pronto para rodar." else problems.joinToString("\n") { "• $it" }, fontSize = 14.sp) },
        confirmButton = { TextButton({ problemsOpen = false }) { Text("Ok") } })
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, containerColor = c.surface,
        title = { Text("Excluir \"${w.name}\"?") }, text = { Text("O fluxo, o agendamento e o histórico de execuções serão apagados.") },
        confirmButton = { TextButton({
            confirmDelete = false
            active?.let { WorkflowEngine.cancel(it.id) }
            WorkflowScheduler.cancel(ctx, w.id); WorkflowStore.delete(w.id); nav.popBackStack()
        }) { Text("Excluir", color = c.err) } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancelar") } })
}

/** Paleta: busca + nós prontos + tipos básicos, por categoria. */
@Composable
private fun NodePalette(onPick: (String, WfPreset?) -> Unit) {
    val c = Cue.c
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
                val basics = WfCatalog.TYPES.filter { it.category == cat }.map { Triple(it.type, null as WfPreset?, it.title to it.description) }
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
private fun Banner(text: String, color: Color, onClick: () -> Unit) {
    Text(text, Modifier.clip(RoundedCornerShape(12.dp)).background(Cue.c.surface).border(1.dp, color.copy(alpha = .6f), RoundedCornerShape(12.dp))
        .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp), color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium)
}

@Composable
private fun NodeCard(n: WfNode, status: String?, selected: Boolean, targetable: Boolean, source: Boolean,
                     onTap: () -> Unit, onDrag: (Float, Float) -> Unit, onDragEnd: () -> Unit, onPort: (String) -> Unit) {
    val c = Cue.c
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

private fun subtitle(n: WfNode): String = when (n.type) {
    "trigger_schedule" -> WfLogic.scheduleLabel(n.config)
    "trigger_manual" -> "Toque em Executar"
    "agent" -> n.config["prompt"].orEmpty().ifBlank { "Defina a instrução" }
    "connector" -> listOf(n.config["conector"].orEmpty(), n.config["acao"].orEmpty()).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Escolha app e ação" }
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
private fun NodeEditor(w: Workflow, node: WfNode, lastStep: WfStep?, nav: NavController, onChange: (WfNode) -> Unit, onDeleteEdge: (String) -> Unit,
                       onConnect: (String) -> Unit, onDuplicate: () -> Unit, onDelete: () -> Unit) {
    val c = Cue.c
    val t = WfCatalog.of(node.type)
    val app = CueApp.instance
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
        CueField(label, { commit(newLabel = it) }, "Nome do nó (${t.title})")
        Text("Id para usar em outros nós: {{${node.id}}}", fontSize = 11.sp, color = c.ink3, fontFamily = FontFamily.Monospace)

        t.fields.forEach { f ->
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
                    CueField(v, { commit(cfg + (f.key to it)); lastKey = f.key }, f.label + if (f.required) " *" else "",
                        minLines = if (f.kind == WfField.Kind.MULTILINE || f.kind == WfField.Kind.JSON) 3 else 1)
                    if (f.hint.isNotBlank()) Text(f.hint, fontSize = 11.sp, color = c.ink3)
                }
            }
        }

        // Variáveis: toque para inserir no último campo editado.
        val others = w.nodes.filter { it.id != node.id && w.edges.any { e -> e.to == node.id || e.from == it.id } }
        if (lastKey != null && t.fields.any { it.kind != WfField.Kind.SELECT }) {
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
            "connector" -> {
                val hub = dev.cueopen.app.connectors.HubConnectors.available(app.store)
                if (!hub) Text("O hub de conectores está desligado. Ligue em Conectores › Apps para usar este nó.", fontSize = 12.sp, color = c.warn)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton({ loadHelper { dev.cueopen.app.connectors.HubConnectors.list(app.store) } }, enabled = hub) { Text("Ver conectores") }
                    OutlinedButton({ loadHelper { dev.cueopen.app.connectors.HubConnectors.actions(app.store, cfg["conector"].orEmpty(), cfg["acao"].orEmpty(), "") } },
                        enabled = hub && !cfg["conector"].isNullOrBlank()) { Text(if (cfg["acao"].isNullOrBlank()) "Ver ações" else "Ver campos") }
                }
            }
            "mcp" -> {
                val tools = app.store.state.value.connectors.filter { it.enabled }.flatMap { con ->
                    dev.cueopen.app.connectors.Connectors.toolNames(con).map { (name, _) -> dev.cueopen.app.connectors.Connectors.toolFunctionName(con.id, name) to "${con.name} · $name" }
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

        lastStep?.let { s ->
            SectionLabel("Última execução deste nó")
            Text((if (s.status == "error") "Erro: ${s.error}" else s.output.ifBlank { "(sem saída)" }).take(3_000),
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface2).padding(10.dp),
                fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = if (s.status == "error") c.err else c.ink2)
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

@Composable
private fun Modifier.horizontalScrollIfNeeded(): Modifier = this.then(Modifier.horizontalScroll(rememberScrollState()))

// ───────────────────────────── Execuções ─────────────────────────────

@Composable
private fun RunsPanel(w: Workflow, runs: List<WfRun>, nav: NavController, onClose: () -> Unit) {
    val c = Cue.c
    var open by remember { mutableStateOf(runs.firstOrNull()?.id) }
    Column(Modifier.padding(16.dp, 0.dp, 16.dp, 28.dp).heightIn(max = 640.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Execuções", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.weight(1f))
            if (runs.any { it.status != "running" && it.status != "waiting" }) TextButton({ WorkflowStore.clearRuns(w.id) }) { Text("Limpar histórico") }
        }
        if (runs.isEmpty()) Text("Nenhuma execução ainda. Toque em Executar.", color = c.ink3, fontSize = 13.sp, modifier = Modifier.padding(vertical = 16.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(runs, key = { it.id }) { r ->
                val col = statusColor(r.status, c) ?: c.ink3
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface2).clickable { open = if (open == r.id) null else r.id }.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(col))
                        Spacer(Modifier.width(8.dp))
                        Text(when (r.status) { "ok" -> "Concluída"; "error" -> "Falhou"; "running" -> "Executando"; "waiting" -> "Aguardando aprovação"; else -> "Parada" },
                            fontWeight = FontWeight.SemiBold, color = col, modifier = Modifier.weight(1f))
                        Text("${if (r.trigger == "schedule") "agendada" else "manual"} · ${timeFmt.format(Date(r.startedAt))}", fontSize = 11.sp, color = c.ink3)
                    }
                    r.finishedAt?.let { Text("Duração: ${((it - r.startedAt) / 1000).coerceAtLeast(0)} s", fontSize = 11.sp, color = c.ink3) }
                    if (r.error.isNotBlank()) Text(r.error.take(400), fontSize = 12.sp, color = c.err)
                    if (r.status == "running" || r.status == "waiting") TextButton({ WorkflowEngine.cancel(r.id) }) { Text("Parar execução", color = c.err) }
                    if (open == r.id) r.steps.forEach { s ->
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(nodeIcon(s.type), null, Modifier.size(16.dp), tint = statusColor(s.status, c) ?: c.ink3)
                            Spacer(Modifier.width(6.dp))
                            Text(s.label, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            s.finishedAt?.let { Text("${((it - s.startedAt) / 100) / 10.0} s", fontSize = 11.sp, color = c.ink3) }
                        }
                        val body = if (s.status == "error") s.error else s.output
                        if (body.isNotBlank()) Text(body.take(1_200), Modifier.fillMaxWidth().padding(start = 22.dp, top = 2.dp), fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace, color = if (s.status == "error") c.err else c.ink2)
                        if (r.status == "waiting" && s.status == "running" && s.type == "approval" && WorkflowEngine.pendingApproval(r.id, s.nodeId)) Row(Modifier.padding(start = 22.dp)) {
                            Button({ WorkflowEngine.approve(r.id, s.nodeId, true) }, colors = ButtonDefaults.buttonColors(containerColor = c.ok)) { Text("Aprovar") }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton({ WorkflowEngine.approve(r.id, s.nodeId, false) }) { Text("Recusar") }
                        }
                        s.sessionId?.let { sid -> TextButton({ onClose(); nav.navigate("chat/$sid") }, Modifier.padding(start = 14.dp)) { Text("Abrir o chat do agente", fontSize = 12.sp) } }
                    }
                }
            }
        }
    }
}
