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

@Composable
fun ProvidersScreen(nav: NavController) {
    val app = CueApp.instance
    val st by app.store.state.collectAsStateWithLifecycle()
    val s = st.settings
    val c = Cue.c
    val activeId = ProviderCatalog.activeId(s)
    val activeName = ProviderCatalog.byId(activeId)?.name ?: "Personalizado"
    Scaffold(containerColor = c.bg, topBar = { CueTopBar("Provedores e modelos", { nav.popBackStack() }) }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // ---------- Em uso
            SectionLabel("Em uso · $activeName")
            val activeKey = KeyPool.current(s.apiKey)
            ModelPicker(s.model, { v -> app.store.updateSettings { it.copy(model = v) } }, "Modelo do agente", baseUrl = s.baseUrl, apiKey = activeKey)
            ModelPicker(s.fastModel, { v -> app.store.updateSettings { it.copy(fastModel = v) } }, "Modelo rápido (títulos e resumos de contexto)", baseUrl = s.baseUrl, apiKey = activeKey)
            FallbackChainEditor(s)
            HorizontalDivider(color = c.line)

            // ---------- Conectar
            SectionLabel("Conectar provedores")
            Text("Regra: quando o provedor oferece login (OAuth), conecte por ele — a chave é criada na sua conta sem copiar nada. " +
                "Os demais conectam com chave de API; coloque várias chaves (uma por linha) para trocar de conta sozinho quando uma bater o limite. " +
                "Planos gratuitos têm limites que mudam: confira no site do provedor.", fontSize = 12.sp, color = c.ink3)
            ProviderCatalog.ordered.forEach { p -> ProviderCard(s, p, activeId == p.id) }
            ProviderCard(s, null, activeId == "custom")
        }
    }
}

@Composable
private fun FallbackChainEditor(s: Settings) {
    val app = CueApp.instance
    val c = Cue.c
    val chain = ModelFallback.chain(s.model, s.fallbackModel).drop(1)
    fun save(list: List<String>) = app.store.updateSettings { it.copy(fallbackModel = list.joinToString(",")) }
    Text("Cadeia de modelos alternativos", fontWeight = FontWeight.SemiBold)
    Text("Quando um modelo bate o limite (429), o agente passa ao próximo da lista — pode ser de outro provedor conectado. O principal é testado de novo depois de alguns minutos.", fontSize = 12.sp, color = c.ink3)
    chain.forEachIndexed { i, m ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${i + 1}. " + ProviderRoute.label(m), Modifier.weight(1f), fontSize = 14.sp)
            if (i > 0) IconButton({ save(chain.toMutableList().also { l -> l.add(i - 1, l.removeAt(i)) }) }) { Icon(Icons.Rounded.ArrowUpward, "Subir", tint = c.ink2) }
            IconButton({ save(chain.filterIndexed { j, _ -> j != i }) }) { Icon(Icons.Rounded.Close, "Remover", tint = c.ink2) }
        }
    }
    val connected = (listOf(ProviderCatalog.activeId(s)) + ProviderCatalog.all.map { it.id } + "custom").distinct().filter { it == ProviderCatalog.activeId(s) || providerConnected(s, it) }
    var pick by remember { mutableStateOf<String?>(null) }
    Row(Modifier.horizontalScrollSafe(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        connected.forEach { id ->
            FilterChip(pick == id, { pick = if (pick == id) null else id }, label = { Text("+ " + (ProviderCatalog.byId(id)?.name ?: "Personalizado"), fontSize = 12.sp) })
        }
    }
    pick?.let { id ->
        val (keys, base) = providerCreds(s, id)
        ModelPicker("", { v ->
            if (v.isNotBlank()) save(chain + ProviderRoute.encode(if (id == ProviderCatalog.activeId(s)) "" else id, v))
            pick = null
        }, "Modelo alternativo em " + (ProviderCatalog.byId(id)?.name ?: "Personalizado"), baseUrl = base, apiKey = KeyPool.current(keys))
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
    var open by remember(id) { mutableStateOf(active) }
    var keys by remember(id, storedKeys) { mutableStateOf(storedKeys) }
    var base by remember(id, storedBase) { mutableStateOf(storedBase.ifBlank { p?.baseUrl.orEmpty() }) }
    var showKeyField by remember(id) { mutableStateOf(p?.oauth != true || storedKeys.isNotBlank()) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    val dirty = keys.trim() != storedKeys.trim() || base.trim() != storedBase.trim()
    Column(Modifier.fillMaxWidth().border(1.dp, if (active) c.accent else c.line, RoundedCornerShape(16.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text((p?.name ?: "Personalizado") + (if (p?.oauth == true) " · login" else ""), fontWeight = FontWeight.SemiBold)
                val status = when {
                    active -> "Ativo" + KeyPool.describe(storedKeys).let { if (it.isBlank()) "" else " · $it" }
                    connected -> "Conectado" + KeyPool.describe(storedKeys).let { if (it.isBlank()) "" else " · $it" }
                    else -> "Não conectado"
                }
                Text(status + " · " + (p?.free ?: "Qualquer endpoint OpenAI-compatible (LiteLLM, OmniRoute, one-api…)"), fontSize = 12.sp, color = c.ink3)
            }
            if (active) Icon(Icons.Rounded.CheckCircle, "Ativo", tint = c.ok)
            else Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = c.ink2)
        }
        if (!open) return@Column
        if (p?.oauth == true) {
            Button(enabled = !busy, onClick = {
                busy = true; msg = "Abrindo o login…"
                scope.launch {
                    runCatching { dev.cueopen.app.llm.OpenRouterOAuth.login(ctx) }
                        .onSuccess { k ->
                            app.store.updateSettings { cur -> saveProviderKey(cur, p, k).let { if (ProviderCatalog.activeId(it) == p.id) it else switchProvider(it, p) } }
                            keys = k; msg = "Conectado e ativado: a chave da sua conta foi criada e salva."
                        }
                        .onFailure { msg = it.message ?: "Falha no login." }
                    busy = false
                }
            }, colors = ButtonDefaults.buttonColors(containerColor = c.ink, contentColor = c.bg), modifier = Modifier.fillMaxWidth()) {
                Text(if (connected) "Entrar de novo com ${p.name}" else "Entrar com ${p.name}")
            }
            if (!showKeyField) TextButton({ showKeyField = true }) { Text("Usar chave de API em vez do login", color = c.ink2, fontSize = 13.sp) }
        }
        if (p == null || !p.needsKey) CueField(base, { base = it }, if (p == null) "Base URL — ex.: https://api.exemplo.com/v1" else "Base URL (troque 127.0.0.1 pelo IP do computador)")
        if ((p == null || p.needsKey) && showKeyField)
            CueField(keys, { keys = it }, "Chave da API (várias contas: uma por linha)", password = KeyPool.keys(keys).size <= 1, minLines = if (KeyPool.keys(keys).size > 1) 2 else 1)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            if (p != null && p.needsKey) TextButton({ runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(p.keyUrl)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } }) { Text("Obter chave", color = c.ink2, fontSize = 13.sp) }
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
