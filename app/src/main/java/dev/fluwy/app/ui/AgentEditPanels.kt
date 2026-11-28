package dev.fluwy.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.fluwy.app.agent.AgentConfigRules
import dev.fluwy.app.data.AgentProfile
import dev.fluwy.app.data.Skill
import dev.fluwy.app.ui.theme.Fluwy

@Composable
fun AgentIdentityPanel(a: AgentProfile, onChange: (AgentProfile) -> Unit) {
    val c = Fluwy.c
    dev.fluwy.app.ui.mascot.MascotPreview(a.face, Color(a.color))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        agentColors.forEach { col ->
            Box(Modifier.padding(4.dp).size(28.dp).clip(CircleShape).background(Color(col))
                .border(if (a.color == col) 3.dp else 0.dp, c.ink, CircleShape).clickable { onChange(a.copy(color = col)) })
        }
    }
    dev.fluwy.app.ui.mascot.MascotPicker(a.face, Color(a.color)) { onChange(a.copy(face = it)) }
    FluwyField(a.name, { onChange(a.copy(name = it.take(AgentConfigRules.MAX_NAME))) }, "Nome do agent")
    FluwyField(a.mission, { onChange(a.copy(mission = it)) }, "Missão em uma frase (aparece nas seções e na autonomia)")
}

@Composable
fun AgentKnowledgePanel(a: AgentProfile, skills: List<Skill>, onChange: (AgentProfile) -> Unit) {
    val c = Fluwy.c
    var query by remember { mutableStateOf("") }
    Text("Todas as skills ativas já ficam disponíveis. As marcadas aparecem primeiro para o agent.", fontSize = 12.sp, color = c.ink3)
    if (skills.size > 6) FluwyField(query, { query = it }, "Buscar skill")
    val shown = skills.filter { query.isBlank() || it.name.contains(query, true) || it.description.contains(query, true) }
    if (shown.isEmpty()) Text("Nenhuma skill encontrada.", fontSize = 13.sp, color = c.ink3)
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
        items(shown.size, key = { shown[it].id }) { i ->
            val s = shown[i]
            val on = s.id in a.skillIds
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onChange(a.copy(skillIds = if (on) a.skillIds - s.id else (a.skillIds + s.id).distinct())) },
                verticalAlignment = Alignment.CenterVertically) {
                Checkbox(on, { v -> onChange(a.copy(skillIds = if (v) (a.skillIds + s.id).distinct() else a.skillIds - s.id)) }, colors = CheckboxDefaults.colors(checkedColor = c.accent))
                Icon(skillIcon(s.icon), null, Modifier.size(16.dp), tint = c.ink2)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.name, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (s.description.isNotBlank()) Text(s.description, fontSize = 11.sp, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    Text("Memória do agent", fontSize = 13.sp, color = c.ink2)
    if (a.memory.isEmpty()) Text("O agent salva fatos aqui com a ferramenta remember.", color = c.ink3, fontSize = 12.sp)
    a.memory.forEachIndexed { i, m ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("• $m", Modifier.weight(1f), fontSize = 14.sp)
            IconButton({ onChange(a.copy(memory = a.memory.filterIndexed { j, _ -> j != i })) }) { Icon(Icons.Rounded.Close, "Remover", Modifier.size(16.dp)) }
        }
    }
    var newMem by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        FluwyField(newMem, { newMem = it }, "Adicionar memória", Modifier.weight(1f))
        IconButton({ if (newMem.isNotBlank()) { onChange(a.copy(memory = a.memory + newMem.trim())); newMem = "" } }) { Icon(Icons.Rounded.Add, "Adicionar") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgentAutonomyPanel(a: AgentProfile, resumeTick: Int, onToggle: (Boolean) -> Unit, onChange: (AgentProfile) -> Unit) {
    val c = Fluwy.c
    val ctx = LocalContext.current
    ToggleRow("Pode trabalhar de forma autônoma", "O próprio agent decide, pela conversa, se e quando acordar para seguir uma tarefa contínua.", a.autonomous, onToggle)
    resumeTick.let {}
    if (a.autonomous && dev.fluwy.app.device.DeviceCaps.BACKGROUND !in a.deviceCaps)
        Text("‘Executar em segundo plano’ foi desligado manualmente. Toque para religar →", fontSize = 12.sp, color = c.warn,
            modifier = Modifier.clickable { onChange(dev.fluwy.app.device.AgentAutonomyPolicy.setAutonomous(a, true)); dev.fluwy.app.device.DeviceCaps.requestBatteryUnrestricted(ctx) })
    else if (a.autonomous && !dev.fluwy.app.device.DeviceCaps.batteryUnrestricted(ctx))
        Text("O Android ainda otimiza a bateria do app e pode pausar os ciclos. Toque para liberar →", fontSize = 12.sp, color = c.warn,
            modifier = Modifier.clickable { dev.fluwy.app.device.DeviceCaps.requestBatteryUnrestricted(ctx) })
    if (!a.autonomous) return
    Text("Diga no chat o que acompanhar (ex.: \"a cada 20 min verifique o mercado\") e ele se agenda sozinho.", fontSize = 12.sp, color = c.ink3)
    Text("Horário ativo", fontSize = 13.sp, color = c.ink2)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(Triple(0, 24, "24h"), Triple(8, 18, "Comercial 8–18h"), Triple(7, 23, "Dia 7–23h"), Triple(22, 6, "Noite 22–6h")).forEach { (s, e, l) ->
            Chip(l, a.activeStartHour == s && a.activeEndHour == e) { onChange(a.copy(activeStartHour = s, activeEndHour = e)) }
        }
    }
    Text("Orçamento diário de tokens", fontSize = 13.sp, color = c.ink2)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AgentConfigRules.budgets.forEach { (v, l) -> Chip(l, a.dailyTokenBudget == v) { onChange(a.copy(dailyTokenBudget = v)) } }
    }
    ToggleRow("Notificar quando agir", "Avisa quando um ciclo fizer algo (silencioso quando nada muda)", a.notifyOnAction) { onChange(a.copy(notifyOnAction = it)) }
}

@Composable
fun AgentAdvancedPanel(a: AgentProfile, isNew: Boolean, onChange: (AgentProfile) -> Unit, onDelete: () -> Unit) {
    val c = Fluwy.c
    if (isNew) { Text("Estatísticas aparecem depois que o agent for salvo.", fontSize = 12.sp, color = c.ink3); return }
    Text("Ciclos: ${a.beats} · Ações: ${a.actions} · Tokens hoje: ${AgentConfigRules.budgetLabel(a.tokensToday)}", fontSize = 13.sp, color = c.ink2)
    if (a.consecutiveFailures > 0) Row(verticalAlignment = Alignment.CenterVertically) {
        Text("${a.consecutiveFailures} falha(s) seguidas na autonomia", Modifier.weight(1f), fontSize = 13.sp, color = c.warn)
        TextButton({ onChange(a.copy(consecutiveFailures = 0)) }) { Text("Zerar") }
    }
    Text("ID: ${a.id}", fontSize = 11.sp, color = c.ink3)
    OutlinedButton(onDelete, Modifier.fillMaxWidth()) { Text("Excluir agent", color = c.err) }
}
