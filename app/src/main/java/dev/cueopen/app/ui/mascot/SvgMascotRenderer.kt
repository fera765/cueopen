package dev.cueopen.app.ui.mascot

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.DrawTransform
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import dev.cueopen.app.ui.mascot.SvgMascotParser.Role

/** Movimento global (pixels do canvas) — usado por mascotes importados. */
internal fun DrawTransform.applyMotion(motion: MascotMotion, t: Float, w: Float, h: Float, state: MascotState) {
    val fast = if (state == MascotState.TALKING) 1.8f else 1f
    when (motion) {
        MascotMotion.FLOAT -> translate(0f, sin(t * 1.6f * fast) * h * .04f)
        MascotMotion.BOUNCE -> {
            val b = abs(sin(t * 3f * fast))
            translate(0f, -b * h * .07f)
            val squash = (1f - b).let { it * it } * .06f
            scale(1f + squash, 1f - squash, Offset(w / 2, h))
        }
        MascotMotion.SWAY -> rotate(sin(t * 1.5f * fast) * 7f, Offset(w / 2, h * .95f))
        MascotMotion.PULSE -> { val s = 1f + sin(t * 3f * fast) * .05f; scale(s, s, Offset(w / 2, h / 2)) }
        MascotMotion.JELLY -> { val s = sin(t * 3f * fast) * .06f; scale(1f + s, 1f - s, Offset(w / 2, h * .95f)) }
        MascotMotion.NONE -> Unit
    }
}

/** Forma pronta para desenhar (caminho já transformado para coordenadas do viewBox). */
internal class PreparedShape(
    val path: Path,
    val fill: Brush?, val fillIsAgentColor: Boolean,
    val stroke: Brush?, val strokeIsAgentColor: Boolean,
    val strokeWidth: Float, val fillAlpha: Float, val strokeAlpha: Float, val roundCap: Boolean,
    val part: Int,
)

internal class PreparedPart(val role: Role, val bounds: Rect, val side: Float)

internal class PreparedSvg(val model: SvgMascotParser.Model, val shapes: List<PreparedShape>, val parts: List<PreparedPart>)

internal fun prepareSvg(model: SvgMascotParser.Model): PreparedSvg {
    val partIndex = LinkedHashMap<String, Int>()
    val partBounds = ArrayList<Rect>()
    val partRoles = ArrayList<Role>()
    val shapes = model.shapes.mapNotNull { s ->
        val path = runCatching { PathParser().parsePathString(s.pathData).toPath() }.getOrNull() ?: return@mapNotNull null
        if (!s.transform.isIdentity) {
            val m = android.graphics.Matrix().apply {
                setValues(floatArrayOf(s.transform.a, s.transform.c, s.transform.e, s.transform.b, s.transform.d, s.transform.f, 0f, 0f, 1f))
            }
            path.asAndroidPath().transform(m)
        }
        if (s.evenOdd) path.fillType = PathFillType.EvenOdd
        val bounds = path.getBounds()
        val part = if (s.role == Role.NONE) -1 else partIndex.getOrPut(s.role.name + "|" + s.partKey) {
            partBounds += bounds; partRoles += s.role; partBounds.size - 1
        }.also { i -> partBounds[i] = partBounds[i].union(bounds) }
        val scale = sqrt(abs(s.transform.a * s.transform.d - s.transform.b * s.transform.c)).takeIf { it > 0f } ?: 1f
        PreparedShape(
            path,
            brush(s.fill, bounds, s.transform), s.fill == SvgMascotParser.Paint.Current,
            brush(s.stroke, bounds, s.transform), s.stroke == SvgMascotParser.Paint.Current,
            s.strokeWidth * scale, s.opacity * s.fillOpacity, s.opacity * s.strokeOpacity, s.roundCap, part,
        )
    }
    val midX = model.minX + model.width / 2
    val parts = partBounds.indices.map { i -> PreparedPart(partRoles[i], partBounds[i], if (partBounds[i].center.x < midX) -1f else 1f) }
    return PreparedSvg(model, shapes, parts)
}

private fun Rect.union(o: Rect) = Rect(min(left, o.left), min(top, o.top), maxOf(right, o.right), maxOf(bottom, o.bottom))

private fun brush(p: SvgMascotParser.Paint?, b: Rect, m: SvgMascotParser.Affine): Brush? = when (p) {
    null -> null
    SvgMascotParser.Paint.Current -> SolidColor(Color.Black) // trocado pela cor do Agent no desenho
    is SvgMascotParser.Paint.Solid -> SolidColor(Color(p.argb.toInt()))
    is SvgMascotParser.Paint.Linear -> {
        val stops = p.stops.map { it.first to Color(it.second.toInt()) }.toTypedArray()
        if (p.userSpace) Brush.linearGradient(*stops, start = map(m, p.x1, p.y1), end = map(m, p.x2, p.y2))
        else Brush.linearGradient(*stops, start = Offset(b.left + p.x1 * b.width, b.top + p.y1 * b.height), end = Offset(b.left + p.x2 * b.width, b.top + p.y2 * b.height))
    }
    is SvgMascotParser.Paint.Radial -> {
        val stops = p.stops.map { it.first to Color(it.second.toInt()) }.toTypedArray()
        if (p.userSpace) Brush.radialGradient(*stops, center = map(m, p.cx, p.cy), radius = (p.r * sqrt(abs(m.a * m.d - m.b * m.c))).coerceAtLeast(.01f))
        else Brush.radialGradient(*stops, center = Offset(b.left + p.cx * b.width, b.top + p.cy * b.height), radius = (p.r * (b.width + b.height) / 2).coerceAtLeast(.01f))
    }
}

private fun map(m: SvgMascotParser.Affine, x: Float, y: Float) = Offset(m.a * x + m.c * y + m.e, m.b * x + m.d * y + m.f)

/** Desenha o SVG preparado, animando cada parte nomeada (olho, boca, braço…) em torno do próprio pivô. */
internal fun DrawScope.drawPreparedSvg(svg: PreparedSvg, agentColor: Color, pose: MascotPose, motion: MascotMotion) {
    val vb = svg.model
    val w = size.width; val h = size.height
    val s = min(w / vb.width, h / vb.height)
    val ox = (w - vb.width * s) / 2; val oy = (h - vb.height * s) / 2
    val agent = SolidColor(agentColor)
    val t = pose.t
    withTransform({
        applyMotion(motion, t, w, h, pose.state)
        translate(ox, oy); scale(s, s, Offset.Zero); translate(-vb.minX, -vb.minY)
    }) {
        for (sh in svg.shapes) {
            val part = svg.parts.getOrNull(sh.part)
            var alphaMul = 1f
            val draw: DrawScope.() -> Unit = {
                sh.fill?.let { drawPath(sh.path, if (sh.fillIsAgentColor) agent else it, (sh.fillAlpha * alphaMul).coerceIn(0f, 1f), Fill) }
                sh.stroke?.let { if (sh.strokeWidth > 0f) drawPath(sh.path, if (sh.strokeIsAgentColor) agent else it, (sh.strokeAlpha * alphaMul).coerceIn(0f, 1f), Stroke(sh.strokeWidth, cap = if (sh.roundCap) StrokeCap.Round else StrokeCap.Butt)) }
            }
            if (part == null) { draw(); continue }
            if (part.role == Role.GLOW) alphaMul = .6f + .4f * ((sin(t * 3f) + 1f) / 2f)
            withTransform({ partTransform(part, pose) }, draw)
        }
    }
}

private fun DrawTransform.partTransform(p: PreparedPart, pose: MascotPose) {
    val b = p.bounds; val t = pose.t
    val inner = Offset(if (p.side < 0) b.right else b.left, b.center.y)
    when (p.role) {
        Role.EYE -> scale(1f, (1f - pose.blink).coerceAtLeast(.08f), b.center)
        Role.MOUTH -> scale(1f + pose.talk * .15f, 1f + pose.talk * .9f, Offset(b.center.x, b.top))
        Role.ARM -> rotate(-p.side * (sin(t * (if (pose.state == MascotState.TALKING) 6f else 3f) + p.side) * 14f + 4f), inner)
        Role.WING -> rotate(p.side * sin(t * 14f) * 18f, inner)
        Role.TAIL -> rotate(sin(t * 2f) * 16f, inner)
        Role.EAR -> { val ph = (t + p.side) % 4.2f; rotate(if (ph < .3f) sin(ph / .3f * Math.PI.toFloat()) * 12f * p.side else 0f, Offset(b.center.x, b.bottom)) }
        Role.ANTENNA -> rotate(sin(t * 3f) * 10f, Offset(b.center.x, b.bottom))
        Role.BODY -> { val k = sin(t * 2f) * .025f; scale(1f + k, 1f - k, Offset(b.center.x, b.bottom)) }
        Role.SPIN -> rotate((t * 90f) % 360f, b.center)
        Role.GLOW, Role.NONE -> Unit
    }
}

/** Renderização completa (AndroidSVG) para SVGs sem partes nomeadas; `currentColor` vira a cor do Agent. */
internal fun renderSvgBitmap(text: String, agentColor: Color, px: Int): ImageBitmap? = runCatching {
    val svg = com.caverock.androidsvg.SVG.getFromString(text)
    if (svg.documentViewBox == null) {
        val w = svg.documentWidth.takeIf { it > 0 } ?: px.toFloat(); val h = svg.documentHeight.takeIf { it > 0 } ?: px.toFloat()
        svg.setDocumentViewBox(0f, 0f, w, h)
    }
    svg.setDocumentWidth("100%"); svg.setDocumentHeight("100%")
    val bm = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bm)
    val hex = String.format("#%06X", agentColor.toArgb() and 0xFFFFFF)
    val withCss = runCatching {
        svg.renderToCanvas(canvas, com.caverock.androidsvg.RenderOptions.create().css("svg { color: $hex; }").viewPort(0f, 0f, px.toFloat(), px.toFloat()))
    }
    if (withCss.isFailure) svg.renderToCanvas(canvas, android.graphics.RectF(0f, 0f, px.toFloat(), px.toFloat()))
    bm.asImageBitmap()
}.getOrNull()

internal fun DrawScope.drawSvgBitmap(bmp: ImageBitmap, pose: MascotPose, motion: MascotMotion) {
    val w = size.width; val h = size.height
    val side = min(w, h)
    withTransform({ applyMotion(motion, pose.t, w, h, pose.state) }) {
        drawImage(bmp, IntOffset.Zero, IntSize(bmp.width, bmp.height), IntOffset(((w - side) / 2).toInt(), ((h - side) / 2).toInt()), IntSize(side.toInt(), side.toInt()))
    }
}
