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
    val c = Fluwy.c
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
    // Menções conhecidas (@CONECTOR:, @APIHUB:, @skill/@agent vindos de /skill e /agent) ficam em azul.
    val mentionTargets = remember(editor.text.count { it == '@' }) {
        runCatching { FluwyApp.instance.agent.mentionTargets() }.getOrDefault(emptyList())
    }
    val mentionTransformation = remember(mentionRange, editor.text, mentionTargets) {
        VisualTransformation { source ->
            val annotated = buildAnnotatedString {
                append(source)
                dev.fluwy.app.agent.Mentions.find(source.text, mentionTargets).forEach { h ->
                    addStyle(SpanStyle(color = Color.White, background = Color(0xFF246BCE), fontWeight = FontWeight.SemiBold), h.start, h.end)
                }
            }
            TransformedText(annotated, OffsetMapping.Identity)
        }
    }
    var files by remember { mutableStateOf(listOf<Uri>()) }
    val composerCtx = androidx.compose.ui.platform.LocalContext.current
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
        if (SpeechRecognizer.isRecognitionAvailable(ctx)) dev.fluwy.app.assistant.SpeechRecognizers.create(ctx) else null
    }
    DisposableEffect(recognizer) {
        onDispose { runCatching { recognizer?.destroy() } }
    }
    val voicePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasAudioPermission = granted
        if (granted) pendingVoiceStart = true else voiceError = "Permissão de microfone negada."
    }

    // Ditado: fala → pausa → para sozinho e insere o texto. O fim da fala é decidido pelo app (sem novo texto
    // da transcrição por [DictationVad.END_SILENCE_MS]), sem depender do provedor, que às vezes não encerra.
    // Cada gravação tem um token: callbacks atrasados de uma gravação já encerrada são ignorados.
    var dictated by remember { mutableStateOf("") }
    var busyRetries by remember { mutableIntStateOf(0) }
    val voiceScope = rememberCoroutineScope()
    var voiceToken by remember { mutableIntStateOf(0) }
    var silenceWatch by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var lastSpeechAt by remember { mutableLongStateOf(0L) }
    var finishing by remember { mutableStateOf(false) }
    fun commitDictation() {
        silenceWatch?.cancel(); silenceWatch = null
        voiceToken++  // encerra de vez: nada desta gravação volta a mexer no estado
        runCatching { recognizer?.cancel() }
        val spoken = (if (voiceText.length > dictated.length) voiceText else dictated).trim()
        if (spoken.isNotEmpty()) onChange(listOf(value.trim(), spoken).filter { it.isNotEmpty() }.joinToString(" "))
        dictated = ""; voiceText = ""; pauseRequested = false; recording = false; finishing = false
    }
    /** Pede o texto final ao reconhecedor; se ele não responder a tempo, insere a transcrição ao vivo. */
    fun finishDictation() {
        if (finishing || !recording) return
        finishing = true
        val token = voiceToken
        runCatching { recognizer?.stopListening() }.onFailure { commitDictation(); return }
        voiceScope.launch { kotlinx.coroutines.delay(dev.fluwy.app.assistant.DictationVad.FINAL_RESULT_WAIT_MS); if (token == voiceToken) commitDictation() }
    }

    fun beginVoiceRecording(retry: Boolean = false) {
        val speech = recognizer
        if (speech == null) {
            voiceError = "O reconhecimento de voz não está disponível neste aparelho."
            return
        }
        voiceError = null
        if (!retry) { voiceToken++; voiceText = ""; dictated = ""; busyRetries = 0; pauseRequested = false; finishing = false }
        val token = voiceToken
        fun live() = token == voiceToken
        val startedAt = android.os.SystemClock.elapsedRealtime()
        lastSpeechAt = 0L
        silenceWatch?.cancel()
        // Vigia: depois que a fala começou, N ms sem texto novo = fim; sem fala nenhuma, desiste após o limite.
        silenceWatch = voiceScope.launch {
            while (live()) {
                kotlinx.coroutines.delay(200)
                if (!live() || finishing) break
                val now = android.os.SystemClock.elapsedRealtime()
                when (dev.fluwy.app.assistant.DictationVad.check(now - startedAt, if (lastSpeechAt == 0L) null else now - lastSpeechAt)) {
                    dev.fluwy.app.assistant.DictationVad.Check.FINISH -> { finishDictation(); break }
                    dev.fluwy.app.assistant.DictationVad.Check.NO_SPEECH -> {
                        voiceToken++; runCatching { speech.cancel() }; recording = false
                        voiceError = "Não detectei fala. Toque no microfone para tentar novamente."; break
                    }
                    dev.fluwy.app.assistant.DictationVad.Check.CONTINUE -> Unit
                }
            }
        }
        speech.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { if (live()) recording = true }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { if (live()) finishDictation() }
            override fun onError(error: Int) {
                if (!live()) return
                when (error) {
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT ->
                        if (!finishing && voiceText.isBlank() && ++busyRetries <= 2) {
                            runCatching { speech.cancel() }
                            voiceScope.launch { kotlinx.coroutines.delay(400); if (live()) beginVoiceRecording(retry = true) }
                        } else if (voiceText.isNotBlank()) commitDictation() else { commitDictation(); voiceError = "O microfone está ocupado. Tente novamente." }
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        if (voiceText.isNotBlank()) commitDictation() else { commitDictation(); voiceError = "Não detectei fala. Toque no microfone para tentar novamente." }
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> { commitDictation(); voiceError = "Permita o acesso ao microfone nas configurações." }
                    else -> if (voiceText.isNotBlank()) commitDictation() else { commitDictation(); voiceError = "Falha no reconhecimento de voz (código $error)." }
                }
            }
            override fun onResults(results: Bundle?) {
                if (!live()) return
                val segment = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim()
                if (segment.isNotEmpty()) dictated = segment
                voiceText = dictated.ifBlank { voiceText }
                commitDictation()
            }
            override fun onPartialResults(partialResults: Bundle?) {
                if (!live()) return
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()?.let { part ->
                    if (part.isNotEmpty() && part != voiceText) { voiceText = part; lastSpeechAt = android.os.SystemClock.elapsedRealtime() }
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "pt-BR")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, dev.fluwy.app.assistant.DictationVad.END_SILENCE_MS)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, dev.fluwy.app.assistant.DictationVad.END_SILENCE_MS)
        runCatching { speech.startListening(intent); recording = true }
            .onFailure { voiceToken++; silenceWatch?.cancel(); recording = false; voiceError = "Não foi possível iniciar o microfone: ${it.message ?: "erro desconhecido"}" }
    }
    LaunchedEffect(pendingVoiceStart) {
        if (pendingVoiceStart) {
            pendingVoiceStart = false
            beginVoiceRecording()
        }
    }
    // Sugestões: "/" (comandos agrupados), "/skill …"/"/agent …" (escolha), "@" (Conectores ou API Hub).
    val token = editor.text.substringAfterLast(' ').substringAfterLast('\n')
    val catalogFlow = remember {
        FluwyApp.instance.store.state.map { it.skills to it.agents }.distinctUntilChanged()
    }
    val initialCatalog = remember {
        FluwyApp.instance.store.state.value.let { it.skills to it.agents }
    }
    val catalog by catalogFlow.collectAsStateWithLifecycle(initialCatalog)
    val suggestions: List<ComposerSuggestion> = remember(token, editor.text, catalog.first, catalog.second, mentionTargets) {
        composerSuggestions(editor.text, token, catalog.first, catalog.second, mentionTargets)
    }
    if (suggestions.isNotEmpty()) {
        ComposerSuggestionBox(suggestions) { sg ->
            if (sg.replaceAll) {
                editor = TextFieldValue(sg.insert, selection = TextRange(sg.insert.length))
                mentionRange = null
                onChange(sg.insert)
            } else {
                val prefix = editor.text.dropLast(token.length)
                val selected = sg.insert.trimEnd()
                val updated = prefix + sg.insert
                val start = prefix.length
                editor = TextFieldValue(updated, selection = TextRange(updated.length))
                mentionRange = TextRange(start, start + selected.length)
                onChange(updated)
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
                    IconButton(onClick = { finishDictation() }) { Icon(Icons.Rounded.Pause, "Parar e inserir texto") }
                } else if (voiceText.isNotBlank()) {
                    IconButton(onClick = {
                        onChange(listOf(value.trim(), voiceText.trim()).filter { it.isNotEmpty() }.joinToString(" "))
                        voiceText = ""; voiceError = null
                    }) { Icon(Icons.Rounded.Check, "Inserir transcrição") }
                }
                IconButton(onClick = {
                    runCatching { recognizer?.cancel() }
                    recording = false; pauseRequested = false; voiceText = ""; dictated = ""; voiceError = null
                }) { Icon(Icons.Rounded.Close, "Cancelar gravação") }
            }
            Spacer(Modifier.height(8.dp))
        }
        Box(Modifier.fillMaxWidth().heightIn(min = 28.dp, max = 160.dp).padding(horizontal = 4.dp)) {
            if (editor.text.isEmpty()) Text(placeholder, color = c.ink3, fontSize = 16.sp)
            BasicTextField(
                value = editor,
                onValueChange = { next0 ->
                    val oldRange = mentionRange
                    val oldText = editor.text
                    // Texto muito longo colado de uma vez vira um arquivo anexado (como no Claude): o campo fica leve
                    // e o agente recebe o conteúdo inteiro como arquivo, em vez de uma mensagem gigante.
                    var next = next0
                    if (next0.text.length - oldText.length >= PASTE_TO_FILE_CHARS) {
                        val pre = oldText.commonPrefixWith(next0.text).length
                        val suf = oldText.substring(pre).commonSuffixWith(next0.text.substring(pre)).length
                        val chunk = next0.text.substring(pre, next0.text.length - suf)
                        runCatching {
                            val dir = java.io.File(composerCtx.cacheDir, "pasted").apply { mkdirs() }
                            val f = java.io.File(dir, "texto-colado-" + java.text.SimpleDateFormat("HHmmss", java.util.Locale.ROOT).format(java.util.Date()) + ".txt")
                            f.writeText(chunk)
                            files = files + Uri.fromFile(f)
                            next = TextFieldValue(oldText, selection = TextRange(pre))
                        }
                    }
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
                if (recording) finishDictation() else if (hasAudioPermission) beginVoiceRecording() else voicePermission.launch(Manifest.permission.RECORD_AUDIO)
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
                val enabled = value.isNotBlank() || files.isNotEmpty()
                Box(Modifier.size(40.dp).clip(CircleShape).background(if (enabled) c.ink else c.surface2)
                    .clickable(enabled) { onSend(value.trim().ifBlank { "Segue o conteúdo no arquivo anexado." }, files); files = emptyList() }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.ArrowUpward, "Enviar", tint = if (enabled) c.bg else c.ink3)
                }
            }
        }
    }
}

@Composable
fun RoundIcon(icon: ImageVector, desc: String, onClick: () -> Unit) {
    val c = Fluwy.c
    Box(Modifier.size(36.dp).clip(CircleShape).border(1.dp, c.line, CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, desc, Modifier.size(20.dp), tint = c.ink2)
    }
}

/** Envolve uma tela (ex.: o chat) com a mesma sidebar da Home; `content` recebe a ação que abre a sidebar. */
@Composable
fun WithSessionDrawer(nav: NavController, content: @Composable (openDrawer: () -> Unit) -> Unit) {
    val app = FluwyApp.instance
    val st by app.store.state.collectAsStateWithLifecycle()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    androidx.activity.compose.BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = drawer.isOpen,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = Fluwy.c.bg, modifier = Modifier.width(310.dp)) {
                if (drawer.isOpen || drawer.isAnimationRunning) SessionDrawer(nav, st.sessions, st.agents, st.connectors.count { it.connectedAt != null }, drawer.isOpen,
                    onClose = { scope.launch { drawer.close() } },
                    onNew = { scope.launch { drawer.close() }; nav.navigate("home") { popUpTo(0) { inclusive = true }; launchSingleTop = true } })
            }
        },
    ) { content { scope.launch { drawer.open() } } }
}
