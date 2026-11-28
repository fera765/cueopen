package dev.fluwy.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.fluwy.app.ui.theme.Fluwy
import dev.fluwy.app.workflow.*

// Painéis do nó no estilo n8n: ENTRADA (itens recebidos), SAÍDA (itens produzidos, teste, pin, executar até aqui) e Configurações.

/** Lista de itens (até 20), em leitura clara ou JSON. */
@Composable
internal fun ItemsView(text: String, count: Int) {
    val c = Fluwy.c
    var raw by remember(text) { mutableStateOf(false) }
    val items = remember(text, count) { WfItems.fromStep(text, count) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (items.size == 1) "1 item" else "${items.size} itens", fontSize = 12.sp, color = c.ink2, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        TextButton({ raw = !raw }) { Text(if (raw) "Leitura clara" else "JSON", fontSize = 12.sp) }
    }
    items.take(20).forEachIndexed { i, it ->
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface2).padding(10.dp)) {
            if (items.size > 1) Text("Item ${i + 1}", fontSize = 10.sp, color = c.ink3)
            if (raw) Text(prettyOut(it.json.toString()).take(3_000), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = c.ink2)
            else FluwyMarkdown(WfFormat.readable(it.text(), maxItems = 10).take(2_000))
        }
    }
    if (items.size > 20) Text("… e mais ${items.size - 20} itens", fontSize = 11.sp, color = c.ink3)
}

@Composable
internal fun NodeInputView(w: Workflow, node: WfNode, lastStep: WfStep?) {
    val c = Fluwy.c
    SectionLabel("Entrada")
    when {
        WfCatalog.of(node.type).trigger -> Text("Gatilho: é o primeiro nó, não recebe itens. A saída dele é o primeiro item da execução.", fontSize = 12.sp, color = c.ink3)
        lastStep == null || lastStep.input.isBlank() -> {
            val from = w.edges.filter { it.to == node.id }.mapNotNull { e -> w.nodes.firstOrNull { it.id == e.from } }.joinToString { WfLogic.label(it) }
            Text("Ainda sem dados. Execute o fluxo (ou \"Executar até aqui\" na aba Saída) para ver os itens que chegam" +
                (if (from.isNotBlank()) " de: $from." else "."), fontSize = 12.sp, color = c.ink3)
        }
        else -> {
            Text("Este nó roda uma vez por item recebido (exceto Esperar e Juntar itens). Use {{ \$json.campo }} para o item atual.", fontSize = 11.sp, color = c.ink3)
            ItemsView(lastStep.input, lastStep.inputItems)
        }
    }
}

@Composable
internal fun NodeOutputView(w: Workflow, node: WfNode, lastStep: WfStep?, onPin: (String) -> Unit) {
    val c = Fluwy.c
    var msg by remember(node.id) { mutableStateOf<String?>(null) }
    if (!WfCatalog.of(node.type).trigger && node.type != "approval") NodeTester(w, node, lastStep)
    else { SectionLabel("Saída"); lastStep?.takeIf { it.output.isNotBlank() }?.let { ItemsView(it.output, it.items) } ?: Text("Execute o fluxo para ver a saída.", fontSize = 12.sp, color = c.ink3) }
    lastStep?.takeIf { it.status != "running" }?.let { s ->
        val extra = listOfNotNull(if (s.attempts > 1) "${s.attempts} tentativas" else null, if (s.pinned) "dados fixados" else null,
            if (s.status == "ok" && s.error.isNotBlank()) s.error.take(300) else null)
        if (extra.isNotEmpty()) Text(extra.joinToString(" · "), fontSize = 11.sp, color = if (s.error.isNotBlank()) c.warn else c.ink3)
        if (s.status == "ok" && s.items > 1) ItemsView(s.output, s.items)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton({
            msg = WorkflowEngine.start(w.id, until = node.id).fold({ "Executando até \"${WfLogic.label(node)}\" (modo teste: envios simulados)." }, { it.message?.lineSequence()?.firstOrNull() })
        }) { Icon(Icons.Rounded.PlayCircle, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Executar até aqui") }
        val pinned = node.settings.pinData.isNotBlank()
        if (pinned) OutlinedButton({ onPin(""); msg = "Dados desafixados." }) { Text("Desafixar") }
        else if (lastStep?.status == "ok" && lastStep.output.isNotBlank()) OutlinedButton({
            onPin(WfItems.pinText(WfItems.fromStep(lastStep.output, lastStep.items))); msg = "Saída fixada: testes e execuções manuais usam estes itens sem rodar o nó."
        }) { Icon(Icons.Rounded.PushPin, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Fixar saída") }
    }
    msg?.let { Text(it, fontSize = 12.sp, color = c.ink2) }
}

@Composable
internal fun NodeSettingsView(node: WfNode, onChange: (WfNodeSettings) -> Unit) {
    val c = Fluwy.c
    val s = node.settings
    var retries by remember(node.id) { mutableStateOf(s.retries.toString()) }
    var wait by remember(node.id) { mutableStateOf((s.retryWaitMs / 1000.0).let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() }) }
    var timeout by remember(node.id) { mutableStateOf(if (s.timeoutSec > 0) s.timeoutSec.toString() else "") }
    var pin by remember(node.id) { mutableStateOf(s.pinData) }
    SectionLabel("Configurações do nó")
    @Composable fun toggle(title: String, hint: String, v: Boolean, set: (Boolean) -> Unit) = Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, fontSize = 14.sp); Text(hint, fontSize = 11.sp, color = c.ink3) }
        Switch(v, set)
    }
    toggle("Continuar em caso de erro", "O erro vira um item com o campo \"error\" na saída \"erro\" (ou na principal, se \"erro\" não estiver ligada).", s.continueOnFail) { onChange(s.copy(continueOnFail = it)) }
    toggle("Desativado", "Não executa: repassa os itens recebidos.", s.disabled) { onChange(s.copy(disabled = it)) }
    FluwyField(retries, { retries = it; it.toIntOrNull()?.let { n -> onChange(s.copy(retries = n.coerceIn(0, 10))) } }, "Tentar de novo se falhar (0–10 vezes)")
    if ((retries.toIntOrNull() ?: 0) > 0) FluwyField(wait, { wait = it; it.replace(',', '.').toDoubleOrNull()?.let { x -> onChange(s.copy(retryWaitMs = (x * 1000).toLong().coerceIn(0, 600_000))) } }, "Espera entre tentativas (segundos)")
    FluwyField(timeout, { timeout = it; onChange(s.copy(timeoutSec = (it.toIntOrNull() ?: 0).coerceIn(0, 7_200))) }, "Tempo máximo por item (segundos; vazio = sem limite)")
    FluwyField(pin, { pin = it }, "Dados fixados (JSON: [{...}])", minLines = 3)
    val pinOk = pin.isBlank() || WfItems.parsePinned(pin) != null
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (pinOk) "Com dados fixados, testes e execuções manuais usam estes itens sem rodar o nó." else "JSON inválido.", fontSize = 11.sp,
            color = if (pinOk) c.ink3 else c.err, modifier = Modifier.weight(1f))
        TextButton({ onChange(s.copy(pinData = pin.trim())) }, enabled = pinOk && pin.trim() != s.pinData) { Text("Salvar") }
    }
}
