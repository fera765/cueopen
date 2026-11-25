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
fun ChatScreen(nav: NavController, sessionId: String, onMenu: () -> Unit = {}) {
    val app = CueApp.instance
    val st by app.store.state.collectAsStateWithLifecycle()
    val session = st.sessions.firstOrNull { it.id == sessionId } ?: run { LaunchedEffect(Unit) { nav.popBackStack() }; return }
    // Abriu a conversa: os avisos dela contam como vistos e saem da bandeja.
    LaunchedEffect(sessionId) { dev.cueopen.app.notify.NotificationCenter.markRead(sessionId) }
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
    // Chat principal de uma seção: "rodando" enquanto o coordenador OU qualquer agente do time trabalha.
    val homeSection = st.sections.firstOrNull { it.sessionId == sessionId }
    val sectionRunning = homeSection != null && st.sessions.any { it.sectionId == homeSection.id && it.status == SessionStatus.RUNNING }
    val list = rememberLazyListState()
    var draft by remember { mutableStateOf("") }
    var copyingAttachments by remember { mutableStateOf(false) }
    var replyingTo by remember(sessionId) { mutableStateOf<ChatItem.Assistant?>(null) }
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var usage by remember { mutableStateOf(false) }
    var runsOpen by remember { mutableStateOf(false) }
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
    val visibleItems = remember(session.items, session.voiceReplies) {
        // Resposta em áudio: o texto já vai dentro do card do áudio, então a bolha de texto some (e some já durante a geração).
        val all = session.items
        val voiced = all.filterIsInstance<ChatItem.Audio>().map { it.sourceItemId }.filter { it.isNotEmpty() }.toSet()
        val generating = all.withIndex().filter { (_, it) -> it is ChatItem.Step && it.tool == "reply_audio" && it.status == StepStatus.RUNNING }.map { it.index }
        AskTools.visibleTimelineItems(all.filterIndexed { i, it ->
            !(it is ChatItem.Assistant && (it.id in voiced || (session.voiceReplies && it.final && generating.any { g -> g > i })))
        })
    }
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
            navigationIcon = { IconButton({ onMenu() }) { SidebarIcon() } },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (agent != null) { AnimatedAgentFace(Color(agent.color), agent.face, 24.dp, state = when { running -> dev.cueopen.app.ui.mascot.MascotState.THINKING; session.status == SessionStatus.SLEEPING -> dev.cueopen.app.ui.mascot.MascotState.SLEEPING; else -> dev.cueopen.app.ui.mascot.MascotState.IDLE }); Spacer(Modifier.width(8.dp)) }
                    Text(session.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                }
            },
            actions = {
                IconButton({ nav.navigate("computer/$sessionId") }) { Icon(Icons.Rounded.Computer, "Computador") }
                IconButton({ menu = true }) { Icon(Icons.Rounded.MoreHoriz, "Mais") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("Compartilhar") }, { menu = false; shareSession(ctx, session) }, leadingIcon = { Icon(Icons.Rounded.IosShare, null) })
                    DropdownMenuItem({ Text("Replay") }, { menu = false; nav.navigate("replay/$sessionId") }, leadingIcon = { Icon(Icons.Rounded.PlayCircle, null) })
                    DropdownMenuItem({ Text("Pages desta conversa") }, { menu = false; nav.navigate("pages?space=" + (app.store.section(app.store.session(sessionId)?.sectionId)?.sessionId ?: sessionId)) }, leadingIcon = { Icon(Icons.Rounded.Article, null) })
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
                    DropdownMenuItem({ Text("Execuções") }, { menu = false; runsOpen = true }, leadingIcon = { Icon(Icons.Rounded.Timeline, null) })
                    DropdownMenuItem({ Text("Conectores") }, { menu = false; connectorsDialog = true }, leadingIcon = { Icon(Icons.Rounded.SettingsEthernet, null) })
                    DropdownMenuItem({ Text(if (session.voiceReplies) "Respostas em áudio: ligado" else "Respostas em áudio") }, {
                        menu = false; app.store.editSession(sessionId) { it.copy(voiceReplies = !it.voiceReplies) }
                    }, leadingIcon = { Icon(if (session.voiceReplies) Icons.Rounded.VolumeUp else Icons.Rounded.VolumeOff, null) })
                    DropdownMenuItem({ Text("Ouvir última resposta") }, { menu = false; app.agent.speakLastAnswer(sessionId) }, leadingIcon = { Icon(Icons.Rounded.RecordVoiceOver, null) })
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
          run {
          Box(Modifier.weight(1f).fillMaxWidth()) {
            val openFile: (String) -> Unit = { p ->
                val f = app.sandbox.hostFile(p.trim().removePrefix("file://"), sessionId)
                if (f.isFile) mediaOpen = f.absolutePath
                else android.widget.Toast.makeText(ctx, "Arquivo não encontrado: ${p.substringAfterLast('/')}", android.widget.Toast.LENGTH_SHORT).show()
            }
            androidx.compose.runtime.CompositionLocalProvider(LocalChatPersona provides ChatPersona.of(agent), LocalOpenFile provides openFile) {
            LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(visibleItems, key = { it.id }) { item ->
                    TimelineItem(item, onOpenComputer = { tab -> nav.navigate("computer/$sessionId?tab=$tab") }, onOpenMedia = { mediaOpen = it }, sessionId = sessionId, nav = nav,
                        onSend = { msg -> app.agent.sendOrQueue(sessionId, msg) },
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
            // Chat da seção (coordenador): mostra o time trabalhando ao vivo no lugar do "computador".
            val teamSection = app.store.section(session.sectionId)?.takeIf { it.sessionId == sessionId }
            if (teamSection != null) TeamLiveStrip(teamSection, nav)
            else AnimatedVisibility(running || act.kind != Activity.Kind.IDLE || (plan != null && plan.phases.any { !it.done })) {
                LiveComputerCard(act, agent?.name ?: "Fluwy Bot", plan, active = running) { nav.navigate("computer/$sessionId") }
            }
            mediaOpen?.let { p -> MediaDialog(java.io.File(p)) { mediaOpen = null } }
            run {
                val queues by app.agent.messageQueue.collectAsStateWithLifecycle()
                val pausedQueues by app.agent.pausedQueues.collectAsStateWithLifecycle()
                val queued = queues[sessionId].orEmpty()
                val queuePaused = sessionId in pausedQueues
                if (queued.isNotEmpty()) Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(14.dp)).background(c.surface).padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Fila · ${queued.size} mensagem(ns)" + (if (queuePaused) " · pausada" else " · entrega no próximo passo"),
                            fontSize = 12.sp, color = c.ink3, modifier = Modifier.weight(1f))
                        TextButton({ app.agent.setQueuePaused(sessionId, !queuePaused) }) { Text(if (queuePaused) "Retomar" else "Pausar", fontSize = 12.sp, color = c.accent) }
                    }
                    queued.forEach { q ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(q.text, fontSize = 13.sp, color = c.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            IconButton({ app.agent.cancelQueued(sessionId, q.id) }, Modifier.size(32.dp)) { Icon(Icons.Rounded.Close, "Cancelar mensagem", Modifier.size(16.dp), tint = c.ink3) }
                        }
                    }
                }
            }
            Composer(
                value = draft, onChange = { draft = it },
                placeholder = if (session.status == SessionStatus.WAITING_USER) "Responda ao agente…" else "Envie uma mensagem",
                running = running || sectionRunning || session.status == SessionStatus.SLEEPING || autoAgent != null,
                onStop = {
                    // Chat da seção: pausa em cascata (coordenador + todos os agentes). Senão, no meio de um passo só interrompe.
                    if (homeSection != null) app.agent.pauseSection(homeSection.id) else app.agent.stop(sessionId, pauseAutonomy = !running)
                },
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
                                kotlinx.coroutines.delay(300)
                                // Reinício do zero: contexto, histórico, workspace, terminal e dados seguros.
                                app.agent.resetSession(sessionId)
                                app.store.addItem(sessionId, step.copy(title = "Sessão limpa do zero", args = "contexto, histórico e workspace apagados", status = StepStatus.OK))
                            }
                            "/stop" -> if (homeSection != null) app.agent.pauseSection(homeSection.id) else app.agent.stop(sessionId, pauseAutonomy = true)
                            "/undo" -> app.store.addItem(sessionId, ChatItem.Assistant(text = app.agent.undoLastFileChange(sessionId), final = false))
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
                        // Agente trabalhando: a mensagem entra na fila e é entregue no próximo passo, sem derrubar o que está em andamento.
                        app.agent.sendOrQueue(sessionId, text, quotedReply = reply)
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
                                // Agente ocupado: anexos também entram na fila (antes derrubavam o trabalho em andamento).
                                app.agent.sendOrQueue(sessionId, text, quotedReply = reply, attachments = names)
                            } finally {
                                copyingAttachments = false
                            }
                        }
                    }
                },
            )
          }
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
    if (runsOpen) ExecutionsDialog(sessionId) { runsOpen = false }
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
        is ChatItem.Step -> when (item.tool) {
            dev.cueopen.app.agent.TEAM_MEMBER_STEP -> TeamMemberPill(item, nav)
            dev.cueopen.app.agent.BuildSquad.STEP -> BuildSquadCard(item, sessionId, nav)
            "team_delegate", "team_status" -> {}  // a pílula do agente já representa a delegação
            "reply_audio" -> if (item.status == StepStatus.RUNNING) GeneratingAudioRow() // no fim aparece só o áudio
            "page" -> PageStepCard(item, nav)
            "login_needed" -> LoginNeededCard(item, sessionId, nav)
            else -> StepRow(item, onOpenComputer)
        }
        is ChatItem.Plan -> {}  // o plano fica no card do computador, acima do composer
        is ChatItem.Media -> MediaCard(item, onOpenMedia)
        is ChatItem.Cards -> ResultCards(item, sessionId, onSend)
        is ChatItem.Audio -> AudioMessage(item)
        is ChatItem.PixQr -> PixQrMessage(item)
        is ChatItem.Error -> InfoRow(Icons.Rounded.ErrorOutline, item.text, Cue.c.err)
        is ChatItem.ConnectorProposal -> ConnectorProposalCard(item, sessionId, nav)
        is ChatItem.UserScriptProposal -> UserScriptProposalCard(item, sessionId, nav)
        is ChatItem.LlmRecovery -> LlmRecoveryCard(item, sessionId, nav)
        is ChatItem.ConnectorEditProposal -> ConnectorEditProposalCard(item, sessionId)
        is ChatItem.Question -> QuestionCard(item, sessionId)
        is ChatItem.SecureForm -> SecureFormCard(item, sessionId)
        is ChatItem.Approval -> ApprovalCard(item, sessionId)
        is ChatItem.SectionSetup -> SectionSetupCard(item, sessionId, nav)
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
            // Menções (@skill, @conector, @agent) destacadas também na mensagem enviada.
            val targets = remember(i.text) { if ('@' in i.text) runCatching { dev.cueopen.app.CueApp.instance.agent.mentionTargets() }.getOrDefault(emptyList()) else emptyList() }
            val shown = remember(i.text, targets) {
                androidx.compose.ui.text.buildAnnotatedString {
                    append(i.text)
                    dev.cueopen.app.agent.Mentions.find(i.text, targets).forEach { h ->
                        addStyle(androidx.compose.ui.text.SpanStyle(color = Color.White, background = Color(0xFF246BCE), fontWeight = FontWeight.SemiBold), h.start, h.end)
                    }
                }
            }
            // Mensagem longa: mostra o começo e um "Ver mais"/"Ver menos" dentro do próprio card.
            val long = remember(i.text) { i.text.length > 700 || i.text.count { it == '\n' } > 12 }
            var expanded by remember(i.id) { mutableStateOf(false) }
            Column(Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)).background(c.bubble).padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(shown, style = MaterialTheme.typography.bodyLarge,
                    maxLines = if (long && !expanded) 10 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
                if (long) Text(if (expanded) "Ver menos" else "Ver mais", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.accent,
                    modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(8.dp)).clickable { expanded = !expanded }.padding(vertical = 2.dp))
            }
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
        AnimatedAgentFace(persona.color, persona.face, 22.dp, Modifier.offset(y = bob.dp).alpha(alpha), state = dev.cueopen.app.ui.mascot.MascotState.THINKING); Spacer(Modifier.width(8.dp))
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
            is ChatItem.LlmRecovery -> appendLine("⚠ Falha do modelo ${i.model} (${i.category}) — ${i.state}\n")
            is ChatItem.UserScriptProposal -> appendLine("📜 User Script ${i.name} ${i.version}${if (i.installed) " — instalado" else ""}\n")
            is ChatItem.ConnectorProposal -> appendLine("🧩 Conector ${i.name} (${i.host})${if (i.installed) " — instalado" else ""}\n")
            is ChatItem.ConnectorEditProposal -> appendLine("⚙️ Atualização de ${i.connectorName}: ${i.summary}${if (i.applied) " — aplicada" else " — pendente"}\n")
            is ChatItem.Question -> { i.questions.forEachIndexed { n, q -> appendLine("❓ ${q.question} → ${i.answers.getOrNull(n)?.joinToString().orEmpty().ifBlank { "—" }}") }; appendLine() }
            is ChatItem.SecureForm -> appendLine("🔒 ${i.title}: ${if (i.state == "submitted") "enviado com segurança (" + i.submitted.joinToString() + ")" else i.state}\n")
            is ChatItem.Media -> appendLine("🖼 ${i.caption} (${java.io.File(i.path).name})\n")
            is ChatItem.Cards -> { appendLine("🗂 ${i.title.ifBlank { i.kind }}"); i.items.forEachIndexed { n, e -> appendLine("${n + 1}. ${e.title}${if (e.price.isNotBlank()) " — " + e.price else ""}${if (e.url.isNotBlank()) " — " + e.url else ""}${if (n == i.selected) " ✅" else ""}") }; appendLine() }
            is ChatItem.Audio -> appendLine("🔊 ${i.text.take(300)}\n")
            // Exportação nunca leva o código Pix (contém a chave): só o resumo.
            is ChatItem.PixQr -> appendLine("💠 Pix · ${i.keyLabel} · ${if (i.cents > 0) dev.cueopen.app.vault.PixRules.money(i.cents) else "valor livre"}\n")
            is ChatItem.Approval -> appendLine("🛡 Confirmação: ${i.summary} — ${i.state}\n")
            is ChatItem.SectionSetup -> appendLine("📁 Seção ${i.sectionName} (${i.agentNames.joinToString()}) — ${i.state}\n")
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

/** Falha do provedor de modelos: o que houve, o que o app vai fazer e ações que funcionam de verdade. */
@Composable
private fun LlmRecoveryCard(p: ChatItem.LlmRecovery, sessionId: String?, nav: NavController?) {
    val c = Cue.c
    val app = CueApp.instance
    val fmt = remember { java.text.SimpleDateFormat("HH:mm", java.util.Locale.ROOT) }
    if (p.state != "waiting") {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface2).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text((if (p.state == "resolved") "✓ Modelo voltou a responder" else "Execução cancelada") + " · ${p.category} às ${fmt.format(java.util.Date(p.time))}", fontSize = 12.sp, color = c.ink2)
        }
        return
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(20.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("⚠ " + p.category.replaceFirstChar { it.uppercase() }, fontWeight = FontWeight.SemiBold, color = c.ink, fontSize = 16.sp)
        Text("Provedor: ${p.provider}\nModelo: ${p.model}\nFalha às ${fmt.format(java.util.Date(p.time))} · ${p.attempts} tentativa(s)" +
            (if (p.round > 0) " · retomada ${p.round}/${dev.cueopen.app.llm.LlmRecovery.AUTO_RESUME_ROUNDS}" else ""), fontSize = 12.sp, color = c.ink2, lineHeight = 17.sp)
        Text(p.advice, fontSize = 13.sp, color = c.ink)
        if (p.detail.isNotBlank()) {
            var showDetail by remember(p.time) { mutableStateOf(false) }
            Text(if (showDetail) "Ocultar detalhes técnicos" else "Detalhes técnicos", fontSize = 12.sp, color = c.ink3,
                modifier = Modifier.clickable { showDetail = !showDetail })
            if (showDetail) Text(p.detail, fontSize = 12.sp, color = c.ink3, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        }
        Text(when {
            p.resumeAt != null -> "Estado: suspenso. O app tenta de novo sozinho às ${fmt.format(java.util.Date(p.resumeAt))} e continua de onde parou."
            p.permanent -> "Estado: aguardando você. Este erro não se resolve sozinho."
            else -> "Estado: aguardando você. As retomadas automáticas se esgotaram."
        }, fontSize = 12.sp, color = if (p.resumeAt != null) c.ok else c.accent)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton({ sessionId?.let { app.agent.retryAfterLlmFailure(it) } }) { Text("Tentar agora") }
            TextButton({ nav?.navigate("settings") }) { Text("Trocar modelo/provedor") }
            TextButton({ sessionId?.let { app.agent.cancelLlmRecovery(it) } }) { Text("Cancelar", color = c.accent) }
        }
    }
}

/** Card "Instalar no Browser" de um userscript criado e testado pela IA. */
@Composable
private fun UserScriptProposalCard(p: ChatItem.UserScriptProposal, sessionId: String?, nav: NavController?) {
    val c = Cue.c
    val app = CueApp.instance
    val scope = rememberCoroutineScope()
    var busy by remember(p.id) { mutableStateOf(false) }
    var error by remember(p.id) { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(20.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("📜 " + p.name + if (p.version.isNotBlank()) " · v" + p.version else "", fontWeight = FontWeight.SemiBold, color = c.ink, fontSize = 16.sp)
        if (p.description.isNotBlank()) Text(p.description, fontSize = 13.sp, color = c.ink2)
        Text("Roda em: " + p.where, fontSize = 12.sp, color = c.ink3)
        error?.let { Text(it, fontSize = 12.sp, color = c.accent) }
        if (p.installed) Row(verticalAlignment = Alignment.CenterVertically) {
            Text("✓ Instalado no Browser", color = c.ok, fontSize = 13.sp, modifier = Modifier.weight(1f))
            TextButton({ nav?.navigate("browserhub") }) { Text("Abrir Browser") }
        } else Button(enabled = !busy && sessionId != null, onClick = {
            busy = true
            scope.launch {
                val r = dev.cueopen.app.browser.UserScriptInstaller.install(p.code, "ia")
                busy = false
                if (r.script == null) error = r.error
                else sessionId?.let { sid -> app.store.replaceItem(sid, p.copy(installed = true, scriptId = r.script.id)) }
            }
        }) { Text(if (busy) "Instalando…" else "Instalar no Browser") }
    }
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


/** Card de confirmação de ação sensível (comprar/pagar, apagar, enviar/publicar, transferir, conta). */
@Composable
private fun ApprovalCard(item: ChatItem.Approval, sessionId: String?) {
    val c = Cue.c
    val app = dev.cueopen.app.CueApp.instance
    val cat = dev.cueopen.app.agent.ActionGate.Category.of(item.category)
    Column(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(16.dp))
            .background(c.surface).border(1.dp, if (item.state == "pending") c.warn else c.line, RoundedCornerShape(16.dp)).padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Shield, null, tint = if (item.state == "pending") c.warn else c.ink3, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(cat?.question ?: "Confirmar ação?", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
        Spacer(Modifier.height(6.dp))
        Text(item.summary, fontSize = 14.sp, color = c.ink2)
        Spacer(Modifier.height(10.dp))
        when (item.state) {
            "pending" -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ sessionId?.let { app.agent.decideApproval(it, item, approve = false) } }, Modifier.weight(1f)) { Text("Negar") }
                Button({ sessionId?.let { app.agent.decideApproval(it, item, approve = true) } }, Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = c.ink, contentColor = c.bg)) { Text("Aprovar") }
            }
            "approved", "used" -> Text("✓ Aprovado", fontSize = 12.sp, color = c.ok)
            else -> Text("✕ Negado", fontSize = 12.sp, color = c.err)
        }
    }
}


/**
 * Card de autorização da Seção: o usuário marca o que o time pode usar e ativa.
 * Ao ativar, as permissões valem para todos os Agents da seção e o coordenador começa a trabalhar.
 */
@Composable
private fun SectionSetupCard(item: ChatItem.SectionSetup, sessionId: String?, nav: androidx.navigation.NavController?) {
    val c = Cue.c
    val app = dev.cueopen.app.CueApp.instance
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var browser by remember(item.id) { mutableStateOf(true) }
    var terminal by remember(item.id) { mutableStateOf(true) }
    var wallet by remember(item.id) { mutableStateOf(item.suggestWallet) }
    var caps by remember(item.id) { mutableStateOf(item.suggestedCaps.toSet()) }
    var connectors by remember(item.id) { mutableStateOf(emptySet<String>()) }
    var fullAuto by remember(item.id) { mutableStateOf(item.suggestAuto) }
    val section = app.store.section(item.sectionId)
    Column(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(16.dp))
            .background(c.surface).border(1.dp, if (item.state == "pending") c.accent else c.line, RoundedCornerShape(16.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Folder, null, tint = c.accent, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
            Text("Seção “${item.sectionName}”", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        }
        Text("Time: " + item.agentNames.joinToString(" · "), fontSize = 13.sp, color = c.ink2)
        when (item.state) {
            "pending" -> {
                Text("O que este time pode usar?", fontSize = 13.sp, color = c.ink2, fontWeight = FontWeight.Medium)
                ToggleRow("Browser", "Navegar e agir em sites (usa seus logins salvos)", browser) { browser = it }
                ToggleRow("Terminal Linux", "Rodar comandos e scripts no sandbox", terminal) { terminal = it }
                ToggleRow("Carteira Solana", "Consultar endereço e saldo e pedir transferências (cada uma pede sua aprovação)", wallet) { wallet = it }
                val hub = app.uiConnectors.connectors
                if (hub.isNotEmpty()) {
                    Text("Conectores", fontSize = 13.sp, color = c.ink2, fontWeight = FontWeight.Medium)
                    hub.forEach { uc ->
                        ToggleRow(uc.spec.name, uc.spec.description.take(90), uc.spec.id in connectors) { on ->
                            connectors = if (on) connectors + uc.spec.id else connectors - uc.spec.id
                        }
                    }
                }
                Text("Acesso ao Android", fontSize = 13.sp, color = c.ink2, fontWeight = FontWeight.Medium)
                dev.cueopen.app.device.DeviceCaps.all.forEach { cap ->
                    ToggleRow(cap.title, cap.desc, cap.id in caps) { on -> caps = if (on) caps + cap.id else caps - cap.id }
                }
                ToggleRow("100% autônomo", "Trabalha sozinho até o fim: não faz perguntas nem pede confirmação (inclusive para comprar, pagar, apagar, enviar ou transferir)", fullAuto) { fullAuto = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ sessionId?.let { app.agent.cancelSectionSetup(it, item) } }, Modifier.weight(1f)) { Text("Agora não") }
                    Button({
                        val sid = sessionId ?: return@Button
                        val target = app.agent.activateSection(sid, item, dev.cueopen.app.agent.AgentRunner.SectionPermissions(
                            browser = browser, terminal = terminal, wallet = wallet, uiConnectorIds = connectors.toList(),
                            deviceCaps = caps.toList(), fullAuto = fullAuto,
                        ))
                        if (dev.cueopen.app.device.DeviceCaps.BACKGROUND in caps) dev.cueopen.app.device.DeviceCaps.requestBatteryUnrestricted(ctx)
                        caps.filterNot { dev.cueopen.app.device.DeviceCaps.granted(ctx, it) }.firstOrNull()?.let { dev.cueopen.app.device.DeviceCaps.request(ctx, it) }
                        target?.let { t ->
                            // O chat que só serviu para criar a seção sai de cena: vai direto para a seção rodando.
                            nav?.popBackStack()
                            nav?.navigate("chat/$t")
                            app.deleteChat(sid)
                        }
                    }, Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = c.ink, contentColor = c.bg)) { Text("Ativar seção") }
                }
            }
            "activated" -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("✓ Seção ativada", fontSize = 13.sp, color = c.ok, modifier = Modifier.weight(1f))
                if (section != null) TextButton({ nav?.navigate("chat/${section.sessionId}") }) { Text("Abrir seção", color = c.accent) }
            }
            else -> Text("Ativação adiada. Peça no chat para ativar a seção quando quiser.", fontSize = 12.sp, color = c.ink3)
        }
    }
}


/** Ponto verde pulsando: agente trabalhando agora. */
@Composable
fun PulsingDot(color: Color, size: androidx.compose.ui.unit.Dp = 8.dp) {
    val t = rememberInfiniteTransition(label = "pulse")
    val k by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1100), RepeatMode.Restart), label = "k")
    Box(Modifier.size(size * 2), contentAlignment = Alignment.Center) {
        Box(Modifier.size(size * (1f + k)).clip(CircleShape).background(color.copy(alpha = (1f - k) * .45f)))
        Box(Modifier.size(size).clip(CircleShape).background(color))
    }
}

private fun teamActivityLabel(a: Activity?, running: Boolean, waiting: Boolean, failed: Boolean, done: Boolean): String = when {
    waiting -> "aguardando você"
    running -> when (a?.kind) {
        Activity.Kind.BROWSER -> "usando o browser"
        Activity.Kind.TERMINAL -> "usando o terminal"
        Activity.Kind.EDITOR -> "editando arquivos"
        Activity.Kind.SEARCH -> "pesquisando"
        else -> "pensando"
    } + (a?.detail?.takeIf { it.isNotBlank() }?.let { " · " + it.take(60) } ?: "")
    failed -> "parou com erro"
    done -> "concluiu"
    else -> "na fila"
}

/** Pílula de um agente da seção no chat do coordenador: mostra ao vivo o que ele está fazendo; tocar abre o chat dele. */
/** Exército de agentes: o time inteiro, cada um com mascote, papel e estado; abre o browser ao vivo. */
@Composable
private fun BuildSquadCard(step: ChatItem.Step, sessionId: String?, nav: androidx.navigation.NavController?) {
    val c = Cue.c
    val squads by dev.cueopen.app.agent.BuildSquad.squads.collectAsStateWithLifecycle()
    // App reiniciado: o time salvo volta para o card (estado final de cada agente).
    LaunchedEffect(step.args) {
        if (dev.cueopen.app.agent.BuildSquad.get(step.args) == null)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { dev.cueopen.app.agent.BuildSquad.restore(step.args) }
    }
    val sq = squads[step.args]
    var expanded by remember(step.id) { mutableStateOf(false) }
    Column(
        Modifier.padding(vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface)
            .border(1.dp, if (sq?.running == true) c.ok.copy(alpha = .5f) else c.line, RoundedCornerShape(18.dp)).padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Exército de agentes", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(if (sq == null) step.result.ifBlank { step.title } else if (sq.running && sq.workers.isEmpty()) "Preparando o app antes de o time entrar" else if (sq.running) "Construindo · ${sq.active} trabalhando · ${sq.done}/${sq.workers.size} prontos"
                    else "${sq.done} de ${sq.workers.size} concluíram", fontSize = 12.sp, color = c.ink3)
            }
            if (sq?.running == true) PulsingDot(c.ok)
            else if (step.status == StepStatus.OK) Icon(Icons.Rounded.Check, null, Modifier.size(16.dp), tint = c.ok)
        }
        if (sq != null) {
            Spacer(Modifier.height(8.dp))
            val shown = if (expanded) sq.workers else sq.workers.sortedBy { if (it.status == dev.cueopen.app.agent.BuildSquad.St.RUNNING) 0 else 1 }.take(8)
            shown.forEach { w ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { nav?.navigate("chat/${w.sessionId}") }.padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    val running = w.status == dev.cueopen.app.agent.BuildSquad.St.RUNNING
                    AnimatedAgentFace(Color(w.persona.color), w.persona.face, 20.dp,
                        state = if (running) dev.cueopen.app.ui.mascot.MascotState.THINKING else dev.cueopen.app.ui.mascot.MascotState.IDLE)
                    Spacer(Modifier.width(8.dp))
                    Text(buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Color(w.persona.color))) { append(w.persona.name) }
                        append(" · ${w.persona.role} · ")
                        withStyle(SpanStyle(color = c.ink3)) { append(if (running) w.doing.ifBlank { w.label } else w.label) }
                    }, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    when (w.status) {
                        dev.cueopen.app.agent.BuildSquad.St.RUNNING -> PulsingDot(Color(w.persona.color))
                        dev.cueopen.app.agent.BuildSquad.St.DONE -> Icon(Icons.Rounded.Check, null, Modifier.size(14.dp), tint = c.ok)
                        dev.cueopen.app.agent.BuildSquad.St.FAILED -> Icon(Icons.Rounded.Close, null, Modifier.size(14.dp), tint = c.err)
                        else -> Text("na fila", fontSize = 11.sp, color = c.ink3)
                    }
                }
            }
            if (sq.notice.isNotBlank()) Text(sq.notice, fontSize = 12.sp, color = c.warn, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            if (sq.workers.size > 8) Text(if (expanded) "Mostrar menos" else "Ver os ${sq.workers.size} agentes", fontSize = 12.sp, color = c.accent,
                modifier = Modifier.clickable { expanded = !expanded }.padding(top = 4.dp))
            if (sq.preview != null && sessionId != null) {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.clip(RoundedCornerShape(12.dp)).background(c.accent.copy(alpha = .12f))
                    .clickable { nav?.navigate("computer/$sessionId?tab=browser") }.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Computer, null, Modifier.size(16.dp), tint = c.accent)
                    Spacer(Modifier.width(6.dp))
                    Text(if (sq.running) "Ver os agentes ao vivo no browser" else "Abrir o resultado no browser", fontSize = 13.sp, color = c.accent, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun TeamMemberPill(step: ChatItem.Step, nav: androidx.navigation.NavController?) {
    val c = Cue.c
    val app = dev.cueopen.app.CueApp.instance
    val (childId, memberId) = step.args.split('|').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
    val st by app.store.state.collectAsStateWithLifecycle()
    val activity by app.agent.activity.collectAsStateWithLifecycle()
    val member = st.agents.firstOrNull { it.id == memberId }
    // O agente usa sempre o mesmo chat, então todas as pílulas dele apontam para a mesma sessão. Só a pílula da
    // delegação ATUAL (a mais recente, ainda em andamento) acompanha o estado ao vivo; as anteriores mostram o
    // estado final que tiveram.
    val isLatest = remember(st.sessions, step.id, childId) {
        st.sessions.firstOrNull { s -> s.items.any { it.id == step.id } }?.items
            ?.lastOrNull { it is ChatItem.Step && it.tool == dev.cueopen.app.agent.TEAM_MEMBER_STEP && it.args.substringBefore('|') == childId }?.id == step.id
    }
    val live = step.status == StepStatus.RUNNING && isLatest
    val child = if (live) st.sessions.firstOrNull { it.id == childId } else null
    val running = child?.status == SessionStatus.RUNNING
    val waiting = (live && child?.status == SessionStatus.WAITING_USER) || step.result == "aguardando você"
    val finishedOk = step.status == StepStatus.OK || (step.status == StepStatus.RUNNING && !isLatest)
    val label = if (!running && step.result == "retomando sozinho") "retomando sozinho (falha temporária do modelo)"
        else if (!running && step.result == "pausado") "pausado"
        else teamActivityLabel(if (live) activity[childId] else null, running, waiting && !running, step.status == StepStatus.ERROR, finishedOk)
    Row(
        Modifier.padding(vertical = 4.dp).clip(RoundedCornerShape(18.dp)).background(c.surface)
            .border(1.dp, if (running) c.ok.copy(alpha = .5f) else c.line, RoundedCornerShape(18.dp))
            .clickable(enabled = childId.isNotBlank()) { nav?.navigate("chat/$childId") }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (member != null) AnimatedAgentFace(Color(member.color), member.face, 26.dp,
            state = if (running) dev.cueopen.app.ui.mascot.MascotState.THINKING else dev.cueopen.app.ui.mascot.MascotState.IDLE)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f, fill = false)) {
            Text(buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(member?.name ?: "Agente") }
                append(" · $label")
            }, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (step.title.isNotBlank()) Text(step.title, fontSize = 12.sp, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(6.dp))
        when {
            running -> PulsingDot(c.ok)
            waiting -> Icon(Icons.Rounded.PanTool, null, Modifier.size(14.dp), tint = c.warn)
            step.status == StepStatus.ERROR -> Icon(Icons.Rounded.Close, null, Modifier.size(14.dp), tint = c.err)
            finishedOk -> Icon(Icons.Rounded.Check, null, Modifier.size(14.dp), tint = c.ok)
        }
        Icon(Icons.Rounded.ChevronRight, null, Modifier.size(16.dp), tint = c.ink3)
    }
}

/** Faixa no topo do composer do chat da seção: quem do time está trabalhando agora (toque abre o chat do agente). */
@Composable
private fun TeamLiveStrip(sec: dev.cueopen.app.data.Section, nav: androidx.navigation.NavController) {
    val c = Cue.c
    val app = dev.cueopen.app.CueApp.instance
    val st by app.store.state.collectAsStateWithLifecycle()
    val activity by app.agent.activity.collectAsStateWithLifecycle()
    val busy = sec.agentIds.mapNotNull { id -> st.agents.firstOrNull { it.id == id } }.mapNotNull { a ->
        val chat = st.sessions.firstOrNull { it.sectionId == sec.id && it.agentId == a.id && it.id != sec.sessionId } ?: return@mapNotNull null
        if (chat.status == SessionStatus.RUNNING || chat.status == SessionStatus.WAITING_USER) Triple(a, chat, activity[chat.id]) else null
    }
    if (busy.isEmpty()) return
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        busy.forEach { (a, chat, act) ->
            val running = chat.status == SessionStatus.RUNNING
            Row(Modifier.clip(RoundedCornerShape(16.dp)).background(c.surface).border(1.dp, if (running) c.ok.copy(alpha = .5f) else c.warn.copy(alpha = .5f), RoundedCornerShape(16.dp))
                .clickable { nav.navigate("chat/${chat.id}") }.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                AnimatedAgentFace(Color(a.color), a.face, 20.dp, state = if (running) dev.cueopen.app.ui.mascot.MascotState.THINKING else dev.cueopen.app.ui.mascot.MascotState.IDLE)
                Spacer(Modifier.width(6.dp))
                Text(a.name + " · " + teamActivityLabel(act, running, !running, false, false).substringBefore(" · "), fontSize = 12.sp, maxLines = 1)
                Spacer(Modifier.width(4.dp))
                if (running) PulsingDot(c.ok, 6.dp)
            }
        }
    }
}

/** Cartão no chat para página criada/atualizada (Fluwy Pages): toque abre a página. */
@Composable
private fun PageStepCard(s: ChatItem.Step, nav: androidx.navigation.NavController?) {
    val c = Cue.c
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface).clickable { nav?.navigate("page/${s.args}") }.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Article, null, tint = c.accent); Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(s.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text("Fluwy Pages · " + s.result + " · toque para abrir", fontSize = 12.sp, color = c.ink3)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = c.ink3)
    }
}

/** Pedido de login (como o takeover do Manus): o usuário entra no site, salva a sessão e a tarefa continua. */
@Composable
private fun LoginNeededCard(s: ChatItem.Step, sessionId: String?, nav: androidx.navigation.NavController?) {
    val c = Cue.c
    val loginId = s.args.substringBefore('|')
    val st by dev.cueopen.app.CueApp.instance.store.state.collectAsStateWithLifecycle()
    val done = st.browserLogins.firstOrNull { it.id == loginId }?.status == "active"
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (done) Icons.Rounded.LockOpen else Icons.Rounded.Lock, null, tint = if (done) c.ok else c.accent); Spacer(Modifier.width(8.dp))
            Text(if (done) "Sessão salva" else s.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
        Text(if (done) "O agente já pode entrar neste site." else "Entre com a sua conta no site. A senha fica só no site: o app e a IA não veem.", fontSize = 12.sp, color = c.ink2)
        if (!done) Button({ nav?.navigate("browserlogin/$loginId" + (sessionId?.let { "?session=$it" } ?: "")) },
            colors = ButtonDefaults.buttonColors(containerColor = c.ink, contentColor = c.bg)) { Text("Entrar e salvar") }
    }
}


/** Execuções do orquestrador nesta conversa: tarefa, estado, ferramentas usadas e duração. */
@Composable
private fun ExecutionsDialog(sessionId: String, onDismiss: () -> Unit) {
    val c = Cue.c
    val runs = remember { dev.cueopen.app.agent.ExecutionLedger.history(sessionId).reversed() }
    val fmt = remember { java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Execuções") }, text = {
        androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (runs.isEmpty()) item { Text("Nenhuma execução registrada ainda.", color = c.ink3, fontSize = 13.sp) }
            items(runs.size) { i ->
                val r = runs[i]
                val color = when (r.state) { "DONE" -> c.ok; "ERROR" -> c.err; "RUNNING" -> c.accent; else -> c.ink3 }
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface).padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(r.state, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.width(8.dp))
                        Text(fmt.format(java.util.Date(r.startedAt)) + (r.endedAt?.let { " · ${(it - r.startedAt) / 1000}s" } ?: ""), fontSize = 11.sp, color = c.ink3)
                        Spacer(Modifier.weight(1f)); Text(r.runId.takeLast(6), fontSize = 10.sp, color = c.ink3)
                    }
                    Text(r.goal.take(160), fontSize = 13.sp, maxLines = 3)
                    val reused = r.ops.count { it.reused }; val failed = r.ops.count { !it.ok }
                    Text("${r.ops.size} operação(ões)" + (if (failed > 0) " · $failed com erro" else "") + (if (reused > 0) " · $reused reaproveitada(s)" else "") +
                        " · ${r.allowedTools.size} ferramentas autorizadas", fontSize = 11.sp, color = c.ink2)
                    r.ops.takeLast(6).forEach { op -> Text((if (op.ok) "✓ " else "✗ ") + op.tool + (if (op.reused) " (reaproveitada)" else " · ${op.ms} ms"), fontSize = 11.sp, color = if (op.ok) c.ink3 else c.err) }
                    if (r.result.isNotBlank()) Text("Resultado: " + r.result.take(160), fontSize = 11.sp, color = c.ink2)
                }
            }
        }
    }, confirmButton = { TextButton(onDismiss) { Text("Fechar") } })
}

/** Enquanto a resposta em áudio é gerada (no lugar do card da ferramenta). */
@Composable
private fun GeneratingAudioRow() {
    val c = dev.cueopen.app.ui.theme.Cue.c
    Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = c.accent)
        Spacer(Modifier.width(8.dp))
        Text("Gerando áudio…", fontSize = 13.sp, color = c.ink2)
    }
}
