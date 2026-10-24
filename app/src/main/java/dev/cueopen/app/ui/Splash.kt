package dev.cueopen.app.ui

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
import dev.cueopen.app.ui.theme.Cue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Splash animada: o robozinho "Pip" (corpo em gota, estilo do ícone) cai com mola, a antena balança,
 * os olhos piscam, a boca vira um prompt ">_" que digita "CueOpen" — e tudo some em fade.
 */
@Composable
fun SplashScreen(onDone: () -> Unit) {
    val c = Cue.c
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
        delay(260)
        squash.animateTo(0.86f, tween(90)); squash.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = 500f))
        launch { title.animateTo(1f, tween(380, easing = FastOutSlowInEasing)) }
        "CueOpen".forEach { ch -> typed += ch; delay(55) }
        delay(420)
        alphaAll.animateTo(0f, tween(260))
        onDone()
    }

    Box(Modifier.fillMaxSize().background(c.bg).alpha(alphaAll.value), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(Modifier.size(150.dp).graphicsLayer { translationY = drop.value; scaleY = squash.value; scaleX = 2f - squash.value }) {
                val w = size.width; val h = size.height; val ink = Color(0xFF111111); val sw = w * 0.045f
                // antena (balança)
                val baseX = w * .5f; val baseY = h * .2f
                val rad = Math.toRadians(antenna.toDouble())
                val tipX = baseX + (h * .14f * Math.sin(rad)).toFloat(); val tipY = baseY - (h * .14f * Math.cos(rad)).toFloat()
                drawLine(ink, Offset(baseX, baseY), Offset(tipX, tipY), sw, StrokeCap.Round)
                drawCircle(Color(0xFFFFC53D), w * .05f, Offset(tipX, tipY)); drawCircle(ink, w * .05f, Offset(tipX, tipY), style = Stroke(sw * .7f))
                // corpo em gota
                val body = Path().apply {
                    moveTo(w * .5f, h * .2f)
                    cubicTo(w * .8f, h * .2f, w * .9f, h * .38f, w * .9f, h * .58f)
                    cubicTo(w * .9f, h * .82f, w * .72f, h * .94f, w * .5f, h * .94f)
                    cubicTo(w * .28f, h * .94f, w * .1f, h * .82f, w * .1f, h * .58f)
                    cubicTo(w * .1f, h * .38f, w * .2f, h * .2f, w * .5f, h * .2f); close()
                }
                drawPath(body, Color(0xFFFF6B3D)); drawPath(body, ink, style = Stroke(sw))
                // bracinhos
                drawLine(ink, Offset(w * .1f, h * .62f), Offset(w * .02f, h * .7f), sw, StrokeCap.Round)
                drawLine(ink, Offset(w * .9f, h * .62f), Offset(w * .98f, h * .54f), sw, StrokeCap.Round)
                // olhos (piscam)
                val eh = h * .1f * blink
                drawRoundRect(Color.White, Offset(w * .3f, h * .45f - eh / 2), Size(w * .14f, eh.coerceAtLeast(2f)), CornerRadius(w * .07f))
                drawRoundRect(Color.White, Offset(w * .56f, h * .45f - eh / 2), Size(w * .14f, eh.coerceAtLeast(2f)), CornerRadius(w * .07f))
                drawCircle(ink, w * .035f * blink.coerceAtLeast(.2f), Offset(w * .38f, h * .46f))
                drawCircle(ink, w * .035f * blink.coerceAtLeast(.2f), Offset(w * .64f, h * .46f))
                // boca = prompt ">_"
                drawLine(ink, Offset(w * .38f, h * .64f), Offset(w * .45f, h * .69f), sw, StrokeCap.Round)
                drawLine(ink, Offset(w * .45f, h * .69f), Offset(w * .38f, h * .74f), sw, StrokeCap.Round)
                drawLine(ink.copy(alpha = cursor), Offset(w * .5f, h * .75f), Offset(w * .62f, h * .75f), sw, StrokeCap.Round)
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
