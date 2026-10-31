package dev.cueopen.app.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import dev.cueopen.app.CueApp
import dev.cueopen.app.ui.theme.Cue
import dev.cueopen.app.uiconnector.ConnectorSettings
import dev.cueopen.app.uiconnector.FieldType
import dev.cueopen.app.uiconnector.Tone
import dev.cueopen.app.uiconnector.UiBlock
import dev.cueopen.app.uiconnector.custom.CustomConnector
import dev.cueopen.app.uiconnector.custom.CustomDef
import dev.cueopen.app.uiconnector.custom.CustomDefParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Criador de UI Connectors: o usuário escreve (ou pede à IA) a definição YAML, vê a validação ao vivo,
 * testa login/painéis/ferramentas e salva. Nada é executado no aparelho além de requisições HTTPS
 * ao host declarado em baseUrl.
 */
@Composable
fun UiConnectorEditorScreen(nav: NavController, id: String) {
    val c = Cue.c
    val hub = CueApp.instance.uiConnectors
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val editing = id != "new"
    val context = androidx.compose.ui.platform.LocalContext.current
    val docLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/markdown")
    ) { uri ->
        if (uri != null) {
            val ok = runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(dev.cueopen.app.uiconnector.custom.SdkPrompt.MARKDOWN.toByteArray()) } != null
            }.getOrDefault(false)
            android.widget.Toast.makeText(context, if (ok) "Documentação do SDK salva." else "Não foi possível salvar o arquivo.", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    var text by remember { mutableStateOf(if (editing) hub.customYaml(id).orEmpty() else "") }
    var parsed by remember { mutableStateOf<CustomDefParser.Parsed?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var confirmSave by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var aiDialog by remember { mutableStateOf(false) }
    var aiText by remember { mutableStateOf("") }

    // validação ao vivo (debounce)
    LaunchedEffect(text) {
        delay(350)
        parsed = withContext(Dispatchers.Default) { CustomDefParser.parse(text) }
    }
    val def: CustomDef? = parsed?.def

    Scaffold(containerColor = c.bg, topBar = {
        CueTopBar(if (editing) "Editar conector" else "Novo conector", { nav.popBackStack() }) {
            TextButton({ docLauncher.launch("cueopen-ui-connector-sdk.md") }) {
                Icon(Icons.Rounded.Description, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Doc")
            }
            if (editing) IconButton({ confirmDelete = true }) { Icon(Icons.Rounded.Delete, "Excluir", tint = c.err) }
        }
    }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(
                    "Descreva o conector em YAML (login, painéis, ações e ferramentas do agente). Capacidades locais como terminal, arquivos e delegação LLM são opcionais, separadas e exigem autorização. Toque em Doc para baixar a especificação do SDK, ou em Criar com a IA para o chat criar, validar e instalar.",
                    fontSize = 13.sp, color = c.ink2,
                )
            }
            if (!editing) item {
                OutlinedButton(
                    onClick = { text = dev.cueopen.app.uiconnector.custom.LocalDevStudioTemplate.YAML; message = "Modelo Fluwy Open Dev carregado. Revise as permissões e o YAML antes de criar." },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                ) { Icon(Icons.Rounded.Code, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Carregar modelo Fluwy Open Dev") }
            }
            item {
                OutlinedTextField(
                    value = text, onValueChange = { text = it; message = null; saveError = null },
                    modifier = Modifier.fillMaxWidth(), minLines = 14, maxLines = 40,
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                    shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    label = { Text("Definição (YAML)") },
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({
                        val pasted = clipboard.getText()?.text
                        if (!pasted.isNullOrBlank()) text = pasted
                    }, shape = RoundedCornerShape(12.dp)) { Text("Colar") }
                    OutlinedButton({ clipboard.setText(AnnotatedString(text)); message = "YAML copiado." }, shape = RoundedCornerShape(12.dp)) { Text("Copiar") }
                    OutlinedButton({
                        aiDialog = true
                    }, shape = RoundedCornerShape(12.dp)) { Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Criar com a IA") }
                }
            }
            item { ValidationCard(parsed) }
            message?.let { m -> item { Text(m, fontSize = 13.sp, color = c.ink2) } }
            saveError?.let { m -> item { Text(m, fontSize = 13.sp, color = c.err) } }
            item {
                Button(
                    onClick = { confirmSave = true }, enabled = def != null,
                    modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(14.dp),
                ) { Text(if (editing) "Salvar alterações" else "Criar conector", fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold) }
            }
            if (def != null) item { TestPanel(def) }
        }
    }

    if (aiDialog) {
        AlertDialog(
            onDismissRequest = { aiDialog = false },
            title = { Text("Criar conector com a IA") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Diga o serviço e o que o conector deve mostrar/fazer. A IA cria, valida, corrige e te mostra o botão Instalar.", fontSize = 13.sp)
                    OutlinedTextField(
                        value = aiText, onValueChange = { aiText = it }, minLines = 3, modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Ex.: notícias do g1 por RSS; ou meus top artistas do Spotify") },
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = aiText.isNotBlank(), onClick = {
                    aiDialog = false
                    val app = CueApp.instance
                    val sess = dev.cueopen.app.data.Session(title = "Conector: " + aiText.trim().take(40), connectorBuilder = true)
                    app.store.upsertSession(sess)
                    app.agent.send(sess.id, "Crie, valide e instale um UI Connector para: " + aiText.trim())
                    nav.navigate("chat/${sess.id}")
                }) { Text("Criar") }
            },
            dismissButton = { TextButton({ aiDialog = false }) { Text("Cancelar") } },
        )
    }
    if (confirmSave && def != null) {
        val risky = def.tools.filter { it.risky }
        AlertDialog(
            onDismissRequest = { confirmSave = false },
            title = { Text("Salvar \"${def.name}\"?") },
            text = {
                Text(
                    (if (def.host.isBlank()) "Este conector não declara host de rede." else "Este conector só falará com ${def.host} (HTTPS).") +
                        (if (risky.isNotEmpty()) "\n\nFerramentas sensíveis para o agente: " + risky.joinToString { it.name + if (it.requiresSignal) " (exige validação Jev)" else "" } + "." else "") +
                        (if (dev.cueopen.app.uiconnector.custom.Capabilities.AGENT in def.capabilities) "\n\nA delegação envia a tarefa e leituras autorizadas ao modelo configurado, pode consumir cota/custo e fica desligada até você autorizá-la." else "") +
                        "\n\nO agente só usa o conector se você ativar nele (\"Agent required ?\" e perfil do agente). Confirme só se você confia nesta definição.",
                )
            },
            confirmButton = {
                TextButton({
                    confirmSave = false
                    scope.launch {
                        hub.saveCustom(text)
                            .onSuccess { d -> nav.popBackStack(); nav.navigate("uiconnector/${d.id}") }
                            .onFailure { saveError = it.message }
                    }
                }) { Text("Salvar") }
            },
            dismissButton = { TextButton({ confirmSave = false }) { Text("Cancelar") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Excluir conector?") }, text = { Text("Remove a definição e as credenciais salvas deste conector.") },
            confirmButton = { TextButton({ confirmDelete = false; scope.launch { hub.deleteCustom(id); nav.popBackStack() } }) { Text("Excluir", color = c.err) } },
            dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun ValidationCard(p: CustomDefParser.Parsed?) {
    val c = Cue.c
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(14.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        when {
            p == null -> Text("Validando…", fontSize = 13.sp, color = c.ink3)
            p.errors.isNotEmpty() -> {
                Text("${p.errors.size} problema(s) — corrija para salvar:", fontSize = 13.sp, color = c.err, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                p.errors.take(12).forEach { Text("• $it", fontSize = 12.sp, color = c.err) }
                if (p.errors.size > 12) Text("… e mais ${p.errors.size - 12}.", fontSize = 12.sp, color = c.ink3)
            }
            else -> {
                val d = p.def!!
                Text("✓ Definição válida", fontSize = 13.sp, color = c.ok, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                Text("Host: ${d.host} · ${d.tools.size} ferramenta(s) (${d.tools.count { it.risky }} sensível) · ${d.panels.size} painel(is) · ${d.actions.size} ação(ões)", fontSize = 12.sp, color = c.ink2)
            }
        }
        p?.warnings?.take(8)?.forEach { Text("⚠ $it", fontSize = 12.sp, color = c.warn) }
    }
}

/** Testa a definição ainda não salva: login real, painéis e execução de ferramentas (sem salvar credenciais). */
@Composable
private fun TestPanel(def: CustomDef) {
    val c = Cue.c
    val scope = rememberCoroutineScope()
    val connector = remember(def) { CustomConnector(def) }
    androidx.compose.runtime.DisposableEffect(connector) { onDispose { connector.dispose() } }
    val settings = remember(def) { ConnectorSettings { k -> def.settings.firstOrNull { it.id == k }?.default } }
    val fields = remember(def) { mutableStateMapOf<String, String>() }
    var loggedIn by remember(def) { mutableStateOf(false) }
    var busy by remember(def) { mutableStateOf(false) }
    var error by remember(def) { mutableStateOf<String?>(null) }
    var blocks by remember(def) { mutableStateOf<List<UiBlock>>(emptyList()) }
    var toolOut by remember(def) { mutableStateOf<String?>(null) }
    var selected by remember(def) { mutableStateOf(def.tools.firstOrNull()?.name) }
    val params = remember(def) { mutableStateMapOf<String, String>() }
    var keep by remember(def) { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel("Testar")
        if (!loggedIn) {
            LoginCard(connector, fields, keep, { keep = it }, busy, error) {
                scope.launch {
                    busy = true; error = null
                    val creds = def.login.fields.associate { f -> f.id to (if (f.type == FieldType.PASSWORD) fields[f.id].orEmpty() else fields[f.id].orEmpty().trim()) }
                    connector.login(creds)
                        .onSuccess {
                            loggedIn = true
                            blocks = runCatching { connector.snapshot(settings) }.getOrElse { e -> listOf(UiBlock.Note(e.message ?: "erro", Tone.BAD)) }
                        }
                        .onFailure { error = it.message ?: "Falha no login." }
                    busy = false
                }
            }
            Text("O teste usa o login real, mas não salva as credenciais. (O botão \"Lembrar\" aqui não tem efeito.)", fontSize = 11.sp, color = c.ink3)
        } else {
            blocks.forEach { b -> UiBlockView(b) { _, _ -> } }
            if (def.tools.isNotEmpty()) {
                SectionLabel("Ferramentas")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    def.tools.forEach { t -> Chip(t.name, selected = selected == t.name) { selected = t.name; toolOut = null } }
                }
                val tool = def.tools.firstOrNull { it.name == selected }
                if (tool != null) {
                    tool.params.forEach { p ->
                        OutlinedTextField(
                            value = params["${tool.name}.${p.name}"].orEmpty(), onValueChange = { params["${tool.name}.${p.name}"] = it },
                            label = { Text(p.name + if (p.required) " *" else "") }, supportingText = { Text(p.description) },
                            singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                        )
                    }
                    if (tool.risky) Text("⚠ Ferramenta sensível: este teste executa a chamada de verdade (valores acima do limite padrão são recusados).", fontSize = 12.sp, color = c.warn)
                    Button(
                        onClick = {
                            scope.launch {
                                busy = true
                                val args = tool.params.associate { it.name to params["${tool.name}.${it.name}"].orEmpty() }
                                toolOut = connector.callTool(tool.name, args, settings)
                                busy = false
                            }
                        }, enabled = !busy, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                    ) { Text("Executar ${tool.name}") }
                }
                toolOut?.let { Text(it.take(1500), fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = c.ink2) }
            }
            OutlinedButton({ scope.launch { connector.logout(); loggedIn = false; blocks = emptyList(); toolOut = null } }, shape = RoundedCornerShape(12.dp)) { Text("Encerrar teste") }
        }
    }
}
