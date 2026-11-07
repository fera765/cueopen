package dev.cueopen.app.ui

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
import dev.cueopen.app.CueApp
import dev.cueopen.app.data.*
import dev.cueopen.app.scheduler.Scheduler
import kotlinx.coroutines.launch
import dev.cueopen.app.ui.theme.Cue
import dev.cueopen.app.wallet.WalletAgentPolicyStore
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// ============================ AGENTS ============================

@Composable
fun AgentsScreen(nav: NavController) {
    val st by CueApp.instance.store.state.collectAsStateWithLifecycle()
    val c = Cue.c
    Scaffold(containerColor = c.bg, topBar = { CueTopBar("Agents", { nav.popBackStack() }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { nav.navigate("agent/new") }, containerColor = c.ink, contentColor = c.bg, shape = CircleShape,
                icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("Criar Agent") })
        }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                CueCard {
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
            if (st.agents.isEmpty()) item { EmptyState(Icons.Rounded.Groups, "Nenhum Agent ainda", "Crie um Agent para tarefas recorrentes — ex.: pesquisador, dev, social media.") }
            items(st.agents, key = { it.id }) { a ->
                CueCard(onClick = { nav.navigate("agent/${a.id}") }) {
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
fun AgentEditScreen(nav: NavController, id: String) {
    val app = CueApp.instance
    val st by app.store.state.collectAsStateWithLifecycle()
    val existing = st.agents.firstOrNull { it.id == id }
    // Foto do Agent no momento em que a tela abriu: base para saber o que o usuário mudou de fato.
    val original = remember { existing }
    var a by remember { mutableStateOf(existing ?: AgentProfile(name = "")) }
    val c = Cue.c
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
        CueTopBar(if (existing == null) "Criar Agent" else a.name, { nav.popBackStack() }) {
            if (existing != null) IconButton({ app.store.deleteAgent(id); nav.popBackStack() }) { Icon(Icons.Rounded.Delete, "Excluir", tint = c.err) }
        }
    }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            dev.cueopen.app.ui.mascot.MascotPreview(a.face, Color(a.color))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                agentColors.forEach { col ->
                    Box(Modifier.padding(4.dp).size(28.dp).clip(CircleShape).background(Color(col))
                        .border(if (a.color == col) 3.dp else 0.dp, c.ink, CircleShape).clickable { a = a.copy(color = col) })
                }
            }
            dev.cueopen.app.ui.mascot.MascotPicker(a.face, Color(a.color)) { a = a.copy(face = it) }
            CueField(a.name, { a = a.copy(name = it) }, "Nome do Agent")
            // Caixa de tamanho fixo: prompts grandes rolam por dentro em vez de esticar a tela.
            CueField(a.instructions, { a = a.copy(instructions = it) }, "Instruções (como deve agir, tom, objetivos)", modifier = Modifier.height(200.dp), minLines = 5)
            ModelPicker(a.model ?: "", { a = a.copy(model = it.ifBlank { null }) }, "Modelo (vazio = padrão do app)", allowEmpty = true,
                baseUrl = app.store.settings.baseUrl, apiKey = app.store.settings.apiKey)
            SectionLabel("Autonomia")
            ToggleRow("Pode trabalhar de forma autônoma", "Quando ligado, o próprio agente decide, pela conversa, se e de quanto em quanto tempo deve acordar para seguir uma tarefa contínua. Sem um pedido assim, ele fica dormindo.", a.autonomous) { on ->
                // Um toque só: ligar a autonomia já liga ‘Executar em segundo plano’ no Acesso ao Android
                // e, se o Android ainda otimiza a bateria do app, abre o diálogo do sistema na hora.
                a = dev.cueopen.app.device.AgentAutonomyPolicy.setAutonomous(a, on)
                // Agent já salvo: grava na hora, para não se perder se o Android recriar a tela
                // enquanto o usuário está no diálogo de bateria.
                if (existing != null) app.store.agent(a.id)?.let { cur ->
                    val upd = dev.cueopen.app.device.AgentAutonomyPolicy.setAutonomous(cur, on)
                    app.store.upsertAgent(upd); dev.cueopen.app.scheduler.Heartbeat.sync(ctx, upd)
                }
                if (on) dev.cueopen.app.device.DeviceCaps.requestBatteryUnrestricted(ctx)
            }
            resumeTick.let {}
            if (a.autonomous && dev.cueopen.app.device.DeviceCaps.BACKGROUND !in a.deviceCaps)
                Text("‘Executar em segundo plano’ foi desligado manualmente. Toque para religar →", fontSize = 12.sp, color = c.warn,
                    modifier = Modifier.clickable { a = dev.cueopen.app.device.AgentAutonomyPolicy.setAutonomous(a, true); dev.cueopen.app.device.DeviceCaps.requestBatteryUnrestricted(ctx) })
            else if (a.autonomous && !dev.cueopen.app.device.DeviceCaps.batteryUnrestricted(ctx))
                Text("O Android ainda otimiza a bateria do app e pode pausar os ciclos. Toque para liberar →", fontSize = 12.sp, color = c.warn,
                    modifier = Modifier.clickable { dev.cueopen.app.device.DeviceCaps.requestBatteryUnrestricted(ctx) })
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
                    dev.cueopen.app.agent.ActionGate.Category.entries.forEach { cat ->
                        val desc = when (cat) {
                            dev.cueopen.app.agent.ActionGate.Category.PURCHASE -> "Botões como Comprar, Finalizar pedido, Pagar, Assinar, gerar Pix/boleto"
                            dev.cueopen.app.agent.ActionGate.Category.TRANSFER -> "Transferir, sacar, apostar, vender, operações em corretoras e conectores de trading"
                            dev.cueopen.app.agent.ActionGate.Category.DELETE -> "Excluir/apagar posts, mensagens, arquivos do aparelho, itens em sites e conectores"
                            dev.cueopen.app.agent.ActionGate.Category.SEND -> "Enviar mensagens e e-mails, publicar, postar, comentar, compartilhar"
                            dev.cueopen.app.agent.ActionGate.Category.ACCOUNT -> "Trocar senha, excluir ou desativar conta"
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
                        a = dev.cueopen.app.device.BrowserAutonomyPolicy.setBrowserEnabled(a, it)
                    }
                    if (a.useBrowser) ToggleRow(
                        "Autonomia do Browser",
                        "Permite que tarefas deste Agent continuem usando Browser em segundo plano. Liga o consentimento interno ‘Executar em segundo plano’, mas não agenda tarefas sozinho nem altera permissões globais do Android. Ao desligar, remove apenas o consentimento que esta opção adicionou; um consentimento anterior fica preservado.",
                        a.browserAutonomy,
                    ) { enabled ->
                        a = dev.cueopen.app.device.BrowserAutonomyPolicy.setEnabled(a, enabled)
                    }

                }
                1 -> {
                    // UI Connectors (SDK): um toggle por conector. Login, limites e "Agent required ?" ficam na tela do conector.
                    val uiHub = dev.cueopen.app.CueApp.instance.uiConnectors
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
            SectionLabel("Skills (vazio = todas ativas)")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                st.skills.forEach { s ->
                    val on = s.id in a.skillIds
                    Chip(s.name, selected = on, icon = skillIcon(s.icon)) { a = a.copy(skillIds = if (on) a.skillIds - s.id else a.skillIds + s.id) }
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
                CueField(newMem, { newMem = it }, "Adicionar memória", Modifier.weight(1f))
                IconButton({ if (newMem.isNotBlank()) { a = a.copy(memory = a.memory + newMem); newMem = "" } }) { Icon(Icons.Rounded.Add, "Adicionar") }
            }
            Spacer(Modifier.height(8.dp))
            PillButton("Salvar", Icons.Rounded.Check, modifier = Modifier.fillMaxWidth(), enabled = a.name.isNotBlank()) {
                val saved = dev.cueopen.app.device.AgentAutonomyPolicy.mergeForSave(a, original, app.store.agent(a.id))
                app.store.upsertAgent(saved)
                dev.cueopen.app.scheduler.Heartbeat.sync(ctx, saved)
                walletPolicies.setAgentCapabilities(
                    a.id,
                    readAddress = walletAccess && walletReadAddress,
                    readBalance = walletAccess && walletReadBalance,
                    requestTransfer = walletAccess && walletRequestTransfer,
                    autonomousTransfer = walletAccess && walletRequestTransfer && walletAutonomousTransfer,
                    dayTrade = walletAccess && walletDayTrade
                )
                if (saved.autonomous) app.agent.startAutonomy()
                nav.popBackStack()
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = Cue.c
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onChange(!checked) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.Medium)
            if (subtitle.isNotBlank()) Text(subtitle, fontSize = 13.sp, color = c.ink2)
        }
        Switch(checked, onChange, colors = SwitchDefaults.colors(checkedTrackColor = c.ink, checkedThumbColor = c.bg))
    }
}

// ============================ SKILLS ============================

@Composable
fun SkillsScreen(nav: NavController) {
    val app = CueApp.instance
    val st by app.store.state.collectAsStateWithLifecycle()
    val c = Cue.c
    Scaffold(containerColor = c.bg, topBar = { CueTopBar("Skills", { nav.popBackStack() }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { nav.navigate("skill/new") }, containerColor = c.ink, contentColor = c.bg, shape = CircleShape,
                icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("Nova skill") })
        }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                CueCard(onClick = {
                    val s = Session(title = "Criar skill"); app.store.upsertSession(s)
                    app.agent.send(s.id, "Quero criar uma nova skill. Me faça perguntas curtas para entender o que ela deve fazer e depois crie com create_skill.")
                    nav.navigate("chat/${s.id}")
                }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.AutoFixHigh, null, tint = c.accent); Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Criar skill conversando", fontWeight = FontWeight.SemiBold)
                            Text("O Skill creator entrevista você e monta a skill.", fontSize = 13.sp, color = c.ink2)
                        }
                        Icon(Icons.Rounded.ChevronRight, null, tint = c.ink3)
                    }
                }
            }
            item { SkillMarketplace() }
            item { SectionLabel("Minhas skills") }
            items(st.skills, key = { it.id }) { s ->
                var confirmDelete by remember(s.id) { mutableStateOf(false) }
                CueCard(onClick = { nav.navigate("skill/${s.id}") }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(c.surface2), contentAlignment = Alignment.Center) { Icon(skillIcon(s.icon), null) }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(s.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (s.builtIn) { Spacer(Modifier.width(6.dp)); Text("nativa", fontSize = 11.sp, color = c.ink3) }
                            }
                            Text(s.description, fontSize = 13.sp, color = c.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Switch(s.enabled, { app.store.upsertSkill(s.copy(enabled = it)) }, colors = SwitchDefaults.colors(checkedTrackColor = c.ink, checkedThumbColor = c.bg))
                        if (!s.builtIn) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Rounded.DeleteOutline, "Excluir skill", tint = c.err) }
                    }
                }
                if (confirmDelete) AlertDialog(
                    onDismissRequest = { confirmDelete = false },
                    title = { Text("Excluir skill?") },
                    text = { Text("A skill “${s.name}” será removida da sua biblioteca.") },
                    confirmButton = { TextButton(onClick = { app.store.deleteSkill(s.id); confirmDelete = false }) { Text("Excluir", color = c.err) } },
                    dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } }
                )
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

@Composable
fun SkillEditScreen(nav: NavController, id: String) {
    val app = CueApp.instance
    val existing = app.store.state.value.skills.firstOrNull { it.id == id }
    var s by remember { mutableStateOf(existing ?: Skill(name = "", description = "", instructions = "")) }
    val c = Cue.c
    Scaffold(containerColor = c.bg, topBar = {
        CueTopBar(if (existing == null) "Nova skill" else s.name, { nav.popBackStack() }) {
            if (existing != null && !existing.builtIn) IconButton({ app.store.deleteSkill(id); nav.popBackStack() }) { Icon(Icons.Rounded.Delete, "Excluir", tint = c.err) }
        }
    }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CueField(s.name, { s = s.copy(name = it) }, "Nome")
            CueField(s.description, { s = s.copy(description = it) }, "Quando usar (descrição curta)", minLines = 2)
            CueField(s.instructions, { s = s.copy(instructions = it) }, "Instruções (estilo SKILL.md)", minLines = 10)
            ToggleRow("Ativa", "Disponível para o agente", s.enabled) { s = s.copy(enabled = it) }
            PillButton("Salvar", Icons.Rounded.Check, modifier = Modifier.fillMaxWidth(), enabled = s.name.isNotBlank()) { app.store.upsertSkill(s); nav.popBackStack() }
        }
    }
}

// ============================ AGENDADAS ============================

private val dateFmt = SimpleDateFormat("dd/MM HH:mm", Locale("pt", "BR"))
fun repeatLabel(r: Repeat) = when (r) { Repeat.ONCE -> "Uma vez"; Repeat.HALF_HOUR -> "30 min"; Repeat.HOURLY -> "1 hora"; Repeat.DAILY -> "Diário"; Repeat.WEEKLY -> "Semanal" }

@Composable
fun ScheduledScreen(nav: NavController) {
    val app = CueApp.instance
    val st by app.store.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val c = Cue.c
    Scaffold(containerColor = c.bg, topBar = { CueTopBar("Tarefas agendadas", { nav.popBackStack() }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { nav.navigate("scheduled/new") }, containerColor = c.ink, contentColor = c.bg, shape = CircleShape,
                icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("Agendar") })
        }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (st.scheduled.isEmpty()) item { EmptyState(Icons.Rounded.Schedule, "Nada agendado", "Agende tarefas recorrentes: resumo de notícias diário, relatório semanal, monitoramento de preços…") }
            items(st.scheduled, key = { it.id }) { t ->
                CueCard(onClick = { nav.navigate("scheduled/${t.id}") }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Schedule, null, tint = if (t.enabled) c.accent else c.ink3); Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(t.title, fontWeight = FontWeight.SemiBold)
                            Text("${repeatLabel(t.repeat)} · desde ${dateFmt.format(Date(t.startAt))}", fontSize = 13.sp, color = c.ink2)
                            t.lastRunAt?.let { Text("Última execução: ${dateFmt.format(Date(it))}", fontSize = 12.sp, color = c.ink3) }
                        }
                        Switch(t.enabled, { on -> val n = t.copy(enabled = on); app.store.upsertScheduled(n); Scheduler.schedule(ctx, n) },
                            colors = SwitchDefaults.colors(checkedTrackColor = c.ink, checkedThumbColor = c.bg))
                    }
                    t.lastSessionId?.let { sid ->
                        TextButton({ nav.navigate("chat/$sid") }) { Text("Ver última execução", color = c.accent) }
                    }
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScheduledEditScreen(nav: NavController, id: String) {
    val app = CueApp.instance
    val st by app.store.state.collectAsStateWithLifecycle()
    val existing = st.scheduled.firstOrNull { it.id == id }
    var t by remember { mutableStateOf(existing ?: ScheduledTask(title = "", prompt = "", startAt = System.currentTimeMillis() + 3600_000)) }
    val ctx = LocalContext.current
    val c = Cue.c
    Scaffold(containerColor = c.bg, topBar = {
        CueTopBar(if (existing == null) "Agendar tarefa" else t.title, { nav.popBackStack() }) {
            if (existing != null) IconButton({ Scheduler.cancel(ctx, id); app.store.deleteScheduled(id); nav.popBackStack() }) { Icon(Icons.Rounded.Delete, "Excluir", tint = c.err) }
        }
    }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CueField(t.title, { t = t.copy(title = it) }, "Nome")
            CueField(t.prompt, { t = t.copy(prompt = it) }, "O que o agente deve fazer", minLines = 5)
            SectionLabel("Repetição")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Repeat.entries.forEach { r -> Chip(repeatLabel(r), t.repeat == r) { t = t.copy(repeat = r) } } }
            SectionLabel("Início")
            Chip(dateFmt.format(Date(t.startAt)), icon = Icons.Rounded.Event) {
                val cal = Calendar.getInstance().apply { timeInMillis = t.startAt }
                DatePickerDialog(ctx, { _, y, m, d ->
                    TimePickerDialog(ctx, { _, h, min ->
                        cal.set(y, m, d, h, min, 0); t = t.copy(startAt = cal.timeInMillis)
                    }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
                }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
            }
            SectionLabel("Agent")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Padrão", t.agentId == null) { t = t.copy(agentId = null) }
                st.agents.forEach { a -> Chip(a.name, t.agentId == a.id) { t = t.copy(agentId = a.id) } }
            }
            Spacer(Modifier.height(8.dp))
            PillButton("Salvar", Icons.Rounded.Check, modifier = Modifier.fillMaxWidth(), enabled = t.title.isNotBlank() && t.prompt.isNotBlank()) {
                app.store.upsertScheduled(t); Scheduler.schedule(ctx, t); nav.popBackStack()
            }
            if (existing != null) PillButton("Executar agora", Icons.Rounded.PlayArrow, primary = false, modifier = Modifier.fillMaxWidth()) {
                app.store.upsertScheduled(t); Scheduler.runNow(ctx, t.id)
            }
        }
    }
}

// ============================ KNOWLEDGE ============================

@Composable
fun KnowledgeScreen(nav: NavController) {
    val app = CueApp.instance
    val st by app.store.state.collectAsStateWithLifecycle()
    val c = Cue.c
    Scaffold(containerColor = c.bg, topBar = { CueTopBar("Knowledge", { nav.popBackStack() }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { nav.navigate("knowledge/new") }, containerColor = c.ink, contentColor = c.bg, shape = CircleShape,
                icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("Adicionar") })
        }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("Fatos, preferências e contexto que o agente sempre considera — sobre você, sua empresa, seus padrões de entrega.",
                    color = c.ink2, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp))
            }
            if (st.knowledge.isEmpty()) item { EmptyState(Icons.Rounded.LibraryBooks, "Sem knowledge", "Ex.: \"Sempre entregue relatórios em PT-BR com tabela de fontes.\"") }
            items(st.knowledge, key = { it.id }) { k ->
                CueCard(onClick = { nav.navigate("knowledge/${k.id}") }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(k.title, fontWeight = FontWeight.SemiBold)
                            Text(k.content, fontSize = 13.sp, color = c.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Switch(k.enabled, { app.store.upsertKnowledge(k.copy(enabled = it)) }, colors = SwitchDefaults.colors(checkedTrackColor = c.ink, checkedThumbColor = c.bg))
                    }
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

@Composable
fun KnowledgeEditScreen(nav: NavController, id: String) {
    val app = CueApp.instance
    val existing = app.store.state.value.knowledge.firstOrNull { it.id == id }
    var k by remember { mutableStateOf(existing ?: KnowledgeItem(title = "", content = "")) }
    val c = Cue.c
    Scaffold(containerColor = c.bg, topBar = {
        CueTopBar(if (existing == null) "Novo knowledge" else k.title, { nav.popBackStack() }) {
            if (existing != null) IconButton({ app.store.deleteKnowledge(id); nav.popBackStack() }) { Icon(Icons.Rounded.Delete, "Excluir", tint = c.err) }
        }
    }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CueField(k.title, { k = k.copy(title = it) }, "Título")
            CueField(k.content, { k = k.copy(content = it) }, "Conteúdo", minLines = 8)
            PillButton("Salvar", Icons.Rounded.Check, modifier = Modifier.fillMaxWidth(), enabled = k.title.isNotBlank()) { app.store.upsertKnowledge(k); nav.popBackStack() }
        }
    }
}


/** Busca no catálogo público de skills (Skyll, milhares de SKILL.md do GitHub) e instala com um toque. */
@Composable
private fun SkillMarketplace() {
    val app = CueApp.instance
    val st by app.store.state.collectAsStateWithLifecycle()
    val c = Cue.c
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<SkillHub.Remote>>(emptyList()) }
    var preview by remember { mutableStateOf<SkillHub.Remote?>(null) }
    fun go() {
        if (q.isBlank()) return
        loading = true; error = null
        scope.launch {
            runCatching { SkillHub.search(q.trim()) }
                .onSuccess { results = it; if (it.isEmpty()) error = "Nenhuma skill encontrada para \"$q\"." }
                .onFailure { error = "Falha ao buscar: ${it.message}" }
            loading = false
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("Marketplace de skills")
        OutlinedTextField(
            value = q, onValueChange = { q = it }, modifier = Modifier.fillMaxWidth(),
            singleLine = true, shape = RoundedCornerShape(20.dp),
            placeholder = { Text("Buscar skills (ex.: pdf, excel, seo…)", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Icon(Icons.Rounded.Search, null, tint = c.ink2) },
            trailingIcon = {
                if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = c.accent)
                else IconButton(onClick = { go() }, enabled = q.isNotBlank()) { Icon(Icons.Rounded.ArrowForward, "Buscar", tint = if (q.isNotBlank()) c.ink else c.ink3) }
            },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { go() }),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.accent, unfocusedBorderColor = c.line, focusedContainerColor = c.surface, unfocusedContainerColor = c.surface)
        )
        error?.let { Text(it, color = c.ink2, fontSize = 13.sp) }
        results.forEach { r ->
            val installed = st.skills.any { it.name.equals(r.title.take(60), true) }
            CueCard(onClick = { preview = r }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(r.description, fontSize = 13.sp, color = c.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val meta = listOfNotNull(r.source.takeIf { it.isNotBlank() }?.take(50), r.installs.takeIf { it > 0 }?.let { "$it instalações" }).joinToString(" · ")
                        if (meta.isNotBlank()) Text(meta, fontSize = 11.sp, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width(8.dp))
                    if (installed) Icon(Icons.Rounded.CheckCircle, "Instalada", tint = c.ok)
                    else Box(Modifier.clip(CircleShape).background(c.ink).clickable { app.store.upsertSkill(SkillHub.toSkill(r)) }.padding(horizontal = 14.dp, vertical = 8.dp)) {
                        Text("Adicionar", color = c.bg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
    preview?.let { r ->
        AlertDialog(onDismissRequest = { preview = null }, title = { Text(r.title) },
            text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(r.description, color = c.ink2, fontSize = 13.sp); Spacer(Modifier.height(8.dp))
                Text(r.content.ifBlank { "(sem conteúdo)" }.take(6000), fontSize = 12.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            } },
            confirmButton = { TextButton({ app.store.upsertSkill(SkillHub.toSkill(r)); preview = null }) { Text("Adicionar") } },
            dismissButton = { TextButton({ preview = null }) { Text("Fechar") } })
    }
}


// ============================ MEMÓRIA ============================

/** Memória de longo prazo (global e por Agent): o agente recupera só o que é relevante a cada passo. */
@Composable
fun MemoryScreen(nav: NavController) {
    val app = CueApp.instance
    val st by app.store.state.collectAsStateWithLifecycle()
    val c = Cue.c
    var q by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    val list = st.memories.filter { q.isBlank() || it.text.contains(q, true) || it.tags.any { t -> t.contains(q, true) } }.sortedByDescending { it.lastUsedAt }
    Scaffold(containerColor = c.bg, topBar = { CueTopBar("Memória", { nav.popBackStack() }) }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                // card de destaque
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.accentSoft).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(c.accent), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Psychology, null, tint = Color.White)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${st.memories.size} memórias", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = c.ink)
                        Text("Só as relevantes entram no contexto a cada passo — economiza tokens.", fontSize = 12.sp, color = c.ink2)
                    }
                }
            }
            item {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(16.dp)).padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Add, null, tint = c.ink3, modifier = Modifier.size(20.dp))
                    BasicTextField(new, { new = it }, Modifier.weight(1f).padding(14.dp), singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.ink), cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent),
                        decorationBox = { inner -> if (new.isEmpty()) Text("Adicionar uma memória…", color = c.ink3); inner() })
                    if (new.isNotBlank()) TextButton({ app.store.upsertMemory(MemoryItem(text = new.trim())); new = "" }) { Text("Salvar", color = c.accent) }
                }
            }
            item {
                OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(16.dp),
                    placeholder = { Text("Buscar memórias") }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.ink, unfocusedBorderColor = c.line, focusedContainerColor = c.surface, unfocusedContainerColor = c.surface))
            }
            if (list.isEmpty()) item { EmptyState(Icons.Rounded.Psychology, "Sem memórias", "Elas aparecem aqui conforme o agente aprende sobre você e seus projetos.") }
            items(list, key = { it.id }) { m ->
                val agentName = m.agentId?.let { id -> st.agents.firstOrNull { it.id == id }?.name }
                CueCard {
                    Text(m.text, fontSize = 14.sp, lineHeight = 20.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.clip(CircleShape).background(if (agentName != null) c.accentSoft else c.surface2).padding(horizontal = 10.dp, vertical = 4.dp)) {
                            Text(agentName?.let { "⬡ $it" } ?: "Global", fontSize = 11.sp, color = if (agentName != null) c.accent else c.ink2, fontWeight = FontWeight.Medium)
                        }
                        if (m.tags.isNotEmpty()) { Spacer(Modifier.width(8.dp)); Text(m.tags.joinToString(" · "), fontSize = 11.sp, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)) }
                        else Spacer(Modifier.weight(1f))
                        if (m.uses > 0) Text("${m.uses}×", fontSize = 11.sp, color = c.ink3)
                        IconButton({ app.store.deleteMemory(m.id) }, Modifier.size(32.dp)) { Icon(Icons.Rounded.Close, "Excluir", Modifier.size(16.dp), tint = c.ink3) }
                    }
                }
            }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }
}

/** Card expansível "Permissões do dispositivo" na criação do Agent. */
@Composable
fun DevicePermissionsCard(a: dev.cueopen.app.data.AgentProfile, onChange: (dev.cueopen.app.data.AgentProfile) -> Unit) {
    val c = Cue.c
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(a.deviceCaps.isNotEmpty()) }
    // Abre sozinho quando outra opção (ex.: autonomia) concede um acesso, para o usuário ver o que ligou.
    LaunchedEffect(a.deviceCaps.size) { if (a.deviceCaps.isNotEmpty()) open = true }
    var tick by remember { mutableIntStateOf(0) }
    var showAccessibilityDisclosure by remember(a.id) { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    fun requestCap(id: String) {
        // O launcher/Settings só é aberto quando o estado real do Android está faltante.
        if (!dev.cueopen.app.device.DeviceCaps.needsAction(ctx, id)) return
        val missing = dev.cueopen.app.device.DeviceCaps.runtimePermissions(ctx, id)
            .filter { ctx.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
        else dev.cueopen.app.device.DeviceCaps.request(ctx, id)
    }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) tick++ }
        lifecycle.lifecycle.addObserver(obs); onDispose { lifecycle.lifecycle.removeObserver(obs) }
    }
    SectionLabel("Permissões do dispositivo")
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).border(1.dp, c.line, RoundedCornerShape(16.dp)).background(c.surface)) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.PhoneAndroid, null, tint = c.accent); Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Acesso ao Android", fontWeight = FontWeight.SemiBold)
                Text(if (a.deviceCaps.isEmpty()) "Nenhuma permissão" else a.deviceCaps.size.toString() + " ativa(s)", fontSize = 12.sp, color = c.ink2)
            }
            Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = c.ink3)
        }
        if (open) {
            HorizontalDivider(color = c.line)
            dev.cueopen.app.device.DeviceCaps.all.forEach { cap ->
                tick.let {}
                val on = cap.id in a.deviceCaps
                val granted = !dev.cueopen.app.device.DeviceCaps.needsAction(ctx, cap.id)
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(cap.title, fontWeight = FontWeight.Medium)
                        Text(cap.desc, fontSize = 12.sp, color = c.ink2)
                        if (on) Text(dev.cueopen.app.device.DeviceCaps.status(ctx, cap.id), fontSize = 11.sp,
                            color = if (granted) c.ok else c.warn,
                            modifier = if (granted) Modifier
                                else Modifier.clickable { requestCap(cap.id) })
                    }
                    Switch(on, { v ->
                        if (cap.id == dev.cueopen.app.device.DeviceCaps.ACCESSIBILITY && v) {
                            showAccessibilityDisclosure = true
                        } else {
                            val updated = if (cap.id == dev.cueopen.app.device.DeviceCapPolicy.BACKGROUND) {
                                dev.cueopen.app.device.BrowserAutonomyPolicy.setBackgroundEnabled(a, v)
                            } else {
                                a.copy(deviceCaps = if (v) (a.deviceCaps + cap.id).distinct() else a.deviceCaps - cap.id)
                            }
                            onChange(updated)
                            if (v) requestCap(cap.id)
                        }
                    }, colors = SwitchDefaults.colors(checkedTrackColor = c.ink, checkedThumbColor = c.bg))
                }
            }
            Text("O agente só usa uma capacidade se ela estiver ligada neste perfil e concedida pelo Android. A acessibilidade é concedida ao app pelo Android, mas o toggle do perfil bloqueia cada Agent separadamente; desligue-o aqui ou revogue o serviço nas Configurações. Ligações e eventos exigem confirmação no Android; ciclos em background dependem dos limites do sistema.",
                fontSize = 11.sp, color = c.ink3, modifier = Modifier.padding(14.dp))
        }
    }
    if (showAccessibilityDisclosure) {
        AlertDialog(
            onDismissRequest = { showAccessibilityDisclosure = false },
            title = { Text("Navegar em outros aplicativos?") },
            text = {
                Text(
                    "O serviço poderá ler texto e controles visíveis e tocar, rolar ou preencher campos para executar tarefas que você pedir. Esse conteúdo pode ser enviado ao modelo configurado e permanecer no histórico local da tarefa até você excluí-la. Campos de senha são ocultados; não serão feitas capturas de tela. O Android concede o serviço ao app inteiro, mas somente Agents com este toggle ligado recebem as ferramentas. Você pode desligar o Agent aqui ou revogar globalmente em Configurações > Acessibilidade > Fluwy Bot.",
                    modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showAccessibilityDisclosure = false
                    onChange(a.copy(deviceCaps = (a.deviceCaps + dev.cueopen.app.device.DeviceCaps.ACCESSIBILITY).distinct()))
                    if (!dev.cueopen.app.device.DeviceCaps.granted(ctx, dev.cueopen.app.device.DeviceCaps.ACCESSIBILITY)) {
                        dev.cueopen.app.device.DeviceCaps.request(ctx, dev.cueopen.app.device.DeviceCaps.ACCESSIBILITY)
                    }
                }) { Text("Entendi, continuar") }
            },
            dismissButton = {
                TextButton(onClick = { showAccessibilityDisclosure = false }) { Text("Cancelar") }
            },
        )
    }
}
