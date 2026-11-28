package dev.fluwy.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.fluwy.app.FluwyApp
import dev.fluwy.app.agent.AgentConfigRules
import dev.fluwy.app.data.AgentProfile
import dev.fluwy.app.ui.theme.Fluwy
import dev.fluwy.app.wallet.WalletAgentPolicyStore

/**
 * Configuração de um agent em seções recolhíveis (resumo de uma linha quando fechada).
 * Nada é gravado até Salvar, exceto autonomia e acessos ao Android de um agent já salvo
 * (valem na hora para não se perderem no diálogo de bateria/permissão do sistema).
 */
@Composable
fun AgentEditScreen(nav: NavController, id: String, botId: String? = null, sectionId: String? = null) {
    val app = FluwyApp.instance
    val st by app.store.state.collectAsStateWithLifecycle()
    val existing = st.agents.firstOrNull { it.id == id }
    val original = remember { existing }
    // Agent novo: Terminal, Browser, Browser em segundo plano e Hub ligados. Bot do catálogo só preenche o rascunho.
    var a by remember { mutableStateOf(existing ?: dev.fluwy.app.device.BrowserAutonomyPolicy.setEnabled(AgentProfile(name = ""), true).let { base ->
        dev.fluwy.app.agent.BotCatalog.find(botId)?.let { dev.fluwy.app.agent.BotCatalog.draft(it, base) } ?: base
    }) }
    val c = Fluwy.c
    val ctx = LocalContext.current
    var resumeTick by remember { mutableIntStateOf(0) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) resumeTick++ }
        lifecycleOwner.lifecycle.addObserver(obs); onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val walletPolicies = remember { WalletAgentPolicyStore(app.applicationContext) }
    val wallet = remember(a.id) { AgentWalletDraft(walletPolicies, a.id) }
    var open by remember { mutableStateOf(if (existing == null) setOf("id", "behavior") else emptySet()) }
    fun toggle(k: String) { open = if (k in open) open - k else open + k }
    var confirmDelete by remember { mutableStateOf(false) }
    val errors = AgentConfigRules.validate(a, st.agents.filter { it.id != a.id }.map { it.name })

    Scaffold(containerColor = c.bg, topBar = {
        FluwyTopBar(if (existing == null) (if (sectionId != null) "Criar Agent para a seção" else "Criar Agent") else a.name.ifBlank { "Agent" }, { nav.popBackStack() })
    }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AgentEditSection("Identidade", Icons.Rounded.Face, AgentConfigRules.identity(a), "id" in open, { toggle("id") }, warn = a.name.isBlank() && "id" !in open) {
                AgentIdentityPanel(a) { a = it }
            }
            AgentEditSection("Comportamento", Icons.Rounded.Psychology, AgentConfigRules.behavior(a), "behavior" in open, { toggle("behavior") }) {
                // Caixa de tamanho fixo: prompts grandes rolam por dentro.
                FluwyField(a.instructions, { a = a.copy(instructions = it) }, "Instruções (como agir, tom, objetivos)", modifier = Modifier.height(200.dp), minLines = 5)
            }
            AgentEditSection("Modelo", Icons.Rounded.Memory, AgentConfigRules.model(a), "model" in open, { toggle("model") }) {
                ModelPicker(a.model ?: "", { a = a.copy(model = it.ifBlank { null }) }, "Modelo (vazio = padrão do app)", allowEmpty = true,
                    baseUrl = app.store.settings.baseUrl, apiKey = app.store.settings.apiKey)
            }
            AgentEditSection("Ferramentas e permissões", Icons.Rounded.Build, AgentConfigRules.tools(a) + if (wallet.access) " · carteira" else "", "tools" in open, { toggle("tools") }) {
                AgentToolsPanel(a, wallet, { a = it }) { upd ->
                    a = upd
                    if (existing != null) app.store.agent(upd.id)?.let { cur ->
                        app.store.upsertAgent(cur.copy(deviceCaps = upd.deviceCaps, browserAutonomy = upd.browserAutonomy,
                            browserAutonomyGrantedBackground = upd.browserAutonomyGrantedBackground))
                    }
                }
            }
            AgentEditSection("Conhecimento e skills", Icons.Rounded.AutoAwesome, AgentConfigRules.knowledge(a), "knowledge" in open, { toggle("knowledge") }) {
                AgentKnowledgePanel(a, st.skills) { a = it }
            }
            AgentEditSection("Autonomia e agenda", Icons.Rounded.Schedule, AgentConfigRules.autonomy(a), "auto" in open, { toggle("auto") }) {
                AgentAutonomyPanel(a, resumeTick, onToggle = { on ->
                    a = dev.fluwy.app.device.AgentAutonomyPolicy.setAutonomous(a, on)
                    if (existing != null) app.store.agent(a.id)?.let { cur ->
                        val upd = dev.fluwy.app.device.AgentAutonomyPolicy.setAutonomous(cur, on)
                        app.store.upsertAgent(upd); dev.fluwy.app.scheduler.Heartbeat.sync(ctx, upd)
                    }
                    if (on) dev.fluwy.app.device.DeviceCaps.requestBatteryUnrestricted(ctx)
                }) { a = it }
            }
            AgentEditSection("Avançado", Icons.Rounded.Tune, if (existing == null) "Disponível após salvar" else "${a.beats} ciclos · ${a.actions} ações", "adv" in open, { toggle("adv") }) {
                AgentAdvancedPanel(a, existing == null, { a = it }) { confirmDelete = true }
            }
            errors.forEach { Text("• $it", fontSize = 12.sp, color = c.err) }
            PillButton("Salvar", Icons.Rounded.Check, modifier = Modifier.fillMaxWidth(), enabled = errors.isEmpty()) {
                val saved = dev.fluwy.app.device.AgentAutonomyPolicy.mergeForSave(a.copy(name = a.name.trim()), original, app.store.agent(a.id))
                app.store.upsertAgent(saved)
                dev.fluwy.app.scheduler.Heartbeat.sync(ctx, saved)
                wallet.save(walletPolicies, a.id)
                if (saved.autonomous) app.agent.startAutonomy()
                // Aberto pelo "Adicionar agente" de uma seção: o agente salvo já entra no time.
                sectionId?.let { app.agent.addAgentToSection(it, saved.id) }
                nav.popBackStack()
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Excluir ${a.name}?") },
        text = { Text("O agent, a memória dele e a agenda autônoma serão removidos.") },
        confirmButton = { TextButton({ confirmDelete = false; app.store.deleteAgent(id); nav.popBackStack() }) { Text("Excluir", color = c.err) } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancelar") } },
    )
}
