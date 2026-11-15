package dev.cueopen.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.cueopen.app.CueApp
import dev.cueopen.app.data.ThemeMode
import dev.cueopen.app.sandbox.Sandbox
import dev.cueopen.app.ui.theme.Cue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Replay: reproduz a tarefa passo a passo, como o replay compartilhável do Cue. */
@Composable
fun ReplayScreen(nav: NavController, sessionId: String) {
    val app = CueApp.instance
    val session = app.store.session(sessionId) ?: return
    var shown by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(true) }
    var speed by remember { mutableFloatStateOf(1f) }
    val c = Cue.c
    val replayList = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(playing, speed) {
        while (playing && shown < session.items.size) { delay((900 / speed).toLong()); shown++ }
        if (shown >= session.items.size) playing = false
    }
    // Mantém o item recém-revelado visível durante a reprodução e volta ao início ao reiniciar.
    LaunchedEffect(shown, session.items.size) {
        if (shown > 0) replayList.animateScrollToItem((shown - 1).coerceAtMost(session.items.lastIndex))
        else if (session.items.isNotEmpty()) replayList.scrollToItem(0)
    }
    Scaffold(containerColor = c.bg, topBar = { CueTopBar("Replay · ${session.title}", { nav.popBackStack() }) }, bottomBar = {
        Column(Modifier.navigationBarsPadding().padding(16.dp)) {
            val p by animateFloatAsState(if (session.items.isEmpty()) 1f else shown / session.items.size.toFloat(), label = "p")
            LinearProgressIndicator(progress = { p }, Modifier.fillMaxWidth().height(4.dp).clip(CircleShape), color = c.accent, trackColor = c.surface2)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RoundIcon(Icons.Rounded.Replay, "Reiniciar") { shown = 0; playing = true }
                RoundIcon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play") { if (shown >= session.items.size) shown = 0; playing = !playing }
                RoundIcon(Icons.Rounded.SkipNext, "Fim") { shown = session.items.size; playing = false }
                Spacer(Modifier.weight(1f))
                listOf(1f, 2f, 4f).forEach { sp -> Chip("${sp.toInt()}x", speed == sp) { speed = sp } }
            }
        }
    }) { pad ->
        LazyColumn(state = replayList, modifier = Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val persona = ChatPersona.of(app.store.state.value.agents.firstOrNull { it.id == session.agentId })
            itemsIndexed(session.items.take(shown), key = { _, it -> it.id }) { _, item ->
                androidx.compose.runtime.CompositionLocalProvider(LocalChatPersona provides persona) { TimelineItem(item, onOpenComputer = {}) }
            }
        }
    }
}
