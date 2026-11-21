package dev.cueopen.app.ui

import androidx.compose.foundation.layout.*
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
import dev.cueopen.app.voice.DiiVoice

/** Configurações › Voz do app: baixar a Dii (rápida ou leve), testar, velocidade, voltar para a voz do Android. */
@Composable
fun VoiceSettingsSection() {
    val c = Cue.c
    val ctx = LocalContext.current
    val st by DiiVoice.state.collectAsStateWithLifecycle()
    var tick by remember { mutableIntStateOf(0) }
    val installed = remember(st, tick) { DiiVoice.installedVariant(ctx) }
    var enabled by remember(tick) { mutableStateOf(DiiVoice.enabled(ctx)) }
    var speed by remember { mutableFloatStateOf(DiiVoice.speed(ctx)) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Dii (OpenVoiceOS, feminina) roda no próprio celular, sem internet: respostas em áudio, assistente e conversa por voz começam a falar em décimos de segundo.",
            fontSize = 12.sp, color = c.ink2)
        when (val s = st) {
            is DiiVoice.State.Downloading -> {
                Text("Baixando a voz… ${(s.progress * 100).toInt()}%", fontSize = 13.sp)
                LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth(), color = c.accent)
            }
            is DiiVoice.State.Loading -> Text("Carregando a voz…", fontSize = 13.sp, color = c.ink3)
            is DiiVoice.State.Failed -> Text(s.message, fontSize = 12.sp, color = c.err)
            else -> {}
        }
        DiiVoice.Variant.entries.forEach { v ->
            val has = DiiVoice.isInstalled(ctx, v)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(v.label, fontWeight = FontWeight.Medium)
                    Text(if (v == DiiVoice.Variant.FAST) "Mais rápida; recomendada." else "Ocupa menos espaço; um pouco mais lenta.", fontSize = 11.sp, color = c.ink3)
                }
                if (has) {
                    if (installed == v) Text("em uso", fontSize = 12.sp, color = c.ok) else TextButton({ DiiVoice.install(ctx, v); tick++ }) { Text("Usar") }
                    TextButton({ DiiVoice.uninstall(ctx, v); tick++ }) { Text("Remover", color = c.err) }
                } else OutlinedButton({ DiiVoice.install(ctx, v) }, enabled = st !is DiiVoice.State.Downloading) { Text("Baixar") }
            }
        }
        if (installed != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Usar a Dii (desligado = voz do Android)", Modifier.weight(1f), fontSize = 14.sp)
                Switch(enabled, { enabled = it; DiiVoice.setEnabled(ctx, it) })
            }
            Text("Velocidade da fala: ${"%.2f".format(speed)}×", fontSize = 13.sp)
            Slider(speed, { speed = it }, valueRange = 0.8f..1.4f, steps = 11, onValueChangeFinished = { DiiVoice.setSpeed(ctx, speed) })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ DiiVoice.speak(ctx, "Olá! Eu sou a Dii, a sua assistente. Pode falar comigo quando quiser, eu respondo na hora.") },
                    colors = ButtonDefaults.buttonColors(containerColor = c.accent)) { Text("Ouvir amostra") }
                OutlinedButton({ DiiVoice.stop() }) { Text("Parar") }
            }
        }
    }
}
