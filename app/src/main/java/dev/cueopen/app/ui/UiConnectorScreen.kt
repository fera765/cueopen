package dev.cueopen.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.navigation.NavController
import dev.cueopen.app.CueApp
import dev.cueopen.app.ui.theme.Cue
import dev.cueopen.app.uiconnector.FieldType
import dev.cueopen.app.uiconnector.SettingType
import dev.cueopen.app.uiconnector.Tone
import dev.cueopen.app.uiconnector.UiBlock
import dev.cueopen.app.uiconnector.UiConnector
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel("UI Conectores")
        Text(
            "Conectores com interface própria: login, painéis ao vivo e ferramentas para o agente. Credenciais ficam cifradas no aparelho e nunca chegam à IA.",
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
                    Text(uc.spec.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
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

    var loggedIn by remember { mutableStateOf(connector.isLoggedIn) }
    var blocks by remember { mutableStateOf<List<UiBlock>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val fields = remember { mutableStateMapOf<String, String>() }
    var remember_ by remember { mutableStateOf(true) }
    var enabled by remember { mutableStateOf(hub.enabled(id)) }
    var agentRequired by remember { mutableStateOf(hub.agentRequired(id)) }

    suspend fun refresh() {
        blocks = runCatching { connector.snapshot(hub.settings(connector)) }
            .getOrElse { listOf(UiBlock.Note(it.message ?: it.javaClass.simpleName, Tone.BAD)) }
    }

    // Reconecta sozinho com as credenciais do cofre ao abrir a tela.
    LaunchedEffect(id) {
        if (!connector.isLoggedIn && hub.hasSavedCredentials(id)) {
            busy = true
            hub.ensureLoggedIn(id).onSuccess { loggedIn = true }.onFailure { error = it.message }
            busy = false
        }
    }
    // Painel ao vivo: atualiza a cada 5 s enquanto logado e a tela está aberta.
    LaunchedEffect(loggedIn) {
        while (loggedIn) { refresh(); delay(5_000) }
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
            if (loggedIn || hub.hasSavedCredentials(id)) item {
                OutlinedButton(
                    onClick = { scope.launch { hub.logout(id); loggedIn = false; blocks = emptyList(); notice = null } },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                ) { Text("Sair e esquecer credenciais", color = c.err) }
            }
            item {
                Text(
                    "Ferramentas para o agente: " + connector.spec.tools.joinToString { "uic_${connector.spec.id}_${it.name}" },
                    fontSize = 11.sp, color = c.ink3,
                )
            }
        }
    }
}

@Composable
private fun LoginCard(
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
private fun UiBlockView(b: UiBlock, onAction: (String, Map<String, String>) -> Unit) {
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
        is UiBlock.Stats -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            b.items.forEachIndexed { i, s ->
                Column(Modifier.weight(if (i == 0) 1.5f else 1f).clip(RoundedCornerShape(14.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(14.dp)).padding(12.dp)) {
                    Text(s.label, fontSize = 11.sp, color = c.ink3, maxLines = 1)
                    Text(s.value, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = toneColor(s.tone), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        is UiBlock.Table -> Column(card.padding(14.dp)) {
            Text(b.title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Spacer(Modifier.height(8.dp))
            if (b.rows.isEmpty()) Text(b.empty, fontSize = 13.sp, color = c.ink3)
            else {
                Row(Modifier.fillMaxWidth()) { b.columns.forEach { Text(it, Modifier.weight(1f), fontSize = 11.sp, color = c.ink3, maxLines = 1) } }
                b.rows.forEach { r ->
                    HorizontalDivider(color = c.line, modifier = Modifier.padding(vertical = 6.dp))
                    Row(Modifier.fillMaxWidth()) { r.forEach { Text(it, Modifier.weight(1f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) } }
                }
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
