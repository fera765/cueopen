package dev.fluwy.app.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.fluwy.app.FluwyApp
import dev.fluwy.app.data.MissedPolicy
import dev.fluwy.app.data.Repeat
import dev.fluwy.app.data.RunStatus
import dev.fluwy.app.data.ScheduledTask
import dev.fluwy.app.scheduler.ScheduleCalc
import dev.fluwy.app.scheduler.ScheduleText
import dev.fluwy.app.scheduler.Scheduler
import dev.fluwy.app.ui.theme.Fluwy
import java.util.Calendar

// ============================ AGENDADAS ============================

@Composable
fun ScheduledScreen(nav: NavController) {
    val app = FluwyApp.instance
    val st by app.store.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val c = Fluwy.c
    Scaffold(containerColor = c.bg, topBar = { FluwyTopBar("Tarefas agendadas", { nav.popBackStack() }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { nav.navigate("scheduled/new") }, containerColor = c.ink, contentColor = c.bg, shape = CircleShape,
                icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("Agendar") })
        }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (st.scheduled.isEmpty()) item { EmptyState(Icons.Rounded.Schedule, "Nada agendado", "Agende tarefas recorrentes: resumo de notícias diário, relatório semanal, monitoramento de preços…") }
            items(st.scheduled.sortedWith(compareBy({ !it.enabled }, { it.nextRunAt ?: Long.MAX_VALUE })), key = { it.id }) { t ->
                val zone = ScheduleCalc.zone(t)
                val last = t.runs.firstOrNull()
                FluwyCard(onClick = { nav.navigate("scheduled/${t.id}") }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Schedule, null, tint = if (t.enabled) c.accent else c.ink3); Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(t.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(ScheduleText.rule(t), fontSize = 12.sp, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(when {
                                t.runningSince != null -> "Executando agora…"
                                !t.enabled -> "Pausada"
                                t.nextRunAt != null -> "Próxima: ${ScheduleText.at(t.nextRunAt, zone)}"
                                else -> "Sem próximas execuções"
                            }, fontSize = 13.sp, color = c.ink2)
                            last?.let { r ->
                                Text("Última: ${ScheduleText.at(r.startedAt, zone)} · ${ScheduleText.status(r.status)}",
                                    fontSize = 12.sp, color = if (r.status == RunStatus.FAILED) c.err else c.ink3)
                            }
                        }
                        Switch(t.enabled, { on -> Scheduler.setEnabled(ctx, t.id, on) },
                            colors = SwitchDefaults.colors(checkedTrackColor = c.ink, checkedThumbColor = c.bg))
                    }
                }
            }
            item { TriggersSection(nav) }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScheduledEditScreen(nav: NavController, id: String) {
    val app = FluwyApp.instance
    val st by app.store.state.collectAsStateWithLifecycle()
    val existing = st.scheduled.firstOrNull { it.id == id }
    var t by remember { mutableStateOf(existing ?: ScheduledTask(title = "", prompt = "", startAt = System.currentTimeMillis() + 3600_000)) }
    var useCron by remember { mutableStateOf(t.cron.isNotBlank()) }
    var confirmDelete by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val c = Fluwy.c
    val draft = if (useCron) t else t.copy(cron = "")
    val error = ScheduleCalc.validate(draft)
    val preview = if (error == null) ScheduleCalc.upcoming(draft.copy(lastRunAt = null, enabled = true), System.currentTimeMillis(), 5) else emptyList()
    val zone = ScheduleCalc.zone(draft)

    fun save(): ScheduledTask {
        // Mantém campos de execução (histórico, em andamento) que podem ter mudado enquanto editava.
        val cur = app.store.state.value.scheduled.firstOrNull { it.id == draft.id }
        val merged = cur?.copy(title = draft.title.trim(), prompt = draft.prompt, agentId = draft.agentId, repeat = draft.repeat,
            startAt = draft.startAt, cron = draft.cron.trim(), timeZone = draft.timeZone.trim(), quietStartHour = draft.quietStartHour,
            quietEndHour = draft.quietEndHour, missedPolicy = draft.missedPolicy,
            lastRunAt = if (draft.repeat == Repeat.ONCE && draft.startAt != cur.startAt) null else cur.lastRunAt)
            ?: draft.copy(title = draft.title.trim(), cron = draft.cron.trim(), timeZone = draft.timeZone.trim())
        app.store.upsertScheduled(merged)
        Scheduler.schedule(ctx, merged)
        return merged
    }

    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false },
        title = { Text("Excluir agenda?") }, text = { Text("O histórico de execuções também será apagado.") },
        confirmButton = { TextButton({ Scheduler.cancel(ctx, id); app.store.deleteScheduled(id); confirmDelete = false; nav.popBackStack() }) { Text("Excluir", color = c.err) } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Cancelar") } })

    Scaffold(containerColor = c.bg, topBar = {
        FluwyTopBar(if (existing == null) "Agendar tarefa" else existing.title, { nav.popBackStack() }) {
            if (existing != null) IconButton({ confirmDelete = true }) { Icon(Icons.Rounded.Delete, "Excluir", tint = c.err) }
        }
    }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FluwyField(t.title, { t = t.copy(title = it) }, "Nome")
            FluwyField(t.prompt, { t = t.copy(prompt = it) }, "O que o agente deve fazer", minLines = 4)

            SectionLabel("Quando")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Repeat.entries.forEach { r -> Chip(ScheduleText.repeat(r), !useCron && t.repeat == r) { useCron = false; t = t.copy(repeat = r) } }
                Chip("Cron", useCron) { useCron = true; if (t.cron.isBlank()) t = t.copy(cron = "0 9 * * 1-5") }
            }
            if (useCron) {
                FluwyField(t.cron, { t = t.copy(cron = it) }, "Expressão cron (min hora dia mês dia-semana)")
                Text("Ex.: 0 9 * * 1-5 (dias úteis às 9h) · 30 */2 * * * (a cada 2 h) · @daily", fontSize = 12.sp, color = c.ink3)
            } else {
                Chip((if (t.repeat == Repeat.ONCE) "Em " else "A partir de ") + ScheduleText.at(t.startAt, zone), icon = Icons.Rounded.Event) {
                    val cal = Calendar.getInstance(java.util.TimeZone.getTimeZone(zone)).apply { timeInMillis = t.startAt }
                    DatePickerDialog(ctx, { _, y, m, d ->
                        TimePickerDialog(ctx, { _, h, min ->
                            cal.set(y, m, d, h, min, 0); cal.set(Calendar.MILLISECOND, 0); t = t.copy(startAt = cal.timeInMillis)
                        }, cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), true).show()
                    }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
                }
            }

            SectionLabel("Fuso horário")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Do aparelho (${java.time.ZoneId.systemDefault().id})", t.timeZone.isBlank()) { t = t.copy(timeZone = "") }
                Chip("UTC", t.timeZone == "UTC") { t = t.copy(timeZone = "UTC") }
            }
            FluwyField(t.timeZone, { t = t.copy(timeZone = it) }, "Outro fuso (IANA, ex.: Europe/Lisbon)")

            SectionLabel("Não executar entre")
            val quietOn = t.quietStartHour >= 0
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(quietOn, { on -> t = if (on) t.copy(quietStartHour = 23, quietEndHour = 7) else t.copy(quietStartHour = -1, quietEndHour = -1) },
                    colors = SwitchDefaults.colors(checkedTrackColor = c.ink, checkedThumbColor = c.bg))
                Spacer(Modifier.width(10.dp))
                if (quietOn) {
                    HourChip(t.quietStartHour) { t = t.copy(quietStartHour = it) }
                    Text("  e  ", color = c.ink2)
                    HourChip(t.quietEndHour) { t = t.copy(quietEndHour = it) }
                } else Text("Desligado: roda a qualquer hora", fontSize = 13.sp, color = c.ink2)
            }
            if (quietOn) Text("Disparos nessa janela são adiados para o fim dela.", fontSize = 12.sp, color = c.ink3)

            SectionLabel("Se perder um horário (aparelho desligado)")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Executar uma vez", t.missedPolicy == MissedPolicy.RUN_ONCE) { t = t.copy(missedPolicy = MissedPolicy.RUN_ONCE) }
                Chip("Pular", t.missedPolicy == MissedPolicy.SKIP) { t = t.copy(missedPolicy = MissedPolicy.SKIP) }
            }

            SectionLabel("Agent")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Padrão", t.agentId == null) { t = t.copy(agentId = null) }
                st.agents.forEach { a -> Chip(a.name, t.agentId == a.id) { t = t.copy(agentId = a.id) } }
            }

            SectionLabel("Próximas execuções")
            when {
                error != null -> Text(error, color = c.err, fontSize = 13.sp)
                preview.isEmpty() -> Text("Nenhuma execução futura com esta configuração.", color = c.ink2, fontSize = 13.sp)
                else -> preview.forEach { Text("• " + ScheduleText.at(it, zone), fontSize = 13.sp, color = c.ink2) }
            }

            Spacer(Modifier.height(4.dp))
            PillButton("Salvar", Icons.Rounded.Check, modifier = Modifier.fillMaxWidth(),
                enabled = t.title.isNotBlank() && t.prompt.isNotBlank() && error == null) { save(); nav.popBackStack() }
            if (existing != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Executar agora", Icons.Rounded.PlayArrow, primary = false, modifier = Modifier.weight(1f),
                    enabled = existing.runningSince == null && error == null) { Scheduler.runNow(ctx, save().id) }
                PillButton(if (existing.enabled) "Pausar" else "Retomar", if (existing.enabled) Icons.Rounded.Pause else Icons.Rounded.PlayCircle,
                    primary = false, modifier = Modifier.weight(1f)) { Scheduler.setEnabled(ctx, existing.id, !existing.enabled) }
            }

            if (existing != null && existing.runs.isNotEmpty()) {
                SectionLabel("Histórico")
                existing.runs.forEach { r ->
                    FluwyCard(onClick = r.sessionId?.let { sid -> { nav.navigate("chat/$sid") } }) {
                        Text("${ScheduleText.at(r.startedAt, zone)} · ${ScheduleText.status(r.status)}" +
                            (if (r.durationMs > 0) " · ${ScheduleText.duration(r.durationMs)}" else "") + (if (r.manual) " · manual" else ""),
                            fontSize = 13.sp, fontWeight = FontWeight.Medium, color = if (r.status == RunStatus.FAILED) c.err else c.ink)
                        if (r.error.isNotBlank()) Text(r.error, fontSize = 12.sp, color = c.ink3, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun HourChip(hour: Int, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Chip("%02d:00".format(hour)) { open = true }
        DropdownMenu(open, { open = false }) {
            (0..23).forEach { h -> DropdownMenuItem(text = { Text("%02d:00".format(h)) }, onClick = { onPick(h); open = false }) }
        }
    }
}
