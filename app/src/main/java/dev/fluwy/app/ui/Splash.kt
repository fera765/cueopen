package dev.fluwy.app.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.fluwy.app.ui.theme.Fluwy
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Splash animada: o robozinho "Pip" (corpo em gota, estilo do ícone) cai com mola, a antena balança,
 * os olhos piscam, a boca vira um prompt ">_" que digita "Fluwy Bot" — e tudo some em fade.
 */
@Composable
fun SplashScreen(onDone: () -> Unit) {
    val c = Fluwy.c
    val drop = remember { Animatable(-260f) }
    val squash = remember { Animatable(1f) }
    val alphaAll = remember { Animatable(1f) }
    val title = remember { Animatable(0f) }
    var typed by remember { mutableStateOf("") }
    val inf = rememberInfiniteTransition(label = "pip")
    val antenna by inf.animateFloat(-14f, 14f, infiniteRepeatable(tween(520, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "ant")
    val blink by inf.animateFloat(1f, 1f, infiniteRepeatable(keyframes {
        durationMillis = 2200; 1f at 0; 1f at 1700; 0.1f at 1800; 1f at 1900
    }), label = "blink")
    val cursor by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(450), RepeatMode.Reverse), label = "cur")

    LaunchedEffect(Unit) {
        launch { drop.animateTo(0f, spring(dampingRatio = 0.45f, stiffness = 260f)) }
        delay(140)
        squash.animateTo(0.86f, tween(60)); squash.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = 500f))
        launch { title.animateTo(1f, tween(220, easing = FastOutSlowInEasing)) }
        "Fluwy Bot".forEach { ch -> typed += ch; delay(32) }
        delay(80)
        alphaAll.animateTo(0f, tween(180))
        onDone()
    }

    Box(Modifier.fillMaxSize().background(c.bg).alpha(alphaAll.value), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(Modifier.size(150.dp).graphicsLayer { translationY = drop.value; scaleY = squash.value; scaleX = 2f - squash.value }) {
                val w = size.width; val h = size.height
                val ink = Color(0xFF14110F); val orange = Color(0xFFFF6B3D); val screen = Color(0xFF0F1115)
                val sw = w * 0.04f
                // --- antena curva com ponta brilhante (balança suave) ---
                val baseX = w * .5f; val baseY = h * .22f
                val rad = Math.toRadians(antenna.toDouble())
                val tipX = baseX + (h * .15f * Math.sin(rad)).toFloat(); val tipY = baseY - (h * .17f * Math.cos(rad)).toFloat()
                val antennaPath = Path().apply { moveTo(baseX, baseY); quadraticBezierTo((baseX + tipX) / 2 + w * .03f, (baseY + tipY) / 2, tipX, tipY) }
                drawPath(antennaPath, ink, style = Stroke(sw, cap = StrokeCap.Round))
                drawCircle(Color(0xFFFFC53D), w * .055f, Offset(tipX, tipY))
                drawCircle(Color.White.copy(alpha = .7f), w * .02f, Offset(tipX - w * .015f, tipY - w * .015f))
                // --- cabeça: retângulo bem arredondado (estilo capacete minimalista) ---
                val headL = w * .16f; val headT = h * .30f; val headW = w * .68f; val headH = h * .52f; val rr = CornerRadius(w * .26f, w * .26f)
                drawRoundRect(orange, Offset(headL, headT), Size(headW, headH), rr)
                drawRoundRect(ink, Offset(headL, headT), Size(headW, headH), rr, style = Stroke(sw))
                // "viseira" escura onde ficam os olhos
                val visL = w * .26f; val visT = h * .40f; val visW = w * .48f; val visH = h * .26f; val vr = CornerRadius(w * .16f, w * .16f)
                drawRoundRect(screen, Offset(visL, visT), Size(visW, visH), vr)
                // --- olhos: dois pontos que piscam, com brilho ---
                val eyeY = visT + visH * .5f; val er = w * .052f
                listOf(w * .40f, w * .60f).forEach { ex ->
                    drawRoundRect(Color(0xFF66E0FF), Offset(ex - er, eyeY - er * blink), Size(er * 2, (er * 2 * blink).coerceAtLeast(2f)), CornerRadius(er, er))
                }
                if (blink > .5f) listOf(w * .40f, w * .60f).forEach { ex ->
                    drawCircle(Color.White, w * .018f, Offset(ex + w * .012f, eyeY - w * .012f))
                }
                // --- bochechas (dão o toque fofo/elegante) ---
                drawCircle(Color(0xFFFF9A77), w * .03f, Offset(w * .33f, h * .60f))
                drawCircle(Color(0xFFFF9A77), w * .03f, Offset(w * .67f, h * .60f))
                // --- orelhinhas/sensores laterais ---
                drawRoundRect(ink, Offset(headL - w * .05f, h * .52f), Size(w * .05f, h * .10f), CornerRadius(w * .02f, w * .02f))
                drawRoundRect(ink, Offset(headL + headW, h * .52f), Size(w * .05f, h * .10f), CornerRadius(w * .02f, w * .02f))
            }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.alpha(title.value).graphicsLayer { translationY = (1 - title.value) * 24f }, verticalAlignment = Alignment.CenterVertically) {
                Text(">", color = c.accent, fontSize = 30.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.width(8.dp))
                Text(typed, color = c.ink, fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp)
                Text("_", color = c.ink.copy(alpha = cursor), fontSize = 30.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
            Spacer(Modifier.height(6.dp))
            Text("seu agente, com um computador de verdade", color = c.ink3, fontSize = 13.sp, modifier = Modifier.alpha(title.value).scale(0.98f + 0.02f * title.value))
        }
    }
}
