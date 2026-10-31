package dev.cueopen.app.ui

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
import dev.cueopen.app.agent.AskTools
import dev.cueopen.app.data.ChatItem
import dev.cueopen.app.data.Session
import dev.cueopen.app.data.SessionStatus
import dev.cueopen.app.data.StepStatus
import dev.cueopen.app.ui.theme.Cue
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(nav: NavController, sessionId: String) {
    val app = CueApp.instance
    val st by app.store.state.collectAsStateWithLifecycle()
    val session = st.sessions.firstOrNull { it.id == sessionId } ?: run { LaunchedEffect(Unit) { nav.popBackStack() }; return }
    // Chat do criador de UI conector: voltar leva direto à página inicial (limpa a pilha).
    val goBack: () -> Unit = {
        if (session.connectorBuilder) nav.navigate("home") { popUpTo(0) { inclusive = true }; launchSingleTop = true }
        else nav.popBackStack()
    }
    androidx.activity.compose.BackHandler(enabled = session.connectorBuilder) { goBack() }
    val agent = st.agents.firstOrNull { it.id == session.agentId }
    val activeUiConnectors = app.uiConnectors.connectors.filter { app.uiConnectors.usableBy(it, agent) }
    val activeMcpConnectors = st.connectors.filter { it.enabled && it.connectedAt != null && it.toolsJson != null }
    val activity by app.agent.activity.collectAsStateWithLifecycle()
    val act = activity[sessionId] ?: Activity()
    val autoAgent = st.agents.firstOrNull { it.id == session.agentId && it.autonomous && it.homeSessionId == sessionId }
    val running = session.status == SessionStatus.RUNNING
    val list = rememberLazyListState()
    var draft by remember { mutableStateOf("") }
    var copyingAttachments by remember { mutableStateOf(false) }
    var replyingTo by remember(sessionId) { mutableStateOf<ChatItem.Assistant?>(null) }
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var usage by remember { mutableStateOf(false) }
    var connectorsDialog by remember { mutableStateOf(false) }
    var mediaOpen by remember { mutableStateOf<String?>(null) }
    val cmdScope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val c = Cue.c

    LaunchedEffect(sessionId) {
        ctx.getSharedPreferences(dev.cueopen.app.assistant.AssistantRoutingPolicy.PREFERENCES, android.content.Context.MODE_PRIVATE)
            .edit()
            .putString(dev.cueopen.app.assistant.AssistantRoutingPolicy.KEY_ACTIVE_SESSION_ID, sessionId)
            .apply()
    }

    // Auto-scroll: segue o stream enquanto o usuário está no fim; se ele rolar para cima, assume o controle.
    var follow by remember { mutableStateOf(true) }
    val atBottom by remember {
        derivedStateOf {
            val info = list.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            last.index >= info.totalItemsCount - 1 && last.offset + last.size <= info.viewportEndOffset + 24
        }
    }
    LaunchedEffect(list.isScrollInProgress, atBottom) {
        if (list.isScrollInProgress) follow = atBottom   // gesto do usuário decide
        else if (atBottom) follow = true
    }
    val visibleItems = remember(session.items) { AskTools.visibleTimelineItems(session.items) }
    val lastItem = session.items.lastOrNull()
    // Evita hashCode() no objeto inteiro (que pode conter milhares de caracteres) em cada atualização.
    val lastItemKey = when (lastItem) {
        is ChatItem.Assistant -> 31 * lastItem.id.hashCode() + lastItem.text.length + lastItem.text.take(128).hashCode()
        is ChatItem.User -> 31 * lastItem.id.hashCode() + lastItem.text.length + lastItem.text.take(128).hashCode()
        is ChatItem.Step -> 31 * lastItem.id.hashCode() + lastItem.result.length + lastItem.title.hashCode()
        null -> 0
        else -> lastItem.id.hashCode()
    }
    val streamKey = session.items.size to lastItemKey
    LaunchedEffect(streamKey, running, follow) {
        if (follow) {
            val total = list.layoutInfo.totalItemsCount
            if (total > 0) list.scrollToItem(total - 1, Int.MAX_VALUE / 2)
        }
    }

    Scaffold(containerColor = c.bg, topBar = {
        TopAppBar(
            colors = TopAppBarDefaults.topAppBarColors(containerColor = c.bg),
            navigationIcon = { IconButton({ goBack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Voltar") } },
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
                        val t = dev.cueopen.app.data.ScheduledTask(title = session.title, prompt = first?.text ?: "", agentId = session.agentId, sourceSessionId = sessionId)
                        app.store.upsertScheduled(t); nav.navigate("scheduled/${t.id}")
                    }, leadingIcon = { Icon(Icons.Rounded.Schedule, null) })
                    DropdownMenuItem({ Text("Uso e contexto") }, { menu = false; usage = true }, leadingIcon = { Icon(Icons.Rounded.DataUsage, null) })
                    DropdownMenuItem({ Text("Conectores") }, { menu = false; connectorsDialog = true }, leadingIcon = { Icon(Icons.Rounded.SettingsEthernet, null) })
                    DropdownMenuItem({ Text("Renomear") }, { menu = false; rename = true }, leadingIcon = { Icon(Icons.Rounded.Edit, null) })
                    if (autoAgent != null) DropdownMenuItem({ Text("Pausar agente") }, {
                        menu = false; app.agent.pauseAgent(autoAgent.id)
                    }, leadingIcon = { Icon(Icons.Rounded.PauseCircle, null) })
                    DropdownMenuItem({ Text("Excluir", color = c.err) }, {
                        menu = false; app.deleteChat(sessionId); nav.popBackStack()
                    }, leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = c.err) })
                }
            },
        )
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding()) {
          Box(Modifier.weight(1f).fillMaxWidth()) {
            androidx.compose.runtime.CompositionLocalProvider(LocalChatPersona provides ChatPersona.of(agent)) {
            LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(visibleItems, key = { it.id }) { item ->
                    TimelineItem(item, onOpenComputer = { tab -> nav.navigate("computer/$sessionId?tab=$tab") }, onOpenMedia = { mediaOpen = it }, sessionId = sessionId, nav = nav,
                        onSend = { msg -> app.agent.send(sessionId, msg, emptyList()) },
                        onReply = { replyingTo = it })
                }
                if (running) item { ThinkingRow(act) }
                if (session.status == SessionStatus.SLEEPING) item {
                    val t = session.wakeAt?.let { java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(it)) } ?: "—"
                    InfoRow(Icons.Rounded.Bedtime, "Modo contínuo: próxima verificação às $t. Toque em ■ ou envie uma mensagem para interromper.")
                }
                if (session.status == SessionStatus.STOPPED) item { InfoRow(Icons.Rounded.StopCircle, "Tarefa interrompida. Envie uma mensagem para continuar.") }
                if (session.status == SessionStatus.DONE) item { DoneRow(onCopy = { copyText(ctx, lastAnswer(session)) }, onShare = { shareText(ctx, lastAnswer(session)) }) }
            }
            }
            if (!follow) {
                SmallFloatingActionButton({ follow = true }, Modifier.align(Alignment.BottomEnd).padding(16.dp), containerColor = c.ink, contentColor = c.bg) {
                    Icon(Icons.Rounded.KeyboardArrowDown, "Ir para o fim")
                }
            }
          }
            val plan = session.items.lastOrNull { it is ChatItem.Plan } as? ChatItem.Plan
            AnimatedVisibility(running || act.kind != Activity.Kind.IDLE || (plan != null && plan.phases.any { !it.done })) {
                LiveComputerCard(act, agent?.name ?: "Fluwy Bot", plan, active = running) { nav.navigate("computer/$sessionId") }
            }
            mediaOpen?.let { p -> MediaDialog(java.io.File(p)) { mediaOpen = null } }
            Composer(
                value = draft, onChange = { draft = it },
                placeholder = if (session.status == SessionStatus.WAITING_USER) "Responda ao agente…" else "Envie uma mensagem",
                running = running || session.status == SessionStatus.SLEEPING || autoAgent != null, onStop = { app.agent.stop(sessionId) },
                // Cronômetro até o próximo ciclo: durante um ciclo ativo (RUNNING) não mostra; parado,
                // usa wakeAt (modo contínuo) ou o próximo heartbeat agendado (lastBeatAt + intervalo).
                nextCycleAt = if (running) null else
                    (session.wakeAt ?: autoAgent?.let { (it.lastBeatAt ?: System.currentTimeMillis()) + it.heartbeatMinutes * 60_000L })
                        ?.takeIf { it > System.currentTimeMillis() },
                replyText = replyingTo?.text?.take(800),
                replyAuthor = agent?.name ?: "Fluwy Bot",
                onClearReply = { replyingTo = null },
                onSend = { text, files ->
                    if (text.trim().startsWith("@create-agent", true)) {
                        val desc = text.trim().removePrefix("@create-agent").removePrefix("@Create-Agent").trim()
                        draft = ""
                        if (desc.isBlank()) app.store.addItem(sessionId, ChatItem.Assistant(text = "Descreva o agente após @create-agent. Ex.: `@create-agent um analista de ações que todo dia de manhã resume o mercado`", final = false))
                        else {
                            app.store.addItem(sessionId, ChatItem.Assistant(text = "Criando agente…", final = false))
                            cmdScope.launch {
                                val a = runCatching { app.agent.createAgentFromPrompt(desc, conversationId = sessionId) }.getOrNull()
                                val okMsg = "✅ Agente **" + a?.name + "** criado e salvo em Agents" + (if (a?.autonomous == true) " · autônomo a cada ${a.heartbeatMinutes} min" else "") + ".\n\n" + (a?.instructions ?: "")
                                app.store.addItem(sessionId, if (a != null)
                                    ChatItem.Assistant(text = okMsg, final = true)
                                    else ChatItem.Assistant(text = "Não consegui gerar o agente. Tente descrever de outro jeito.", final = true))
                            }
                        }
                        return@Composer
                    }
                    val cmd = text.trim().lowercase()
                    if (cmd.startsWith("/") && !cmd.contains(' ')) {
                        draft = ""
                        when (cmd) {
                            "/compact" -> cmdScope.launch {
                                val step = ChatItem.Step(tool = "compact", title = "Compactando contexto…", args = "", status = StepStatus.RUNNING)
                                app.store.addItem(sessionId, step)
                                val msg = app.agent.compactNow(sessionId)
                                app.store.replaceItem(sessionId, step.copy(title = "Contexto compactado", args = msg.substringAfter(":").trim().ifBlank { "memória reduzida" }, status = StepStatus.OK))
                            }
                            "/autocompact" -> cmdScope.launch {
                                val on = !(app.store.session(sessionId)?.autoCompact ?: true)
                                val step = ChatItem.Step(tool = "autocompact", title = "Ajustando compactação automática…", args = "", status = StepStatus.RUNNING)
                                app.store.addItem(sessionId, step)
                                app.store.editSession(sessionId) { it.copy(autoCompact = on) }
                                kotlinx.coroutines.delay(450)
                                app.store.replaceItem(sessionId, step.copy(title = if (on) "Compactação automática habilitada" else "Compactação automática desligada", args = if (on) "ligada" else "desligada", status = StepStatus.OK))
                            }
                            "/clear" -> cmdScope.launch {
                                val step = ChatItem.Step(tool = "clear", title = "Limpando contexto…", args = "", status = StepStatus.RUNNING)
                                app.store.addItem(sessionId, step)
                                app.store.editSession(sessionId) { it.copy(llm = emptyList(), summary = "") }
                                kotlinx.coroutines.delay(450)
                                app.store.replaceItem(sessionId, step.copy(title = "Contexto do modelo limpo", args = "histórico mantido na tela", status = StepStatus.OK))
                            }
                            "/stop" -> app.agent.stop(sessionId)
                            "/usage" -> usage = true
                            "/memory" -> nav.navigate("memory")
                            "/skills" -> nav.navigate("skills")
                            "/connectors" -> nav.navigate("connectors")
                            else -> app.store.addItem(sessionId, ChatItem.Assistant(text = "**Comandos**\n" + slashCommands.joinToString("\n") { "- `${it.first}` — ${it.second}" } +
                                "\n\nUse `@` para mencionar uma skill (carrega as instruções dela) ou um Agent (passa a tarefa para ele).", final = false))
                        }
                        return@Composer
                    }
                    val reply = replyingTo?.text
                    val attachments = files.toList()
                    if (attachments.isEmpty()) {
                        app.agent.send(sessionId, text, emptyList(), quotedReply = reply)
                        draft = ""; replyingTo = null
                    } else {
                        if (copyingAttachments) return@Composer
                        copyingAttachments = true
                        draft = ""; replyingTo = null
                        app.applicationScope.launch {
                            try {
                                val names = withContext(Dispatchers.IO) {
                                    val ws = app.sandbox.workspaceFor(sessionId)
                                    attachments.mapNotNull { u -> runCatching {
                                        val name = queryName(ctx, u)
                                        ctx.contentResolver.openInputStream(u)?.use { i -> File(ws, name).outputStream().use { i.copyTo(it) } }
                                            ?: error("O anexo não pôde ser aberto.")
                                        name
                                    }.getOrNull() }
                                }
                                app.agent.send(sessionId, text, names, quotedReply = reply)
                            } finally {
                                copyingAttachments = false
                            }
                        }
                    }
                },
            )
        }
    }
    if (connectorsDialog) {
        AlertDialog(
            onDismissRequest = { connectorsDialog = false },
            title = { Text("Conectores deste chat") },
            text = {
                Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Somente conectores habilitados e disponíveis para este agente aparecem aqui. Abrir esta lista não conecta nem atualiza serviços.", fontSize = 12.sp, color = c.ink2)
                    if (activeUiConnectors.isNotEmpty()) {
                        Text("UI Connectors", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                        activeUiConnectors.forEach { connector ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface2)
                                    .clickable {
                                        connectorsDialog = false
                                        nav.navigate("uiconnector/${android.net.Uri.encode(connector.spec.id)}")
                                    }.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(connector.spec.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                    Text(
                                        when {
                                            connector.isLoggedIn -> "Conectado"
                                            app.uiConnectors.hasSavedCredentials(connector.spec.id) -> "Credenciais salvas · login necessário"
                                            else -> "Login necessário"
                                        },
                                        fontSize = 11.sp,
                                        color = c.ink2,
                                    )
                                }
                                Icon(Icons.Rounded.ChevronRight, null, tint = c.ink3)
                            }
                        }
                    }
                    if (activeMcpConnectors.isNotEmpty()) {
                        Text("Servidores MCP", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                        activeMcpConnectors.forEach { connector ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface2)
                                    .clickable {
                                        connectorsDialog = false
                                        nav.navigate("mcpconnector/${android.net.Uri.encode(connector.id)}")
                                    }.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(connector.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                    Text("Conectado", fontSize = 11.sp, color = c.ink2)
                                }
                                Icon(Icons.Rounded.ChevronRight, null, tint = c.ink3)
                            }
                        }
                    }
                    if (activeUiConnectors.isEmpty() && activeMcpConnectors.isEmpty()) {
                        Text("Nenhum conector está ativo e disponível neste chat.", fontSize = 13.sp, color = c.ink2, modifier = Modifier.padding(vertical = 8.dp))
                    }
                }
            },
            confirmButton = { TextButton({ connectorsDialog = false }) { Text("Fechar") } },
            dismissButton = {
                TextButton({ connectorsDialog = false; nav.navigate("connectors") }) { Text("Gerenciar") }
            },
        )
    }
    if (usage) {
        AlertDialog(onDismissRequest = { usage = false }, title = { Text("Uso e contexto") }, text = {
            val totalTokens = (session.tokensIn + session.tokensOut).coerceAtLeast(1L)
            Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Modelo: ${session.modelUsed ?: app.store.settings.model}", fontSize = 12.sp, color = c.ink2)
                Text("Chamadas ao modelo: ${session.llmCalls}")
                Text("Contexto ativo: ${session.llm.size} mensagens · ${session.compactions} compactações")
                Text("Tokens usados", fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Entrada", modifier = Modifier.width(68.dp), fontSize = 12.sp)
                    LinearProgressIndicator(progress = { session.tokensIn.toFloat() / totalTokens }, modifier = Modifier.weight(1f), color = c.accent)
                    Text(session.tokensIn.toString(), fontSize = 11.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Saída", modifier = Modifier.width(68.dp), fontSize = 12.sp)
                    LinearProgressIndicator(progress = { session.tokensOut.toFloat() / totalTokens }, modifier = Modifier.weight(1f), color = c.ink)
                    Text(session.tokensOut.toString(), fontSize = 11.sp)
                }
                Text("Tokens em cache: ${session.tokensCached}")
                if (session.hasPricedUsage) {
                    HorizontalDivider()
                    Text("Gasto estimado neste chat", fontWeight = FontWeight.SemiBold)
                    Text("$" + String.format(java.util.Locale.US, "%.6f", session.costUsd), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = c.accent)
                    Text("Estimativa baseada nos preços publicados pela OpenRouter e no uso informado pela API. O valor pode variar.", fontSize = 11.sp, color = c.ink2)
                }
                Text("Modo contínuo: ${if (session.continuous) "ativo" else "desligado"}")
                if (session.summary.isNotBlank()) {
                    Text("Resumo da sessão:", fontWeight = FontWeight.SemiBold)
                    Text(session.summary.take(1500), fontSize = 12.sp, color = c.ink2)
                }
            }
        }, confirmButton = { TextButton({ usage = false }) { Text("Fechar") } })
    }
    if (rename) {
        var t by remember { mutableStateOf(session.title) }
        AlertDialog(onDismissRequest = { rename = false }, title = { Text("Renomear tarefa") }, text = { CueField(t, { t = it }, "Título") },
            confirmButton = { TextButton({ app.store.editSession(sessionId) { it.copy(title = t) }; rename = false }) { Text("Salvar") } },
            dismissButton = { TextButton({ rename = false }) { Text("Cancelar") } })
    }
}

@Composable
fun TimelineItem(item: ChatItem, onOpenComputer: (String) -> Unit, onOpenMedia: (String) -> Unit = {}, sessionId: String? = null, nav: androidx.navigation.NavController? = null, onSend: ((String) -> Unit)? = null, onReply: ((ChatItem.Assistant) -> Unit)? = null) {
    when (item) {
        is ChatItem.User -> UserBubble(item)
        is ChatItem.Assistant -> AssistantText(item, onSend, onReply)
        is ChatItem.Step -> StepRow(item, onOpenComputer)
        is ChatItem.Plan -> {}  // o plano fica no card do computador, acima do composer
        is ChatItem.Media -> MediaCard(item, onOpenMedia)
        is ChatItem.Error -> InfoRow(Icons.Rounded.ErrorOutline, item.text, Cue.c.err)
        is ChatItem.ConnectorProposal -> ConnectorProposalCard(item, sessionId, nav)
        is ChatItem.ConnectorEditProposal -> ConnectorEditProposalCard(item, sessionId)
        is ChatItem.Question -> QuestionCard(item, sessionId)
        is ChatItem.SecureForm -> SecureFormCard(item, sessionId)
    }
}

@Composable
private fun UserBubble(i: ChatItem.User) {
    val c = Cue.c
    Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 10.dp), horizontalAlignment = Alignment.End) {
        i.replyTo?.takeIf { it.isNotBlank() }?.let { quoted ->
            Row(Modifier.widthIn(max = 320.dp).padding(bottom = 6.dp).clip(RoundedCornerShape(12.dp)).background(c.surface2).padding(start = 8.dp, end = 10.dp, top = 7.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(3.dp).height(36.dp).clip(RoundedCornerShape(2.dp)).background(c.accent))
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("Respondendo ao agente", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = c.ink2)
                    Text(quoted, fontSize = 12.sp, color = c.ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
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
private fun AssistantText(i: ChatItem.Assistant, onSend: ((String) -> Unit)? = null, onReply: ((ChatItem.Assistant) -> Unit)? = null) {
    val c = Cue.c
    var dragX by remember(i.id) { mutableStateOf(0f) }
    val density = LocalDensity.current
    val thresholdPx = with(density) { 72.dp.toPx() }
    val maxOffsetPx = with(density) { 112.dp.toPx() }
    val dragModifier = if (onReply != null) Modifier.pointerInput(i.id, onReply) {
        detectHorizontalDragGestures(
            onHorizontalDrag = { change, amount ->
                if (amount > 0f || dragX > 0f) dragX = (dragX + amount).coerceIn(0f, maxOffsetPx)
                change.consume()
            },
            onDragEnd = { if (dragX >= thresholdPx) onReply(i); dragX = 0f },
            onDragCancel = { dragX = 0f },
        )
    } else Modifier
    Box(Modifier.fillMaxWidth()) {
        if (dragX > 4f) Icon(Icons.Rounded.Reply, "Responder", Modifier.align(Alignment.CenterStart).padding(start = 8.dp).size(20.dp), tint = c.accent)
        Column(Modifier.fillMaxWidth().then(dragModifier).graphicsLayer { translationX = dragX }) {
            if (i.final) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                val persona = LocalChatPersona.current
                AgentFace(persona.color, persona.face, 20.dp); Spacer(Modifier.width(6.dp))
                Text(persona.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
            CueMarkdown(i.text, onSend = onSend)
        }
    }
}

/** Markdown leve: títulos, listas, **negrito**, `código` e blocos ``` . */
@Composable
fun Markdown(text: String) = CueMarkdown(text)

@Composable
private fun LegacyMarkdown(text: String) {
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
    tool == "multi_agent_worker" -> Icons.Rounded.CheckCircle
    tool == "clear" -> Icons.Rounded.CleaningServices
    tool == "compact" -> Icons.Rounded.Compress
    tool == "autocompact" -> Icons.Rounded.AutoMode
    else -> Icons.Rounded.Bolt
}

@Composable
private fun StepRow(s: ChatItem.Step, onOpenComputer: (String) -> Unit) {
    val c = Cue.c
    // Só há expansão quando existe conteúdo útil. Comandos sem saída (ex.: mkdir) não viram pílulas clicáveis.
    val hasUsefulOutput = s.result.isNotBlank() && (
        s.tool in setOf("file_write", "file_edit", "file_read") ||
        s.status == StepStatus.ERROR ||
        s.result.lineSequence().any { it.trim().isNotEmpty() && !it.trim().matches(Regex("\\[exit \\d+\\]")) }
    )
    val canExpand = hasUsefulOutput
    var open by remember(s.id) { mutableStateOf(false) }
    val filePreview = remember(s.result, s.tool) {
        if (s.tool == "file_write" || s.tool == "file_edit") s.result.lineSequence().take(10).joinToString("\n")
        else s.result.takeLast(2500)
    }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.clip(RoundedCornerShape(14.dp)).then(if (canExpand) Modifier.clickable { open = !open } else Modifier).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
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
        AnimatedVisibility(canExpand && open) {
            Column(Modifier.padding(start = 34.dp, top = 4.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (s.tool == "shell") Color(0xFF0F1115) else c.surface2).padding(10.dp)) {
                if (s.tool == "file_write" || s.tool == "file_edit") {
                    val lang = s.args.substringAfterLast('.').substringBefore(' ').lowercase()
                    val palette = if (c.dark) SyntaxPalette.dark else SyntaxPalette.light
                    val preview = remember(filePreview, lang, c.dark) { Syntax.highlight(filePreview, lang, palette) }
                    Text(preview, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp, color = c.ink2)
                    Text("Prévia: até 10 linhas", fontSize = 10.sp, color = c.ink3)
                } else {
                    Text(s.result.takeLast(2500), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = if (s.tool == "shell") Color(0xFFE6E6E6) else c.ink2)
                }
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

/** Quem está respondendo neste chat: o Agent escolhido (cor + forma do mascote) ou o Fluwy Bot padrão. */
data class ChatPersona(val color: Color, val face: String, val name: String) {
    companion object {
        val DEFAULT = ChatPersona(Color(0xFFFF6B3D), "wink", "Fluwy Bot")
        fun of(agent: dev.cueopen.app.data.AgentProfile?): ChatPersona =
            if (agent == null) DEFAULT else ChatPersona(Color(agent.color), agent.face, agent.name.ifBlank { DEFAULT.name })
    }
}

val LocalChatPersona = androidx.compose.runtime.staticCompositionLocalOf { ChatPersona.DEFAULT }

/** "Pensando…": o mascote é o MESMO do Agent escolhido (igual ao do topo do chat), respirando e balançando. */
@Composable
private fun ThinkingRow(a: Activity) {
    val c = Cue.c
    val persona = LocalChatPersona.current
    val t = rememberInfiniteTransition(label = "t")
    val alpha by t.animateFloat(0.45f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a")
    val bob by t.animateFloat(0f, -3f, infiniteRepeatable(tween(520, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "b")
    Row(verticalAlignment = Alignment.CenterVertically) {
        AgentFace(persona.color, persona.face, 22.dp, Modifier.offset(y = bob.dp).alpha(alpha)); Spacer(Modifier.width(8.dp))
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
    cm.setPrimaryClip(android.content.ClipData.newPlainText("Fluwy Bot", text))
    android.widget.Toast.makeText(ctx, "Copiado", android.widget.Toast.LENGTH_SHORT).show()
}

/** Compartilha SOMENTE o texto da resposta do agente (Markdown), sem anexar arquivos. */
fun shareText(ctx: android.content.Context, text: String) {
    val i = android.content.Intent(android.content.Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(android.content.Intent.EXTRA_TEXT, text)
    ctx.startActivity(android.content.Intent.createChooser(i, "Compartilhar resposta"))
}

/**
 * Card "Computador do agente" acima do composer (como no Cue). Mostra também as tarefas do plano,
 * sempre atualizadas (o agente marca cada fase com complete_step).
 */
@Composable
private fun LiveComputerCard(a: Activity, name: String, plan: ChatItem.Plan?, active: Boolean, onClick: () -> Unit) {
    val c = Cue.c
    var expanded by remember { mutableStateOf(false) }
    val label = when (a.kind) {
        Activity.Kind.TERMINAL -> "Usando Terminal"; Activity.Kind.BROWSER -> "Usando Browser"
        Activity.Kind.EDITOR -> "Usando Editor"; Activity.Kind.SEARCH -> "Pesquisando"
        Activity.Kind.IDLE -> if (active) "Pensando" else "Pausado"; else -> "Pensando"
    }
    val icon = when (a.kind) { Activity.Kind.TERMINAL -> Icons.Rounded.Terminal; Activity.Kind.BROWSER -> Icons.Rounded.Language; Activity.Kind.EDITOR -> Icons.Rounded.EditNote; else -> Icons.Rounded.Computer }
    Column(Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(18.dp))) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 56.dp, height = 40.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF0F1115)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = Color(0xFFFF8A63), modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Computador de $name", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text("$label${if (a.detail.isNotBlank()) " · " + a.detail else ""}", fontSize = 12.sp, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Rounded.OpenInFull, null, tint = c.ink3, modifier = Modifier.size(18.dp))
        }
        if (plan != null && plan.phases.isNotEmpty()) {
            val done = plan.phases.count { it.done }
            val current = plan.phases.indexOfFirst { !it.done }
            HorizontalDivider(color = c.line)
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Checklist, null, Modifier.size(18.dp), tint = c.accent); Spacer(Modifier.width(8.dp))
                Text(if (current >= 0) plan.phases[current].title else "Tudo concluído", Modifier.weight(1f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("$done/${plan.phases.size}", fontSize = 12.sp, color = c.ink3)
                Icon(if (expanded) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess, null, tint = c.ink3)
            }
            LinearProgressIndicator(progress = { done / plan.phases.size.toFloat() }, Modifier.fillMaxWidth().height(3.dp), color = c.accent, trackColor = c.surface2)
            AnimatedVisibility(expanded) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    plan.phases.forEachIndexed { i, ph ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(when { ph.done -> Icons.Rounded.CheckCircle; i == current -> Icons.Rounded.RadioButtonChecked; else -> Icons.Rounded.RadioButtonUnchecked },
                                null, Modifier.size(16.dp), tint = when { ph.done -> c.ok; i == current -> c.accent; else -> c.ink3 })
                            Spacer(Modifier.width(8.dp))
                            Text(ph.title, fontSize = 13.sp, color = if (ph.done) c.ink3 else c.ink)
                        }
                    }
                }
            }
        }
    }
}

/** "Pílula" de mídia no chat: miniatura de imagem/SVG ou cartão do arquivo; toque abre o visualizador. */
@Composable
private fun MediaCard(m: ChatItem.Media, onOpen: (String) -> Unit) {
    val c = Cue.c
    val f = remember(m.path) { java.io.File(m.path) }
    val ext = f.extension.lowercase()
    val thumb by produceState<android.graphics.Bitmap?>(null, m.path) { value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { loadThumb(f, 900) } }
    Column(Modifier.widthIn(max = 340.dp).clip(RoundedCornerShape(18.dp)).border(1.dp, c.line, RoundedCornerShape(18.dp)).background(c.surface).clickable { onOpen(m.path) }) {
        val t = thumb
        if (t != null) androidx.compose.foundation.Image(t.asImageBitmap(), m.caption, Modifier.fillMaxWidth().heightIn(max = 260.dp).background(if (ext == "svg") Color.White else c.surface2),
            contentScale = androidx.compose.ui.layout.ContentScale.Fit)
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(fileIcon(f), null, Modifier.size(18.dp), tint = c.accent); Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(m.caption.ifBlank { f.name }, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${f.name} · ${if (f.exists()) humanSize(f.length()) else "removido"}", fontSize = 11.sp, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Rounded.OpenInFull, null, Modifier.size(16.dp), tint = c.ink3)
        }
    }
}

fun loadThumb(f: java.io.File, max: Int): android.graphics.Bitmap? = runCatching {
    when (f.extension.lowercase()) {
        "png", "jpg", "jpeg", "webp", "gif", "bmp" -> {
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(f.absolutePath, o)
            var s = 1; while (o.outWidth / s > max || o.outHeight / s > max) s *= 2
            android.graphics.BitmapFactory.decodeFile(f.absolutePath, android.graphics.BitmapFactory.Options().apply { inSampleSize = s })
        }
        "svg" -> {
            val svg = com.caverock.androidsvg.SVG.getFromString(f.readText())
            val w = svg.documentWidth.takeIf { it > 0 } ?: 512f; val h = svg.documentHeight.takeIf { it > 0 } ?: 512f
            val k = max / maxOf(w, h)
            svg.setDocumentWidth("100%"); svg.setDocumentHeight("100%")
            android.graphics.Bitmap.createBitmap((w * k).toInt().coerceAtLeast(1), (h * k).toInt().coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888).also {
                svg.renderToCanvas(android.graphics.Canvas(it), android.graphics.RectF(0f, 0f, it.width.toFloat(), it.height.toFloat()))
            }
        }
        else -> null
    }
}.getOrNull()

@Composable
private fun MediaDialog(f: java.io.File, onClose: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Cue.c.bg)) { FileViewer(f, onClose = onClose, onEdit = onClose) }
    }
}

fun sessionMarkdown(s: Session): String = buildString {
    appendLine("# ${s.title}\n")
    s.items.forEach { i ->
        when (i) {
            is ChatItem.User -> {
                i.replyTo?.let { appendLine("> Mensagem do agente citada:\n> ${it.lines().joinToString("\n> ")}\n") }
                appendLine("**Você:** ${i.text}\n")
            }
            is ChatItem.Assistant -> appendLine("**Fluwy Bot:** ${i.text}\n")
            is ChatItem.Step -> appendLine("> ${i.title}: `${i.args}`")
            is ChatItem.Plan -> { appendLine("**Plano:** ${i.goal}"); i.phases.forEach { appendLine("- [${if (it.done) "x" else " "}] ${it.title}") }; appendLine() }
            is ChatItem.Error -> appendLine("⚠️ ${i.text}\n")
            is ChatItem.ConnectorProposal -> appendLine("🧩 Conector ${i.name} (${i.host})${if (i.installed) " — instalado" else ""}\n")
            is ChatItem.ConnectorEditProposal -> appendLine("⚙️ Atualização de ${i.connectorName}: ${i.summary}${if (i.applied) " — aplicada" else " — pendente"}\n")
            is ChatItem.Question -> { i.questions.forEachIndexed { n, q -> appendLine("❓ ${q.question} → ${i.answers.getOrNull(n)?.joinToString().orEmpty().ifBlank { "—" }}") }; appendLine() }
            is ChatItem.SecureForm -> appendLine("🔒 ${i.title}: ${if (i.state == "submitted") "enviado com segurança (" + i.submitted.joinToString() + ")" else i.state}\n")
            is ChatItem.Media -> appendLine("🖼 ${i.caption} (${java.io.File(i.path).name})\n")
        }
    }
    appendLine("\n— gerado com Fluwy Bot")
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

/** Card de instalação de um UI Connector que o agente criou e validou. */
@Composable
private fun ConnectorProposalCard(p: ChatItem.ConnectorProposal, sessionId: String?, nav: NavController?) {
    val c = Cue.c
    val app = CueApp.instance
    val scope = rememberCoroutineScope()
    var busy by remember(p.id) { mutableStateOf(false) }
    var error by remember(p.id) { mutableStateOf<String?>(null) }
    val exists = remember(p.id, p.installed) { app.uiConnectors.isCustom(p.connectorId) }
    val validationValid = remember(p.id, p.yaml, p.validation) {
        dev.cueopen.app.uiconnector.custom.ConnectorAttestationPolicy.isInstallable(p.yaml, p.validation)
    }
    // Capacidades são autorizadas somente no momento da instalação (o usuário pode desmarcar).
    var grantCaps by remember(p.id) { mutableStateOf(true) }
    val secretsReady = sessionId != null && p.loginSecrets.isNotEmpty() &&
        p.loginSecrets.values.all { v -> dev.cueopen.app.agent.SecretVault.missing(sessionId, v).isEmpty() }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface)
            .border(1.dp, if (p.installed) c.ok else c.accent, RoundedCornerShape(18.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (p.installed) Icons.Rounded.CheckCircle else Icons.Rounded.Extension, null, tint = if (p.installed) c.ok else c.accent)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(p.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text(if (p.installed) "Conector instalado" else if (validationValid) "Dry-run offline aprovado · pronto para instalar" else "Validação ausente ou desatualizada", fontSize = 12.sp, color = if (p.installed) c.ok else c.ink2)
            }
        }
        Text(p.description, fontSize = 13.sp, color = c.ink2)
        Text("Host: ${p.host}", fontSize = 12.sp, color = c.ink3)
        if (p.tools.isNotEmpty()) Text("Ferramentas do agente: " + p.tools.joinToString(), fontSize = 12.sp, color = c.ink3)
        if (!p.installed && p.capabilities.isNotEmpty()) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { grantCaps = !grantCaps }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(grantCaps, { grantCaps = it })
            Text("Autorizar " + p.capabilities.joinToString { dev.cueopen.app.uiconnector.custom.Capabilities.label(it) } + " (a execução real começa após sua autorização)", fontSize = 12.sp, color = c.ink2)
        }
        if (!p.installed && secretsReady) Text("🔒 Entra com as credenciais que você enviou no formulário seguro (testadas).", fontSize = 12.sp, color = c.ink2)
        error?.let { Text(it, fontSize = 12.sp, color = c.err) }
        if (p.installed) {
            Text("Pronto: ative-o no agente em Agents → editar → UI Conectores.", fontSize = 12.sp, color = c.ink2)
        } else if (!validationValid) {
            Text("Instalação bloqueada. Volte ao criador e rode connector_test novamente para esta definição YAML.", fontSize = 12.sp, color = c.err)
        } else {
            Button(
                onClick = {
                    scope.launch {
                        busy = true; error = null
                        if (!dev.cueopen.app.uiconnector.custom.ConnectorAttestationPolicy.isInstallable(p.yaml, p.validation)) {
                            error = "A validação não corresponde mais a esta definição. Volte ao criador e teste novamente."
                            busy = false
                            return@launch
                        }
                        app.uiConnectors.saveCustom(p.yaml)
                            .onSuccess {
                                if (grantCaps) p.capabilities.forEach { cap -> app.uiConnectors.setCapabilityGranted(p.connectorId, cap, true) }
                                if (secretsReady && sessionId != null) {
                                    val creds = p.loginSecrets.mapValues { dev.cueopen.app.agent.SecretVault.resolve(sessionId, it.value) }
                                    app.uiConnectors.login(p.connectorId, creds, remember = true)
                                        .onFailure { e -> error = "Instalado, mas o login automático falhou: ${e.message}. Entre manualmente em Conectores." }
                                }
                                if (sessionId != null) app.store.replaceItem(sessionId, p.copy(installed = true))
                            }
                            .onFailure { error = it.message ?: "Falha ao instalar." }
                        busy = false
                    }
                },
                enabled = !busy, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(14.dp),
            ) { Text(if (exists) "Atualizar conector" else "Instalar conector", fontWeight = FontWeight.SemiBold) }
            Text("Dry-run validou o contrato com mocks locais; API/login reais e comandos não foram executados. O agente só usa o conector se você o ativar.", fontSize = 11.sp, color = c.ink3)
        }
    }
}

/** Card de revisão e aplicação explícita para configurações de um único conector. */
@Composable
private fun ConnectorEditProposalCard(p: ChatItem.ConnectorEditProposal, sessionId: String?) {
    val c = Cue.c
    val app = CueApp.instance
    val scope = rememberCoroutineScope()
    var busy by remember(p.id) { mutableStateOf(false) }
    var error by remember(p.id) { mutableStateOf<String?>(null) }
    var showDefinition by remember(p.id) { mutableStateOf(false) }
    val current = app.uiConnectors.editableConfiguration(p.connectorId)
    val settingById = current?.settings.orEmpty().associateBy { it.id }
    val risky = p.settings.keys.any { settingById[it]?.risky == true }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface)
            .border(1.dp, if (p.applied) c.ok else c.accent, RoundedCornerShape(18.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (p.applied) Icons.Rounded.CheckCircle else Icons.Rounded.Tune, null, tint = if (p.applied) c.ok else c.accent)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(p.connectorName, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text(if (p.applied) "Atualização aplicada" else "Revisar atualização", fontSize = 12.sp, color = if (p.applied) c.ok else c.ink2)
            }
        }
        Text(p.summary, fontSize = 13.sp, color = c.ink2)
        if (p.enabled != null) Text("Habilitado: ${if (p.enabled) "ligado" else "desligado"}", fontSize = 13.sp)
        if (p.agentRequired != null) Text("Agent required?: ${if (p.agentRequired) "ligado" else "desligado"}", fontSize = 13.sp)
        p.settings.forEach { (id, value) ->
            val label = settingById[id]?.label ?: id
            Text("$label → $value", fontSize = 13.sp, color = c.ink)
        }
        if (p.definitionYaml != null) {
            Text("Definição funcional", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            TextButton(onClick = { showDefinition = !showDefinition }, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp)) {
                Text(if (showDefinition) "Ocultar YAML sanitizado" else "Revisar YAML sanitizado")
            }
            if (showDefinition) SelectionContainer {
                Text(
                    p.definitionYaml,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = c.ink2,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState()).background(c.bg, RoundedCornerShape(10.dp)).padding(10.dp),
                )
            }
            if (!p.applied) Text(
                "A aprovação grava a definição, mas não executa ações. Mudanças em ferramentas ou ações podem fazer chamadas externas quando forem usadas depois; credenciais e permissões ficam preservadas.",
                fontSize = 12.sp, color = c.warn,
            )
        }
        if (risky && !p.applied) Text("Atenção: esta proposta altera uma configuração marcada como sensível. Revise antes de aplicar.", fontSize = 12.sp, color = c.warn)
        error?.let { Text(it, fontSize = 12.sp, color = c.err) }
        if (!p.applied) {
            Button(
                onClick = {
                    scope.launch {
                        busy = true
                        error = null
                        val result = app.uiConnectors.applyEditableConfiguration(
                            p.connectorId, p.baseFingerprint, p.enabled, p.agentRequired, p.settings,
                            definitionYaml = p.definitionYaml,
                            sessionId = sessionId,
                        )
                        result.onSuccess {
                            if (sessionId != null) {
                                app.store.replaceItem(sessionId, p.copy(applied = true))
                                val nextFingerprint = app.uiConnectors.configurationFingerprint(p.connectorId)
                                app.store.editSession(sessionId) { session ->
                                    if (session.connectorEditId == p.connectorId) session.copy(connectorEditFingerprint = nextFingerprint) else session
                                }
                            }
                        }.onFailure { error = it.message ?: "Não foi possível aplicar a atualização." }
                        busy = false
                    }
                },
                enabled = !busy && current != null,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = c.bg)
                else Text("Atualizar conector", fontWeight = FontWeight.SemiBold)
            }
            Text("A configuração só muda depois deste toque. Nenhuma rede externa, login ou ação real é executada; validações de definição usam mocks locais.", fontSize = 11.sp, color = c.ink3)
        }
    }
}
