package dev.cueopen.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.cueopen.app.backup.DataTransfer
import dev.cueopen.app.backup.ImportJob
import dev.cueopen.app.ui.theme.Cue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

/** Configurações › Dados: "Exportar dados" e "Importar dados". */
@Composable
fun DataTransferSection(nav: NavController) {
    val c = Cue.c
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var exportOpen by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<Uri?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) picked = uri }

    SettingsLink(Icons.Rounded.Download, "Exportar dados", "Agentes, sessões, conectores, agenda, browser e Cofre num .zip em Downloads") { exportOpen = true }
    SettingsLink(Icons.Rounded.Upload, "Importar dados", "Restaurar um .zip exportado pelo Fluwy") {
        pick.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
    }

    if (exportOpen) {
        var pass by remember { mutableStateOf("") }
        var pass2 by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var progress by remember { mutableFloatStateOf(0f) }
        var step by remember { mutableStateOf("") }
        var err by remember { mutableStateOf<String?>(null) }
        AlertDialog(onDismissRequest = { if (!busy) exportOpen = false }, title = { Text("Exportar dados") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("O .zip leva agentes, sessões (chats), skills, agenda, gatilhos, conectores (sem senhas: entre de novo depois), configurações e permissões de sites.", fontSize = 13.sp, color = c.ink2)
                Text("Chaves de API, Pix, cartões, carteira e os logins do browser vão criptografados com a senha abaixo. Sem ela, essa parte não pode ser lida — guarde bem.", fontSize = 13.sp, color = c.ink2)
                CueField(pass, { pass = it }, "Senha do backup (mín. 6)", password = true)
                CueField(pass2, { pass2 = it }, "Repita a senha", password = true)
                if (busy) {
                    LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth(), color = c.accent)
                    Text(step, fontSize = 12.sp, color = c.ink3)
                }
                err?.let { Text(it, color = c.err, fontSize = 12.sp) }
            }
        }, confirmButton = {
            TextButton(enabled = !busy, onClick = {
                err = when {
                    pass.length < 6 -> "A senha precisa ter pelo menos 6 caracteres."
                    pass != pass2 -> "As senhas não conferem."
                    else -> null
                }
                if (err != null) return@TextButton
                busy = true
                scope.launch {
                    runCatching { DataTransfer.export(pass.toCharArray()) { f, l -> progress = f; step = l } }
                        .onSuccess { where ->
                            exportOpen = false
                            android.widget.Toast.makeText(ctx, "Dados exportados em $where", android.widget.Toast.LENGTH_LONG).show()
                        }
                        .onFailure { err = "Falha ao exportar: ${it.message}" }
                    busy = false
                }
            }) { Text("Exportar") }
        }, dismissButton = { if (!busy) TextButton({ exportOpen = false }) { Text("Cancelar") } })
    }

    picked?.let { uri ->
        var manifest by remember(uri) { mutableStateOf<DataTransfer.Manifest?>(null) }
        var err by remember(uri) { mutableStateOf<String?>(null) }
        var pass by remember(uri) { mutableStateOf("") }
        LaunchedEffect(uri) { runCatching { DataTransfer.inspect(uri) }.onSuccess { manifest = it }.onFailure { err = it.message } }
        AlertDialog(onDismissRequest = { picked = null }, title = { Text("Importar dados") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    err != null -> Text(err!!, color = c.err, fontSize = 13.sp)
                    manifest == null -> CircularProgressIndicator(color = c.accent)
                    else -> {
                        val m = manifest!!
                        val date = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale("pt", "BR")).format(java.util.Date(m.createdAt))
                        Text("Backup de $date" + (if (m.device.isNotBlank()) " · ${m.device}" else "") + (if (m.appVersion.isNotBlank()) " · v${m.appVersion}" else ""), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(listOfNotNull(
                            m.counts["agents"]?.let { "$it agente(s)" }, m.counts["sessions"]?.let { "$it sessão(ões)" },
                            m.counts["scheduled"]?.let { "$it agendamento(s)" }, m.counts["connectors"]?.let { "$it conector(es)" },
                            m.counts["browserLogins"]?.let { "$it login(s) do browser" },
                        ).joinToString(" · "), fontSize = 13.sp, color = c.ink2)
                        Text("Agentes, sessões, agenda e configurações atuais serão substituídos pelos do backup. Pix e cartões são somados aos atuais. O app reinicia no fim.", fontSize = 13.sp, color = c.ink2)
                        if (m.encrypted) CueField(pass, { pass = it }, "Senha do backup", password = true)
                    }
                }
            }
        }, confirmButton = {
            if (manifest != null && err == null) TextButton(enabled = !manifest!!.encrypted || pass.isNotEmpty(), onClick = {
                ImportJob.start(uri, pass.toCharArray())
                picked = null
                nav.navigate("importdata") { launchSingleTop = true }
            }) { Text("Importar") }
        }, dismissButton = { TextButton({ picked = null }) { Text("Cancelar") } })
    }
}

/** Tela escura com uma onda que enche de 0 a 100% enquanto os dados são importados. */
@Composable
fun ImportDataScreen(nav: NavController) {
    val st by ImportJob.status.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val shown by animateFloatAsState(st.fraction, tween(600), label = "importProgress")
    val wave = rememberInfiniteTransition(label = "wave")
    val phase by wave.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart), label = "phase")
    val accent = Color(0xFF7C5CFF)
    val accent2 = Color(0xFF2EC4C4)

    LaunchedEffect(st.done) {
        if (st.done) { delay(900); ImportJob.restartApp(ctx) }
    }
    // Voltar não interrompe a importação (ela segue no app); só sai se der erro.
    androidx.activity.compose.BackHandler(enabled = st.error == null) { }

    Box(Modifier.fillMaxSize().background(Color(0xFF07070A)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(22.dp)) {
            Canvas(Modifier.size(210.dp).clip(CircleShape)) {
                val w = size.width; val h = size.height
                drawCircle(Color(0xFF14141C))
                val level = h * (1f - shown.coerceIn(0f, 1f))
                fun wavePath(amp: Float, len: Float, shift: Float): Path = Path().apply {
                    moveTo(0f, h)
                    lineTo(0f, level)
                    var x = 0f
                    while (x <= w) { lineTo(x, level + amp * sin((x / len) * 2 * PI.toFloat() + shift)); x += 4f }
                    lineTo(w, h); close()
                }
                clipPath(Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset.Zero, Size(w, h))) }) {
                    drawPath(wavePath(10f, w * 0.9f, phase + 1.4f), accent2.copy(alpha = 0.45f))
                    drawPath(wavePath(12f, w * 0.7f, phase), Brush.verticalGradient(listOf(accent, accent2), startY = level, endY = h))
                }
                drawCircle(accent.copy(alpha = 0.6f), style = Stroke(width = 3.dp.toPx()))
            }
            Text("Importando dados", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text("${(shown * 100).toInt()}%", color = Color.White.copy(alpha = 0.9f), fontSize = 40.sp, fontWeight = FontWeight.Bold)
            Text(if (st.done) "Concluído — abrindo o app…" else st.label, color = Color.White.copy(alpha = 0.55f), fontSize = 13.sp)
            st.error?.let { e ->
                Text(e, color = Color(0xFFFF6B6B), fontSize = 14.sp, modifier = Modifier.padding(horizontal = 32.dp))
                Button(onClick = { ImportJob.reset(); nav.popBackStack() }) { Text("Voltar") }
            }
        }
    }
}
