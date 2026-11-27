package dev.fluwy.app.ui
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.fluwy.app.FluwyApp
import dev.fluwy.app.agent.Activity
import dev.fluwy.app.agent.AskTools
import dev.fluwy.app.data.ChatItem
import dev.fluwy.app.data.Session
import dev.fluwy.app.data.SessionStatus
import dev.fluwy.app.data.StepStatus
import dev.fluwy.app.ui.theme.Fluwy
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Card de autorização da Seção: o usuário marca o que o time pode usar e ativa.
 * Ao ativar, as permissões valem para todos os Agents da seção e o coordenador começa a trabalhar.
 */
@Composable
internal fun SectionSetupCard(item: ChatItem.SectionSetup, sessionId: String?, nav: androidx.navigation.NavController?) {
    val c = Fluwy.c
    val app = dev.fluwy.app.FluwyApp.instance
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var browser by remember(item.id) { mutableStateOf(true) }
    var terminal by remember(item.id) { mutableStateOf(true) }
    var wallet by remember(item.id) { mutableStateOf(item.suggestWallet) }
    var caps by remember(item.id) { mutableStateOf(item.suggestedCaps.toSet()) }
    var connectors by remember(item.id) { mutableStateOf(emptySet<String>()) }
    var fullAuto by remember(item.id) { mutableStateOf(item.suggestAuto) }
    val section = app.store.section(item.sectionId)
    Column(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(16.dp))
            .background(c.surface).border(1.dp, if (item.state == "pending") c.accent else c.line, RoundedCornerShape(16.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Folder, null, tint = c.accent, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
            Text("Seção “${item.sectionName}”", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        }
        Text("Time: " + item.agentNames.joinToString(" · "), fontSize = 13.sp, color = c.ink2)
        when (item.state) {
            "pending" -> {
                Text("O que este time pode usar?", fontSize = 13.sp, color = c.ink2, fontWeight = FontWeight.Medium)
                ToggleRow("Browser", "Navegar e agir em sites (usa seus logins salvos)", browser) { browser = it }
                ToggleRow("Terminal Linux", "Rodar comandos e scripts no sandbox", terminal) { terminal = it }
                ToggleRow("Carteira Solana", "Consultar endereço e saldo e pedir transferências (cada uma pede sua aprovação)", wallet) { wallet = it }
                val hub = app.uiConnectors.connectors
                if (hub.isNotEmpty()) {
                    Text("Conectores", fontSize = 13.sp, color = c.ink2, fontWeight = FontWeight.Medium)
                    hub.forEach { uc ->
                        ToggleRow(uc.spec.name, uc.spec.description.take(90), uc.spec.id in connectors) { on ->
                            connectors = if (on) connectors + uc.spec.id else connectors - uc.spec.id
                        }
                    }
                }
                Text("Acesso ao Android", fontSize = 13.sp, color = c.ink2, fontWeight = FontWeight.Medium)
                dev.fluwy.app.device.DeviceCaps.all.forEach { cap ->
                    ToggleRow(cap.title, cap.desc, cap.id in caps) { on -> caps = if (on) caps + cap.id else caps - cap.id }
                }
                ToggleRow("100% autônomo", "Trabalha sozinho até o fim: não faz perguntas nem pede confirmação (inclusive para comprar, pagar, apagar, enviar ou transferir)", fullAuto) { fullAuto = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ sessionId?.let { app.agent.cancelSectionSetup(it, item) } }, Modifier.weight(1f)) { Text("Agora não") }
                    Button({
                        val sid = sessionId ?: return@Button
                        val target = app.agent.activateSection(sid, item, dev.fluwy.app.agent.AgentRunner.SectionPermissions(
                            browser = browser, terminal = terminal, wallet = wallet, uiConnectorIds = connectors.toList(),
                            deviceCaps = caps.toList(), fullAuto = fullAuto,
                        ))
                        if (dev.fluwy.app.device.DeviceCaps.BACKGROUND in caps) dev.fluwy.app.device.DeviceCaps.requestBatteryUnrestricted(ctx)
                        caps.filterNot { dev.fluwy.app.device.DeviceCaps.granted(ctx, it) }.firstOrNull()?.let { dev.fluwy.app.device.DeviceCaps.request(ctx, it) }
                        target?.let { t ->
                            // O chat que só serviu para criar a seção sai de cena: vai direto para a seção rodando.
                            nav?.popBackStack()
                            nav?.navigate("chat/$t")
                            app.deleteChat(sid)
                        }
                    }, Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = c.ink, contentColor = c.bg)) { Text("Ativar seção") }
                }
            }
            "activated" -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("✓ Seção ativada", fontSize = 13.sp, color = c.ok, modifier = Modifier.weight(1f))
                if (section != null) TextButton({ nav?.navigate("chat/${section.sessionId}") }) { Text("Abrir seção", color = c.accent) }
            }
            else -> Text("Ativação adiada. Peça no chat para ativar a seção quando quiser.", fontSize = 12.sp, color = c.ink3)
        }
    }
}


/** Ponto verde pulsando: agente trabalhando agora. */
@Composable
fun PulsingDot(color: Color, size: androidx.compose.ui.unit.Dp = 8.dp) {
    val t = rememberInfiniteTransition(label = "pulse")
    val k by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1100), RepeatMode.Restart), label = "k")
    Box(Modifier.size(size * 2), contentAlignment = Alignment.Center) {
        Box(Modifier.size(size * (1f + k)).clip(CircleShape).background(color.copy(alpha = (1f - k) * .45f)))
        Box(Modifier.size(size).clip(CircleShape).background(color))
    }
}

internal fun teamActivityLabel(a: Activity?, running: Boolean, waiting: Boolean, failed: Boolean, done: Boolean): String = when {
    waiting -> "aguardando você"
    running -> when (a?.kind) {
        Activity.Kind.BROWSER -> "usando o browser"
        Activity.Kind.TERMINAL -> "usando o terminal"
        Activity.Kind.EDITOR -> "editando arquivos"
        Activity.Kind.SEARCH -> "pesquisando"
        else -> "pensando"
    } + (a?.detail?.takeIf { it.isNotBlank() }?.let { " · " + it.take(60) } ?: "")
    failed -> "parou com erro"
    done -> "concluiu"
    else -> "na fila"
}

/** Pílula de um agente da seção no chat do coordenador: mostra ao vivo o que ele está fazendo; tocar abre o chat dele. */
/** Exército de agentes: o time inteiro, cada um com mascote, papel e estado; abre o browser ao vivo. */
@Composable
internal fun BuildSquadCard(step: ChatItem.Step, sessionId: String?, nav: androidx.navigation.NavController?) {
    val c = Fluwy.c
    val squads by dev.fluwy.app.agent.BuildSquad.squads.collectAsStateWithLifecycle()
    // App reiniciado: o time salvo volta para o card (estado final de cada agente).
    LaunchedEffect(step.args) {
        if (dev.fluwy.app.agent.BuildSquad.get(step.args) == null)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { dev.fluwy.app.agent.BuildSquad.restore(step.args) }
    }
    val sq = squads[step.args]
    var expanded by remember(step.id) { mutableStateOf(false) }
    Column(
        Modifier.padding(vertical = 4.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface)
            .border(1.dp, if (sq?.running == true) c.ok.copy(alpha = .5f) else c.line, RoundedCornerShape(18.dp)).padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Exército de agentes", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text(if (sq == null) step.result.ifBlank { step.title } else if (sq.running && sq.workers.isEmpty()) "Preparando o app antes de o time entrar" else if (sq.running) "Construindo · ${sq.active} trabalhando · ${sq.done}/${sq.workers.size} prontos"
                    else "${sq.done} de ${sq.workers.size} concluíram", fontSize = 12.sp, color = c.ink3)
            }
            if (sq?.running == true) PulsingDot(c.ok)
            else if (step.status == StepStatus.OK) Icon(Icons.Rounded.Check, null, Modifier.size(16.dp), tint = c.ok)
        }
        if (sq != null) {
            Spacer(Modifier.height(8.dp))
            val shown = if (expanded) sq.workers else sq.workers.sortedBy { if (it.status == dev.fluwy.app.agent.BuildSquad.St.RUNNING) 0 else 1 }.take(8)
            shown.forEach { w ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { nav?.navigate("chat/${w.sessionId}") }.padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    val running = w.status == dev.fluwy.app.agent.BuildSquad.St.RUNNING
                    AnimatedAgentFace(Color(w.persona.color), w.persona.face, 20.dp,
                        state = if (running) dev.fluwy.app.ui.mascot.MascotState.THINKING else dev.fluwy.app.ui.mascot.MascotState.IDLE)
                    Spacer(Modifier.width(8.dp))
                    Text(buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Color(w.persona.color))) { append(w.persona.name) }
                        append(" · ${w.persona.role} · ")
                        withStyle(SpanStyle(color = c.ink3)) { append(if (running) w.doing.ifBlank { w.label } else w.label) }
                    }, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    when (w.status) {
                        dev.fluwy.app.agent.BuildSquad.St.RUNNING -> PulsingDot(Color(w.persona.color))
                        dev.fluwy.app.agent.BuildSquad.St.DONE -> Icon(Icons.Rounded.Check, null, Modifier.size(14.dp), tint = c.ok)
                        dev.fluwy.app.agent.BuildSquad.St.FAILED -> Icon(Icons.Rounded.Close, null, Modifier.size(14.dp), tint = c.err)
                        else -> Text("na fila", fontSize = 11.sp, color = c.ink3)
                    }
                }
            }
            if (sq.notice.isNotBlank()) Text(sq.notice, fontSize = 12.sp, color = c.warn, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            if (sq.workers.size > 8) Text(if (expanded) "Mostrar menos" else "Ver os ${sq.workers.size} agentes", fontSize = 12.sp, color = c.accent,
                modifier = Modifier.clickable { expanded = !expanded }.padding(top = 4.dp))
            if (sq.preview != null && sessionId != null) {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.clip(RoundedCornerShape(12.dp)).background(c.accent.copy(alpha = .12f))
                    .clickable { nav?.navigate("computer/$sessionId?tab=browser") }.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Computer, null, Modifier.size(16.dp), tint = c.accent)
                    Spacer(Modifier.width(6.dp))
                    Text(if (sq.running) "Ver os agentes ao vivo no browser" else "Abrir o resultado no browser", fontSize = 13.sp, color = c.accent, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
internal fun TeamMemberPill(step: ChatItem.Step, nav: androidx.navigation.NavController?) {
    val c = Fluwy.c
    val app = dev.fluwy.app.FluwyApp.instance
    val (childId, memberId) = step.args.split('|').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
    val st by app.store.state.collectAsStateWithLifecycle()
    val activity by app.agent.activity.collectAsStateWithLifecycle()
    val member = st.agents.firstOrNull { it.id == memberId }
    // O agente usa sempre o mesmo chat, então todas as pílulas dele apontam para a mesma sessão. Só a pílula da
    // delegação ATUAL (a mais recente, ainda em andamento) acompanha o estado ao vivo; as anteriores mostram o
    // estado final que tiveram.
    val isLatest = remember(st.sessions, step.id, childId) {
        st.sessions.firstOrNull { s -> s.items.any { it.id == step.id } }?.items
            ?.lastOrNull { it is ChatItem.Step && it.tool == dev.fluwy.app.agent.TEAM_MEMBER_STEP && it.args.substringBefore('|') == childId }?.id == step.id
    }
    val live = step.status == StepStatus.RUNNING && isLatest
    val child = if (live) st.sessions.firstOrNull { it.id == childId } else null
    val running = child?.status == SessionStatus.RUNNING
    val waiting = (live && child?.status == SessionStatus.WAITING_USER) || step.result == "aguardando você"
    val finishedOk = step.status == StepStatus.OK || (step.status == StepStatus.RUNNING && !isLatest)
    val label = if (!running && step.result == "retomando sozinho") "retomando sozinho (falha temporária do modelo)"
        else if (!running && step.result == "pausado") "pausado"
        else teamActivityLabel(if (live) activity[childId] else null, running, waiting && !running, step.status == StepStatus.ERROR, finishedOk)
    Row(
        Modifier.padding(vertical = 4.dp).clip(RoundedCornerShape(18.dp)).background(c.surface)
            .border(1.dp, if (running) c.ok.copy(alpha = .5f) else c.line, RoundedCornerShape(18.dp))
            .clickable(enabled = childId.isNotBlank()) { nav?.navigate("chat/$childId") }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (member != null) AnimatedAgentFace(Color(member.color), member.face, 26.dp,
            state = if (running) dev.fluwy.app.ui.mascot.MascotState.THINKING else dev.fluwy.app.ui.mascot.MascotState.IDLE)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f, fill = false)) {
            Text(buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(member?.name ?: "Agente") }
                append(" · $label")
            }, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (step.title.isNotBlank()) Text(step.title, fontSize = 12.sp, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(6.dp))
        when {
            running -> PulsingDot(c.ok)
            waiting -> Icon(Icons.Rounded.PanTool, null, Modifier.size(14.dp), tint = c.warn)
            step.status == StepStatus.ERROR -> Icon(Icons.Rounded.Close, null, Modifier.size(14.dp), tint = c.err)
            finishedOk -> Icon(Icons.Rounded.Check, null, Modifier.size(14.dp), tint = c.ok)
        }
        Icon(Icons.Rounded.ChevronRight, null, Modifier.size(16.dp), tint = c.ink3)
    }
}

/** Faixa no topo do composer do chat da seção: quem do time está trabalhando agora (toque abre o chat do agente). */
@Composable
internal fun TeamLiveStrip(sec: dev.fluwy.app.data.Section, nav: androidx.navigation.NavController) {
    val c = Fluwy.c
    val app = dev.fluwy.app.FluwyApp.instance
    val st by app.store.state.collectAsStateWithLifecycle()
    val activity by app.agent.activity.collectAsStateWithLifecycle()
    val busy = sec.agentIds.mapNotNull { id -> st.agents.firstOrNull { it.id == id } }.mapNotNull { a ->
        val chat = st.sessions.firstOrNull { it.sectionId == sec.id && it.agentId == a.id && it.id != sec.sessionId } ?: return@mapNotNull null
        if (chat.status == SessionStatus.RUNNING || chat.status == SessionStatus.WAITING_USER) Triple(a, chat, activity[chat.id]) else null
    }
    if (busy.isEmpty()) return
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        busy.forEach { (a, chat, act) ->
            val running = chat.status == SessionStatus.RUNNING
            Row(Modifier.clip(RoundedCornerShape(16.dp)).background(c.surface).border(1.dp, if (running) c.ok.copy(alpha = .5f) else c.warn.copy(alpha = .5f), RoundedCornerShape(16.dp))
                .clickable { nav.navigate("chat/${chat.id}") }.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                AnimatedAgentFace(Color(a.color), a.face, 20.dp, state = if (running) dev.fluwy.app.ui.mascot.MascotState.THINKING else dev.fluwy.app.ui.mascot.MascotState.IDLE)
                Spacer(Modifier.width(6.dp))
                Text(a.name + " · " + teamActivityLabel(act, running, !running, false, false).substringBefore(" · "), fontSize = 12.sp, maxLines = 1)
                Spacer(Modifier.width(4.dp))
                if (running) PulsingDot(c.ok, 6.dp)
            }
        }
    }
}

/** Cartão no chat para página criada/atualizada (Fluwy Pages): toque abre a página. */
@Composable
internal fun PageStepCard(s: ChatItem.Step, nav: androidx.navigation.NavController?) {
    val c = Fluwy.c
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface).clickable { nav?.navigate("page/${s.args}") }.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Article, null, tint = c.accent); Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(s.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text("Fluwy Pages · " + s.result + " · toque para abrir", fontSize = 12.sp, color = c.ink3)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = c.ink3)
    }
}

/** Pedido de login (como o takeover do Manus): o usuário entra no site, salva a sessão e a tarefa continua. */
@Composable
internal fun LoginNeededCard(s: ChatItem.Step, sessionId: String?, nav: androidx.navigation.NavController?) {
    val c = Fluwy.c
    val loginId = s.args.substringBefore('|')
    val st by dev.fluwy.app.FluwyApp.instance.store.state.collectAsStateWithLifecycle()
    val done = st.browserLogins.firstOrNull { it.id == loginId }?.status == "active"
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (done) Icons.Rounded.LockOpen else Icons.Rounded.Lock, null, tint = if (done) c.ok else c.accent); Spacer(Modifier.width(8.dp))
            Text(if (done) "Sessão salva" else s.title, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
        Text(if (done) "O agente já pode entrar neste site." else "Entre com a sua conta no site. A senha fica só no site: o app e a IA não veem.", fontSize = 12.sp, color = c.ink2)
        if (!done) Button({ nav?.navigate("browserlogin/$loginId" + (sessionId?.let { "?session=$it" } ?: "")) },
            colors = ButtonDefaults.buttonColors(containerColor = c.ink, contentColor = c.bg)) { Text("Entrar e salvar") }
    }
}


/** Execuções do orquestrador nesta conversa: tarefa, estado, ferramentas usadas e duração. */
@Composable
internal fun ExecutionsDialog(sessionId: String, onDismiss: () -> Unit) {
    val c = Fluwy.c
    val runs = remember { dev.fluwy.app.agent.ExecutionLedger.history(sessionId).reversed() }
    val fmt = remember { java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Execuções") }, text = {
        androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (runs.isEmpty()) item { Text("Nenhuma execução registrada ainda.", color = c.ink3, fontSize = 13.sp) }
            items(runs.size) { i ->
                val r = runs[i]
                val color = when (r.state) { "DONE" -> c.ok; "ERROR" -> c.err; "RUNNING" -> c.accent; else -> c.ink3 }
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface).padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(r.state, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.width(8.dp))
                        Text(fmt.format(java.util.Date(r.startedAt)) + (r.endedAt?.let { " · ${(it - r.startedAt) / 1000}s" } ?: ""), fontSize = 11.sp, color = c.ink3)
                        Spacer(Modifier.weight(1f)); Text(r.runId.takeLast(6), fontSize = 10.sp, color = c.ink3)
                    }
                    Text(r.goal.take(160), fontSize = 13.sp, maxLines = 3)
                    val reused = r.ops.count { it.reused }; val failed = r.ops.count { !it.ok }
                    Text("${r.ops.size} operação(ões)" + (if (failed > 0) " · $failed com erro" else "") + (if (reused > 0) " · $reused reaproveitada(s)" else "") +
                        " · ${r.allowedTools.size} ferramentas autorizadas", fontSize = 11.sp, color = c.ink2)
                    r.ops.takeLast(6).forEach { op -> Text((if (op.ok) "✓ " else "✗ ") + op.tool + (if (op.reused) " (reaproveitada)" else " · ${op.ms} ms"), fontSize = 11.sp, color = if (op.ok) c.ink3 else c.err) }
                    if (r.result.isNotBlank()) Text("Resultado: " + r.result.take(160), fontSize = 11.sp, color = c.ink2)
                }
            }
        }
    }, confirmButton = { TextButton(onDismiss) { Text("Fechar") } })
}

/** Enquanto a resposta em áudio é gerada (no lugar do card da ferramenta). */
@Composable
internal fun GeneratingAudioRow() {
    val c = dev.fluwy.app.ui.theme.Fluwy.c
    Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = c.accent)
        Spacer(Modifier.width(8.dp))
        Text("Gerando áudio…", fontSize = 13.sp, color = c.ink2)
    }
}

/** Linha "Nova mensagem" antes da ordem do coordenador que o usuário ainda não tinha visto. */
@Composable
internal fun NewMessageDivider() {
    val c = dev.fluwy.app.ui.theme.Fluwy.c
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        HorizontalDivider(Modifier.weight(1f), color = c.err, thickness = 1.5.dp)
        Text("Nova mensagem", color = c.err, fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 10.dp))
        HorizontalDivider(Modifier.weight(1f), color = c.err, thickness = 1.5.dp)
    }
}
