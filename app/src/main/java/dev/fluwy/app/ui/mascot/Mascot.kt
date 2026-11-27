package dev.fluwy.app.ui.mascot

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Mascote do Agent. [face] é um ID nativo ("jelly", "bot"…), um ID antigo (migrado sozinho) ou
 * "custom:<id>" para um mascote importado. A animação roda só na fase de desenho: o relógio é lido
 * dentro do Canvas, então a tela em volta não recompõe a cada quadro.
 */
@Composable
fun Mascot(
    face: String,
    color: Color,
    size: Dp = 40.dp,
    modifier: Modifier = Modifier,
    state: MascotState = MascotState.IDLE,
    animated: Boolean = true,
) {
    val clock = remember { mutableFloatStateOf(0f) }
    if (animated) LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { clock.floatValue = (it - start) / 1_000_000_000f }
    }
    if (Mascots.isCustom(face)) {
        val ctx = LocalContext.current
        val lib = remember { MascotLibrary.get(ctx) }
        val all by lib.items.collectAsState()
        val custom = all.firstOrNull { it.id == Mascots.customId(face) }
        if (custom != null && CustomMascotView(custom, color, size, modifier, state, animated, clock)) return
        // apagado ou ilegível: cai num nativo para nunca deixar o Agent sem rosto
        BuiltinCanvas("jelly", color, size, modifier, state, animated, clock)
    } else BuiltinCanvas(Mascots.resolveBuiltin(face), color, size, modifier, state, animated, clock)
}

@Composable
private fun BuiltinCanvas(id: String, color: Color, size: Dp, modifier: Modifier, state: MascotState, animated: Boolean, clock: androidx.compose.runtime.MutableFloatState) {
    val seed = (abs(id.hashCode()) % 1000) / 100f
    Canvas(modifier.size(size)) {
        drawBuiltinMascot(id, color, MascotPose.at(clock.floatValue, seed, state, animated))
    }
}

/** @return false se não conseguiu desenhar (o chamador usa um mascote nativo). */
@Composable
private fun CustomMascotView(
    m: CustomMascot, color: Color, size: Dp, modifier: Modifier, state: MascotState, animated: Boolean,
    clock: androidx.compose.runtime.MutableFloatState,
): Boolean {
    val seed = (abs(m.id.hashCode()) % 1000) / 100f
    val motion = if (animated) m.motion else MascotMotion.NONE
    val model = m.model
    // Partes nomeadas (olho, boca, braço…) ou VectorDrawable → renderizador próprio, que anima cada parte.
    if (model != null && (model.hasAnimatedParts || m.format == "xml")) {
        val prepared = remember(m.id) { runCatching { prepareSvg(model) }.getOrNull() } ?: return false
        Canvas(modifier.size(size)) {
            drawPreparedSvg(prepared, color, MascotPose.at(clock.floatValue, seed, state, animated), motion)
        }
        return true
    }
    // SVG "comum" → AndroidSVG (fidelidade total) + movimento global escolhido na importação.
    val render by produceState<Pair<Boolean, ImageBitmap?>>(false to null, m.id, color) {
        value = true to withContext(Dispatchers.Default) { renderSvgBitmap(m.text, color, 384) }
    }
    if (!render.first) { androidx.compose.foundation.layout.Box(modifier.size(size)); return true } // ainda renderizando
    val b = render.second
    if (b == null) {
        if (model == null) return false
        val prepared = remember(m.id) { runCatching { prepareSvg(model) }.getOrNull() } ?: return false
        Canvas(modifier.size(size)) { drawPreparedSvg(prepared, color, MascotPose.at(clock.floatValue, seed, state, animated), motion) }
        return true
    }
    Canvas(modifier.size(size)) { drawSvgBitmap(b, MascotPose.at(clock.floatValue, seed, state, animated), motion) }
    return true
}
