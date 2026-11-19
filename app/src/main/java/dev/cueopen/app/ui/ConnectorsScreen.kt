package dev.cueopen.app.ui

import androidx.compose.foundation.verticalScroll

import androidx.compose.foundation.rememberScrollState

import androidx.compose.foundation.horizontalScroll

import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.cueopen.app.CueApp
import dev.cueopen.app.connectors.Connectors
import dev.cueopen.app.connectors.ActivepiecesHub
import dev.cueopen.app.data.Connector
import dev.cueopen.app.ui.theme.Cue
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Conectores MCP com OAuth público: um toque abre o login do serviço; sem API key, sem client ID. */
@Composable
fun ConnectorsScreen(nav: NavController) {
    val app = CueApp.instance
    val st by app.store.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val c = Cue.c
    var busy by remember { mutableStateOf<String?>(null) }
    var installing by remember { mutableStateOf<String?>(null) }
    var custom by remember { mutableStateOf(false) }
    var tokenFor by remember { mutableStateOf<Connector?>(null) }
    var policyFor by remember { mutableStateOf<Connector?>(null) }
    fun openUrl(url: String) {
        runCatching { CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(ctx, Uri.parse(url)) }
            .onFailure { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url))) }
    }
    fun remove(conn: Connector) = app.store.deleteConnector(conn.id)

    fun connect(conn: Connector) {
        busy = conn.id
        app.store.upsertConnector(conn)
        scope.launch {
            runCatching {
                val result = Connectors.connect(ctx, app.store, conn) { url ->
                    runCatching { CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(ctx, Uri.parse(url)) }
                        .onFailure { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url))) }
                }
                if (result.lastError != null) app.store.upsertConnector(result)
            }.onFailure { e -> app.store.upsertConnector((app.store.connector(conn.id) ?: conn).copy(lastError = e.message)) }
            busy = null
        }
    }

    policyFor?.let { pc ->
        val live = st.connectors.firstOrNull { it.id == pc.id } ?: pc
        AlertDialog(onDismissRequest = { policyFor = null }, title = { Text("Permissões · ${live.name}") },
            text = {
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Como no Executor: escolha, por ferramenta, se o agente usa livremente, pede sua aprovação ou não pode usar.", fontSize = 12.sp, color = c.ink2)
                    Connectors.toolNames(live).forEach { (tool, desc) ->
                        val cur = live.toolPolicy[tool]
                        Column {
                            Text(tool, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            if (desc.isNotBlank()) Text(desc, fontSize = 11.sp, color = c.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                listOf(null to "Padrão", Connectors.POLICY_ALLOW to "Liberada", Connectors.POLICY_ASK to "Aprovação", Connectors.POLICY_BLOCK to "Bloqueada").forEach { (v, label) ->
                                    FilterChip(cur == v, {
                                        val next = if (v == null) live.toolPolicy - tool else live.toolPolicy + (tool to v)
                                        app.store.upsertConnector(live.copy(toolPolicy = next))
                                    }, label = { Text(label, fontSize = 11.sp) })
                                }
                            }
                        }
                    }
                    if (Connectors.toolNames(live).isEmpty()) Text("Conecte e atualize as ferramentas para configurar.", fontSize = 12.sp, color = c.ink3)
                }
            },
            confirmButton = { TextButton({ policyFor = null }) { Text("Pronto") } })
    }
    Scaffold(containerColor = c.bg, topBar = { CueTopBar("Conectores", { nav.popBackStack() }) {
        IconButton({ custom = true }) { Icon(Icons.Rounded.Add, "Conector personalizado") }
    } }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                UiConnectorsSection(nav)
            }
            item {
                SectionLabel("Apps (Activepieces)")
                Text("Gmail, Drive, Planilhas, Slack, Notion, GitHub e mais de 600 apps, com login do próprio serviço. Roda no próprio aparelho, leve: cada conector é baixado só quando usado.",
                    fontSize = 13.sp, color = c.ink2)
            }
            item {
                val hubState by ActivepiecesHub.state.collectAsStateWithLifecycle()
                val hubLog by ActivepiecesHub.log.collectAsStateWithLifecycle()
                var hubLogOpen by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { ActivepiecesHub.refresh(); dev.cueopen.app.connectors.HubConnectors.syncConnector(app.store) }
                val hubConn = st.connectors.firstOrNull { it.id == ActivepiecesHub.CONNECTOR_ID }
                val busyState = hubState == ActivepiecesHub.State.INSTALLING || hubState == ActivepiecesHub.State.STARTING
                CueCard {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(c.accentSoft), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Hub, null, tint = c.accent)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Conectores · Activepieces", fontWeight = FontWeight.SemiBold)
                                Text(when (hubState) {
                                    ActivepiecesHub.State.NOT_INSTALLED -> if (ActivepiecesHub.sandboxReady()) "Não instalado · ~50 MB (uma vez)" else "Instale o Linux (Alpine) em Configurações › Sandbox"
                                    ActivepiecesHub.State.INSTALLING -> "Instalando/atualizando…"
                                    ActivepiecesHub.State.STOPPED -> "Pronto · liga sozinho quando o agente usa"
                                    ActivepiecesHub.State.STARTING -> "Iniciando…"
                                    ActivepiecesHub.State.RUNNING -> "Ativo"
                                    ActivepiecesHub.State.ERROR -> "Erro · veja o registro"
                                }, fontSize = 12.sp, color = if (hubState == ActivepiecesHub.State.ERROR) c.err else c.ink2)
                            }
                            if (hubConn != null && ActivepiecesHub.installed()) Switch(hubConn.enabled, { v -> app.store.upsertConnector(hubConn.copy(enabled = v)) },
                                colors = SwitchDefaults.colors(checkedTrackColor = c.ink, checkedThumbColor = c.bg))
                        }
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            @Composable fun pill(label: String, primary: Boolean = true, onClick: () -> Unit) =
                                Box(Modifier.clip(CircleShape).background(if (primary) c.ink else c.surface2).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp)) {
                                    Text(label, color = if (primary) c.bg else c.ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                }
                            when {
                                busyState -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = c.accent)
                                !ActivepiecesHub.installed() -> if (ActivepiecesHub.sandboxReady()) pill("Instalar") { ActivepiecesHub.installInBackground() }
                                else -> {
                                    pill("Atualizar conectores", primary = false) { ActivepiecesHub.updateInBackground() }
                                    if (hubState == ActivepiecesHub.State.RUNNING) pill("Parar", primary = false) { scope.launch { ActivepiecesHub.stop() } }
                                    if (hubState == ActivepiecesHub.State.ERROR) pill("Tentar de novo") { scope.launch { ActivepiecesHub.start() } }
                                }
                            }
                        }
                        val lastLine = hubLog.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }
                        if (lastLine != null && (busyState || hubState == ActivepiecesHub.State.ERROR))
                            Text(lastLine, fontSize = 12.sp, color = if (hubState == ActivepiecesHub.State.ERROR) c.err else c.accent, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (hubLogOpen) "Ocultar registro" else "Ver registro", fontSize = 12.sp, color = c.accent, modifier = Modifier.clickable { hubLogOpen = !hubLogOpen })
                            Text("Copiar", fontSize = 12.sp, color = c.accent, modifier = Modifier.clickable {
                                scope.launch {
                                    val text = withContext(Dispatchers.IO) { ActivepiecesHub.fullLog() }
                                    (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                                        .setPrimaryClip(android.content.ClipData.newPlainText("Registro dos conectores", text))
                                    android.widget.Toast.makeText(ctx, "Registro copiado", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            })
                            Text("Enviar", fontSize = 12.sp, color = c.accent, modifier = Modifier.clickable {
                                scope.launch {
                                    val text = withContext(Dispatchers.IO) { ActivepiecesHub.fullLog() }
                                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                        type = "text/plain"; putExtra(android.content.Intent.EXTRA_SUBJECT, "Fluwy Bot — registro dos conectores"); putExtra(android.content.Intent.EXTRA_TEXT, text.takeLast(90_000))
                                    }
                                    ctx.startActivity(android.content.Intent.createChooser(send, "Enviar registro"))
                                }
                            })
                        }
                        if (hubLogOpen) {
                            val logScroll = rememberScrollState()
                            LaunchedEffect(hubLog.length) { logScroll.animateScrollTo(logScroll.maxValue) }
                            Box(Modifier.fillMaxWidth().heightIn(max = 360.dp).clip(RoundedCornerShape(12.dp)).background(androidx.compose.ui.graphics.Color(0xFF0F1115))
                                .verticalScroll(logScroll).padding(10.dp)) {
                                Text(hubLog.ifBlank { "Sem registro ainda." }, fontSize = 10.sp, color = androidx.compose.ui.graphics.Color(0xFFD7DCE3),
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, lineHeight = 13.sp)
                            }
                        }
                    }
                }
            }
            if (ActivepiecesHub.sandboxReady()) item {
                HubConnectionsSection(nav, ActivepiecesHub.installed())
            }
            // O hub aparece como os conectores dele (seção acima), não como um conector único.
            val shown = st.connectors.filter { it.gatewayRef != ActivepiecesHub.REF }
            if (shown.isNotEmpty()) item { SectionLabel("Seus conectores") }
            items(shown, key = { it.id }) { conn ->
                val connected = conn.connectedAt != null
                val toolsCount = runCatching { org.json.JSONArray(conn.toolsJson ?: "[]").length() }.getOrDefault(0)
                CueCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(if (connected) c.accentSoft else c.surface2), contentAlignment = Alignment.Center) {
                            Text(conn.name.take(1), fontWeight = FontWeight.Bold, fontSize = 18.sp, color = if (connected) c.accent else c.ink2)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(conn.name, fontWeight = FontWeight.SemiBold)
                            }
                            Text(conn.description.ifBlank { conn.url }, fontSize = 12.sp, color = c.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            when {
                                busy == conn.id -> Text(if (conn.connectedAt == null && !conn.needsAuth) "Instalando e verificando ferramentas…" else "Conectando… conclua o login no navegador", fontSize = 11.sp, color = c.accent)
                                conn.lastError != null -> Text(conn.lastError, fontSize = 11.sp, color = c.err, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                connected -> Text("Conectado · $toolsCount ferramentas", fontSize = 11.sp, color = c.ok)
                            }
                            if (!connected && conn.lastError != null && true) Text("Tenho um token →", fontSize = 11.sp, color = c.accent,
                                modifier = Modifier.clickable { tokenFor = conn })
                        }
                        Spacer(Modifier.width(8.dp))
                        var menu by remember(conn.id) { mutableStateOf(false) }
                        if (busy == conn.id) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = c.accent)
                        else {
                            if (connected) {
                                Switch(conn.enabled, { v -> app.store.upsertConnector(conn.copy(enabled = v)) },
                                    colors = SwitchDefaults.colors(checkedTrackColor = c.ink, checkedThumbColor = c.bg))
                            } else {
                                Box(Modifier.clip(CircleShape).background(c.ink).clickable { connect(conn.copy(enabled = true)) }.padding(horizontal = 14.dp, vertical = 8.dp)) {
                                    Text(if (conn.lastError != null) "Tentar novamente" else if (conn.connectedAt == null) "Conectar" else "Conectar", color = c.bg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Opções") }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    if (connected) {
                                        DropdownMenuItem(text = { Text("Permissões das ferramentas") }, onClick = { menu = false; policyFor = conn })
                                        DropdownMenuItem(text = { Text("Atualizar ferramentas") }, onClick = { menu = false; scope.launch { Connectors.refreshTools(app.store, conn) } })
                                        DropdownMenuItem(text = { Text("Reconectar") }, onClick = { menu = false; connect(conn.copy(accessToken = null)) })
                                    }
                                    DropdownMenuItem(text = { Text(if (connected) "Desconectar e remover" else "Desinstalar conector", color = c.err) },
                                        leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = c.err) },
                                        onClick = { menu = false; remove(conn) })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    tokenFor?.let { conn ->
        var tk by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { tokenFor = null }, title = { Text("Conectar ${conn.name} com token") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Este servidor não oferece login OAuth público. Cole um token de acesso (ex.: token pessoal do serviço) para conectar.", fontSize = 13.sp, color = c.ink2)
                CueField(tk, { tk = it }, "Token", password = true)
            }
        }, confirmButton = { TextButton({
            val co = conn; tokenFor = null; busy = co.id
            scope.launch { runCatching { Connectors.connectWithToken(app.store, co, tk) }; busy = null }
        }, enabled = tk.isNotBlank()) { Text("Conectar") } }, dismissButton = { TextButton({ tokenFor = null }) { Text("Cancelar") } })
    }
    if (custom) {
        var name by remember { mutableStateOf("") }
        var url by remember { mutableStateOf("https://") }
        var auth by remember { mutableStateOf(true) }
        AlertDialog(onDismissRequest = { custom = false }, title = { Text("Conector MCP personalizado") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CueField(name, { name = it }, "Nome")
                CueField(url, { url = it }, "URL do servidor MCP (streamable HTTP)")
                ToggleRow("Requer login OAuth", "Desligue para servidores públicos", auth) { auth = it }
            }
        }, confirmButton = { TextButton({
            custom = false
            val id = name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { "custom" }
            connect(Connector(id, name.ifBlank { id }, url.trim(), "Personalizado", auth))
        }) { Text("Conectar") } }, dismissButton = { TextButton({ custom = false }) { Text("Cancelar") } })
    }
}


/** Conectores conectados no hub, listados como conectores do app; "Adicionar" abre o catálogo nativo. */
@Composable
private fun HubConnectionsSection(nav: NavController, running: Boolean) {
    val app = CueApp.instance
    val c = Cue.c
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var conns by remember { mutableStateOf(dev.cueopen.app.connectors.HubConnectors.lastKnown()) }
    var err by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) reload++ }
        lifecycle.lifecycle.addObserver(obs); onDispose { lifecycle.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(running, reload) {
        runCatching { dev.cueopen.app.connectors.HubConnectors.connections(app.store, fresh = reload > 0) }.onSuccess { conns = it; err = null }.onFailure { err = it.message }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Seus apps conectados")
            Spacer(Modifier.weight(1f))
            Box(Modifier.clip(CircleShape).background(c.ink).clickable { nav.navigate("hubcatalog") }.padding(horizontal = 14.dp, vertical = 7.dp)) {
                Text("+ Adicionar conector", color = c.bg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        when {
            !running -> Text("Instale os conectores (acima) para conectar seus apps.", fontSize = 12.sp, color = c.ink3)
            err != null -> Text(err!!, fontSize = 12.sp, color = c.err)
            conns == null -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = c.accent)
            conns!!.isEmpty() -> Text("Nenhum app ainda. Toque em Adicionar conector para conectar Gmail, Notion, GitHub e mais de 600 apps.", fontSize = 12.sp, color = c.ink3)
        }
        conns.orEmpty().forEach { cn ->
            var menu by remember(cn.externalId) { mutableStateOf(false) }
            CueCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    coil.compose.AsyncImage(dev.cueopen.app.connectors.HubApi.catalogPiece(cn.pieceName)?.logoUrl?.ifBlank { null } ?: ("https://cdn.activepieces.com/pieces/" + cn.pieceName.substringAfterLast("piece-") + ".png"), null,
                        Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(c.surface2))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(dev.cueopen.app.connectors.HubConnectors.appName(cn.pieceName), fontWeight = FontWeight.SemiBold)
                        Text(cn.displayName, fontSize = 12.sp, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(when (cn.status) { "ACTIVE" -> "Conectado · o agente usa direto"; dev.cueopen.app.connectors.HubApi.PENDING -> "Do backup · conecte de novo em Adicionar conector"; else -> "Acesso perdido · conecte de novo" }, fontSize = 11.sp, color = if (cn.status == "ACTIVE") c.ok else c.err)
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Opções") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Remover conexão", color = c.err) }, leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = c.err) }, onClick = {
                                menu = false
                                scope.launch {
                                    runCatching {
                                        dev.cueopen.app.connectors.HubApi.deleteConnection(cn.externalId)
                                    }.onSuccess { reload++; android.widget.Toast.makeText(ctx, "Conexão removida.", android.widget.Toast.LENGTH_SHORT).show() }
                                        .onFailure { android.widget.Toast.makeText(ctx, it.message ?: "Falha ao remover", android.widget.Toast.LENGTH_LONG).show() }
                                }
                            })
                        }
                    }
                }
            }
        }
    }
}
