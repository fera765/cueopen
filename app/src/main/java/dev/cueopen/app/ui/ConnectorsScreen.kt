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
import dev.cueopen.app.connectors.LocalMcpGateway
import dev.cueopen.app.data.Connector
import dev.cueopen.app.ui.theme.Cue
import kotlinx.coroutines.launch

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
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Todos") }
    var shownCount by remember { mutableStateOf(40) }
    var policyFor by remember { mutableStateOf<Connector?>(null) }
    var paramsFor by remember { mutableStateOf<LocalMcpGateway.Server?>(null) }
    var gwErr by remember { mutableStateOf<String?>(null) }
    var showLog by remember { mutableStateOf(false) }
    val gwBusy by LocalMcpGateway.busy.collectAsStateWithLifecycle()
    val gwLog by LocalMcpGateway.log.collectAsStateWithLifecycle()
    var engineReady by remember { mutableStateOf(LocalMcpGateway.engineInstalled()) }
    LaunchedEffect(gwBusy) { if (!gwBusy) engineReady = LocalMcpGateway.engineInstalled() }
    val localCatalog = remember { LocalMcpGateway.catalog(ctx) }
    fun cidOf(s: LocalMcpGateway.Server) = "mcp_" + LocalMcpGateway.safeName(s.id).replace('-', '_')
    fun installLocal(s: LocalMcpGateway.Server, values: Map<String, String>) {
        val id = cidOf(s); val name = LocalMcpGateway.safeName(s.id)
        busy = id; gwErr = null
        scope.launch {
            runCatching {
                val url = LocalMcpGateway.install(s, values)
                val conn = Connector(id, s.name, url, s.desc.take(300), needsAuth = false, enabled = true, gatewayRef = LocalMcpGateway.ref(name))
                app.store.upsertConnector(conn)
                Connectors.connectWithToken(app.store, conn, LocalMcpGateway.token())
            }.onFailure { e ->
                val cur = app.store.connector(id)
                if (cur != null) app.store.upsertConnector(cur.copy(lastError = e.message)) else gwErr = e.message
            }
            busy = null
        }
    }
    fun reconnectLocal(conn: Connector) {
        busy = conn.id
        scope.launch {
            runCatching {
                if (!LocalMcpGateway.ensureRunning(restart = true)) error("O gateway local não iniciou. ${LocalMcpGateway.lastLog().takeLast(300)}")
                Connectors.connectWithToken(app.store, conn.copy(lastError = null), LocalMcpGateway.token())
            }.onFailure { e -> app.store.upsertConnector(conn.copy(lastError = e.message)) }
            busy = null
        }
    }
    fun remove(conn: Connector) {
        app.store.deleteConnector(conn.id)
        LocalMcpGateway.nameOf(conn.gatewayRef)?.let { n -> scope.launch { runCatching { LocalMcpGateway.uninstall(n) } } }
    }

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
                SectionLabel("Conectores MCP · Gateway local")
                Text("Os servidores MCP rodam dentro do Linux (Alpine) do próprio app, num gateway local e gratuito. Nada passa por servidores de terceiros além do próprio serviço que você conectar.",
                    fontSize = 13.sp, color = c.ink2)
            }
            item {
                CueCard {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val sandboxOk = LocalMcpGateway.sandboxReady()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (engineReady) Icons.Rounded.CheckCircle else Icons.Rounded.Info, null, tint = if (engineReady) c.ok else c.ink2, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(when {
                                !sandboxOk -> "Instale o Linux (Alpine) em Configurações › Sandbox"
                                engineReady -> "Gateway local instalado · ${localCatalog.size} servidores no catálogo"
                                gwBusy -> "Instalando o gateway local…"
                                else -> "Gateway local ainda não instalado"
                            }, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            if (gwBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = c.accent)
                        }
                        if (sandboxOk && !engineReady && !gwBusy) {
                            Box(Modifier.clip(CircleShape).background(c.ink).clickable { scope.launch { LocalMcpGateway.installEngine(); engineReady = LocalMcpGateway.engineInstalled() } }
                                .padding(horizontal = 14.dp, vertical = 8.dp)) {
                                Text("Instalar gateway local", color = c.bg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        if (gwLog.isNotBlank()) Text(if (showLog) "Ocultar registro" else "Ver registro", fontSize = 12.sp, color = c.accent, modifier = Modifier.clickable { showLog = !showLog })
                        if (showLog) Text(gwLog.takeLast(2500), fontSize = 11.sp, color = c.ink2, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                        gwErr?.let { Text(it, fontSize = 12.sp, color = c.err) }
                    }
                }
            }
            val shown = st.connectors
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
                            if (!connected && conn.lastError != null && conn.gatewayRef == null) Text("Tenho um token →", fontSize = 11.sp, color = c.accent,
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
                                Box(Modifier.clip(CircleShape).background(c.ink).clickable { if (conn.gatewayRef != null) reconnectLocal(conn.copy(enabled = true)) else connect(conn.copy(enabled = true)) }.padding(horizontal = 14.dp, vertical = 8.dp)) {
                                    Text(if (conn.lastError != null) "Tentar novamente" else if (conn.connectedAt == null) "Conectar" else "Conectar", color = c.bg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Opções") }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    if (connected) {
                                        DropdownMenuItem(text = { Text("Permissões das ferramentas") }, onClick = { menu = false; policyFor = conn })
                                        DropdownMenuItem(text = { Text("Atualizar ferramentas") }, onClick = { menu = false; scope.launch { Connectors.refreshTools(app.store, conn) } })
                                        DropdownMenuItem(text = { Text("Reconectar") }, onClick = { menu = false; if (conn.gatewayRef != null) reconnectLocal(conn) else connect(conn.copy(accessToken = null)) })
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
            item {
                SectionLabel("Catálogo")
                OutlinedTextField(
                    value = query, onValueChange = { query = it; shownCount = 40 },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true, shape = RoundedCornerShape(20.dp),
                    placeholder = { Text("Buscar conectores (ex.: github, postgres, notion)", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Icon(Icons.Rounded.Search, null, tint = c.ink2) },
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.accent, unfocusedBorderColor = c.line, focusedContainerColor = c.surface, unfocusedContainerColor = c.surface)
                )
                Spacer(Modifier.height(6.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (listOf("Todos", "Sem chave", "Oficiais") + localCatalog.map { it.category }.distinct().sorted()).forEach { cat ->
                        FilterChip(category == cat, { category = cat; shownCount = 40 }, label = { Text(cat, fontSize = 12.sp) })
                    }
                }
            }
            val q = query.trim()
            val filtered = localCatalog.filter { s ->
                (q.isBlank() || s.name.contains(q, true) || s.desc.contains(q, true) || s.id.contains(q, true) || s.category.contains(q, true)) &&
                    when (category) { "Todos" -> true; "Sem chave" -> s.params.isEmpty(); "Oficiais" -> s.official; else -> s.category == category }
            }
            item { Text("${filtered.size} servidores", fontSize = 12.sp, color = c.ink3) }
            items(filtered.take(shownCount), key = { "cat_" + it.id }) { srv ->
                val cid = cidOf(srv)
                val mine = st.connectors.firstOrNull { it.id == cid }
                CueCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(c.surface2), contentAlignment = Alignment.Center) {
                            Text(srv.name.take(1).uppercase(), fontWeight = FontWeight.Bold, fontSize = 18.sp, color = c.ink2)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(srv.name + if (srv.official) " ✓" else "", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(srv.desc.ifBlank { srv.id }, fontSize = 12.sp, color = c.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(srv.category + " · " + (if (srv.params.isEmpty()) "sem chave" else "pede ${srv.params.size} chave(s)") + " · " + srv.cmd,
                                fontSize = 11.sp, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.width(8.dp))
                        when {
                            busy == cid -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = c.accent)
                            mine?.connectedAt != null -> Icon(Icons.Rounded.CheckCircle, null, tint = c.ok)
                            else -> Box(Modifier.clip(CircleShape).background(if (LocalMcpGateway.sandboxReady() && busy == null) c.ink else c.surface2)
                                .clickable(enabled = LocalMcpGateway.sandboxReady() && busy == null) {
                                    if (srv.params.isEmpty()) installLocal(srv, emptyMap()) else paramsFor = srv
                                }.padding(horizontal = 14.dp, vertical = 8.dp)) {
                                Text("Instalar", color = c.bg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
            if (filtered.size > shownCount) item {
                TextButton(onClick = { shownCount += 40 }, modifier = Modifier.fillMaxWidth()) { Text("Mostrar mais", color = c.accent) }
            }
        }
    }
    paramsFor?.let { srv ->
        val values = remember(srv.id) { mutableStateMapOf<String, String>() }
        AlertDialog(onDismissRequest = { paramsFor = null }, title = { Text("Configurar ${srv.name}") }, text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("As chaves ficam só no Linux do app e vão apenas para o servidor ${srv.name}.", fontSize = 12.sp, color = c.ink2)
                srv.params.forEach { p ->
                    CueField(values[p.key].orEmpty(), { values[p.key] = it }, p.key + if (p.required) " *" else "", password = p.key.contains("KEY", true) || p.key.contains("TOKEN", true) || p.key.contains("SECRET", true) || p.key.contains("PASSWORD", true))
                    if (p.desc.isNotBlank()) Text(p.desc + if (p.example.isNotBlank()) " (ex.: ${p.example})" else "", fontSize = 11.sp, color = c.ink3)
                }
            }
        }, confirmButton = { TextButton({
            val v = values.toMap(); paramsFor = null; installLocal(srv, v)
        }, enabled = srv.params.filter { it.required }.all { !values[it.key].isNullOrBlank() }) { Text("Instalar") } },
            dismissButton = { TextButton({ paramsFor = null }) { Text("Cancelar") } })
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
