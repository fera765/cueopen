package dev.cueopen.app.ui

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.cueopen.app.browser.BrowserPermissions

/** Mostra os pedidos de recursos dos sites (câmera, microfone, localização…) e o seletor de arquivos do browser. */
@Composable
fun BrowserPermissionHost() {
    val ctx = LocalContext.current
    val queue by BrowserPermissions.pending.collectAsStateWithLifecycle()
    val pick by BrowserPermissions.filePick.collectAsStateWithLifecycle()
    var remember by remember { mutableStateOf(true) }
    var waiting by remember { mutableStateOf<BrowserPermissions.Pending?>(null) }
    val runtime = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        val p = waiting ?: return@rememberLauncherForActivityResult
        waiting = null
        p.decide(res.values.all { it }, remember)
    }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val fp = BrowserPermissions.filePick.value ?: return@rememberLauncherForActivityResult
        BrowserPermissions.filePick.value = null
        val data = r.data
        val uris = when {
            r.resultCode != android.app.Activity.RESULT_OK || data == null -> null
            data.clipData != null -> Array(data.clipData!!.itemCount) { data.clipData!!.getItemAt(it).uri }
            data.data != null -> arrayOf(data.data!!)
            else -> null
        }
        runCatching { fp.callback.onReceiveValue(uris) }
    }
    LaunchedEffect(pick?.id) {
        val fp = pick ?: return@LaunchedEffect
        val intent = android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).addCategory(android.content.Intent.CATEGORY_OPENABLE)
            .setType(fp.mimeTypes.singleOrNull()?.takeIf { it.contains('/') } ?: "*/*")
            .putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, fp.multiple)
            .apply { if (fp.mimeTypes.size > 1) putExtra(android.content.Intent.EXTRA_MIME_TYPES, fp.mimeTypes.filter { it.contains('/') }.toTypedArray()) }
        runCatching { files.launch(android.content.Intent.createChooser(intent, "Escolher arquivo")) }
            .onFailure { BrowserPermissions.filePick.value = null; runCatching { fp.callback.onReceiveValue(null) } }
    }
    val p = queue.firstOrNull() ?: return
    if (waiting?.id == p.id) return
    AlertDialog(onDismissRequest = { p.decide(false, false) },
        title = { Text("Permitir ${p.kinds.joinToString(" e ") { it.label }}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${p.origin.removePrefix("https://").removePrefix("http://")} quer usar ${p.kinds.joinToString(" e ") { it.label }} no browser do Fluwy.")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(remember, { remember = it }); Text("Lembrar para este site", fontSize = 13.sp)
                }
            }
        },
        confirmButton = {
            TextButton({
                val missing = p.kinds.mapNotNull { it.androidPermission }.filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }
                    .let { m -> if (android.Manifest.permission.ACCESS_FINE_LOCATION in m) m + android.Manifest.permission.ACCESS_COARSE_LOCATION else m }
                if (missing.isEmpty()) p.decide(true, remember)
                else { waiting = p; runtime.launch(missing.toTypedArray()) }
            }) { Text("Permitir") }
        },
        dismissButton = { TextButton({ p.decide(false, remember) }) { Text("Negar") } })
}
