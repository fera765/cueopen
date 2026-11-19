package dev.cueopen.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.cueopen.app.notify.NotificationCenter
import dev.cueopen.app.ui.theme.Cue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Histórico de avisos: o que os agentes notificaram (inclusive os silenciosos), com acesso direto à sessão. */
@Composable
fun NotificationsScreen(nav: NavController) {
    val c = Cue.c
    val list by NotificationCenter.history.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf("pendentes") }
    val fmt = remember { SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()) }
    val shown = when (filter) {
        "pendentes" -> list.filter { it.needsAction && !it.read }
        "importantes" -> list.filter { it.priority != "SILENT" && it.priority != "LOW" }
        else -> list
    }
    Scaffold(containerColor = c.bg, topBar = { CueTopBar("Avisos", { nav.popBackStack() }) {
        TextButton({ NotificationCenter.markRead() }) { Text("Marcar vistos") }
    } }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("pendentes" to "Aguardando você (${NotificationCenter.pendingCount()})", "importantes" to "Importantes", "todos" to "Todos").forEach { (k, label) ->
                        FilterChip(filter == k, { filter = k }, { Text(label, fontSize = 12.sp) })
                    }
                }
            }
            if (shown.isEmpty()) item { Text(if (filter == "pendentes") "Nada aguardando você." else "Sem avisos.", color = c.ink3, fontSize = 13.sp) }
            items(shown, key = { it.id }) { e ->
                val color = when (e.priority) { "CRITICAL" -> c.err; "HIGH" -> c.accent; "NORMAL" -> c.ok; else -> c.ink3 }
                CueCard(onClick = e.sessionId?.let { sid -> { NotificationCenter.markRead(sid); nav.navigate("chat/$sid") } }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(if (e.read) c.surface2 else color))
                        Spacer(Modifier.width(8.dp))
                        Text(e.title, fontWeight = if (e.read) FontWeight.Normal else FontWeight.SemiBold, modifier = Modifier.weight(1f), fontSize = 14.sp)
                        Text(fmt.format(Date(e.at)), fontSize = 11.sp, color = c.ink3)
                    }
                    Text(e.text, fontSize = 12.sp, color = c.ink2, maxLines = 4)
                    Text(runCatching { NotificationCenter.Category.valueOf(e.category).label }.getOrDefault(e.category) +
                        (if (e.agent.isNotBlank()) " · ${e.agent}" else "") + (if (e.priority == "SILENT") " · silencioso" else ""), fontSize = 11.sp, color = c.ink3)
                }
            }
        }
    }
}
