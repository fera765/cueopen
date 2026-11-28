package dev.fluwy.app.ui
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.fluwy.app.FluwyApp
import dev.fluwy.app.data.AgentProfile
import dev.fluwy.app.data.Session
import dev.fluwy.app.data.SessionStatus
import dev.fluwy.app.ui.theme.Fluwy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Seção na sidebar: tocar abre o chat da seção e expande o time; segurar mostra limpar/reiniciar/excluir. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SectionRow(
    sec: dev.fluwy.app.data.Section, sessions: List<Session>, agents: List<AgentProfile>,
    onOpen: (String) -> Unit, onOpenAgent: (String) -> Unit, onCreateAgent: (String) -> Unit = {},
) {
    val c = Fluwy.c
    val app = FluwyApp.instance
    var expanded by androidx.compose.runtime.saveable.rememberSaveable(sec.id) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) } // "delete" | "restart" | "clear"
    var addAgent by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<AgentProfile?>(null) }
    if (addAgent) AddAgentToSectionDialog(sec, agents, onCreate = { addAgent = false; onCreateAgent(sec.id) }) { addAgent = false }
    val chats = sessions.filter { it.sectionId == sec.id }
    val busy = chats.any { it.status == SessionStatus.RUNNING }
    val waiting = chats.any { it.status == SessionStatus.WAITING_USER }
    // Itens de LazyColumn empilham filhos como um Box: a linha e a lista do time precisam de uma Column.
    Column {
    Box {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).combinedClickable(
            onClick = { expanded = true; onOpen(sec.sessionId) }, onLongClick = { menu = true },
        ).padding(horizontal = 8.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(26.dp).clip(RoundedCornerShape(8.dp)).background(c.accentSoft), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Folder, null, Modifier.size(15.dp), tint = c.accent)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(sec.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(if (sec.archived) "Arquivada · ${sec.agentIds.size} agentes" else if (!sec.configured) "Aguardando ativação" else "${sec.agentIds.size} agentes · coordenador",
                    fontSize = 12.sp, color = c.ink3, maxLines = 1)
            }
            if (busy) PulsingDot(c.ok) else StatusDot(if (waiting) SessionStatus.WAITING_USER else SessionStatus.IDLE)
            IconButton({ expanded = !expanded }, Modifier.size(32.dp)) {
                Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (expanded) "Recolher" else "Expandir", tint = c.ink3)
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Abrir") }, leadingIcon = { Icon(Icons.Rounded.ChatBubbleOutline, null) }, onClick = { menu = false; onOpen(sec.sessionId) })
            DropdownMenuItem(text = { Text("Limpar contexto") }, leadingIcon = { Icon(Icons.Rounded.CleaningServices, null) }, onClick = { menu = false; confirm = "clear" })
            DropdownMenuItem(text = { Text("Reiniciar") }, leadingIcon = { Icon(Icons.Rounded.RestartAlt, null) }, onClick = { menu = false; confirm = "restart" })
            DropdownMenuItem(text = { Text("Adicionar agente") }, leadingIcon = { Icon(Icons.Rounded.PersonAdd, null) }, onClick = { menu = false; addAgent = true })
            if (sec.archived) DropdownMenuItem(text = { Text("Restaurar") }, leadingIcon = { Icon(Icons.Rounded.Unarchive, null) }, onClick = { menu = false; app.agent.restoreSection(sec.id) })
            else DropdownMenuItem(text = { Text("Arquivar") }, leadingIcon = { Icon(Icons.Rounded.Archive, null) }, onClick = { menu = false; app.agent.archiveSection(sec.id) })
            DropdownMenuItem(text = { Text("Excluir seção", color = c.err) }, leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = c.err) }, onClick = { menu = false; confirm = "delete" })
        }
    }
    androidx.compose.animation.AnimatedVisibility(expanded) {
        Column(Modifier.padding(start = 22.dp)) {
            agents.firstOrNull { it.id == sec.coordinatorAgentId }?.let { coord ->
                val main = chats.firstOrNull { it.id == sec.sessionId }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onOpen(sec.sessionId) }
                    .padding(horizontal = 8.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    AgentFace(Color(coord.color), coord.face, 20.dp); Spacer(Modifier.width(8.dp))
                    Text("Coordenador", fontSize = 14.sp, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
                    if (main?.status == SessionStatus.RUNNING) PulsingDot(c.ok) else StatusDot(main?.status ?: SessionStatus.IDLE)
                }
            }
            val ordered = SidebarOrder.apply(sec.agentIds, sec.memberOrder).mapNotNull { id -> agents.firstOrNull { it.id == id } }
            ReorderableColumn(ordered.map { it.id }, onMove = { from, to ->
                // Só a ordem visual muda (memberOrder); a identidade e o papel do agente ficam iguais.
                app.store.upsertSection(sec.copy(memberOrder = SidebarOrder.move(ordered.map { it.id }, from, to)))
            }) { id ->
                val a = ordered.first { it.id == id }
                val chat = chats.firstOrNull { it.agentId == a.id && it.id != sec.sessionId }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { if (chat != null) onOpen(chat.id) else onOpenAgent(a.id) }
                    .padding(horizontal = 8.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    AgentFace(Color(a.color), a.face, 20.dp); Spacer(Modifier.width(8.dp))
                    Text(a.name, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    // agente trabalhando agora: ponto verde pulsando (um por agente ativo)
                    if (chat?.status == SessionStatus.RUNNING) PulsingDot(c.ok) else StatusDot(chat?.status ?: SessionStatus.IDLE)
                    IconButton({ removing = a }, Modifier.size(28.dp)) { Icon(Icons.Rounded.Close, "Remover ${a.name}", Modifier.size(16.dp), tint = c.ink3) }
                }
            }
        }
    }
    }
    removing?.let { ag ->
        AlertDialog(onDismissRequest = { removing = null }, title = { Text("Remover ${ag.name}?") },
            text = { Text("Tirar da seção mantém o agente na sua biblioteca. Excluir apaga o agente de vez (de todas as seções).") },
            confirmButton = { Row {
                TextButton({ app.agent.removeAgentFromSection(sec.id, ag.id, delete = false); removing = null }) { Text("Tirar da seção") }
                TextButton({ app.agent.removeAgentFromSection(sec.id, ag.id, delete = true); removing = null }) { Text("Excluir", color = c.err) }
            } },
            dismissButton = { TextButton({ removing = null }) { Text("Cancelar") } })
    }
    confirm?.let { kind ->
        val (title, body, action) = when (kind) {
            "clear" -> Triple("Limpar contexto?", "Apaga TUDO da seção: conversa de todos os agentes, histórico, arquivos do workspace, terminais e dados seguros. O time fica parado esperando um novo pedido.", "Limpar")
            "restart" -> Triple("Reiniciar a seção?", "Apaga TUDO (conversa, histórico, workspace, terminais, dados seguros) e o coordenador recomeça do zero pelo pedido original. Agentes e permissões continuam.", "Reiniciar")
            else -> Triple("Excluir a seção “${sec.name}”?", "Remove a seção, os chats dela e os ${sec.agentIds.size + 1} agentes criados para ela.", "Excluir")
        }
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(title) }, text = { Text(body) },
            confirmButton = { TextButton({
                when (kind) {
                    "clear" -> app.agent.clearSectionContext(sec.id)
                    "restart" -> app.agent.restartSection(sec.id)
                    else -> {
                        (sec.agentIds + sec.coordinatorAgentId).forEach { dev.fluwy.app.scheduler.Heartbeat.cancel(app, it) }
                        app.store.deleteSection(sec.id).forEach { app.deleteChat(it) }
                        runCatching { app.sandbox.deleteEnvironment("section_${sec.id}") }  // workspace compartilhado do time
                    }
                }
                confirm = null
            }) { Text(action, color = if (kind == "delete") c.err else c.accent) } },
            dismissButton = { TextButton({ confirm = null }) { Text("Cancelar") } },
        )
    }
}

/**
 * Adicionar agente à seção: uma lista com rolagem — agentes da sua biblioteca e os agentes bots do catálogo.
 * Um toque adiciona (bot do catálogo é instalado na biblioteca e já entra no time). "Criar novo agente" abre a tela
 * completa de criação (boneco, estados, instruções, modelo, autonomia) e o agente salvo entra na seção.
 */
@Composable
internal fun AddAgentToSectionDialog(sec: dev.fluwy.app.data.Section, agents: List<AgentProfile>, onCreate: () -> Unit, onDone: () -> Unit) {
    val c = Fluwy.c
    val app = FluwyApp.instance
    var query by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf<String?>(null) }
    val library = remember(agents, sec, query) {
        agents.filter { it.id != sec.coordinatorAgentId && it.id !in sec.agentIds && !it.name.startsWith("Coordenador ·") }
            .filter { query.isBlank() || it.name.contains(query, true) || it.mission.contains(query, true) }
    }
    val allBots = remember { dev.fluwy.app.agent.BotCatalog.all() }
    val bots = remember(allBots, agents, query) {
        val have = agents.map { it.name.lowercase() }.toSet()
        dev.fluwy.app.agent.BotCatalog.search(allBots, query, null).filter { it.name.lowercase() !in have }
    }
    AlertDialog(onDismissRequest = onDone, title = { Text("Adicionar agente · ${sec.name}", maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                notice?.let { Text(it, fontSize = 12.sp, color = c.ok, modifier = Modifier.padding(bottom = 6.dp)) }
                OutlinedTextField(query, { query = it }, placeholder = { Text("Buscar agentes e bots") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                    if (library.isNotEmpty()) item { Text("SUA BIBLIOTECA", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = c.ink3, modifier = Modifier.padding(vertical = 6.dp)) }
                    items(library, key = { "a-" + it.id }) { a ->
                        AddAgentRow(a.name, a.mission, { AgentFace(Color(a.color), a.face, 24.dp) }) { notice = app.agent.addAgentToSection(sec.id, a.id) }
                    }
                    if (bots.isNotEmpty()) item { Text("AGENTES BOTS (${bots.size})", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = c.ink3, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp)) }
                    items(bots, key = { "b-" + it.id }) { b ->
                        AddAgentRow(b.name, b.summary, { AgentFace(Color(dev.fluwy.app.agent.BotCatalog.colorFor(b)), dev.fluwy.app.agent.BotCatalog.faceFor(b), 24.dp) }) {
                            val ag = dev.fluwy.app.agent.BotCatalog.draft(b, dev.fluwy.app.device.BrowserAutonomyPolicy.setEnabled(AgentProfile(name = ""), true))
                            app.store.upsertAgent(ag)
                            notice = app.agent.addAgentToSection(sec.id, ag.id)
                        }
                    }
                    if (library.isEmpty() && bots.isEmpty()) item { Text("Nada encontrado. Crie um agente novo.", fontSize = 13.sp, color = c.ink3) }
                }
            }
        },
        confirmButton = { TextButton(onCreate) { Text("Criar novo agente") } },
        dismissButton = { TextButton(onDone) { Text("Fechar") } })
}

@Composable
internal fun AddAgentRow(name: String, sub: String, face: @Composable () -> Unit, onAdd: () -> Unit) {
    val c = Fluwy.c
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onAdd).padding(vertical = 8.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        face(); Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub.isNotBlank()) Text(sub, fontSize = 12.sp, color = c.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Rounded.PersonAdd, "Adicionar", Modifier.size(18.dp), tint = c.accent)
    }
}
