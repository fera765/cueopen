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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.cueopen.app.CueApp
import dev.cueopen.app.data.ThemeMode
import dev.cueopen.app.sandbox.Sandbox
import dev.cueopen.app.ui.theme.Cue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(nav: NavController) {
    val app = CueApp.instance
    val st by app.store.state.collectAsStateWithLifecycle()
    val s = st.settings
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
            SettingsLink(Icons.Rounded.Psychology, "Memória de longo prazo", "${st.memories.size} itens") { nav.navigate("memory") }
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
            SectionLabel("Computador")
            SettingsLink(Icons.Rounded.Computer, "Sandbox Linux", "Instalar, pacotes, reinstalar") { nav.navigate("sandbox") }
            SettingsLink(Icons.Rounded.Terminal, "Abrir terminal", "Shell interativo no Alpine") { nav.navigate("terminal") }
            SettingsLink(Icons.Rounded.FolderOpen, "Arquivos", "/root no sandbox") { nav.navigate("files") }
            val crash = remember { java.io.File(app.filesDir, "last_crash.txt").takeIf { it.exists() }?.readText() }
            if (crash != null) {
                SectionLabel("Último erro")
                Text(crash.take(3000), Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF0F1115)).padding(12.dp),
                    color = Color(0xFFFF8A8A), fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            }
            SectionLabel("Logs de erros")
            var logRefresh by remember { mutableStateOf(0) }
            val logBytes = remember(logRefresh) { dev.cueopen.app.diag.ErrorLog.sizeBytes(nav.context) }
            val logTail = remember(logRefresh) { dev.cueopen.app.diag.ErrorLog.readAll(nav.context).takeLast(2500) }
            Text(
                "Guarda as FALHAS reais do app (ferramentas do agente, browser, terminal, swaps, mercado, crashes) — nunca o conteúdo das conversas. Baixe e envie aqui no Claude para eu corrigir.",
                fontSize = 12.sp, color = c.ink2
            )
            if (logBytes <= 0L) {
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
                        val clip = nav.context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clip.setPrimaryClip(android.content.ClipData.newPlainText("Fluwy Bot log de erros", dev.cueopen.app.diag.ErrorLog.readAll(nav.context)))
                    }) { Icon(Icons.Rounded.ContentCopy, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Copiar") }
                }
                TextButton(onClick = { dev.cueopen.app.diag.ErrorLog.clear(nav.context); logRefresh++ }) {
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
