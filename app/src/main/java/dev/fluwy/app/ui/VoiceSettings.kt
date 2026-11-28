package dev.fluwy.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import dev.fluwy.app.ui.theme.Fluwy
import dev.fluwy.app.voice.AppVoice
import dev.fluwy.app.voice.PhononVoice

/** Configurações › Voz do app: vozes Phonon (português e inglês), no próprio celular. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceSettingsSection() {
    val c = Fluwy.c
    val ctx = LocalContext.current
    val st by PhononVoice.state.collectAsStateWithLifecycle()
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(st) { if (st is AppVoice.State.Ready || st is AppVoice.State.Idle) tick++ }
    // As vozes de cada modelo só aparecem depois que ele carrega: carrega ao abrir a tela.
    LaunchedEffect(Unit) { PhononVoice.preload(ctx); kotlinx.coroutines.delay(2500); tick++ }
    var enabled by remember(tick) { mutableStateOf(AppVoice.enabled(ctx)) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!PhononVoice.supported) {
            Text("As vozes do app precisam de um celular 64-bit (arm64). Neste aparelho a voz do Android é usada.", fontSize = 13.sp, color = c.ink2)
            return@Column
        }
        Text("Vozes Phonon (Gradium) que rodam no próprio celular, sem internet. Falam em fluxo (o 1º som sai quase na hora) e cada frase sai no idioma certo, " +
            "com números, horas, datas, siglas, e-mails e sites lidos do jeito falado. Licença CC BY-NC (uso não comercial).", fontSize = 13.sp, color = c.ink2)
        when (val s = st) {
            is AppVoice.State.Downloading -> Column {
                Text("Baixando… ${(s.progress * 100).toInt()}%", fontSize = 13.sp, color = c.ink2)
                LinearProgressIndicator(progress = { s.progress }, Modifier.fillMaxWidth().padding(top = 4.dp))
            }
            is AppVoice.State.Loading -> Text("Carregando a voz…", fontSize = 13.sp, color = c.ink3)
            is AppVoice.State.Failed -> Text(s.message, fontSize = 12.sp, color = c.err)
            else -> {}
        }
        PhononVoice.MODELS.forEach { m ->
            Text(m.title.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = c.ink3, modifier = Modifier.padding(top = 6.dp))
            val installed = remember(tick, st) { PhononVoice.isInstalled(ctx, m.lang) }
            var voice by remember(tick) { mutableStateOf(PhononVoice.voice(ctx, m.lang)) }
            Surface(shape = MaterialTheme.shapes.medium, color = c.surface2, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Phonon 40m · ${m.title}", fontWeight = FontWeight.Medium)
                    if (!installed) {
                        OutlinedButton({ PhononVoice.install(ctx, m.lang) }, enabled = st !is AppVoice.State.Downloading) { Text("Baixar ~75 MB") }
                    } else {
                        val list = remember(tick, st) { PhononVoice.voices(m.lang) }
                        if (list.size > 1) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            list.forEach { v ->
                                FilterChip(voice == v, { voice = v; PhononVoice.setVoice(ctx, m.lang, v) }, label = { Text(v.substringAfterLast('_'), fontSize = 12.sp) })
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Instalada", fontSize = 12.sp, color = c.ok, modifier = Modifier.weight(1f))
                            IconButton({ AppVoice.speak(ctx, if (m.lang == "pt") "Oi! Sua reunião com a equipe de TI é às 10h30 e o orçamento ficou em R$ 1.250,90. Me manda o PDF no e-mail joao@empresa.com.br?"
                                else "Hi! Your meeting with the IT team is at 10:30 AM and the budget is $1,250.90. Can you send the PDF to john@company.com?") }) { Icon(Icons.Rounded.PlayArrow, "Testar") }
                            TextButton({ PhononVoice.uninstall(ctx, m.lang); tick++ }) { Text("Remover", color = c.err) }
                        }
                    }
                }
            }
        }
        if (PhononVoice.hasAny(ctx)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Usar estas vozes (desligado = voz do Android)", Modifier.weight(1f), fontSize = 14.sp)
                Switch(enabled, { enabled = it; AppVoice.setEnabled(ctx, it) })
            }
            OutlinedButton({ AppVoice.stop() }) { Icon(Icons.Rounded.Stop, null); Text(" Parar") }
        }
    }
}
