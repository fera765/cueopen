package dev.cueopen.app.ui.mascot

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cueopen.app.ui.Chip
import dev.cueopen.app.ui.CueField
import dev.cueopen.app.ui.theme.Cue

/** Prévia grande com chips para ver como o mascote reage a cada estado. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MascotPreview(face: String, color: Color) {
    var state by remember { mutableStateOf(MascotState.IDLE) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Mascot(face, color, 104.dp, state = state)
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(MascotState.IDLE to "Parado", MascotState.THINKING to "Pensando", MascotState.TALKING to "Falando", MascotState.SLEEPING to "Dormindo")
                .forEach { (s, l) -> Chip(l, state == s) { state = s } }
        }
    }
}

/** Grade de mascotes nativos + importados, com o botão Importar (SVG ou XML). Toque longo apaga um importado. */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun MascotPicker(selected: String, color: Color, onSelect: (String) -> Unit) {
    val c = Cue.c
    val ctx = LocalContext.current
    val lib = remember { MascotLibrary.get(ctx) }
    val customs by lib.items.collectAsState()
    var pending by remember { mutableStateOf<Pair<String, String>?>(null) } // nome sugerido, conteúdo
    var readError by remember { mutableStateOf<String?>(null) }
    var toDelete by remember { mutableStateOf<CustomMascot?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) readMascotFile(ctx, uri).onSuccess { pending = it }.onFailure { readError = it.message }
    }
    val current = if (Mascots.isCustom(selected)) selected else Mascots.resolveBuiltin(selected)

    @Composable
    fun Tile(isSelected: Boolean, onClick: () -> Unit, onLongClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
        val shape = RoundedCornerShape(14.dp)
        Box(
            Modifier.padding(4.dp).clip(shape)
                .border(if (isSelected) 2.dp else 1.dp, if (isSelected) c.ink else c.line, shape)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(6.dp),
            contentAlignment = Alignment.Center,
        ) { content() }
    }

    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Mascots.builtins.forEach { b ->
            Tile(current == b.id, { onSelect(b.id) }) { Mascot(b.id, color, 36.dp) }
        }
        customs.forEach { m ->
            Tile(current == m.face, { onSelect(m.face) }, { toDelete = m }) { Mascot(m.face, color, 36.dp) }
        }
        Tile(false, { picker.launch(arrayOf("image/svg+xml", "text/xml", "application/xml", "text/plain", "application/octet-stream")) }) {
            Column(Modifier.size(36.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Rounded.FileUpload, "Importar mascote", tint = c.ink2, modifier = Modifier.size(18.dp))
                Text("Importar", fontSize = 8.sp, color = c.ink2)
            }
        }
    }
    val info = Mascots.builtins.firstOrNull { it.id == current }
    val customInfo = customs.firstOrNull { it.face == current }
    Text(
        when {
            info != null -> "${info.name} · ${info.personality}"
            customInfo != null -> "${customInfo.name} · importado (${customInfo.motion.label.lowercase()}). Toque longo para apagar."
            else -> ""
        },
        fontSize = 12.sp, color = c.ink3, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
    )

    pending?.let { (suggested, text) ->
        ImportMascotDialog(suggested, text, color, onDismiss = { pending = null }) { m -> pending = null; onSelect(m.face) }
    }
    readError?.let { msg ->
        AlertDialog(onDismissRequest = { readError = null }, confirmButton = { TextButton({ readError = null }) { Text("OK") } },
            title = { Text("Não foi possível abrir") }, text = { Text(msg) })
    }
    toDelete?.let { m ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Apagar ${m.name}?") },
            text = { Text("Agents que usam este mascote passam a usar a Geleia.") },
            confirmButton = { TextButton({ lib.delete(m.id); if (current == m.face) onSelect("jelly"); toDelete = null }) { Text("Apagar", color = c.err) } },
            dismissButton = { TextButton({ toDelete = null }) { Text("Cancelar") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ImportMascotDialog(suggestedName: String, text: String, color: Color, onDismiss: () -> Unit, onImported: (CustomMascot) -> Unit) {
    val c = Cue.c
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(suggestedName) }
    var motion by remember { mutableStateOf(MascotMotion.FLOAT) }
    var error by remember { mutableStateOf<String?>(null) }
    // Prévia antes de salvar: valida e mostra exatamente como vai ficar.
    val check = remember(text) { runCatching { SvgMascotParser.parse(text) } }
    val previewModel = check.getOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Importar mascote") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val preview = remember(text, motion) { CustomMascot("preview", name, motion, if (text.contains("<vector")) "xml" else "svg", text) }
                    PreviewCustom(preview, color)
                }
                CueField(name, { name = it }, "Nome do mascote")
                Text("Movimento", fontSize = 13.sp, color = c.ink2)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    MascotMotion.entries.forEach { m -> Chip(m.label, motion == m) { motion = m } }
                }
                val parts = previewModel?.shapes?.map { it.role }?.filter { it != SvgMascotParser.Role.NONE }?.distinct().orEmpty()
                Text(
                    if (parts.isNotEmpty()) "Partes animadas encontradas: " + parts.joinToString { roleLabel(it) } + "."
                    else "Dica: dê id às partes para animá-las sozinhas — olho, boca, braco, asa, cauda, orelha, antena, corpo, gira, brilho. Use fill=\"currentColor\" para pintar com a cor do Agent.",
                    fontSize = 12.sp, color = c.ink3,
                )
                if (previewModel?.usesAgentColor == true) Text("Usa a cor do Agent (currentColor).", fontSize = 12.sp, color = c.ok)
                error?.let { Text(it, fontSize = 12.sp, color = c.err) }
            }
        },
        confirmButton = {
            TextButton({
                MascotLibrary.import(ctx, name, text, motion).onSuccess(onImported).onFailure { error = it.message ?: "Arquivo inválido." }
            }) { Text("Importar", fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancelar") } },
    )
}

/** Prévia de um mascote ainda não salvo (mesmo renderizador do app). */
@Composable
private fun PreviewCustom(m: CustomMascot, color: Color) {
    val model = m.model
    val clock = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { clock.floatValue = (it - start) / 1_000_000_000f }
    }
    val prepared = remember(m) { model?.let { runCatching { prepareSvg(it) }.getOrNull() } }
    val bmp = remember(m.text, color) { if (model?.hasAnimatedParts == true || m.format == "xml") null else renderSvgBitmap(m.text, color, 384) }
    androidx.compose.foundation.Canvas(Modifier.size(96.dp)) {
        val pose = MascotPose.at(clock.floatValue, 1f, MascotState.IDLE, true)
        when {
            bmp != null -> drawSvgBitmap(bmp, pose, m.motion)
            prepared != null -> drawPreparedSvg(prepared, color, pose, m.motion)
        }
    }
    if (bmp == null && prepared == null) Text("Prévia indisponível — o arquivo pode estar inválido.", fontSize = 12.sp, color = Cue.c.warn)
}

private fun roleLabel(r: SvgMascotParser.Role) = when (r) {
    SvgMascotParser.Role.EYE -> "olhos"; SvgMascotParser.Role.MOUTH -> "boca"; SvgMascotParser.Role.ARM -> "braços"
    SvgMascotParser.Role.WING -> "asas"; SvgMascotParser.Role.TAIL -> "cauda"; SvgMascotParser.Role.EAR -> "orelhas"
    SvgMascotParser.Role.ANTENNA -> "antena"; SvgMascotParser.Role.BODY -> "corpo"; SvgMascotParser.Role.SPIN -> "giro"
    SvgMascotParser.Role.GLOW -> "brilho"; SvgMascotParser.Role.NONE -> ""
}

/** Lê o arquivo escolhido (até 1 MB) e sugere um nome a partir do nome do arquivo. */
private fun readMascotFile(ctx: android.content.Context, uri: Uri): Result<Pair<String, String>> = runCatching {
    val cr = ctx.contentResolver
    val displayName = runCatching {
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: "mascote"
    val bytes = cr.openInputStream(uri)?.use { input ->
        val buf = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(chunk); if (n < 0) break
            buf.write(chunk, 0, n)
            if (buf.size() > SvgMascotParser.MAX_BYTES) throw IllegalArgumentException("Arquivo grande demais (máx. 1 MB).")
        }
        buf.toByteArray()
    } ?: throw IllegalArgumentException("Não consegui ler o arquivo.")
    val text = bytes.toString(Charsets.UTF_8)
    if (!Regex("<svg[\\s>]|<vector[\\s>]").containsMatchIn(text)) throw IllegalArgumentException("Escolha um arquivo .svg ou um VectorDrawable .xml.")
    displayName.substringBeforeLast('.').replace('_', ' ').replace('-', ' ').trim().ifBlank { "Meu mascote" } to text
}
