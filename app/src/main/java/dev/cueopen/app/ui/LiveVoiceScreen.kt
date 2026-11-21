package dev.cueopen.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.NavController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.cueopen.app.CueApp
import dev.cueopen.app.assistant.SpeechRecognizers
import dev.cueopen.app.assistant.VoiceReplies
import dev.cueopen.app.ui.theme.Cue
import dev.cueopen.app.voice.AppVoice
import kotlinx.coroutines.launch

/**
 * Live Audio: simula uma ligação com a assistente (base para o futuro sistema de chamadas).
 * Ouve → responde → fala na hora (streaming, frase a frase) → volta a ouvir, sem tocar em nada.
 * Tocar na esfera interrompe a fala. Modo rápido: chamada direta ao modelo rápido (~1 s); modo agente: o agente
 * completo (ferramentas) e cada mensagem dele é falada assim que fica pronta.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveVoiceScreen(nav: NavController) {
    val c = Cue.c
    val ctx = LocalContext.current
    val app = CueApp.instance
    val scope = rememberCoroutineScope()
    data class Turn(val me: Boolean, val text: String)
    val turns = remember { mutableStateListOf<Turn>() }
    var phase by remember { mutableStateOf("pronto") }  // pronto · ouvindo · pensando · falando
    var partial by remember { mutableStateOf("") }
    var level by remember { mutableFloatStateOf(0f) }
    var useAgent by remember { mutableStateOf(false) }
    var active by remember { mutableStateOf(false) }
    val voiceState by AppVoice.state.collectAsStateWithLifecycle()
    val hasVoice = remember(voiceState) { AppVoice.enabled(ctx) }
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val list = rememberLazyListState()
    val sessionId = remember { "live-" + System.currentTimeMillis() }
    var recognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }
    var muted by remember { mutableStateOf(false) }
    var callStart by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(callStart) { while (callStart > 0) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(1000) } }

    LaunchedEffect(Unit) { AppVoice.prewarm(ctx) }
    LaunchedEffect(turns.size) { if (turns.isNotEmpty()) list.animateScrollToItem(turns.size - 1) }

    fun stopListening() { runCatching { recognizer?.cancel(); recognizer?.destroy() }; recognizer = null }
    lateinit var listen: () -> Unit

    fun answer(text: String) {
        phase = "pensando"
        scope.launch {
            if (useAgent) {
                // Agente: fala cada mensagem dele assim que fica completa (andamento + resposta final).
                val s = app.store.session(sessionId) ?: dev.cueopen.app.data.Session(id = sessionId, title = "Live Audio").also(app.store::upsertSession)
                val before = s.items.map { it.id }.toSet(); val spoken = HashSet<String>()
                val voice = AppVoice.startStream(ctx, AppVoice.detectLang(text)) { scope.launch { if (active && phase == "falando") listen() } }
                fun flush(all: Boolean) {
                    val items = app.store.session(s.id)?.items.orEmpty()
                    items.forEachIndexed { i, it ->
                        if (it !is dev.cueopen.app.data.ChatItem.Assistant || it.id in before || it.id in spoken || it.text.isBlank()) return@forEachIndexed
                        if (!all && !it.final && i == items.lastIndex) return@forEachIndexed
                        spoken += it.id
                        val say = VoiceReplies.speakable(it.text); turns += Turn(false, say)
                        if (voice != null) { phase = "falando"; voice.trySend(say) }
                    }
                }
                val job = runCatching { app.agent.send(s.id, text) }.getOrNull()
                while (job?.isActive == true && active) { flush(false); kotlinx.coroutines.delay(300) }
                flush(true); voice?.close()
                if (voice == null || spoken.isEmpty()) { if (active) listen() else phase = "pronto" }
                return@launch
            }
            // Modo rápido em STREAMING: a IA manda o texto aos poucos e cada trecho já vai sendo falado.
            val history = turns.takeLast(10).joinToString("\n") { (if (it.me) "Usuário: " else "Assistente: ") + it.text }
            val prompt = "Você é a assistente de voz do app, numa ligação em tempo real em português do Brasil (responda em inglês só se o usuário falar em inglês). " +
                "Responda como fala natural de telefone: curto (1 a 3 frases), direto, sem markdown, listas, emojis ou links. Comece pela resposta, sem rodeios. " +
                "Se precisar de uma ação no celular ou na internet, diga em uma frase que pode fazer no modo Agente.\n\nConversa até agora:\n$history\n\nAssistente:"
            val voice = if (active) AppVoice.startStream(ctx, AppVoice.detectLang(text)) { scope.launch { if (active && phase == "falando") listen() } } else null
            val idx = turns.size; turns += Turn(false, "…")
            val buf = StringBuilder(); val shown = StringBuilder(); var pieces = 0
            fun send(piece: String) {
                val say = VoiceReplies.speakable(piece).trim(); if (say.isEmpty()) return
                pieces++; voice?.trySend(say)
                if (voice != null) scope.launch { phase = "falando" }
            }
            fun flush(force: Boolean) {
                while (true) {
                    val str = buf.toString()
                    var cut = str.indexOfFirst { it in ".!?…\n" }
                    if (cut < 0 && pieces == 0) cut = str.indexOf(',').takeIf { it >= 18 } ?: -1          // 1º trecho: já na vírgula
                    if (cut < 0 && str.length > 140) cut = str.lastIndexOf(' ').takeIf { it > 60 } ?: -1   // trecho longo sem pontuação
                    if (cut < 0) break
                    send(str.substring(0, cut + 1)); buf.delete(0, cut + 1)
                }
                if (force && buf.isNotBlank()) { send(buf.toString()); buf.setLength(0) }
            }
            val reply = runCatching {
                app.llm.quickStream(prompt, maxTokens = 220, conversationId = sessionId) { d ->
                    buf.append(d); shown.append(d)
                    val txt = VoiceReplies.speakable(shown.toString())
                    scope.launch { if (idx < turns.size) turns[idx] = Turn(false, txt) }
                    flush(false)
                }
            }.getOrElse { val e = "Desculpe, não consegui responder agora: ${it.message ?: "erro"}."; buf.setLength(0); buf.append(e); e }
            flush(true); voice?.close()
            val finalText = VoiceReplies.speakable(reply).ifBlank { "Não tenho uma resposta para isso." }
            if (idx < turns.size) turns[idx] = Turn(false, finalText)
            if (voice == null) {
                phase = "pronto"
                if (active) turns += Turn(false, "(Baixe uma voz em Configurações › Voz do app para ouvir as respostas.)")
            } else if (pieces == 0 && active) listen()
        }
    }

    listen = listen@{
        stopListening()
        if (muted) { phase = "mudo"; return@listen }
        if (!granted) { perm.launch(Manifest.permission.RECORD_AUDIO); return@listen }
        val r = runCatching { SpeechRecognizers.create(ctx) }.getOrNull() ?: run { phase = "pronto"; return@listen }
        recognizer = r
        phase = "ouvindo"; partial = ""
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) { level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { level = 0f }
            override fun onError(error: Int) {
                // Silêncio/sem fala: volta a ouvir enquanto a conversa estiver ativa.
                if (active && (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) scope.launch { listen() }
                else if (phase == "ouvindo") phase = "pronto"
            }
            override fun onResults(results: Bundle?) {
                val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                partial = ""
                if (t.isBlank()) { if (active) scope.launch { listen() }; return }
                turns += Turn(true, t); answer(t)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { partial = it }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // Fim de fala detectado rápido: a resposta começa logo depois que você para de falar.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 700L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 500L)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        })
    }

    DisposableEffect(Unit) { onDispose { active = false; AppVoice.stop(); stopListening() } }

    fun startCall() {
        active = true; callStart = System.currentTimeMillis(); turns.clear()
        phase = "falando"
        val hello = if (AppVoice.speak(ctx, "Oi! Pode falar, estou ouvindo.") { scope.launch { if (active) listen() } }) true else { listen(); false }
        if (hello) turns += Turn(false, "Oi! Pode falar, estou ouvindo.")
    }
    fun hangUp() { active = false; AppVoice.stop(); stopListening(); phase = "pronto"; callStart = 0L }

    Scaffold(containerColor = c.bg, topBar = {
        CueTopBar("Live Audio", { hangUp(); nav.popBackStack() }) {
            FilterChip(useAgent, { useAgent = !useAgent }, { Text(if (useAgent) "Agente" else "Rápido") },
                leadingIcon = { Icon(if (useAgent) Icons.Rounded.SmartToy else Icons.Rounded.Bolt, null, Modifier.size(16.dp)) })
        }
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (!hasVoice) Row(Modifier.padding(12.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.warn.copy(alpha = .12f)).clickable { nav.navigate("settings") }.padding(12.dp)) {
                Icon(Icons.Rounded.RecordVoiceOver, null, tint = c.warn); Spacer(Modifier.width(8.dp))
                Text("Baixe uma voz em Configurações › Voz do app para ouvir as respostas na hora.", fontSize = 13.sp)
            }
            // Cabeçalho de chamada
            Text("Assistente", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
            val secs = if (callStart > 0) ((now - callStart) / 1000).coerceAtLeast(0) else 0
            Text(if (callStart > 0) "%02d:%02d".format(secs / 60, secs % 60) + " · " + when (phase) {
                    "ouvindo" -> "ouvindo"; "pensando" -> "pensando"; "falando" -> "falando"; "mudo" -> "microfone mudo"; else -> "em chamada" }
                else "Simulação de ligação por voz", color = c.ink3, fontSize = 13.sp)
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(turns) { t ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (t.me) Arrangement.End else Arrangement.Start) {
                        Text(t.text, Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(16.dp)).background(if (t.me) c.accent.copy(alpha = .16f) else c.surface).padding(12.dp), fontSize = 15.sp)
                    }
                }
            }
            if (partial.isNotBlank()) Text(partial, Modifier.padding(horizontal = 24.dp), color = c.ink3, fontSize = 14.sp, textAlign = TextAlign.Center)
            val pulse = rememberInfiniteTransition(label = "orb")
            val breathe by pulse.animateFloat(1f, 1.08f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "b")
            val sc = when (phase) { "ouvindo" -> 1f + level * 0.35f; "falando", "pensando" -> breathe; else -> 1f }
            Box(Modifier.padding(18.dp).size(130.dp).scale(sc).clip(CircleShape)
                .background(Brush.radialGradient(listOf(c.accent, c.accent.copy(alpha = .35f))))
                .clickable {
                    when {
                        callStart == 0L -> startCall()
                        phase == "falando" -> { AppVoice.stop(); listen() }  // interrompe e já ouve
                        phase == "pronto" -> listen()
                    }
                }, contentAlignment = Alignment.Center) {
                Icon(when (phase) { "ouvindo" -> Icons.Rounded.Mic; "falando" -> Icons.Rounded.GraphicEq; "pensando" -> Icons.Rounded.MoreHoriz; "mudo" -> Icons.Rounded.MicOff; else -> Icons.Rounded.MicNone },
                    null, Modifier.size(50.dp), tint = c.bg)
            }
            // Controles da chamada
            Row(Modifier.padding(bottom = 28.dp), horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                if (callStart == 0L) {
                    Button({ startCall() }, colors = ButtonDefaults.buttonColors(containerColor = c.ok), shape = CircleShape, modifier = Modifier.size(68.dp), contentPadding = PaddingValues(0.dp)) {
                        Icon(Icons.Rounded.Call, "Ligar", Modifier.size(30.dp))
                    }
                } else {
                    FilledTonalIconButton({ muted = !muted; if (muted) { stopListening(); phase = "mudo" } else listen() }, Modifier.size(56.dp)) {
                        Icon(if (muted) Icons.Rounded.MicOff else Icons.Rounded.Mic, if (muted) "Ativar microfone" else "Mudo")
                    }
                    Button({ hangUp() }, colors = ButtonDefaults.buttonColors(containerColor = c.err), shape = CircleShape, modifier = Modifier.size(68.dp), contentPadding = PaddingValues(0.dp)) {
                        Icon(Icons.Rounded.CallEnd, "Encerrar", Modifier.size(30.dp))
                    }
                }
            }
        }
    }
}
