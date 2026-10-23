package dev.cueopen.app.ui

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.cueopen.app.CueApp
import dev.cueopen.app.agent.Activity
import dev.cueopen.app.data.ChatItem
import dev.cueopen.app.data.Session
import dev.cueopen.app.data.SessionStatus
import dev.cueopen.app.data.StepStatus
import dev.cueopen.app.ui.theme.Cue
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(nav: NavController, sessionId: String) {
    val app = CueApp.instance
    val st by app.store.state.collectAsStateWithLifecycle()
    val session = st.sessions.firstOrNull { it.id == sessionId } ?: run { LaunchedEffect(Unit) { nav.popBackStack() }; return }
    val agent = st.agents.firstOrNull { it.id == session.agentId }
    val activity by app.agent.activity.collectAsStateWithLifecycle()
    val act = activity[sessionId] ?: Activity()
    val running = session.status == SessionStatus.RUNNING
    val list = rememberLazyListState()
    var draft by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val c = Cue.c

    LaunchedEffect(session.items.size) { if (session.items.isNotEmpty()) list.animateScrollToItem(session.items.size) }

    Scaffold(containerColor = c.bg, topBar = {
        TopAppBar(
            colors = TopAppBarDefaults.topAppBarColors(containerColor = c.bg),
            navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Voltar") } },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (agent != null) { AgentFace(Color(agent.color), agent.face, 24.dp); Spacer(Modifier.width(8.dp)) }
                    Text(session.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                }
            },
            actions = {
                IconButton({ nav.navigate("computer/$sessionId") }) { Icon(Icons.Rounded.Computer, "Computador") }
                IconButton({ menu = true }) { Icon(Icons.Rounded.MoreHoriz, "Mais") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("Compartilhar") }, { menu = false; shareSession(ctx, session) }, leadingIcon = { Icon(Icons.Rounded.IosShare, null) })
                    DropdownMenuItem({ Text("Replay") }, { menu = false; nav.navigate("replay/$sessionId") }, leadingIcon = { Icon(Icons.Rounded.PlayCircle, null) })
                    DropdownMenuItem({ Text("Arquivos da tarefa") }, { menu = false; nav.navigate("computer/$sessionId?tab=files") }, leadingIcon = { Icon(Icons.Rounded.FolderOpen, null) })
                    DropdownMenuItem({ Text("Fazer igual (nova tarefa)") }, {
                        menu = false
                        val first = session.items.firstOrNull { it is ChatItem.User } as? ChatItem.User
                        nav.navigate("home?draft=${android.net.Uri.encode(first?.text ?: "")}")
                    }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) })
                    DropdownMenuItem({ Text("Agendar esta tarefa") }, {
                        menu = false
                        val first = session.items.firstOrNull { it is ChatItem.User } as? ChatItem.User
                        val t = dev.cueopen.app.data.ScheduledTask(title = session.title, prompt = first?.text ?: "", agentId = session.agentId)
                        app.store.upsertScheduled(t); nav.navigate("scheduled/${t.id}")
                    }, leadingIcon = { Icon(Icons.Rounded.Schedule, null) })
                    DropdownMenuItem({ Text("Renomear") }, { menu = false; rename = true }, leadingIcon = { Icon(Icons.Rounded.Edit, null) })
                    DropdownMenuItem({ Text("Excluir", color = c.err) }, {
                        menu = false; app.agent.stop(sessionId); app.store.deleteSession(sessionId); nav.popBackStack()
                    }, leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = c.err) })
                }
            },
        )
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding()) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(session.items, key = { it.id }) { item -> TimelineItem(item, onOpenComputer = { tab -> nav.navigate("computer/$sessionId?tab=$tab") }) }
                if (running) item { ThinkingRow(act) }
                if (session.status == SessionStatus.STOPPED) item { InfoRow(Icons.Rounded.StopCircle, "Tarefa interrompida. Envie uma mensagem para continuar.") }
                if (session.status == SessionStatus.DONE) item { DoneRow(onCopy = { copyText(ctx, lastAnswer(session)) }, onShare = { shareSession(ctx, session) }) }
            }
            AnimatedVisibility(running || act.kind != Activity.Kind.IDLE) {
                LiveComputerCard(act, agent?.name ?: "CueOpen") { nav.navigate("computer/$sessionId") }
            }
            Composer(
                value = draft, onChange = { draft = it },
                placeholder = if (session.status == SessionStatus.WAITING_USER) "Responda ao agente…" else "Envie uma mensagem",
                running = running, onStop = { app.agent.stop(sessionId) },
                onSend = { text, files ->
                    val ws = app.sandbox.workspaceFor(sessionId)
                    val names = files.mapNotNull { u -> runCatching {
                        val n = queryName(ctx, u); ctx.contentResolver.openInputStream(u)!!.use { i -> File(ws, n).outputStream().use { i.copyTo(it) } }; n
                    }.getOrNull() }
                    app.agent.send(sessionId, text, names); draft = ""
                },
            )
        }
    }
    if (rename) {
        var t by remember { mutableStateOf(session.title) }
        AlertDialog(onDismissRequest = { rename = false }, title = { Text("Renomear tarefa") }, text = { CueField(t, { t = it }, "Título") },
            confirmButton = { TextButton({ app.store.editSession(sessionId) { it.copy(title = t) }; rename = false }) { Text("Salvar") } },
            dismissButton = { TextButton({ rename = false }) { Text("Cancelar") } })
    }
}

@Composable
fun TimelineItem(item: ChatItem, onOpenComputer: (String) -> Unit) {
    when (item) {
        is ChatItem.User -> UserBubble(item)
        is ChatItem.Assistant -> AssistantText(item)
        is ChatItem.Step -> StepRow(item, onOpenComputer)
        is ChatItem.Plan -> PlanCard(item)
        is ChatItem.Error -> InfoRow(Icons.Rounded.ErrorOutline, item.text, Cue.c.err)
    }
}

@Composable
private fun UserBubble(i: ChatItem.User) {
    val c = Cue.c
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        if (i.attachments.isNotEmpty()) {
            Row(Modifier.padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                i.attachments.forEach { a ->
                    Row(Modifier.clip(RoundedCornerShape(12.dp)).border(1.dp, c.line, RoundedCornerShape(12.dp)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Description, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(a, fontSize = 12.sp)
                    }
                }
            }
        }
        SelectionContainer {
            Text(i.text, Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)).background(c.bubble).padding(horizontal = 14.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun AssistantText(i: ChatItem.Assistant) {
    val c = Cue.c
    Column(Modifier.fillMaxWidth()) {
        if (i.final) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
            AgentFace(c.accent, "smile", 20.dp); Spacer(Modifier.width(6.dp))
            Text("CueOpen", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
        Markdown(i.text)
    }
}

/** Markdown leve: títulos, listas, **negrito**, `código` e blocos ``` . */
@Composable
fun Markdown(text: String) {
    val c = Cue.c
    val blocks = remember(text) { text.split("```") }
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            blocks.forEachIndexed { idx, b ->
                if (idx % 2 == 1) {
                    val code = b.substringAfter('\n', b).trimEnd()
                    Text(code, Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface2).horizontalScroll(rememberScrollState()).padding(12.dp),
                        fontFamily = FontFamily.Monospace, fontSize = 13.sp, softWrap = false)
                } else {
                    b.trim('\n').lines().forEach { line ->
                        when {
                            line.startsWith("### ") -> Text(mdInline(line.drop(4)), style = MaterialTheme.typography.titleMedium)
                            line.startsWith("## ") -> Text(mdInline(line.drop(3)), style = MaterialTheme.typography.titleLarge)
                            line.startsWith("# ") -> Text(mdInline(line.drop(2)), style = MaterialTheme.typography.headlineSmall)
                            line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") ->
                                Row { Text("•  ", color = c.ink2); Text(mdInline(line.trimStart().drop(2)), style = MaterialTheme.typography.bodyLarge) }
                            line.isBlank() -> Spacer(Modifier.height(2.dp))
                            else -> Text(mdInline(line), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun mdInline(s: String): AnnotatedString {
    val c = Cue.c
    return buildAnnotatedString {
        var i = 0
        while (i < s.length) {
            when {
                s.startsWith("**", i) && s.indexOf("**", i + 2) > 0 -> { val e = s.indexOf("**", i + 2); withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(s.substring(i + 2, e)) }; i = e + 2 }
                s[i] == '`' && s.indexOf('`', i + 1) > 0 -> { val e = s.indexOf('`', i + 1); withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = c.surface2)) { append(s.substring(i + 1, e)) }; i = e + 1 }
                else -> { append(s[i]); i++ }
            }
        }
    }
}

fun toolIcon(tool: String): ImageVector = when {
    tool == "shell" -> Icons.Rounded.Terminal
    tool.startsWith("browser") -> Icons.Rounded.Language
    tool.startsWith("file") -> Icons.Rounded.EditNote
    tool == "remember" -> Icons.Rounded.Psychology
    tool == "create_skill" -> Icons.Rounded.AutoAwesome
    else -> Icons.Rounded.Bolt
}

@Composable
private fun StepRow(s: ChatItem.Step, onOpenComputer: (String) -> Unit) {
    val c = Cue.c
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.clip(RoundedCornerShape(14.dp)).clickable { open = !open }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(26.dp).clip(CircleShape).background(c.surface2), contentAlignment = Alignment.Center) {
                when (s.status) {
                    StepStatus.RUNNING -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = c.accent)
                    StepStatus.ERROR -> Icon(Icons.Rounded.Close, null, Modifier.size(14.dp), tint = c.err)
                    StepStatus.OK -> Icon(toolIcon(s.tool), null, Modifier.size(14.dp), tint = c.ink2)
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(s.title, fontSize = 14.sp, color = c.ink2)
            if (s.args.isNotBlank()) {
                Spacer(Modifier.width(6.dp))
                Text(s.args, Modifier.weight(1f, fill = false).clip(RoundedCornerShape(8.dp)).background(c.surface2).padding(horizontal = 8.dp, vertical = 3.dp),
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        AnimatedVisibility(open && s.result.isNotBlank()) {
            Column(Modifier.padding(start = 34.dp, top = 4.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (s.tool == "shell") Color(0xFF0F1115) else c.surface2).padding(10.dp)) {
                Text(s.result.takeLast(2500), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = if (s.tool == "shell") Color(0xFFE6E6E6) else c.ink2)
                TextButton({ onOpenComputer(if (s.tool.startsWith("browser")) "browser" else if (s.tool.startsWith("file")) "files" else "terminal") }) {
                    Text("Ver no computador", color = c.accent)
                }
            }
        }
    }
}

@Composable
private fun PlanCard(p: ChatItem.Plan) {
    val c = Cue.c
    var open by remember { mutableStateOf(true) }
    val done = p.phases.count { it.done }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).border(1.dp, c.line, RoundedCornerShape(18.dp)).background(c.surface).padding(14.dp)) {
        Row(Modifier.clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Checklist, null, tint = c.accent); Spacer(Modifier.width(8.dp))
            Text(p.goal, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = if (open) 3 else 1, overflow = TextOverflow.Ellipsis)
            Text("$done/${p.phases.size}", color = c.ink3, fontSize = 13.sp)
            Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = c.ink3)
        }
        LinearProgressIndicator(progress = { if (p.phases.isEmpty()) 0f else done / p.phases.size.toFloat() },
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp).height(4.dp).clip(CircleShape), color = c.accent, trackColor = c.surface2)
        AnimatedVisibility(open) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                p.phases.forEachIndexed { i, ph ->
                    val current = !ph.done && p.phases.take(i).all { it.done }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(when { ph.done -> Icons.Rounded.CheckCircle; current -> Icons.Rounded.RadioButtonChecked; else -> Icons.Rounded.RadioButtonUnchecked },
                            null, Modifier.size(18.dp), tint = when { ph.done -> c.ok; current -> c.accent; else -> c.ink3 })
                        Spacer(Modifier.width(8.dp))
                        Text(ph.title, fontSize = 14.sp, color = if (ph.done) c.ink3 else c.ink)
                    }
                }
            }
        }
    }
}

@Composable
private fun ThinkingRow(a: Activity) {
    val c = Cue.c
    val t = rememberInfiniteTransition(label = "t")
    val alpha by t.animateFloat(0.3f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a")
    Row(verticalAlignment = Alignment.CenterVertically) {
        AgentFace(c.accent, "side", 22.dp, Modifier.alpha(alpha)); Spacer(Modifier.width(8.dp))
        Text(when (a.kind) { Activity.Kind.THINKING, Activity.Kind.IDLE -> "Pensando…"; else -> "Trabalhando…" }, color = c.ink2, fontSize = 14.sp)
    }
}

@Composable
private fun InfoRow(icon: ImageVector, text: String, tint: Color = Cue.c.ink2) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Cue.c.surface2).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 14.sp, color = tint)
    }
}

@Composable
private fun DoneRow(onCopy: () -> Unit, onShare: () -> Unit) {
    val c = Cue.c
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(Icons.Rounded.ContentCopy to onCopy, Icons.Rounded.IosShare to onShare).forEach { (ic, fn) ->
            Box(Modifier.size(30.dp).clip(CircleShape).clickable(onClick = fn), contentAlignment = Alignment.Center) {
                Icon(ic, null, Modifier.size(16.dp), tint = c.ink3)
            }
        }
    }
}

fun lastAnswer(s: Session): String =
    (s.items.lastOrNull { it is ChatItem.Assistant } as? ChatItem.Assistant)?.text ?: sessionMarkdown(s)

fun copyText(ctx: android.content.Context, text: String) {
    val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
    cm.setPrimaryClip(android.content.ClipData.newPlainText("CueOpen", text))
    android.widget.Toast.makeText(ctx, "Copiado", android.widget.Toast.LENGTH_SHORT).show()
}

/** Card "Computador do agente" que fica acima do composer enquanto a tarefa roda (como no Cue). */
@Composable
private fun LiveComputerCard(a: Activity, name: String, onClick: () -> Unit) {
    val c = Cue.c
    val label = when (a.kind) {
        Activity.Kind.TERMINAL -> "Usando Terminal"; Activity.Kind.BROWSER -> "Usando Browser"
        Activity.Kind.EDITOR -> "Usando Editor"; Activity.Kind.SEARCH -> "Pesquisando"; else -> "Pensando"
    }
    val icon = when (a.kind) { Activity.Kind.TERMINAL -> Icons.Rounded.Terminal; Activity.Kind.BROWSER -> Icons.Rounded.Language; Activity.Kind.EDITOR -> Icons.Rounded.EditNote; else -> Icons.Rounded.Computer }
    Row(Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(18.dp))
        .clickable(onClick = onClick).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 64.dp, height = 44.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF0F1115)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color(0xFFFF8A63), modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Computador de $name", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text("$label${if (a.detail.isNotBlank()) " · " + a.detail else ""}", fontSize = 12.sp, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Rounded.OpenInFull, null, tint = c.ink3, modifier = Modifier.size(18.dp))
    }
}

fun sessionMarkdown(s: Session): String = buildString {
    appendLine("# ${s.title}\n")
    s.items.forEach { i ->
        when (i) {
            is ChatItem.User -> appendLine("**Você:** ${i.text}\n")
            is ChatItem.Assistant -> appendLine("**CueOpen:** ${i.text}\n")
            is ChatItem.Step -> appendLine("> ${i.title}: `${i.args}`")
            is ChatItem.Plan -> { appendLine("**Plano:** ${i.goal}"); i.phases.forEach { appendLine("- [${if (it.done) "x" else " "}] ${it.title}") }; appendLine() }
            is ChatItem.Error -> appendLine("⚠️ ${i.text}\n")
        }
    }
    appendLine("\n— gerado com CueOpen")
}

/** Compartilhar = exporta a tarefa (replay em Markdown) + arquivos via share sheet do Android. */
fun shareSession(ctx: android.content.Context, s: Session) {
    val app = CueApp.instance
    val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
    val md = File(dir, "${s.title.replace(Regex("[^\\w\\-]+"), "_").take(40)}.md").apply { writeText(sessionMarkdown(s)) }
    val uris = arrayListOf(FileProvider.getUriForFile(ctx, ctx.packageName + ".files", md))
    app.sandbox.workspaceFor(s.id).walkTopDown().filter { it.isFile && it.length() < 20_000_000 }.take(10).forEach {
        uris.add(FileProvider.getUriForFile(ctx, ctx.packageName + ".files", it))
    }
    val i = Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        .putExtra(Intent.EXTRA_TEXT, sessionMarkdown(s).take(4000)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(i, "Compartilhar tarefa"))
}
