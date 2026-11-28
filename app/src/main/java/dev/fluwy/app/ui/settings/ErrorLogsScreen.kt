package dev.fluwy.app.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import dev.fluwy.app.diag.ErrorLog
import dev.fluwy.app.ui.FluwyCard
import dev.fluwy.app.ui.FluwyTopBar
import dev.fluwy.app.ui.theme.Fluwy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Configurações > Logs de erro: falhas registradas pelo app, filtráveis por área. */
@Composable
fun ErrorLogsScreen(nav: NavController) {
    val c = Fluwy.c
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var reload by remember { mutableIntStateOf(0) }
    var entries by remember { mutableStateOf(emptyList<ErrorLog.Entry>()) }
    var source by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(reload) { entries = withContext(Dispatchers.IO) { ErrorLog.entries(ctx) } }
    val sources = entries.map { it.source }.distinct().sorted()
    val shown = entries.filter { source == null || it.source == source }
    Scaffold(containerColor = c.bg, topBar = {
        FluwyTopBar("Logs de erro", { nav.popBackStack() }) {
            TextButton({ ErrorLog.clear(ctx); reload++ }) { Text("Limpar") }
        }
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(source == null, { source = null }, { Text("Todos (${entries.size})") })
                    sources.forEach { s -> FilterChip(source == s, { source = s }, { Text(s) }) }
                }
                if (shown.isEmpty()) Text("Nenhum erro registrado.", color = c.ink2, fontSize = 13.sp)
            }
            items(shown, key = { it.ts.toString() + it.message.hashCode() }) { e ->
                FluwyCard(Modifier.fillMaxWidth(), onClick = { expanded = if (expanded == e.ts) null else e.ts }) {
                    Text(listOfNotNull(e.time, e.source, e.tool, if (e.count > 1) "×${e.count}" else null).joinToString(" · "),
                        fontSize = 11.sp, color = c.ink2)
                    Text(e.message, fontSize = 13.sp, color = c.ink, fontWeight = FontWeight.Medium, maxLines = if (expanded == e.ts) Int.MAX_VALUE else 3)
                    if (expanded == e.ts) {
                        e.stack?.let { Text(it, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = c.ink2) }
                        TextButton({ clipboard.setText(AnnotatedString(ErrorLog.render(e))) }) { Text("Copiar") }
                    }
                }
            }
        }
    }
}
