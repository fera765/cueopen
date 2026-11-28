package dev.fluwy.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import dev.fluwy.app.prompts.PromptCatalog
import dev.fluwy.app.prompts.PromptTemplate
import dev.fluwy.app.prompts.Prompts
import dev.fluwy.app.ui.theme.Fluwy
import dev.fluwy.app.ui.FluwyCard
import dev.fluwy.app.ui.FluwyField
import dev.fluwy.app.ui.FluwyTopBar
import dev.fluwy.app.ui.SectionLabel

/** Configurações > Prompts: lista todos os prompts do app, agrupados. */
@Composable
fun PromptsScreen(nav: NavController) {
    val c = Fluwy.c
    var query by remember { mutableStateOf("") }
    val q = query.trim().lowercase()
    val items = PromptCatalog.all.filter { q.isEmpty() || it.title.lowercase().contains(q) || it.id.contains(q) }
    Scaffold(containerColor = c.bg, topBar = { FluwyTopBar("Prompts", { nav.popBackStack() }) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text("Textos que o app envia aos modelos. Toque para editar; \"Restaurar\" volta ao padrão.", fontSize = 13.sp, color = c.ink2,
                    modifier = Modifier.padding(top = 8.dp))
                FluwyField(query, { query = it }, "Buscar prompt")
            }
            items.groupBy { it.group }.forEach { (group, defs) ->
                item(key = "g:$group") { SectionLabel(group) }
                items(defs, key = { it.id }) { def ->
                    FluwyCard(Modifier.fillMaxWidth(), onClick = { nav.navigate("prompt/" + def.id) }) {
                        Text(def.title, fontWeight = if (def.isFragment) FontWeight.Normal else FontWeight.SemiBold, fontSize = 14.sp, color = c.ink)
                        Text((if (Prompts.isCustomized(def.id)) "Personalizado · " else "") + def.description, fontSize = 12.sp, color = c.ink2)
                    }
                }
            }
        }
    }
}

/** Edição de um prompt: salvar ou restaurar o texto padrão. */
@Composable
fun PromptEditorScreen(nav: NavController, id: String) {
    val c = Fluwy.c
    val def = PromptCatalog.byId(id) ?: run { nav.popBackStack(); return }
    var text by remember(id) { mutableStateOf(Prompts.text(id)) }
    var confirmReset by remember { mutableStateOf(false) }
    val missing = remember(text) { PromptTemplate.placeholders(def.default) - PromptTemplate.placeholders(text).toSet() }
    Scaffold(containerColor = c.bg, topBar = { FluwyTopBar(def.title, { nav.popBackStack() }) }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(def.description, fontSize = 13.sp, color = c.ink2)
            val vars = PromptTemplate.placeholders(def.default).filter { it != "lb" }
            if (vars.isNotEmpty()) Text(
                "Variáveis preenchidas pelo app: " + vars.joinToString { "{{$it}}" } + ". Trechos entre {{#nome}} e {{/nome}} só aparecem quando a condição vale.",
                fontSize = 12.sp, color = c.ink2,
            )
            if (missing.isNotEmpty()) Text("Atenção: o texto não usa " + missing.joinToString { "{{$it}}" } + ".", fontSize = 12.sp, color = c.accent)
            androidx.compose.material3.OutlinedTextField(
                value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(), minLines = 12,
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button({ Prompts.save(id, text); nav.popBackStack() }, colors = ButtonDefaults.buttonColors(containerColor = c.ink)) { Text("Salvar") }
                OutlinedButton({ confirmReset = true }) { Text("Restaurar padrão") }
            }
        }
    }
    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false },
        title = { Text("Restaurar padrão?") },
        text = { Text("O texto personalizado deste prompt será descartado.") },
        confirmButton = { TextButton({ Prompts.reset(id); text = def.default; confirmReset = false }) { Text("Restaurar") } },
        dismissButton = { TextButton({ confirmReset = false }) { Text("Cancelar") } },
    )
}
