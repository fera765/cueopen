package dev.cueopen.app.ui.mascot

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Paleta derivada da cor do Agent: corpo, sombra, luz e tinta do rosto. */
internal class MascotPalette(base: Color) {
    val body = base
    val shade = lerp(base, Color.Black, .22f)
    val deep = lerp(base, Color.Black, .45f)
    val light = lerp(base, Color.White, .4f)
    val shine = Color.White.copy(alpha = .3f)
    val ink = Color(0xFF16161C)
    val cheek = Color(0xFFFF7A95).copy(alpha = .42f)
}

// ---------- partes do rosto compartilhadas (mesmo "estilo de casa" em todos os mascotes) ----------

internal fun DrawScope.mascotEyes(cx: Float, cy: Float, gap: Float, r: Float, pose: MascotPose, ink: Color, tall: Float = 1.18f) {
    val open = (1f - pose.blink).coerceIn(0f, 1f)
    for (side in intArrayOf(-1, 1)) {
        val x = cx + side * gap + pose.lookX * r * .55f
        val y = cy + pose.lookY * r * .45f
        if (open < .2f) {
            val p = Path().apply { moveTo(x - r, y); quadraticBezierTo(x, y + r * .8f, x + r, y) }
            drawPath(p, ink, style = Stroke(r * .45f, cap = StrokeCap.Round))
        } else {
            val eh = r * tall * open
            drawOval(ink, Offset(x - r, y - eh), Size(r * 2, eh * 2))
            drawCircle(Color.White, r * .34f, Offset(x - r * .32f, y - eh * .38f))
        }
    }
}

internal fun DrawScope.mascotMouth(cx: Float, cy: Float, width: Float, pose: MascotPose, ink: Color) {
    when {
        pose.talk > .06f -> {
            val mh = width * (.18f + .5f * pose.talk)
            drawOval(ink, Offset(cx - width * .38f, cy - mh * .35f), Size(width * .76f, mh))
            drawOval(Color(0xFFFF6F86), Offset(cx - width * .2f, cy + mh * .2f), Size(width * .4f, mh * .38f))
        }
        pose.state == MascotState.THINKING -> drawLine(ink, Offset(cx - width * .22f, cy), Offset(cx + width * .18f, cy - width * .06f), width * .14f, StrokeCap.Round)
        pose.state == MascotState.SLEEPING -> drawCircle(ink, width * .1f, Offset(cx, cy))
        else -> {
            val p = Path().apply { moveTo(cx - width * .5f, cy - width * .08f); quadraticBezierTo(cx, cy + width * .42f, cx + width * .5f, cy - width * .08f) }
            drawPath(p, ink, style = Stroke(width * .15f, cap = StrokeCap.Round))
        }
    }
}

internal fun DrawScope.mascotCheeks(cx: Float, cy: Float, gap: Float, r: Float, color: Color) {
    drawOval(color, Offset(cx - gap - r, cy - r * .6f), Size(r * 2, r * 1.2f))
    drawOval(color, Offset(cx + gap - r, cy - r * .6f), Size(r * 2, r * 1.2f))
}

/** "Zz" flutuando quando o mascote dorme. */
internal fun DrawScope.mascotZzz(x: Float, y: Float, s: Float, t: Float, ink: Color) {
    for (i in 0..1) {
        val ph = ((t * .6f + i * .5f) % 1f)
        val zx = x + i * s * .9f + sin(ph * 6f) * s * .15f
        val zy = y - ph * s * 2.2f
        val k = s * (.55f + i * .25f)
        val p = Path().apply { moveTo(zx, zy); lineTo(zx + k, zy); lineTo(zx, zy + k); lineTo(zx + k, zy + k) }
        drawPath(p, ink.copy(alpha = (1f - ph) * .8f), style = Stroke(k * .22f, cap = StrokeCap.Round))
    }
}

private fun DrawScope.shine(x: Float, y: Float, rw: Float, rh: Float, pal: MascotPalette) {
    drawOval(pal.shine, Offset(x - rw, y - rh), Size(rw * 2, rh * 2))
}

private fun wave(t: Float, speed: Float, phase: Float = 0f) = sin(t * speed + phase)

// ---------- os 12 mascotes ----------

internal fun DrawScope.drawBuiltinMascot(id: String, color: Color, pose: MascotPose) {
    val pal = MascotPalette(color)
    when (id) {
        "bot" -> drawBot(pal, pose)
        "ghost" -> drawGhost(pal, pose)
        "cat" -> drawCat(pal, pose)
        "owl" -> drawOwl(pal, pose)
        "octo" -> drawOcto(pal, pose)
        "sprout" -> drawSprout(pal, pose)
        "cloud" -> drawCloud(pal, pose)
        "star" -> drawStar(pal, pose)
        "bee" -> drawBee(pal, pose)
        "flame" -> drawFlame(pal, pose)
        "orbit" -> drawOrbit(pal, pose)
        else -> drawJelly(pal, pose)
    }
    if (pose.state == MascotState.SLEEPING) mascotZzz(size.width * .74f, size.height * .26f, size.width * .1f, pose.t, pal.ink)
}

/** Geleia: squash & stretch apoiada no chão; bolhas sobem por dentro quando pensa. */
private fun DrawScope.drawJelly(pal: MascotPalette, pose: MascotPose) {
    val w = size.width; val h = size.height; val t = pose.t
    val sq = wave(t, if (pose.state == MascotState.TALKING) 6f else 3f) * .06f
    withTransform({ scale(1f + sq, 1f - sq, Offset(w * .5f, h * .9f)) }) {
        val body = Path().apply {
            moveTo(w * .13f, h * .86f)
            cubicTo(w * .06f, h * .5f, w * .26f, h * .15f, w * .5f, h * .15f)
            cubicTo(w * .74f, h * .15f, w * .94f, h * .5f, w * .87f, h * .86f)
            quadraticBezierTo(w * .69f, h * .94f, w * .5f, h * .88f)
            quadraticBezierTo(w * .31f, h * .94f, w * .13f, h * .86f)
            close()
        }
        drawPath(body, pal.body)
        clipPath(body) {
            drawOval(pal.shade, Offset(w * .05f, h * .7f), Size(w * .9f, h * .4f))
            if (pose.state == MascotState.THINKING) for (i in 0..3) {
                val ph = (t * .5f + i * .27f) % 1f
                drawCircle(pal.light.copy(alpha = .7f * (1f - ph)), w * (.025f + i * .008f), Offset(w * (.3f + i * .14f), h * (.84f - ph * .6f)))
            }
        }
        shine(w * .32f, h * .3f, w * .1f, h * .06f, pal)
        mascotEyes(w * .5f, h * .5f, w * .13f, w * .055f, pose, pal.ink)
        mascotCheeks(w * .5f, h * .62f, w * .24f, w * .05f, pal.cheek)
        mascotMouth(w * .5f, h * .65f, w * .12f, pose, pal.ink)
    }
}

/** Pixel: robô que paira; antena balança e pisca; olhos de LED fazem varredura ao pensar. */
private fun DrawScope.drawBot(pal: MascotPalette, pose: MascotPose) {
    val w = size.width; val h = size.height; val t = pose.t
    val hover = wave(t, 2f) * h * .03f
    withTransform({ translate(0f, hover) }) {
        // sombra no chão acompanha a altura
        val tipX = w * .5f + wave(t, 3f) * w * .06f
        drawLine(pal.deep, Offset(w * .5f, h * .3f), Offset(tipX, h * .13f), w * .035f, StrokeCap.Round)
        val glow = (wave(t, 4f) + 1f) / 2f
        drawCircle(lerp(pal.light, Color.White, glow), w * .055f, Offset(tipX, h * .12f))
        drawCircle(pal.shade, w * .06f, Offset(w * .17f, h * .55f))
        drawCircle(pal.shade, w * .06f, Offset(w * .83f, h * .55f))
        drawRoundRect(pal.body, Offset(w * .18f, h * .28f), Size(w * .64f, h * .54f), CornerRadius(w * .16f))
        drawRoundRect(pal.shade, Offset(w * .18f, h * .7f), Size(w * .64f, h * .12f), CornerRadius(w * .16f))
        drawRoundRect(pal.ink, Offset(w * .26f, h * .38f), Size(w * .48f, h * .26f), CornerRadius(w * .1f))
        val led = lerp(pal.light, Color.White, .45f)
        if (pose.state == MascotState.THINKING) {
            val x = w * .5f + sin(t * 3f) * w * .14f
            drawRoundRect(led, Offset(x - w * .07f, h * .49f), Size(w * .14f, h * .04f), CornerRadius(w * .02f))
        } else {
            val open = (1f - pose.blink).coerceIn(.12f, 1f)
            for (side in intArrayOf(-1, 1)) {
                val x = w * .5f + side * w * .11f + pose.lookX * w * .03f
                val eh = h * .1f * open
                drawRoundRect(led, Offset(x - w * .045f, h * .51f - eh / 2), Size(w * .09f, eh), CornerRadius(w * .03f))
            }
        }
        val mouthH = h * (.018f + pose.talk * .05f)
        drawRoundRect(pal.deep, Offset(w * .4f, h * .69f - mouthH / 2), Size(w * .2f, mouthH), CornerRadius(w * .02f))
        shine(w * .3f, h * .33f, w * .07f, h * .03f, pal)
    }
}

/** Bú: fantasminha que flutua; a barra ondula. */
private fun DrawScope.drawGhost(pal: MascotPalette, pose: MascotPose) {
    val w = size.width; val h = size.height; val t = pose.t
    withTransform({
        translate(0f, wave(t, 1.6f) * h * .04f)
        rotate(wave(t, 1.1f) * 4f, Offset(w * .5f, h * .5f))
    }) {
        val body = Path().apply {
            moveTo(w * .18f, h * .82f)
            lineTo(w * .18f, h * .44f)
            cubicTo(w * .18f, h * .2f, w * .34f, h * .1f, w * .5f, h * .1f)
            cubicTo(w * .66f, h * .1f, w * .82f, h * .2f, w * .82f, h * .44f)
            lineTo(w * .82f, h * .82f)
            val bumps = 4
            for (i in 0 until bumps) {
                val x0 = w * (.82f - .64f * i / bumps); val x1 = w * (.82f - .64f * (i + 1) / bumps)
                val cy = h * (if (i % 2 == 0) .93f else .76f) + wave(t, 4f, i * 1.3f) * h * .03f
                quadraticBezierTo((x0 + x1) / 2, cy, x1, h * .82f)
            }
            close()
        }
        drawPath(body, pal.body.copy(alpha = .96f))
        shine(w * .34f, h * .24f, w * .08f, h * .05f, pal)
        mascotEyes(w * .5f, h * .44f, w * .12f, w * .06f, pose, pal.ink, tall = 1.5f)
        mascotCheeks(w * .5f, h * .56f, w * .22f, w * .045f, pal.cheek)
        if (pose.talk > .06f || pose.state != MascotState.IDLE) mascotMouth(w * .5f, h * .6f, w * .1f, pose, pal.ink)
        else drawOval(pal.ink, Offset(w * .47f, h * .57f), Size(w * .06f, h * .07f))
    }
}

/** Mia: gatinho; pisca devagar, orelha dá um tique e o rabo balança. */
private fun DrawScope.drawCat(pal: MascotPalette, pose: MascotPose) {
    val w = size.width; val h = size.height; val t = pose.t
    val tailX = w * .9f + wave(t, 2f) * w * .05f
    val tail = Path().apply { moveTo(w * .7f, h * .82f); cubicTo(w * .95f, h * .86f, w * .98f, h * .6f, tailX, h * .42f) }
    drawPath(tail, pal.shade, style = Stroke(w * .08f, cap = StrokeCap.Round))
    val twitchPhase = (t % 4.2f)
    val twitch = if (twitchPhase < .3f) sin(twitchPhase / .3f * PI.toFloat()) * 14f else 0f
    for (side in intArrayOf(-1, 1)) {
        val bx = w * .5f + side * w * .24f
        val ear = Path().apply { moveTo(bx - side * w * .14f, h * .4f); lineTo(bx + side * w * .04f, h * .12f); lineTo(bx + side * w * .12f, h * .44f); close() }
        val inner = Path().apply { moveTo(bx - side * w * .06f, h * .38f); lineTo(bx + side * w * .035f, h * .2f); lineTo(bx + side * w * .07f, h * .4f); close() }
        rotate(if (side == 1) twitch else 0f, Offset(bx, h * .4f)) {
            drawPath(ear, pal.body); drawPath(inner, pal.cheek.copy(alpha = .8f))
        }
    }
    drawOval(pal.body, Offset(w * .14f, h * .3f), Size(w * .72f, h * .58f))
    drawOval(pal.light.copy(alpha = .55f), Offset(w * .32f, h * .6f), Size(w * .36f, h * .24f))
    shine(w * .33f, h * .4f, w * .07f, h * .04f, pal)
    val slow = if (pose.state == MascotState.IDLE) pose.copy(blink = maxOf(pose.blink, (sin(t * .4f) + 1f) * .18f)) else pose
    mascotEyes(w * .5f, h * .53f, w * .14f, w * .055f, slow, pal.ink)
    drawPath(Path().apply { moveTo(w * .47f, h * .63f); lineTo(w * .53f, h * .63f); lineTo(w * .5f, h * .665f); close() }, Color(0xFFFF6F86))
    if (pose.talk > .06f) mascotMouth(w * .5f, h * .72f, w * .1f, pose, pal.ink)
    else {
        val m = Path().apply {
            moveTo(w * .43f, h * .69f); quadraticBezierTo(w * .465f, h * .73f, w * .5f, h * .675f)
            quadraticBezierTo(w * .535f, h * .73f, w * .57f, h * .69f)
        }
        drawPath(m, pal.ink, style = Stroke(w * .018f, cap = StrokeCap.Round))
    }
    for (side in intArrayOf(-1, 1)) for (k in 0..1) {
        val y = h * (.64f + k * .05f)
        drawLine(pal.ink.copy(alpha = .35f), Offset(w * .5f + side * w * .16f, y), Offset(w * .5f + side * w * .36f, y - h * .02f + k * h * .03f), w * .012f, StrokeCap.Round)
    }
}

/** Corujo: inclina a cabeça e varre o ambiente com olhos grandes; pálpebras descem ao piscar. */
private fun DrawScope.drawOwl(pal: MascotPalette, pose: MascotPose) {
    val w = size.width; val h = size.height; val t = pose.t
    val tilt = if (pose.state == MascotState.THINKING) 14f + wave(t, 1.2f) * 4f else wave(t, .9f) * 8f
    rotate(tilt, Offset(w * .5f, h * .8f)) {
        val wingFlap = if (pose.state == MascotState.TALKING) wave(t, 10f) * 8f else wave(t, 2f) * 3f
        for (side in intArrayOf(-1, 1)) rotate(side * wingFlap, Offset(w * .5f + side * w * .26f, h * .5f)) {
            drawOval(pal.shade, Offset(w * .5f + side * w * .3f - w * .1f, h * .45f), Size(w * .2f, h * .36f))
        }
        for (side in intArrayOf(-1, 1)) {
            val tuft = Path().apply { moveTo(w * .5f + side * w * .1f, h * .2f); lineTo(w * .5f + side * w * .3f, h * .08f); lineTo(w * .5f + side * w * .28f, h * .28f); close() }
            drawPath(tuft, pal.shade)
        }
        drawOval(pal.body, Offset(w * .2f, h * .14f), Size(w * .6f, h * .76f))
        drawOval(pal.light.copy(alpha = .6f), Offset(w * .32f, h * .5f), Size(w * .36f, h * .36f))
        val eyeR = w * .13f
        for (side in intArrayOf(-1, 1)) {
            val c = Offset(w * .5f + side * w * .14f, h * .38f)
            drawCircle(Color.White, eyeR, c)
            val p = Offset(c.x + pose.lookX * eyeR * .4f, c.y + pose.lookY * eyeR * .35f)
            drawCircle(pal.ink, eyeR * .5f, p)
            drawCircle(Color.White, eyeR * .16f, Offset(p.x - eyeR * .17f, p.y - eyeR * .17f))
            val lid = pose.blink.coerceIn(0f, 1f)
            if (lid > .02f) drawArc(pal.body, 180f, 180f, true, Offset(c.x - eyeR, c.y - eyeR), Size(eyeR * 2, eyeR * 2 * lid))
        }
        val beakOpen = pose.talk * h * .04f
        drawPath(Path().apply { moveTo(w * .45f, h * .5f); lineTo(w * .55f, h * .5f); lineTo(w * .5f, h * .58f); close() }, Color(0xFFFFB238))
        if (beakOpen > 1f) drawPath(Path().apply { moveTo(w * .46f, h * .55f + beakOpen); lineTo(w * .54f, h * .55f + beakOpen); lineTo(w * .5f, h * .6f + beakOpen); close() }, Color(0xFFE09420))
    }
}

/** Polvo: tentáculos em onda contínua; acelera quando fala. */
private fun DrawScope.drawOcto(pal: MascotPalette, pose: MascotPose) {
    val w = size.width; val h = size.height; val t = pose.t
    val speed = if (pose.state == MascotState.TALKING) 5f else 2.6f
    val bob = wave(t, 1.5f) * h * .02f
    withTransform({ translate(0f, bob) }) {
        for (i in 0 until 5) {
            val x0 = w * (.26f + i * .12f)
            val p = Path().apply {
                moveTo(x0, h * .56f)
                var y = h * .56f
                var x = x0
                val steps = 6
                for (s in 1..steps) {
                    val ny = h * .56f + h * .34f * s / steps
                    val nx = x0 + (i - 2) * w * .02f * s + sin(t * speed + i * .9f + s * .8f) * w * .035f
                    quadraticBezierTo(x, (y + ny) / 2, nx, ny); x = nx; y = ny
                }
            }
            drawPath(p, if (i % 2 == 0) pal.body else pal.shade, style = Stroke(w * .085f, cap = StrokeCap.Round))
        }
        drawOval(pal.body, Offset(w * .18f, h * .12f), Size(w * .64f, h * .56f))
        drawOval(pal.shade.copy(alpha = .5f), Offset(w * .2f, h * .5f), Size(w * .6f, h * .16f))
        for (k in 0..2) drawCircle(pal.light.copy(alpha = .5f), w * (.025f + k * .01f), Offset(w * (.66f - k * .06f), h * (.24f + k * .06f)))
        shine(w * .34f, h * .24f, w * .08f, h * .05f, pal)
        mascotEyes(w * .5f, h * .4f, w * .13f, w * .055f, pose, pal.ink)
        mascotCheeks(w * .5f, h * .5f, w * .22f, w * .045f, pal.cheek)
        mascotMouth(w * .5f, h * .53f, w * .1f, pose, pal.ink)
    }
}

/** Broto: semente com folhas que balançam; giram como hélice quando pensa. */
private fun DrawScope.drawSprout(pal: MascotPalette, pose: MascotPose) {
    val w = size.width; val h = size.height; val t = pose.t
    val leaf = lerp(pal.body, Color(0xFF3DBE6E), .55f)
    val leafDeep = lerp(leaf, Color.Black, .2f)
    val stemTop = Offset(w * .5f + wave(t, 1.6f) * w * .02f, h * .24f)
    drawLine(leafDeep, Offset(w * .5f, h * .42f), stemTop, w * .035f, StrokeCap.Round)
    val sway = if (pose.state == MascotState.THINKING) t * 240f else wave(t, 2f) * 14f
    rotate(sway, stemTop) {
        for (side in intArrayOf(-1, 1)) {
            val p = Path().apply {
                moveTo(stemTop.x, stemTop.y)
                quadraticBezierTo(stemTop.x + side * w * .1f, stemTop.y - h * .16f, stemTop.x + side * w * .26f, stemTop.y - h * .06f)
                quadraticBezierTo(stemTop.x + side * w * .12f, stemTop.y + h * .06f, stemTop.x, stemTop.y)
                close()
            }
            drawPath(p, if (side < 0) leaf else leafDeep)
        }
    }
    val breathe = 1f + wave(t, 2.2f) * .025f
    withTransform({ scale(breathe, 2f - breathe, Offset(w * .5f, h * .92f)) }) {
        drawOval(pal.body, Offset(w * .17f, h * .38f), Size(w * .66f, h * .54f))
        drawOval(pal.shade, Offset(w * .2f, h * .74f), Size(w * .6f, h * .16f))
        shine(w * .34f, h * .48f, w * .08f, h * .045f, pal)
        mascotEyes(w * .5f, h * .6f, w * .13f, w * .055f, pose, pal.ink)
        mascotCheeks(w * .5f, h * .71f, w * .23f, w * .045f, pal.cheek)
        mascotMouth(w * .5f, h * .74f, w * .1f, pose, pal.ink)
    }
}

/** Nimbo: nuvem que deriva; garoa quando pensa. */
private fun DrawScope.drawCloud(pal: MascotPalette, pose: MascotPose) {
    val w = size.width; val h = size.height; val t = pose.t
    withTransform({ translate(wave(t, .8f) * w * .04f, wave(t, 1.3f) * h * .015f) }) {
        if (pose.state == MascotState.THINKING) for (i in 0..2) {
            val ph = (t * 1.2f + i * .33f) % 1f
            val x = w * (.34f + i * .16f)
            drawLine(lerp(pal.light, Color(0xFF7FB8FF), .5f).copy(alpha = 1f - ph), Offset(x, h * (.72f + ph * .2f)), Offset(x - w * .015f, h * (.78f + ph * .2f)), w * .03f, StrokeCap.Round)
        }
        val shapes = listOf(Triple(.3f, .52f, .17f), Triple(.5f, .4f, .22f), Triple(.7f, .52f, .17f))
        drawRoundRect(pal.body, Offset(w * .14f, h * .48f), Size(w * .72f, h * .24f), CornerRadius(w * .12f))
        shapes.forEach { (x, y, r) -> drawCircle(pal.body, w * r, Offset(w * x, h * y)) }
        drawRoundRect(pal.shade.copy(alpha = .45f), Offset(w * .14f, h * .62f), Size(w * .72f, h * .1f), CornerRadius(w * .1f))
        shine(w * .42f, h * .27f, w * .08f, h * .04f, pal)
        mascotEyes(w * .5f, h * .5f, w * .13f, w * .05f, pose, pal.ink)
        mascotCheeks(w * .5f, h * .6f, w * .24f, w * .045f, pal.cheek)
        mascotMouth(w * .5f, h * .62f, w * .1f, pose, pal.ink)
    }
}

/** Estelar: estrela que pulsa e oscila, com brilhos piscando em volta. */
private fun DrawScope.drawStar(pal: MascotPalette, pose: MascotPose) {
    val w = size.width; val h = size.height; val t = pose.t
    for (i in 0..2) {
        val a = (t * .7f + i * 2.1f)
        val k = ((sin(t * 2.4f + i * 2f) + 1f) / 2f)
        val c = Offset(w * (.5f + cos(a) * .42f), h * (.52f + sin(a) * .4f))
        sparkle(c, w * .045f * k, lerp(pal.light, Color.White, .6f).copy(alpha = k))
    }
    val pulse = 1f + wave(t, if (pose.state == MascotState.TALKING) 7f else 3f) * .04f
    withTransform({
        rotate(wave(t, .8f) * 10f + if (pose.state == MascotState.THINKING) t * 40f else 0f, Offset(w * .5f, h * .54f))
        scale(pulse, pulse, Offset(w * .5f, h * .54f))
    }) {
        drawPath(roundedStar(w * .5f, h * .55f, w * .44f, w * .22f), pal.body)
        drawPath(roundedStar(w * .5f, h * .6f, w * .3f, w * .15f), pal.shade.copy(alpha = .35f))
    }
    shine(w * .38f, h * .38f, w * .06f, h * .035f, pal)
    mascotEyes(w * .5f, h * .53f, w * .1f, w * .045f, pose, pal.ink)
    mascotMouth(w * .5f, h * .65f, w * .09f, pose, pal.ink)
}

private fun roundedStar(cx: Float, cy: Float, outer: Float, inner: Float): Path = Path().apply {
    val n = 10
    val pts = (0 until n).map { i ->
        val r = if (i % 2 == 0) outer else inner
        val a = -PI / 2 + 2 * PI * i / n
        Offset(cx + r * cos(a).toFloat(), cy + r * sin(a).toFloat())
    }
    // cantos suavizados: cada vértice vira uma curva entre os pontos médios das arestas
    fun mid(a: Offset, b: Offset) = Offset((a.x + b.x) / 2, (a.y + b.y) / 2)
    val start = mid(pts.last(), pts[0])
    moveTo(start.x, start.y)
    for (i in 0 until n) {
        val m = mid(pts[i], pts[(i + 1) % n])
        quadraticBezierTo(pts[i].x, pts[i].y, m.x, m.y)
    }
    close()
}

private fun DrawScope.sparkle(c: Offset, r: Float, color: Color) {
    if (r <= .5f) return
    val p = Path().apply {
        moveTo(c.x, c.y - r); quadraticBezierTo(c.x, c.y, c.x + r, c.y); quadraticBezierTo(c.x, c.y, c.x, c.y + r)
        quadraticBezierTo(c.x, c.y, c.x - r, c.y); quadraticBezierTo(c.x, c.y, c.x, c.y - r); close()
    }
    drawPath(p, color)
}

/** Zum: abelhinha; asas batem rápido e o voo faz zigue-zague. */
private fun DrawScope.drawBee(pal: MascotPalette, pose: MascotPose) {
    val w = size.width; val h = size.height; val t = pose.t
    withTransform({ translate(wave(t, 1.3f) * w * .05f, wave(t, 2.6f) * h * .035f); rotate(wave(t, 1.3f) * 6f, Offset(w * .5f, h * .55f)) }) {
        val flap = abs(sin(t * 26f))
        for (side in intArrayOf(-1, 1)) {
            val wx = w * .5f + side * w * .14f
            withTransform({ scale(1f, .45f + .55f * flap, Offset(wx, h * .38f)) }) {
                drawOval(Color.White.copy(alpha = .72f), Offset(wx - w * .12f, h * .14f), Size(w * .24f, h * .26f))
            }
        }
        for (side in intArrayOf(-1, 1)) {
            val tip = Offset(w * .5f + side * w * .14f + wave(t, 3f, side.toFloat()) * w * .02f, h * .14f)
            drawLine(pal.ink, Offset(w * .5f + side * w * .06f, h * .34f), tip, w * .02f, StrokeCap.Round)
            drawCircle(pal.ink, w * .03f, tip)
        }
        drawPath(Path().apply { moveTo(w * .8f, h * .6f); lineTo(w * .92f, h * .64f); lineTo(w * .8f, h * .7f); close() }, pal.ink)
        val body = Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(w * .2f, h * .32f), Size(w * .62f, h * .54f))) }
        drawPath(body, pal.body)
        clipPath(body) {
            for (k in 0..1) drawRect(pal.ink.copy(alpha = .85f), Offset(w * (.56f + k * .14f), h * .3f), Size(w * .07f, h * .6f))
            drawOval(pal.shade.copy(alpha = .5f), Offset(w * .18f, h * .72f), Size(w * .66f, h * .2f))
        }
        shine(w * .34f, h * .42f, w * .07f, h * .04f, pal)
        mascotEyes(w * .4f, h * .56f, w * .09f, w * .045f, pose, pal.ink)
        mascotCheeks(w * .4f, h * .66f, w * .16f, w * .04f, pal.cheek)
        mascotMouth(w * .4f, h * .69f, w * .09f, pose, pal.ink)
    }
}

/** Chama: labareda que tremeluz; cresce quando fala. */
private fun DrawScope.drawFlame(pal: MascotPalette, pose: MascotPose) {
    val w = size.width; val h = size.height; val t = pose.t
    val flick = sin(t * 7f) * .04f + sin(t * 11.3f) * .02f
    val grow = 1f + (if (pose.state == MascotState.TALKING) .06f * pose.talk else 0f) + sin(t * 9f) * .02f
    fun flame(cx: Float, base: Float, width: Float, height: Float, tipShift: Float) = Path().apply {
        moveTo(cx, base)
        cubicTo(cx - width * .62f, base, cx - width * .58f, base - height * .5f, cx - width * .18f, base - height * .7f)
        quadraticBezierTo(cx - width * .05f + tipShift, base - height * .85f, cx + tipShift, base - height)
        quadraticBezierTo(cx + width * .1f + tipShift, base - height * .72f, cx + width * .3f, base - height * .58f)
        cubicTo(cx + width * .62f, base - height * .35f, cx + width * .55f, base, cx, base)
        close()
    }
    withTransform({ scale(1f - flick * .5f, grow, Offset(w * .5f, h * .9f)) }) {
        drawPath(flame(w * .5f, h * .9f, w * .7f, h * .8f, flick * w * 2f), pal.body)
        drawPath(flame(w * .5f, h * .9f, w * .42f, h * .5f, -flick * w * 1.5f), lerp(pal.light, Color(0xFFFFE08A), .5f).copy(alpha = .85f))
    }
    mascotEyes(w * .5f, h * .62f, w * .1f, w * .045f, pose, pal.ink)
    mascotMouth(w * .5f, h * .74f, w * .09f, pose, pal.ink)
}

/** Orbi: planetinha com anel e uma lua em órbita (passa na frente e atrás). */
private fun DrawScope.drawOrbit(pal: MascotPalette, pose: MascotPose) {
    val w = size.width; val h = size.height; val t = pose.t
    val speed = if (pose.state == MascotState.THINKING) 3.6f else 1.6f
    val a = t * speed
    val moon = Offset(w * (.5f + cos(a) * .42f), h * (.55f + sin(a) * .14f))
    val moonFront = sin(a) > 0f
    val bob = wave(t, 1.4f) * h * .02f
    withTransform({ translate(0f, bob) }) {
        drawArc(pal.deep, 180f, 180f, false, Offset(w * .06f, h * .44f), Size(w * .88f, h * .22f), style = Stroke(w * .035f))
        if (!moonFront) drawCircle(pal.light, w * .07f, moon)
        drawCircle(pal.body, w * .29f, Offset(w * .5f, h * .53f))
        drawArc(pal.shade, 20f, 140f, false, Offset(w * .21f, h * .24f), Size(w * .58f, w * .58f), style = Stroke(w * .05f))
        drawArc(pal.deep, 0f, 180f, false, Offset(w * .06f, h * .44f), Size(w * .88f, h * .22f), style = Stroke(w * .035f))
        if (moonFront) drawCircle(pal.light, w * .07f, moon)
        shine(w * .4f, h * .36f, w * .07f, h * .04f, pal)
        mascotEyes(w * .5f, h * .5f, w * .11f, w * .05f, pose, pal.ink)
        mascotCheeks(w * .5f, h * .6f, w * .2f, w * .04f, pal.cheek)
        mascotMouth(w * .5f, h * .63f, w * .09f, pose, pal.ink)
    }
}
