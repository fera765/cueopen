package dev.cueopen.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import dev.cueopen.app.notify.NotificationCenter
import dev.cueopen.app.ui.theme.Cue
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Arquitetura de notificação → ação: a notificação guarda um conteúdo ([NotificationCenter.Entry.detail]) e/ou uma rota.
 * Tocar abre um quadro com o conteúdo completo (ex.: o e-mail novo) com botão para a página relacionada (fluxo, chat).
 * Com o app aberto, aparece também um aviso no topo que abre o mesmo quadro.
 */
@Composable
fun NotificationOverlay(nav: NavController, pendingEntry: MutableState<Long?>) {
    val c = Cue.c
    var open by remember { mutableStateOf<NotificationCenter.Entry?>(null) }
    var banner by remember { mutableStateOf<NotificationCenter.Entry?>(null) }
    LaunchedEffect(pendingEntry.value) {
        val id = pendingEntry.value ?: return@LaunchedEffect
        pendingEntry.value = null
        val e = NotificationCenter.entry(id) ?: return@LaunchedEffect
        NotificationCenter.markEntryRead(id)
        openEntry(e, nav) { open = it }
    }
    LaunchedEffect(Unit) { NotificationCenter.live.collect { banner = it } }
    LaunchedEffect(banner) { if (banner != null) { delay(6_000); banner = null } }

    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(banner != null, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp),
            enter = slideInVertically { -it }, exit = slideOutVertically { -it }) {
            val b = banner
            if (b != null) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface).border(1.dp, c.accent.copy(alpha = .5f), RoundedCornerShape(18.dp))
                .clickable { banner = null; NotificationCenter.markEntryRead(b.id); openEntry(b, nav) { open = it } }
                .padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Notifications, null, tint = c.accent)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(b.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(b.text, fontSize = 12.sp, color = c.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                IconButton({ banner = null }) { Icon(Icons.Rounded.Close, "Fechar", tint = c.ink3) }
            }
        }
    }

    open?.let { e ->
        AlertDialog(onDismissRequest = { open = null }, containerColor = c.surface,
            title = {
                Column {
                    Text(e.title, fontWeight = FontWeight.SemiBold)
                    Text(SimpleDateFormat("dd/MM HH:mm", Locale("pt", "BR")).format(Date(e.at)) + (e.agent.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                        fontSize = 12.sp, color = c.ink3)
                }
            },
            text = {
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                    SelectionContainer { CueMarkdown(e.detail.ifBlank { e.text }) }
                }
            },
            confirmButton = {
                e.route?.let { r -> TextButton({ open = null; nav.navigate(r) { launchSingleTop = true } }) { Text(e.routeLabel.ifBlank { "Abrir" }) } }
                    ?: e.sessionId?.let { sid -> TextButton({ open = null; nav.navigate("chat/$sid") { launchSingleTop = true } }) { Text("Abrir chat") } }
                    ?: TextButton({ open = null }) { Text("Fechar") }
            },
            dismissButton = { if (e.route != null || e.sessionId != null) TextButton({ open = null }) { Text("Fechar") } })
    }
}

/** Toque numa notificação: página inteira, quadro, ou direto para a rota (sem conteúdo). */
private fun openEntry(e: NotificationCenter.Entry, nav: NavController, showModal: (NotificationCenter.Entry) -> Unit) {
    when {
        e.page -> nav.navigate("notif/${e.id}") { launchSingleTop = true }
        e.detail.isBlank() && e.route != null -> nav.navigate(e.route) { launchSingleTop = true }
        else -> showModal(e)
    }
}

/** Página de uma notificação: conteúdo completo formatado, com atalho para o fluxo/chat de origem. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationPageScreen(nav: NavController, id: Long) {
    val c = Cue.c
    val e = remember(id) { NotificationCenter.entry(id) }
    Scaffold(containerColor = c.bg, topBar = { CueTopBar(e?.title ?: "Notificação", { nav.popBackStack() }) }) { pad ->
        if (e == null) Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) { Text("Notificação não encontrada.", color = c.ink3) }
        else Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR")).format(Date(e.at)) + (e.agent.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                fontSize = 12.sp, color = c.ink3)
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(18.dp)).padding(16.dp)) {
                SelectionContainer { CueMarkdown(e.detail.ifBlank { e.text }) }
            }
            e.route?.let { r -> OutlinedButton({ nav.navigate(r) { launchSingleTop = true } }) { Text(e.routeLabel.ifBlank { "Abrir" }) } }
        }
    }
}
