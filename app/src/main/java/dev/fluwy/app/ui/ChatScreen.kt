package dev.fluwy.app.ui

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
import dev.fluwy.app.FluwyApp
import dev.fluwy.app.agent.Activity
import dev.fluwy.app.agent.AskTools
import dev.fluwy.app.data.ChatItem
import dev.fluwy.app.data.Session
import dev.fluwy.app.data.SessionStatus
import dev.fluwy.app.data.StepStatus
import dev.fluwy.app.ui.theme.Fluwy
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(nav: NavController, sessionId: String, onMenu: () -> Unit = {}) {
    val app = FluwyApp.instance
    val st by app.store.state.collectAsStateWithLifecycle()
    val session = st.sessions.firstOrNull { it.id == sessionId } ?: run { LaunchedEffect(Unit) { nav.popBackStack() }; return }
    // Abriu a conversa: os avisos dela contam como vistos e saem da bandeja.
    LaunchedEffect(sessionId) { dev.fluwy.app.notify.NotificationCenter.markRead(sessionId) }
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
    val c = Fluwy.c

    LaunchedEffect(sessionId) {
        ctx.getSharedPreferences(dev.fluwy.app.assistant.AssistantRoutingPolicy.PREFERENCES, android.content.Context.MODE_PRIVATE)
            .edit()
            .putString(dev.fluwy.app.assistant.AssistantRoutingPolicy.KEY_ACTIVE_SESSION_ID, sessionId)
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
                    if (agent != null) { AnimatedAgentFace(Color(agent.color), agent.face, 24.dp, state = when { running -> dev.fluwy.app.ui.mascot.MascotState.THINKING; session.status == SessionStatus.SLEEPING -> dev.fluwy.app.ui.mascot.MascotState.SLEEPING; else -> dev.fluwy.app.ui.mascot.MascotState.IDLE }); Spacer(Modifier.width(8.dp)) }
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
                        val t = dev.fluwy.app.data.ScheduledTask(title = session.title, prompt = first?.text ?: "", agentId = session.agentId, sourceSessionId = sessionId)
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
            val onOpenComputer: (String) -> Unit = remember(sessionId) { { tab -> nav.navigate("computer/$sessionId?tab=$tab") } }
            val onOpenMedia: (String) -> Unit = remember { { mediaOpen = it } }
            val onSendMsg: (String) -> Unit = remember(sessionId) { { msg -> app.agent.sendOrQueue(sessionId, msg) } }
            val onReplyItem: (ChatItem.Assistant) -> Unit = remember { { replyingTo = it } }
            // "Nova mensagem": marca a última ordem do coordenador ainda não vista; some na próxima visita.
            val lastCoord = remember(visibleItems) { visibleItems.lastOrNull { it is ChatItem.User && it.text.startsWith("[Coordenador]") }?.id }
            var newCoordId by remember(sessionId) { mutableStateOf<String?>(null) }
            LaunchedEffect(sessionId, lastCoord) {
                if (lastCoord == null) return@LaunchedEffect
                val p = ctx.getSharedPreferences("coord_seen", android.content.Context.MODE_PRIVATE)
                if (p.getString(sessionId, null) != lastCoord) { newCoordId = lastCoord; p.edit().putString(sessionId, lastCoord).apply() }
            }
            LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Rolagem leve em chats longos: callbacks estáveis (não recompõem todos os itens a cada token do stream)
                // e contentType por tipo (o LazyColumn reaproveita a composição de itens do mesmo tipo).
                items(visibleItems, key = { it.id }, contentType = { it::class }) { item ->
                    if (item.id == newCoordId) Column { NewMessageDivider()
                        TimelineItem(item, onOpenComputer = onOpenComputer, onOpenMedia = onOpenMedia, sessionId = sessionId, nav = nav, onSend = onSendMsg, onReply = onReplyItem) }
                    else TimelineItem(item, onOpenComputer = onOpenComputer, onOpenMedia = onOpenMedia, sessionId = sessionId, nav = nav,
                        onSend = onSendMsg, onReply = onReplyItem)
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
            // Botão "ir para o fim" some enquanto um card de pergunta está na tela: ficava em cima das opções/botão de enviar.
            val questionOnScreen by remember(visibleItems) {
                derivedStateOf {
                    val idx = visibleItems.indexOfLast { it is ChatItem.Question && it.state == "pending" }
                    idx >= 0 && list.layoutInfo.visibleItemsInfo.any { it.index == idx }
                }
            }
            if (!follow && !questionOnScreen) {
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
                onSend = { rawText, files ->
                    // /skill Nome … e /agent Nome … viram a menção "@Nome …" que o agente já entende.
                    val st0 = app.store.state.value
                    val text = dev.fluwy.app.agent.SlashCommands.resolveArgCommand(rawText, st0.skills.filter { it.enabled }.map { it.name }, st0.agents.map { it.name }) ?: rawText
                    val tt = text.trim()
                    if (tt.startsWith("/skill ", true) || tt.equals("/skill", true) || tt.startsWith("/agent ", true) || tt.equals("/agent", true)) {
                        draft = ""
                        app.store.addItem(sessionId, ChatItem.Assistant(text = "Não encontrei esse nome. Digite `/skill ` ou `/agent ` e escolha na lista.", final = false))
                        return@Composer
                    }
                    if (tt.startsWith("/create-agent", true) || tt.startsWith("@create-agent", true)) {
                        val desc = tt.drop("/create-agent".length).trim()
                        draft = ""
                        if (desc.isBlank()) app.store.addItem(sessionId, ChatItem.Assistant(text = "Descreva o agente após /create-agent. Ex.: `/create-agent um analista de ações que todo dia de manhã resume o mercado`", final = false))
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
                            "/agents" -> nav.navigate("agents")
                            "/new" -> nav.navigate("home") { launchSingleTop = true }
                            else -> app.store.addItem(sessionId, ChatItem.Assistant(text =
                                (if (cmd == "/help") "" else "Comando desconhecido: `$cmd`.\n\n") + dev.fluwy.app.agent.SlashCommands.helpMarkdown(), final = false))
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
        AlertDialog(onDismissRequest = { rename = false }, title = { Text("Renomear tarefa") }, text = { FluwyField(t, { t = it }, "Título") },
            confirmButton = { TextButton({ app.store.editSession(sessionId) { it.copy(title = t) }; rename = false }) { Text("Salvar") } },
            dismissButton = { TextButton({ rename = false }) { Text("Cancelar") } })
    }
}
