package dev.cueopen.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.cueopen.app.CueApp
import dev.cueopen.app.browser.BrowserFeatures
import dev.cueopen.app.browser.BrowserHost
import dev.cueopen.app.browser.BrowserPolicies
import dev.cueopen.app.data.AgentProfile
import dev.cueopen.app.data.BrowserLogin
import dev.cueopen.app.data.UserScript
import dev.cueopen.app.ui.theme.Cue
import kotlinx.coroutines.launch

private fun ago(t: Long?): String {
    if (t == null) return "nunca"
    val m = (System.currentTimeMillis() - t) / 60_000
    return when { m < 1 -> "agora"; m < 60 -> "há $m min"; m < 1440 -> "há ${m / 60} h"; else -> "há ${m / 1440} d" }
}

/** Seção Browser: browsers dos agentes, sessões de login globais, User Scripts e configurações. */
@Composable
fun BrowserHubScreen(nav: NavController) {
    val c = Cue.c
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Browsers", "Sessões", "API Hub", "Scripts", "Configurações")
    Scaffold(containerColor = c.bg, topBar = { CueTopBar("Browser", { nav.popBackStack() }) }) { pad ->
        Column(Modifier.padding(pad)) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tabs.forEachIndexed { i, t -> Chip(t, selected = tab == i) { tab = i } }
            }
            when (tab) {
                0 -> AgentBrowsersTab(nav)
                1 -> LoginsTab(nav)
                2 -> WebApiHubTab(nav)
                3 -> UserScriptsTab(nav)
                else -> BrowserSettingsTab()
            }
        }
    }
}

@Composable
private fun AgentBrowsersTab(nav: NavController) {
    val app = CueApp.instance
    val st by app.store.state.collectAsStateWithLifecycle()
    val c = Cue.c
    val open = remember(st.sessions.size) { app.openBrowsers().filterKeys { it != BrowserFeatures.LOGIN_BROWSER_KEY } }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Cada chat/agente tem o PRÓPRIO browser (abas, histórico e estado independentes). Só as sessões de login e os User Scripts abaixo são globais, e cada um respeita os agentes autorizados.",
                fontSize = 13.sp, color = c.ink2, lineHeight = 18.sp)
        }
        if (open.isEmpty()) item { EmptyState(Icons.Rounded.Language, "Nenhum browser aberto", "Os browsers aparecem aqui quando um agente navega.") }
        items(open.entries.toList(), key = { it.key }) { (sid, host) ->
            val ui by host.ui.collectAsStateWithLifecycle()
            val s = st.sessions.firstOrNull { it.id == sid }
            val agent = s?.agentId?.let { id -> st.agents.firstOrNull { it.id == id } }
            CueCard(onClick = { nav.navigate("computer/$sid?tab=browser") }) {
                Text(agent?.name ?: s?.title ?: "Chat", fontWeight = FontWeight.SemiBold, color = c.ink)
                Text(ui.url.ifBlank { "(sem página)" }, fontSize = 12.sp, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Monospace)
                if (ui.loading) Text("carregando…", fontSize = 11.sp, color = c.accent)
            }
        }
    }
}

@Composable
private fun LoginsTab(nav: NavController) {
    val app = CueApp.instance
    val st by app.store.state.collectAsStateWithLifecycle()
    val c = Cue.c
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<BrowserLogin?>(null) }
    var counts by remember { mutableStateOf(mapOf<String, Int>()) }
    // Estado real: confere os cookies do perfil para cada sessão ao abrir a aba.
    LaunchedEffect(st.browserLogins.map { it.id to it.status }) {
        counts = st.browserLogins.associate { it.id to BrowserFeatures.cookieCount(it.domain) }
        st.browserLogins.forEach { l ->
            val n = counts[l.id] ?: 0
            val status = if (l.status == "revoked") "revoked" else if (n > 0) "active" else "expired"
            if (status != l.status || l.lastVerifiedAt == null || System.currentTimeMillis() - (l.lastVerifiedAt) > 60_000)
                app.store.editBrowserLogin(l.id) { it.copy(status = status, lastVerifiedAt = System.currentTimeMillis()) }
        }
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Faça login uma vez num site e salve a sessão: os agentes autorizados entram já logados, cada um no próprio browser. A senha nunca passa pela IA nem fica salva pelo app.",
                fontSize = 13.sp, color = c.ink2, lineHeight = 18.sp)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Adicionar sessão", Icons.Rounded.Add) { adding = true }
                if (st.browserLogins.isNotEmpty()) PillButton("Sair de todas", Icons.Rounded.Logout) {
                    scope.launch { st.browserLogins.forEach { l -> BrowserFeatures.revoke(l.domain); app.store.editBrowserLogin(l.id) { it.copy(status = "revoked") } } }
                }
            }
        }
        if (st.browserLogins.isEmpty()) item { EmptyState(Icons.Rounded.Key, "Nenhuma sessão salva", "Toque em Adicionar sessão, informe o site e entre na sua conta.") }
        items(st.browserLogins, key = { it.id }) { l ->
            val n = counts[l.id]
            CueCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(l.name, fontWeight = FontWeight.SemiBold, color = c.ink)
                        Text(l.domain, fontSize = 12.sp, color = c.ink3, fontFamily = FontFamily.Monospace)
                    }
                    val (label, color) = when (l.status) {
                        "active" -> "ativa" to c.ok
                        "revoked" -> "revogada" to c.ink3
                        else -> "expirada" to c.accent
                    }
                    Box(Modifier.clip(CircleShape).background(c.surface2).padding(horizontal = 10.dp, vertical = 4.dp)) { Text(label, fontSize = 11.sp, color = color) }
                }
                Spacer(Modifier.height(6.dp))
                val agents = if (l.allowedAgentIds.isEmpty()) "todos os agentes" else l.allowedAgentIds.mapNotNull { id -> st.agents.firstOrNull { it.id == id }?.name }.joinToString()
                Text("Cookies no perfil: ${n ?: "…"} · usada ${l.useCount}× · último uso ${ago(l.lastUsedAt)}", fontSize = 12.sp, color = c.ink2)
                Text("Pode usar: $agents", fontSize = 12.sp, color = c.ink2)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton({ nav.navigate("browserlogin/${l.id}") }) { Text(if (l.status == "active") "Abrir/atualizar login" else "Entrar de novo") }
                    TextButton({ editing = l }) { Text("Editar") }
                    if (l.status != "revoked") TextButton({ scope.launch { BrowserFeatures.revoke(l.domain); app.store.editBrowserLogin(l.id) { it.copy(status = "revoked") } } }) { Text("Revogar") }
                    TextButton({ scope.launch { BrowserFeatures.revoke(l.domain); app.store.deleteBrowserLogin(l.id) } }) { Text("Excluir", color = c.accent) }
                }
            }
        }
        item { Spacer(Modifier.height(40.dp)) }
    }
    if (adding) LoginEditDialog(null, st.agents, onDismiss = { adding = false }) { saved ->
        adding = false
        app.store.upsertBrowserLogin(saved)
        nav.navigate("browserlogin/${saved.id}")
    }
    editing?.let { e -> LoginEditDialog(e, st.agents, onDismiss = { editing = null }) { saved -> editing = null; app.store.upsertBrowserLogin(saved) } }
}

@Composable
private fun AgentPicker(agents: List<AgentProfile>, selected: List<String>, onChange: (List<String>) -> Unit) {
    val c = Cue.c
    Text("Agentes que podem usar (nenhum marcado = todos):", fontSize = 12.sp, color = c.ink2)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        agents.forEach { a ->
            Chip(a.name, selected = a.id in selected) { onChange(if (a.id in selected) selected - a.id else selected + a.id) }
        }
    }
}

@Composable
private fun LoginEditDialog(existing: BrowserLogin?, agents: List<AgentProfile>, onDismiss: () -> Unit, onSave: (BrowserLogin) -> Unit) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var url by remember { mutableStateOf(existing?.url ?: "https://") }
    var allowed by remember { mutableStateOf(existing?.allowedAgentIds.orEmpty()) }
    val domain = BrowserPolicies.normalizeDomain(url)
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Nova sessão de login" else "Editar sessão") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Nome (ex.: Facebook da loja)") }, singleLine = true)
                OutlinedTextField(url, { url = it }, label = { Text("Endereço do site") }, singleLine = true, enabled = existing == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                if (url.length > 8 && domain.isEmpty()) Text("Endereço inválido", color = Cue.c.accent, fontSize = 12.sp)
                AgentPicker(agents, allowed) { allowed = it }
            }
        },
        confirmButton = {
            TextButton({
                onSave((existing ?: BrowserLogin(name = name, url = url.trim(), domain = domain)).copy(name = name.trim().ifBlank { domain }, allowedAgentIds = allowed))
            }, enabled = domain.isNotEmpty() && !domain.startsWith("*.")) { Text(if (existing == null) "Abrir e fazer login" else "Salvar") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancelar") } })
}

/** Browser de login: o usuário entra na conta manualmente; o app só confere os cookies e salva os metadados. */
@Composable
fun BrowserLoginScreen(nav: NavController, loginId: String, returnSession: String? = null) {
    val app = CueApp.instance
    val st by app.store.state.collectAsStateWithLifecycle()
    val c = Cue.c
    val login = st.browserLogins.firstOrNull { it.id == loginId }
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(loginId) { login?.let { runCatching { app.browserFor(BrowserFeatures.LOGIN_BROWSER_KEY).navigate(it.url) } } }
    Scaffold(containerColor = c.bg, topBar = {
        CueTopBar(login?.name ?: "Login", { nav.popBackStack() }) {
            TextButton({
                val l = login ?: return@TextButton
                scope.launch {
                    val n = BrowserFeatures.cookieCount(l.domain)
                    if (n == 0) msg = "Nenhum cookie de ${l.domain} encontrado. Termine o login e tente de novo."
                    else {
                        app.store.editBrowserLogin(l.id) { it.copy(status = "active", lastVerifiedAt = System.currentTimeMillis()) }
                        // Veio de um pedido do agente: a tarefa continua sozinha (como o Manus depois do takeover).
                        returnSession?.let { sid -> app.agent.onUserLoggedIn(sid, l.domain) }
                        nav.popBackStack()
                    }
                }
            }) { Text("Salvar sessão", color = c.accent, fontWeight = FontWeight.SemiBold) }
        }
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Text("Entre na sua conta normalmente e toque em Salvar sessão. A senha fica só neste site; o app guarda apenas que a sessão existe.",
                fontSize = 12.sp, color = c.ink2, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            msg?.let { Text(it, fontSize = 12.sp, color = c.accent, modifier = Modifier.padding(horizontal = 16.dp)) }
            if (login == null) EmptyState(Icons.Rounded.Key, "Sessão não encontrada", "Ela pode ter sido excluída.")
            else Box(Modifier.weight(1f)) { BrowserPane(BrowserFeatures.LOGIN_BROWSER_KEY) }
        }
    }
}

@Composable
private fun UserScriptsTab(nav: NavController) {
    val app = CueApp.instance
    val st by app.store.state.collectAsStateWithLifecycle()
    val c = Cue.c
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<UserScript?>(null) }
    var menu by remember { mutableStateOf(false) }
    var pasting by remember { mutableStateOf(false) }
    var aiDialog by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    fun install(code: String, source: String) = scope.launch {
        val r = dev.cueopen.app.browser.UserScriptInstaller.install(code, source)
        notice = r.error?.let { "Não instalado: $it" } ?: "${if (r.updated) "Atualizado" else "Instalado"}: ${r.script?.name} ${r.script?.version.orEmpty()}"
    }
    // Importar arquivo .js (Storage Access Framework): lê o texto e instala direto.
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.getOrNull()
            if (text == null) notice = "Não consegui ler o arquivo." else install(text, "arquivo")
        }
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Padrão Tampermonkey: nome, versão e sites saem do cabeçalho // ==UserScript== (@name, @version, @match). Rodam sozinhos nos browsers dos agentes, uma vez por página; 3 erros seguidos desligam o script.",
                fontSize = 13.sp, color = c.ink2, lineHeight = 18.sp)
            Spacer(Modifier.height(10.dp))
            PillButton("Novo script", Icons.Rounded.Add) { menu = true }
            notice?.let { Spacer(Modifier.height(8.dp)); Text(it, fontSize = 13.sp, color = if (it.startsWith("Não")) c.accent else c.ok) }
        }
        if (st.userScripts.isEmpty()) item { EmptyState(Icons.Rounded.Code, "Nenhum script", "Crie com IA, cole um userscript ou importe um arquivo .js.") }
        items(st.userScripts.sortedByDescending { it.installedAt }, key = { it.id }) { u ->
            CueCard(onClick = { editing = u }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(u.name + if (u.version.isNotBlank()) "  v${u.version}" else "", fontWeight = FontWeight.SemiBold, color = c.ink)
                        Text((u.matches + u.includes).ifEmpty { u.domains }.joinToString(), fontSize = 12.sp, color = c.ink3, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Switch(u.enabled, { on -> app.store.editUserScript(u.id) { it.copy(enabled = on, consecutiveErrors = if (on) 0 else it.consecutiveErrors) } })
                }
                if (u.description.isNotBlank()) Text(u.description, fontSize = 12.sp, color = c.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("Execuções ${u.runs} · erros ${u.errors} · última ${ago(u.lastRunAt)} · " + when (u.source) { "ia" -> "criado com IA"; "arquivo" -> "importado"; else -> "colado" },
                    fontSize = 12.sp, color = c.ink2)
                if (u.lastError.isNotBlank()) Text("Último erro: ${u.lastError}", fontSize = 12.sp, color = c.accent)
                if (!u.enabled && u.consecutiveErrors >= BrowserPolicies.AUTO_DISABLE_AFTER_ERRORS) Text("Desligado automaticamente após erros seguidos.", fontSize = 12.sp, color = c.accent)
            }
        }
        item { Spacer(Modifier.height(40.dp)) }
    }
    if (menu) AlertDialog(onDismissRequest = { menu = false }, title = { Text("Novo User Script") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CueCard(onClick = { menu = false; aiDialog = true }) {
                    Text("✨ Criar com IA", fontWeight = FontWeight.SemiBold); Text("Descreva o que quer; a IA cria e testa no browser e mostra o card de instalar.", fontSize = 12.sp, color = c.ink2)
                }
                CueCard(onClick = { menu = false; pasting = true }) {
                    Text("📋 Colar script", fontWeight = FontWeight.SemiBold); Text("Cole um userscript completo (com cabeçalho ==UserScript==).", fontSize = 12.sp, color = c.ink2)
                }
                CueCard(onClick = { menu = false; picker.launch(arrayOf("*/*")) }) {
                    Text("📂 Importar arquivo .js", fontWeight = FontWeight.SemiBold); Text("Escolha um arquivo .user.js / .js do aparelho.", fontSize = 12.sp, color = c.ink2)
                }
            }
        },
        confirmButton = { TextButton({ menu = false }) { Text("Fechar") } })
    if (pasting) PasteScriptDialog(onDismiss = { pasting = false }) { code ->
        scope.launch {
            val r = dev.cueopen.app.browser.UserScriptInstaller.install(code, "manual")
            if (r.script != null) { pasting = false; notice = "${if (r.updated) "Atualizado" else "Instalado"}: ${r.script.name} ${r.script.version}" }
            else notice = "Não instalado: ${r.error}"
        }
    }
    if (aiDialog) {
        var what by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { aiDialog = false }, title = { Text("Criar script com IA") },
            text = { OutlinedTextField(what, { what = it }, minLines = 3, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Ex.: na Biblioteca de Anúncios do Facebook, mostrar há quantos dias cada anúncio está ativo") }) },
            confirmButton = { TextButton(enabled = what.isNotBlank(), onClick = {
                aiDialog = false
                val sess = dev.cueopen.app.data.Session(title = "Script: " + what.trim().take(40), scriptBuilder = true)
                app.store.upsertSession(sess)
                app.agent.send(sess.id, "Crie, teste no browser e proponha um userscript (Tampermonkey, javascript/DOM) para: " + what.trim())
                nav.navigate("chat/${sess.id}")
            }) { Text("Criar") } },
            dismissButton = { TextButton({ aiDialog = false }) { Text("Cancelar") } })
    }
    editing?.let { e -> ScriptEditDialog(e, st.agents, onDismiss = { editing = null }, onDelete = { app.store.deleteUserScript(e.id); editing = null }) { code, agentIds ->
        scope.launch {
            val r = dev.cueopen.app.browser.UserScriptInstaller.install(code, e.source)
            if (r.script == null) notice = "Não salvo: ${r.error}"
            else {
                // Mesmo @name/@namespace: atualiza o próprio registro; se o nome mudou, o antigo sai.
                if (r.script.id != e.id) app.store.deleteUserScript(e.id)
                app.store.editUserScript(r.script.id) { it.copy(agentIds = agentIds) }
                editing = null; notice = "Salvo: ${r.script.name} ${r.script.version}"
            }
        }
    } }
}

@Composable
private fun PasteScriptDialog(onDismiss: () -> Unit, onInstall: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    val (meta, err) = remember(code) { if (code.isBlank()) null to null else dev.cueopen.app.browser.UserScriptMeta.parse(code) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Colar userscript") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(code, { code = it }, minLines = 8, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(dev.cueopen.app.browser.UserScriptMeta.EXAMPLE + "\n\n// seu código…", fontFamily = FontFamily.Monospace, fontSize = 11.sp) },
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp))
                when {
                    err != null -> Text("Formato inválido: $err", color = Cue.c.accent, fontSize = 12.sp)
                    meta != null -> Text("✓ ${meta.name} ${meta.version} · roda em ${meta.whereLabel}" +
                        if (meta.allSites) " (TODOS os sites)" else "", color = Cue.c.ok, fontSize = 12.sp)
                }
            }
        },
        confirmButton = { TextButton({ onInstall(code) }, enabled = meta != null) { Text("Instalar") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancelar") } })
}

@Composable
private fun ScriptEditDialog(existing: UserScript, agents: List<AgentProfile>, onDismiss: () -> Unit, onDelete: () -> Unit, onSave: (String, List<String>) -> Unit) {
    var code by remember { mutableStateOf(existing.code) }
    var agentIds by remember { mutableStateOf(existing.agentIds) }
    // Scripts antigos (antes do cabeçalho) ganham um cabeçalho gerado a partir dos domínios.
    LaunchedEffect(existing.id) {
        if (dev.cueopen.app.browser.UserScriptMeta.parse(code).first == null && existing.domains.isNotEmpty())
            code = "// ==UserScript==\n// @name         ${existing.name}\n// @version      1.0\n" +
                existing.domains.joinToString("") { d -> "// @match        *://${if (d.startsWith("*.")) d else "*.$d"}/*\n// @match        *://${d.removePrefix("*.")}/*\n" } +
                "// ==/UserScript==\n" + existing.code
    }
    val (meta, err) = remember(code) { dev.cueopen.app.browser.UserScriptMeta.parse(code) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(existing.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(code, { code = it }, minLines = 10, modifier = Modifier.fillMaxWidth(),
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp))
                if (err != null) Text("Formato inválido: $err", color = Cue.c.accent, fontSize = 12.sp)
                else if (meta != null) Text("${meta.name} ${meta.version} · ${meta.whereLabel} · ${meta.runAt}", fontSize = 12.sp, color = Cue.c.ink2)
                AgentPicker(agents, agentIds) { agentIds = it }
                existing.history.takeIf { it.isNotEmpty() }?.let { h ->
                    Text("Histórico:", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    h.asReversed().forEach { Text(it, fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
                }
                TextButton(onDelete) { Text("Excluir script", color = Cue.c.accent) }
            }
        },
        confirmButton = { TextButton({ onSave(code, agentIds) }, enabled = meta != null) { Text("Salvar") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancelar") } })
}

@Composable
private fun BrowserSettingsTab() {
    val app = CueApp.instance
    val st by app.store.state.collectAsStateWithLifecycle()
    val c = Cue.c
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var confirm by remember { mutableStateOf(false) }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel("Global (vale para todos os agentes)")
        CueCard {
            Text("Perfil de contas compartilhado", fontWeight = FontWeight.SemiBold, color = c.ink)
            Text("Cookies e logins ficam no armazenamento do próprio WebView (Chromium), não no arquivo do app. ${st.browserLogins.count { it.status == "active" }} sessão(ões) ativa(s), ${st.userScripts.count { it.enabled }} script(s) ligado(s).",
                fontSize = 12.sp, color = c.ink2)
        }
        SectionLabel("Individual (por agente)")
        CueCard {
            Text("Cada agente tem abas, histórico e navegação próprios. Para ver ou controlar o browser de um agente, abra a aba Browsers ou o Computador do chat.",
                fontSize = 12.sp, color = c.ink2)
        }
        SectionLabel("Limitações reais")
        CueCard {
            Text("• O WebView não isola cookies por agente: o controle de quem usa cada sessão é feito antes da navegação (o agente não autorizado é impedido de abrir o site).\n" +
                "• User Scripts capturam erros síncronos; erros em código assíncrono não aparecem no histórico.\n" +
                "• Revogar expira os cookies visíveis do domínio e apaga o armazenamento local dele; alguns sites guardam sessão em outros domínios.",
                fontSize = 12.sp, color = c.ink2, lineHeight = 17.sp)
        }
        PillButton("Sair de todas as contas", Icons.Rounded.Logout, primary = false) { confirm = true }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false },
        title = { Text("Sair de todas as contas?") },
        text = { Text("Apaga cookies e armazenamento do perfil de contas. Todas as sessões salvas ficam expiradas.") },
        confirmButton = { TextButton({
            confirm = false
            BrowserHost.signOutEverywhere(ctx)
            st.browserLogins.forEach { l -> app.store.editBrowserLogin(l.id) { it.copy(status = "expired") } }
        }) { Text("Sair de todas") } },
        dismissButton = { TextButton({ confirm = false }) { Text("Cancelar") } })
}

/**
 * Web API Hub: scrapers que viram API JSON para os agentes (webapi_call). Rodam num WebView à parte, com a sessão e o proxy do browser.
 * Nativas vêm com o app; novas são criadas pela IA (conversa de criação) e testadas antes de salvar.
 */
@Composable
private fun WebApiHubTab(nav: NavController) {
    val c = Cue.c
    val app = CueApp.instance
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val st by app.store.state.collectAsStateWithLifecycle()
    val apis = remember(st.webApis) { dev.cueopen.app.browser.WebApiHub.all(ctx) }
    val scope = rememberCoroutineScope()
    var aiDialog by remember { mutableStateOf(false) }
    var trying by remember { mutableStateOf<dev.cueopen.app.data.WebApi?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("APIs prontas sobre sites: os agentes chamam com webapi_call e recebem JSON (com paginação). Rodam num navegador separado, com os mesmos logins e o mesmo proxy do Browser.",
                fontSize = 13.sp, color = c.ink2)
            Spacer(Modifier.height(8.dp))
            Button({ aiDialog = true }) { Icon(Icons.Rounded.AutoAwesome, null); Spacer(Modifier.width(6.dp)); Text("Criar API com IA") }
        }
        items(apis, key = { it.slug }) { a ->
            Surface(shape = RoundedCornerShape(16.dp), color = c.surface2, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(a.name, fontWeight = FontWeight.SemiBold)
                            Text(a.slug + (if (a.builtin) " · nativa" else " · " + a.source), fontSize = 11.sp, color = c.ink3, fontFamily = FontFamily.Monospace)
                        }
                        Switch(a.enabled, { on ->
                            val saved = app.store.state.value.webApis.firstOrNull { it.slug == a.slug } ?: a
                            app.store.upsertWebApi(saved.copy(enabled = on))
                        })
                    }
                    Text(a.description, fontSize = 12.sp, color = c.ink2)
                    Text("args: " + a.params.joinToString { it.name + (if (it.required) "*" else "") }, fontSize = 11.sp, color = c.ink3)
                    if (a.runs > 0) Text("${a.runs} execuções · ${a.errors} erros" + (if (a.lastError.isNotBlank()) " · último: " + a.lastError.take(80) else ""),
                        fontSize = 11.sp, color = if (a.lastError.isNotBlank()) c.err else c.ink3)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton({ trying = a }) { Text("Testar") }
                        TextButton({
                            val sess = dev.cueopen.app.data.Session(title = "API: " + a.slug, webApiBuilder = true)
                            app.store.upsertSession(sess)
                            app.agent.send(sess.id, "Melhore/corrija a Web API \"${a.slug}\" do hub (salve com o mesmo slug). Template atual: ${a.urlTemplate}\nCódigo atual:\n${a.code}")
                            nav.navigate("chat/${sess.id}")
                        }) { Text("Ajustar com IA") }
                        if (!a.builtin || app.store.state.value.webApis.any { it.slug == a.slug && !it.builtin })
                            TextButton({ app.store.deleteWebApi(a.slug) }) { Text(if (a.builtin) "Restaurar" else "Remover", color = c.err) }
                    }
                }
            }
        }
    }
    if (aiDialog) {
        var what by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { aiDialog = false }, title = { Text("Criar Web API com IA") },
            text = { OutlinedTextField(what, { what = it }, minLines = 3, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Ex.: API que busca produtos no Mercado Livre e devolve título, preço, frete e vendedor, com paginação") }) },
            confirmButton = { TextButton(enabled = what.isNotBlank(), onClick = {
                aiDialog = false
                val sess = dev.cueopen.app.data.Session(title = "API: " + what.trim().take(40), webApiBuilder = true)
                app.store.upsertSession(sess)
                app.agent.send(sess.id, "Crie, teste e salve no hub uma Web API (scraper via WebView que devolve JSON) para: " + what.trim())
                nav.navigate("chat/${sess.id}")
            }) { Text("Criar") } },
            dismissButton = { TextButton({ aiDialog = false }) { Text("Cancelar") } })
    }
    trying?.let { a ->
        var args by remember(a.slug) { mutableStateOf(kotlinx.serialization.json.buildJsonObject {
            a.params.forEach { p -> put(p.name, kotlinx.serialization.json.JsonPrimitive(p.default.ifBlank { if (p.name == "query") "notebook" else "" })) }
        }.toString()) }
        var out by remember(a.slug) { mutableStateOf("") }
        var busy by remember(a.slug) { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { trying = null }, title = { Text("Testar " + a.slug) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(args, { args = it }, label = { Text("args (JSON)") }, modifier = Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                if (out.isNotBlank()) Text(out.take(6_000), fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 8.dp))
            } },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                val o = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(args) as kotlinx.serialization.json.JsonObject }.getOrNull()
                if (o == null) { out = "args não é um objeto JSON"; return@TextButton }
                busy = true
                scope.launch {
                    val r = dev.cueopen.app.browser.WebApiHub.call(ctx, a, o)
                    out = runCatching { kotlinx.serialization.json.Json { prettyPrint = true }.let { j -> j.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), j.parseToJsonElement(r)) } }.getOrDefault(r)
                    busy = false
                }
            }) { Text("Executar") } },
            dismissButton = { TextButton({ trying = null }) { Text("Fechar") } })
    }
}
