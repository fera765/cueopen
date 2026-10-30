package dev.cueopen.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.selection.SelectionContainer
import dev.cueopen.app.CueApp
import dev.cueopen.app.ui.theme.Cue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Per-connector diagnostics: hidden by default, bounded and redacted before persistence and again before export. */
@Composable
fun ConnectorDebugSection(connectorId: String) {
    val c = Cue.c
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val logStore = CueApp.instance.connectorDebugLog
    var expanded by remember(connectorId) { mutableStateOf(false) }
    var logText by remember(connectorId) { mutableStateOf("") }
    var exportText by remember(connectorId) { mutableStateOf("") }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(exportText.toByteArray(Charsets.UTF_8)) } != null }.getOrDefault(false)
            }
            android.widget.Toast.makeText(context, if (ok) "Log exportado." else "Não foi possível exportar o log.", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(connectorId, expanded) {
        if (expanded) {
            logText = withContext(Dispatchers.IO) { logStore.read(connectorId) }
            logStore.events(connectorId).collect {
                logText = withContext(Dispatchers.IO) { logStore.read(connectorId) }
            }
        }
    }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Debug", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text("Diagnósticos redigidos · $connectorId", color = c.ink3, fontSize = 11.sp)
            }
            Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (expanded) "Recolher Debug" else "Expandir Debug", tint = c.ink2)
        }
        if (expanded) {
            HorizontalDivider(color = c.line)
            Text("Mostra metadados e erros deste conector. Corpos, credenciais e tokens não são registrados.", color = c.ink2, fontSize = 12.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(modifier = Modifier.weight(1f), onClick = {
                    scope.launch {
                        val text = withContext(Dispatchers.IO) { logStore.read(connectorId) }
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Debug $connectorId", text))
                        android.widget.Toast.makeText(context, "Log copiado.", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Icon(Icons.Rounded.ContentCopy, null, Modifier.size(15.dp)); Spacer(Modifier.size(5.dp)); Text("Copiar", fontSize = 12.sp)
                }
                OutlinedButton(modifier = Modifier.weight(1f), onClick = {
                    scope.launch {
                        exportText = withContext(Dispatchers.IO) { logStore.read(connectorId) }
                        exportLauncher.launch("cue-${connectorId.replace(Regex("[^A-Za-z0-9_-]"), "_")}-debug.txt")
                    }
                }) {
                    Icon(Icons.Rounded.Download, null, Modifier.size(15.dp)); Spacer(Modifier.size(5.dp)); Text("Exportar", fontSize = 12.sp)
                }
                OutlinedButton(onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { logStore.clear(connectorId) }
                        logText = ""
                    }
                }) {
                    Icon(Icons.Rounded.DeleteOutline, "Limpar log", Modifier.size(16.dp), tint = c.err)
                }
            }
            if (logText.isBlank()) Text("Nenhum diagnóstico registrado ainda.", color = c.ink3, fontSize = 12.sp)
            else SelectionContainer {
                Box(Modifier.fillMaxWidth().heightIn(max = 300.dp).clip(RoundedCornerShape(10.dp)).background(c.bg).verticalScroll(rememberScrollState()).padding(10.dp)) {
                    Text(logText.takeLast(80_000), color = c.ink2, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 14.sp)
                }
            }
        }
    }
}
