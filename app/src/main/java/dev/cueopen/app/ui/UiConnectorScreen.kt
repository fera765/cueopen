package dev.cueopen.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import dev.cueopen.app.CueApp
import dev.cueopen.app.ui.theme.Cue
import dev.cueopen.app.uiconnector.FieldType
import dev.cueopen.app.uiconnector.SettingType
import dev.cueopen.app.uiconnector.Tone
import dev.cueopen.app.uiconnector.UiBlock
import dev.cueopen.app.uiconnector.UiConnector
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Composable
private fun toneColor(t: Tone): Color {
    val c = Cue.c
    return when (t) { Tone.GOOD -> c.ok; Tone.BAD -> c.err; Tone.WARN -> c.warn; Tone.NEUTRAL -> c.ink }
}

/** Seção "UI Conectores" da página de Conectores: um cartão por conector do SDK. */
@Composable
fun UiConnectorsSection(nav: NavController) {
    val c = Cue.c
    val hub = CueApp.instance.uiConnectors
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var version by remember { mutableStateOf(0) }
    var report by remember { mutableStateOf<String?>(null) }
    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            val ok = runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(hub.exportBackup().toByteArray()) } != null }.getOrDefault(false)
            android.widget.Toast.makeText(context, if (ok) "Backup dos UI Conectores salvo." else "Não foi possível salvar o backup.", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) scope.launch {
            val text = runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull()
            report = if (text == null) "Não foi possível ler o arquivo."
            else hub.importBackup(text).fold(
                { r ->
                    "Importado: ${r.installed.size} conector(es)" + (if (r.installed.isNotEmpty()) " (" + r.installed.joinToString() + ")" else "") +
                        ", ${r.prefs} configuração(ões). Faça login de novo nos conectores (credenciais não vão no backup)." +
                        (if (r.errors.isNotEmpty()) "\nNão importados: " + r.errors.joinToString("; ") else "")
                },
                { it.message ?: "Falha ao importar." },
            )
            version++
        }
    }
    key(version) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { SectionLabel("UI Conectores") }
            TextButton({ exportLauncher.launch("cueopen-ui-conectores-backup.json") }) {
                Icon(Icons.Rounded.Backup, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Backup")
            }
            TextButton({ importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*")) }) {
                Icon(Icons.Rounded.FileOpen, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Importar")
            }
        }
        report?.let { Text(it, fontSize = 12.sp, color = c.ink2) }
        Text(
            "Conectores com interface própria: login, painéis ao vivo e ferramentas para o agente. Credenciais ficam cifradas no aparelho; o Proxy configura a rede do app e não vira ferramenta do agente.",
            fontSize = 13.sp, color = c.ink2,
        )
        hub.connectors.forEach { uc ->
            val status = when {
                uc.isLoggedIn -> "Conectado" to c.ok
                hub.hasSavedCredentials(uc.spec.id) -> "Credenciais salvas" to c.warn
                else -> "Desconectado" to c.ink3
            }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface)
                    .border(1.dp, c.line, RoundedCornerShape(16.dp))
                    .clickable { nav.navigate("uiconnector/${uc.spec.id}") }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(Color(uc.spec.accent)), contentAlignment = Alignment.Center) {
                    Text(uc.spec.name.take(1), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(uc.spec.name + if (hub.isCustom(uc.spec.id)) "  ·  Personalizado" else "", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                    Text(uc.spec.description, fontSize = 12.sp, color = c.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(status.second))
                        Spacer(Modifier.width(6.dp))
                        Text(status.first, fontSize = 12.sp, color = c.ink2)
                        if (!hub.enabled(uc.spec.id)) Text("  ·  desabilitado", fontSize = 12.sp, color = c.ink3)
                    }
                }
                Icon(Icons.Rounded.ChevronRight, null, tint = c.ink3)
            }
        }
        // Proxy é uma configuração compartilhada do app, não uma ferramenta do agente.
        // Mostre-o no mesmo catálogo visual e reutilize a tela de perfis já existente.
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface)
                .border(1.dp, c.line, RoundedCornerShape(16.dp))
                .clickable { nav.navigate("proxy") }.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(c.accent), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.SettingsEthernet, null, tint = Color.White, modifier = Modifier.size(23.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Proxy", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text("Perfis independentes para Browser, Terminal e Agent/API.", fontSize = 12.sp, color = c.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(c.accent))
                    Spacer(Modifier.width(6.dp))
                    Text("Configuração de rede", fontSize = 12.sp, color = c.ink2)
                }
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = c.ink3)
        }
        OutlinedButton(
            onClick = { nav.navigate("uiconnector/edit/new") },
            modifier = Modifier.fillMaxWidth().height(46.dp), shape = RoundedCornerShape(14.dp),
        ) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(8.dp)); Text("Criar conector (SDK)") }
    }
    }
}

/** Tela de um UI Connector: login → painéis renderizados a partir dos [UiBlock]s → configurações. */
@Composable
fun UiConnectorScreen(nav: NavController, id: String) {
    val c = Cue.c
    val hub = CueApp.instance.uiConnectors
    val connector = hub.get(id)
    if (connector == null) { LaunchedEffect(Unit) { nav.popBackStack() }; return }
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    var loggedIn by remember { mutableStateOf(connector.isLoggedIn) }
    var blocks by remember { mutableStateOf<List<UiBlock>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val fields = remember { mutableStateMapOf<String, String>() }
    var remember_ by remember { mutableStateOf(true) }
    var enabled by remember { mutableStateOf(hub.enabled(id)) }
    var agentRequired by remember { mutableStateOf(hub.agentRequired(id)) }

    val refreshMutex = remember(id) { Mutex() }
    suspend fun refresh(): Boolean = refreshMutex.withLock {
        val next = runCatching { connector.snapshot(hub.settings(connector)) }
            .getOrElse {
                CueApp.instance.connectorDebugLog.recordFailure(id, "snapshot", it)
                listOf(UiBlock.Note(it.message ?: it.javaClass.simpleName, Tone.BAD))
            }
        val changed = next != blocks
        if (changed) blocks = next
        changed
    }

    // Reconecta sozinho com as credenciais do cofre ao abrir a tela.
    LaunchedEffect(id) {
        if (!connector.isLoggedIn && hub.hasSavedCredentials(id)) {
            busy = true
            hub.ensureLoggedIn(id).onSuccess { loggedIn = true }.onFailure { error = it.message }
            busy = false
        }
    }
    // A tela observa eventos do conector enquanto visível. Sem push, usa fallback
    // exponencial limitado; não mantém ciclo de atualização oculto em background.
    LaunchedEffect(id, loggedIn, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            coroutineScope {
                if (connector.isLoggedIn) {
                    loggedIn = true
                    refresh()
                }
                launch {
                    connector.stateChanges.collect {
                        val live = connector.isLoggedIn
                        if (live != loggedIn) loggedIn = live
                        if (live) refresh()
                    }
                }
                val baseInterval = connector.fallbackRefreshMs?.coerceIn(5_000L, 60_000L) ?: awaitCancellation()
                var interval = baseInterval
                while (isActive) {
                    delay(interval)
                    val live = connector.isLoggedIn
                    if (live != loggedIn) loggedIn = live
                    if (live) {
                        val changed = refresh()
                        interval = if (changed) baseInterval else (interval * 2).coerceAtMost(60_000L)
                    } else interval = baseInterval
                }
            }
        }
    }

    Scaffold(containerColor = c.bg, topBar = {
        CueTopBar(connector.spec.name, { nav.popBackStack() }) {
            if (loggedIn) IconButton({ scope.launch { refresh() } }) { Icon(Icons.Rounded.Refresh, "Atualizar") }
        }
    }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!loggedIn) item {
                LoginCard(connector, fields, remember_, { remember_ = it }, busy, error) {
                    scope.launch {
                        busy = true; error = null
                        // senhas vão exatamente como digitadas; os demais campos sem espaços nas pontas
                        val creds = connector.spec.loginFields.associate { f ->
                            val raw = fields[f.id].orEmpty()
                            f.id to (if (f.type == FieldType.PASSWORD) raw else raw.trim())
                        }
                        hub.login(id, creds, remember_)
                            .onSuccess { loggedIn = true; fields.clear() }
                            .onFailure { error = it.message ?: "Falha no login." }
                        busy = false
                    }
                }
            } else {
                notice?.let { n -> item { Text(n, fontSize = 13.sp, color = c.ink2, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.accentSoft).padding(12.dp)) } }
                if (blocks.isEmpty()) item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp, color = c.accent) } }
                blocks.forEach { b ->
                    item {
                        UiBlockView(b) { action, args ->
                            scope.launch {
                                notice = connector.onAction(action, args, hub.settings(connector)).fold({ it }, { it.message ?: "Falha na ação." })
                                refresh()
                            }
                        }
                    }
                }
            }

            item { SectionLabel("Configurações do conector") }
            item {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(16.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ToggleRow("Habilitado", "Desligado: o conector some para o agente e para as conversas.", enabled) { enabled = it; hub.setEnabled(id, it) }
                    ToggleRow(
                        "Agent required ?",
                        "Ligado: só agentes com este conector ativado no perfil podem usá-lo. Desligado: qualquer conversa pode usar.",
                        agentRequired,
                    ) { agentRequired = it; hub.setAgentRequired(id, it) }
                    connector.spec.settings.forEach { s ->
                        when (s.type) {
                            SettingType.SWITCH -> {
                                var v by remember(s.id) { mutableStateOf(hub.settingValue(connector, s).equals("true", true)) }
                                ToggleRow(s.label + if (s.risky) "  ⚠" else "", s.description, v) { v = it; hub.setSetting(connector, s, it.toString()) }
                            }
                            else -> {
                                var v by remember(s.id) { mutableStateOf(hub.settingValue(connector, s)) }
                                Column {
                                    OutlinedTextField(
                                        value = v, onValueChange = { v = it; hub.setSetting(connector, s, it) },
                                        label = { Text(s.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                                        keyboardOptions = KeyboardOptions(keyboardType = if (s.type == SettingType.NUMBER) KeyboardType.Decimal else KeyboardType.Text),
                                    )
                                    Text(s.description, fontSize = 12.sp, color = c.ink3, modifier = Modifier.padding(start = 4.dp, top = 2.dp))
                                }
                            }
                        }
                    }
                }
            }
            val caps = hub.declaredCapabilities(id)
            if (caps.isNotEmpty()) {
                item { SectionLabel("Permissões do conector") }
                item {
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(16.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Este conector pede acesso a recursos do aparelho. Autorize só o necessário — fica desligado por padrão.", fontSize = 12.sp, color = c.ink3)
                        dev.cueopen.app.uiconnector.custom.Capabilities.ALL.filter { it in caps }.forEach { cap ->
                            var g by remember(cap) { mutableStateOf(hub.capabilityGranted(id, cap)) }
                            ToggleRow(
                                "⚠ " + dev.cueopen.app.uiconnector.custom.Capabilities.label(cap),
                                dev.cueopen.app.uiconnector.custom.Capabilities.warning(cap),
                                g,
                            ) { g = it; hub.setCapabilityGranted(id, cap, it) }
                        }
                    }
                }
            }
            if (loggedIn || hub.hasSavedCredentials(id)) item {
                OutlinedButton(
                    onClick = { scope.launch { hub.logout(id); loggedIn = false; blocks = emptyList(); notice = null } },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                ) { Text("Sair e esquecer credenciais", color = c.err) }
            }
            if (hub.isCustom(id)) item {
                OutlinedButton(
                    onClick = { nav.navigate("uiconnector/edit/$id") },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                ) { Text("Editar definição do conector") }
            }
            item {
                Text(
                    "Ferramentas para o agente: " + connector.spec.tools.joinToString { "uic_${connector.spec.id}_${it.name}" },
                    fontSize = 11.sp, color = c.ink3,
                )
            }
            item { ConnectorDebugSection(id) }
        }
    }
}

@Composable
internal fun LoginCard(
    connector: UiConnector, fields: MutableMap<String, String>,
    keepCredentials: Boolean, onKeepChange: (Boolean) -> Unit,
    busy: Boolean, error: String?, onLogin: () -> Unit,
) {
    val c = Cue.c
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(18.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Color(connector.spec.accent)), contentAlignment = Alignment.Center) {
                Text(connector.spec.name.take(1), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Entrar em ${connector.spec.name}", fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                Text("v${connector.spec.version}", fontSize = 12.sp, color = c.ink3)
            }
        }
        connector.spec.loginFields.forEach { f ->
            var reveal by remember(f.id) { mutableStateOf(false) }
            val secret = f.type == FieldType.PASSWORD
            OutlinedTextField(
                value = fields[f.id].orEmpty(), onValueChange = { fields[f.id] = it },
                label = { Text(f.label) }, placeholder = { if (f.hint.isNotBlank()) Text(f.hint) },
                singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                visualTransformation = if (secret && !reveal) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = when (f.type) {
                    FieldType.EMAIL -> KeyboardType.Email; FieldType.PASSWORD -> KeyboardType.Password
                    FieldType.NUMBER -> KeyboardType.Number; FieldType.TEXT -> KeyboardType.Text
                }),
                trailingIcon = if (secret) ({
                    IconButton({ reveal = !reveal }) { Icon(if (reveal) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (reveal) "Ocultar" else "Mostrar") }
                }) else null,
            )
        }
        ToggleRow("Lembrar neste aparelho", "As credenciais ficam cifradas no cofre do aparelho (Android Keystore) para o agente reconectar sozinho. Nunca são enviadas à IA.", keepCredentials, onKeepChange)
        error?.let { Text(it, color = c.err, fontSize = 13.sp) }
        Button(onClick = onLogin, enabled = !busy, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(14.dp)) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = c.bg) else Text("Entrar", fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Renderiza um [UiBlock] — genérico para qualquer conector. */
@Composable
internal fun UiBlockView(b: UiBlock, onAction: (String, Map<String, String>) -> Unit) {
    val c = Cue.c
    val card = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(16.dp))
    when (b) {
        is UiBlock.Header -> Row(card.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(b.title, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                if (b.subtitle.isNotBlank()) Text(b.subtitle, fontSize = 13.sp, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (b.badge.isNotBlank()) {
                val col = toneColor(b.tone)
                Text(b.badge, color = col, fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
                    modifier = Modifier.clip(CircleShape).border(1.dp, col, CircleShape).padding(horizontal = 12.dp, vertical = 6.dp))
            }
        }
        is UiBlock.Stats -> {
            // até 3 por linha, quebrando em várias linhas: nada de valor truncado quando há muitos tiles
            val perRow = if (b.items.size <= 3) b.items.size.coerceAtLeast(1) else 3
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                b.items.chunked(perRow).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { s ->
                            Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(14.dp)).padding(12.dp)) {
                                Text(s.label, fontSize = 11.sp, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(s.value, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = toneColor(s.tone), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        is UiBlock.Table -> Column(card.padding(14.dp)) {
            Text(b.title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Spacer(Modifier.height(8.dp))
            if (b.rows.isEmpty()) Text(b.empty, fontSize = 13.sp, color = c.ink3)
            else if (b.columns.size <= 3 && b.rows.all { r -> r.sumOf { it.length } <= 42 }) {
                Row(Modifier.fillMaxWidth()) { b.columns.forEach { Text(it, Modifier.weight(1f), fontSize = 11.sp, color = c.ink3, maxLines = 1) } }
                b.rows.forEach { r ->
                    HorizontalDivider(color = c.line, modifier = Modifier.padding(vertical = 6.dp))
                    Row(Modifier.fillMaxWidth()) { r.forEach { Text(it, Modifier.weight(1f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) } }
                }
            } else {
                // tabela larga: rolagem horizontal com largura por coluna pelo conteúdo (nada fica escondido em telas pequenas)
                val widths = b.columns.indices.map { i ->
                    val longest = maxOf(b.columns[i].length, b.rows.maxOfOrNull { it.getOrNull(i)?.length ?: 0 } ?: 0)
                    (longest * 8 + 16).coerceIn(64, 260).dp
                }
                Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    Row { b.columns.forEachIndexed { i, h -> Text(h, Modifier.width(widths[i]), fontSize = 11.sp, color = c.ink3, maxLines = 1) } }
                    b.rows.forEach { r ->
                        HorizontalDivider(color = c.line, modifier = Modifier.padding(vertical = 6.dp).width(widths.fold(0.dp) { acc, w -> acc + w }))
                        Row { r.forEachIndexed { i, v -> Text(v, Modifier.width(widths.getOrElse(i) { 100.dp }), fontSize = 13.sp, maxLines = 1,
                            color = when { "ganhando" in v || v.startsWith("WIN") -> c.ok; "perdendo" in v || v.startsWith("LOSS") -> c.err; else -> c.ink }) } }
                    }
                }
                Text("↔ deslize para ver todas as colunas", fontSize = 11.sp, color = c.ink3, modifier = Modifier.padding(top = 6.dp))
            }
        }
        is UiBlock.Segmented -> {
            var pending by remember { mutableStateOf<String?>(null) }
            Column(card.padding(14.dp)) {
                Text(b.title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    b.options.forEach { (value, label) ->
                        Chip(label, selected = value == b.selected) {
                            if (value == b.selected) return@Chip
                            if (value in b.confirmValues) pending = value else onAction(b.actionId, mapOf("value" to value))
                        }
                    }
                }
            }
            pending?.let { v ->
                AlertDialog(
                    onDismissRequest = { pending = null },
                    title = { Text("Confirmar") }, text = { Text(b.confirmText) },
                    confirmButton = { TextButton({ pending = null; onAction(b.actionId, mapOf("value" to v)) }) { Text("Confirmar", color = c.err) } },
                    dismissButton = { TextButton({ pending = null }) { Text("Cancelar") } },
                )
            }
        }
        is UiBlock.Button -> Button(
            onClick = { onAction(b.actionId, b.args) }, modifier = Modifier.fillMaxWidth().height(46.dp), shape = RoundedCornerShape(14.dp),
            colors = if (b.risky) ButtonDefaults.buttonColors(containerColor = c.err) else ButtonDefaults.buttonColors(),
        ) { Text(b.label) }
        is UiBlock.Note -> Text(b.text, fontSize = 12.sp, color = if (b.tone == Tone.NEUTRAL) c.ink3 else toneColor(b.tone), modifier = Modifier.padding(horizontal = 4.dp))
    }
}
