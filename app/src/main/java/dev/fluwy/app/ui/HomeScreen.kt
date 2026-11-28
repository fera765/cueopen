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

/** Ponto de entrada de uma nova tarefa: cria a sessão, copia anexos e dispara o agente. */
suspend fun startTask(nav: NavController, text: String, agentId: String?, attachments: List<Uri> = emptyList(), ctx: android.content.Context) {
    val app = FluwyApp.instance
    val s = Session(agentId = agentId)
    app.store.upsertSession(s)
    val names = if (attachments.isEmpty()) emptyList() else withContext(Dispatchers.IO) {
        val ws = app.sandbox.workspaceFor(s.id)
        attachments.mapNotNull { u ->
            runCatching {
                val name = queryName(ctx, u)
                ctx.contentResolver.openInputStream(u)?.use { i -> File(ws, name).outputStream().use { i.copyTo(it) } }
                    ?: error("O anexo não pôde ser aberto.")
                name
            }.getOrNull()
        }
    }
    app.agent.send(s.id, text, names)
    nav.navigate("chat/${s.id}")
}

internal fun safeAttachmentName(name: String): String =
    name.substringAfterLast('/').substringAfterLast('\\').replace("..", "_").trim().ifBlank { "arquivo" }

fun queryName(ctx: android.content.Context, u: Uri): String {
    ctx.contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) return safeAttachmentName(c.getString(0).orEmpty())
    }
    return safeAttachmentName(u.lastPathSegment.orEmpty())
}

data class Starter(val title: String, val icon: ImageVector, val tint: Long, val prompt: String, val hint: String = "")

val starters = listOf(
    Starter("Slides", Icons.Rounded.Slideshow, 0xFFFFE6DC, "Crie uma apresentação de 8 slides sobre ", "Apresentação pronta"),
    Starter("Website", Icons.Rounded.Language, 0xFFDDE9FF, "Construa um site de uma página para ", "Página publicada"),
    Starter("Wide Research", Icons.Rounded.TravelExplore, 0xFFDDF5E7, "Pesquise e compare as principais opções de ", "Pesquisa ampla"),
    Starter("Criar imagem", Icons.Rounded.Image, 0xFFFFF3CF, "Crie uma ilustração de ", "Arte e ilustração"),
    Starter("Playbook", Icons.Rounded.MenuBook, 0xFFEDE3FF, "Monte um playbook passo a passo para ", "Guia passo a passo"),
    Starter("Mobile app", Icons.Rounded.PhoneAndroid, 0xFFFFE0EA, "Prototipe um app mobile (PWA) que ", "Protótipo PWA"),
    Starter("Analisar dados", Icons.Rounded.QueryStats, 0xFFDDF4F4, "Analise os dados do arquivo anexado e gere gráficos sobre ", "Gráficos e insights"),
)

internal data class HomeHeaderState(
    val agents: List<AgentProfile>,
    val userName: String,
    val apiKey: String,
    val connectedConnectors: Int = 0,
)

internal data class DrawerSnapshot(
    val sessions: List<Session>,
    val connectedConnectorCount: Int,
)

@Composable
fun HomeScreen(nav: NavController, initialDraft: String? = null, initialAgent: String? = null) {
    val app = FluwyApp.instance
    val headerFlow = remember(app) {
        app.store.state.map { state ->
            HomeHeaderState(state.agents, state.settings.userName, state.settings.apiKey, state.connectors.count { it.connectedAt != null && it.enabled })
        }.distinctUntilChanged()
    }
    val currentState = remember(app) {
        app.store.state.value.let { s -> HomeHeaderState(s.agents, s.settings.userName, s.settings.apiKey, s.connectors.count { it.connectedAt != null && it.enabled }) }
    }
    val st by headerFlow.collectAsStateWithLifecycle(currentState)
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var drawerSnapshot by remember(app) {
        val state = app.store.state.value
        mutableStateOf(DrawerSnapshot(state.sessions, state.connectors.count { it.connectedAt != null }))
    }
    LaunchedEffect(drawer.isOpen) {
        if (drawer.isOpen) {
            app.store.state.map { state ->
                DrawerSnapshot(state.sessions, state.connectors.count { it.connectedAt != null })
            }.distinctUntilChanged().collect { drawerSnapshot = it }
        }
    }
    var draft by remember { mutableStateOf(initialDraft ?: "") }
    var agentId by remember { mutableStateOf(initialAgent) }
    var sendingTask by remember { mutableStateOf(false) }
    val agent = st.agents.firstOrNull { it.id == agentId }
    val ctx = LocalContext.current
    val c = Fluwy.c

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = c.bg, modifier = Modifier.width(310.dp)) {
                SessionDrawer(nav, drawerSnapshot.sessions, st.agents, drawerSnapshot.connectedConnectorCount, drawer.isOpen, onClose = { scope.launch { drawer.close() } }, onNew = {
                    draft = ""; scope.launch { drawer.close() }
                })
            }
        },
    ) {
        Scaffold(containerColor = c.bg, topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({
                    val state = app.store.state.value
                    drawerSnapshot = DrawerSnapshot(state.sessions, state.connectors.count { it.connectedAt != null })
                    scope.launch { drawer.open() }
                }) { SidebarIcon() }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    AgentSelector(agent, st.agents, onSelect = { agentId = it }, onManage = { nav.navigate("agents") })
                }
                IconButton({ nav.navigate("terminal") }) { Icon(Icons.Rounded.Terminal, "Terminal", tint = c.ink2) }
                IconButton({ nav.navigate("settings") { launchSingleTop = true } }) { Icon(Icons.Rounded.Tune, "Configurações", tint = c.ink2) }
            }
        }) { pad ->
            Column(Modifier.fillMaxSize().padding(pad).imePadding()) {
                Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(88.dp).clip(CircleShape).background(c.accentSoft.copy(alpha = if (c.dark) 0.25f else 0.7f)), contentAlignment = Alignment.Center) {
                        if (agent == null) RotatingAgentFace(60.dp, intervalMillis = 4_800L)
                        else AnimatedAgentFace(Color(agent.color), agent.face, 60.dp)
                    }
                    Spacer(Modifier.height(18.dp))
                    val hello = st.userName.ifBlank { null }?.let { "Olá, $it" } ?: "Olá"
                    Text(hello, style = MaterialTheme.typography.titleMedium, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Text("O que posso fazer por você?", style = MaterialTheme.typography.headlineLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    if (agent != null) {
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.widthIn(max = 280.dp).clip(CircleShape).background(c.surface2).padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                            AgentFace(Color(agent.color), agent.face, 18.dp); Spacer(Modifier.width(6.dp))
                            Text("com ${agent.name}", fontSize = 12.sp, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (st.apiKey.isBlank()) {
                        Spacer(Modifier.height(14.dp))
                        HomeStatusChip(Icons.Rounded.Key, "Adicionar chave do modelo", warn = true) { nav.navigate("settings") { launchSingleTop = true } }
                    }
                    Spacer(Modifier.height(22.dp))
                    // Sugestões como pílulas centralizadas: um toque preenche o pedido.
                    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        starters.forEach { s -> SuggestionPill(s) { draft = s.prompt } }
                    }
                }
                Composer(
                    value = draft, onChange = { draft = it },
                    placeholder = "Atribua uma tarefa ou pergunte qualquer coisa",
                    onSend = { text, files ->
                        if (text.isNotBlank() && !sendingTask) {
                            val selectedAgent = agentId
                            sendingTask = true
                            draft = ""
                            app.applicationScope.launch {
                                try { startTask(nav, text, selectedAgent, files, ctx) }
                                finally { sendingTask = false }
                            }
                        }
                    },
                )
            }
        }
    }
}

@Composable
internal fun SuggestionPill(s: Starter, onClick: () -> Unit) {
    val c = Fluwy.c
    val tint = Color(s.tint)
    Row(Modifier.clip(CircleShape).background(c.surface).border(1.dp, c.line, CircleShape).clickable(onClick = onClick)
        .padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(if (c.dark) c.surface2 else tint), contentAlignment = Alignment.Center) {
            Icon(s.icon, null, tint = c.ink, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(s.title, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** Ícone da sidebar: painel lateral desenhado (mais leve que o "hambúrguer"). */
@Composable
internal fun SidebarIcon() {
    val color = Fluwy.c.ink
    androidx.compose.foundation.Canvas(Modifier.size(22.dp)) {
        val s = size.minDimension
        val stroke = s * 0.085f
        val r = androidx.compose.ui.geometry.CornerRadius(s * 0.22f)
        drawRoundRect(color, topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, s * 0.14f),
            size = androidx.compose.ui.geometry.Size(s - stroke, s * 0.72f), cornerRadius = r,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke))
        val x = s * 0.38f
        drawLine(color, androidx.compose.ui.geometry.Offset(x, s * 0.14f), androidx.compose.ui.geometry.Offset(x, s * 0.86f), strokeWidth = stroke)
        drawLine(color, androidx.compose.ui.geometry.Offset(s * 0.16f, s * 0.36f), androidx.compose.ui.geometry.Offset(s * 0.27f, s * 0.36f), strokeWidth = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(color, androidx.compose.ui.geometry.Offset(s * 0.16f, s * 0.5f), androidx.compose.ui.geometry.Offset(s * 0.27f, s * 0.5f), strokeWidth = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
    }
}

@Composable
internal fun HomeStatusChip(icon: ImageVector, text: String, warn: Boolean = false, onClick: () -> Unit) {
    val c = Fluwy.c
    Row(Modifier.clip(CircleShape).background(if (warn) c.accentSoft else c.surface2).clickable(onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(16.dp), tint = if (warn) c.accent else c.ink2)
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = if (warn) c.accent else c.ink)
    }
}

@Composable
internal fun AgentSelector(agent: AgentProfile?, agents: List<AgentProfile>, onSelect: (String?) -> Unit, onManage: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.widthIn(max = 230.dp).clip(CircleShape).background(Fluwy.c.surface2).clickable { open = true }.padding(start = 6.dp, end = 8.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (agent != null) AgentFace(Color(agent.color), agent.face, 24.dp) else AgentFace(Color(0xFFFF6B3D), "wink", 24.dp)
            Spacer(Modifier.width(6.dp))
            Text(agent?.name ?: "Fluwy Bot", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            Icon(Icons.Rounded.KeyboardArrowDown, null, Modifier.size(18.dp), tint = Fluwy.c.ink2)
        }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem(text = { Text("Fluwy Bot (padrão)") }, leadingIcon = { AgentFace(Color(0xFFFF6B3D), "wink", 24.dp) }, onClick = { onSelect(null); open = false })
            agents.forEach { a ->
                DropdownMenuItem(text = { Text(a.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }, leadingIcon = { AgentFace(Color(a.color), a.face, 24.dp) }, onClick = { onSelect(a.id); open = false })
            }
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Gerenciar Agents") }, leadingIcon = { Icon(Icons.Rounded.Groups, null) }, onClick = { open = false; onManage() })
        }
    }
}

/** Colagem a partir deste tamanho vira arquivo anexado. */
internal const val PASTE_TO_FILE_CHARS = 3_000
