package dev.cueopen.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
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
import dev.cueopen.app.data.ProviderModels
import dev.cueopen.app.ui.theme.Cue

/**
 * Campo com busca sobre GET {Base URL}/models. Também aceita um ID digitado manualmente.
 * OpenCode Go usa o endpoint de catálogo irmão documentado pelo provedor.
 */
@Composable
fun ModelPicker(
    value: String,
    onChange: (String) -> Unit,
    label: String = "Modelo",
    allowEmpty: Boolean = false,
    baseUrl: String = "https://openrouter.ai/api/v1",
    apiKey: String = "",
    includeSystemOne: Boolean = false,
) {
    val c = Cue.c
    var query by remember(value) { mutableStateOf(value) }
    var open by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember(baseUrl, apiKey, includeSystemOne) { mutableStateOf(false) }
    var loaded by remember(baseUrl, apiKey, includeSystemOne) { mutableStateOf(false) }
    var loadedRefresh by remember(baseUrl, apiKey, includeSystemOne) { mutableIntStateOf(-1) }
    var models by remember(baseUrl, apiKey, includeSystemOne) { mutableStateOf<List<ProviderModels.Model>>(emptyList()) }
    var error by remember(baseUrl, apiKey, includeSystemOne) { mutableStateOf<String?>(null) }

    LaunchedEffect(open, baseUrl, apiKey, refresh, includeSystemOne) {
        if (open && (!loaded || refresh != loadedRefresh)) {
            loading = true
            error = null
            try {
                models = ProviderModels.all(baseUrl, apiKey, forceRefresh = loadedRefresh >= 0 && refresh != loadedRefresh, includeSystemOne = includeSystemOne)
                loaded = true
                loadedRefresh = refresh
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "Não foi possível carregar os modelos (${e.message ?: "falha de rede"})."
            } finally {
                loading = false
            }
        }
    }
    val results = remember(query, models) {
        if (query.isBlank() || query == value) models.take(40) else ProviderModels.search(models, query)
    }
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
                Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Modelos deste provedor", Modifier.weight(1f), fontSize = 12.sp, color = c.ink2)
                    IconButton(onClick = { refresh++; error = null }, enabled = !loading) {
                        Icon(Icons.Rounded.Refresh, "Atualizar modelos", tint = c.ink2)
                    }
                }
                when {
                    error != null -> Text(error!!, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), color = c.err, fontSize = 13.sp)
                    loading -> Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = c.accent); Spacer(Modifier.width(8.dp))
                        Text("Carregando modelos…", fontSize = 13.sp, color = c.ink2)
                    }
                    models.isEmpty() -> Text("O provedor não retornou modelos. Você ainda pode informar um ID manualmente.", Modifier.padding(12.dp), fontSize = 13.sp, color = c.ink2)
                    results.isEmpty() -> Text("Nenhum modelo encontrado. Toque em concluir para usar \"$query\".", Modifier.padding(12.dp), fontSize = 13.sp, color = c.ink2)
                    else -> LazyColumn(Modifier.heightIn(max = 280.dp)) {
                        items(results, key = { it.id }) { model ->
                            Column(Modifier.fillMaxWidth().clickable { onChange(model.id); query = model.id; open = false }.padding(horizontal = 12.dp, vertical = 8.dp)) {
                                Text(model.name, fontWeight = FontWeight.Medium, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val details = buildList {
                                    add(model.id)
                                    if (model.context > 0) add("${model.context / 1000}k ctx")
                                    if (model.promptPrice != null && model.completionPrice != null) {
                                        add("$${"%.2f".format(java.util.Locale.US, model.promptPrice)}/$${"%.2f".format(java.util.Locale.US, model.completionPrice)} por 1M")
                                    }
                                    if (model.supportedParameters.isNotEmpty()) add(if ("tools" in model.supportedParameters) "tools" else "sem tools")
                                    model.apiFamily?.let { add(it) }
                                }
                                Text(details.joinToString(" · "), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                TextButton({ onChange(query.trim()); open = false }, Modifier.align(Alignment.End)) { Text("Usar ID digitado", color = c.ink2) }
            }
        }
    }
}
