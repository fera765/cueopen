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

@Composable
fun SettingsScreen(nav: NavController) {
    val app = FluwyApp.instance
    val scope = rememberCoroutineScope()
    val settingsFlow = remember(app) {
        app.store.state.map { it.settings to it.memories.size }.distinctUntilChanged()
    }
    val initialSettings = remember(app) {
        app.store.state.value.let { it.settings to it.memories.size }
    }
    val settingsSnapshot by settingsFlow.collectAsStateWithLifecycle(initialSettings)
    val s = settingsSnapshot.first
    val c = Fluwy.c
    var confirmClear by remember { mutableStateOf(false) }
    Scaffold(containerColor = c.bg, topBar = { FluwyTopBar("Configurações", { nav.popBackStack() }) }) { pad ->
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
            FluwyField(s.userName, { v -> app.store.updateSettings { it.copy(userName = v) } }, "Seu nome")
            SectionLabel("Provedor de modelos")
            SettingsLink(Icons.Rounded.Hub, "Provedores", "Ativo: " + (dev.fluwy.app.llm.ProviderCatalog.byId(dev.fluwy.app.llm.ProviderCatalog.activeId(s))?.name ?: "Personalizado") +
                " · " + (s.savedProviders.count { it.value.getOrNull(0).orEmpty().isNotBlank() } + if (s.apiKey.isNotBlank()) 1 else 0) + " com chave") { nav.navigate("providers") }
            ToggleRow("Desativar thinking", "Zero raciocínio estendido — respostas mais rápidas", s.disableThinking) { v -> app.store.updateSettings { it.copy(disableThinking = v) } }
            Text("Passos máximos por execução: ${s.maxSteps}", fontSize = 14.sp)
            Slider(s.maxSteps.toFloat(), { v -> app.store.updateSettings { it.copy(maxSteps = v.toInt()) } }, valueRange = 10f..1000f,
                colors = SliderDefaults.colors(thumbColor = c.ink, activeTrackColor = c.ink))
            Text("Orçamento de contexto antes de compactar: ${s.contextBudget / 1000}k tokens", fontSize = 14.sp)
            Slider(s.contextBudget.toFloat(), { v -> app.store.updateSettings { it.copy(contextBudget = (v / 1000).toInt() * 1000) } }, valueRange = 8000f..120000f,
                colors = SliderDefaults.colors(thumbColor = c.ink, activeTrackColor = c.ink))
            SettingsLink(Icons.Rounded.Psychology, "Memória de longo prazo", "${settingsSnapshot.second} itens") { nav.navigate("memory") }
            SettingsLink(Icons.Rounded.EditNote, "Prompts", "Ver e editar os textos enviados aos modelos") { nav.navigate("prompts") }
            SectionLabel("Carteira")
            SettingsLink(Icons.Rounded.AccountBalanceWallet, "Carteira Solana", "Criar ou restaurar carteira local e consultar saldo") { nav.navigate("wallet") }
            // Chave Demo gratuita e opcional da CoinGecko para o mercado (evita rate limit). Vazio = keyless.
            val marketPrefs = remember { nav.context.getSharedPreferences("solana_wallet_settings", android.content.Context.MODE_PRIVATE) }
            var geckoKey by remember { mutableStateOf(marketPrefs.getString("gecko_api_key", "").orEmpty()) }
            FluwyField(geckoKey, { v ->
                geckoKey = v.trim()
                marketPrefs.edit().putString("gecko_api_key", geckoKey).apply()
                dev.fluwy.app.wallet.MarketApiConfig.geckoApiKey = geckoKey.ifBlank { null }
            }, "Chave Demo CoinGecko (opcional, grátis)", password = true)
            Text("O mercado funciona sem chave (com cache e limites automáticos). Se você bater em limite de requisições com frequência, crie uma chave Demo gratuita em coingecko.com e cole aqui para ter quota dedicada.", fontSize = 12.sp, color = Fluwy.c.ink2)
            SectionLabel("Assistente do Android")
            AssistantDefaultRoleLink(nav.context)
            SectionLabel("Voz do app")
            VoiceSettingsSection()
            SectionLabel("Aparência")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(ThemeMode.SYSTEM to "Sistema", ThemeMode.LIGHT to "Claro", ThemeMode.DARK to "Escuro").forEach { (m, l) ->
                    Chip(l, s.theme == m) { app.store.updateSettings { it.copy(theme = m) } }
                }
            }
            SettingsLink(Icons.Rounded.Insights, "Uso e limites", "Chamadas e tokens por modelo e agent; limites do plano") { nav.navigate("usage") }
            SectionLabel("Canais de envio de arquivos")
            SettingsLink(Icons.Rounded.Send, "Canais de envio", "Telegram, Discord, Slack e WhatsApp") { nav.navigate("channels") }
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
                    val crashText = dev.fluwy.app.diag.ErrorLog.lastCrashFile(diagnosticContext).takeIf { it.exists() }?.readText()
                    val bytes = dev.fluwy.app.diag.ErrorLog.sizeBytes(diagnosticContext)
                    val tail = dev.fluwy.app.diag.ErrorLog.readAll(diagnosticContext).takeLast(2500)
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
            SectionLabel("Dados")
            Text("JSON completo da interação dos chats — mensagens, chamadas de ferramenta, resultados, schemas das funções, agentes e times de build — para enviar e rastrear falhas. Não inclui chaves de API, cofre, carteira nem logins.",
                fontSize = 12.sp, color = c.ink2)
            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                val context = nav.context
                scope.launch {
                    runCatching {
                        val f = withContext(Dispatchers.IO) { dev.fluwy.app.diag.ChatDataExport.export(context) }
                        val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", f)
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "application/json"
                            putExtra(android.content.Intent.EXTRA_STREAM, uri)
                            putExtra(android.content.Intent.EXTRA_SUBJECT, "Fluwy Bot — dados dos chats")
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(android.content.Intent.createChooser(send, "Baixar / enviar dados").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
            }) { Icon(Icons.Rounded.Download, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Baixar dados (JSON)") }
            SectionLabel("Apps de banco")
            var bankGuard by remember { mutableStateOf(dev.fluwy.app.device.BankApps.autoOff(nav.context)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Desligar a acessibilidade do Fluwy ao abrir app de banco (Nubank e outros recusam abrir com ela ligada)", Modifier.weight(1f), fontSize = 13.sp, color = c.ink2)
                Switch(bankGuard, { bankGuard = it; dev.fluwy.app.device.BankApps.setAutoOff(nav.context, it) })
            }
            SectionLabel("Logs (erros e atividade)")
            Text(
                "Registra as FALHAS e a ATIVIDADE do app: cada ferramenta executada (argumentos, status, tempo e resultado), cada chamada de Web API do API Hub (URL, etapa, tempo), cada chamada ao modelo, além de conectores, hub, Linux, browser e crashes — com um resumo de falhas por ferramenta. O texto das conversas não entra. Baixe o diagnóstico completo e envie aqui no Claude para eu corrigir.",
                fontSize = 12.sp, color = c.ink2
            )
            if (logBytes < 0L) {
                FluwyCard { Text("Carregando logs…", color = c.ink2, fontSize = 13.sp) }
            } else {
                if (logBytes == 0L) FluwyCard { Text("Nenhum erro registrado até agora. O diagnóstico completo ainda inclui conectores, hub e Linux.", color = c.ink2, fontSize = 13.sp) }
                else {
                    Text("${logBytes / 1024 + 1} KB registrados", fontSize = 12.sp, color = c.ink3)
                    if (logTail.isNotBlank()) Text(
                        logTail, Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF0F1115)).padding(12.dp),
                        color = Color(0xFFFFB38A), fontFamily = FontFamily.Monospace, fontSize = 10.sp
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(modifier = Modifier.weight(1f), onClick = {
                        runCatching {
                            val f = dev.fluwy.app.diag.ErrorLog.exportAll(nav.context)
                            val uri = androidx.core.content.FileProvider.getUriForFile(nav.context, nav.context.packageName + ".files", f)
                            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                putExtra(android.content.Intent.EXTRA_SUBJECT, "Fluwy Bot — diagnóstico completo")
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            nav.context.startActivity(android.content.Intent.createChooser(send, "Baixar / enviar log de erros").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }) { Icon(Icons.Rounded.Download, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Baixar / enviar") }
                    OutlinedButton(modifier = Modifier.weight(1f), onClick = {
                        val context = nav.context.applicationContext
                        scope.launch {
                            val text = withContext(Dispatchers.IO) { dev.fluwy.app.diag.ErrorLog.exportAll(context).readText() }
                            val clip = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clip.setPrimaryClip(android.content.ClipData.newPlainText("Fluwy Bot log de erros", text))
                        }
                    }) { Icon(Icons.Rounded.ContentCopy, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Copiar") }
                }
            }
            if (logBytes >= 0L && (logBytes > 0L || lastCrash != null)) TextButton(onClick = {
                val context = nav.context.applicationContext
                scope.launch {
                    withContext(Dispatchers.IO) { dev.fluwy.app.diag.ErrorLog.clear(context) }
                    logRefresh++
                }
            }) {
                Text("Limpar logs e último erro", color = c.err)
            }
            SectionLabel("Dados")
            DataTransferSection(nav)
            SettingsLink(Icons.Rounded.DeleteSweep, "Apagar todas as tarefas", "Agents, skills e knowledge são mantidos", tint = c.err) { confirmClear = true }
            Spacer(Modifier.height(12.dp))
            val exitCtx = androidx.compose.ui.platform.LocalContext.current
            Button(onClick = { (exitCtx as? android.app.Activity)?.let { dev.fluwy.app.AppExit.exit(it) } }, modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = c.err, contentColor = Color.White)) { Text("Sair do app") }
            Text("Fecha o app de vez: para agentes, ciclos, agendas e terminal até você abrir o app de novo.", fontSize = 12.sp, color = c.ink3)
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
private fun AssistantDefaultRoleLink(context: android.content.Context) {
    var refresh by remember { mutableIntStateOf(0) }
    val active = remember(refresh) { dev.fluwy.app.assistant.AndroidAssistantRole.isDefaultAssistant(context) }
    val roleHeld = remember(refresh) { dev.fluwy.app.assistant.AndroidAssistantRole.isRoleHeld(context) }
    val roleRequestAvailable = remember(refresh) { dev.fluwy.app.assistant.AndroidAssistantRole.isRoleRequestAvailable(context) }
    var showSetupHelp by remember { mutableStateOf(false) }
    var setupFeedback by remember { mutableStateOf<String?>(null) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) refresh++
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        refresh++
        val nowActive = dev.fluwy.app.assistant.AndroidAssistantRole.isDefaultAssistant(context)
        val nowRoleHeld = dev.fluwy.app.assistant.AndroidAssistantRole.isRoleHeld(context)
        setupFeedback = when {
            nowActive -> "O Android confirmou Fluwy como assistente ativo."
            nowRoleHeld -> "Fluwy foi selecionado, mas o serviço ainda não confirmou atividade. Se a invocação falhar, consulte Configurações > Logs de erros."
            // A tela de Configurações sempre volta como "cancelada": o que vale é o estado real.
            else -> "Fluwy ainda não é o assistente. Na tela do Android, toque em \"Assistente digital padrão\" (ou \"App assistente\") e escolha Fluwy. Toque de novo aqui se precisar."
        }
    }
    SettingsLink(
        icon = Icons.Rounded.RecordVoiceOver,
        title = when {
            active -> "Fluwy é o assistente ativo"
            roleHeld -> "Fluwy está selecionado, mas inativo"
            else -> "Definir Fluwy como assistente"
        },
        sub = if (active)
            "Use o gesto do Android. Se a sessão não abrir, consulte Configurações > Logs de erros. O conteúdo só segue para um rascunho quando você tocar em Continuar."
        else if (roleHeld)
            "O papel foi concedido, mas o VoiceInteractionService ainda não está ativo. Toque para reabrir as opções do sistema e conferir."
        else if (roleRequestAvailable)
            "Abre a tela do Android: toque em \"Assistente digital padrão\" e escolha Fluwy. Depois, segure o botão home (ou deslize do canto) para chamar o Fluwy."
        else
            "Toque para abrir as opções do sistema. Se necessário, escolha Apps padrão > Assistente digital; a disponibilidade depende do Android/fabricante.",
        tint = Fluwy.c.accent,
    ) {
        setupFeedback = null
        var launched = false
        for (intent in dev.fluwy.app.assistant.AndroidAssistantRole.requestIntents(context)) {
            val launch = runCatching { roleLauncher.launch(intent) }
            if (launch.isSuccess) {
                launched = true
                break
            }
            runCatching {
                dev.fluwy.app.diag.ErrorLog.log(
                    "assistant",
                    "Falha ao abrir opções de assistente (${launch.exceptionOrNull()?.javaClass?.simpleName ?: "erro desconhecido"}).",
                )
            }
        }
        if (!launched) {
            setupFeedback = "Não foi possível abrir o seletor de assistente neste dispositivo."
            showSetupHelp = true
        }
    }
    setupFeedback?.let {
        Text(it, Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 8.dp), fontSize = 12.sp, color = Fluwy.c.ink2)
    }
    if (showSetupHelp) AlertDialog(
        onDismissRequest = { showSetupHelp = false },
        title = { Text("Como ativar o assistente") },
        text = { Text("Este Android não disponibilizou a seleção direta. Nas Configurações, tente Apps > Apps padrão > Assistente digital. A seleção só é aplicada quando você confirma no sistema; alguns fabricantes podem não oferecer esse recurso para apps de terceiros.") },
        confirmButton = {
            TextButton(onClick = {
                showSetupHelp = false
                dev.fluwy.app.assistant.AndroidAssistantRole.generalSettingsIntent(context)?.let { intent ->
                    val started = runCatching { roleLauncher.launch(intent); true }.getOrDefault(false)
                    if (!started) showSetupHelp = true
                } ?: run { showSetupHelp = true }
            }) { Text("Abrir Configurações") }
        },
        dismissButton = { TextButton(onClick = { showSetupHelp = false }) { Text("Fechar") } },
    )
}

@Composable
internal fun SettingsLink(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, tint: Color = Fluwy.c.ink, onClick: () -> Unit) {
    val c = Fluwy.c
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface).clickable(onClick = onClick).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint); Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.Medium, color = tint); Text(sub, fontSize = 13.sp, color = c.ink2) }
        Icon(Icons.Rounded.ChevronRight, null, tint = c.ink3)
    }
}
