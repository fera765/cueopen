package dev.fluwy.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.fluwy.app.FluwyApp
import dev.fluwy.app.data.ThemeMode
import dev.fluwy.app.sandbox.Sandbox
import dev.fluwy.app.ui.theme.Fluwy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** Onboarding sem login: nome e instalação opcional do sandbox; provedor/modelo ficam em Configurações. */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val app = FluwyApp.instance
    val st by app.store.state.collectAsStateWithLifecycle()
    var page by remember { mutableIntStateOf(0) }
    val c = Fluwy.c
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding().imePadding().padding(24.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(3) { i -> Box(Modifier.height(4.dp).weight(1f).clip(CircleShape).background(if (i <= page) c.ink else c.line)) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center) {
            when (page) {
                0 -> {
                    Row { agentColors.take(4).forEachIndexed { i, col -> AgentFace(Color(col), agentFaces[i], 64.dp, Modifier.offset(x = (-14 * i).dp)) } }
                    Spacer(Modifier.height(24.dp))
                    Text("Seu agente, com um computador de verdade.", style = MaterialTheme.typography.headlineLarge)
                    Spacer(Modifier.height(12.dp))
                    Text("O Fluwy Bot planeja, pesquisa no browser, escreve e roda código num Linux dentro do seu celular — e entrega arquivos prontos. Sem conta, sem login.",
                        style = MaterialTheme.typography.bodyLarge, color = c.ink2)
                    Spacer(Modifier.height(8.dp))
                    Text("Você pode escolher provedor e modelo depois, em Configurações.", style = MaterialTheme.typography.bodyMedium, color = c.ink3)
                    Spacer(Modifier.height(20.dp))
                    FluwyField(st.settings.userName, { v -> app.store.updateSettings { it.copy(userName = v) } }, "Como devo te chamar?")
                }
                2 -> OnboardingPermissions()
                else -> {
                    val state by app.sandbox.state.collectAsStateWithLifecycle()
                    val log by app.sandbox.log.collectAsStateWithLifecycle()
                    Text("Instale o computador do agente", style = MaterialTheme.typography.headlineLarge)
                    Spacer(Modifier.height(12.dp))
                    Text("Um Linux Alpine roda localmente no aparelho. Leva 1–3 minutos com Wi-Fi.", style = MaterialTheme.typography.bodyLarge, color = c.ink2)
                    Spacer(Modifier.height(20.dp))
                    when (state) {
                        Sandbox.State.READY -> Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.CheckCircle, null, tint = c.ok); Spacer(Modifier.width(8.dp)); Text("Pronto!") }
                        Sandbox.State.INSTALLING -> { LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.accent); Spacer(Modifier.height(8.dp)); Text(log.lines().lastOrNull { it.isNotBlank() }.orEmpty(), fontSize = 12.sp, color = c.ink3) }
                        else -> PillButton("Instalar agora", Icons.Rounded.Download, modifier = Modifier.fillMaxWidth()) { scope.launch { app.sandbox.install(st.settings.sandboxPackages) } }
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (page > 0) TextButton({ page-- }) { Text("Voltar", color = c.ink2) }
            Spacer(Modifier.weight(1f))
            if (page >= 1) TextButton({ app.store.update { it.copy(onboarded = true) }; onDone() }) { Text("Pular", color = c.ink2) }
            PillButton(when (page) { 0 -> "Continuar"; 1 -> "Começar"; else -> "Concluir" }) {
                // "Começar" leva às permissões: o app já sai configurado para trabalhar em segundo plano.
                if (page < 2) page++ else { app.store.update { it.copy(onboarded = true) }; onDone() }
            }
        }
    }
}

/**
 * Último passo da instalação: pede, em sequência, o que o agente precisa para trabalhar com o app fechado.
 * Cada pedido abre a tela do Android correspondente; ao voltar, o próximo pendente abre sozinho (uma tentativa cada).
 */
@Composable
private fun OnboardingPermissions() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val c = Fluwy.c
    val pkg = android.net.Uri.parse("package:" + ctx.packageName)
    var tick by remember { mutableIntStateOf(0) }
    var walking by remember { mutableStateOf(true) }
    val attempted = remember { mutableSetOf<String>() }
    val notifLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { tick++ }
    fun open(vararg intents: android.content.Intent) {
        for (i in intents) { if (runCatching { ctx.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return }
    }
    val appDetails = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
    data class Perm(val id: String, val title: String, val desc: String, val granted: () -> Boolean, val request: () -> Unit)
    val perms = remember {
        listOf(
            Perm("notif", "Notificações", "Avisos de tarefas concluídas e a notificação fixa que mantém o agente rodando.",
                { android.os.Build.VERSION.SDK_INT < 33 || androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED },
                { if (android.os.Build.VERSION.SDK_INT >= 33) runCatching { notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS) } }),
            Perm("battery", "Rodar em segundo plano sem restrição", "Escolha \"Permitir\" / \"Sem restrições\": sem isso o Android para o agente quando o app sai da tela.",
                { runCatching { ctx.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName) }.getOrDefault(false) },
                { open(android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg), android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS), appDetails) }),
            Perm("alarm", "Alarmes e lembretes", "Permite acordar o agente na hora certa dos ciclos agendados (ex.: uma vez por dia).",
                { android.os.Build.VERSION.SDK_INT < 31 || runCatching { ctx.getSystemService(android.app.AlarmManager::class.java).canScheduleExactAlarms() }.getOrDefault(false) },
                { if (android.os.Build.VERSION.SDK_INT >= 31) open(android.content.Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg), appDetails) }),
            Perm("overlay", "Sobrepor a outros apps", "Reserva para o browser do agente funcionar com o app fechado em aparelhos que bloqueiam a tela virtual.",
                { runCatching { android.provider.Settings.canDrawOverlays(ctx) }.getOrDefault(false) },
                { open(android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg), appDetails) }),
        )
    }
    LaunchedEffect(Unit) {
        delay(500)
        while (true) {
            tick++
            if (walking && dev.fluwy.app.browser.OffscreenStage.foreground) {
                val next = perms.firstOrNull { !it.granted() && it.id !in attempted }
                if (next == null) walking = false else { attempted += next.id; next.request(); delay(1_500) }
            }
            delay(700)
        }
    }
    Text("Permissões para trabalhar sozinho", style = MaterialTheme.typography.headlineLarge)
    Spacer(Modifier.height(12.dp))
    Text("O agente continua as tarefas com o app fechado. Em cada tela do Android, escolha sempre a opção que permite o tempo todo.", style = MaterialTheme.typography.bodyLarge, color = c.ink2)
    Spacer(Modifier.height(16.dp))
    perms.forEach { p ->
        val ok = remember(tick) { p.granted() }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.title, style = MaterialTheme.typography.titleMedium)
                Text(p.desc, style = MaterialTheme.typography.bodyMedium, color = c.ink3)
            }
            Spacer(Modifier.width(8.dp))
            if (ok) Icon(Icons.Rounded.CheckCircle, "Permitido", tint = c.ok)
            else TextButton({ walking = false; attempted += p.id; p.request() }) { Text("Permitir", color = c.accent) }
        }
    }
}
