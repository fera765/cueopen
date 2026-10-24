package dev.cueopen.app.ui

import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.cueopen.app.CueApp
import dev.cueopen.app.data.AgentProfile
import dev.cueopen.app.data.Session
import dev.cueopen.app.data.SessionStatus
import dev.cueopen.app.ui.theme.Cue
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Ponto de entrada de uma nova tarefa: cria a sessão, copia anexos e dispara o agente. */
fun startTask(nav: NavController, text: String, agentId: String?, attachments: List<Uri> = emptyList(), ctx: android.content.Context) {
    val app = CueApp.instance
    val s = Session(agentId = agentId)
    app.store.upsertSession(s)
    val ws = app.sandbox.workspaceFor(s.id)
    val names = attachments.mapNotNull { u ->
        runCatching {
            val name = queryName(ctx, u)
            ctx.contentResolver.openInputStream(u)!!.use { i -> File(ws, name).outputStream().use { i.copyTo(it) } }
            name
        }.getOrNull()
    }
    app.agent.send(s.id, text, names)
    nav.navigate("chat/${s.id}")
}

fun queryName(ctx: android.content.Context, u: Uri): String {
    ctx.contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) return c.getString(0)
    }
    return u.lastPathSegment?.substringAfterLast('/') ?: "arquivo"
}

data class Starter(val title: String, val icon: ImageVector, val tint: Long, val prompt: String)

val starters = listOf(
    Starter("Slides", Icons.Rounded.Slideshow, 0xFFFFE6DC, "Crie uma apresentação de 8 slides sobre "),
    Starter("Website", Icons.Rounded.Language, 0xFFDDE9FF, "Construa um site de uma página para "),
    Starter("Wide Research", Icons.Rounded.TravelExplore, 0xFFDDF5E7, "Pesquise e compare as principais opções de "),
    Starter("Criar imagem", Icons.Rounded.Image, 0xFFFFF3CF, "Crie uma ilustração de "),
    Starter("Playbook", Icons.Rounded.MenuBook, 0xFFEDE3FF, "Monte um playbook passo a passo para "),
    Starter("Mobile app", Icons.Rounded.PhoneAndroid, 0xFFFFE0EA, "Prototipe um app mobile (PWA) que "),
    Starter("Analisar dados", Icons.Rounded.QueryStats, 0xFFDDF4F4, "Analise os dados do arquivo anexado e gere gráficos sobre "),
)

@Composable
fun HomeScreen(nav: NavController, initialDraft: String? = null, initialAgent: String? = null) {
    val app = CueApp.instance
    val st by app.store.state.collectAsStateWithLifecycle()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf(initialDraft ?: "") }
    var agentId by remember { mutableStateOf(initialAgent) }
    val agent = st.agents.firstOrNull { it.id == agentId }
    val ctx = LocalContext.current
    val c = Cue.c

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = c.bg, modifier = Modifier.width(310.dp)) {
                SessionDrawer(nav, st.sessions, st.agents, onClose = { scope.launch { drawer.close() } }, onNew = {
                    draft = ""; scope.launch { drawer.close() }
                })
            }
        },
    ) {
        Scaffold(containerColor = c.bg, topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ scope.launch { drawer.open() } }) { Icon(Icons.Rounded.Menu, "Menu") }
                AgentSelector(agent, st.agents, onSelect = { agentId = it }, onManage = { nav.navigate("agents") })
                Spacer(Modifier.weight(1f))
                IconButton({ nav.navigate("terminal") }) { Icon(Icons.Rounded.Terminal, "Terminal") }
                IconButton({ nav.navigate("settings") }) { Icon(Icons.Rounded.Tune, "Configurações") }
            }
        }) { pad ->
            Column(Modifier.fillMaxSize().padding(pad).imePadding()) {
                Column(Modifier.weight(1f).padding(horizontal = 20.dp), verticalArrangement = Arrangement.Center) {
                    AgentFace(Color(agent?.color ?: 0xFFFF6B3D), agent?.face ?: "wink", 56.dp)
                    Spacer(Modifier.height(16.dp))
                    val hello = st.settings.userName.ifBlank { null }?.let { "Olá, $it" } ?: "Olá"
                    Text(hello, style = MaterialTheme.typography.headlineLarge, color = c.ink3)
                    Text(if (agent != null) "O que ${agent.name} deve fazer?" else "O que posso fazer por você?", style = MaterialTheme.typography.headlineLarge)
                    if (st.settings.apiKey.isBlank()) {
                        Spacer(Modifier.height(16.dp))
                        Row(Modifier.clip(RoundedCornerShape(14.dp)).background(c.accentSoft).clickable { nav.navigate("settings") }.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Key, null, tint = c.accent); Spacer(Modifier.width(8.dp))
                            Text("Adicione sua chave OpenRouter para começar", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (app.sandbox.state.collectAsStateWithLifecycle().value != dev.cueopen.app.sandbox.Sandbox.State.READY) {
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.clip(RoundedCornerShape(14.dp)).background(c.surface2).clickable { nav.navigate("sandbox") }.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Computer, null); Spacer(Modifier.width(8.dp))
                            Text("Instale o computador do agente (Linux local)", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    starters.forEach { s -> StarterCard(s) { draft = s.prompt } }
                }
                Composer(
                    value = draft, onChange = { draft = it },
                    placeholder = "Atribua uma tarefa ou pergunte qualquer coisa",
                    onSend = { text, files -> if (text.isNotBlank()) { startTask(nav, text, agentId, files, ctx); draft = "" } },
                )
            }
        }
    }
}

@Composable
private fun StarterCard(s: Starter, onClick: () -> Unit) {
    val c = Cue.c
    Column(Modifier.width(112.dp).clip(RoundedCornerShape(18.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(18.dp))
        .clickable(onClick = onClick).padding(12.dp)) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(if (c.dark) c.surface2 else Color(s.tint)), contentAlignment = Alignment.Center) {
            Icon(s.icon, null, tint = c.ink, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(10.dp))
        Text(s.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun AgentSelector(agent: AgentProfile?, agents: List<AgentProfile>, onSelect: (String?) -> Unit, onManage: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.clip(CircleShape).clickable { open = true }.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(agent?.name ?: "CueOpen", style = MaterialTheme.typography.titleLarge)
            Icon(Icons.Rounded.KeyboardArrowDown, null)
        }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem(text = { Text("CueOpen (padrão)") }, leadingIcon = { AgentFace(Color(0xFFFF6B3D), "wink", 24.dp) }, onClick = { onSelect(null); open = false })
            agents.forEach { a ->
                DropdownMenuItem(text = { Text(a.name) }, leadingIcon = { AgentFace(Color(a.color), a.face, 24.dp) }, onClick = { onSelect(a.id); open = false })
            }
            HorizontalDivider()
            DropdownMenuItem(text = { Text("Gerenciar Agents") }, leadingIcon = { Icon(Icons.Rounded.Groups, null) }, onClick = { open = false; onManage() })
        }
    }
}

/** Caixa de composição (anexo, voz, enviar) — usada na Home e no Chat. */
@Composable
fun Composer(
    value: String, onChange: (String) -> Unit, placeholder: String,
    running: Boolean = false, onStop: () -> Unit = {},
    onSend: (String, List<Uri>) -> Unit,
) {
    val c = Cue.c
    var files by remember { mutableStateOf(listOf<Uri>()) }
    val ctx = LocalContext.current
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { files = files + it }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { onChange((value + " " + it).trim()) }
    }
    Column(Modifier.fillMaxWidth().padding(12.dp)
        .clip(RoundedCornerShape(26.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(26.dp)).padding(12.dp)) {
        if (files.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                files.forEach { u ->
                    Row(Modifier.clip(RoundedCornerShape(10.dp)).background(c.surface2).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Description, null, Modifier.size(14.dp)); Spacer(Modifier.width(4.dp))
                        Text(queryName(ctx, u), fontSize = 12.sp, maxLines = 1)
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Rounded.Close, "Remover", Modifier.size(14.dp).clickable { files = files - u })
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().heightIn(min = 28.dp, max = 160.dp).padding(horizontal = 4.dp)) {
            if (value.isEmpty()) Text(placeholder, color = c.ink3, fontSize = 16.sp)
            BasicTextField(value, onChange, textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.ink),
                cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoundIcon(Icons.Rounded.Add, "Anexar") { pick.launch("*/*") }
            Spacer(Modifier.width(6.dp))
            RoundIcon(Icons.Rounded.Mic, "Voz") {
                runCatching { voice.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)) }
            }
            Spacer(Modifier.weight(1f))
            if (running) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(c.ink).clickable(onClick = onStop), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(12.dp).clip(RoundedCornerShape(2.dp)).background(c.bg))
                }
            } else {
                val enabled = value.isNotBlank()
                Box(Modifier.size(40.dp).clip(CircleShape).background(if (enabled) c.ink else c.surface2)
                    .clickable(enabled) { onSend(value.trim(), files); files = emptyList() }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.ArrowUpward, "Enviar", tint = if (enabled) c.bg else c.ink3)
                }
            }
        }
    }
}

@Composable
fun RoundIcon(icon: ImageVector, desc: String, onClick: () -> Unit) {
    val c = Cue.c
    Box(Modifier.size(36.dp).clip(CircleShape).border(1.dp, c.line, CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, desc, Modifier.size(20.dp), tint = c.ink2)
    }
}

@Composable
private fun SessionDrawer(nav: NavController, sessions: List<Session>, agents: List<AgentProfile>, onClose: () -> Unit, onNew: () -> Unit) {
    val c = Cue.c
    var query by remember { mutableStateOf("") }
    val filtered = sessions.filter { query.isBlank() || it.title.contains(query, true) }.sortedByDescending { it.updatedAt }
    fun go(r: String) { onClose(); nav.navigate(r) }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AgentFace(Color(0xFFFF6B3D), "wink", 32.dp); Spacer(Modifier.width(10.dp))
            Text("CueOpen", style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.height(12.dp))
        PillButton("Nova tarefa", Icons.Rounded.Edit, modifier = Modifier.fillMaxWidth()) { onNew() }
        Spacer(Modifier.height(8.dp))
        CueField(query, { query = it }, "Buscar tarefas")
        Spacer(Modifier.height(4.dp))
        DrawerItem(Icons.Rounded.Groups, "Agents", "${agents.size}") { go("agents") }
        DrawerItem(Icons.Rounded.AutoAwesome, "Skills") { go("skills") }
        DrawerItem(Icons.Rounded.Schedule, "Tarefas agendadas") { go("scheduled") }
        DrawerItem(Icons.Rounded.LibraryBooks, "Knowledge") { go("knowledge") }
        DrawerItem(Icons.Rounded.Psychology, "Memória") { go("memory") }
        DrawerItem(Icons.Rounded.FolderOpen, "Arquivos") { go("files") }
        DrawerItem(Icons.Rounded.Computer, "Computador (sandbox)") { go("sandbox") }
        Divider()
        LazyColumn(Modifier.weight(1f)) {
            val groups = filtered.groupBy { dayGroup(it.updatedAt) }
            groups.forEach { (g, list) ->
                item { SectionLabel(g) }
                items(list, key = { it.id }) { s -> SessionRow(s, agents.firstOrNull { it.id == s.agentId }) { go("chat/${s.id}") } }
            }
            if (filtered.isEmpty()) item { Text("Nenhuma tarefa ainda", color = c.ink3, modifier = Modifier.padding(16.dp)) }
        }
        DrawerItem(Icons.Rounded.Settings, "Configurações") { go("settings") }
    }
}

@Composable
private fun DrawerItem(icon: ImageVector, text: String, badge: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(20.dp), tint = Cue.c.ink2); Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (badge != null) Text(badge, color = Cue.c.ink3, fontSize = 13.sp)
    }
}

@Composable
private fun SessionRow(s: Session, agent: AgentProfile?, onClick: () -> Unit) {
    val c = Cue.c
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically) {
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
        StatusDot(s.status)
    }
}

@Composable
fun StatusDot(status: SessionStatus) {
    val c = Cue.c
    val col = when (status) {
        SessionStatus.RUNNING -> c.accent; SessionStatus.WAITING_USER -> c.warn; SessionStatus.ERROR -> c.err
        SessionStatus.DONE -> c.ok; else -> Color.Transparent
    }
    Box(Modifier.size(8.dp).clip(CircleShape).background(col))
}

fun previewOf(i: dev.cueopen.app.data.ChatItem): String = when (i) {
    is dev.cueopen.app.data.ChatItem.User -> i.text
    is dev.cueopen.app.data.ChatItem.Assistant -> i.text
    is dev.cueopen.app.data.ChatItem.Step -> i.title + " · " + i.args
    is dev.cueopen.app.data.ChatItem.Plan -> i.goal
    is dev.cueopen.app.data.ChatItem.Error -> i.text
    is dev.cueopen.app.data.ChatItem.Media -> "🖼 " + (i.caption.ifBlank { java.io.File(i.path).name })
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
