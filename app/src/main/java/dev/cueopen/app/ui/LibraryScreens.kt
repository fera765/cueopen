package dev.cueopen.app.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
    var a by remember { mutableStateOf(existing ?: AgentProfile(name = "")) }
    val c = Cue.c
    val walletPolicies = remember { WalletAgentPolicyStore(app.applicationContext) }
    var walletAccess by remember(a.id) {
        mutableStateOf(
            walletPolicies.canAgentReadAddress(a.id) ||
                walletPolicies.canAgentReadBalance(a.id) ||
                walletPolicies.canAgentRequestTransfer(a.id)
        )
    }
    var walletReadAddress by remember(a.id) { mutableStateOf(walletPolicies.canAgentReadAddress(a.id)) }
    var walletReadBalance by remember(a.id) { mutableStateOf(walletPolicies.canAgentReadBalance(a.id)) }
    var walletRequestTransfer by remember(a.id) { mutableStateOf(walletPolicies.canAgentRequestTransfer(a.id)) }
    Scaffold(containerColor = c.bg, topBar = {
        CueTopBar(if (existing == null) "Criar Agent" else a.name, { nav.popBackStack() }) {
            if (existing != null) IconButton({ app.store.deleteAgent(id); nav.popBackStack() }) { Icon(Icons.Rounded.Delete, "Excluir", tint = c.err) }
        }
    }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { AgentFace(Color(a.color), a.face, 96.dp) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                agentColors.forEach { col ->
                    Box(Modifier.padding(4.dp).size(28.dp).clip(CircleShape).background(Color(col))
                        .border(if (a.color == col) 3.dp else 0.dp, c.ink, CircleShape).clickable { a = a.copy(color = col) })
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                agentFaces.forEach { f ->
                    Box(Modifier.padding(4.dp).clip(RoundedCornerShape(12.dp)).border(if (a.face == f) 2.dp else 1.dp, if (a.face == f) c.ink else c.line, RoundedCornerShape(12.dp))
                        .clickable { a = a.copy(face = f) }.padding(6.dp)) { AgentFace(Color(a.color), f, 32.dp) }
                }
            }
            CueField(a.name, { a = a.copy(name = it) }, "Nome do Agent")
            CueField(a.instructions, { a = a.copy(instructions = it) }, "Instruções (como deve agir, tom, objetivos)", minLines = 5)
            ModelPicker(a.model ?: "", { a = a.copy(model = it.ifBlank { null }) }, "Modelo (vazio = padrão do app)", allowEmpty = true)
            SectionLabel("Autonomia")
            ToggleRow("Pode trabalhar de forma autônoma", "Quando ligado, o próprio agente decide, pela conversa, se e de quanto em quanto tempo deve acordar para seguir uma tarefa contínua. Sem um pedido assim, ele fica dormindo.", a.autonomous) { a = a.copy(autonomous = it) }
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
            DevicePermissionsCard(a) { a = it }
            SectionLabel("Ferramentas")
            ToggleRow("Terminal Linux", "Executar comandos no sandbox", a.useTerminal) { a = a.copy(useTerminal = it) }
            ToggleRow("Browser", "Navegar e interagir com sites", a.useBrowser) { a = a.copy(useBrowser = it) }
            SectionLabel("Carteira Solana")
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
                    ToggleRow("Solicitar transferência", "Permite criar uma solicitação para revisão humana. Não permite assinatura ou envio automático.", walletRequestTransfer) { walletRequestTransfer = it }
                    Text(
                        "As transferências continuam sujeitas aos limites locais e à aprovação explícita do usuário.",
                        fontSize = 12.sp,
                        color = c.ink2
                    )
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
                app.store.upsertAgent(a)
                walletPolicies.setAgentReadAddress(a.id, walletAccess && walletReadAddress)
                walletPolicies.setAgentReadBalance(a.id, walletAccess && walletReadBalance)
                walletPolicies.setAgentRequestTransfer(a.id, walletAccess && walletRequestTransfer)
                if (a.autonomous) app.agent.startAutonomy()
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
    var tick by remember { mutableIntStateOf(0) }
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
                val granted = dev.cueopen.app.device.DeviceCaps.granted(ctx, cap.id)
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(cap.title, fontWeight = FontWeight.Medium)
                        Text(cap.desc, fontSize = 12.sp, color = c.ink2)
                        if (on && !granted) Text("Toque para conceder no Android →", fontSize = 11.sp, color = c.warn,
                            modifier = Modifier.clickable { dev.cueopen.app.device.DeviceCaps.request(ctx, cap.id) })
                        else if (on && granted) Text("Concedida", fontSize = 11.sp, color = c.ok)
                    }
                    Switch(on, { v ->
                        onChange(a.copy(deviceCaps = if (v) a.deviceCaps + cap.id else a.deviceCaps - cap.id))
                        if (v && !granted) dev.cueopen.app.device.DeviceCaps.request(ctx, cap.id)
                    }, colors = SwitchDefaults.colors(checkedTrackColor = c.ink, checkedThumbColor = c.bg))
                }
            }
            Text("As permissões são concedidas por você nas telas do Android. O agente só usa o que estiver ligado aqui e concedido.",
                fontSize = 11.sp, color = c.ink3, modifier = Modifier.padding(14.dp))
        }
    }
}
