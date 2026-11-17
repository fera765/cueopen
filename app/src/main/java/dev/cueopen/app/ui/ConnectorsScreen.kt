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
import dev.cueopen.app.connectors.SmitheryGateway
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
    var searching by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<SmitheryGateway.Server>>(emptyList()) }
    var page by remember { mutableStateOf(1) }
    var totalPages by remember { mutableStateOf(1) }
    var totalCount by remember { mutableStateOf(0) }
    var searchErr by remember { mutableStateOf<String?>(null) }
    var policyFor by remember { mutableStateOf<Connector?>(null) }
    var keyDraft by remember { mutableStateOf("") }
    var editKey by remember { mutableStateOf(false) }
    val gwKey = st.settings.smitheryKey
    fun openUrl(url: String) {
        runCatching { CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(ctx, Uri.parse(url)) }
            .onFailure { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url))) }
    }
    fun load(next: Boolean = false) {
        if (gwKey.isBlank() || searching) return
        searching = true; searchErr = null
        val target = if (next) page + 1 else 1
        scope.launch {
            runCatching { SmitheryGateway.search(gwKey, query, target) }
                .onSuccess { r ->
                    found = if (next) (found + r.servers).distinctBy { it.qualifiedName } else r.servers
                    page = r.page; totalPages = r.totalPages; totalCount = r.totalCount
                    if (found.isEmpty()) searchErr = "Nenhum servidor encontrado no gateway."
                }
                .onFailure { searchErr = it.message ?: it::class.java.simpleName }
            searching = false
        }
    }
    fun doSearch() = load(false)
    LaunchedEffect(gwKey) { if (gwKey.isNotBlank()) load(false) }
    suspend fun ensureNamespace(): String = st.settings.smitheryNamespace.ifBlank {
        SmitheryGateway.namespace(gwKey).also { ns -> app.store.updateSettings { it.copy(smitheryNamespace = ns) } }
    }
    /** Acompanha a conexão no gateway até ficar pronta (login OAuth/config concluídos no navegador) e carrega as ferramentas. */
    suspend fun follow(conn: Connector, ns: String, id: String, first: SmitheryGateway.Conn) {
        var s = first
        var opened = false
        val deadline = System.currentTimeMillis() + 5 * 60_000
        while (true) {
            when (s.state) {
                "connected" -> { Connectors.connectWithToken(app.store, conn, gwKey); return }
                "auth_required", "input_required" -> {
                    if (!opened) { s.setupUrl?.let { openUrl(it) } ?: run {
                        app.store.upsertConnector(conn.copy(lastError = "O gateway pediu login, mas não enviou o link. Toque em Conectar de novo.")); return }
                        opened = true }
                }
                "error" -> { app.store.upsertConnector(conn.copy(lastError = s.message ?: "Erro no gateway.")); return }
                else -> Unit
            }
            if (System.currentTimeMillis() > deadline) {
                app.store.upsertConnector(conn.copy(lastError = "Login não concluído. Termine no navegador e toque em Conectar para verificar."))
                return
            }
            kotlinx.coroutines.delay(3000)
            s = SmitheryGateway.status(gwKey, ns, id)
        }
    }
    fun gatewayConnect(server: SmitheryGateway.Server) {
        val id = "sm_" + SmitheryGateway.connectionId(server.qualifiedName).replace('-', '_')
        busy = id
        scope.launch {
            runCatching {
                val ns = ensureNamespace()
                val r = SmitheryGateway.connect(gwKey, ns, server)
                val conn = Connector(id, server.displayName, SmitheryGateway.mcpUrl(ns, r.id), server.description.take(300),
                    needsAuth = false, enabled = true, gatewayRef = SmitheryGateway.ref(ns, r.id))
                app.store.upsertConnector(conn)
                follow(conn, ns, r.id, r)
            }.onFailure { e ->
                val cur = app.store.connector(id)
                if (cur != null) app.store.upsertConnector(cur.copy(lastError = e.message)) else searchErr = e.message
            }
            busy = null
        }
    }
    fun gatewayResume(conn: Connector) {
        val (ns, id) = SmitheryGateway.parseRef(conn.gatewayRef) ?: return
        busy = conn.id
        scope.launch {
            runCatching { follow(conn.copy(lastError = null), ns, id, SmitheryGateway.status(gwKey, ns, id)) }
                .onFailure { e -> app.store.upsertConnector(conn.copy(lastError = e.message)) }
            busy = null
        }
    }
    fun remove(conn: Connector) {
        app.store.deleteConnector(conn.id)
        SmitheryGateway.parseRef(conn.gatewayRef)?.let { (ns, id) -> if (gwKey.isNotBlank()) scope.launch { SmitheryGateway.delete(gwKey, ns, id) } }
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
                SectionLabel("Conectores MCP · Gateway Smithery")
                Text("Um gateway MCP gratuito (Smithery Connect) com milhares de servidores. O login de cada serviço é feito no próprio gateway; o app recebe um endpoint MCP único por conexão.",
                    fontSize = 13.sp, color = c.ink2)
            }
            if (gwKey.isBlank() || editKey) {
                item {
                    CueCard {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Ativar o gateway", fontWeight = FontWeight.SemiBold)
                            Text("1. Crie uma conta gratuita no Smithery e gere uma API key.\n2. Cole a chave abaixo. Ela fica só neste aparelho.", fontSize = 12.sp, color = c.ink2)
                            TextButton(onClick = { openUrl(SmitheryGateway.KEYS_URL) }) { Text("Abrir smithery.ai › API keys", color = c.accent) }
                            CueField(keyDraft, { keyDraft = it }, "API key do Smithery", password = true)
                            var checking by remember { mutableStateOf(false) }
                            var keyErr by remember { mutableStateOf<String?>(null) }
                            keyErr?.let { Text(it, fontSize = 12.sp, color = c.err) }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Box(Modifier.clip(CircleShape).background(if (keyDraft.isNotBlank() && !checking) c.ink else c.surface2)
                                    .clickable(enabled = keyDraft.isNotBlank() && !checking) {
                                        checking = true; keyErr = null
                                        val k = keyDraft.trim()
                                        scope.launch {
                                            runCatching { SmitheryGateway.namespace(k) }
                                                .onSuccess { ns -> app.store.updateSettings { it.copy(smitheryKey = k, smitheryNamespace = ns) }; keyDraft = ""; editKey = false }
                                                .onFailure { keyErr = it.message }
                                            checking = false
                                        }
                                    }.padding(horizontal = 14.dp, vertical = 8.dp)) {
                                    Text(if (checking) "Validando…" else "Validar e salvar", color = c.bg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                }
                                if (editKey) TextButton({ editKey = false; keyDraft = "" }) { Text("Cancelar") }
                            }
                        }
                    }
                }
            } else {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CheckCircle, null, tint = c.ok, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                        Text("Gateway ativo" + st.settings.smitheryNamespace.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(), fontSize = 12.sp, color = c.ink2, modifier = Modifier.weight(1f))
                        TextButton({ editKey = true }) { Text("Trocar chave", fontSize = 12.sp) }
                        TextButton({ app.store.updateSettings { it.copy(smitheryKey = "", smitheryNamespace = "") }; found = emptyList() }) { Text("Desativar", fontSize = 12.sp, color = c.err) }
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
                                Box(Modifier.clip(CircleShape).background(c.ink).clickable { if (conn.gatewayRef != null) gatewayResume(conn.copy(enabled = true)) else connect(conn.copy(enabled = true)) }.padding(horizontal = 14.dp, vertical = 8.dp)) {
                                    Text(if (conn.lastError != null) "Tentar novamente" else if (conn.connectedAt == null) "Conectar" else "Conectar", color = c.bg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Opções") }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    if (connected) {
                                        DropdownMenuItem(text = { Text("Permissões das ferramentas") }, onClick = { menu = false; policyFor = conn })
                                        DropdownMenuItem(text = { Text("Atualizar ferramentas") }, onClick = { menu = false; scope.launch { Connectors.refreshTools(app.store, conn) } })
                                        DropdownMenuItem(text = { Text("Reconectar") }, onClick = { menu = false; if (conn.gatewayRef != null) gatewayResume(conn) else connect(conn.copy(accessToken = null)) })
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
            if (gwKey.isNotBlank() && !editKey) {
                item {
                    SectionLabel("Catálogo do gateway" + if (totalCount > 0) " · $totalCount servidores" else "")
                    OutlinedTextField(
                        value = query, onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true, shape = RoundedCornerShape(20.dp),
                        placeholder = { Text("Buscar conectores (ex.: gmail, planilhas, notion)", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = { Icon(Icons.Rounded.Search, null, tint = c.ink2) },
                        trailingIcon = {
                            if (searching) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = c.accent)
                            else IconButton(onClick = { doSearch() }) { Icon(Icons.Rounded.ArrowForward, "Buscar", tint = c.ink) }
                        },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { doSearch() }),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.accent, unfocusedBorderColor = c.line, focusedContainerColor = c.surface, unfocusedContainerColor = c.surface)
                    )
                }
                searchErr?.let { item { Text(it, color = c.ink2, fontSize = 13.sp) } }
                items(found, key = { "gw_" + it.qualifiedName }) { srv ->
                    val cid = "sm_" + SmitheryGateway.connectionId(srv.qualifiedName).replace('-', '_')
                    val mine = st.connectors.firstOrNull { it.id == cid }
                    CueCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(c.surface2), contentAlignment = Alignment.Center) {
                                Text(srv.displayName.take(1).uppercase(), fontWeight = FontWeight.Bold, fontSize = 18.sp, color = c.ink2)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(srv.displayName + if (srv.verified) " ✓" else "", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(srv.description.ifBlank { srv.qualifiedName }, fontSize = 12.sp, color = c.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (srv.useCount > 0) Text("${srv.useCount} usos", fontSize = 11.sp, color = c.ink3)
                            }
                            Spacer(Modifier.width(8.dp))
                            when {
                                busy == cid -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = c.accent)
                                mine?.connectedAt != null -> Icon(Icons.Rounded.CheckCircle, null, tint = c.ok)
                                else -> Box(Modifier.clip(CircleShape).background(c.ink).clickable { gatewayConnect(srv) }.padding(horizontal = 14.dp, vertical = 8.dp)) {
                                    Text("Conectar", color = c.bg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
                if (found.isNotEmpty() && page < totalPages) item {
                    TextButton(onClick = { load(next = true) }, enabled = !searching, modifier = Modifier.fillMaxWidth()) {
                        Text(if (searching) "Carregando…" else "Carregar mais", color = c.accent)
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
