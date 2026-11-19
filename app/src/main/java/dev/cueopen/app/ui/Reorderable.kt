package dev.cueopen.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.cueopen.app.ui.theme.Cue
import kotlin.math.roundToInt

/** Regras puras da ordem da barra lateral (testáveis). */
object SidebarOrder {
    /** Ordem salva primeiro; agentes novos (fora da ordem salva) vão no fim; removidos somem. */
    fun apply(ids: List<String>, saved: List<String>): List<String> = saved.filter { it in ids } + ids.filter { it !in saved }

    /** Move o item de [from] para [to] empurrando os intermediários. */
    fun move(ids: List<String>, from: Int, to: Int): List<String> {
        if (from !in ids.indices) return ids
        val list = ids.toMutableList()
        val item = list.removeAt(from)
        list.add(to.coerceIn(0, list.size), item)
        return list
    }
}

/**
 * Coluna com arrastar e soltar: segure um item e arraste. Mostra a posição de destino (os vizinhos abrem espaço
 * com animação) e chama [onMove] ao soltar.
 */
@Composable
fun ReorderableColumn(keys: List<String>, onMove: (from: Int, to: Int) -> Unit, row: @Composable (String) -> Unit) {
    val haptic = LocalHapticFeedback.current
    var dragging by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableStateOf(0f) }
    var rowPx by remember { mutableStateOf(1f) }
    val from = keys.indexOf(dragging)
    val target = if (from < 0) -1 else (from + (offset / rowPx).roundToInt()).coerceIn(0, keys.lastIndex)
    Column {
        keys.forEachIndexed { i, k ->
            val isDragged = k == dragging
            val shift = when {
                from < 0 || isDragged -> 0f
                i in (from + 1)..target -> -rowPx
                i in target until from -> rowPx
                else -> 0f
            }
            val animShift by animateFloatAsState(shift, label = "reorder")
            key(k) {
                Box(Modifier.fillMaxWidth()
                    .onSizeChanged { if (it.height > 0) rowPx = it.height.toFloat() }
                    .zIndex(if (isDragged) 1f else 0f)
                    .graphicsLayer { translationY = if (isDragged) offset else animShift; if (isDragged) { scaleX = 1.02f; scaleY = 1.02f } }
                    .then(if (isDragged) Modifier.shadow(8.dp, RoundedCornerShape(10.dp)).clip(RoundedCornerShape(10.dp)).background(Cue.c.surface2) else Modifier)
                    .pointerInput(keys) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { dragging = k; offset = 0f; haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                            onDragEnd = {
                                val f = keys.indexOf(dragging); val t = if (f < 0) -1 else (f + (offset / rowPx).roundToInt()).coerceIn(0, keys.lastIndex)
                                if (f >= 0 && t != f) onMove(f, t)
                                dragging = null; offset = 0f
                            },
                            onDragCancel = { dragging = null; offset = 0f },
                            onDrag = { change, amount -> change.consume(); offset += amount.y },
                        )
                    }) { row(k) }
            }
        }
    }
}
