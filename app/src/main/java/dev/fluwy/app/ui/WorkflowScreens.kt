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

internal const val NODE_W = 212f
internal const val NODE_H = 84f
internal const val WORLD = 4000f

internal fun nodeIcon(type: String): ImageVector = when (WfCatalog.of(type).icon) {
    "play" -> Icons.Rounded.PlayArrow
    "schedule" -> Icons.Rounded.Schedule
    "webhook" -> Icons.Rounded.Webhook
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
    "ai" -> Icons.Rounded.AutoAwesome
    "decide" -> Icons.Rounded.Psychology
    "review" -> Icons.Rounded.RateReview
    "new" -> Icons.Rounded.FiberNew
    else -> Icons.Rounded.Circle
}

internal fun statusColor(status: String?, c: dev.fluwy.app.ui.theme.FluwyColors): Color? = when (status) {
    "running" -> c.accent
    "ok" -> c.ok
    "error" -> c.err
    "waiting" -> c.warn
    "paused" -> c.warn
    "queued" -> c.ink3
    else -> null
}

internal val timeFmt = SimpleDateFormat("dd/MM HH:mm", Locale("pt", "BR"))

/** Modelo pronto: título, descrição e montagem (ids curtos e legíveis; layout automático). */
internal data class WfTemplate(val title: String, val description: String, val icon: String, val make: () -> Workflow)

internal fun flow(name: String, nodes: List<WfNode>, vararg edges: WfEdge) = WfLogic.layout(Workflow(name = name, nodes = nodes, edges = edges.toList()), NODE_W, NODE_H)
