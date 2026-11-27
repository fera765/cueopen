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
fun WorkflowsScreen(nav: NavController) {
    val c = Fluwy.c
    val flows by WorkflowStore.flows.collectAsStateWithLifecycle()
    val runs by WorkflowStore.runs.collectAsStateWithLifecycle()
    val ctx = nav.context
    var newSheet by remember { mutableStateOf(false) }
    Scaffold(containerColor = c.bg, topBar = { FluwyTopBar("Fluxos", { nav.popBackStack() }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { newSheet = true }, containerColor = c.accent, contentColor = Color.White,
                icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("Novo fluxo") })
        }) { pad ->
        // Relógio da lista: atualiza "última/próxima execução" a cada 15 s.
        var tick by remember { mutableStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) { while (true) { tick = System.currentTimeMillis(); kotlinx.coroutines.delay(15_000) } }
        if (flows.isEmpty()) Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(Icons.Rounded.AccountTree, "Nenhum fluxo ainda",
                "Monte automações ligando nós no canvas: gatilho por horário, agentes de IA, apps conectados, HTTP, condições e notificações.")
        } else LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(flows.sortedByDescending { it.updatedAt }, key = { it.id }) { w ->
                val mine = runs.filter { it.workflowId == w.id }
                val last = mine.maxByOrNull { it.startedAt }
                val active = mine.filter { WorkflowEngine.isActive(it.status) }.maxByOrNull { it.startedAt }
                val sched = w.nodes.firstOrNull { it.type == "trigger_schedule" }
                // Gatilho por evento: escuta em tempo real (sem horário) — fica ativo/desligado, nunca "manual".
                val event = w.nodes.firstOrNull { it.type == "trigger_whatsapp" || it.type == "trigger_webhook" }
                val eventLabel = if (event?.type == "trigger_whatsapp") "Mensagem no WhatsApp" else "Webhook"
                val accent = statusColor(active?.status, c) ?: c.accent
                FluwyCard(onClick = { nav.navigate("workflow/${w.id}") }) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(accent.copy(alpha = .14f)), contentAlignment = Alignment.Center) {
                                if (active?.status == "running") CircularProgressIndicator(Modifier.size(22.dp), color = accent, strokeWidth = 2.5.dp)
                                else Icon(Icons.Rounded.AccountTree, null, tint = accent)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (w.enabled && (event != null || sched != null)) { LiveDot(Color(0xFF2EC27E)); Spacer(Modifier.width(6.dp)) }
                                    Text(w.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Text("${w.nodes.size} nós · " + (event?.let { eventLabel + if (w.enabled) " · ativo, escutando" else " · desligado" }
                                    ?: sched?.let { WfLogic.scheduleLabel(it.config) + if (w.enabled) " · ativo" else " · desligado" } ?: "manual"),
                                    fontSize = 12.sp, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (active == null && last != null) Text(runLabel(last), fontSize = 12.sp, color = statusColor(last.status, c) ?: c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            // Start / pausa / continuar / parar direto da lista.
                            when {
                                // Evento: o botão liga/desliga a escuta (▶ ativa · ⏸ pausa), em vez de executar uma vez.
                                active == null && event != null -> if (w.enabled) FilledTonalIconButton({
                                    val v = WorkflowStore.upsert(w.copy(enabled = false)); WorkflowScheduler.sync(ctx, v)
                                }) { Icon(Icons.Rounded.Pause, "Pausar escuta") } else FilledIconButton({
                                    if (WfLogic.problems(w).any { !it.contains("não está ligado") }) nav.navigate("workflow/${w.id}")
                                    else { val v = WorkflowStore.upsert(w.copy(enabled = true)); WorkflowScheduler.sync(ctx, v) }
                                }, colors = IconButtonDefaults.filledIconButtonColors(containerColor = c.accent)) { Icon(Icons.Rounded.PlayArrow, "Ativar escuta", tint = Color.White) }
                                active == null -> FilledIconButton({
                                    WorkflowEngine.start(w.id).onFailure { nav.navigate("workflow/${w.id}") }
                                }, colors = IconButtonDefaults.filledIconButtonColors(containerColor = c.accent)) { Icon(Icons.Rounded.PlayArrow, "Executar", tint = Color.White) }
                                active.status == "waiting" -> FilledTonalIconButton({
                                    val rv = WorkflowEngine.pendingReviewOf(w.id)
                                    nav.navigate(if (rv != null) "review/${rv.runId}/${rv.nodeId}" else "workflow/${w.id}")
                                }) { Icon(Icons.Rounded.HourglassTop, "Responder", tint = c.warn) }
                                else -> Row {
                                    FilledTonalIconButton({ if (active.status == "paused") WorkflowEngine.resume(active.id) else WorkflowEngine.pause(active.id) }) {
                                        Icon(if (active.status == "paused") Icons.Rounded.PlayArrow else Icons.Rounded.Pause, if (active.status == "paused") "Continuar" else "Pausar")
                                    }
                                    IconButton({ WorkflowEngine.cancel(active.id) }) { Icon(Icons.Rounded.Stop, "Parar", tint = c.err) }
                                }
                            }
                        }
                        // Execução ao vivo: nó atual e progresso (o editor abre herdando este estado).
                        if (active != null) {
                            val done = active.steps.count { it.status == "ok" }
                            val cur = active.steps.lastOrNull()
                            Spacer(Modifier.height(10.dp))
                            LinearProgressIndicator(progress = { (done.toFloat() / w.nodes.size.coerceAtLeast(1)).coerceIn(0.03f, 1f) },
                                modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)), color = accent, trackColor = c.line)
                            Spacer(Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                cur?.let { Icon(nodeIcon(it.type), null, Modifier.size(14.dp), tint = accent); Spacer(Modifier.width(6.dp)) }
                                Text(when (active.status) {
                                    "paused" -> "Pausado"
    "queued" -> "Na fila (outros fluxos rodando)"
                                    "waiting" -> "Aguardando aprovação"
                                    else -> "Executando"
                                } + (cur?.let { " · ${it.label}" } ?: "") + " · $done de ${w.nodes.size}", fontSize = 12.sp, color = accent, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f))
                                Text("Ver ao vivo ›", fontSize = 12.sp, color = c.ink3)
                            }
                        }
                                // Sempre: última execução e próxima (qualquer intervalo — 2 min, 1 h, 1 dia…).
                        val lastAt = listOfNotNull(last?.startedAt, if (sched != null) WorkflowScheduler.lastFire(w, ctx) else null).maxOrNull()
                        val nextAt = if (sched != null && w.enabled) WorkflowScheduler.nextRuns(w, ctx).firstOrNull { it > tick - 120_000 } else null
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.History, null, Modifier.size(14.dp), tint = c.ink3); Spacer(Modifier.width(4.dp))
                            Text("Última: " + (lastAt?.let { WfLogic.whenLabel(it, tick) } ?: "nunca"), fontSize = 12.sp, color = c.ink2, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Icon(Icons.Rounded.Schedule, null, Modifier.size(14.dp), tint = if (nextAt != null) c.accent else c.ink3); Spacer(Modifier.width(4.dp))
                            Text("Próxima: " + when { event != null -> if (w.enabled) "a cada evento" else "desligado"; nextAt != null -> WfLogic.whenLabel(nextAt, tick); sched == null -> "manual"; else -> "desligado" },
                                fontSize = 12.sp, color = if (nextAt != null || (event != null && w.enabled)) c.accent else c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (sched != null) {
                            Spacer(Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Agendamento", fontSize = 12.sp, color = c.ink3, modifier = Modifier.weight(1f))
                                Switch(checked = w.enabled, onCheckedChange = { on ->
                                    if (on && WfLogic.problems(w).any { !it.contains("não está ligado") }) nav.navigate("workflow/${w.id}")
                                    else if (on) WorkflowEngine.publish(ctx, w.id) { ok, msg -> if (!ok) android.widget.Toast.makeText(ctx, msg.take(300), android.widget.Toast.LENGTH_LONG).show() }
                                    else { val v = WorkflowStore.upsert(w.copy(enabled = false)); WorkflowScheduler.sync(ctx, v) }
                                }, modifier = Modifier.scale(0.85f))
                            }
                        }
                    }
                }
            }
        }
    }
    if (newSheet) ModalBottomSheet(onDismissRequest = { newSheet = false }, containerColor = c.surface) {
        Column(Modifier.padding(16.dp, 0.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Começar de", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text("Modelos prontos, já ligados e validados — ajuste os campos e toque em Executar.", fontSize = 12.sp, color = c.ink3)
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                templates().forEach { tp ->
                    FluwyCard(onClick = {
                        val w = WorkflowStore.upsert(tp.make())
                        newSheet = false
                        nav.navigate("workflow/${w.id}")
                    }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(c.accent.copy(alpha = .14f)), contentAlignment = Alignment.Center) {
                                Icon(when (tp.icon) { "notify" -> Icons.Rounded.Notifications; "apps" -> Icons.Rounded.Apps; "http" -> Icons.Rounded.Http
                                    "schedule" -> Icons.Rounded.Schedule; "agent" -> Icons.Rounded.SmartToy; "approval" -> Icons.Rounded.ThumbUp; "review" -> Icons.Rounded.RateReview; else -> Icons.Rounded.PlayArrow }, null, tint = c.accent)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(tp.title, fontWeight = FontWeight.Medium)
                                Text(tp.description, fontSize = 12.sp, color = c.ink3)
                            }
                        }
                    }
                }
            }
        }
    }
}

internal fun runLabel(r: WfRun): String = when (r.status) {
    "running" -> "Executando agora…"
    "paused" -> "Pausado"
    "waiting" -> "Aguardando sua aprovação"
    "ok" -> "Última execução ok · ${timeFmt.format(Date(r.startedAt))}"
    "error" -> "Falhou · ${timeFmt.format(Date(r.startedAt))} · ${r.error.take(60)}"
    else -> "Parada · ${timeFmt.format(Date(r.startedAt))}"
}

// ───────────────────────────── Editor (canvas) ─────────────────────────────

internal const val GRID = 20f

internal fun snap(v: Float) = (Math.round(v / GRID) * GRID).toFloat()

/** Lugar livre perto de (x, y): desce até não encostar em nenhum nó. */
internal fun freeSpot(w: Workflow, x: Float, y: Float, except: String? = null): Pair<Float, Float> {
    var ny = snap(y); val nx = snap(x).coerceIn(0f, WORLD - NODE_W)
    fun hit(yy: Float) = w.nodes.any { it.id != except && kotlin.math.abs(it.x - nx) < NODE_W + 20f && kotlin.math.abs(it.y - yy) < NODE_H + 20f }
    var guard = 0
    while (hit(ny) && guard++ < 40) ny += NODE_H + 30f
    return nx to ny.coerceIn(0f, WORLD - NODE_H)
}
