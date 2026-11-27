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

@Composable
fun TimelineItem(item: ChatItem, onOpenComputer: (String) -> Unit, onOpenMedia: (String) -> Unit = {}, sessionId: String? = null, nav: androidx.navigation.NavController? = null, onSend: ((String) -> Unit)? = null, onReply: ((ChatItem.Assistant) -> Unit)? = null) {
    when (item) {
        is ChatItem.User -> UserBubble(item)
        is ChatItem.Assistant -> AssistantText(item, onSend, onReply)
        is ChatItem.Step -> when (item.tool) {
            dev.fluwy.app.agent.TEAM_MEMBER_STEP -> TeamMemberPill(item, nav)
            dev.fluwy.app.agent.BuildSquad.STEP -> BuildSquadCard(item, sessionId, nav)
            "team_delegate", "team_status" -> {}  // a pílula do agente já representa a delegação
            "reply_audio" -> if (item.status == StepStatus.RUNNING) GeneratingAudioRow() // no fim aparece só o áudio
            "page" -> PageStepCard(item, nav)
            "login_needed" -> LoginNeededCard(item, sessionId, nav)
            else -> StepRow(item, onOpenComputer)
        }
        is ChatItem.Plan -> {}  // o plano fica no card do computador, acima do composer
        is ChatItem.Media -> MediaCard(item, onOpenMedia)
        is ChatItem.Cards -> ResultCards(item, sessionId, onSend)
        is ChatItem.Audio -> AudioMessage(item)
        is ChatItem.PixQr -> PixQrMessage(item)
        is ChatItem.Error -> InfoRow(Icons.Rounded.ErrorOutline, item.text, Fluwy.c.err)
        is ChatItem.ConnectorProposal -> ConnectorProposalCard(item, sessionId, nav)
        is ChatItem.UserScriptProposal -> UserScriptProposalCard(item, sessionId, nav)
        is ChatItem.LlmRecovery -> LlmRecoveryCard(item, sessionId, nav)
        is ChatItem.ConnectorEditProposal -> ConnectorEditProposalCard(item, sessionId)
        is ChatItem.Question -> QuestionCard(item, sessionId)
        is ChatItem.SecureForm -> SecureFormCard(item, sessionId)
        is ChatItem.Approval -> ApprovalCard(item, sessionId)
        is ChatItem.SectionSetup -> SectionSetupCard(item, sessionId, nav)
    }
}

@Composable
internal fun UserBubble(i: ChatItem.User) {
    val c = Fluwy.c
    Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 10.dp), horizontalAlignment = Alignment.End) {
        i.replyTo?.takeIf { it.isNotBlank() }?.let { quoted ->
            Row(Modifier.widthIn(max = 320.dp).padding(bottom = 6.dp).clip(RoundedCornerShape(12.dp)).background(c.surface2).padding(start = 8.dp, end = 10.dp, top = 7.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(3.dp).height(36.dp).clip(RoundedCornerShape(2.dp)).background(c.accent))
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("Respondendo ao agente", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = c.ink2)
                    Text(quoted, fontSize = 12.sp, color = c.ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (i.attachments.isNotEmpty()) {
            Row(Modifier.padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                i.attachments.forEach { a ->
                    Row(Modifier.clip(RoundedCornerShape(12.dp)).border(1.dp, c.line, RoundedCornerShape(12.dp)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Description, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(a, fontSize = 12.sp)
                    }
                }
            }
        }
        SelectionContainer {
            // Menções (@skill, @conector, @agent) destacadas também na mensagem enviada.
            val targets = remember(i.text) { if ('@' in i.text) runCatching { dev.fluwy.app.FluwyApp.instance.agent.mentionTargets() }.getOrDefault(emptyList()) else emptyList() }
            val shown = remember(i.text, targets) {
                androidx.compose.ui.text.buildAnnotatedString {
                    append(i.text)
                    dev.fluwy.app.agent.Mentions.find(i.text, targets).forEach { h ->
                        addStyle(androidx.compose.ui.text.SpanStyle(color = Color.White, background = Color(0xFF246BCE), fontWeight = FontWeight.SemiBold), h.start, h.end)
                    }
                }
            }
            // Mensagem longa: mostra o começo e um "Ver mais"/"Ver menos" dentro do próprio card.
            val long = remember(i.text) { i.text.length > 700 || i.text.count { it == '\n' } > 12 }
            var expanded by remember(i.id) { mutableStateOf(false) }
            Column(Modifier.widthIn(max = 320.dp).clip(RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp)).background(c.bubble).padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(shown, style = MaterialTheme.typography.bodyLarge,
                    maxLines = if (long && !expanded) 10 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
                if (long) Text(if (expanded) "Ver menos" else "Ver mais", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.accent,
                    modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(8.dp)).clickable { expanded = !expanded }.padding(vertical = 2.dp))
            }
        }
    }
}

@Composable
internal fun AssistantText(i: ChatItem.Assistant, onSend: ((String) -> Unit)? = null, onReply: ((ChatItem.Assistant) -> Unit)? = null) {
    val c = Fluwy.c
    var dragX by remember(i.id) { mutableStateOf(0f) }
    val density = LocalDensity.current
    val thresholdPx = with(density) { 72.dp.toPx() }
    val maxOffsetPx = with(density) { 112.dp.toPx() }
    val dragModifier = if (onReply != null) Modifier.pointerInput(i.id, onReply) {
        detectHorizontalDragGestures(
            onHorizontalDrag = { change, amount ->
                if (amount > 0f || dragX > 0f) dragX = (dragX + amount).coerceIn(0f, maxOffsetPx)
                change.consume()
            },
            onDragEnd = { if (dragX >= thresholdPx) onReply(i); dragX = 0f },
            onDragCancel = { dragX = 0f },
        )
    } else Modifier
    Box(Modifier.fillMaxWidth()) {
        if (dragX > 4f) Icon(Icons.Rounded.Reply, "Responder", Modifier.align(Alignment.CenterStart).padding(start = 8.dp).size(20.dp), tint = c.accent)
        Column(Modifier.fillMaxWidth().then(dragModifier).graphicsLayer { translationX = dragX }) {
            if (i.final) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                val persona = LocalChatPersona.current
                AgentFace(persona.color, persona.face, 20.dp); Spacer(Modifier.width(6.dp))
                Text(persona.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
            FluwyMarkdown(i.text, onSend = onSend)
        }
    }
}

/** Markdown leve: títulos, listas, **negrito**, `código` e blocos ``` . */
@Composable
fun Markdown(text: String) = FluwyMarkdown(text)

@Composable
internal fun LegacyMarkdown(text: String) {
    val c = Fluwy.c
    val blocks = remember(text) { text.split("```") }
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            blocks.forEachIndexed { idx, b ->
                if (idx % 2 == 1) {
                    val code = b.substringAfter('\n', b).trimEnd()
                    Text(code, Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface2).horizontalScroll(rememberScrollState()).padding(12.dp),
                        fontFamily = FontFamily.Monospace, fontSize = 13.sp, softWrap = false)
                } else {
                    b.trim('\n').lines().forEach { line ->
                        when {
                            line.startsWith("### ") -> Text(mdInline(line.drop(4)), style = MaterialTheme.typography.titleMedium)
                            line.startsWith("## ") -> Text(mdInline(line.drop(3)), style = MaterialTheme.typography.titleLarge)
                            line.startsWith("# ") -> Text(mdInline(line.drop(2)), style = MaterialTheme.typography.headlineSmall)
                            line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") ->
                                Row { Text("•  ", color = c.ink2); Text(mdInline(line.trimStart().drop(2)), style = MaterialTheme.typography.bodyLarge) }
                            line.isBlank() -> Spacer(Modifier.height(2.dp))
                            else -> Text(mdInline(line), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun mdInline(s: String): AnnotatedString {
    val c = Fluwy.c
    return buildAnnotatedString {
        var i = 0
        while (i < s.length) {
            when {
                s.startsWith("**", i) && s.indexOf("**", i + 2) > 0 -> { val e = s.indexOf("**", i + 2); withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(s.substring(i + 2, e)) }; i = e + 2 }
                s[i] == '`' && s.indexOf('`', i + 1) > 0 -> { val e = s.indexOf('`', i + 1); withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = c.surface2)) { append(s.substring(i + 1, e)) }; i = e + 1 }
                else -> { append(s[i]); i++ }
            }
        }
    }
}

fun toolIcon(tool: String): ImageVector = when {
    tool == "shell" -> Icons.Rounded.Terminal
    tool.startsWith("browser") -> Icons.Rounded.Language
    tool.startsWith("file") -> Icons.Rounded.EditNote
    tool == "remember" -> Icons.Rounded.Psychology
    tool == "create_skill" -> Icons.Rounded.AutoAwesome
    tool == "multi_agent_worker" -> Icons.Rounded.CheckCircle
    tool == "clear" -> Icons.Rounded.CleaningServices
    tool == "compact" -> Icons.Rounded.Compress
    tool == "autocompact" -> Icons.Rounded.AutoMode
    else -> Icons.Rounded.Bolt
}

@Composable
internal fun StepRow(s: ChatItem.Step, onOpenComputer: (String) -> Unit) {
    val c = Fluwy.c
    // Só há expansão quando existe conteúdo útil. Comandos sem saída (ex.: mkdir) não viram pílulas clicáveis.
    val hasUsefulOutput = s.result.isNotBlank() && (
        s.tool in setOf("file_write", "file_edit", "file_read") ||
        s.status == StepStatus.ERROR ||
        s.result.lineSequence().any { it.trim().isNotEmpty() && !it.trim().matches(Regex("\\[exit \\d+\\]")) }
    )
    val canExpand = hasUsefulOutput
    var open by remember(s.id) { mutableStateOf(false) }
    val filePreview = remember(s.result, s.tool) {
        if (s.tool == "file_write" || s.tool == "file_edit") s.result.lineSequence().take(10).joinToString("\n")
        else s.result.takeLast(2500)
    }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.clip(RoundedCornerShape(14.dp)).then(if (canExpand) Modifier.clickable { open = !open } else Modifier).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(26.dp).clip(CircleShape).background(c.surface2), contentAlignment = Alignment.Center) {
                when (s.status) {
                    StepStatus.RUNNING -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = c.accent)
                    StepStatus.ERROR -> Icon(Icons.Rounded.Close, null, Modifier.size(14.dp), tint = c.err)
                    StepStatus.OK -> Icon(toolIcon(s.tool), null, Modifier.size(14.dp), tint = c.ink2)
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(s.title, fontSize = 14.sp, color = c.ink2)
            if (s.args.isNotBlank()) {
                Spacer(Modifier.width(6.dp))
                Text(s.args, Modifier.weight(1f, fill = false).clip(RoundedCornerShape(8.dp)).background(c.surface2).padding(horizontal = 8.dp, vertical = 3.dp),
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        AnimatedVisibility(canExpand && open) {
            Column(Modifier.padding(start = 34.dp, top = 4.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (s.tool == "shell") Color(0xFF0F1115) else c.surface2).padding(10.dp)) {
                if (s.tool == "file_write" || s.tool == "file_edit") {
                    val lang = s.args.substringAfterLast('.').substringBefore(' ').lowercase()
                    val palette = if (c.dark) SyntaxPalette.dark else SyntaxPalette.light
                    val preview = remember(filePreview, lang, c.dark) { Syntax.highlight(filePreview, lang, palette) }
                    Text(preview, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp, color = c.ink2)
                    Text("Prévia: até 10 linhas", fontSize = 10.sp, color = c.ink3)
                } else {
                    Text(s.result.takeLast(2500), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = if (s.tool == "shell") Color(0xFFE6E6E6) else c.ink2)
                }
                TextButton({ onOpenComputer(if (s.tool.startsWith("browser")) "browser" else if (s.tool.startsWith("file")) "files" else "terminal") }) {
                    Text("Ver no computador", color = c.accent)
                }
            }
        }
    }
}

@Composable
internal fun PlanCard(p: ChatItem.Plan) {
    val c = Fluwy.c
    var open by remember { mutableStateOf(true) }
    val done = p.phases.count { it.done }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).border(1.dp, c.line, RoundedCornerShape(18.dp)).background(c.surface).padding(14.dp)) {
        Row(Modifier.clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Checklist, null, tint = c.accent); Spacer(Modifier.width(8.dp))
            Text(p.goal, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = if (open) 3 else 1, overflow = TextOverflow.Ellipsis)
            Text("$done/${p.phases.size}", color = c.ink3, fontSize = 13.sp)
            Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = c.ink3)
        }
        LinearProgressIndicator(progress = { if (p.phases.isEmpty()) 0f else done / p.phases.size.toFloat() },
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp).height(4.dp).clip(CircleShape), color = c.accent, trackColor = c.surface2)
        AnimatedVisibility(open) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                p.phases.forEachIndexed { i, ph ->
                    val current = !ph.done && p.phases.take(i).all { it.done }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(when { ph.done -> Icons.Rounded.CheckCircle; current -> Icons.Rounded.RadioButtonChecked; else -> Icons.Rounded.RadioButtonUnchecked },
                            null, Modifier.size(18.dp), tint = when { ph.done -> c.ok; current -> c.accent; else -> c.ink3 })
                        Spacer(Modifier.width(8.dp))
                        Text(ph.title, fontSize = 14.sp, color = if (ph.done) c.ink3 else c.ink)
                    }
                }
            }
        }
    }
}

/** Quem está respondendo neste chat: o Agent escolhido (cor + forma do mascote) ou o Fluwy Bot padrão. */
data class ChatPersona(val color: Color, val face: String, val name: String) {
    companion object {
        val DEFAULT = ChatPersona(Color(0xFFFF6B3D), "wink", "Fluwy Bot")
        fun of(agent: dev.fluwy.app.data.AgentProfile?): ChatPersona =
            if (agent == null) DEFAULT else ChatPersona(Color(agent.color), agent.face, agent.name.ifBlank { DEFAULT.name })
    }
}

val LocalChatPersona = androidx.compose.runtime.staticCompositionLocalOf { ChatPersona.DEFAULT }

/** "Pensando…": o mascote é o MESMO do Agent escolhido (igual ao do topo do chat), respirando e balançando. */
@Composable
internal fun ThinkingRow(a: Activity) {
    val c = Fluwy.c
    val persona = LocalChatPersona.current
    val t = rememberInfiniteTransition(label = "t")
    val alpha by t.animateFloat(0.45f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a")
    val bob by t.animateFloat(0f, -3f, infiniteRepeatable(tween(520, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "b")
    Row(verticalAlignment = Alignment.CenterVertically) {
        AnimatedAgentFace(persona.color, persona.face, 22.dp, Modifier.offset(y = bob.dp).alpha(alpha), state = dev.fluwy.app.ui.mascot.MascotState.THINKING); Spacer(Modifier.width(8.dp))
        Text(when (a.kind) { Activity.Kind.THINKING, Activity.Kind.IDLE -> "Pensando…"; else -> "Trabalhando…" }, color = c.ink2, fontSize = 14.sp)
    }
}

@Composable
internal fun InfoRow(icon: ImageVector, text: String, tint: Color = Fluwy.c.ink2) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Fluwy.c.surface2).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 14.sp, color = tint)
    }
}

@Composable
internal fun DoneRow(onCopy: () -> Unit, onShare: () -> Unit) {
    val c = Fluwy.c
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(Icons.Rounded.ContentCopy to onCopy, Icons.Rounded.IosShare to onShare).forEach { (ic, fn) ->
            Box(Modifier.size(30.dp).clip(CircleShape).clickable(onClick = fn), contentAlignment = Alignment.Center) {
                Icon(ic, null, Modifier.size(16.dp), tint = c.ink3)
            }
        }
    }
}

fun lastAnswer(s: Session): String =
    (s.items.lastOrNull { it is ChatItem.Assistant } as? ChatItem.Assistant)?.text ?: sessionMarkdown(s)

fun copyText(ctx: android.content.Context, text: String) {
    val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
    cm.setPrimaryClip(android.content.ClipData.newPlainText("Fluwy Bot", text))
    android.widget.Toast.makeText(ctx, "Copiado", android.widget.Toast.LENGTH_SHORT).show()
}

/** Compartilha SOMENTE o texto da resposta do agente (Markdown), sem anexar arquivos. */
fun shareText(ctx: android.content.Context, text: String) {
    val i = android.content.Intent(android.content.Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(android.content.Intent.EXTRA_TEXT, text)
    ctx.startActivity(android.content.Intent.createChooser(i, "Compartilhar resposta"))
}

/**
 * Card "Computador do agente" acima do composer (como no Fluwy). Mostra também as tarefas do plano,
 * sempre atualizadas (o agente marca cada fase com complete_step).
 */
@Composable
internal fun LiveComputerCard(a: Activity, name: String, plan: ChatItem.Plan?, active: Boolean, onClick: () -> Unit) {
    val c = Fluwy.c
    var expanded by remember { mutableStateOf(false) }
    val label = when (a.kind) {
        Activity.Kind.TERMINAL -> "Usando Terminal"; Activity.Kind.BROWSER -> "Usando Browser"
        Activity.Kind.EDITOR -> "Usando Editor"; Activity.Kind.SEARCH -> "Pesquisando"
        Activity.Kind.IDLE -> if (active) "Pensando" else "Pausado"; else -> "Pensando"
    }
    val icon = when (a.kind) { Activity.Kind.TERMINAL -> Icons.Rounded.Terminal; Activity.Kind.BROWSER -> Icons.Rounded.Language; Activity.Kind.EDITOR -> Icons.Rounded.EditNote; else -> Icons.Rounded.Computer }
    Column(Modifier.padding(horizontal = 12.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(18.dp))) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 56.dp, height = 40.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF0F1115)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = Color(0xFFFF8A63), modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Computador de $name", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text("$label${if (a.detail.isNotBlank()) " · " + a.detail else ""}", fontSize = 12.sp, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Rounded.OpenInFull, null, tint = c.ink3, modifier = Modifier.size(18.dp))
        }
        if (plan != null && plan.phases.isNotEmpty()) {
            val done = plan.phases.count { it.done }
            val current = plan.phases.indexOfFirst { !it.done }
            HorizontalDivider(color = c.line)
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Checklist, null, Modifier.size(18.dp), tint = c.accent); Spacer(Modifier.width(8.dp))
                Text(if (current >= 0) plan.phases[current].title else "Tudo concluído", Modifier.weight(1f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("$done/${plan.phases.size}", fontSize = 12.sp, color = c.ink3)
                Icon(if (expanded) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess, null, tint = c.ink3)
            }
            LinearProgressIndicator(progress = { done / plan.phases.size.toFloat() }, Modifier.fillMaxWidth().height(3.dp), color = c.accent, trackColor = c.surface2)
            AnimatedVisibility(expanded) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    plan.phases.forEachIndexed { i, ph ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(when { ph.done -> Icons.Rounded.CheckCircle; i == current -> Icons.Rounded.RadioButtonChecked; else -> Icons.Rounded.RadioButtonUnchecked },
                                null, Modifier.size(16.dp), tint = when { ph.done -> c.ok; i == current -> c.accent; else -> c.ink3 })
                            Spacer(Modifier.width(8.dp))
                            Text(ph.title, fontSize = 13.sp, color = if (ph.done) c.ink3 else c.ink)
                        }
                    }
                }
            }
        }
    }
}

/** "Pílula" de mídia no chat: miniatura de imagem/SVG ou cartão do arquivo; toque abre o visualizador. */
@Composable
internal fun MediaCard(m: ChatItem.Media, onOpen: (String) -> Unit) {
    val c = Fluwy.c
    val f = remember(m.path) { java.io.File(m.path) }
    val ext = f.extension.lowercase()
    val thumb by produceState<android.graphics.Bitmap?>(null, m.path) { value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { loadThumb(f, 900) } }
    Column(Modifier.widthIn(max = 340.dp).clip(RoundedCornerShape(18.dp)).border(1.dp, c.line, RoundedCornerShape(18.dp)).background(c.surface).clickable { onOpen(m.path) }) {
        val t = thumb
        if (t != null) androidx.compose.foundation.Image(t.asImageBitmap(), m.caption, Modifier.fillMaxWidth().heightIn(max = 260.dp).background(if (ext == "svg") Color.White else c.surface2),
            contentScale = androidx.compose.ui.layout.ContentScale.Fit)
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(fileIcon(f), null, Modifier.size(18.dp), tint = c.accent); Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(m.caption.ifBlank { f.name }, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${f.name} · ${if (f.exists()) humanSize(f.length()) else "removido"}", fontSize = 11.sp, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Rounded.OpenInFull, null, Modifier.size(16.dp), tint = c.ink3)
        }
    }
}

fun loadThumb(f: java.io.File, max: Int): android.graphics.Bitmap? = runCatching {
    when (f.extension.lowercase()) {
        "png", "jpg", "jpeg", "webp", "gif", "bmp" -> {
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(f.absolutePath, o)
            var s = 1; while (o.outWidth / s > max || o.outHeight / s > max) s *= 2
            android.graphics.BitmapFactory.decodeFile(f.absolutePath, android.graphics.BitmapFactory.Options().apply { inSampleSize = s })
        }
        "svg" -> {
            val svg = com.caverock.androidsvg.SVG.getFromString(f.readText())
            val w = svg.documentWidth.takeIf { it > 0 } ?: 512f; val h = svg.documentHeight.takeIf { it > 0 } ?: 512f
            val k = max / maxOf(w, h)
            svg.setDocumentWidth("100%"); svg.setDocumentHeight("100%")
            android.graphics.Bitmap.createBitmap((w * k).toInt().coerceAtLeast(1), (h * k).toInt().coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888).also {
                svg.renderToCanvas(android.graphics.Canvas(it), android.graphics.RectF(0f, 0f, it.width.toFloat(), it.height.toFloat()))
            }
        }
        else -> null
    }
}.getOrNull()

@Composable
internal fun MediaDialog(f: java.io.File, onClose: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Fluwy.c.bg)) { FileViewer(f, onClose = onClose, onEdit = onClose) }
    }
}

fun sessionMarkdown(s: Session): String = buildString {
    appendLine("# ${s.title}\n")
    s.items.forEach { i ->
        when (i) {
            is ChatItem.User -> {
                i.replyTo?.let { appendLine("> Mensagem do agente citada:\n> ${it.lines().joinToString("\n> ")}\n") }
                appendLine("**Você:** ${i.text}\n")
            }
            is ChatItem.Assistant -> appendLine("**Fluwy Bot:** ${i.text}\n")
            is ChatItem.Step -> appendLine("> ${i.title}: `${i.args}`")
            is ChatItem.Plan -> { appendLine("**Plano:** ${i.goal}"); i.phases.forEach { appendLine("- [${if (it.done) "x" else " "}] ${it.title}") }; appendLine() }
            is ChatItem.Error -> appendLine("⚠️ ${i.text}\n")
            is ChatItem.LlmRecovery -> appendLine("⚠ Falha do modelo ${i.model} (${i.category}) — ${i.state}\n")
            is ChatItem.UserScriptProposal -> appendLine("📜 User Script ${i.name} ${i.version}${if (i.installed) " — instalado" else ""}\n")
            is ChatItem.ConnectorProposal -> appendLine("🧩 Conector ${i.name} (${i.host})${if (i.installed) " — instalado" else ""}\n")
            is ChatItem.ConnectorEditProposal -> appendLine("⚙️ Atualização de ${i.connectorName}: ${i.summary}${if (i.applied) " — aplicada" else " — pendente"}\n")
            is ChatItem.Question -> { i.questions.forEachIndexed { n, q -> appendLine("❓ ${q.question} → ${i.answers.getOrNull(n)?.joinToString().orEmpty().ifBlank { "—" }}") }; appendLine() }
            is ChatItem.SecureForm -> appendLine("🔒 ${i.title}: ${if (i.state == "submitted") "enviado com segurança (" + i.submitted.joinToString() + ")" else i.state}\n")
            is ChatItem.Media -> appendLine("🖼 ${i.caption} (${java.io.File(i.path).name})\n")
            is ChatItem.Cards -> { appendLine("🗂 ${i.title.ifBlank { i.kind }}"); i.items.forEachIndexed { n, e -> appendLine("${n + 1}. ${e.title}${if (e.price.isNotBlank()) " — " + e.price else ""}${if (e.url.isNotBlank()) " — " + e.url else ""}${if (n == i.selected) " ✅" else ""}") }; appendLine() }
            is ChatItem.Audio -> appendLine("🔊 ${i.text.take(300)}\n")
            // Exportação nunca leva o código Pix (contém a chave): só o resumo.
            is ChatItem.PixQr -> appendLine("💠 Pix · ${i.keyLabel} · ${if (i.cents > 0) dev.fluwy.app.vault.PixRules.money(i.cents) else "valor livre"}\n")
            is ChatItem.Approval -> appendLine("🛡 Confirmação: ${i.summary} — ${i.state}\n")
            is ChatItem.SectionSetup -> appendLine("📁 Seção ${i.sectionName} (${i.agentNames.joinToString()}) — ${i.state}\n")
        }
    }
    appendLine("\n— gerado com Fluwy Bot")
}

/** Compartilhar = exporta a tarefa (replay em Markdown) + arquivos via share sheet do Android. */
fun shareSession(ctx: android.content.Context, s: Session) {
    val app = FluwyApp.instance
    val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
    val md = File(dir, "${s.title.replace(Regex("[^\\w\\-]+"), "_").take(40)}.md").apply { writeText(sessionMarkdown(s)) }
    val uris = arrayListOf(FileProvider.getUriForFile(ctx, ctx.packageName + ".files", md))
    app.sandbox.workspaceFor(s.id).walkTopDown().filter { it.isFile && it.length() < 20_000_000 }.take(10).forEach {
        uris.add(FileProvider.getUriForFile(ctx, ctx.packageName + ".files", it))
    }
    val i = Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        .putExtra(Intent.EXTRA_TEXT, sessionMarkdown(s).take(4000)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(i, "Compartilhar tarefa"))
}

/** Falha do provedor de modelos: o que houve, o que o app vai fazer e ações que funcionam de verdade. */
@Composable
internal fun LlmRecoveryCard(p: ChatItem.LlmRecovery, sessionId: String?, nav: NavController?) {
    val c = Fluwy.c
    val app = FluwyApp.instance
    val fmt = remember { java.text.SimpleDateFormat("HH:mm", java.util.Locale.ROOT) }
    if (p.state != "waiting") {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface2).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text((if (p.state == "resolved") "✓ Modelo voltou a responder" else "Execução cancelada") + " · ${p.category} às ${fmt.format(java.util.Date(p.time))}", fontSize = 12.sp, color = c.ink2)
        }
        return
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(20.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("⚠ " + p.category.replaceFirstChar { it.uppercase() }, fontWeight = FontWeight.SemiBold, color = c.ink, fontSize = 16.sp)
        Text("Provedor: ${p.provider}\nModelo: ${p.model}\nFalha às ${fmt.format(java.util.Date(p.time))} · ${p.attempts} tentativa(s)" +
            (if (p.round > 0) " · retomada ${p.round}/${dev.fluwy.app.llm.LlmRecovery.AUTO_RESUME_ROUNDS}" else ""), fontSize = 12.sp, color = c.ink2, lineHeight = 17.sp)
        Text(p.advice, fontSize = 13.sp, color = c.ink)
        if (p.detail.isNotBlank()) {
            var showDetail by remember(p.time) { mutableStateOf(false) }
            Text(if (showDetail) "Ocultar detalhes técnicos" else "Detalhes técnicos", fontSize = 12.sp, color = c.ink3,
                modifier = Modifier.clickable { showDetail = !showDetail })
            if (showDetail) Text(p.detail, fontSize = 12.sp, color = c.ink3, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
        }
        Text(when {
            p.resumeAt != null -> "Estado: suspenso. O app tenta de novo sozinho às ${fmt.format(java.util.Date(p.resumeAt))} e continua de onde parou."
            p.permanent -> "Estado: aguardando você. Este erro não se resolve sozinho."
            else -> "Estado: aguardando você. As retomadas automáticas se esgotaram."
        }, fontSize = 12.sp, color = if (p.resumeAt != null) c.ok else c.accent)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton({ sessionId?.let { app.agent.retryAfterLlmFailure(it) } }) { Text("Tentar agora") }
            TextButton({ nav?.navigate("settings") }) { Text("Trocar modelo/provedor") }
            TextButton({ sessionId?.let { app.agent.cancelLlmRecovery(it) } }) { Text("Cancelar", color = c.accent) }
        }
    }
}

/** Card "Instalar no Browser" de um userscript criado e testado pela IA. */
@Composable
internal fun UserScriptProposalCard(p: ChatItem.UserScriptProposal, sessionId: String?, nav: NavController?) {
    val c = Fluwy.c
    val app = FluwyApp.instance
    val scope = rememberCoroutineScope()
    var busy by remember(p.id) { mutableStateOf(false) }
    var error by remember(p.id) { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(20.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("📜 " + p.name + if (p.version.isNotBlank()) " · v" + p.version else "", fontWeight = FontWeight.SemiBold, color = c.ink, fontSize = 16.sp)
        if (p.description.isNotBlank()) Text(p.description, fontSize = 13.sp, color = c.ink2)
        Text("Roda em: " + p.where, fontSize = 12.sp, color = c.ink3)
        error?.let { Text(it, fontSize = 12.sp, color = c.accent) }
        if (p.installed) Row(verticalAlignment = Alignment.CenterVertically) {
            Text("✓ Instalado no Browser", color = c.ok, fontSize = 13.sp, modifier = Modifier.weight(1f))
            TextButton({ nav?.navigate("browserhub") }) { Text("Abrir Browser") }
        } else Button(enabled = !busy && sessionId != null, onClick = {
            busy = true
            scope.launch {
                val r = dev.fluwy.app.browser.UserScriptInstaller.install(p.code, "ia")
                busy = false
                if (r.script == null) error = r.error
                else sessionId?.let { sid -> app.store.replaceItem(sid, p.copy(installed = true, scriptId = r.script.id)) }
            }
        }) { Text(if (busy) "Instalando…" else "Instalar no Browser") }
    }
}
