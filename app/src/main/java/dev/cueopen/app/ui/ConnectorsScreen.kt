package dev.cueopen.app.ui

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
    var custom by remember { mutableStateOf(false) }
    var tokenFor by remember { mutableStateOf<Connector?>(null) }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<Connectors.Catalog>>(emptyList()) }
    var searchErr by remember { mutableStateOf<String?>(null) }
    fun doSearch() {
        if (query.isBlank()) { found = emptyList(); return }
        searching = true; searchErr = null
        scope.launch {
            runCatching { Connectors.searchRegistry(query.trim()) }
                .onSuccess { r -> found = r.distinctBy { it.id }; if (r.isEmpty()) searchErr = "Nenhum servidor remoto encontrado." }
                .onFailure { searchErr = "Falha na busca: ${it.message}" }
            searching = false
        }
    }

    fun connect(conn: Connector) {
        busy = conn.id
        app.store.upsertConnector(conn)
        scope.launch {
            runCatching {
                Connectors.connect(ctx, app.store, conn) { url ->
                    runCatching { CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(ctx, Uri.parse(url)) }
                        .onFailure { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url))) }
                }
            }.onFailure { e -> app.store.upsertConnector((app.store.connector(conn.id) ?: conn).copy(lastError = e.message)) }
            busy = null
        }
    }

    Scaffold(containerColor = c.bg, topBar = { CueTopBar("Conectores", { nav.popBackStack() }) {
        IconButton({ custom = true }) { Icon(Icons.Rounded.Add, "Conector personalizado") }
    } }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("Conecte serviços via MCP com login OAuth do próprio serviço. As ferramentas ficam disponíveis para o agente e para os seus Agents autônomos.",
                    fontSize = 13.sp, color = c.ink2)
            }
            item {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(16.dp),
                    placeholder = { Text("Buscar servidores MCP (registro oficial)") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    trailingIcon = { if (searching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = c.accent) else if (query.isNotBlank()) IconButton({ doSearch() }) { Icon(Icons.Rounded.ArrowForward, "Buscar") } },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { doSearch() }),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.ink, unfocusedBorderColor = c.line, focusedContainerColor = c.surface, unfocusedContainerColor = c.surface))
            }
            searchErr?.let { item { Text(it, color = c.ink2, fontSize = 13.sp) } }
            if (found.isNotEmpty()) {
                item { SectionLabel("Resultados da busca") }
                itemsIndexed(found) { idx, cat ->
                    val already = st.connectors.any { it.id == cat.id }
                    CueCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(cat.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(cat.description.ifBlank { cat.url }, fontSize = 12.sp, color = c.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            Spacer(Modifier.width(8.dp))
                            if (already) Icon(Icons.Rounded.CheckCircle, null, tint = c.ok)
                            else Box(Modifier.clip(CircleShape).background(c.ink).clickable { connect(Connector(cat.id, cat.name, cat.url, cat.description, cat.needsAuth, enabled = true)) }.padding(horizontal = 14.dp, vertical = 8.dp)) {
                                Text(if (cat.needsAuth) "Conectar" else "Instalar", color = c.bg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
                item { SectionLabel("Destaques") }
            }
            val installed = st.connectors
            val all = Connectors.catalog.map { cat -> installed.firstOrNull { it.id == cat.id } ?: Connector(cat.id, cat.name, cat.url, cat.description, cat.needsAuth, enabled = false) } +
                installed.filter { i -> Connectors.catalog.none { it.id == i.id } }
            items(all, key = { it.id }) { conn ->
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
                                if (!conn.needsAuth) { Spacer(Modifier.width(6.dp)); Text("sem login", fontSize = 11.sp, color = c.ink3) }
                            }
                            Text(conn.description.ifBlank { conn.url }, fontSize = 12.sp, color = c.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            when {
                                busy == conn.id -> Text("Conectando… conclua o login no navegador", fontSize = 11.sp, color = c.accent)
                                conn.lastError != null -> Text(conn.lastError, fontSize = 11.sp, color = c.err, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                connected -> Text("Conectado · $toolsCount ferramentas", fontSize = 11.sp, color = c.ok)
                            }
                            if (!connected && conn.lastError != null) Text("Tenho um token →", fontSize = 11.sp, color = c.accent,
                                modifier = Modifier.clickable { tokenFor = conn })
                        }
                        Spacer(Modifier.width(8.dp))
                        if (busy == conn.id) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = c.accent)
                        else if (connected) {
                            var menu by remember { mutableStateOf(false) }
                            Box {
                                Switch(conn.enabled, { v -> app.store.upsertConnector(conn.copy(enabled = v)) },
                                    colors = SwitchDefaults.colors(checkedTrackColor = c.ink, checkedThumbColor = c.bg))
                                DropdownMenu(menu, { menu = false }) {
                                    DropdownMenuItem({ Text("Atualizar ferramentas") }, { menu = false; scope.launch { Connectors.refreshTools(app.store, conn) } })
                                    DropdownMenuItem({ Text("Reconectar") }, { menu = false; connect(conn.copy(accessToken = null)) })
                                    DropdownMenuItem({ Text("Desconectar", color = c.err) }, { menu = false; app.store.deleteConnector(conn.id) })
                                }
                            }
                            IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, "Opções") }
                        } else {
                            Box(Modifier.clip(CircleShape).background(c.ink).clickable { connect(conn.copy(enabled = true)) }.padding(horizontal = 14.dp, vertical = 8.dp)) {
                                Text("Conectar", color = c.bg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
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
