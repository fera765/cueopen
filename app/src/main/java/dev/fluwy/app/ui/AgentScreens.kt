package dev.fluwy.app.ui
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.text.BasicTextField
import androidx.navigation.NavController
import dev.fluwy.app.FluwyApp
import dev.fluwy.app.data.*
import dev.fluwy.app.scheduler.Scheduler
import kotlinx.coroutines.launch
import dev.fluwy.app.ui.theme.Fluwy
import dev.fluwy.app.wallet.WalletAgentPolicyStore
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun AgentsScreen(nav: NavController) {
    val st by FluwyApp.instance.store.state.collectAsStateWithLifecycle()
    val c = Fluwy.c
    Scaffold(containerColor = c.bg, topBar = { FluwyTopBar("Agents", { nav.popBackStack() }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { nav.navigate("agent/new") }, containerColor = c.ink, contentColor = c.bg, shape = CircleShape,
                icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("Criar Agent") })
        }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                FluwyCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Row { agentColors.take(3).forEachIndexed { i, col -> AgentFace(Color(col), agentFaces[i], 34.dp, Modifier.offset(x = (-10 * i).dp)) } }
                        Spacer(Modifier.width(4.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Agents especializados", fontWeight = FontWeight.SemiBold)
                            Text("Cada Agent tem instruções, skills, memória e modelo próprios.", fontSize = 13.sp, color = c.ink2)
                        }
                    }
                }
            }
            item {
                val bots = remember { dev.fluwy.app.agent.BotCatalog.all() }
                FluwyCard(onClick = { nav.navigate("agentbots") }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(c.accentSoft), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.SmartToy, null, tint = c.accent)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Agentes bots", fontWeight = FontWeight.SemiBold)
                            Text("${bots.size} agentes bots para configurar e adicionar.", fontSize = 13.sp, color = c.ink2)
                        }
                        Icon(Icons.Rounded.ChevronRight, null, tint = c.ink3)
                    }
                }
            }
            if (st.agents.isEmpty()) item { EmptyState(Icons.Rounded.Groups, "Nenhum Agent ainda", "Crie um Agent para tarefas recorrentes — ex.: pesquisador, dev, social media.") }
            items(st.agents, key = { it.id }) { a ->
                FluwyCard(onClick = { nav.navigate("agent/${a.id}") }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AgentFace(Color(a.color), a.face, 44.dp); Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(a.name, style = MaterialTheme.typography.titleMedium)
                            Text(a.instructions.ifBlank { a.mission.ifBlank { "Sem instruções" } }, fontSize = 13.sp, color = c.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (a.autonomous) Text("● Autônomo · ${a.actions} ações", fontSize = 11.sp, color = c.ok)
                        }
                        IconButton({ nav.navigate("home?agent=${a.id}") }) { Icon(Icons.Rounded.ChatBubbleOutline, "Nova tarefa") }
                    }
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun AgentEditScreen(nav: NavController, id: String, botId: String? = null, sectionId: String? = null) {
    val app = FluwyApp.instance
    val st by app.store.state.collectAsStateWithLifecycle()
    val existing = st.agents.firstOrNull { it.id == id }
    // Foto do Agent no momento em que a tela abriu: base para saber o que o usuário mudou de fato.
    val original = remember { existing }
    // Agent novo já vem com Terminal, Browser, Autonomia do Browser e Hub de conectores ligados.
    // Bot do catálogo: só preenche o rascunho. Nada é gravado até o usuário tocar em Salvar.
    var a by remember { mutableStateOf(existing ?: dev.fluwy.app.device.BrowserAutonomyPolicy.setEnabled(AgentProfile(name = ""), true).let { base ->
        dev.fluwy.app.agent.BotCatalog.find(botId)?.let { dev.fluwy.app.agent.BotCatalog.draft(it, base) } ?: base
    }) }
    val c = Fluwy.c
    val ctx = LocalContext.current
    var resumeTick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) resumeTick++ }
        lifecycleOwner.lifecycle.addObserver(obs); onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val walletPolicies = remember { WalletAgentPolicyStore(app.applicationContext) }
    var walletAccess by remember(a.id) {
        mutableStateOf(
                walletPolicies.canAgentReadAddress(a.id) ||
                walletPolicies.canAgentReadBalance(a.id) ||
                walletPolicies.canAgentRequestTransfer(a.id) ||
                walletPolicies.canAgentDayTrade(a.id)
        )
    }
    var walletReadAddress by remember(a.id) { mutableStateOf(walletPolicies.canAgentReadAddress(a.id)) }
    var walletReadBalance by remember(a.id) { mutableStateOf(walletPolicies.canAgentReadBalance(a.id)) }
    var walletRequestTransfer by remember(a.id) { mutableStateOf(walletPolicies.canAgentRequestTransfer(a.id)) }
    var walletAutonomousTransfer by remember(a.id) { mutableStateOf(walletPolicies.canAgentAutonomousTransfer(a.id)) }
    var walletDayTrade by remember(a.id) { mutableStateOf(walletPolicies.canAgentDayTrade(a.id)) }
    Scaffold(containerColor = c.bg, topBar = {
        FluwyTopBar(if (existing == null) (if (sectionId != null) "Criar Agent para a seção" else "Criar Agent") else a.name, { nav.popBackStack() }) {
            if (existing != null) IconButton({ app.store.deleteAgent(id); nav.popBackStack() }) { Icon(Icons.Rounded.Delete, "Excluir", tint = c.err) }
        }
    }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            dev.fluwy.app.ui.mascot.MascotPreview(a.face, Color(a.color))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                agentColors.forEach { col ->
                    Box(Modifier.padding(4.dp).size(28.dp).clip(CircleShape).background(Color(col))
                        .border(if (a.color == col) 3.dp else 0.dp, c.ink, CircleShape).clickable { a = a.copy(color = col) })
                }
            }
            dev.fluwy.app.ui.mascot.MascotPicker(a.face, Color(a.color)) { a = a.copy(face = it) }
            FluwyField(a.name, { a = a.copy(name = it) }, "Nome do Agent")
            // Caixa de tamanho fixo: prompts grandes rolam por dentro em vez de esticar a tela.
            FluwyField(a.instructions, { a = a.copy(instructions = it) }, "Instruções (como deve agir, tom, objetivos)", modifier = Modifier.height(200.dp), minLines = 5)
            ModelPicker(a.model ?: "", { a = a.copy(model = it.ifBlank { null }) }, "Modelo (vazio = padrão do app)", allowEmpty = true,
                baseUrl = app.store.settings.baseUrl, apiKey = app.store.settings.apiKey)
            SectionLabel("Autonomia")
            ToggleRow("Pode trabalhar de forma autônoma", "Quando ligado, o próprio agente decide, pela conversa, se e de quanto em quanto tempo deve acordar para seguir uma tarefa contínua. Sem um pedido assim, ele fica dormindo.", a.autonomous) { on ->
                // Um toque só: ligar a autonomia já liga ‘Executar em segundo plano’ no Acesso ao Android
                // e, se o Android ainda otimiza a bateria do app, abre o diálogo do sistema na hora.
                a = dev.fluwy.app.device.AgentAutonomyPolicy.setAutonomous(a, on)
                // Agent já salvo: grava na hora, para não se perder se o Android recriar a tela
                // enquanto o usuário está no diálogo de bateria.
                if (existing != null) app.store.agent(a.id)?.let { cur ->
                    val upd = dev.fluwy.app.device.AgentAutonomyPolicy.setAutonomous(cur, on)
                    app.store.upsertAgent(upd); dev.fluwy.app.scheduler.Heartbeat.sync(ctx, upd)
                }
                if (on) dev.fluwy.app.device.DeviceCaps.requestBatteryUnrestricted(ctx)
            }
            resumeTick.let {}
            if (a.autonomous && dev.fluwy.app.device.DeviceCaps.BACKGROUND !in a.deviceCaps)
                Text("‘Executar em segundo plano’ foi desligado manualmente. Toque para religar →", fontSize = 12.sp, color = c.warn,
                    modifier = Modifier.clickable { a = dev.fluwy.app.device.AgentAutonomyPolicy.setAutonomous(a, true); dev.fluwy.app.device.DeviceCaps.requestBatteryUnrestricted(ctx) })
            else if (a.autonomous && !dev.fluwy.app.device.DeviceCaps.batteryUnrestricted(ctx))
                Text("O Android ainda otimiza a bateria do app e pode pausar os ciclos. Toque para liberar →", fontSize = 12.sp, color = c.warn,
                    modifier = Modifier.clickable { dev.fluwy.app.device.DeviceCaps.requestBatteryUnrestricted(ctx) })
            if (a.autonomous) {
                Text("Não precisa configurar intervalo: diga no chat o que acompanhar (ex.: \"a cada 20 min verifique o mercado\") e ele se agenda sozinho. Deixe o orçamento de tokens por dia como limite de segurança.", fontSize = 12.sp, color = c.ink3)
                Text("Orçamento diário de tokens", fontSize = 13.sp, color = c.ink2)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(100_000L to "100k", 300_000L to "300k", 1_000_000L to "1M", 3_000_000L to "3M").forEach { (v, l) ->
                        Chip(l, a.dailyTokenBudget == v) { a = a.copy(dailyTokenBudget = v) }
                    }
                }
                ToggleRow("Notificar quando agir", "Avisa quando um ciclo fizer algo (silencioso quando nada muda)", a.notifyOnAction) { a = a.copy(notifyOnAction = it) }
            }
            DevicePermissionsCard(a) { upd ->
                a = upd
                // Acessos ao Android de um Agent existente valem imediatamente, sem depender do Salvar.
                if (existing != null) app.store.agent(upd.id)?.let { cur ->
                    app.store.upsertAgent(cur.copy(deviceCaps = upd.deviceCaps, browserAutonomy = upd.browserAutonomy,
                        browserAutonomyGrantedBackground = upd.browserAutonomyGrantedBackground))
                }
            }
            SectionLabel("Confirmações")
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(16.dp)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ToggleRow(
                    "100% autônomo (sem confirmações)",
                    "Executa tudo sozinho, inclusive comprar, pagar, apagar e enviar. Use apenas em Agents de total confiança.",
                    a.confirmMode == "auto",
                ) { a = a.copy(confirmMode = if (it) "auto" else "ask") }
                if (a.confirmMode == "auto") Text("⚠ Este Agent não vai pedir sua confirmação para nada.", fontSize = 12.sp, color = c.warn)
                else {
                    Text("Perguntar antes de:", fontSize = 13.sp, color = c.ink2)
                    dev.fluwy.app.agent.ActionGate.Category.entries.forEach { cat ->
                        val desc = when (cat) {
                            dev.fluwy.app.agent.ActionGate.Category.PURCHASE -> "Botões como Comprar, Finalizar pedido, Pagar, Assinar, gerar Pix/boleto"
                            dev.fluwy.app.agent.ActionGate.Category.TRANSFER -> "Transferir, sacar, apostar, vender, operações em corretoras e conectores de trading"
                            dev.fluwy.app.agent.ActionGate.Category.DELETE -> "Excluir/apagar posts, mensagens, arquivos do aparelho, itens em sites e conectores"
                            dev.fluwy.app.agent.ActionGate.Category.SEND -> "Enviar mensagens e e-mails, publicar, postar, comentar, compartilhar"
                            dev.fluwy.app.agent.ActionGate.Category.ACCOUNT -> "Trocar senha, excluir ou desativar conta"
                        }
                        ToggleRow(cat.label, desc, cat.id in a.confirmCategories) { on ->
                            a = a.copy(confirmCategories = if (on) (a.confirmCategories + cat.id).distinct() else a.confirmCategories - cat.id)
                        }
                    }
                    Text("Quando precisar, o Agent para, mostra um card no chat e envia uma notificação. Cada aprovação vale uma única vez. A Carteira Solana segue as regras da aba Carteira abaixo.",
                        fontSize = 11.sp, color = c.ink3)
                }
            }
            SectionLabel("Capacidades")
            var capTab by remember { mutableStateOf(0) }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val nUic = a.uiConnectorIds.size
                Chip("Ferramentas", capTab == 0) { capTab = 0 }
                Chip("UI Conectores" + if (nUic > 0) " ($nUic)" else "", capTab == 1) { capTab = 1 }
                Chip("Carteira" + if (walletAccess) " ✓" else "", capTab == 2) { capTab = 2 }
            }
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(16.dp)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
            when (capTab) {
                0 -> {
                    ToggleRow("Terminal Linux", "Executar comandos no sandbox", a.useTerminal) { a = a.copy(useTerminal = it) }
                    ToggleRow("Browser", "Navegar e interagir com sites", a.useBrowser) {
                        a = dev.fluwy.app.device.BrowserAutonomyPolicy.setBrowserEnabled(a, it)
                    }
                    if (a.useBrowser) ToggleRow(
                        "Autonomia do Browser",
                        "Permite que tarefas deste Agent continuem usando Browser em segundo plano. Liga o consentimento interno ‘Executar em segundo plano’, mas não agenda tarefas sozinho nem altera permissões globais do Android. Ao desligar, remove apenas o consentimento que esta opção adicionou; um consentimento anterior fica preservado.",
                        a.browserAutonomy,
                    ) { enabled ->
                        a = dev.fluwy.app.device.BrowserAutonomyPolicy.setEnabled(a, enabled)
                    }
                    ToggleRow(
                        "Hub de conectores",
                        "Gmail, Planilhas, Instagram, GitHub e os outros apps conectados no Hub (Activepieces). No chat normal ele já fica sempre ativo.",
                        a.useConnectorHub,
                    ) { a = a.copy(useConnectorHub = it) }

                }
                1 -> {
                    // UI Connectors (SDK): um toggle por conector. Login, limites e "Agent required ?" ficam na tela do conector.
                    val uiHub = dev.fluwy.app.FluwyApp.instance.uiConnectors
                    if (uiHub.connectors.isEmpty()) Text("Nenhum UI Conector ainda. Crie em Conectores → UI Conectores.", fontSize = 13.sp, color = c.ink3)
                    else {
                        uiHub.connectors.forEach { uc ->
                            ToggleRow(
                                "Usar ${uc.spec.name}",
                                uc.spec.description.take(90),
                                uc.spec.id in a.uiConnectorIds,
                            ) { on ->
                                a = a.copy(uiConnectorIds = if (on) (a.uiConnectorIds + uc.spec.id).distinct() else a.uiConnectorIds - uc.spec.id)
                            }
                        }
                    }

                }
                else -> {
                    ToggleRow(
                        "Permitir acesso à carteira",
                        "Ative para este agente apenas as capacidades selecionadas abaixo. Chaves privadas e frase de recuperação nunca são disponibilizadas.",
                        walletAccess
                    ) { walletAccess = it }
                    if (walletAccess) {
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface2).padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ToggleRow("Consultar endereço público", "Permite ler o endereço público da carteira local.", walletReadAddress) { walletReadAddress = it }
                            ToggleRow("Consultar saldo SOL", "Permite consultar o saldo on-chain da rede selecionada.", walletReadBalance) { walletReadBalance = it }
                            ToggleRow("Solicitar transferência", "Permite criar um pedido de envio de SOL. Por padrão, cada pedido exige aprovação manual.", walletRequestTransfer) { walletRequestTransfer = it; if (!it) walletAutonomousTransfer = false }
                            if (walletRequestTransfer) {
                                ToggleRow(
                                    "Transferência automática (sem aprovação manual)",
                                    "O agente assina e envia transferências sozinho, sem confirmar a cada vez, para qualquer destinatário. Sempre respeita os limites por transação e diário deste agente. Use com cautela.",
                                    walletAutonomousTransfer
                                ) { walletAutonomousTransfer = it }
                                if (walletAutonomousTransfer) {
                                    Text(
                                        "⚠ Modo autônomo ativo: o agente pode enviar para qualquer endereço, sem aprovação manual. A allowlist é opcional (em Carteira → Mais); se vazia, qualquer destinatário é permitido. Os limites por transação/dia são o único teto de segurança — mantenha-os.",
                                        fontSize = 12.sp,
                                        color = c.warn
                                    )
                                }
                            }
                            ToggleRow(
                                "Day trade (mercado + swaps + regras)",
                                "Libera dados de mercado reais (preço, velas, liquidez), listar tokens, executar trocas e armar stop-loss/take-profit. Entradas em SOL respeitam o limite por operação. Não concede envio de SOL a terceiros.",
                                walletDayTrade
                            ) { walletDayTrade = it }
                            if (walletDayTrade) {
                                Text(
                                    "⚠ Day trade envolve risco de perda. O agente opera com dados 100% reais e só relata execução com a assinatura on-chain; nunca promete lucro. O teto por operação (em SOL) limita cada entrada.",
                                    fontSize = 12.sp,
                                    color = c.warn
                                )
                            }
                            Text(
                                "Chaves privadas e a frase de recuperação nunca são expostas ao agente. Os limites locais são sempre aplicados no assinador.",
                                fontSize = 12.sp,
                                color = c.ink2
                            )
                        }
                    }

                }
            }
            }
            // Skills em destaque: caixa recolhida por padrão; aberta, lista rolável com checkbox e busca.
            var skillsOpen by remember { mutableStateOf(false) }
            var skillQuery by remember { mutableStateOf("") }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(16.dp))) {
                Row(Modifier.fillMaxWidth().clickable { skillsOpen = !skillsOpen }.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(18.dp), tint = c.accent)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Skills em destaque (opcional)", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(if (a.skillIds.isEmpty()) "Nenhuma marcada · ${st.skills.size} disponíveis" else "${a.skillIds.count { id -> st.skills.any { it.id == id } }} marcadas de ${st.skills.size}",
                            fontSize = 12.sp, color = c.ink3)
                    }
                    Icon(if (skillsOpen) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (skillsOpen) "Recolher" else "Mostrar", tint = c.ink2)
                }
                androidx.compose.animation.AnimatedVisibility(skillsOpen) {
                    Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                        Text("Todas as skills ativas já ficam disponíveis para este Agent. Marcar faz as escolhidas aparecerem primeiro para ele.", fontSize = 12.sp, color = c.ink3)
                        if (st.skills.size > 6) { Spacer(Modifier.height(8.dp)); FluwyField(skillQuery, { skillQuery = it }, "Buscar skill") }
                        Spacer(Modifier.height(6.dp))
                        val shown = st.skills.filter { skillQuery.isBlank() || it.name.contains(skillQuery, true) || it.description.contains(skillQuery, true) }
                        if (shown.isEmpty()) Text("Nenhuma skill encontrada.", fontSize = 13.sp, color = c.ink3, modifier = Modifier.padding(8.dp))
                        androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                            items(shown.size, key = { shown[it].id }) { i ->
                                val s = shown[i]
                                val on = s.id in a.skillIds
                                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                    .clickable { a = a.copy(skillIds = if (on) a.skillIds - s.id else a.skillIds + s.id) }.padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(on, { v -> a = a.copy(skillIds = if (v) (a.skillIds + s.id).distinct() else a.skillIds - s.id) },
                                        colors = CheckboxDefaults.colors(checkedColor = c.accent))
                                    Icon(skillIcon(s.icon), null, Modifier.size(16.dp), tint = c.ink2)
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(s.name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        if (s.description.isNotBlank()) Text(s.description, fontSize = 11.sp, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            SectionLabel("Memória do Agent")
            if (a.memory.isEmpty()) Text("O Agent salva fatos aqui com a ferramenta remember.", color = c.ink3, fontSize = 13.sp)
            a.memory.forEachIndexed { i, m ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("• $m", Modifier.weight(1f), fontSize = 14.sp)
                    IconButton({ a = a.copy(memory = a.memory.filterIndexed { j, _ -> j != i }) }) { Icon(Icons.Rounded.Close, "Remover", Modifier.size(16.dp)) }
                }
            }
            var newMem by remember { mutableStateOf("") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                FluwyField(newMem, { newMem = it }, "Adicionar memória", Modifier.weight(1f))
                IconButton({ if (newMem.isNotBlank()) { a = a.copy(memory = a.memory + newMem); newMem = "" } }) { Icon(Icons.Rounded.Add, "Adicionar") }
            }
            Spacer(Modifier.height(8.dp))
            PillButton("Salvar", Icons.Rounded.Check, modifier = Modifier.fillMaxWidth(), enabled = a.name.isNotBlank()) {
                val saved = dev.fluwy.app.device.AgentAutonomyPolicy.mergeForSave(a, original, app.store.agent(a.id))
                app.store.upsertAgent(saved)
                dev.fluwy.app.scheduler.Heartbeat.sync(ctx, saved)
                walletPolicies.setAgentCapabilities(
                    a.id,
                    readAddress = walletAccess && walletReadAddress,
                    readBalance = walletAccess && walletReadBalance,
                    requestTransfer = walletAccess && walletRequestTransfer,
                    autonomousTransfer = walletAccess && walletRequestTransfer && walletAutonomousTransfer,
                    dayTrade = walletAccess && walletDayTrade
                )
                if (saved.autonomous) app.agent.startAutonomy()
                // Aberto pelo "Adicionar agente" de uma seção: o agente salvo já entra no time.
                sectionId?.let { app.agent.addAgentToSection(it, saved.id) }
                nav.popBackStack()
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = Fluwy.c
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onChange(!checked) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            if (subtitle.isNotBlank()) Text(subtitle, fontSize = 13.sp, color = c.ink2)
        }
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedTrackColor = c.ink, checkedThumbColor = c.bg))
    }
}

// ============================ SKILLS ============================
