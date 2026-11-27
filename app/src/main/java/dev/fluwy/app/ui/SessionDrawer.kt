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

@Composable
internal fun SessionDrawer(nav: NavController, sessions: List<Session>, agents: List<AgentProfile>, connectedConnectorCount: Int, drawerOpen: Boolean, onClose: () -> Unit, onNew: () -> Unit) {
    val c = Fluwy.c
    var query by remember { mutableStateOf("") }
    // Sessões de subagentes são detalhes internos da tarefa pai, nunca chats independentes na navegação.
    val filtered = remember(sessions, query) {
        sessions.filterNot { it.title.startsWith("[Worker ", ignoreCase = true) || it.title.startsWith("[Workflow]") || it.sectionId != null || it.squadParentId != null }
            .filter { query.isBlank() || it.title.contains(query, true) }
            .sortedByDescending { it.updatedAt }
    }
    val groups = remember(filtered) { filtered.groupBy { dayGroup(it.updatedAt) } }
    fun go(r: String) { onClose(); nav.navigate(r) }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp)) {
        // Cabeçalho: identidade, saudação e números do workspace num cartão suave.
        val userName = remember { FluwyApp.instance.store.state.value.settings.userName }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(c.accentSoft.copy(alpha = if (c.dark) 0.22f else 0.85f), c.surface)))
            .border(1.dp, c.line, RoundedCornerShape(22.dp)).padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(46.dp).clip(CircleShape).background(c.bg.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
                    RotatingAgentFace(32.dp, intervalMillis = 5_200L, enabled = drawerOpen)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Fluwy Bot", style = MaterialTheme.typography.titleLarge, maxLines = 1)
                    Text(userName.ifBlank { null }?.let { "Olá, $it" } ?: "Seu agente pessoal", fontSize = 12.sp, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box(Modifier.size(36.dp).clip(CircleShape).background(c.bg.copy(alpha = 0.7f)).clickable { go("settings") }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Settings, "Configurações", Modifier.size(19.dp), tint = c.ink2)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.bg.copy(alpha = 0.6f)).padding(vertical = 8.dp)) {
                DrawerStat("${agents.size}", "Agents", Modifier.weight(1f)) { go("agents") }
                Box(Modifier.width(1.dp).height(28.dp).background(c.line).align(Alignment.CenterVertically))
                DrawerStat("$connectedConnectorCount", "Conectores", Modifier.weight(1f)) { go("connectors") }
                Box(Modifier.width(1.dp).height(28.dp).background(c.line).align(Alignment.CenterVertically))
                DrawerStat("${filtered.size}", "Tarefas", Modifier.weight(1f)) { }
            }
        }
        Spacer(Modifier.height(12.dp))
        // Ação principal em destaque.
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.accent).clickable { onNew() }.padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Edit, null, tint = Color.White, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text("Nova tarefa", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f))
            Icon(Icons.Rounded.ArrowForward, null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.height(10.dp))
        // Busca compacta em pílula.
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface2).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Search, null, Modifier.size(18.dp), tint = c.ink3)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text("Buscar tarefas", fontSize = 14.sp, color = c.ink3)
                androidx.compose.foundation.text.BasicTextField(query, { query = it }, singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = c.ink),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent), modifier = Modifier.fillMaxWidth())
            }
            if (query.isNotEmpty()) Icon(Icons.Rounded.Close, "Limpar", Modifier.size(18.dp).clickable { query = "" }, tint = c.ink3)
        }
        Spacer(Modifier.height(12.dp))
        // Atalhos sempre visíveis em grade (antes ficavam escondidos num menu recolhido).
        val shortcuts = listOf(
            DrawerShortcut(Icons.Rounded.Groups, "Agents", agents.size.takeIf { it > 0 }?.toString(), 0xFF7C5CFF) { go("agents") },
            DrawerShortcut(Icons.Rounded.Cable, "Conectores", connectedConnectorCount.takeIf { it > 0 }?.toString(), 0xFF2F9E6E) { go("connectors") },
            DrawerShortcut(Icons.Rounded.AutoAwesome, "Skills", tint = 0xFFE0A100) { go("skills") },
            DrawerShortcut(Icons.Rounded.Schedule, "Agenda", tint = 0xFF2D7FF9) { go("scheduled") },
            DrawerShortcut(Icons.Rounded.FolderOpen, "Arquivos", tint = 0xFFFF6B3D) { go("files") },
            DrawerShortcut(Icons.Rounded.Psychology, "Memória", tint = 0xFFD94E8F) { go("memory") },
            DrawerShortcut(Icons.Rounded.LibraryBooks, "Knowledge", tint = 0xFF0FA3B1) { go("knowledge") },
            DrawerShortcut(Icons.Rounded.Language, "Browser", tint = 0xFF3B82F6) { go("browserhub") },
            DrawerShortcut(Icons.Rounded.AccountTree, "Fluxos", tint = 0xFF2FBF71) { go("workflows") },
            DrawerShortcut(Icons.Rounded.RecordVoiceOver, "Live Audio", tint = 0xFF9B7BFF) { go("live") },
            DrawerShortcut(Icons.Rounded.Computer, "Linux", tint = 0xFF64748B) { go("sandbox") },
            DrawerShortcut(Icons.Rounded.AccountBalanceWallet, "Cofre", tint = 0xFF9945FF) { go("vault") },
        )
        shortcuts.chunked(5).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { sc -> ShortcutTile(sc, Modifier.weight(1f)) }
                repeat(5 - row.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(6.dp))
        }
        Divider()
        val live by FluwyApp.instance.store.state.collectAsStateWithLifecycle()
        val sections = live.sections.filterNot { it.archived }
        val archivedSections = live.sections.filter { it.archived }
        var showArchived by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
        LazyColumn(Modifier.weight(1f)) {
            // Seções só aparecem quando existem (criadas pelo chat).
            if (sections.isNotEmpty()) {
                item { SectionLabel("Seções") }
                items(sections, key = { "sec-" + it.id }) { sec ->
                    SectionRow(sec, live.sessions, live.agents, onOpen = { go("chat/$it") }, onOpenAgent = { go("agent/$it") }, onCreateAgent = { go("agent/new?section=$it") })
                }
            }
            if (archivedSections.isNotEmpty()) {
                item {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { showArchived = !showArchived }.padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Inventory2, null, Modifier.size(16.dp), tint = Fluwy.c.ink3)
                        Spacer(Modifier.width(8.dp))
                        Text("Seções arquivadas (${archivedSections.size})", fontSize = 13.sp, color = Fluwy.c.ink3, modifier = Modifier.weight(1f))
                        Icon(if (showArchived) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = Fluwy.c.ink3)
                    }
                }
                if (showArchived) items(archivedSections, key = { "arch-" + it.id }) { sec ->
                    SectionRow(sec, live.sessions, live.agents, onOpen = { go("chat/$it") }, onOpenAgent = { go("agent/$it") }, onCreateAgent = { go("agent/new?section=$it") })
                }
            }
            groups.forEach { (g, list) ->
                item { SectionLabel(g) }
                items(list, key = { it.id }) { s -> SessionRow(s, agents.firstOrNull { it.id == s.agentId }) { go("chat/${s.id}") } }
            }
            if (filtered.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.ChatBubbleOutline, null, tint = c.ink3, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(if (query.isBlank()) "Nenhuma tarefa ainda" else "Nada encontrado para \"$query\"", color = c.ink3, fontSize = 13.sp)
                }
            }
        }
    }
}

internal class DrawerShortcut(val icon: ImageVector, val label: String, val badge: String? = null, val tint: Long = 0xFFFF6B3D, val onClick: () -> Unit)

@Composable
internal fun DrawerStat(value: String, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(vertical = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(label, fontSize = 11.sp, color = Fluwy.c.ink3)
    }
}

@Composable
internal fun ShortcutTile(sc: DrawerShortcut, modifier: Modifier) {
    val c = Fluwy.c
    Column(modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = sc.onClick).padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            val tint = Color(sc.tint)
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(tint.copy(alpha = if (c.dark) 0.18f else 0.14f)), contentAlignment = Alignment.Center) {
                Icon(sc.icon, null, Modifier.size(20.dp), tint = tint)
            }
            // Selo redondo de tamanho fixo: o número se ajusta ao círculo (até "99+"), o círculo nunca cresce.
            if (sc.badge != null) {
                val n = sc.badge.toIntOrNull()
                val label = if (n != null && n > 99) "99+" else sc.badge
                Box(Modifier.align(Alignment.TopEnd).offset(x = 5.dp, y = (-5).dp).size(20.dp).clip(CircleShape).background(c.accent)
                    .border(1.5.dp, c.bg, CircleShape), contentAlignment = Alignment.Center) {
                    Text(label, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false,
                        fontSize = when (label.length) { 1 -> 10.sp; 2 -> 9.sp; else -> 7.sp },
                        style = androidx.compose.ui.text.TextStyle(platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false), lineHeight = 10.sp))
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(sc.label, fontSize = 10.sp, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun DrawerItem(icon: ImageVector, text: String, badge: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(20.dp), tint = Fluwy.c.ink2); Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (badge != null) Text(badge, color = Fluwy.c.ink3, fontSize = 13.sp)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SessionRow(s: Session, agent: AgentProfile?, onClick: () -> Unit) {
    val c = Fluwy.c
    val app = FluwyApp.instance
    val store = app.store
    var menu by remember { mutableStateOf(false) }
    var renameDialog by remember { mutableStateOf(false) }
    var deleteDialog by remember { mutableStateOf(false) }
    var title by remember(s.id, s.title) { mutableStateOf(s.title) }
    Box {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).combinedClickable(
            onClick = onClick, onLongClick = { menu = true }
        ).padding(horizontal = 8.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            if (agent != null) AgentFace(Color(agent.color), agent.face, 26.dp)
            else Box(Modifier.size(26.dp).clip(RoundedCornerShape(8.dp)).background(c.surface2), contentAlignment = Alignment.Center) {
                Icon(if (s.scheduledTaskId != null) Icons.Rounded.Schedule else Icons.Rounded.ChatBubbleOutline, null, Modifier.size(14.dp), tint = c.ink2)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp)
                val last = s.items.lastOrNull()?.let { previewOf(it) }.orEmpty()
                if (last.isNotBlank()) Text(last, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = c.ink3)
            }
            // Agente agendado/autônomo = "online": mostra dot verde mesmo parado entre ciclos.
            StatusDot(s.status, online = agent?.autonomous == true && agent.homeSessionId == s.id)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Mudar título") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = {
                title = s.title; menu = false; renameDialog = true
            })
            DropdownMenuItem(text = { Text("Excluir chat", color = c.err) }, leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = c.err) }, onClick = {
                menu = false; deleteDialog = true
            })
        }
    }
    if (renameDialog) AlertDialog(
        onDismissRequest = { renameDialog = false },
        title = { Text("Mudar título do chat") },
        text = { OutlinedTextField(value = title, onValueChange = { title = it }, singleLine = true, label = { Text("Título") }) },
        confirmButton = { TextButton(onClick = { val t = title.trim(); if (t.isNotEmpty()) store.editSession(s.id) { it.copy(title = t) }; renameDialog = false }) { Text("Salvar") } },
        dismissButton = { TextButton(onClick = { renameDialog = false }) { Text("Cancelar") } }
    )
    if (deleteDialog) AlertDialog(
        onDismissRequest = { deleteDialog = false },
        title = { Text("Excluir conversa?") },
        text = { Text("O chat “${s.title}” e seu histórico serão removidos deste aparelho.") },
        confirmButton = { TextButton(onClick = { app.deleteChat(s.id); deleteDialog = false }) { Text("Excluir", color = c.err) } },
        dismissButton = { TextButton(onClick = { deleteDialog = false }) { Text("Cancelar") } }
    )
}

@Composable
fun StatusDot(status: SessionStatus, online: Boolean = false) {
    val c = Fluwy.c
    val col = when (status) {
        SessionStatus.RUNNING -> c.accent; SessionStatus.WAITING_USER -> c.warn; SessionStatus.ERROR -> c.err
        SessionStatus.DONE -> c.ok
        // Autônomo parado entre ciclos continua "online" (verde), em vez de sumir (antes: Transparent).
        else -> if (online) c.ok else Color.Transparent
    }
    Box(Modifier.size(8.dp).clip(CircleShape).background(col))
}

fun previewOf(i: dev.fluwy.app.data.ChatItem): String = when (i) {
    is dev.fluwy.app.data.ChatItem.User -> i.text
    is dev.fluwy.app.data.ChatItem.Assistant -> i.text
    is dev.fluwy.app.data.ChatItem.Step -> i.title + " · " + i.args
    is dev.fluwy.app.data.ChatItem.Plan -> i.goal
    is dev.fluwy.app.data.ChatItem.Error -> i.text
    is dev.fluwy.app.data.ChatItem.ConnectorProposal -> "🧩 Conector " + i.name
    is dev.fluwy.app.data.ChatItem.UserScriptProposal -> "📜 User Script " + i.name
    is dev.fluwy.app.data.ChatItem.LlmRecovery -> "⚠ Falha do modelo: " + i.category
    is dev.fluwy.app.data.ChatItem.ConnectorEditProposal -> "Atualização de " + i.connectorName
    is dev.fluwy.app.data.ChatItem.Question -> "❓ " + (i.questions.firstOrNull()?.question ?: "Pergunta")
    is dev.fluwy.app.data.ChatItem.SecureForm -> "🔒 " + i.title
    is dev.fluwy.app.data.ChatItem.Approval -> "🛡 " + i.summary
    is dev.fluwy.app.data.ChatItem.SectionSetup -> "📁 Seção " + i.sectionName
    is dev.fluwy.app.data.ChatItem.Media -> "🖼 " + (i.caption.ifBlank { java.io.File(i.path).name })
    is dev.fluwy.app.data.ChatItem.Cards -> "🗂 " + (i.title.ifBlank { "${i.items.size} resultado(s)" })
    is dev.fluwy.app.data.ChatItem.Audio -> "🔊 " + i.text
    is dev.fluwy.app.data.ChatItem.PixQr -> "💠 Pix " + (if (i.cents > 0) dev.fluwy.app.vault.PixRules.money(i.cents) else "valor livre")
}.lineSequence().firstOrNull().orEmpty()

fun dayGroup(t: Long): String {
    val cal = Calendar.getInstance(); val today = cal.get(Calendar.DAY_OF_YEAR) to cal.get(Calendar.YEAR)
    cal.timeInMillis = t; val d = cal.get(Calendar.DAY_OF_YEAR) to cal.get(Calendar.YEAR)
    return when {
        d == today -> "Hoje"
        d.second == today.second && today.first - d.first == 1 -> "Ontem"
        d.second == today.second && today.first - d.first < 7 -> "Últimos 7 dias"
        else -> SimpleDateFormat("MMMM yyyy", Locale("pt", "BR")).format(Date(t))
    }
}


/** Comandos da caixa de mensagem. */
val slashCommands = listOf(
    "/compact" to "Compacta o contexto da conversa agora",
    "/autocompact" to "Liga/desliga a compactação automática ao atingir o limite",
    "/clear" to "Limpa o contexto do modelo (mantém o histórico na tela)",
    "/stop" to "Para o agente",
    "/undo" to "Desfaz a última alteração de arquivo feita pelo agente",
    "/usage" to "Mostra uso de tokens e contexto",
    "/memory" to "Abre a memória de longo prazo",
    "/skills" to "Abre as skills",
    "/connectors" to "Abre os conectores",
    "/help" to "Lista os comandos",
)
