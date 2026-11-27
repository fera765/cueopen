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

/** Memória de longo prazo (global e por Agent): o agente recupera só o que é relevante a cada passo. */
@Composable
fun MemoryScreen(nav: NavController) {
    val app = FluwyApp.instance
    val st by app.store.state.collectAsStateWithLifecycle()
    val c = Fluwy.c
    var q by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    val list = st.memories.filter { q.isBlank() || it.text.contains(q, true) || it.tags.any { t -> t.contains(q, true) } }.sortedByDescending { it.lastUsedAt }
    // Modo seleção ("Excluir…"): checkbox em cada memória + "Selecionar tudo"; nada é apagado até confirmar no rodapé.
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    // Segundo cérebro: abre sempre no grafo; a lista fica a um toque.
    var graph by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(true) }
    androidx.activity.compose.BackHandler(enabled = selecting) { selecting = false; selected = emptySet() }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false },
        title = { Text("Excluir ${selected.size} memória${if (selected.size == 1) "" else "s"}?") },
        text = { Text("Essa ação não pode ser desfeita.") },
        confirmButton = { TextButton({ app.store.deleteMemories(selected); confirmDelete = false; selecting = false; selected = emptySet() }) { Text("Excluir", color = c.err) } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancelar") } })
    Scaffold(containerColor = c.bg, topBar = {
        FluwyTopBar("Memória", { if (selecting) { selecting = false; selected = emptySet() } else nav.popBackStack() }) {
            if (!selecting) IconButton({ graph = !graph }) {
                Icon(if (graph) Icons.Rounded.ViewList else Icons.Rounded.Hub, if (graph) "Ver em lista" else "Ver segundo cérebro", tint = c.ink)
            }
            if (!graph && !selecting && st.memories.isNotEmpty()) TextButton({ selecting = true; selected = emptySet() }) {
                Icon(Icons.Rounded.DeleteSweep, null, Modifier.size(18.dp), tint = c.err); Spacer(Modifier.width(4.dp)); Text("Excluir…", color = c.err)
            }
        }
    }, bottomBar = {
        if (selecting) Row(Modifier.fillMaxWidth().background(c.surface).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton({ selecting = false; selected = emptySet() }, Modifier.weight(1f)) { Text("Cancelar") }
            Button({ confirmDelete = true }, Modifier.weight(1f), enabled = selected.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = c.err, contentColor = Color.White)) {
                Icon(Icons.Rounded.DeleteOutline, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Deletar (${selected.size})")
            }
        }
    }) { pad ->
        if (graph) { MemoryBrainView(st.memories, st.agents, Modifier.padding(pad)) { app.store.deleteMemory(it) }; return@Scaffold }
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
            if (selecting && list.isNotEmpty()) item {
                val allIds = list.map { it.id }.toSet()
                val all = selected.containsAll(allIds)
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface2)
                    .clickable { selected = if (all) selected - allIds else selected + allIds }.padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(all, { v -> selected = if (v) selected + allIds else selected - allIds }, colors = CheckboxDefaults.colors(checkedColor = c.err))
                    Text("Selecionar tudo" + if (q.isNotBlank()) " (da busca)" else "", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text("${selected.size} selecionada${if (selected.size == 1) "" else "s"}", fontSize = 12.sp, color = c.ink3, modifier = Modifier.padding(end = 10.dp))
                }
            }
            items(list, key = { it.id }) { m ->
                val agentName = m.agentId?.let { id -> st.agents.firstOrNull { it.id == id }?.name }
                val isSel = m.id in selected
                FluwyCard(onClick = if (selecting) ({ selected = if (isSel) selected - m.id else selected + m.id }) else null) {
                    if (selecting) Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(isSel, { v -> selected = if (v) selected + m.id else selected - m.id }, colors = CheckboxDefaults.colors(checkedColor = c.err))
                        Text(if (isSel) "Selecionada" else "Toque para selecionar", fontSize = 12.sp, color = c.ink3)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(memoryKindColor(dev.fluwy.app.agent.MemoryGraph.kindOf(m))))
                        Spacer(Modifier.width(6.dp))
                        Text(dev.fluwy.app.agent.MemoryGraph.titleOf(m), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(dev.fluwy.app.agent.MemoryGraph.plain(m.text), fontSize = 14.sp, lineHeight = 20.sp)
                    if (m.links.isNotEmpty()) Text("→ " + m.links.mapNotNull { id -> st.memories.firstOrNull { it.id == id } }.joinToString(", ") { "[[${dev.fluwy.app.agent.MemoryGraph.titleOf(it)}]]" },
                        fontSize = 12.sp, color = c.accent, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.clip(CircleShape).background(if (agentName != null) c.accentSoft else c.surface2).padding(horizontal = 10.dp, vertical = 4.dp)) {
                            Text(agentName?.let { "⬡ $it" } ?: "Global", fontSize = 11.sp, color = if (agentName != null) c.accent else c.ink2, fontWeight = FontWeight.Medium)
                        }
                        if (m.tags.isNotEmpty()) { Spacer(Modifier.width(8.dp)); Text(m.tags.joinToString(" · "), fontSize = 11.sp, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)) }
                        else Spacer(Modifier.weight(1f))
                        if (m.uses > 0) Text("${m.uses}×", fontSize = 11.sp, color = c.ink3)
                        if (!selecting) IconButton({ app.store.deleteMemory(m.id) }, Modifier.size(32.dp)) { Icon(Icons.Rounded.Close, "Excluir", Modifier.size(16.dp), tint = c.ink3) }
                    }
                }
            }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }
}

/** Card expansível "Permissões do dispositivo" na criação do Agent. */
@Composable
fun DevicePermissionsCard(a: dev.fluwy.app.data.AgentProfile, onChange: (dev.fluwy.app.data.AgentProfile) -> Unit) {
    val c = Fluwy.c
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(a.deviceCaps.isNotEmpty()) }
    // Abre sozinho quando outra opção (ex.: autonomia) concede um acesso, para o usuário ver o que ligou.
    LaunchedEffect(a.deviceCaps.size) { if (a.deviceCaps.isNotEmpty()) open = true }
    var tick by remember { mutableIntStateOf(0) }
    var showAccessibilityDisclosure by remember(a.id) { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }
    fun requestCap(id: String) {
        // O launcher/Settings só é aberto quando o estado real do Android está faltante.
        if (!dev.fluwy.app.device.DeviceCaps.needsAction(ctx, id)) return
        val missing = dev.fluwy.app.device.DeviceCaps.runtimePermissions(ctx, id)
            .filter { ctx.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
        else dev.fluwy.app.device.DeviceCaps.request(ctx, id)
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
            dev.fluwy.app.device.DeviceCaps.all.forEach { cap ->
                tick.let {}
                val on = cap.id in a.deviceCaps
                val granted = !dev.fluwy.app.device.DeviceCaps.needsAction(ctx, cap.id)
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(cap.title, fontWeight = FontWeight.Medium)
                        Text(cap.desc, fontSize = 12.sp, color = c.ink2)
                        if (on) Text(dev.fluwy.app.device.DeviceCaps.status(ctx, cap.id), fontSize = 11.sp,
                            color = if (granted) c.ok else c.warn,
                            modifier = if (granted) Modifier
                                else Modifier.clickable { requestCap(cap.id) })
                    }
                    Switch(on, { v ->
                        if (cap.id == dev.fluwy.app.device.DeviceCaps.ACCESSIBILITY && v) {
                            showAccessibilityDisclosure = true
                        } else {
                            val updated = if (cap.id == dev.fluwy.app.device.DeviceCapPolicy.BACKGROUND) {
                                dev.fluwy.app.device.BrowserAutonomyPolicy.setBackgroundEnabled(a, v)
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
                    onChange(a.copy(deviceCaps = (a.deviceCaps + dev.fluwy.app.device.DeviceCaps.ACCESSIBILITY).distinct()))
                    if (!dev.fluwy.app.device.DeviceCaps.granted(ctx, dev.fluwy.app.device.DeviceCaps.ACCESSIBILITY)) {
                        dev.fluwy.app.device.DeviceCaps.request(ctx, dev.fluwy.app.device.DeviceCaps.ACCESSIBILITY)
                    }
                }) { Text("Entendi, continuar") }
            },
            dismissButton = {
                TextButton(onClick = { showAccessibilityDisclosure = false }) { Text("Cancelar") }
            },
        )
    }
}
