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
    val tabs = listOf("Browsers", "Sessões", "User Scripts", "Configurações")
    Scaffold(containerColor = c.bg, topBar = { CueTopBar("Browser", { nav.popBackStack() }) }) { pad ->
        Column(Modifier.padding(pad)) {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tabs.forEachIndexed { i, t -> Chip(t, selected = tab == i) { tab = i } }
            }
            when (tab) {
                0 -> AgentBrowsersTab(nav)
                1 -> LoginsTab(nav)
                2 -> UserScriptsTab()
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
            PillButton("Adicionar sessão", Icons.Rounded.Add) { adding = true }
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
fun BrowserLoginScreen(nav: NavController, loginId: String) {
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
private fun UserScriptsTab() {
    val app = CueApp.instance
    val st by app.store.state.collectAsStateWithLifecycle()
    val c = Cue.c
    var editing by remember { mutableStateOf<UserScript?>(null) }
    var creating by remember { mutableStateOf(false) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Scripts rodam sozinhos nos browsers dos agentes, só nos domínios que você definir (nunca em todos os sites). Rodam uma vez por página; 3 erros seguidos desligam o script.",
                fontSize = 13.sp, color = c.ink2, lineHeight = 18.sp)
            Spacer(Modifier.height(10.dp))
            PillButton("Novo script", Icons.Rounded.Add) { creating = true }
        }
        if (st.userScripts.isEmpty()) item { EmptyState(Icons.Rounded.Code, "Nenhum script", "Ex.: esconder anúncios ou banners de cookies num site específico.") }
        items(st.userScripts.sortedByDescending { it.priority }, key = { it.id }) { u ->
            CueCard(onClick = { editing = u }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(u.name, fontWeight = FontWeight.SemiBold, color = c.ink)
                        Text(u.domains.joinToString(), fontSize = 12.sp, color = c.ink3, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Switch(u.enabled, { on -> app.store.editUserScript(u.id) { it.copy(enabled = on, consecutiveErrors = if (on) 0 else it.consecutiveErrors) } })
                }
                if (u.description.isNotBlank()) Text(u.description, fontSize = 12.sp, color = c.ink2)
                Text("Execuções ${u.runs} · erros ${u.errors} · prioridade ${u.priority} · última ${ago(u.lastRunAt)}", fontSize = 12.sp, color = c.ink2)
                if (u.lastError.isNotBlank()) Text("Último erro: ${u.lastError}", fontSize = 12.sp, color = c.accent)
                if (!u.enabled && u.consecutiveErrors >= BrowserPolicies.AUTO_DISABLE_AFTER_ERRORS) Text("Desligado automaticamente após erros seguidos.", fontSize = 12.sp, color = c.accent)
            }
        }
        item { Spacer(Modifier.height(40.dp)) }
    }
    if (creating) ScriptEditDialog(null, st.agents, onDismiss = { creating = false }) { creating = false; app.store.upsertUserScript(it) }
    editing?.let { e -> ScriptEditDialog(e, st.agents, onDismiss = { editing = null }, onDelete = { app.store.deleteUserScript(e.id); editing = null }) { editing = null; app.store.upsertUserScript(it) } }
}

@Composable
private fun ScriptEditDialog(existing: UserScript?, agents: List<AgentProfile>, onDismiss: () -> Unit, onDelete: (() -> Unit)? = null, onSave: (UserScript) -> Unit) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var desc by remember { mutableStateOf(existing?.description.orEmpty()) }
    var domains by remember { mutableStateOf(existing?.domains?.joinToString(", ").orEmpty()) }
    var path by remember { mutableStateOf(existing?.pathContains.orEmpty()) }
    var priority by remember { mutableStateOf((existing?.priority ?: 0).toString()) }
    var code by remember { mutableStateOf(existing?.code ?: "document.querySelectorAll('[aria-label=\"Patrocinado\"]').forEach(e => e.remove());") }
    var agentIds by remember { mutableStateOf(existing?.agentIds.orEmpty()) }
    val domainList = domains.split(',', '\n', ' ').map { it.trim() }.filter { it.isNotEmpty() }
    val error = BrowserPolicies.validateScript(name, code, domainList)
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Novo User Script" else "Editar script") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Nome") }, singleLine = true)
                OutlinedTextField(desc, { desc = it }, label = { Text("Descrição") })
                OutlinedTextField(domains, { domains = it }, label = { Text("Domínios (ex.: facebook.com, *.exemplo.com)") })
                OutlinedTextField(path, { path = it }, label = { Text("Só se a URL contiver (opcional)") }, singleLine = true)
                OutlinedTextField(priority, { priority = it.filter { ch -> ch.isDigit() || ch == '-' }.take(4) }, label = { Text("Prioridade (maior roda primeiro)") }, singleLine = true)
                OutlinedTextField(code, { code = it }, label = { Text("JavaScript") }, minLines = 6,
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp))
                AgentPicker(agents, agentIds) { agentIds = it }
                if (error != null) Text(error, color = Cue.c.accent, fontSize = 12.sp)
                existing?.history?.takeIf { it.isNotEmpty() }?.let { h ->
                    Text("Histórico:", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    h.asReversed().forEach { Text(it, fontSize = 11.sp, fontFamily = FontFamily.Monospace) }
                }
                if (onDelete != null) TextButton(onDelete) { Text("Excluir script", color = Cue.c.accent) }
            }
        },
        confirmButton = {
            TextButton({
                val base = existing ?: UserScript(name = name, code = code)
                onSave(base.copy(name = name.trim(), description = desc.trim(), code = code,
                    domains = domainList.map { BrowserPolicies.normalizeDomain(it) }.distinct(), pathContains = path.trim(),
                    priority = priority.toIntOrNull() ?: 0, agentIds = agentIds,
                    // Editou: zera a sequência de erros para dar nova chance.
                    consecutiveErrors = 0, enabled = existing?.enabled ?: true))
            }, enabled = error == null) { Text("Salvar") }
        },
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
