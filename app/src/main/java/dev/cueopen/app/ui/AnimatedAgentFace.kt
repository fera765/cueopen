package dev.cueopen.app.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** A regular polygon in normalized Canvas coordinates. */
internal fun avatarRegularPolygon(width: Float, height: Float, sides: Int, radius: Float): Path =
    avatarRadialPath(width, height, sides, radius, radius)

/** A radial star/gear path in normalized Canvas coordinates. */
internal fun avatarStarPath(width: Float, height: Float, points: Int, outer: Float, inner: Float): Path =
    avatarRadialPath(width, height, points * 2, outer, inner)

private fun avatarRadialPath(width: Float, height: Float, vertices: Int, outer: Float, inner: Float): Path {
    val path = Path()
    repeat(vertices) { i ->
        val radius = if (vertices % 2 == 0 && outer != inner && i % 2 == 1) inner else outer
        val angle = -PI / 2.0 + 2.0 * PI * i / vertices
        val point = Offset(
            width * (.5f + radius * cos(angle).toFloat()),
            height * (.5f + radius * sin(angle).toFloat()),
        )
        if (i == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
    }
    path.close()
    return path
}

internal fun avatarPolygon(width: Float, height: Float, points: List<Pair<Float, Float>>): Path = Path().apply {
    points.forEachIndexed { index, point ->
        val x = width * point.first
        val y = height * point.second
        if (index == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

/**
 * Animates only this avatar's graphics layer. Motion, tempo and phase vary per model, so the 30
 * designs do not trigger recomposition of the surrounding screen while they move.
 */
@Composable
fun AnimatedAgentFace(
    color: Color,
    face: String,
    size: Dp = 40.dp,
    modifier: Modifier = Modifier,
) {
    val resolvedFace = when (face) {
        "wink" -> "blob"
        "smile" -> "square"
        "side" -> "pill"
        "dot" -> "circle"
        else -> face
    }
    val index = agentFaces.indexOf(resolvedFace).coerceAtLeast(0)
    val motion = index % 10
    val duration = 1320 + (index * 137 % 1560)
    val transition = rememberInfiniteTransition(label = "avatar-$resolvedFace")
    val progress = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = duration, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "avatar-motion-$resolvedFace",
    )
    AgentFace(
        color = color,
        face = face,
        size = size,
        modifier = modifier
            .graphicsLayer {
                val phase = progress.value * (2f * PI.toFloat()) + index * .61f
                val wave = sin(phase)
                val wave2 = sin(phase * 1.7f + .9f)
                val amplitude = .025f + (index % 5) * .008f
                val extent = size.toPx()
                translationX = when (motion) {
                    1, 5, 8 -> wave2 * extent * amplitude * 1.8f
                    else -> 0f
                }
                translationY = when (motion) {
                    0, 1, 5, 7, 9 -> -abs(wave) * extent * amplitude * 2.2f
                    2 -> wave2 * extent * amplitude
                    else -> 0f
                }
                rotationZ = when (motion) {
                    2 -> wave * (4f + index % 4)
                    4 -> wave * (9f + index % 6)
                    7 -> wave2 * (5f + index % 5)
                    8 -> wave * (3f + index % 3)
                    else -> 0f
                }
                scaleX = when (motion) {
                    0, 6 -> 1f + abs(wave) * amplitude
                    3 -> 1f + wave * amplitude
                    5, 8 -> 1f + wave2 * amplitude * .65f
                    else -> 1f
                }
                scaleY = when (motion) {
                    6 -> 1f - abs(wave) * amplitude * 1.4f
                    9 -> 1f - abs(wave2) * amplitude * 1.8f
                    else -> scaleX
                }
            },
    )
}

/** Shows a changing sample mascot while keeping timer state local to this small subtree. */
@Composable
fun RotatingAgentFace(
    size: Dp,
    modifier: Modifier = Modifier,
    intervalMillis: Long = 5_000L,
    enabled: Boolean = true,
) {
    var face by remember { mutableStateOf(agentFaces.random()) }
    LaunchedEffect(enabled, intervalMillis) {
        if (!enabled) return@LaunchedEffect
        while (true) {
            delay(intervalMillis)
            face = agentFaces.asSequence().filterNot { it == face }.toList().random()
        }
    }
    val index = agentFaces.indexOf(face).coerceAtLeast(0)
    AnimatedAgentFace(Color(agentColors[index % agentColors.size]), face, size, modifier)
}
