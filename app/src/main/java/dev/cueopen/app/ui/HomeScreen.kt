package dev.cueopen.app.ui

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
import dev.cueopen.app.CueApp
import dev.cueopen.app.data.AgentProfile
import dev.cueopen.app.data.Session
import dev.cueopen.app.data.SessionStatus
import dev.cueopen.app.ui.theme.Cue
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
    val app = CueApp.instance
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

private fun safeAttachmentName(name: String): String =
    name.substringAfterLast('/').substringAfterLast('\\').replace("..", "_").trim().ifBlank { "arquivo" }

fun queryName(ctx: android.content.Context, u: Uri): String {
    ctx.contentResolver.query(u, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) return safeAttachmentName(c.getString(0).orEmpty())
    }
    return safeAttachmentName(u.lastPathSegment.orEmpty())
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

private data class HomeHeaderState(
    val agents: List<AgentProfile>,
    val userName: String,
    val apiKey: String,
)

private data class DrawerSnapshot(
    val sessions: List<Session>,
    val connectedConnectorCount: Int,
)

@Composable
fun HomeScreen(nav: NavController, initialDraft: String? = null, initialAgent: String? = null) {
    val app = CueApp.instance
    val headerFlow = remember(app) {
        app.store.state.map { state ->
            HomeHeaderState(state.agents, state.settings.userName, state.settings.apiKey)
        }.distinctUntilChanged()
    }
    val currentState = remember(app) {
        app.store.state.value.let { HomeHeaderState(it.agents, it.settings.userName, it.settings.apiKey) }
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
    val c = Cue.c

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
                }) { Icon(Icons.Rounded.Menu, "Menu") }
                AgentSelector(agent, st.agents, onSelect = { agentId = it }, onManage = { nav.navigate("agents") })
                Spacer(Modifier.weight(1f))
                IconButton({ nav.navigate("terminal") }) { Icon(Icons.Rounded.Terminal, "Terminal") }
                IconButton({ nav.navigate("settings") { launchSingleTop = true } }) { Icon(Icons.Rounded.Tune, "Configurações") }
            }
        }) { pad ->
            Column(Modifier.fillMaxSize().padding(pad).imePadding()) {
                Column(Modifier.weight(1f).padding(horizontal = 20.dp), verticalArrangement = Arrangement.Center) {
                    if (agent == null) RotatingAgentFace(56.dp, intervalMillis = 4_800L)
                    else AnimatedAgentFace(Color(agent.color), agent.face, 56.dp)
                    Spacer(Modifier.height(16.dp))
                    val hello = st.userName.ifBlank { null }?.let { "Olá, $it" } ?: "Olá"
                    Text(hello, style = MaterialTheme.typography.headlineLarge, color = c.ink3)
                    Text(if (agent != null) "O que ${agent.name} deve fazer?" else "O que posso fazer por você?", style = MaterialTheme.typography.headlineLarge)
                    if (st.apiKey.isBlank()) {
                        Spacer(Modifier.height(16.dp))
                        Row(Modifier.clip(RoundedCornerShape(14.dp)).background(c.accentSoft).clickable { nav.navigate("settings") { launchSingleTop = true } }.padding(12.dp),
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
            Text(agent?.name ?: "Fluwy Bot", style = MaterialTheme.typography.titleLarge)
            Icon(Icons.Rounded.KeyboardArrowDown, null)
        }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem(text = { Text("Fluwy Bot (padrão)") }, leadingIcon = { AgentFace(Color(0xFFFF6B3D), "wink", 24.dp) }, onClick = { onSelect(null); open = false })
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
    nextCycleAt: Long? = null,
    onSend: (String, List<Uri>) -> Unit,
    replyText: String? = null,
    replyAuthor: String = "mensagem do agente",
    onClearReply: () -> Unit = {},
) {
    val c = Cue.c
    // Mantém cursor/seleção dentro do Composer; String isolada perde a posição do cursor
    // quando uma sugestão @ é substituída.
    var editor by remember { mutableStateOf(TextFieldValue(value, selection = TextRange(value.length))) }
    var mentionRange by remember { mutableStateOf<TextRange?>(null) }
    LaunchedEffect(value) {
        if (value != editor.text) {
            editor = TextFieldValue(value, selection = TextRange(value.length))
            mentionRange = null
        }
    }
    val mentionTransformation = remember(mentionRange, editor.text) {
        val range = mentionRange
        val expected = range?.takeIf { it.start >= 0 && it.end <= editor.text.length }
            ?.let { editor.text.substring(it.start, it.end) }
        VisualTransformation { source ->
            val annotated = buildAnnotatedString {
                append(source)
                if (range != null && expected != null && range.end <= source.length &&
                    source.text.substring(range.start, range.end) == expected) {
                    addStyle(
                        SpanStyle(color = Color.White, background = Color(0xFF246BCE), fontWeight = FontWeight.SemiBold),
                        range.start,
                        range.end
                    )
                }
            }
            TransformedText(annotated, OffsetMapping.Identity)
        }
    }
    var files by remember { mutableStateOf(listOf<Uri>()) }
    var fileNames by remember { mutableStateOf<Map<Uri, String>>(emptyMap()) }
    val ctx = LocalContext.current
    LaunchedEffect(files) {
        val selected = files
        fileNames = withContext(Dispatchers.IO) {
            selected.associateWith { uri -> runCatching { queryName(ctx, uri) }.getOrDefault("arquivo") }
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { files = files + it }
    var recording by remember { mutableStateOf(false) }
    var voiceText by remember { mutableStateOf("") }
    var voiceError by remember { mutableStateOf<String?>(null) }
    var pauseRequested by remember { mutableStateOf(false) }
    var pendingVoiceStart by remember { mutableStateOf(false) }
    var hasAudioPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }
    val recognizer = remember(ctx) {
        if (SpeechRecognizer.isRecognitionAvailable(ctx)) SpeechRecognizer.createSpeechRecognizer(ctx) else null
    }
    DisposableEffect(recognizer) {
        onDispose { runCatching { recognizer?.destroy() } }
    }
    val voicePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasAudioPermission = granted
        if (granted) pendingVoiceStart = true else voiceError = "Permissão de microfone negada."
    }

    fun beginVoiceRecording() {
        val speech = recognizer
        if (speech == null) {
            voiceError = "O reconhecimento de voz não está disponível neste aparelho."
            return
        }
        voiceError = null
        voiceText = ""
        pauseRequested = false
        speech.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { recording = true }
            override fun onBeginningOfSpeech() { recording = true }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                recording = false
                if (pauseRequested) {
                    val spoken = voiceText.trim()
                    if (spoken.isNotEmpty()) onChange(listOf(value.trim(), spoken).filter { it.isNotEmpty() }.joinToString(" "))
                    voiceText = ""
                    pauseRequested = false
                } else if (error != SpeechRecognizer.ERROR_CLIENT && error != SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                    voiceError = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "Não consegui reconhecer a fala. Tente novamente."
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Não detectei fala. Toque no microfone para tentar novamente."
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permita o acesso ao microfone nas configurações."
                        else -> "Falha no reconhecimento de voz (código $error)."
                    }
                }
            }
            override fun onResults(results: Bundle?) {
                val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().ifBlank { voiceText }.trim()
                voiceText = spoken
                recording = false
                if (pauseRequested) {
                    if (spoken.isNotEmpty()) onChange(listOf(value.trim(), spoken).filter { it.isNotEmpty() }.joinToString(" "))
                    voiceText = ""
                    pauseRequested = false
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { voiceText = it }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "pt-BR")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        runCatching { speech.startListening(intent); recording = true }
            .onFailure { recording = false; voiceError = "Não foi possível iniciar o microfone: ${it.message ?: "erro desconhecido"}" }
    }
    LaunchedEffect(pendingVoiceStart) {
        if (pendingVoiceStart) {
            pendingVoiceStart = false
            beginVoiceRecording()
        }
    }
    // Sugestões de comandos (/) e menções (@ skills e agents)
    val token = editor.text.substringAfterLast(' ').substringAfterLast('\n')
    val catalogFlow = remember {
        CueApp.instance.store.state.map { it.skills to it.agents }.distinctUntilChanged()
    }
    val initialCatalog = remember {
        CueApp.instance.store.state.value.let { it.skills to it.agents }
    }
    val catalog by catalogFlow.collectAsStateWithLifecycle(initialCatalog)
    val suggestions: List<Triple<String, String, String>> = remember(token, catalog.first, catalog.second) {
        when {
            value.startsWith("/") && !value.contains(' ') -> slashCommands.filter { it.first.startsWith(value, true) }.map { Triple(it.first, it.second, it.first + " ") }
            token.startsWith("@") -> (listOf(Triple("@create-agent", "Criar um novo agente a partir de uma descrição", "@create-agent ")) +
                catalog.second.map { Triple("@" + it.name, "Agent", "@" + it.name + " ") } +
                catalog.first.filter { it.enabled }.map { Triple("@" + it.name, "Skill · " + it.description.take(60), "@" + it.name + " ") })
                .filter { it.first.contains(token.drop(1), true) }.take(12)
            else -> emptyList()
        }
    }
    if (suggestions.isNotEmpty()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(18.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(18.dp))
            .heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
            suggestions.forEach { (label, desc, insert) ->
                Row(Modifier.fillMaxWidth().clickable {
                    if (label.startsWith("/")) {
                        editor = TextFieldValue(insert, selection = TextRange(insert.length))
                        mentionRange = null
                        onChange(insert)
                    } else {
                        val prefix = editor.text.dropLast(token.length)
                        val selected = insert.trimEnd()
                        val updated = prefix + insert
                        val start = prefix.length
                        editor = TextFieldValue(updated, selection = TextRange(updated.length))
                        mentionRange = TextRange(start, start + selected.length)
                        onChange(updated)
                    }
                }.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(when { label.startsWith("/") -> Icons.Rounded.Terminal; desc == "Agent" -> Icons.Rounded.SmartToy; else -> Icons.Rounded.AutoAwesome },
                        null, Modifier.size(18.dp), tint = c.accent)
                    Spacer(Modifier.width(10.dp))
                    Text(label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(desc, fontSize = 12.sp, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = if (imeVisible) 0.dp else 12.dp)
        .clip(RoundedCornerShape(26.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(26.dp)).padding(12.dp)) {
        if (!replyText.isNullOrBlank()) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface2).padding(start = 8.dp, end = 2.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(3.dp).height(34.dp).clip(RoundedCornerShape(2.dp)).background(c.accent))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Respondendo a $replyAuthor", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = c.ink2, maxLines = 1)
                    Text(replyText, fontSize = 12.sp, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onClearReply, modifier = Modifier.size(34.dp)) { Icon(Icons.Rounded.Close, "Cancelar resposta", Modifier.size(18.dp), tint = c.ink2) }
            }
            Spacer(Modifier.height(8.dp))
        }
        if (files.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                files.forEach { u ->
                    Row(Modifier.clip(RoundedCornerShape(10.dp)).background(c.surface2).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Description, null, Modifier.size(14.dp)); Spacer(Modifier.width(4.dp))
                        Text(fileNames[u] ?: "Carregando…", fontSize = 12.sp, maxLines = 1)
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Rounded.Close, "Remover", Modifier.size(14.dp).clickable { files = files - u })
                    }
                }
            }
        }
        if (recording || voiceText.isNotBlank() || voiceError != null) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface2).padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Mic, null, tint = if (recording) c.accent else c.ink2, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (recording) "Gravando áudio… fale naturalmente" else if (voiceError != null) "Gravação de voz" else "Transcrição pronta",
                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (voiceError != null) c.err else c.ink2)
                    Text(voiceError ?: voiceText.ifBlank { "O texto aparecerá aqui enquanto você fala." },
                        fontSize = 12.sp, color = if (voiceError != null) c.err else c.ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                if (recording) {
                    IconButton(onClick = {
                        pauseRequested = true
                        runCatching { recognizer?.stopListening() }.onFailure {
                            recording = false
                            val spoken = voiceText.trim()
                            if (spoken.isNotEmpty()) onChange(listOf(value.trim(), spoken).filter { it.isNotEmpty() }.joinToString(" "))
                            voiceText = ""; pauseRequested = false
                        }
                    }) { Icon(Icons.Rounded.Pause, "Pausar e inserir texto") }
                } else if (voiceText.isNotBlank()) {
                    IconButton(onClick = {
                        onChange(listOf(value.trim(), voiceText.trim()).filter { it.isNotEmpty() }.joinToString(" "))
                        voiceText = ""; voiceError = null
                    }) { Icon(Icons.Rounded.Check, "Inserir transcrição") }
                }
                IconButton(onClick = {
                    runCatching { recognizer?.cancel() }
                    recording = false; pauseRequested = false; voiceText = ""; voiceError = null
                }) { Icon(Icons.Rounded.Close, "Cancelar gravação") }
            }
            Spacer(Modifier.height(8.dp))
        }
        Box(Modifier.fillMaxWidth().heightIn(min = 28.dp, max = 160.dp).padding(horizontal = 4.dp)) {
            if (editor.text.isEmpty()) Text(placeholder, color = c.ink3, fontSize = 16.sp)
            BasicTextField(
                value = editor,
                onValueChange = { next ->
                    val oldRange = mentionRange
                    val oldText = editor.text
                    editor = next
                    if (oldRange != null && (oldRange.end > next.text.length ||
                            oldRange.end > oldText.length ||
                            next.text.substring(oldRange.start, oldRange.end) != oldText.substring(oldRange.start, oldRange.end))) {
                        // The selected token changed or was removed; stop styling a stale range.
                        mentionRange = null
                    }
                    onChange(next.text)
                },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.ink),
                cursorBrush = SolidColor(c.accent),
                visualTransformation = mentionTransformation,
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoundIcon(Icons.Rounded.Add, "Anexar") { pick.launch("*/*") }
            Spacer(Modifier.width(6.dp))
            RoundIcon(if (recording) Icons.Rounded.MicOff else Icons.Rounded.Mic, if (recording) "Gravando voz" else "Gravar voz") {
                if (recording) {
                    pauseRequested = true
                    runCatching { recognizer?.stopListening() }.onFailure {
                        recording = false
                        val spoken = voiceText.trim()
                        if (spoken.isNotEmpty()) onChange(listOf(value.trim(), spoken).filter { it.isNotEmpty() }.joinToString(" "))
                        voiceText = ""; pauseRequested = false
                    }
                } else if (hasAudioPermission) beginVoiceRecording() else voicePermission.launch(Manifest.permission.RECORD_AUDIO)
            }
            Spacer(Modifier.weight(1f))
            // Com o agente ativo e sem texto digitado → botão PARAR. Ao digitar → botão ENVIAR.
            if (running && value.isBlank()) {
                // alinhado à direita: o botão PARAR fica sempre no mesmo lugar do botão ENVIAR
                Column(horizontalAlignment = Alignment.End) {
                    // Balão de cronômetro real: conta o tempo exato até o próximo ciclo agendado.
                    if (nextCycleAt != null) {
                        var now by remember(nextCycleAt) { mutableStateOf(System.currentTimeMillis()) }
                        LaunchedEffect(nextCycleAt) {
                            while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(1000) }
                        }
                        val remaining = (nextCycleAt - now).coerceAtLeast(0L)
                        val mm = (remaining / 60_000L).toInt()
                        val ss = ((remaining % 60_000L) / 1000L).toInt()
                        Row(
                            Modifier.clip(RoundedCornerShape(14.dp)).background(c.ink).padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.Schedule, null, Modifier.size(15.dp), tint = c.bg)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (remaining == 0L) "iniciando ciclo…" else "Próximo ciclo em " + String.format("%02d:%02d", mm, ss),
                                color = c.bg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                    Box(Modifier.size(40.dp).clip(CircleShape).background(c.ink).clickable(onClick = onStop), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(12.dp).clip(RoundedCornerShape(2.dp)).background(c.bg))
                    }
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

/** Envolve uma tela (ex.: o chat) com a mesma sidebar da Home; `content` recebe a ação que abre a sidebar. */
@Composable
fun WithSessionDrawer(nav: NavController, content: @Composable (openDrawer: () -> Unit) -> Unit) {
    val app = CueApp.instance
    val st by app.store.state.collectAsStateWithLifecycle()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    androidx.activity.compose.BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = drawer.isOpen,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = Cue.c.bg, modifier = Modifier.width(310.dp)) {
                if (drawer.isOpen || drawer.isAnimationRunning) SessionDrawer(nav, st.sessions, st.agents, st.connectors.count { it.connectedAt != null }, drawer.isOpen,
                    onClose = { scope.launch { drawer.close() } },
                    onNew = { scope.launch { drawer.close() }; nav.navigate("home") { popUpTo(0) { inclusive = true }; launchSingleTop = true } })
            }
        },
    ) { content { scope.launch { drawer.open() } } }
}

@Composable
private fun SessionDrawer(nav: NavController, sessions: List<Session>, agents: List<AgentProfile>, connectedConnectorCount: Int, drawerOpen: Boolean, onClose: () -> Unit, onNew: () -> Unit) {
    val c = Cue.c
    var query by remember { mutableStateOf("") }
    // Sessões de subagentes são detalhes internos da tarefa pai, nunca chats independentes na navegação.
    val filtered = remember(sessions, query) {
        sessions.filterNot { it.title.startsWith("[Worker ", ignoreCase = true) || it.sectionId != null }
            .filter { query.isBlank() || it.title.contains(query, true) }
            .sortedByDescending { it.updatedAt }
    }
    val groups = remember(filtered) { filtered.groupBy { dayGroup(it.updatedAt) } }
    fun go(r: String) { onClose(); nav.navigate(r) }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RotatingAgentFace(32.dp, intervalMillis = 5_200L, enabled = drawerOpen); Spacer(Modifier.width(10.dp))
            Text("Fluwy Bot", style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.height(12.dp))
        PillButton("Nova tarefa", Icons.Rounded.Edit, modifier = Modifier.fillMaxWidth()) { onNew() }
        Spacer(Modifier.height(8.dp))
        CueField(query, { query = it }, "Buscar tarefas")
        Spacer(Modifier.height(4.dp))
        // Menu agrupado e recolhido por padrão: a sidebar fica para tarefas e seções.
        var menuOpen by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { menuOpen = !menuOpen }.padding(horizontal = 10.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Apps, null, Modifier.size(20.dp), tint = c.ink2); Spacer(Modifier.width(12.dp))
            Text("Menu", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Icon(if (menuOpen) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (menuOpen) "Recolher menu" else "Abrir menu", tint = c.ink3)
        }
        androidx.compose.animation.AnimatedVisibility(menuOpen) {
            Column(Modifier.padding(start = 8.dp)) {
                DrawerItem(Icons.Rounded.Groups, "Agents", "${agents.size}") { go("agents") }
                DrawerItem(Icons.Rounded.AutoAwesome, "Skills") { go("skills") }
                DrawerItem(Icons.Rounded.Cable, "Conectores", "$connectedConnectorCount".takeIf { it != "0" }) { go("connectors") }
                DrawerItem(Icons.Rounded.Schedule, "Tarefas agendadas") { go("scheduled") }
                DrawerItem(Icons.Rounded.LibraryBooks, "Knowledge") { go("knowledge") }
                DrawerItem(Icons.Rounded.Psychology, "Memória") { go("memory") }
                DrawerItem(Icons.Rounded.FolderOpen, "Arquivos") { go("files") }
                DrawerItem(Icons.Rounded.AccountBalanceWallet, "Carteira Solana") { go("wallet") }
                DrawerItem(Icons.Rounded.Language, "Browser") { go("browserhub") }
                    DrawerItem(Icons.Rounded.Computer, "Computador (sandbox)") { go("sandbox") }
            }
        }
        Divider()
        val live by CueApp.instance.store.state.collectAsStateWithLifecycle()
        val sections = live.sections.filterNot { it.archived }
        val archivedSections = live.sections.filter { it.archived }
        var showArchived by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
        LazyColumn(Modifier.weight(1f)) {
            // Seções só aparecem quando existem (criadas pelo chat).
            if (sections.isNotEmpty()) {
                item { SectionLabel("Seções") }
                items(sections, key = { "sec-" + it.id }) { sec ->
                    SectionRow(sec, live.sessions, live.agents, onOpen = { go("chat/$it") }, onOpenAgent = { go("agent/$it") })
                }
            }
            if (archivedSections.isNotEmpty()) {
                item {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { showArchived = !showArchived }.padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Inventory2, null, Modifier.size(16.dp), tint = Cue.c.ink3)
                        Spacer(Modifier.width(8.dp))
                        Text("Seções arquivadas (${archivedSections.size})", fontSize = 13.sp, color = Cue.c.ink3, modifier = Modifier.weight(1f))
                        Icon(if (showArchived) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = Cue.c.ink3)
                    }
                }
                if (showArchived) items(archivedSections, key = { "arch-" + it.id }) { sec ->
                    SectionRow(sec, live.sessions, live.agents, onOpen = { go("chat/$it") }, onOpenAgent = { go("agent/$it") })
                }
            }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(s: Session, agent: AgentProfile?, onClick: () -> Unit) {
    val c = Cue.c
    val app = CueApp.instance
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
    val c = Cue.c
    val col = when (status) {
        SessionStatus.RUNNING -> c.accent; SessionStatus.WAITING_USER -> c.warn; SessionStatus.ERROR -> c.err
        SessionStatus.DONE -> c.ok
        // Autônomo parado entre ciclos continua "online" (verde), em vez de sumir (antes: Transparent).
        else -> if (online) c.ok else Color.Transparent
    }
    Box(Modifier.size(8.dp).clip(CircleShape).background(col))
}

fun previewOf(i: dev.cueopen.app.data.ChatItem): String = when (i) {
    is dev.cueopen.app.data.ChatItem.User -> i.text
    is dev.cueopen.app.data.ChatItem.Assistant -> i.text
    is dev.cueopen.app.data.ChatItem.Step -> i.title + " · " + i.args
    is dev.cueopen.app.data.ChatItem.Plan -> i.goal
    is dev.cueopen.app.data.ChatItem.Error -> i.text
    is dev.cueopen.app.data.ChatItem.ConnectorProposal -> "🧩 Conector " + i.name
    is dev.cueopen.app.data.ChatItem.UserScriptProposal -> "📜 User Script " + i.name
    is dev.cueopen.app.data.ChatItem.LlmRecovery -> "⚠ Falha do modelo: " + i.category
    is dev.cueopen.app.data.ChatItem.ConnectorEditProposal -> "Atualização de " + i.connectorName
    is dev.cueopen.app.data.ChatItem.Question -> "❓ " + (i.questions.firstOrNull()?.question ?: "Pergunta")
    is dev.cueopen.app.data.ChatItem.SecureForm -> "🔒 " + i.title
    is dev.cueopen.app.data.ChatItem.Approval -> "🛡 " + i.summary
    is dev.cueopen.app.data.ChatItem.SectionSetup -> "📁 Seção " + i.sectionName
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


/** Comandos da caixa de mensagem. */
val slashCommands = listOf(
    "/compact" to "Compacta o contexto da conversa agora",
    "/autocompact" to "Liga/desliga a compactação automática ao atingir o limite",
    "/clear" to "Limpa o contexto do modelo (mantém o histórico na tela)",
    "/stop" to "Para o agente",
    "/usage" to "Mostra uso de tokens e contexto",
    "/memory" to "Abre a memória de longo prazo",
    "/skills" to "Abre as skills",
    "/connectors" to "Abre os conectores",
    "/help" to "Lista os comandos",
)


/** Seção na sidebar: tocar abre o chat da seção e expande o time; segurar mostra limpar/reiniciar/excluir. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SectionRow(
    sec: dev.cueopen.app.data.Section, sessions: List<Session>, agents: List<AgentProfile>,
    onOpen: (String) -> Unit, onOpenAgent: (String) -> Unit,
) {
    val c = Cue.c
    val app = CueApp.instance
    var expanded by androidx.compose.runtime.saveable.rememberSaveable(sec.id) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) } // "delete" | "restart" | "clear"
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
            sec.agentIds.mapNotNull { id -> agents.firstOrNull { it.id == id } }.forEach { a ->
                val chat = chats.firstOrNull { it.agentId == a.id && it.id != sec.sessionId }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { if (chat != null) onOpen(chat.id) else onOpenAgent(a.id) }
                    .padding(horizontal = 8.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    AgentFace(Color(a.color), a.face, 20.dp); Spacer(Modifier.width(8.dp))
                    Text(a.name, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    // agente trabalhando agora: ponto verde pulsando (um por agente ativo)
                    if (chat?.status == SessionStatus.RUNNING) PulsingDot(c.ok) else StatusDot(chat?.status ?: SessionStatus.IDLE)
                }
            }
        }
    }
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
                        (sec.agentIds + sec.coordinatorAgentId).forEach { dev.cueopen.app.scheduler.Heartbeat.cancel(app, it) }
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
