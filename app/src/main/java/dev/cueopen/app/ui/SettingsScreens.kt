package dev.cueopen.app.ui

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
import dev.cueopen.app.CueApp
import dev.cueopen.app.data.ThemeMode
import dev.cueopen.app.sandbox.Sandbox
import dev.cueopen.app.ui.theme.Cue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(nav: NavController) {
    val app = CueApp.instance
    val scope = rememberCoroutineScope()
    val settingsFlow = remember(app) {
        app.store.state.map { it.settings to it.memories.size }.distinctUntilChanged()
    }
    val initialSettings = remember(app) {
        app.store.state.value.let { it.settings to it.memories.size }
    }
    val settingsSnapshot by settingsFlow.collectAsStateWithLifecycle(initialSettings)
    val s = settingsSnapshot.first
    val c = Cue.c
    var confirmClear by remember { mutableStateOf(false) }
    Scaffold(containerColor = c.bg, topBar = { CueTopBar("Configurações", { nav.popBackStack() }) }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Hero
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(c.ink).padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                AgentFace(c.accent, "wink", 44.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("Fluwy Bot", color = c.bg, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Text("Agente local · sem login · v3", color = c.bg.copy(alpha = .7f), fontSize = 12.sp)
                }
            }
            SectionLabel("Você")
            CueField(s.userName, { v -> app.store.updateSettings { it.copy(userName = v) } }, "Seu nome")
            SectionLabel("Provedor de modelos")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val isOR = s.baseUrl.contains("openrouter", ignoreCase = true)
                Chip("OpenRouter", isOR) { app.store.updateSettings { it.copy(baseUrl = "https://openrouter.ai/api/v1") } }
                Chip("Personalizado", !isOR) { app.store.updateSettings { it.copy(baseUrl = if (it.baseUrl.contains("openrouter", true)) "https://" else it.baseUrl) } }
            }
            Text("OpenRouter é o exemplo recomendado. Personalizado aceita endpoints OpenAI-compatible; por exemplo, OpenCode Go usa https://opencode.ai/inference/go/openai/v1. O catálogo é carregado do provedor; famílias OpenCode que exigem outra API são ocultadas, e function calling depende do modelo escolhido.", fontSize = 12.sp, color = Cue.c.ink2)
            SectionLabel("Credenciais")
            CueField(s.apiKey, { v -> app.store.updateSettings { it.copy(apiKey = v.trim()) } }, "Chave da API", password = true)
            CueField(s.baseUrl, { v -> app.store.updateSettings { it.copy(baseUrl = v.trim()) } }, "Base URL (compatível OpenAI)")
            ModelPicker(s.model, { v -> app.store.updateSettings { it.copy(model = v) } }, "Modelo do agente", baseUrl = s.baseUrl, apiKey = s.apiKey)
            ModelPicker(s.fastModel, { v -> app.store.updateSettings { it.copy(fastModel = v) } }, "Modelo rápido (títulos e resumos de contexto)", baseUrl = s.baseUrl, apiKey = s.apiKey)
            ModelPicker(dev.cueopen.app.trading.SignalEvaluationAdapter.resolveModel(s.accuracyModel.ifBlank { "typesafe/jev-router" }, s.baseUrl), { v -> app.store.updateSettings { it.copy(accuracyModel = v) } }, "Modelo avaliador de acurácia (day trade)", baseUrl = s.baseUrl, apiKey = s.apiKey, includeSystemOne = true)
            Text("Jev usa a API System One de decisões tipadas. No OpenCode, Jev 1.13 pertence ao catálogo Console, não ao Go, e pode exigir créditos; Jev Free depende da política de privacidade da conta. Outros modelos recebem um pedido JSON estruturado, sem equivalência de calibração. Dados ausentes, resposta inválida ou confiança abaixo de 70% não liberam uma ordem.", fontSize = 12.sp, color = Cue.c.ink2)
            SectionLabel("TypeSafe Jev")
            ToggleRow("Orquestração Jev", "Cada passo leva só as ferramentas, conectores, skills, memórias e knowledge relevantes — menos tokens de entrada", s.jevOrchestration) { v -> app.store.updateSettings { it.copy(jevOrchestration = v) } }
            ToggleRow("Roteamento Jev Router", "O Jev Router escolhe o modelo e o esforço de raciocínio de cada passo (qualidade × custo). Só OpenRouter; o modelo definido no Agent tem prioridade", s.jevRouting) { v -> app.store.updateSettings { it.copy(jevRouting = v) } }
            ToggleRow("Desativar thinking", "Zero raciocínio estendido — respostas mais rápidas", s.disableThinking) { v -> app.store.updateSettings { it.copy(disableThinking = v) } }
            Text("Passos máximos por execução: ${s.maxSteps}", fontSize = 14.sp)
            Slider(s.maxSteps.toFloat(), { v -> app.store.updateSettings { it.copy(maxSteps = v.toInt()) } }, valueRange = 10f..1000f,
                colors = SliderDefaults.colors(thumbColor = c.ink, activeTrackColor = c.ink))
            Text("Orçamento de contexto antes de compactar: ${s.contextBudget / 1000}k tokens", fontSize = 14.sp)
            Slider(s.contextBudget.toFloat(), { v -> app.store.updateSettings { it.copy(contextBudget = (v / 1000).toInt() * 1000) } }, valueRange = 8000f..120000f,
                colors = SliderDefaults.colors(thumbColor = c.ink, activeTrackColor = c.ink))
            SettingsLink(Icons.Rounded.Psychology, "Memória de longo prazo", "${settingsSnapshot.second} itens") { nav.navigate("memory") }
            SectionLabel("Carteira")
            SettingsLink(Icons.Rounded.AccountBalanceWallet, "Carteira Solana", "Criar ou restaurar carteira local e consultar saldo") { nav.navigate("wallet") }
            // Chave Demo gratuita e opcional da CoinGecko para o mercado (evita rate limit). Vazio = keyless.
            val marketPrefs = remember { nav.context.getSharedPreferences("solana_wallet_settings", android.content.Context.MODE_PRIVATE) }
            var geckoKey by remember { mutableStateOf(marketPrefs.getString("gecko_api_key", "").orEmpty()) }
            CueField(geckoKey, { v ->
                geckoKey = v.trim()
                marketPrefs.edit().putString("gecko_api_key", geckoKey).apply()
                dev.cueopen.app.wallet.MarketApiConfig.geckoApiKey = geckoKey.ifBlank { null }
            }, "Chave Demo CoinGecko (opcional, grátis)", password = true)
            Text("O mercado funciona sem chave (com cache e limites automáticos). Se você bater em limite de requisições com frequência, crie uma chave Demo gratuita em coingecko.com e cole aqui para ter quota dedicada.", fontSize = 12.sp, color = Cue.c.ink2)
            SectionLabel("Aparência")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(ThemeMode.SYSTEM to "Sistema", ThemeMode.LIGHT to "Claro", ThemeMode.DARK to "Escuro").forEach { (m, l) ->
                    Chip(l, s.theme == m) { app.store.updateSettings { it.copy(theme = m) } }
                }
            }
            SectionLabel("Notificações")
            ToggleRow("Avisar ao concluir tarefas agendadas", "", s.notifyOnDone) { v -> app.store.updateSettings { it.copy(notifyOnDone = v) } }
            SectionLabel("Rede")
            SettingsLink(Icons.Rounded.SettingsEthernet, "Proxy", "Perfis e seleções independentes para Browser, Terminal e Agent/API") { nav.navigate("proxy") }
            SectionLabel("Computador")
            SettingsLink(Icons.Rounded.Computer, "Sandbox Linux", "Instalar, pacotes, reinstalar") { nav.navigate("sandbox") }
            SettingsLink(Icons.Rounded.Terminal, "Abrir terminal", "Shell interativo no Alpine") { nav.navigate("terminal") }
            SettingsLink(Icons.Rounded.FolderOpen, "Arquivos", "/root no sandbox") { nav.navigate("files") }
            var crash by remember { mutableStateOf<String?>(null) }
            var logBytes by remember { mutableLongStateOf(-1L) }
            var logTail by remember { mutableStateOf("") }
            var logRefresh by remember { mutableIntStateOf(0) }
            val diagnosticContext = remember(nav) { nav.context.applicationContext }
            LaunchedEffect(logRefresh) {
                val diagnosticSnapshot = withContext(Dispatchers.IO) {
                    val crashText = java.io.File(app.filesDir, "last_crash.txt").takeIf { it.exists() }?.readText()
                    val bytes = dev.cueopen.app.diag.ErrorLog.sizeBytes(diagnosticContext)
                    val tail = dev.cueopen.app.diag.ErrorLog.readAll(diagnosticContext).takeLast(2500)
                    Triple(crashText, bytes, tail)
                }
                crash = diagnosticSnapshot.first
                logBytes = diagnosticSnapshot.second
                logTail = diagnosticSnapshot.third
            }
            val lastCrash = crash
            if (lastCrash != null) {
                SectionLabel("Último erro")
                Text(lastCrash.take(3000), Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF0F1115)).padding(12.dp),
                    color = Color(0xFFFF8A8A), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            }
            SectionLabel("Logs de erros")
            Text(
                "Guarda as FALHAS reais do app (ferramentas do agente, browser, terminal, swaps, mercado, crashes) — nunca o conteúdo das conversas. Baixe e envie aqui no Claude para eu corrigir.",
                fontSize = 12.sp, color = c.ink2
            )
            if (logBytes < 0L) {
                CueCard { Text("Carregando logs…", color = c.ink2, fontSize = 13.sp) }
            } else if (logBytes == 0L) {
                CueCard { Text("Nenhum erro registrado até agora. 🎉", color = c.ink2, fontSize = 13.sp) }
            } else {
                Text("${logBytes / 1024 + 1} KB registrados", fontSize = 12.sp, color = c.ink3)
                if (logTail.isNotBlank()) Text(
                    logTail, Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF0F1115)).padding(12.dp),
                    color = Color(0xFFFFB38A), fontFamily = FontFamily.Monospace, fontSize = 10.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(modifier = Modifier.weight(1f), onClick = {
                        runCatching {
                            val f = dev.cueopen.app.diag.ErrorLog.file(nav.context)
                            val uri = androidx.core.content.FileProvider.getUriForFile(nav.context, nav.context.packageName + ".files", f)
                            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                putExtra(android.content.Intent.EXTRA_SUBJECT, "Fluwy Bot — log de erros")
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            nav.context.startActivity(android.content.Intent.createChooser(send, "Baixar / enviar log de erros").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }) { Icon(Icons.Rounded.Download, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Baixar / enviar") }
                    OutlinedButton(modifier = Modifier.weight(1f), onClick = {
                        val context = nav.context.applicationContext
                        scope.launch {
                            val text = withContext(Dispatchers.IO) { dev.cueopen.app.diag.ErrorLog.readAll(context) }
                            val clip = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clip.setPrimaryClip(android.content.ClipData.newPlainText("Fluwy Bot log de erros", text))
                        }
                    }) { Icon(Icons.Rounded.ContentCopy, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Copiar") }
                }
                TextButton(onClick = {
                    val context = nav.context.applicationContext
                    scope.launch {
                        withContext(Dispatchers.IO) { dev.cueopen.app.diag.ErrorLog.clear(context) }
                        logRefresh++
                    }
                }) {
                    Text("Limpar log", color = c.err)
                }
            }
            SectionLabel("Dados")
            SettingsLink(Icons.Rounded.DeleteSweep, "Apagar todas as tarefas", "Agents, skills e knowledge são mantidos", tint = c.err) { confirmClear = true }
            Spacer(Modifier.height(12.dp))
            Text("Fluwy Bot · sem login, tudo local no seu aparelho.",
                fontSize = 12.sp, color = c.ink3)
            Spacer(Modifier.height(24.dp))
        }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("Apagar tarefas?") },
        text = { Text("Remove o histórico de todas as tarefas. Arquivos do workspace continuam em /root/workspace.") },
        confirmButton = { TextButton({ app.store.update { it.copy(sessions = emptyList()) }; confirmClear = false }) { Text("Apagar", color = c.err) } },
        dismissButton = { TextButton({ confirmClear = false }) { Text("Cancelar") } })
}

@Composable
private fun SettingsLink(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, tint: Color = Cue.c.ink, onClick: () -> Unit) {
    val c = Cue.c
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface).clickable(onClick = onClick).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint); Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.Medium, color = tint); Text(sub, fontSize = 13.sp, color = c.ink2) }
        Icon(Icons.Rounded.ChevronRight, null, tint = c.ink3)
    }
}

@Composable
fun SandboxScreen(nav: NavController) {
    val app = CueApp.instance
    val state by app.sandbox.state.collectAsStateWithLifecycle()
    val log by app.sandbox.log.collectAsStateWithLifecycle()
    val st by app.store.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val c = Cue.c
    var confirm by remember { mutableStateOf(false) }
    Scaffold(containerColor = c.bg, topBar = { CueTopBar("Computador do agente", { nav.popBackStack() }) }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CueCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(52.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF0F1115)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Terminal, null, tint = Color(0xFFFF8A63))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Linux local (Alpine 3.21)", fontWeight = FontWeight.SemiBold)
                        Text(when (state) {
                            Sandbox.State.READY -> "Pronto"; Sandbox.State.INSTALLING -> "Instalando…"
                            Sandbox.State.ERROR -> "Erro na instalação"; Sandbox.State.NOT_INSTALLED -> "Não instalado"
                        }, color = when (state) { Sandbox.State.READY -> c.ok; Sandbox.State.ERROR -> c.err; else -> c.ink2 }, fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text("Roda no próprio celular via proot — sem root, sem nuvem. O agente usa este computador para executar código, instalar pacotes e servir sites. ~150 MB após a instalação.",
                    fontSize = 13.sp, color = c.ink2)
            }
            CueField(st.settings.sandboxPackages, { v -> app.store.updateSettings { it.copy(sandboxPackages = v) } }, "Pacotes iniciais (apk add)")
            when (state) {
                Sandbox.State.NOT_INSTALLED, Sandbox.State.ERROR -> PillButton("Instalar", Icons.Rounded.Download, modifier = Modifier.fillMaxWidth()) {
                    scope.launch { app.sandbox.install(st.settings.sandboxPackages) }
                }
                Sandbox.State.INSTALLING -> LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.accent)
                Sandbox.State.READY -> {
                    PillButton("Abrir terminal", Icons.Rounded.Terminal, modifier = Modifier.fillMaxWidth()) { nav.navigate("terminal") }
                    PillButton("Reinstalar", Icons.Rounded.Refresh, primary = false, modifier = Modifier.fillMaxWidth()) { confirm = true }
                    PillButton("Parar servidor de terminal", Icons.Rounded.Stop, primary = false, modifier = Modifier.fillMaxWidth()) { app.sandbox.stopAxs() }
                }
            }
            if (log.isNotBlank()) {
                Text(log.takeLast(6000), Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF0F1115)).padding(12.dp),
                    color = Color(0xFFE6E6E6), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Reinstalar sandbox?") },
        text = { Text("Apaga o sistema Alpine (pacotes instalados). Seus arquivos em /root são mantidos.") },
        confirmButton = { TextButton({ confirm = false; app.sandbox.uninstall(); scope.launch { app.sandbox.install(st.settings.sandboxPackages) } }) { Text("Reinstalar") } },
        dismissButton = { TextButton({ confirm = false }) { Text("Cancelar") } })
}

/** Onboarding sem login: nome e instalação opcional do sandbox; provedor/modelo ficam em Configurações. */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val app = CueApp.instance
    val st by app.store.state.collectAsStateWithLifecycle()
    var page by remember { mutableIntStateOf(0) }
    val c = Cue.c
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding().imePadding().padding(24.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(2) { i -> Box(Modifier.height(4.dp).weight(1f).clip(CircleShape).background(if (i <= page) c.ink else c.line)) }
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
                    CueField(st.settings.userName, { v -> app.store.updateSettings { it.copy(userName = v) } }, "Como devo te chamar?")
                }
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
            if (page == 1) TextButton({ app.store.update { it.copy(onboarded = true) }; onDone() }) { Text("Pular", color = c.ink2) }
            PillButton(if (page < 1) "Continuar" else "Começar") {
                if (page < 1) page++ else { app.store.update { it.copy(onboarded = true) }; onDone() }
            }
        }
    }
}

/** Replay: reproduz a tarefa passo a passo, como o replay compartilhável do Cue. */
@Composable
fun ReplayScreen(nav: NavController, sessionId: String) {
    val app = CueApp.instance
    val session = app.store.session(sessionId) ?: return
    var shown by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(true) }
    var speed by remember { mutableFloatStateOf(1f) }
    val c = Cue.c
    val replayList = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(playing, speed) {
        while (playing && shown < session.items.size) { delay((900 / speed).toLong()); shown++ }
        if (shown >= session.items.size) playing = false
    }
    // Mantém o item recém-revelado visível durante a reprodução e volta ao início ao reiniciar.
    LaunchedEffect(shown, session.items.size) {
        if (shown > 0) replayList.animateScrollToItem((shown - 1).coerceAtMost(session.items.lastIndex))
        else if (session.items.isNotEmpty()) replayList.scrollToItem(0)
    }
    Scaffold(containerColor = c.bg, topBar = { CueTopBar("Replay · ${session.title}", { nav.popBackStack() }) }, bottomBar = {
        Column(Modifier.navigationBarsPadding().padding(16.dp)) {
            val p by animateFloatAsState(if (session.items.isEmpty()) 1f else shown / session.items.size.toFloat(), label = "p")
            LinearProgressIndicator(progress = { p }, Modifier.fillMaxWidth().height(4.dp).clip(CircleShape), color = c.accent, trackColor = c.surface2)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RoundIcon(Icons.Rounded.Replay, "Reiniciar") { shown = 0; playing = true }
                RoundIcon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play") { if (shown >= session.items.size) shown = 0; playing = !playing }
                RoundIcon(Icons.Rounded.SkipNext, "Fim") { shown = session.items.size; playing = false }
                Spacer(Modifier.weight(1f))
                listOf(1f, 2f, 4f).forEach { sp -> Chip("${sp.toInt()}x", speed == sp) { speed = sp } }
            }
        }
    }) { pad ->
        LazyColumn(state = replayList, modifier = Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val persona = ChatPersona.of(app.store.state.value.agents.firstOrNull { it.id == session.agentId })
            itemsIndexed(session.items.take(shown), key = { _, it -> it.id }) { _, item ->
                androidx.compose.runtime.CompositionLocalProvider(LocalChatPersona provides persona) { TimelineItem(item, onOpenComputer = {}) }
            }
        }
    }
}

@Composable
fun ProxyScreen(nav: NavController) {
    val app = CueApp.instance
    val c = Cue.c
    val scope = rememberCoroutineScope()
    val state by app.store.state.collectAsStateWithLifecycle()
    val browserProxyStatus by app.browserProxyStatus.collectAsStateWithLifecycle()
    var profiles by remember { mutableStateOf(app.proxyStore.profiles()) }
    var rotation by remember { mutableStateOf(app.proxyStore.rotationSettings()) }
    var intervalText by remember(rotation.intervalMinutes) { mutableStateOf(rotation.intervalMinutes.toString()) }
    var editing by remember { mutableStateOf<dev.cueopen.app.proxy.ProxyProfile?>(null) }
    fun saveRotation(next: dev.cueopen.app.proxy.ProxyRotationSettings) {
        rotation = next
        intervalText = next.intervalMinutes.toString()
        app.configureProxyRotation(next)
    }
    fun selectProxy(scope: dev.cueopen.app.proxy.ProxyScope, id: String?) {
        app.store.updateSettings { s -> when (scope) {
            dev.cueopen.app.proxy.ProxyScope.BROWSER -> s.copy(browserProxyId = id)
            dev.cueopen.app.proxy.ProxyScope.TERMINAL -> s.copy(terminalProxyId = id)
            dev.cueopen.app.proxy.ProxyScope.AGENT_API -> s.copy(agentApiProxyId = id)
        } }
        app.connectorDebugLog.recordEvent("proxy", "selection", "scope=${scope.name} profile_id=${id ?: "direct"}")
        if (scope == dev.cueopen.app.proxy.ProxyScope.BROWSER) app.applyBrowserProxy()
    }
    fun save(p: dev.cueopen.app.proxy.ProxyProfile) {
        profiles = (profiles.filterNot { it.id == p.id } + p)
        app.proxyStore.saveProfiles(profiles)
        app.connectorDebugLog.recordEvent("proxy", "profile", "saved profile_id=${p.id}")
        editing = null
        app.applyBrowserProxy()
    }
    Scaffold(containerColor = c.bg, topBar = { CueTopBar("Proxy", { nav.popBackStack() }) }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Selecione um perfil por escopo. O padrão é direto, sem proxy.", color = c.ink2, fontSize = 13.sp)
            Text("Browser: o ProxyController afeta todos os WebViews deste processo, não um BrowserHost individual. WebView não recebe credenciais.", color = c.ink2, fontSize = 12.sp)
            if (!androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.PROXY_OVERRIDE))
                Text("Este WebView não oferece ProxyController; a seleção Browser não pode ser aplicada nesta versão do sistema.", color = c.err, fontSize = 12.sp)
            Text("Status Browser: $browserProxyStatus", color = if (browserProxyStatus.startsWith("Falha") || browserProxyStatus.startsWith("Indisponível") || browserProxyStatus.startsWith("Navegação bloqueada")) c.err else c.ink2, fontSize = 12.sp)
            Text("Terminal: aplica variáveis de proxy ao shell e ao bootstrap do Sandbox; HTTP/SOCKS, sem credenciais. O suporte depende do programa de linha de comando.", color = c.ink2, fontSize = 12.sp)
            Text("Agent/API: modelo e próximo login/reconnect IQ Option. HTTP CONNECT autenticado usa Basic para o proxy em conexão não cifrada; use apenas proxy/rede confiáveis. SOCKS autenticado não é suportado. WebSocket ativo exige reconexão.", color = c.ink2, fontSize = 12.sp)
            ProxyScopeRow("Browser", state.settings.browserProxyId, profiles, dev.cueopen.app.proxy.ProxyScope.BROWSER) { id -> selectProxy(dev.cueopen.app.proxy.ProxyScope.BROWSER, id) }
            ProxyScopeRow("Terminal", state.settings.terminalProxyId, profiles, dev.cueopen.app.proxy.ProxyScope.TERMINAL) { id -> selectProxy(dev.cueopen.app.proxy.ProxyScope.TERMINAL, id) }
            ProxyScopeRow("Agent/API", state.settings.agentApiProxyId, profiles, dev.cueopen.app.proxy.ProxyScope.AGENT_API) { id -> selectProxy(dev.cueopen.app.proxy.ProxyScope.AGENT_API, id) }
            listOf("Browser" to state.settings.browserProxyId to dev.cueopen.app.proxy.ProxyScope.BROWSER, "Terminal" to state.settings.terminalProxyId to dev.cueopen.app.proxy.ProxyScope.TERMINAL, "Agent/API" to state.settings.agentApiProxyId to dev.cueopen.app.proxy.ProxyScope.AGENT_API).forEach { entry ->
                val selectedId = entry.first.second
                val selected = selectedId?.let { id -> profiles.firstOrNull { it.id == id } }
                if (selectedId != null && selected == null) Text("${entry.first.first}: perfil selecionado ausente; uso bloqueado até escolher outro perfil ou Direto.", color = c.err, fontSize = 12.sp)
                if (selected != null) {
                    val check = dev.cueopen.app.proxy.ProxyValidator.validate(selected, entry.second)
                    if (!check.valid) Text("${entry.first.first}: ${check.message}", color = c.err, fontSize = 12.sp)
                }
            }
            CueCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Rotação automática", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text("Desligada por padrão. Quando habilitada, alterna sem repetição entre perfis válidos e não altera conexões long-lived já abertas.", color = c.ink2, fontSize = 12.sp)
                    ToggleRow("Habilitar rotação", "A rotação começa após o intervalo definido.", rotation.enabled) { enabled ->
                        saveRotation(rotation.copy(enabled = enabled))
                    }
                    OutlinedTextField(
                        intervalText,
                        { intervalText = it.filter(Char::isDigit).take(4) },
                        label = { Text("Intervalo em minutos (1–1440)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(onClick = {
                        val minutes = intervalText.toIntOrNull()?.takeIf { it in 1..1_440 }
                        if (minutes == null) android.widget.Toast.makeText(nav.context, "Informe um intervalo entre 1 e 1440 minutos.", android.widget.Toast.LENGTH_SHORT).show()
                        else saveRotation(rotation.copy(intervalMinutes = minutes))
                    }) { Text("Aplicar intervalo") }
                    ProxyRotationPool("Pool Browser", dev.cueopen.app.proxy.ProxyScope.BROWSER, profiles, rotation.browserProfileIds) { ids ->
                        saveRotation(rotation.copy(browserProfileIds = ids))
                    }
                    ProxyRotationPool("Pool Terminal", dev.cueopen.app.proxy.ProxyScope.TERMINAL, profiles, rotation.terminalProfileIds) { ids ->
                        saveRotation(rotation.copy(terminalProfileIds = ids))
                    }
                    ProxyRotationPool("Pool Agent/API", dev.cueopen.app.proxy.ProxyScope.AGENT_API, profiles, rotation.agentApiProfileIds) { ids ->
                        saveRotation(rotation.copy(agentApiProfileIds = ids))
                    }
                    Text("A troca de perfil vale para novas chamadas HTTP/WebSocket e novos comandos do Terminal. WebSockets ativos da IQ Option não são reiniciados automaticamente; reconectar é uma ação separada.", color = c.ink3, fontSize = 11.sp)
                }
            }
            profiles.forEach { p ->
                CueCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(p.name, fontWeight = FontWeight.SemiBold); Text("${p.scheme.name} ${p.host}:${p.port}" + if (p.hasCredentials) " · credencial protegida" else "", fontSize = 12.sp, color = c.ink2) }
                        TextButton({ editing = p }) { Text("Editar") }
                        TextButton({
                            profiles = profiles.filterNot { it.id == p.id }
                            app.proxyStore.saveProfiles(profiles)
                            app.proxyStore.saveCredentials(p.id, null)
                            app.store.updateSettings { it.copy(browserProxyId = if (it.browserProxyId == p.id) null else it.browserProxyId, terminalProxyId = if (it.terminalProxyId == p.id) null else it.terminalProxyId, agentApiProxyId = if (it.agentApiProxyId == p.id) null else it.agentApiProxyId) }
                            saveRotation(rotation.copy(
                                browserProfileIds = rotation.browserProfileIds - p.id,
                                terminalProfileIds = rotation.terminalProfileIds - p.id,
                                agentApiProfileIds = rotation.agentApiProfileIds - p.id,
                            ))
                            app.connectorDebugLog.recordEvent("proxy", "profile", "removed profile_id=${p.id}")
                            app.applyBrowserProxy()
                        }) { Text("Remover", color = c.err) }
                    }
                }
            }
            PillButton("Adicionar perfil", Icons.Rounded.Add, modifier = Modifier.fillMaxWidth()) { editing = dev.cueopen.app.proxy.ProxyProfile() }
            ConnectorDebugSection("proxy")
        }
    }
    editing?.let { p -> ProxyEditor(p, { save(it) }, { editing = null }) }
}

@Composable
private fun ProxyRotationPool(
    title: String,
    scope: dev.cueopen.app.proxy.ProxyScope,
    profiles: List<dev.cueopen.app.proxy.ProxyProfile>,
    selectedIds: List<String>,
    onSelectionChange: (List<String>) -> Unit,
) {
    val c = Cue.c
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, fontWeight = FontWeight.Medium, fontSize = 13.sp)
        val eligible = profiles.filter { dev.cueopen.app.proxy.ProxyValidator.validate(it, scope).valid }
        if (eligible.isEmpty()) Text("Nenhum perfil válido para este escopo.", color = c.ink3, fontSize = 11.sp)
        eligible.forEach { profile ->
            val checked = profile.id in selectedIds
            Row(
                Modifier.fillMaxWidth().clickable {
                    val updated = if (checked) selectedIds - profile.id else selectedIds + profile.id
                    onSelectionChange(updated.distinct())
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = checked, onCheckedChange = { value ->
                    val updated = if (value) selectedIds + profile.id else selectedIds - profile.id
                    onSelectionChange(updated.distinct())
                })
                Column {
                    Text(profile.name, fontSize = 12.sp)
                    Text("${profile.scheme.name} · ${profile.host}:${profile.port}", color = c.ink3, fontSize = 10.sp)
                }
            }
        }
        if (selectedIds.size < 2) Text("Selecione ao menos 2 perfis para alternar neste escopo.", color = c.ink3, fontSize = 10.sp)
    }
}

@Composable
private fun ProxyScopeRow(
    label: String, selected: String?, profiles: List<dev.cueopen.app.proxy.ProxyProfile>,
    proxyScope: dev.cueopen.app.proxy.ProxyScope, onSelect: (String?) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontWeight = FontWeight.Medium)
        var expanded by remember { mutableStateOf(false) }
        Box { OutlinedButton({ expanded = true }) { Text(profiles.firstOrNull { it.id == selected }?.name ?: if (selected == null) "Direto" else "Perfil ausente") }; DropdownMenu(expanded, { expanded = false }) { DropdownMenuItem({ Text("Direto") }, { onSelect(null); expanded = false }); profiles.filter { dev.cueopen.app.proxy.ProxyValidator.validate(it, proxyScope).valid }.forEach { p -> DropdownMenuItem({ Text(p.name) }, { onSelect(p.id); expanded = false }) } } }
    }
}

@Composable
private fun ProxyEditor(initial: dev.cueopen.app.proxy.ProxyProfile, onSave: (dev.cueopen.app.proxy.ProxyProfile) -> Unit, onCancel: () -> Unit) {
    val c = Cue.c
    var p by remember(initial) { mutableStateOf(initial) }
    var user by remember(initial) { mutableStateOf("") }
    var pass by remember(initial) { mutableStateOf("") }
    var removeCredentials by remember(initial) { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onCancel, title = { Text("Perfil de proxy") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            CueField(p.name, { p = p.copy(name = it) }, "Nome")
            CueField(p.host, { p = p.copy(host = it) }, "Host")
            CueField(p.port.toString(), { p = p.copy(port = it.toIntOrNull() ?: 0) }, "Porta")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { dev.cueopen.app.proxy.ProxyScheme.values().forEach { s -> Chip(s.name, p.scheme == s) { p = p.copy(scheme = s) } } }
            CueField(user, { user = it }, "Usuário opcional")
            CueField(pass, { pass = it }, "Senha opcional", password = true)
            Text("Credenciais ficam temporariamente neste formulário e são cifradas com Android Keystore antes de persistir; não vão ao estado compartilhado, logs ou prompts. Terminal e WebView recusam perfis autenticados.", fontSize = 11.sp, color = c.ink2)
            if (p.hasCredentials) TextButton({ removeCredentials = true; p = p.copy(hasCredentials = false); user = ""; pass = "" }) { Text("Remover credenciais", color = c.err) }
        }
    }, confirmButton = { TextButton({ val hasNewCredentials = user.isNotBlank() || pass.isNotBlank(); if (hasNewCredentials) CueApp.instance.proxyStore.saveCredentials(p.id, dev.cueopen.app.proxy.ProxyCredentials(user, pass)) else if (removeCredentials) CueApp.instance.proxyStore.saveCredentials(p.id, null); onSave(p.copy(hasCredentials = hasNewCredentials || (p.hasCredentials && !removeCredentials))) }) { Text("Salvar") } }, dismissButton = { TextButton(onCancel) { Text("Cancelar") } })
}
