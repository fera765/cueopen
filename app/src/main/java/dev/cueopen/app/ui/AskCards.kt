package dev.cueopen.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.focus.focusRequester
import dev.cueopen.app.CueApp
import dev.cueopen.app.agent.AskTools
import dev.cueopen.app.data.ChatItem
import dev.cueopen.app.ui.theme.Cue

/**
 * Card do `ask_question`: uma pergunta por página ("1 de 3"), opções com caixa de seleção
 * (ou única escolha), "Outra resposta" com texto livre, Pular e enviar. Depois de respondido,
 * vira um resumo só-leitura.
 */
@Composable
fun QuestionCard(item: ChatItem.Question, sessionId: String?) {
    val c = Cue.c
    val total = item.questions.size
    if (item.state != "pending" || sessionId == null) { AnsweredQuestions(item); return }
    var page by remember(item.id) { mutableIntStateOf(0) }
    val picked = remember(item.id) { mutableStateListOf<MutableSet<String>>().apply { repeat(total) { add(mutableSetOf()) } } }
    val other = remember(item.id) { mutableStateListOf<String>().apply { repeat(total) { add("") } } }
    val otherOn = remember(item.id) { mutableStateListOf<Boolean>().apply { repeat(total) { add(false) } } }
    var tick by remember(item.id) { mutableIntStateOf(0) } // força recomposição ao trocar seleção
    val q = item.questions[page]

    fun answersNow(): List<List<String>> = item.questions.indices.map { i ->
        picked[i].toList() + (if (otherOn[i] && other[i].isNotBlank()) listOf(other[i].trim()) else emptyList())
    }
    fun submit() = CueApp.instance.agent.answerQuestion(sessionId, item, answersNow())
    fun next() { if (page < total - 1) page++ else submit() }

    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(20.dp))) {
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.Top) {
            Text(q.question, Modifier.weight(1f).padding(top = 6.dp), fontFamily = FontFamily.Serif, fontSize = 18.sp, lineHeight = 24.sp, color = c.ink)
            if (total > 1) Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton({ if (page > 0) page-- }, Modifier.size(32.dp), enabled = page > 0) { Icon(Icons.Rounded.ChevronLeft, "Anterior", tint = c.ink3) }
                Text("${page + 1} de $total", fontSize = 13.sp, color = c.ink3)
                IconButton({ if (page < total - 1) page++ }, Modifier.size(32.dp), enabled = page < total - 1) { Icon(Icons.Rounded.ChevronRight, "Próxima", tint = c.ink3) }
            }
            IconButton({ CueApp.instance.agent.dismissQuestion(sessionId, item) }, Modifier.size(36.dp)) { Icon(Icons.Rounded.Close, "Fechar", tint = c.ink2) }
        }
        if (q.multiSelect) Text("Escolha uma ou mais", Modifier.padding(horizontal = 18.dp), fontSize = 12.sp, color = c.ink3)
        Spacer(Modifier.height(6.dp))
        val sel = picked[page]
        if (tick < 0) return@Column // lê o contador: seleções em conjuntos mutáveis recompõem a tela
        q.options.forEachIndexed { idx, opt ->
            val on = opt.label in sel
            OptionRow(opt.label, opt.description, on, q.multiSelect) {
                if (q.multiSelect) { if (on) sel.remove(opt.label) else sel.add(opt.label) }
                else { sel.clear(); otherOn[page] = false; if (!on) sel.add(opt.label) }
                tick++
            }
            if (idx < q.options.lastIndex || q.allowOther) HorizontalDivider(Modifier.padding(horizontal = 18.dp), color = c.line)
        }
        if (q.allowOther) {
            OptionRow("Outra resposta", "", otherOn[page], q.multiSelect, muted = !otherOn[page]) {
                otherOn[page] = !otherOn[page]
                if (!q.multiSelect && otherOn[page]) sel.clear()
                tick++
            }
            // Marcou "Outra resposta": o campo já ganha foco e o teclado abre para digitar.
            val otherFocus = remember(item.id, page) { androidx.compose.ui.focus.FocusRequester() }
            val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
            LaunchedEffect(otherOn[page], page) {
                if (otherOn[page]) { kotlinx.coroutines.delay(80); runCatching { otherFocus.requestFocus() }; keyboard?.show() }
            }
            if (otherOn[page]) OutlinedTextField(other[page], { other[page] = it }, Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp).focusRequester(otherFocus),
                placeholder = { Text("Escreva sua resposta") }, singleLine = false, maxLines = 4, shape = RoundedCornerShape(12.dp))
        }
        HorizontalDivider(Modifier.padding(top = 8.dp), color = c.line)
        val count = sel.size + (if (otherOn[page] && other[page].isNotBlank()) 1 else 0)
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (count == 1) "1 selecionada" else "$count selecionadas", Modifier.weight(1f), fontSize = 13.sp, color = c.ink3)
            OutlinedButton({ sel.clear(); otherOn[page] = false; tick++; next() }, shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(horizontal = 16.dp)) { Text("Pular", color = c.ink) }
            Spacer(Modifier.width(8.dp))
            val bg by animateColorAsState(if (count > 0) c.ink else c.line, label = "send")
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(bg).clickable(enabled = count > 0) { next() }, contentAlignment = Alignment.Center) {
                Icon(if (page < total - 1) Icons.AutoMirrored.Rounded.ArrowForward else Icons.Rounded.ArrowUpward, if (page < total - 1) "Próxima" else "Enviar", tint = c.bg, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun OptionRow(label: String, description: String, on: Boolean, multi: Boolean, muted: Boolean = false, onClick: () -> Unit) {
    val c = Cue.c
    val bg by animateColorAsState(if (on) c.surface2 else Color.Transparent, label = "opt")
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp).clip(RoundedCornerShape(14.dp)).background(bg).clickable(onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        val shape = if (multi) RoundedCornerShape(6.dp) else CircleShape
        Box(Modifier.size(22.dp).clip(shape).background(if (on) c.ink else Color.Transparent).border(1.5.dp, if (on) c.ink else c.ink3, shape), contentAlignment = Alignment.Center) {
            if (on) Icon(if (multi) Icons.Rounded.Check else Icons.Rounded.Circle, null, tint = c.bg, modifier = Modifier.size(if (multi) 16.dp else 8.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 15.sp, color = if (muted) c.ink3 else c.ink)
            if (description.isNotBlank()) Text(description, fontSize = 12.sp, color = c.ink3)
        }
    }
}

@Composable
private fun AnsweredQuestions(item: ChatItem.Question) {
    val c = Cue.c
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface2).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (item.state == "answered") Icons.Rounded.CheckCircle else Icons.Rounded.DoNotDisturbOn, null, Modifier.size(16.dp), tint = if (item.state == "answered") c.ok else c.ink3)
            Spacer(Modifier.width(6.dp))
            Text(if (item.state == "answered") "Respostas enviadas" else if (item.state == "dismissed") "Perguntas fechadas" else "Pergunta", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.ink2)
        }
        item.questions.forEachIndexed { i, q ->
            Text(q.question, fontSize = 13.sp, color = c.ink2)
            Text("→ " + item.answers.getOrNull(i).orEmpty().ifEmpty { listOf("—") }.joinToString(", "), fontSize = 14.sp, color = c.ink)
        }
    }
}

/**
 * Card do `ask_secure`: formulário cujos valores vão DIRETO para o cofre do aparelho
 * ([dev.cueopen.app.agent.SecretVault]). Nada do que é digitado entra no histórico nem chega à IA.
 */
@Composable
fun SecureFormCard(item: ChatItem.SecureForm, sessionId: String?) {
    val c = Cue.c
    if (item.state != "pending" || sessionId == null) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface2).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (item.state == "submitted") Icons.Rounded.Lock else Icons.Rounded.LockOpen, null, Modifier.size(18.dp), tint = if (item.state == "submitted") c.ok else c.ink3)
            Spacer(Modifier.width(8.dp))
            Text(if (item.state == "submitted") "${item.title}: enviado com segurança · ${item.submitted.joinToString()}" else "${item.title}: cancelado", fontSize = 13.sp, color = c.ink2)
        }
        return
    }
    // Valores só na memória da tela (não usa rememberSaveable para não irem ao estado salvo do Android).
    val values = remember(item.id) { mutableStateMapOf<String, String>() }
    val visible = remember(item.id) { mutableStateMapOf<String, Boolean>() }
    var sending by remember(item.id) { mutableStateOf(false) }
    val missing = item.fields.filter { it.required && values[it.name].isNullOrBlank() }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(20.dp)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(c.accentSoft), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Lock, null, Modifier.size(18.dp), tint = c.accent) }
            Spacer(Modifier.width(10.dp))
            Text(item.title, Modifier.weight(1f), fontFamily = FontFamily.Serif, fontSize = 18.sp, color = c.ink)
            IconButton({ CueApp.instance.agent.cancelSecure(sessionId, item) }, Modifier.size(36.dp)) { Icon(Icons.Rounded.Close, "Cancelar", tint = c.ink2) }
        }
        if (!item.forAgentName.isNullOrBlank()) Text("Pedido por ${item.forAgentName} · fica disponível para todos os agentes da seção", fontSize = 12.sp, color = c.accent)
        if (item.reason.isNotBlank()) Text(item.reason, fontSize = 13.sp, color = c.ink2)
        item.fields.forEach { f ->
            val masked = f.type in AskTools.MASKED
            val show = visible[f.name] == true
            OutlinedTextField(
                value = values[f.name].orEmpty(), onValueChange = { values[f.name] = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(f.label + if (f.required) "" else " (opcional)") },
                placeholder = { if (f.hint.isNotBlank()) Text(f.hint) },
                singleLine = true, shape = RoundedCornerShape(12.dp),
                visualTransformation = if (masked && !show) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = when (f.type) { "email" -> KeyboardType.Email; "number" -> KeyboardType.Number; "url" -> KeyboardType.Uri; "text" -> KeyboardType.Text; else -> KeyboardType.Password },
                    autoCorrectEnabled = false,
                ),
                trailingIcon = if (masked) ({ IconButton({ visible[f.name] = !show }) { Icon(if (show) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (show) "Ocultar" else "Mostrar") } }) else null,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Shield, null, Modifier.size(14.dp), tint = c.ok); Spacer(Modifier.width(6.dp))
            Text("Fica só neste aparelho. A IA nunca vê esses valores — só sabe que eles existem.", fontSize = 11.sp, color = c.ink3)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ CueApp.instance.agent.cancelSecure(sessionId, item) }, Modifier.weight(1f).height(46.dp), shape = RoundedCornerShape(12.dp)) { Text("Cancelar", color = c.ink) }
            Button({
                sending = true
                val filled = values.filterValues { it.isNotBlank() }.toMap()
                CueApp.instance.agent.submitSecure(sessionId, item, filled)
                values.clear()
            }, Modifier.weight(1.4f).height(46.dp), enabled = missing.isEmpty() && !sending, shape = RoundedCornerShape(12.dp)) {
                Icon(Icons.Rounded.Lock, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Enviar com segurança", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
