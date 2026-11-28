package dev.fluwy.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.fluwy.app.agent.MemoryCurator
import dev.fluwy.app.agent.MemoryGraph
import dev.fluwy.app.data.MemoryItem
import dev.fluwy.app.ui.theme.Fluwy

internal fun memoryOrigin(m: MemoryItem): String = MemoryCurator.originLabel(m)

/** Edição de uma memória: texto, título, categoria e tags (com os mesmos limites do agente). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MemoryEditDialog(m: MemoryItem, onDismiss: () -> Unit, onSave: (MemoryItem) -> Unit, onDelete: () -> Unit) {
    val c = Fluwy.c
    var text by remember(m.id) { mutableStateOf(m.text) }
    var title by remember(m.id) { mutableStateOf(m.title) }
    var kind by remember(m.id) { mutableStateOf(MemoryGraph.kindOf(m)) }
    var tags by remember(m.id) { mutableStateOf(m.tags.joinToString(", ")) }
    val tooLong = text.trim().length > MemoryCurator.MAX_CHARS
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("Editar memória") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FluwyField(text, { text = it }, "Fato", minLines = 3)
                Text("${text.trim().length}/${MemoryCurator.MAX_CHARS}", fontSize = 11.sp, color = if (tooLong) c.err else c.ink3)
                FluwyField(title, { title = it.take(MemoryCurator.MAX_TITLE) }, "Título (opcional)")
                SectionLabel("Categoria")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    MemoryGraph.KINDS.forEach { k -> Chip(k, kind == k) { kind = k } }
                }
                FluwyField(tags, { tags = it }, "Tags (separadas por vírgula)")
                Text(memoryOrigin(m), fontSize = 11.sp, color = c.ink3)
            }
        },
        confirmButton = {
            TextButton({
                onSave(m.copy(text = text.trim(), title = title.trim(), kind = MemoryCurator.canonicalKind(kind),
                    tags = MemoryCurator.cleanTags(tags.split(','))))
            }, enabled = text.isNotBlank() && !tooLong) { Text("Salvar", color = c.accent) }
        },
        dismissButton = {
            Row {
                TextButton(onDelete) { Text("Excluir", color = c.err) }
                TextButton(onDismiss) { Text("Cancelar") }
            }
        })
}
