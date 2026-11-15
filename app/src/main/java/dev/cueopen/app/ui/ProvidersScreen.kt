package dev.cueopen.app.ui

import androidx.compose.foundation.border
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
import dev.cueopen.app.data.Settings
import dev.cueopen.app.llm.ProviderCatalog
import dev.cueopen.app.ui.theme.Cue
import kotlinx.coroutines.launch

/** Liga o provedor [target]: guarda a configuração do atual e restaura a que o [target] já tinha (chave, modelos). */
fun switchProvider(s: Settings, target: ProviderCatalog.Preset?, customBaseUrl: String = ""): Settings {
    val fromKey = ProviderCatalog.storageKey(s.baseUrl)
    val toKey = target?.id ?: "custom"
    val saved = s.savedProviders + (fromKey to listOf(s.apiKey, s.baseUrl, s.model, s.fastModel, s.fallbackModel))
    val r = saved[toKey]
    val base = target?.baseUrl ?: r?.getOrNull(1)?.takeIf { it.isNotBlank() } ?: customBaseUrl
    return s.copy(savedProviders = saved, apiKey = r?.getOrNull(0).orEmpty(), baseUrl = base,
        model = r?.getOrNull(2)?.ifBlank { null } ?: target?.defaultModel.orEmpty(),
        fastModel = r?.getOrNull(3)?.ifBlank { null } ?: target?.defaultModel.orEmpty(),
        fallbackModel = r?.getOrNull(4).orEmpty())
}

/** Guarda a chave de um provedor sem ativá-lo (ou atualiza a do ativo). */
fun saveProviderKey(s: Settings, p: ProviderCatalog.Preset, key: String): Settings =
    if (ProviderCatalog.storageKey(s.baseUrl) == p.id) s.copy(apiKey = key.trim())
    else {
        val cur = s.savedProviders[p.id].orEmpty()
        s.copy(savedProviders = s.savedProviders + (p.id to listOf(key.trim(), p.baseUrl, cur.getOrNull(2).orEmpty(), cur.getOrNull(3).orEmpty(), cur.getOrNull(4).orEmpty())))
    }

@Composable
fun ProvidersScreen(nav: NavController) {
    val app = CueApp.instance
    val st by app.store.state.collectAsStateWithLifecycle()
    val s = st.settings
    val c = Cue.c
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val activeKey = ProviderCatalog.storageKey(s.baseUrl)
    var oauthBusy by remember { mutableStateOf(false) }
    var oauthMsg by remember { mutableStateOf<String?>(null) }
    Scaffold(containerColor = c.bg, topBar = { CueTopBar("Provedores de modelos", { nav.popBackStack() }) }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Ligue quantos provedores quiser, cada um com a sua chave. Um fica ativo por vez; trocar guarda a chave e os modelos de cada um. " +
                "Os planos gratuitos têm limites que mudam — confira no site do provedor. Use o Modelo alternativo (Configurações) para pular para outro modelo quando um bater o limite.",
                fontSize = 12.sp, color = c.ink3)
            ProviderCatalog.all.forEach { p ->
                val active = activeKey == p.id
                val storedKey = if (active) s.apiKey else s.savedProviders[p.id]?.getOrNull(0).orEmpty()
                var key by remember(p.id, storedKey) { mutableStateOf(storedKey) }
                Column(Modifier.fillMaxWidth().border(1.dp, if (active) c.accent else c.line, RoundedCornerShape(16.dp)).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name + if (active) " · ativo" else "", fontWeight = FontWeight.SemiBold)
                            Text(p.free, fontSize = 12.sp, color = c.ink3)
                        }
                        if (!active) TextButton({ app.store.updateSettings { switchProvider(it, p) } }) { Text("Ativar", color = c.accent) }
                        else Icon(Icons.Rounded.CheckCircle, "Ativo", tint = c.ok)
                    }
                    if (p.needsKey) {
                        CueField(key, { key = it }, "Chave da API", password = true)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton({ runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(p.keyUrl)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } }) { Text("Obter chave", color = c.ink2, fontSize = 13.sp) }
                            if (p.oauth) TextButton(enabled = !oauthBusy, onClick = {
                                oauthBusy = true; oauthMsg = "Abrindo o login do OpenRouter…"
                                scope.launch {
                                    runCatching { dev.cueopen.app.llm.OpenRouterOAuth.login(ctx) }
                                        .onSuccess { k -> app.store.updateSettings { saveProviderKey(it, p, k) }; key = k; oauthMsg = "Conectado ao OpenRouter: chave salva." }
                                        .onFailure { oauthMsg = it.message ?: "Falha no login." }
                                    oauthBusy = false
                                }
                            }) { Text("Entrar com OpenRouter", color = c.accent, fontSize = 13.sp) }
                            Spacer(Modifier.weight(1f))
                            if (key != storedKey) TextButton({ app.store.updateSettings { saveProviderKey(it, p, key) } }) { Text("Salvar", color = c.accent, fontSize = 13.sp) }
                        }
                        if (p.oauth) oauthMsg?.let { Text(it, fontSize = 12.sp, color = c.ink2) }
                    } else Text("Sem chave: informe o IP do computador em Configurações › Base URL se ele não for este aparelho.", fontSize = 12.sp, color = c.ink3)
                }
            }
            val customActive = activeKey == "custom"
            Column(Modifier.fillMaxWidth().border(1.dp, if (customActive) c.accent else c.line, RoundedCornerShape(16.dp)).padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Personalizado" + if (customActive) " · ativo" else "", fontWeight = FontWeight.SemiBold)
                        Text("Qualquer endpoint OpenAI-compatible (gateways como LiteLLM, OmniRoute, one-api…). Base URL e chave em Configurações.", fontSize = 12.sp, color = c.ink3)
                    }
                    if (!customActive) TextButton({ app.store.updateSettings { switchProvider(it, null) } }) { Text("Ativar", color = c.accent) }
                }
            }
        }
    }
}
