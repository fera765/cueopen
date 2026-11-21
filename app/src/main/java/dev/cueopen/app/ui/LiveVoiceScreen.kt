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
import dev.cueopen.app.voice.DiiVoice
import kotlinx.coroutines.launch

/**
 * Conversa ao vivo por voz: ouve → responde curto → a Dii fala na hora (streaming, frase a frase) → volta a ouvir.
 * Tocar na esfera interrompe a fala e volta a ouvir. Modo rápido (padrão): chamada direta ao modelo rápido, sem
 * ferramentas, para a resposta chegar em ~1 s; modo agente: o agente completo (ferramentas), mais lento.
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
    val voiceState by DiiVoice.state.collectAsStateWithLifecycle()
    val hasVoice = remember(voiceState) { DiiVoice.enabled(ctx) }
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val list = rememberLazyListState()
    val sessionId = remember { "live-" + System.currentTimeMillis() }
    var recognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }

    LaunchedEffect(Unit) { DiiVoice.prewarm(ctx) }
    LaunchedEffect(turns.size) { if (turns.isNotEmpty()) list.animateScrollToItem(turns.size - 1) }

    fun stopListening() { runCatching { recognizer?.cancel(); recognizer?.destroy() }; recognizer = null }
    lateinit var listen: () -> Unit

    fun answer(text: String) {
        phase = "pensando"
        scope.launch {
            val reply = runCatching {
                if (useAgent) {
                    val s = app.store.session(sessionId) ?: dev.cueopen.app.data.Session(id = sessionId, title = "Conversa por voz").also(app.store::upsertSession)
                    app.agent.send(s.id, text).join()
                    (app.store.session(s.id)?.items?.lastOrNull { it is dev.cueopen.app.data.ChatItem.Assistant } as? dev.cueopen.app.data.ChatItem.Assistant)?.text.orEmpty()
                } else {
                    val history = turns.takeLast(10).joinToString("\n") { (if (it.me) "Usuário: " else "Assistente: ") + it.text }
                    app.llm.quick("Você é a assistente de voz do app, numa conversa falada em tempo real em português do Brasil. " +
                        "Responda como fala natural: curto (1 a 3 frases), direto, sem markdown, listas, emojis ou links. " +
                        "Se precisar de uma ação no celular ou na internet, diga em uma frase que pode fazer pelo chat.\n\nConversa até agora:\n$history\n\nAssistente:",
                        maxTokens = 220, conversationId = sessionId)
                }
            }.getOrElse { "Desculpe, não consegui responder agora: ${it.message ?: "erro"}." }
            val say = VoiceReplies.speakable(reply).ifBlank { "Não tenho uma resposta para isso." }
            turns += Turn(false, say)
            if (!active) { phase = "pronto"; return@launch }
            phase = "falando"
            val ok = DiiVoice.speak(ctx, say) { scope.launch { if (active && phase == "falando") listen() } }
            if (!ok) { phase = "pronto"; turns += Turn(false, "(Baixe a voz Dii em Configurações › Voz do app para ouvir as respostas.)") }
        }
    }

    listen = listen@{
        stopListening()
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

    DisposableEffect(Unit) { onDispose { active = false; DiiVoice.stop(); stopListening() } }

    Scaffold(containerColor = c.bg, topBar = {
        CueTopBar("Conversa por voz", { nav.popBackStack() }) {
            FilterChip(useAgent, { useAgent = !useAgent }, { Text(if (useAgent) "Agente" else "Rápido") },
                leadingIcon = { Icon(if (useAgent) Icons.Rounded.SmartToy else Icons.Rounded.Bolt, null, Modifier.size(16.dp)) })
        }
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (!hasVoice) Row(Modifier.padding(12.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.warn.copy(alpha = .12f)).clickable { nav.navigate("settings") }.padding(12.dp)) {
                Icon(Icons.Rounded.RecordVoiceOver, null, tint = c.warn); Spacer(Modifier.width(8.dp))
                Text("Baixe a voz Dii em Configurações › Voz do app para ouvir as respostas na hora.", fontSize = 13.sp)
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(turns) { t ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (t.me) Arrangement.End else Arrangement.Start) {
                        Text(t.text, Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(16.dp)).background(if (t.me) c.accent.copy(alpha = .16f) else c.surface).padding(12.dp), fontSize = 15.sp)
                    }
                }
            }
            if (partial.isNotBlank()) Text(partial, Modifier.padding(horizontal = 24.dp), color = c.ink3, fontSize = 14.sp, textAlign = TextAlign.Center)
            // Esfera: toque para começar/interromper.
            val pulse = rememberInfiniteTransition(label = "orb")
            val breathe by pulse.animateFloat(1f, 1.08f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "b")
            val sc = when (phase) { "ouvindo" -> 1f + level * 0.35f; "falando", "pensando" -> breathe; else -> 1f }
            Box(Modifier.padding(28.dp).size(150.dp).scale(sc).clip(CircleShape)
                .background(Brush.radialGradient(listOf(c.accent, c.accent.copy(alpha = .35f))))
                .clickable {
                    when (phase) {
                        "falando" -> { DiiVoice.stop(); active = true; listen() }  // interrompe e já ouve
                        "ouvindo" -> { active = false; stopListening(); phase = "pronto" }
                        "pensando" -> {}
                        else -> { active = true; listen() }
                    }
                }, contentAlignment = Alignment.Center) {
                Icon(when (phase) { "ouvindo" -> Icons.Rounded.Mic; "falando" -> Icons.Rounded.GraphicEq; "pensando" -> Icons.Rounded.MoreHoriz; else -> Icons.Rounded.MicNone },
                    null, Modifier.size(56.dp), tint = c.bg)
            }
            Text(when (phase) { "ouvindo" -> "Ouvindo… fale à vontade"; "pensando" -> "Pensando…"; "falando" -> "Falando — toque para interromper"; else -> "Toque para conversar" },
                color = c.ink2, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 28.dp))
        }
    }
}
