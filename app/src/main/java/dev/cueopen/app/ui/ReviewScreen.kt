package dev.cueopen.app.ui

import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.cueopen.app.ui.theme.Cue
import dev.cueopen.app.workflow.WorkflowEngine

/**
 * Página do nó "Revisar e aprovar": o item completo (e-mail com remetente, fotos, anexos e texto) e, embaixo, o
 * rascunho editável. Aprovar segue o fluxo com o texto final (ex.: envia a resposta); Recusar segue pelo "não".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(nav: NavController, runId: String, nodeId: String) {
    val c = Cue.c
    val all by WorkflowEngine.reviews.collectAsStateWithLifecycle()
    val rv = all["$runId|$nodeId"]
    var draft by remember(runId, nodeId) { mutableStateOf(rv?.draft.orEmpty()) }
    var confirmReject by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf<String?>(null) }
    Scaffold(containerColor = c.bg, topBar = { CueTopBar(rv?.title ?: "Revisão", { nav.popBackStack() }) }) { pad ->
        if (rv == null) {
            Column(Modifier.padding(pad).fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(if (done != null) Icons.Rounded.CheckCircle else Icons.Rounded.HourglassDisabled, null, Modifier.size(44.dp), tint = if (done != null) c.ok else c.ink3)
                Spacer(Modifier.height(10.dp))
                Text(done ?: "Esta revisão não está mais pendente (já respondida, expirou ou o fluxo foi parado).", color = c.ink2, fontSize = 15.sp)
                Spacer(Modifier.height(12.dp))
                OutlinedButton({ nav.popBackStack() }) { Text("Voltar") }
            }
            return@Scaffold
        }
        Column(Modifier.padding(pad).fillMaxSize()) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Fluxo · ${rv.workflowName}", fontSize = 12.sp, color = c.ink3)
                // O item como ele é (UI kit: e-mail com foto renderizada, anexos e texto completo).
                WfViewContent(rv.view) { r -> nav.navigate(r) }
                HorizontalDivider(color = c.line)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.EditNote, null, tint = c.accent)
                    Spacer(Modifier.width(8.dp))
                    Text("Rascunho da resposta", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.weight(1f))
                    if (draft != rv.draft) TextButton({ draft = rv.draft }) { Text("Desfazer edição", fontSize = 12.sp) }
                }
                Text("Edite à vontade: o texto que estiver aqui é o que segue no fluxo quando você aprovar.", fontSize = 12.sp, color = c.ink3)
                OutlinedTextField(draft, { draft = it }, Modifier.fillMaxWidth().heightIn(min = 180.dp), shape = RoundedCornerShape(16.dp),
                    placeholder = { Text("Sem rascunho — escreva a resposta aqui") })
            }
            Row(Modifier.fillMaxWidth().background(c.surface).border(1.dp, c.line).padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton({ confirmReject = true }, Modifier.weight(1f), colors = ButtonDefaults.outlinedButtonColors(contentColor = c.err)) {
                    Icon(Icons.Rounded.Close, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Recusar")
                }
                Button({
                    WorkflowEngine.decideReview(runId, nodeId, true, draft)
                    done = "Aprovado — o fluxo continuou com o seu texto."
                }, Modifier.weight(1.4f), enabled = draft.isNotBlank(), colors = ButtonDefaults.buttonColors(containerColor = c.ok)) {
                    Icon(Icons.Rounded.Send, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Aprovar e enviar")
                }
            }
        }
    }
    if (confirmReject) AlertDialog(onDismissRequest = { confirmReject = false }, containerColor = c.surface,
        title = { Text("Recusar?") }, text = { Text("Nada será enviado; o fluxo segue pelo caminho \"não\".") },
        confirmButton = { TextButton({ confirmReject = false; WorkflowEngine.decideReview(runId, nodeId, false, draft); done = "Recusado — nada foi enviado." }) { Text("Recusar", color = c.err) } },
        dismissButton = { TextButton({ confirmReject = false }) { Text("Cancelar") } })
}
