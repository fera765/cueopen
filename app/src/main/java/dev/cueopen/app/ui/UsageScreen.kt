package dev.cueopen.app.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
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
import dev.cueopen.app.llm.KeyPool
import dev.cueopen.app.llm.ModelFallback
import dev.cueopen.app.llm.PlanUsage
import dev.cueopen.app.llm.ProviderCatalog
import dev.cueopen.app.llm.ProviderRoute
import dev.cueopen.app.llm.UsageLedger
import dev.cueopen.app.ui.theme.Cue

private fun fmtTokens(n: Long) = when { n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0); n >= 1_000 -> "%.1fk".format(n / 1_000.0); else -> n.toString() }

private fun resetLabel(iso: String): String = runCatching {
    val t = java.time.Instant.parse(iso)
    java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(t.toEpochMilli()))
}.getOrDefault(iso)

/** Limites do plano do provedor (OpenCode Go: % das janelas; OpenRouter: gasto e pedidos gratuitos) + modelos em espera. */
@Composable
fun PlanLimitsCard(providerId: String, keys: String, orgId: String) {
    val c = Cue.c
    var report by remember(providerId, keys, orgId) { mutableStateOf<PlanUsage.Report?>(null) }
    var loading by remember(providerId, keys, orgId) { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    val supported = providerId in setOf("opencode-go", "opencode-zen", "openrouter")
    LaunchedEffect(providerId, keys, orgId, refresh) {
        if (!supported) return@LaunchedEffect
        loading = true
        report = PlanUsage.fetch(providerId, KeyPool.current(keys), orgId)
        loading = false
    }
    val waiting = ModelFallback.limited()
    if (!supported && waiting.isEmpty()) return
    Column(Modifier.fillMaxWidth().border(1.dp, c.line, RoundedCornerShape(16.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Limites do plano" + (report?.provider?.let { " · $it" } ?: ""), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (supported) IconButton({ refresh++ }, enabled = !loading) { Icon(Icons.Rounded.Refresh, "Atualizar", tint = c.ink2) }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.accent)
        report?.error?.let { Text(it, fontSize = 12.sp, color = c.err) }
        report?.meters?.forEach { m ->
            Text("${m.label}: ${"%.0f".format(m.percent)}% usado" + (if (m.limited) " · LIMITE ATINGIDO" else "") +
                (if (m.resetsAt.isNotBlank()) " · reinicia ${resetLabel(m.resetsAt)}" else ""), fontSize = 13.sp, color = if (m.limited) c.err else c.ink)
            LinearProgressIndicator({ (m.percent / 100.0).toFloat().coerceIn(0f, 1f) }, Modifier.fillMaxWidth(),
                color = when { m.percent >= 90 -> c.err; m.percent >= 70 -> c.warn; else -> c.ok }, trackColor = c.line)
        }
        report?.lines?.forEach { Text(it, fontSize = 13.sp) }
        if (waiting.isNotEmpty()) {
            Text("Modelos em espera por limite:", fontSize = 12.sp, color = c.ink2)
            waiting.forEach { (m, until) ->
                Text("• " + ProviderRoute.label(m) + " até " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(until)), fontSize = 12.sp, color = c.ink2)
            }
        }
        if (supported && report == null && !loading) Text("Sem dados de uso (confira a chave).", fontSize = 12.sp, color = c.ink3)
    }
}

/** Uso de LLM por período, modelo e agent (painel de uso do OpenBot) + limites do plano do provedor ativo. */
@Composable
fun UsageScreen(nav: NavController) {
    val app = CueApp.instance
    val ctx = LocalContext.current
    val st by app.store.state.collectAsStateWithLifecycle()
    val s = st.settings
    val c = Cue.c
    var period by remember { mutableIntStateOf(1) }
    val days = remember(period) { UsageLedger.snapshot(ctx) }
    val total = UsageLedger.total(days, period)
    Scaffold(containerColor = c.bg, topBar = { CueTopBar("Uso e limites", { nav.popBackStack() }) }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val active = ProviderCatalog.activeId(s)
            PlanLimitsCard(active, s.apiKey, s.opencodeOrgId)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(1 to "Hoje", 7 to "7 dias", 30 to "30 dias").forEach { (n, l) -> FilterChip(period == n, { period = n }, label = { Text(l) }) }
            }
            val calls = total.models.values.sumOf { it.calls }
            val inTok = total.models.values.sumOf { it.input }; val outTok = total.models.values.sumOf { it.output }
            Text("$calls chamadas ao modelo · ${fmtTokens(inTok)} tokens de entrada · ${fmtTokens(outTok)} de saída", fontWeight = FontWeight.SemiBold)
            if (calls == 0) Text("Sem uso registrado neste período.", fontSize = 13.sp, color = c.ink3)
            if (total.models.isNotEmpty()) SectionLabel("Por modelo")
            total.models.entries.sortedByDescending { it.value.input + it.value.output }.forEach { (k, v) ->
                Text("$k\n${v.calls} chamadas · ${fmtTokens(v.input)} entrada · ${fmtTokens(v.output)} saída", fontSize = 13.sp)
            }
            if (total.agents.isNotEmpty()) SectionLabel("Por agent")
            total.agents.entries.sortedByDescending { it.value.input + it.value.output }.forEach { (k, v) ->
                Text("$k · ${v.calls} chamadas · ${fmtTokens(v.input + v.output)} tokens", fontSize = 13.sp)
            }
            Text("Conta as chamadas do agente principal; a cada conversa nova o app já economiza o título e só extrai memórias quando você diz algo duradouro.", fontSize = 11.sp, color = c.ink3)
        }
    }
}
