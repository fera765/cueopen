package dev.fluwy.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.fluwy.app.FluwyApp
import dev.fluwy.app.data.ThemeMode
import dev.fluwy.app.sandbox.Sandbox
import dev.fluwy.app.ui.theme.Fluwy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@Composable
fun ProxyScreen(nav: NavController) {
    val app = FluwyApp.instance
    val c = Fluwy.c
    val scope = rememberCoroutineScope()
    val state by app.store.state.collectAsStateWithLifecycle()
    val browserProxyStatus by app.browserProxyStatus.collectAsStateWithLifecycle()
    var profiles by remember { mutableStateOf(app.proxyStore.profiles()) }
    var rotation by remember { mutableStateOf(app.proxyStore.rotationSettings()) }
    var intervalText by remember(rotation.intervalMinutes) { mutableStateOf(rotation.intervalMinutes.toString()) }
    var editing by remember { mutableStateOf<dev.fluwy.app.proxy.ProxyProfile?>(null) }
    var importResult by remember { mutableStateOf<String?>(null) }
    val importCtx = androidx.compose.ui.platform.LocalContext.current
    val importPicker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            importCtx.contentResolver.openInputStream(uri)?.use { ins ->
                val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(16_384)
                while (out.size() < 2_000_000) { val n = ins.read(buf); if (n < 0) break; out.write(buf, 0, n) }
                out.toString("UTF-8")
            }
        }.getOrNull()
        if (text == null) { importResult = "Não consegui ler o arquivo."; return@rememberLauncherForActivityResult }
        val r = dev.fluwy.app.proxy.ProxyImport.parse(text, profiles)
        if (r.profiles.isNotEmpty()) {
            profiles = profiles + r.profiles
            app.proxyStore.saveProfiles(profiles)
            // Com a rotação ligada, os importados já entram nos pools do Browser e do Terminal.
            if (rotation.enabled) {
                val ids = r.profiles.map { it.id }
                val next = rotation.copy(browserProfileIds = rotation.browserProfileIds + ids, terminalProfileIds = rotation.terminalProfileIds + ids)
                rotation = next; app.configureProxyRotation(next)
            }
            app.connectorDebugLog.recordEvent("proxy", "profile", "imported count=${r.profiles.size}")
            app.applyBrowserProxy()
        }
        importResult = "${r.profiles.size} proxy(s) importado(s)" +
            listOfNotNull("${r.duplicates} repetido(s)".takeIf { r.duplicates > 0 }, "${r.withCredentials} com login/senha ignorado(s)".takeIf { r.withCredentials > 0 },
                "${r.invalid} linha(s) inválida(s)".takeIf { r.invalid > 0 }).joinToString(", ").let { if (it.isBlank()) "." else " — $it." } +
            (if (r.profiles.isNotEmpty() && rotation.enabled) " Já estão nos pools de rotação." else if (r.profiles.isNotEmpty()) " Selecione um deles por escopo ou ligue a rotação." else "")
    }
    fun saveRotation(next: dev.fluwy.app.proxy.ProxyRotationSettings) {
        rotation = next
        intervalText = next.intervalMinutes.toString()
        app.configureProxyRotation(next)
    }
    fun selectProxy(scope: dev.fluwy.app.proxy.ProxyScope, id: String?) {
        app.store.updateSettings { s -> when (scope) {
            dev.fluwy.app.proxy.ProxyScope.BROWSER -> s.copy(browserProxyId = id)
            dev.fluwy.app.proxy.ProxyScope.TERMINAL -> s.copy(terminalProxyId = id)
            dev.fluwy.app.proxy.ProxyScope.AGENT_API -> s.copy(agentApiProxyId = id)
        } }
        app.connectorDebugLog.recordEvent("proxy", "selection", "scope=${scope.name} profile_id=${id ?: "direct"}")
        if (scope == dev.fluwy.app.proxy.ProxyScope.BROWSER) app.applyBrowserProxy()
    }
    fun save(p: dev.fluwy.app.proxy.ProxyProfile) {
        profiles = (profiles.filterNot { it.id == p.id } + p)
        app.proxyStore.saveProfiles(profiles)
        app.connectorDebugLog.recordEvent("proxy", "profile", "saved profile_id=${p.id}")
        editing = null
        app.applyBrowserProxy()
    }
    Scaffold(containerColor = c.bg, topBar = { FluwyTopBar("Proxy", { nav.popBackStack() }) }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Selecione um perfil por escopo. O padrão é direto, sem proxy.", color = c.ink2, fontSize = 13.sp)
            Text("Browser: o ProxyController afeta todos os WebViews deste processo, não um BrowserHost individual. WebView não recebe credenciais.", color = c.ink2, fontSize = 12.sp)
            if (!androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.PROXY_OVERRIDE))
                Text("Este WebView não oferece ProxyController; a seleção Browser não pode ser aplicada nesta versão do sistema.", color = c.err, fontSize = 12.sp)
            Text("Status Browser: $browserProxyStatus", color = if (browserProxyStatus.startsWith("Falha") || browserProxyStatus.startsWith("Indisponível") || browserProxyStatus.startsWith("Navegação bloqueada")) c.err else c.ink2, fontSize = 12.sp)
            Text("Terminal: aplica variáveis de proxy ao shell e ao bootstrap do Sandbox; HTTP/SOCKS, sem credenciais. O suporte depende do programa de linha de comando.", color = c.ink2, fontSize = 12.sp)
            Text("Agent/API: modelo e próximo login/reconnect IQ Option. HTTP CONNECT autenticado usa Basic para o proxy em conexão não cifrada; use apenas proxy/rede confiáveis. SOCKS autenticado não é suportado. WebSocket ativo exige reconexão.", color = c.ink2, fontSize = 12.sp)
            ProxyScopeRow("Browser", state.settings.browserProxyId, profiles, dev.fluwy.app.proxy.ProxyScope.BROWSER) { id -> selectProxy(dev.fluwy.app.proxy.ProxyScope.BROWSER, id) }
            ProxyScopeRow("Terminal", state.settings.terminalProxyId, profiles, dev.fluwy.app.proxy.ProxyScope.TERMINAL) { id -> selectProxy(dev.fluwy.app.proxy.ProxyScope.TERMINAL, id) }
            ProxyScopeRow("Agent/API", state.settings.agentApiProxyId, profiles, dev.fluwy.app.proxy.ProxyScope.AGENT_API) { id -> selectProxy(dev.fluwy.app.proxy.ProxyScope.AGENT_API, id) }
            listOf("Browser" to state.settings.browserProxyId to dev.fluwy.app.proxy.ProxyScope.BROWSER, "Terminal" to state.settings.terminalProxyId to dev.fluwy.app.proxy.ProxyScope.TERMINAL, "Agent/API" to state.settings.agentApiProxyId to dev.fluwy.app.proxy.ProxyScope.AGENT_API).forEach { entry ->
                val selectedId = entry.first.second
                val selected = selectedId?.let { id -> profiles.firstOrNull { it.id == id } }
                if (selectedId != null && selected == null) Text("${entry.first.first}: perfil selecionado ausente; uso bloqueado até escolher outro perfil ou Direto.", color = c.err, fontSize = 12.sp)
                if (selected != null) {
                    val check = dev.fluwy.app.proxy.ProxyValidator.validate(selected, entry.second)
                    if (!check.valid) Text("${entry.first.first}: ${check.message}", color = c.err, fontSize = 12.sp)
                }
            }
            FluwyCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Rotação automática", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text("Desligada por padrão. Quando habilitada, alterna sem repetição entre perfis válidos e não altera conexões long-lived já abertas.", color = c.ink2, fontSize = 12.sp)
                    ToggleRow("Habilitar rotação", "A rotação começa após o intervalo definido.", rotation.enabled) { enabled ->
                        saveRotation(rotation.copy(enabled = enabled))
                    }
                    OutlinedTextField(
                        intervalText,
                        { intervalText = it.filter(Char::isDigit).take(4) },
                        label = { Text("Intervalo em minutos (1–1440)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(onClick = {
                        val minutes = intervalText.toIntOrNull()?.takeIf { it in 1..1_440 }
                        if (minutes == null) android.widget.Toast.makeText(nav.context, "Informe um intervalo entre 1 e 1440 minutos.", android.widget.Toast.LENGTH_SHORT).show()
                        else saveRotation(rotation.copy(intervalMinutes = minutes))
                    }) { Text("Aplicar intervalo") }
                    ProxyRotationPool("Pool Browser", dev.fluwy.app.proxy.ProxyScope.BROWSER, profiles, rotation.browserProfileIds) { ids ->
                        saveRotation(rotation.copy(browserProfileIds = ids))
                    }
                    ProxyRotationPool("Pool Terminal", dev.fluwy.app.proxy.ProxyScope.TERMINAL, profiles, rotation.terminalProfileIds) { ids ->
                        saveRotation(rotation.copy(terminalProfileIds = ids))
                    }
                    ProxyRotationPool("Pool Agent/API", dev.fluwy.app.proxy.ProxyScope.AGENT_API, profiles, rotation.agentApiProfileIds) { ids ->
                        saveRotation(rotation.copy(agentApiProfileIds = ids))
                    }
                    Text("A troca de perfil vale para novas chamadas HTTP/WebSocket e novos comandos do Terminal. WebSockets ativos da IQ Option não são reiniciados automaticamente; reconectar é uma ação separada.", color = c.ink3, fontSize = 11.sp)
                }
            }
            profiles.forEach { p ->
                FluwyCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(p.name, fontWeight = FontWeight.SemiBold); Text("${p.scheme.name} ${p.host}:${p.port}" + if (p.hasCredentials) " · credencial protegida" else "", fontSize = 12.sp, color = c.ink2) }
                        TextButton({ editing = p }) { Text("Editar") }
                        TextButton({
                            profiles = profiles.filterNot { it.id == p.id }
                            app.proxyStore.saveProfiles(profiles)
                            app.proxyStore.saveCredentials(p.id, null)
                            app.store.updateSettings { it.copy(browserProxyId = if (it.browserProxyId == p.id) null else it.browserProxyId, terminalProxyId = if (it.terminalProxyId == p.id) null else it.terminalProxyId, agentApiProxyId = if (it.agentApiProxyId == p.id) null else it.agentApiProxyId) }
                            saveRotation(rotation.copy(
                                browserProfileIds = rotation.browserProfileIds - p.id,
                                terminalProfileIds = rotation.terminalProfileIds - p.id,
                                agentApiProfileIds = rotation.agentApiProfileIds - p.id,
                            ))
                            app.connectorDebugLog.recordEvent("proxy", "profile", "removed profile_id=${p.id}")
                            app.applyBrowserProxy()
                        }) { Text("Remover", color = c.err) }
                    }
                }
            }
            PillButton("Adicionar perfil", Icons.Rounded.Add, modifier = Modifier.fillMaxWidth()) { editing = dev.fluwy.app.proxy.ProxyProfile() }
            PillButton("Importar lista (.txt)", Icons.Rounded.Download, modifier = Modifier.fillMaxWidth()) { importResult = null; runCatching { importPicker.launch("text/*") } }
            Text("Um proxy por linha, sem login e senha: ip:porta, http://ip:porta ou socks5://ip:porta.", color = c.ink3, fontSize = 12.sp)
            importResult?.let { Text(it, color = c.ink2, fontSize = 13.sp) }
            ConnectorDebugSection("proxy")
        }
    }
    editing?.let { p -> ProxyEditor(p, { save(it) }, { editing = null }) }
}

@Composable
private fun ProxyRotationPool(
    title: String,
    scope: dev.fluwy.app.proxy.ProxyScope,
    profiles: List<dev.fluwy.app.proxy.ProxyProfile>,
    selectedIds: List<String>,
    onSelectionChange: (List<String>) -> Unit,
) {
    val c = Fluwy.c
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, fontWeight = FontWeight.Medium, fontSize = 13.sp)
        val eligible = profiles.filter { dev.fluwy.app.proxy.ProxyValidator.validate(it, scope).valid }
        if (eligible.isEmpty()) Text("Nenhum perfil válido para este escopo.", color = c.ink3, fontSize = 11.sp)
        eligible.forEach { profile ->
            val checked = profile.id in selectedIds
            Row(
                Modifier.fillMaxWidth().clickable {
                    val updated = if (checked) selectedIds - profile.id else selectedIds + profile.id
                    onSelectionChange(updated.distinct())
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = checked, onCheckedChange = { value ->
                    val updated = if (value) selectedIds + profile.id else selectedIds - profile.id
                    onSelectionChange(updated.distinct())
                })
                Column {
                    Text(profile.name, fontSize = 12.sp)
                    Text("${profile.scheme.name} · ${profile.host}:${profile.port}", color = c.ink3, fontSize = 10.sp)
                }
            }
        }
        if (selectedIds.size < 2) Text("Selecione ao menos 2 perfis para alternar neste escopo.", color = c.ink3, fontSize = 10.sp)
    }
}

@Composable
private fun ProxyScopeRow(
    label: String, selected: String?, profiles: List<dev.fluwy.app.proxy.ProxyProfile>,
    proxyScope: dev.fluwy.app.proxy.ProxyScope, onSelect: (String?) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontWeight = FontWeight.Medium)
        var expanded by remember { mutableStateOf(false) }
        Box { OutlinedButton({ expanded = true }) { Text(profiles.firstOrNull { it.id == selected }?.name ?: if (selected == null) "Direto" else "Perfil ausente") }; DropdownMenu(expanded, { expanded = false }) { DropdownMenuItem({ Text("Direto") }, { onSelect(null); expanded = false }); profiles.filter { dev.fluwy.app.proxy.ProxyValidator.validate(it, proxyScope).valid }.forEach { p -> DropdownMenuItem({ Text(p.name) }, { onSelect(p.id); expanded = false }) } } }
    }
}

@Composable
private fun ProxyEditor(initial: dev.fluwy.app.proxy.ProxyProfile, onSave: (dev.fluwy.app.proxy.ProxyProfile) -> Unit, onCancel: () -> Unit) {
    val c = Fluwy.c
    var p by remember(initial) { mutableStateOf(initial) }
    var user by remember(initial) { mutableStateOf("") }
    var pass by remember(initial) { mutableStateOf("") }
    var removeCredentials by remember(initial) { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onCancel, title = { Text("Perfil de proxy") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FluwyField(p.name, { p = p.copy(name = it) }, "Nome")
            FluwyField(p.host, { p = p.copy(host = it) }, "Host")
            FluwyField(p.port.toString(), { p = p.copy(port = it.toIntOrNull() ?: 0) }, "Porta")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { dev.fluwy.app.proxy.ProxyScheme.values().forEach { s -> Chip(s.name, p.scheme == s) { p = p.copy(scheme = s) } } }
            FluwyField(user, { user = it }, "Usuário opcional")
            FluwyField(pass, { pass = it }, "Senha opcional", password = true)
            Text("Credenciais ficam temporariamente neste formulário e são cifradas com Android Keystore antes de persistir; não vão ao estado compartilhado, logs ou prompts. Terminal e WebView recusam perfis autenticados.", fontSize = 11.sp, color = c.ink2)
            if (p.hasCredentials) TextButton({ removeCredentials = true; p = p.copy(hasCredentials = false); user = ""; pass = "" }) { Text("Remover credenciais", color = c.err) }
        }
    }, confirmButton = { TextButton({ val hasNewCredentials = user.isNotBlank() || pass.isNotBlank(); if (hasNewCredentials) FluwyApp.instance.proxyStore.saveCredentials(p.id, dev.fluwy.app.proxy.ProxyCredentials(user, pass)) else if (removeCredentials) FluwyApp.instance.proxyStore.saveCredentials(p.id, null); onSave(p.copy(hasCredentials = hasNewCredentials || (p.hasCredentials && !removeCredentials))) }) { Text("Salvar") } }, dismissButton = { TextButton(onCancel) { Text("Cancelar") } })
}
