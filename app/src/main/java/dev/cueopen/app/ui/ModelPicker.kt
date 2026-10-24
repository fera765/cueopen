package dev.cueopen.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cueopen.app.data.OpenRouterModels
import dev.cueopen.app.ui.theme.Cue

/**
 * Campo de modelo com busca: ao tocar, carrega o catálogo da OpenRouter e filtra enquanto digita.
 * Também aceita qualquer id digitado manualmente.
 */
@Composable
fun ModelPicker(value: String, onChange: (String) -> Unit, label: String = "Modelo", allowEmpty: Boolean = false) {
    val c = Cue.c
    var query by remember(value) { mutableStateOf(value) }
    var open by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf<List<OpenRouterModels.Model>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(open) {
        if (open && models.isEmpty()) {
            runCatching { OpenRouterModels.all() }.onSuccess { models = it; error = null }.onFailure { error = "Não foi possível carregar a lista (${it.message})" }
        }
    }
    val results = remember(query, models) { if (query.isBlank() || query == value) models.take(40) else OpenRouterModels.search(models, query) }
    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it; open = true; if (allowEmpty && it.isBlank()) onChange("") },
            label = { Text(label) },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) open = true },
            shape = RoundedCornerShape(16.dp),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onChange(query.trim()); open = false }),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.ink, unfocusedBorderColor = c.line, focusedContainerColor = c.surface, unfocusedContainerColor = c.surface),
        )
        if (open) {
            Column(Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(16.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(16.dp))) {
                when {
                    error != null -> Text(error!!, Modifier.padding(12.dp), color = c.err, fontSize = 13.sp)
                    models.isEmpty() -> Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = c.accent); Spacer(Modifier.width(8.dp))
                        Text("Carregando modelos da OpenRouter…", fontSize = 13.sp, color = c.ink2)
                    }
                    else -> LazyColumn(Modifier.heightIn(max = 280.dp)) {
                        items(results, key = { it.id }) { m ->
                            Column(Modifier.fillMaxWidth().clickable { onChange(m.id); query = m.id; open = false }.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                Text(m.name, fontWeight = FontWeight.Medium, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${m.id} · ${m.context / 1000}k ctx · $${"%.2f".format(m.promptPrice)}/$${"%.2f".format(m.completionPrice)} por 1M",
                                    fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        if (results.isEmpty()) item { Text("Nenhum modelo encontrado. Toque em concluir para usar \"$query\".", Modifier.padding(12.dp), fontSize = 13.sp, color = c.ink2) }
                    }
                }
                TextButton({ onChange(query.trim()); open = false }, Modifier.align(Alignment.End)) { Text("Fechar", color = c.ink2) }
            }
        }
    }
}
