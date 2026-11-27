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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowEditorScreen(nav: NavController, id: String) {
    val c = Fluwy.c
    val ctx = nav.context
    val flows by WorkflowStore.flows.collectAsStateWithLifecycle()
    val runs by WorkflowStore.runs.collectAsStateWithLifecycle()
    val w = flows.firstOrNull { it.id == id }
    if (w == null) {
        Scaffold(containerColor = c.bg, topBar = { FluwyTopBar("Fluxo", { nav.popBackStack() }) }) { pad ->
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
    var confirmAuto by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    var watchRun by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<WfRun?>(null) }
    val problems = remember(w) { WfLogic.problems(w) }
    val myRuns = runs.filter { it.workflowId == w.id }.sortedByDescending { it.startedAt }
    val active = myRuns.firstOrNull { WorkflowEngine.isActive(it.status) }
    val shown = active ?: myRuns.firstOrNull()
    val nodeStatus = shown?.steps?.associate { it.nodeId to it.status }.orEmpty()

    LaunchedEffect(w.nodes) { pending.keys.toList().forEach { k -> val n = w.nodes.firstOrNull { it.id == k }; if (n == null || (n.x == pending[k]?.x && n.y == pending[k]?.y)) pending.remove(k) } }
    // Execução disparada daqui terminou: mostra o resultado na tela.
    LaunchedEffect(myRuns.firstOrNull { it.id == watchRun }?.status) {
        val r = myRuns.firstOrNull { it.id == watchRun } ?: return@LaunchedEffect
        if (!WorkflowEngine.isActive(r.status)) { result = r; watchRun = null }
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
                    val next = WorkflowScheduler.nextRuns(w, ctx).minOrNull()
                    val done = active?.steps?.count { it.status == "ok" } ?: 0
                    Text(when {
                        active?.status == "waiting" -> "Aguardando sua aprovação"
                        active?.status == "paused" -> "Pausado · $done de ${w.nodes.size} nós"
                        active != null -> "Executando · $done de ${w.nodes.size} nós"
                        next != null -> "Ativo · próxima ${timeFmt.format(Date(next))}"
                        w.nodes.any { it.type == "trigger_schedule" } -> "Agendamento desligado"
                        else -> "Manual · ${w.nodes.size} nós"
                    }, fontSize = 12.sp, color = if (active != null) c.accent else c.ink3)
                }
            },
            actions = {
                if (w.nodes.any { it.type == "trigger_schedule" || it.type == "trigger_webhook" || it.type == "trigger_whatsapp" }) Switch(checked = w.enabled, onCheckedChange = { on ->
                    if (on && problems.any { !it.contains("não está ligado") }) { problemsOpen = true }
                    else if (on) { toast = "Testando de ponta a ponta antes de publicar…"; WorkflowEngine.publish(ctx, w.id) { _, msg -> toast = msg.take(300) } }
                    else { val v = WorkflowStore.upsert(w.copy(enabled = false)); WorkflowScheduler.sync(ctx, v); toast = "Desligado" }
                }, modifier = Modifier.padding(end = 4.dp))
                IconButton({ history = true }) {
                    BadgedBox(badge = { if (active?.status == "waiting") Badge(containerColor = c.warn) }) { Icon(Icons.Rounded.History, "Execuções") }
                }
                Box {
                    IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, "Mais") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("Renomear") }, { menu = false; rename = true }, leadingIcon = { Icon(Icons.Rounded.Edit, null) })
                        if (w.nodes.any { it.type == "review" }) DropdownMenuItem({ Text(if (w.autonomous) "Desligar autonomia total" else "Autonomia total…") }, {
                            menu = false
                            if (w.autonomous) { save(w.copy(autonomous = false)); toast = "Revisões voltam a pedir sua aprovação" } else confirmAuto = true
                        }, leadingIcon = { Icon(Icons.Rounded.Bolt, null) })
                        DropdownMenuItem({ Text("Organizar nós") }, {
                            menu = false
                            save(WfLogic.layout(w, NODE_W, NODE_H)); fitted = false
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
                else if (active?.status == "waiting") Banner("Aguardando sua aprovação — toque para responder", c.warn) {
                    val rv = WorkflowEngine.pendingReviewOf(w.id)
                    if (rv != null) nav.navigate("review/${rv.runId}/${rv.nodeId}") else history = true
                }
                else if (w.autonomous) Banner("Autonomia total: revisões seguem sozinhas", c.accent) { menu = true }
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
                        val lastOut = r.steps.lastOrNull { it.status == "ok" && !it.type.startsWith("trigger") }?.output.orEmpty().let { WfFormat.oneLine(it) }
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
                if (active == null) FilledTonalButton({ palette = true }, contentPadding = PaddingValues(horizontal = 12.dp)) { Icon(Icons.Rounded.AddBox, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Nó") }
                Spacer(Modifier.width(6.dp))
                if (active != null && active.status != "waiting") {
                    IconButton({ if (active.status == "paused") WorkflowEngine.resume(active.id) else WorkflowEngine.pause(active.id) }) {
                        Icon(if (active.status == "paused") Icons.Rounded.PlayArrow else Icons.Rounded.Pause, if (active.status == "paused") "Continuar" else "Pausar", tint = c.warn)
                    }
                }
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
            title = { Text("Nome do fluxo") }, text = { FluwyField(name, { name = it }, "Nome") },
            confirmButton = { TextButton({ if (name.isNotBlank()) save(w.copy(name = name.trim())); rename = false }) { Text("Salvar") } },
            dismissButton = { TextButton({ rename = false }) { Text("Cancelar") } })
    }
    if (problemsOpen) AlertDialog(onDismissRequest = { problemsOpen = false }, containerColor = c.surface,
        title = { Text(if (problems.isEmpty()) "Tudo certo" else "Ajustes pendentes") },
        text = { Text(if (problems.isEmpty()) "O fluxo está pronto para rodar." else problems.joinToString("\n") { "• $it" }, fontSize = 14.sp) },
        confirmButton = { TextButton({ problemsOpen = false }) { Text("Ok") } })
    if (confirmAuto) AlertDialog(onDismissRequest = { confirmAuto = false }, containerColor = c.surface,
        title = { Text("Autonomia total?") },
        text = { Text("Os nós \"Revisar e aprovar\" deste fluxo vão seguir sozinhos com o rascunho da IA — por exemplo, responder e-mails sem você ver antes. Você pode desligar a qualquer momento.") },
        confirmButton = { TextButton({ confirmAuto = false; save(w.copy(autonomous = true)); toast = "Autonomia total ligada" }) { Text("Ligar") } },
        dismissButton = { TextButton({ confirmAuto = false }) { Text("Cancelar") } })
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, containerColor = c.surface,
        title = { Text("Excluir \"${w.name}\"?") }, text = { Text("O fluxo, o agendamento e o histórico de execuções serão apagados.") },
        confirmButton = { TextButton({
            confirmDelete = false
            active?.let { WorkflowEngine.cancel(it.id) }
            WorkflowScheduler.cancel(ctx, w.id); WorkflowStore.delete(w.id); nav.popBackStack()
        }) { Text("Excluir", color = c.err) } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancelar") } })
}
