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

/** Gatilhos: acordam uma conversa quando chega algo novo numa fonte (conector do hub ou página web). */
@Composable
internal fun TriggersSection(nav: NavController) {
    val ctx = LocalContext.current
    val c = Fluwy.c
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { dev.fluwy.app.triggers.TriggerStore.load(ctx) }
    val list by dev.fluwy.app.triggers.TriggerStore.all.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    if (creating) NewTriggerDialog { creating = false }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Gatilhos"); Spacer(Modifier.weight(1f))
            TextButton({ creating = true }) { Text("+ Novo gatilho", color = c.accent) }
        }
        if (list.isEmpty()) Text("Nenhum gatilho. Peça a um agente, por exemplo: \"quando chegar e-mail de cliente, resuma e prepare a resposta\".", fontSize = 12.sp, color = c.ink3)
        list.forEach { t ->
            var menu by remember(t.id) { mutableStateOf(false) }
            FluwyCard(onClick = { nav.navigate("chat/${t.sessionId}") }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("⚡", fontSize = 18.sp); Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(t.name, fontWeight = FontWeight.SemiBold)
                        Text((if (t.source == "site") t.url else dev.fluwy.app.connectors.HubConnectors.appName(dev.fluwy.app.connectors.HubApi.pieceName(t.piece)) + " · " + t.action) +
                            " · a cada ${t.intervalMinutes} min", fontSize = 12.sp, color = c.ink2, maxLines = 1)
                        Text(when {
                            t.lastError != null -> "Erro: ${t.lastError.take(80)}"
                            t.lastRunAt == null -> "Aguardando a 1ª verificação"
                            else -> "Verificado ${dateFmt.format(Date(t.lastRunAt))} · ${t.history.sumOf { it.count }} evento(s)"
                        }, fontSize = 11.sp, color = if (t.lastError != null) c.err else c.ink3)
                    }
                    Switch(t.enabled, { on -> val n = t.copy(enabled = on); dev.fluwy.app.triggers.TriggerStore.upsert(ctx, n); dev.fluwy.app.triggers.TriggerEngine.schedule(ctx, n) },
                        colors = SwitchDefaults.colors(checkedTrackColor = c.ink, checkedThumbColor = c.bg))
                    Box {
                        IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, "Opções") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("Verificar agora") }, onClick = { menu = false
                                scope.launch {
                                    val r = dev.fluwy.app.triggers.TriggerEngine.check(ctx.applicationContext as FluwyApp, t.id)
                                    android.widget.Toast.makeText(ctx, r, android.widget.Toast.LENGTH_LONG).show()
                                } })
                            DropdownMenuItem(text = { Text("Remover", color = c.err) }, onClick = { menu = false
                                dev.fluwy.app.triggers.TriggerEngine.cancel(ctx, t.id); dev.fluwy.app.triggers.TriggerStore.remove(ctx, t.id) })
                        }
                    }
                }
            }
        }
    }
}


/** Criação manual de gatilho: conversa alvo, fonte (conector do hub ou site), filtro, instrução e intervalo. */
@Composable
internal fun NewTriggerDialog(onDismiss: () -> Unit) {
    val app = FluwyApp.instance
    val ctx = LocalContext.current
    val c = Fluwy.c
    val scope = rememberCoroutineScope()
    val st by app.store.state.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf("") }
    var source by remember { mutableStateOf("connector") }
    var sessionId by remember { mutableStateOf(st.sessions.maxByOrNull { it.updatedAt }?.id.orEmpty()) }
    var conns by remember { mutableStateOf<List<dev.fluwy.app.connectors.HubConnectors.Conn>>(emptyList()) }
    var conn by remember { mutableStateOf<dev.fluwy.app.connectors.HubConnectors.Conn?>(null) }
    var actions by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var action by remember { mutableStateOf("") }
    var fields by remember { mutableStateOf("{}") }
    var url by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("") }
    var instruction by remember { mutableStateOf("") }
    var interval by remember { mutableStateOf("15") }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(source) { if (source == "connector") runCatching { dev.fluwy.app.connectors.HubConnectors.connections(app.store) }.onSuccess { conns = it }.onFailure { err = it.message } }
    LaunchedEffect(conn) {
        val cn = conn ?: return@LaunchedEffect
        runCatching { dev.fluwy.app.connectors.HubApi.piece(cn.pieceName) }.onSuccess { meta ->
            actions = (meta["actions"] as? kotlinx.serialization.json.JsonObject).orEmpty().values.mapNotNull { it as? kotlinx.serialization.json.JsonObject }
                .map { (it["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty() to (it["displayName"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty() }
        }.onFailure { err = it.message }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Novo gatilho") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FluwyField(name, { name = it.take(60) }, "Nome (ex.: E-mails de clientes)")
            Text("Conversa que será acordada", fontSize = 12.sp, color = c.ink2)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                st.sessions.sortedByDescending { it.updatedAt }.take(12).forEach { ss -> FilterChip(sessionId == ss.id, { sessionId = ss.id }, { Text(ss.title.take(24), fontSize = 11.sp) }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(source == "connector", { source = "connector" }, { Text("Conector do hub") })
                FilterChip(source == "site", { source = "site" }, { Text("Página web") })
            }
            if (source == "connector") {
                if (conns.isEmpty()) Text("Nenhum conector conectado no hub.", fontSize = 12.sp, color = c.ink3)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    conns.forEach { cn -> FilterChip(conn == cn, { conn = cn; action = "" }, { Text(dev.fluwy.app.connectors.HubConnectors.appName(cn.pieceName) + " · " + cn.displayName.take(16), fontSize = 11.sp) }) }
                }
                if (actions.isNotEmpty()) {
                    Text("Ação de leitura", fontSize = 12.sp, color = c.ink2)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        actions.forEach { (n, d) -> FilterChip(action == n, { action = n }, { Text(d.ifBlank { n }.take(28), fontSize = 11.sp) }) }
                    }
                }
                FluwyField(fields, { fields = it }, "Campos da ação em JSON (ex.: {\"limit\":10})")
            } else FluwyField(url, { url = it.trim() }, "Endereço da página (https://…)")
            FluwyField(filter, { filter = it.take(200) }, "Só quando contiver (palavras, opcional)")
            FluwyField(instruction, { instruction = it.take(800) }, "O que o agente deve fazer", minLines = 2)
            FluwyField(interval, { interval = it.filter { ch -> ch.isDigit() }.take(4) }, "Verificar a cada (min, mínimo 15)")
            err?.let { Text(it, color = c.err, fontSize = 12.sp) }
        }
    }, confirmButton = {
        if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = c.accent)
        else TextButton({
            err = null
            val input = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(fields.ifBlank { "{}" }) as kotlinx.serialization.json.JsonObject }.getOrNull()
            err = when {
                name.isBlank() -> "Dê um nome ao gatilho."
                sessionId.isBlank() -> "Escolha a conversa."
                instruction.isBlank() -> "Diga o que o agente deve fazer."
                source == "connector" && (conn == null || action.isBlank()) -> "Escolha o conector e a ação de leitura."
                source == "connector" && input == null -> "Campos precisam ser um objeto JSON."
                source == "site" && !url.startsWith("http") -> "Informe um endereço http(s)."
                else -> null
            }
            if (err != null) return@TextButton
            val t = dev.fluwy.app.triggers.Trigger(name = name.trim(), sessionId = sessionId, agentId = app.store.session(sessionId)?.agentId, source = source,
                piece = conn?.pieceName.orEmpty(), action = action, input = (input ?: kotlinx.serialization.json.JsonObject(emptyMap())).toString(), connection = conn?.externalId.orEmpty(),
                url = url, filter = filter.trim(), instruction = instruction.trim(), intervalMinutes = (interval.toIntOrNull() ?: 15).coerceIn(15, 1440))
            dev.fluwy.app.triggers.TriggerStore.upsert(ctx, t); dev.fluwy.app.triggers.TriggerEngine.schedule(ctx, t)
            busy = true
            scope.launch {
                val r = dev.fluwy.app.triggers.TriggerEngine.check(ctx.applicationContext as FluwyApp, t.id)
                busy = false
                android.widget.Toast.makeText(ctx, "Gatilho criado · $r", android.widget.Toast.LENGTH_LONG).show()
                onDismiss()
            }
        }) { Text("Criar") }
    }, dismissButton = { TextButton(onDismiss) { Text("Cancelar") } })
}


/** Agentes bots prontos: tocar abre o agente preenchido para revisar; só é adicionado ao Salvar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentBotsScreen(nav: NavController) {
    val c = Fluwy.c
    val all = remember { dev.fluwy.app.agent.BotCatalog.all() }
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<String?>(null) }
    val categories = remember(all) { all.map { it.category to it.categoryLabel }.distinct().sortedBy { it.second } }
    val shown = remember(query, category, all) { dev.fluwy.app.agent.BotCatalog.search(all, query, category) }
    Scaffold(containerColor = c.bg, topBar = { FluwyTopBar("Agentes bots", { nav.popBackStack() }) }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("Agentes bots prontos. Toque em um para revisar nome, instruções e permissões; ele só entra nos seus agentes quando você tocar em Salvar.",
                    fontSize = 13.sp, color = c.ink2)
            }
            item { FluwyField(query, { query = it }, "Buscar bots") }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(category == null, { category = null }, { Text("Todos", fontSize = 12.sp) })
                    categories.forEach { (id, label) -> FilterChip(category == id, { category = if (category == id) null else id }, { Text(label, fontSize = 12.sp) }) }
                }
            }
            if (all.isEmpty()) item { EmptyState(Icons.Rounded.SmartToy, "Lista indisponível", "O catálogo de bots não veio neste APK.") }
            else if (shown.isEmpty()) item { Text("Nenhum bot com \"$query\".", fontSize = 13.sp, color = c.ink3) }
            items(shown, key = { it.id }) { b ->
                FluwyCard(onClick = { nav.navigate("agent/new?bot=${b.id}") }) {
                    Row(verticalAlignment = Alignment.Top) {
                        AgentFace(Color(dev.fluwy.app.agent.BotCatalog.colorFor(b)), dev.fluwy.app.agent.BotCatalog.faceFor(b), 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(b.name, fontWeight = FontWeight.SemiBold)
                            Text(b.categoryLabel, fontSize = 11.sp, color = c.accent)
                            Text(b.summary, fontSize = 13.sp, color = c.ink2, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(40.dp)) }
        }
    }
}
