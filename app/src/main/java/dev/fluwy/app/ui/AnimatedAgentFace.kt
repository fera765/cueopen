package dev.fluwy.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.fluwy.app.ui.mascot.Mascot
import dev.fluwy.app.ui.mascot.MascotState
import kotlinx.coroutines.delay

/** Mascote animado: cada mascote tem a própria animação e reage ao [state] do agente. */
@Composable
fun AnimatedAgentFace(
    color: Color,
    face: String,
    size: Dp = 40.dp,
    modifier: Modifier = Modifier,
    state: MascotState = MascotState.IDLE,
) = Mascot(face, color, size, modifier, state = state)

/** Mostra mascotes de exemplo alternando, com o timer restrito a este pequeno trecho da árvore. */
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
