package dev.cueopen.app.ui

import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SettingsEthernet
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import org.json.JSONArray

/** Configuração explícita de um servidor MCP; abrir a tela não inicia rede nem OAuth. */
@Composable
fun McpConnectorSettingsScreen(nav: NavController, id: String) {
    val app = CueApp.instance
    val state by app.store.state.collectAsStateWithLifecycle()
    val connector = state.connectors.firstOrNull { it.id == id }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val c = Cue.c
    var busy by remember(id) { mutableStateOf(false) }
    var notice by remember(id) { mutableStateOf<String?>(null) }

    if (connector == null) {
        LaunchedEffect(id) { nav.popBackStack() }
        return
    }

    fun connect(candidate: Connector) {
        busy = true
        notice = null
        val enabledCandidate = candidate.copy(enabled = true, lastError = null)
        app.store.upsertConnector(enabledCandidate)
        scope.launch {
            runCatching {
                Connectors.connect(context, app.store, enabledCandidate) { url ->
                    runCatching { CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, Uri.parse(url)) }
                        .onFailure { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url))) }
                }
            }.onSuccess { result ->
                app.store.upsertConnector(result)
                notice = result.lastError?.let { "Não foi possível conectar: $it" } ?: "Conector conectado."
            }.onFailure { error ->
                val current = app.store.connector(id) ?: enabledCandidate
                app.store.upsertConnector(current.copy(lastError = error.message ?: "Falha na conexão"))
                notice = error.message ?: "Falha na conexão."
            }
            busy = false
        }
    }

    val tools = remember(connector.toolsJson) {
        runCatching {
            val array = JSONArray(connector.toolsJson ?: "[]")
            (0 until array.length()).mapNotNull { index ->
                val tool = array.optJSONObject(index) ?: return@mapNotNull null
                tool.optString("name").takeIf(String::isNotBlank)?.let { it to tool.optString("description") }
            }
        }.getOrDefault(emptyList())
    }
    val connected = connector.connectedAt != null

    Scaffold(containerColor = c.bg, topBar = {
        CueTopBar(connector.name, { nav.popBackStack() }) {
            if (connected) IconButton(onClick = {
                if (!busy) {
                    busy = true
                    scope.launch {
                        val result = Connectors.refreshTools(app.store, connector)
                        notice = result.lastError?.let { "Falha ao atualizar: $it" }
                            ?: "Ferramentas atualizadas (${runCatching { JSONArray(result.toolsJson ?: "[]").length() }.getOrDefault(0)} disponíveis)."
                        busy = false
                    }
                }
            }, enabled = !busy) { Icon(Icons.Rounded.Refresh, "Atualizar ferramentas") }
        }
    }) { pad ->
        LazyColumn(
            Modifier.padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(connector.name, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Text(if (connected) "Conectado" else "Desconectado", color = if (connected) c.ok else c.ink2, fontSize = 13.sp)
                        }
                        Icon(Icons.Rounded.SettingsEthernet, null, tint = if (connected) c.ok else c.ink3)
                    }
                    Text(connector.description.ifBlank { connector.url }, fontSize = 13.sp, color = c.ink2)
                    Text(connector.url, fontSize = 12.sp, color = c.ink3, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Disponível para os agentes", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text("Desligar remove as ferramentas MCP das próximas chamadas.", fontSize = 12.sp, color = c.ink2)
                        }
                        Switch(checked = connector.enabled, onCheckedChange = { value -> app.store.upsertConnector(connector.copy(enabled = value)) })
                    }
                    Button(
                        onClick = { connect(connector.copy(accessToken = if (connector.needsAuth) null else connector.accessToken)) },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Icon(if (connected) Icons.Rounded.Refresh else Icons.Rounded.CheckCircle, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (connected) "Reconectar" else "Conectar")
                    }
                    notice?.let { Text(it, fontSize = 12.sp, color = if (connector.lastError == null) c.ink2 else c.err) }
                    connector.lastError?.let { Text(it, fontSize = 12.sp, color = c.err) }
                }
            }
            item {
                SectionLabel("Ferramentas MCP · ${tools.size}")
                if (tools.isEmpty()) {
                    Text("Nenhuma ferramenta carregada. Conecte ou atualize o servidor para consultar a lista.", fontSize = 13.sp, color = c.ink2)
                }
            }
            items(tools) { (name, description) ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface).padding(12.dp)) {
                    Text(name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    if (description.isNotBlank()) Text(description, fontSize = 12.sp, color = c.ink2, modifier = Modifier.padding(top = 3.dp))
                }
            }
        }
    }
}
