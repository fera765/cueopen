package dev.cueopen.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.cueopen.app.ui.theme.Cue
import dev.cueopen.app.voice.AppVoice
import dev.cueopen.app.voice.PhononVoice

/** Configurações › Voz do app: cada voz com amostra (já vem no app), baixar padrão/leve, escolher por idioma, velocidade e teste. */
@Composable
fun VoiceSettingsSection() {
    val c = Cue.c
    val ctx = LocalContext.current
    val st by AppVoice.state.collectAsStateWithLifecycle()
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(st) { if (st is AppVoice.State.Ready || st is AppVoice.State.Idle) tick++ }
    var enabled by remember(tick) { mutableStateOf(AppVoice.enabled(ctx)) }
    var speed by remember { mutableFloatStateOf(AppVoice.speed(ctx)) }
    var playing by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) { onDispose { AppVoice.stopPreview() } }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Vozes que rodam no próprio celular, sem internet. A resposta começa a tocar em décimos de segundo. Em português, valores, horas e datas são lidos por extenso.",
            fontSize = 13.sp, color = c.ink2)
        when (val s = st) {
            is AppVoice.State.Downloading -> Column {
                Text("Baixando… ${(s.progress * 100).toInt()}%", fontSize = 13.sp, color = c.ink2)
                LinearProgressIndicator(progress = { s.progress }, Modifier.fillMaxWidth().padding(top = 4.dp))
            }
            is AppVoice.State.Loading -> Text("Carregando a voz…", fontSize = 13.sp, color = c.ink3)
            is AppVoice.State.Failed -> Text(s.message, fontSize = 12.sp, color = c.err)
            else -> {}
        }
        if (PhononVoice.supported) PhononCard(tick) { tick++ }
        listOf("pt" to "Português", "en" to "Inglês").forEach { (lang, title) ->
            Text(title.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = c.ink3, modifier = Modifier.padding(top = 6.dp))
            val chosen = remember(tick) { AppVoice.chosen(ctx, lang)?.id }
            AppVoice.CATALOG.filter { it.lang == lang }.forEach { v ->
                val has = remember(tick) { AppVoice.installedVariant(ctx, v.id) }
                Surface(shape = MaterialTheme.shapes.medium, color = c.surface2, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${v.name} · ${v.gender}", fontWeight = FontWeight.Medium)
                                Text(v.desc, fontSize = 12.sp, color = c.ink3)
                            }
                            IconButton({
                                if (playing == v.id) { AppVoice.stopPreview(); playing = null }
                                else { playing = v.id; AppVoice.playPreview(ctx, v.id) { playing = null } }
                            }) { Icon(if (playing == v.id) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, "Ouvir amostra") }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (has != null) {
                                Text("Instalada", fontSize = 12.sp, color = c.ok, modifier = Modifier.weight(1f))
                                if (chosen == v.id) Text("em uso", fontSize = 12.sp, color = c.ok)
                                else TextButton({ AppVoice.choose(ctx, v.id); tick++ }) { Text("Usar") }
                                TextButton({ AppVoice.uninstall(ctx, v.id); tick++ }) { Text("Remover", color = c.err) }
                            } else {
                                Spacer(Modifier.weight(1f))
                                val busy = st is AppVoice.State.Downloading
                                OutlinedButton({ AppVoice.install(ctx, v.id, AppVoice.Variant.LITE) }, enabled = !busy) { Text("Baixar ${v.liteMb} MB") }
                            }
                        }
                    }
                }
            }
        }
        if (AppVoice.hasAny(ctx)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Usar estas vozes (desligado = voz do Android)", Modifier.weight(1f), fontSize = 14.sp)
                Switch(enabled, { enabled = it; AppVoice.setEnabled(ctx, it) })
            }
            Text("Velocidade da fala: ${"%.1f".format(speed)}×", fontSize = 13.sp, color = c.ink2)
            Slider(speed, { speed = it }, valueRange = 0.8f..1.4f, steps = 11, onValueChangeFinished = { AppVoice.setSpeed(ctx, speed) })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ AppVoice.speak(ctx, "Oi! Já organizei sua agenda: a reunião é às 10h30 e o orçamento ficou em R$ 1.250,90. Quer que eu prepare a resposta?") }) { Text("Testar") }
                OutlinedButton({ AppVoice.stop() }) { Text("Parar") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
/** Voz Phonon (pt-BR, mais natural, fala em fluxo): baixar, ligar, escolher a voz. */
@Composable
private fun PhononCard(tick: Int, changed: () -> Unit) {
    val c = Cue.c
    val ctx = LocalContext.current
    val st by PhononVoice.state.collectAsStateWithLifecycle()
    LaunchedEffect(st) { if (st is AppVoice.State.Ready || st is AppVoice.State.Idle) changed() }
    val installed = remember(tick, st) { PhononVoice.isInstalled(ctx) }
    var on by remember(tick) { mutableStateOf(PhononVoice.useIt(ctx)) }
    var voice by remember(tick) { mutableStateOf(PhononVoice.voice(ctx)) }
    Text("PORTUGUÊS · PHONON", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = c.ink3, modifier = Modifier.padding(top = 6.dp))
    Surface(shape = MaterialTheme.shapes.medium, color = c.surface2, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Phonon 40m (Gradium) · a mais humana", fontWeight = FontWeight.SemiBold, color = c.ink)
            Text("Fala em fluxo (o 1º som sai quase na hora), lê números, datas e símbolos sozinha. ~75 MB. Licença CC BY-NC (uso não comercial).",
                fontSize = 12.sp, color = c.ink2)
            when (val s = st) {
                is AppVoice.State.Downloading -> LinearProgressIndicator(progress = { s.progress }, Modifier.fillMaxWidth())
                is AppVoice.State.Loading -> Text("Carregando…", fontSize = 12.sp, color = c.ink3)
                is AppVoice.State.Failed -> Text(s.message, fontSize = 12.sp, color = c.err)
                else -> {}
            }
            if (!installed) {
                OutlinedButton({ PhononVoice.install(ctx) }, enabled = st !is AppVoice.State.Downloading) { Text("Baixar ~75 MB") }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Usar no português", Modifier.weight(1f), color = c.ink)
                    Switch(on, { on = it; PhononVoice.setUse(ctx, it) })
                }
                val list = remember(tick, st) { PhononVoice.voices(ctx) }
                if (list.size > 1) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    list.take(4).forEach { v ->
                        FilterChip(voice == v, { voice = v; PhononVoice.setVoice(ctx, v) }, label = { Text(v.removePrefix("pt_br_"), fontSize = 12.sp) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ AppVoice.speak(ctx, "Oi! Já organizei sua agenda: a reunião é às 10h30 e o orçamento ficou em R$ 1.250,90. Quer que eu prepare a resposta?") }) { Text("Testar") }
                    TextButton({ PhononVoice.uninstall(ctx); changed() }) { Text("Remover", color = c.err) }
                }
            }
        }
    }
}
