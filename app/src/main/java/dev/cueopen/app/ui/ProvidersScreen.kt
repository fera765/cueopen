package dev.cueopen.app.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.cueopen.app.CueApp
import dev.cueopen.app.data.ProviderModels
import dev.cueopen.app.data.Settings
import dev.cueopen.app.llm.KeyPool
import dev.cueopen.app.llm.ModelFallback
import dev.cueopen.app.llm.ProviderCatalog
import dev.cueopen.app.llm.ProviderRoute
import dev.cueopen.app.ui.theme.Cue
import kotlinx.coroutines.launch

/** Credenciais de um provedor: (chaves — uma por linha —, Base URL). O ativo fica nos campos principais de Settings. */
fun providerCreds(s: Settings, id: String): Pair<String, String> =
    if (ProviderCatalog.activeId(s) == id) s.apiKey to s.baseUrl
    else s.savedProviders[id].orEmpty().let { it.getOrNull(0).orEmpty() to (it.getOrNull(1)?.takeIf { b -> b.isNotBlank() } ?: ProviderCatalog.byId(id)?.baseUrl.orEmpty()) }

/** Provedor conectado = tem chave (ou é local e não precisa). */
fun providerConnected(s: Settings, id: String): Boolean {
    val p = ProviderCatalog.byId(id)
    val (key, base) = providerCreds(s, id)
    return if (p != null && !p.needsKey) ProviderCatalog.activeId(s) == id || s.savedProviders.containsKey(id) else key.isNotBlank() && base.isNotBlank()
}

/** Liga o provedor [target] (null = personalizado): guarda o atual e restaura chave, Base URL e modelos do alvo. */
fun switchProvider(s: Settings, target: ProviderCatalog.Preset?, customBaseUrl: String = ""): Settings {
    val fromKey = ProviderCatalog.activeId(s)
    val toKey = target?.id ?: "custom"
    if (fromKey == toKey) return s.copy(activeProvider = toKey)
    val saved = s.savedProviders + (fromKey to listOf(s.apiKey, s.baseUrl, s.model, s.fastModel, s.fallbackModel))
    val r = saved[toKey]
    val base = r?.getOrNull(1)?.takeIf { it.isNotBlank() } ?: target?.baseUrl ?: customBaseUrl
    return s.copy(savedProviders = saved, activeProvider = toKey, apiKey = r?.getOrNull(0).orEmpty(), baseUrl = base,
        model = r?.getOrNull(2)?.ifBlank { null } ?: target?.defaultModel.orEmpty(),
        fastModel = r?.getOrNull(3)?.ifBlank { null } ?: target?.defaultModel.orEmpty(),
        fallbackModel = r?.getOrNull(4).orEmpty())
}

/** Salva chave(s) e Base URL de um provedor, ativo ou não. */
fun saveProviderCreds(s: Settings, id: String, keys: String, baseUrl: String): Settings {
    val k = KeyPool.keys(keys).joinToString("\n")
    return if (ProviderCatalog.activeId(s) == id) s.copy(apiKey = k, baseUrl = baseUrl.trim())
    else {
        val cur = s.savedProviders[id].orEmpty()
        s.copy(savedProviders = s.savedProviders + (id to listOf(k, baseUrl.trim(), cur.getOrNull(2).orEmpty(), cur.getOrNull(3).orEmpty(), cur.getOrNull(4).orEmpty())))
    }
}

/** Compat: salvar só a chave (usado pelo login OAuth). */
fun saveProviderKey(s: Settings, p: ProviderCatalog.Preset, key: String): Settings = saveProviderCreds(s, p.id, key, providerCreds(s, p.id).second.ifBlank { p.baseUrl })

/** Modelo do agente guardado num provedor (ativo = campos principais). */
fun saveProviderModel(s: Settings, id: String, model: String): Settings =
    if (ProviderCatalog.activeId(s) == id) s.copy(model = model)
    else {
        val cur = s.savedProviders[id].orEmpty()
        s.copy(savedProviders = s.savedProviders + (id to listOf(cur.getOrNull(0).orEmpty(), cur.getOrNull(1)?.ifBlank { null } ?: ProviderCatalog.byId(id)?.baseUrl.orEmpty(),
            model, cur.getOrNull(3).orEmpty(), cur.getOrNull(4).orEmpty())))
    }

private fun providerName(id: String) = ProviderCatalog.byId(id)?.name ?: "Personalizado"
private fun allIds() = ProviderCatalog.ordered.map { it.id } + "custom"

@Composable
fun ProvidersScreen(nav: NavController) {
    val app = CueApp.instance
    val st by app.store.state.collectAsStateWithLifecycle()
    val s = st.settings
    val c = Cue.c
    val activeId = ProviderCatalog.activeId(s)
    val connected = allIds().filter { it == activeId || providerConnected(s, it) }
    Scaffold(containerColor = c.bg, topBar = { CueTopBar("Provedores e modelos", { nav.popBackStack() }) }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // ---------- Provedor ativo: tocar já ativa
            SectionLabel("Provedor ativo")
            Row(Modifier.horizontalScrollSafe(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                connected.forEach { id ->
                    FilterChip(id == activeId, { if (id != activeId) app.store.updateSettings { switchProvider(it, ProviderCatalog.byId(id)) } },
                        label = { Text(providerName(id), fontSize = 13.sp) })
                }
            }
            val activeKey = KeyPool.current(s.apiKey)
            ModelPicker(s.model, { v -> app.store.updateSettings { it.copy(model = v) } }, "Modelo do agente · ${providerName(activeId)}", baseUrl = s.baseUrl, apiKey = activeKey)
            ModelPicker(s.fastModel, { v -> app.store.updateSettings { it.copy(fastModel = v) } }, "Modelo rápido (títulos e resumos de contexto)", baseUrl = s.baseUrl, apiKey = activeKey)
            ProviderChainEditor(s, connected.filter { it != activeId })
            HorizontalDivider(color = c.line)

            // ---------- Conectar
            SectionLabel("Conectar provedores")
            Text("Regra: quando o provedor oferece login (OAuth), conecte por ele. Os demais usam chave de API — várias chaves (uma por linha) trocam de conta sozinhas no limite. " +
                "Tocar num provedor conectado já o ativa. Planos gratuitos têm limites que mudam.", fontSize = 12.sp, color = c.ink3)
            ProviderCatalog.ordered.forEach { p -> ProviderCard(s, p, activeId == p.id) }
            ProviderCard(s, null, activeId == "custom")
        }
    }
}

@Composable
private fun ProviderChainEditor(s: Settings, others: List<String>) {
    val app = CueApp.instance
    val c = Cue.c
    Text("Provedores alternativos", fontWeight = FontWeight.SemiBold)
    Text("Se o ativo bater o limite ou falhar 3 vezes, o agente passa ao próximo marcado (com o modelo escolhido nele). O ativo é testado de novo em alguns minutos e reassume quando volta.",
        fontSize = 12.sp, color = c.ink3)
    if (others.isEmpty()) Text("Conecte outro provedor abaixo para usá-lo como alternativo.", fontSize = 12.sp, color = c.ink2)
    val order = s.fallbackProviders.filter { it in others } + others.filter { it !in s.fallbackProviders }
    order.forEach { id ->
        val on = id in s.fallbackProviders
        val pos = s.fallbackProviders.filter { it in others }.indexOf(id)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(on, { v -> app.store.updateSettings { cur -> cur.copy(fallbackProviders = if (v) cur.fallbackProviders + id else cur.fallbackProviders - id) } })
            Column(Modifier.weight(1f)) {
                Text((if (on) "${pos + 1}. " else "") + providerName(id), fontSize = 14.sp)
                val m = dev.cueopen.app.llm.ProviderChain.modelOf(s, id)
                Text(if (m.isBlank()) "Sem modelo: escolha no card do provedor" else "Modelo: $m", fontSize = 12.sp, color = if (m.isBlank()) c.err else c.ink3)
            }
            if (on && pos > 0) IconButton({ app.store.updateSettings { cur ->
                val l = cur.fallbackProviders.filter { it in others }.toMutableList(); l.add(pos - 1, l.removeAt(pos))
                cur.copy(fallbackProviders = l + cur.fallbackProviders.filter { it !in others })
            } }) { Icon(Icons.Rounded.ArrowUpward, "Subir", tint = c.ink2) }
        }
    }
    val legacy = s.fallbackModel.split(',').map { it.trim() }.filter { it.isNotBlank() }
    legacy.forEach { m ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Modelo alternativo: " + ProviderRoute.label(m), Modifier.weight(1f), fontSize = 13.sp, color = c.ink2)
            IconButton({ app.store.updateSettings { it.copy(fallbackModel = (legacy - m).joinToString(",")) } }) { Icon(Icons.Rounded.Close, "Remover", tint = c.ink2) }
        }
    }
}

@Composable
private fun Modifier.horizontalScrollSafe(): Modifier = this.horizontalScroll(rememberScrollState())

@Composable
private fun ProviderCard(s: Settings, p: ProviderCatalog.Preset?, active: Boolean) {
    val app = CueApp.instance
    val c = Cue.c
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val id = p?.id ?: "custom"
    val (storedKeys, storedBase) = providerCreds(s, id)
    val connected = active || providerConnected(s, id)
    var open by remember(id, active) { mutableStateOf(active) }
    var keys by remember(id, storedKeys) { mutableStateOf(storedKeys) }
    var base by remember(id, storedBase) { mutableStateOf(storedBase.ifBlank { p?.baseUrl.orEmpty() }) }
    var clientId by remember(id) { mutableStateOf(s.oauthClients[id].orEmpty()) }
    var showKeyField by remember(id) { mutableStateOf(p?.oauth != true || storedKeys.isNotBlank()) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    val dirty = keys.trim() != storedKeys.trim() || base.trim() != storedBase.trim()
    fun activate() = app.store.updateSettings { cur -> if (ProviderCatalog.activeId(cur) == id) cur else switchProvider(cur, p, base) }
    fun openUrl(url: String) = runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
    Column(Modifier.fillMaxWidth().border(1.dp, if (active) c.accent else c.line, RoundedCornerShape(16.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // Tocar no cabeçalho: conectado → ativa; não conectado → abre para conectar.
        Row(Modifier.fillMaxWidth().clickable { if (connected) { activate(); open = true } else open = !open }, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text((p?.name ?: "Personalizado") + (if (p?.oauth == true) " · login" else ""), fontWeight = FontWeight.SemiBold)
                val accounts = KeyPool.describe(storedKeys).let { if (it.isBlank()) "" else " · $it" }
                val status = when { active -> "Ativo$accounts"; connected -> "Conectado$accounts · toque para ativar"; else -> "Não conectado" }
                Text(status + " · " + (p?.free ?: "Qualquer endpoint OpenAI-compatible (LiteLLM, OmniRoute, one-api…)"), fontSize = 12.sp, color = c.ink3)
            }
            if (active) Icon(Icons.Rounded.CheckCircle, "Ativo", tint = c.ok)
            else IconButton({ open = !open }) { Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, "Detalhes", tint = c.ink2) }
        }
        if (!open) return@Column
        // ---- Login (OAuth): PKCE do OpenRouter ou login por código
        if (p?.oauth == true && p.device == null) {
            Button(enabled = !busy, onClick = {
                busy = true; msg = "Abrindo o login…"
                scope.launch {
                    runCatching { dev.cueopen.app.llm.OpenRouterOAuth.login(ctx) }
                        .onSuccess { k -> app.store.updateSettings { cur -> saveProviderKey(cur, p, k).let { if (ProviderCatalog.activeId(it) == p.id) it else switchProvider(it, p) } }; keys = k; msg = "Conectado e ativado." }
                        .onFailure { msg = "Falhou: " + (it.message ?: "login interrompido") }
                    busy = false
                }
            }, colors = ButtonDefaults.buttonColors(containerColor = c.ink, contentColor = c.bg), modifier = Modifier.fillMaxWidth()) {
                Text(if (connected) "Entrar de novo com ${p.name}" else "Entrar com ${p.name}")
            }
        }
        p?.device?.let { auth ->
            Text("Login por código: ${auth.howTo}", fontSize = 12.sp, color = c.ink3)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { CueField(clientId, { clientId = it.trim() }, "Client ID do seu app OAuth (público)") }
                TextButton({ openUrl(auth.registerUrl) }) { Text("Criar app", color = c.ink2, fontSize = 13.sp) }
            }
            Button(enabled = !busy && clientId.isNotBlank(), onClick = {
                busy = true; msg = "Pedindo o código…"
                scope.launch {
                    runCatching {
                        val start = dev.cueopen.app.llm.DeviceFlow.start(auth, clientId)
                        (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                            .setPrimaryClip(android.content.ClipData.newPlainText("código", start.userCode))
                        msg = "Código ${start.userCode} (copiado). Cole no site que abriu e confirme; o app espera aqui."
                        openUrl(start.verificationUri)
                        dev.cueopen.app.llm.DeviceFlow.poll(auth, clientId, start)
                    }.onSuccess { tokens ->
                        app.store.updateSettings { cur ->
                            dev.cueopen.app.llm.OAuthRefresh.saveTokens(cur.copy(oauthClients = cur.oauthClients + (p.id to clientId)), p.id, tokens)
                                .let { if (ProviderCatalog.activeId(it) == p.id) it else switchProvider(it, p) }
                        }
                        keys = tokens.accessToken; msg = "Conectado e ativado pelo login."
                    }.onFailure { msg = "Falhou: " + (it.message ?: "login interrompido") }
                    busy = false
                }
            }, colors = ButtonDefaults.buttonColors(containerColor = c.ink, contentColor = c.bg), modifier = Modifier.fillMaxWidth()) {
                Text(if (connected) "Entrar de novo com ${p.name}" else "Entrar com ${p.name}")
            }
        }
        if (p?.oauth == true && !showKeyField) TextButton({ showKeyField = true }) { Text("Usar chave de API em vez do login", color = c.ink2, fontSize = 13.sp) }
        // ---- Chave / Base URL
        if (p == null || !p.needsKey) CueField(base, { base = it }, if (p == null) "Base URL — ex.: https://api.exemplo.com/v1" else "Base URL (troque 127.0.0.1 pelo IP do computador)")
        if ((p == null || p.needsKey) && showKeyField)
            CueField(keys, { keys = it }, "Chave da API (várias contas: uma por linha)", password = KeyPool.keys(keys).size <= 1, minLines = if (KeyPool.keys(keys).size > 1) 2 else 1)
        // ---- Modelo deste provedor (o do ativo fica no topo)
        if (connected && !active) {
            val (k, b) = providerCreds(s, id)
            ModelPicker(dev.cueopen.app.llm.ProviderChain.modelOf(s, id), { v -> app.store.updateSettings { saveProviderModel(it, id, v) } },
                "Modelo deste provedor (ao ativar ou como alternativo)", baseUrl = b, apiKey = KeyPool.current(k))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            if (p != null && p.needsKey) TextButton({ openUrl(p.keyUrl) }) { Text("Obter chave", color = c.ink2, fontSize = 13.sp) }
            TextButton(enabled = !busy && base.isNotBlank(), onClick = {
                busy = true; msg = "Testando…"
                scope.launch {
                    msg = runCatching { ProviderModels.all(base.trim(), KeyPool.current(keys), forceRefresh = true) }
                        .fold({ list -> "Conexão OK · ${list.size} modelos" + list.count { ProviderModels.isFree(it) }.let { n -> if (n > 0) " ($n gratuitos)" else "" } },
                              { e -> "Falhou: ${e.message ?: "sem resposta"}" })
                    busy = false
                }
            }) { Text("Testar", color = c.ink2, fontSize = 13.sp) }
            Spacer(Modifier.weight(1f))
            if (dirty) TextButton({ app.store.updateSettings { saveProviderCreds(it, id, keys, base) }; msg = "Salvo." }) { Text("Salvar", color = c.accent, fontSize = 13.sp) }
            if (!active) TextButton({ app.store.updateSettings { cur -> switchProvider(saveProviderCreds(cur, id, keys, base), p, base) } }) { Text("Ativar", color = c.accent, fontSize = 13.sp) }
        }
        msg?.let { Text(it, fontSize = 12.sp, color = if (it.startsWith("Falhou")) c.err else c.ink2) }
    }
}
