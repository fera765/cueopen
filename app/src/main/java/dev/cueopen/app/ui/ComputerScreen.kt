package dev.cueopen.app.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.cueopen.app.CueApp
import dev.cueopen.app.sandbox.Sandbox
import dev.cueopen.app.sandbox.resizeTerminal
import dev.cueopen.app.ui.theme.Cue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/** "Computador" do agente em tela cheia: Terminal (agente + shell), Browser e Arquivos. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ComputerScreen(nav: NavController, sessionId: String, initialTab: String?) {
    val app = CueApp.instance
    val tabs = listOf("terminal" to "Terminal", "browser" to "Browser", "files" to "Arquivos")
    var tab by remember { mutableStateOf(initialTab ?: "terminal") }
    val activity by app.agent.activity.collectAsStateWithLifecycle()
    val act = activity[sessionId]
    // segue o agente automaticamente quando ele troca de ferramenta
    LaunchedEffect(act?.kind) {
        when (act?.kind) {
            dev.cueopen.app.agent.Activity.Kind.TERMINAL -> tab = "terminal"
            dev.cueopen.app.agent.Activity.Kind.BROWSER -> tab = "browser"
            dev.cueopen.app.agent.Activity.Kind.EDITOR -> tab = "files"
            else -> {}
        }
    }
    val c = Cue.c
    Scaffold(containerColor = c.bg, topBar = {
        Column(Modifier.statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ nav.popBackStack() }) { Icon(Icons.Rounded.CloseFullscreen, "Fechar") }
                Text("Computador", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (act != null && act.kind != dev.cueopen.app.agent.Activity.Kind.IDLE) {
                    Row(Modifier.clip(CircleShape).background(c.accentSoft).padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(c.accent)); Spacer(Modifier.width(6.dp))
                        Text("Agente ativo", fontSize = 12.sp, color = c.accent)
                    }
                    Spacer(Modifier.width(12.dp))
                }
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tabs.forEach { (k, l) -> Chip(l, selected = tab == k, icon = when (k) { "terminal" -> Icons.Rounded.Terminal; "browser" -> Icons.Rounded.Language; else -> Icons.Rounded.FolderOpen }) { tab = k } }
            }
        }
    }) { pad ->
        Box(Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad).imePadding()) {
            when (tab) {
                "terminal" -> TerminalPane(sessionId)
                "browser" -> BrowserPane()
                else -> FilesPane(app.sandbox.workspaceFor(sessionId), "/root/workspace/$sessionId", onPreview = { f ->
                    MainScope().launch { app.browser.navigate("file://" + f.absolutePath) }; tab = "browser"
                })
            }
        }
    }
}

@Composable
private fun TerminalPane(sessionId: String) {
    Box(Modifier.fillMaxSize().padding(8.dp).clip(RoundedCornerShape(16.dp))) {
        TerminalView(key = sessionId, cwd = "/root/workspace/$sessionId", modifier = Modifier.fillMaxSize())
    }
}

/**
 * Terminal nativo do Termux (TerminalView + PTY real). O mesmo terminal é usado pelo agente e
 * pelo usuário: os comandos do agente aparecem em tempo real e você digita direto nele.
 */
@Composable
fun TerminalView(key: String, cwd: String, modifier: Modifier = Modifier) {
    val app = CueApp.instance
    val session = remember(key) { app.terminals.get(key, cwd) }
    val sandboxState by app.sandbox.state.collectAsStateWithLifecycle()
    var ctrl by remember { mutableStateOf(false) }
    var viewRef by remember { mutableStateOf<com.termux.view.TerminalView?>(null) }
    val density = androidx.compose.ui.platform.LocalDensity.current

    if (sandboxState != Sandbox.State.READY) {
        Box(modifier.background(Color(0xFF0F1115)), contentAlignment = Alignment.Center) {
            Text("Sandbox não instalado.\nAbra Computador (sandbox) e toque em Instalar.", color = Color(0xFF9A9A9A),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        return
    }
    DisposableEffect(session) { onDispose { viewRef?.let { session.detach(it) } } }

    Column(modifier.background(Color(0xFF0F1115))) {
        AndroidView(modifier = Modifier.weight(1f).fillMaxWidth().padding(start = 6.dp, top = 4.dp), factory = { ctx ->
            com.termux.view.TerminalView(ctx, null).apply {
                setBackgroundColor(android.graphics.Color.parseColor("#0f1115"))
                setTypeface(android.graphics.Typeface.MONOSPACE)
                setTextSize(with(density) { 13.sp.toPx() }.toInt())
                isFocusable = true
                isFocusableInTouchMode = true
                keepScreenOn = true
                val tv = this
                setTerminalViewClient(object : com.termux.view.TerminalViewClient {
                    var fontPx = with(density) { 13.sp.toPx() }
                    override fun onScale(scale: Float): Float {
                        fontPx = (fontPx * scale).coerceIn(18f, 64f); tv.setTextSize(fontPx.toInt()); return 1f
                    }
                    override fun onSingleTapUp(e: android.view.MotionEvent?) { tv.requestFocus(); showIme(tv) }
                    override fun shouldBackButtonBeMappedToEscape() = false
                    override fun shouldEnforceCharBasedInput() = true
                    override fun shouldUseCtrlSpaceWorkaround() = false
                    override fun isTerminalViewSelected() = true
                    override fun copyModeChanged(copyMode: Boolean) {}
                    override fun onKeyDown(keyCode: Int, e: android.view.KeyEvent?, session: com.termux.terminal.TerminalSession?) = false
                    override fun onKeyUp(keyCode: Int, e: android.view.KeyEvent?) = false
                    override fun onLongPress(event: android.view.MotionEvent?) = false
                    override fun readControlKey(): Boolean { val c = ctrl; if (c) ctrl = false; return c }
                    override fun readAltKey() = false
                    override fun readShiftKey() = false
                    override fun readFnKey() = false
                    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: com.termux.terminal.TerminalSession?) = false
                    override fun onEmulatorSet() {}
                    override fun logError(tag: String?, message: String?) {}
                    override fun logWarn(tag: String?, message: String?) {}
                    override fun logInfo(tag: String?, message: String?) {}
                    override fun logDebug(tag: String?, message: String?) {}
                    override fun logVerbose(tag: String?, message: String?) {}
                    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}
                    override fun logStackTrace(tag: String?, e: Exception?) {}
                })
                session.attach(this)
                viewRef = this
                post { requestFocus() }
            }
        })
        ExtraKeys(ctrl = ctrl, onCtrl = { ctrl = !ctrl }) { seq -> session.write(seq); viewRef?.requestFocus() }
    }
}

private fun showIme(v: android.view.View) {
    val imm = v.context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
    imm?.showSoftInput(v, 0)
}

/** Linha de teclas extras (como no Termux/Acode), fica logo acima do teclado. */
@Composable
private fun ExtraKeys(ctrl: Boolean, onCtrl: () -> Unit, send: (String) -> Unit) {
    val keys = listOf("ESC" to "\u001b", "TAB" to "\t", "^C" to "\u0003", "^D" to "\u0004", "^L" to "\u000c",
        "↑" to "\u001b[A", "↓" to "\u001b[B", "←" to "\u001b[D", "→" to "\u001b[C", "HOME" to "\u001b[H", "END" to "\u001b[F",
        "|" to "|", "/" to "/", "-" to "-", "~" to "~")
    Row(Modifier.fillMaxWidth().background(Color(0xFF16181D)).horizontalScroll(rememberScrollState()).padding(4.dp)) {
        Box(Modifier.padding(2.dp).clip(RoundedCornerShape(8.dp)).background(if (ctrl) Color(0xFFFF6B3D) else Color(0xFF23262D))
            .clickable(onClick = onCtrl).padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text("CTRL", color = Color(0xFFE6E6E6), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        }
        keys.forEach { (l, s) ->
            Box(Modifier.padding(2.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF23262D)).clickable { send(s) }.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(l, color = Color(0xFFE6E6E6), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            }
        }
    }
}

@Composable
fun BrowserPane() {
    val app = CueApp.instance
    val ui by app.browser.ui.collectAsStateWithLifecycle()
    val c = Cue.c
    var url by remember(ui.url) { mutableStateOf(if (ui.url.startsWith("file:///android_asset")) "" else ui.url) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ scope.launch { app.browser.back() } }, enabled = ui.canBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Voltar") }
            IconButton({ app.browser.view().goForward() }, enabled = ui.canForward) { Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Avançar") }
            Row(Modifier.weight(1f).clip(CircleShape).background(c.surface2).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (ui.url.startsWith("https")) Icons.Rounded.Lock else Icons.Rounded.Public, null, Modifier.size(14.dp), tint = c.ink3)
                Spacer(Modifier.width(6.dp))
                BasicTextField(url, { url = it }, singleLine = true, textStyle = MaterialTheme.typography.bodyMedium.copy(color = c.ink),
                    cursorBrush = SolidColor(c.accent), modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { scope.launch { app.browser.navigate(url) } }))
            }
            IconButton({ app.browser.home() }) { Icon(Icons.Rounded.Home, "Início") }
            IconButton({ scope.launch { app.browser.reload() } }) { Icon(if (ui.loading) Icons.Rounded.Close else Icons.Rounded.Refresh, "Recarregar") }
        }
        if (ui.loading) LinearProgressIndicator(progress = { ui.progress / 100f }, Modifier.fillMaxWidth().height(2.dp), color = c.accent)
        if (ui.agentControl) {
            Row(Modifier.fillMaxWidth().background(c.accentSoft).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.SmartToy, null, Modifier.size(16.dp), tint = c.accent); Spacer(Modifier.width(6.dp))
                Text("O agente está controlando o browser", fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text("Assumir", fontSize = 12.sp, color = c.accent, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { app.browser.setAgentControl(false) })
            }
        }
        AndroidView(factory = { ctx -> app.browser.view(ctx) }, modifier = Modifier.fillMaxSize())
    }
}

/** Gerenciador de arquivos + editor de texto do workspace. */
@Composable
fun FilesPane(root: File, guestRoot: String, onPreview: (File) -> Unit) {
    val c = Cue.c
    val ctx = LocalContext.current
    var dir by remember { mutableStateOf(root) }
    var editing by remember { mutableStateOf<File?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(2000); tick++ } }
    val files = remember(dir, tick) { (dir.listFiles()?.toList() ?: emptyList()).sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) }
    val e = editing
    if (e != null) { FileEditor(e, onClose = { editing = null }, onPreview = { onPreview(e) }); return }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (dir != root) IconButton({ dir = dir.parentFile ?: root }) { Icon(Icons.Rounded.ArrowUpward, "Subir") }
            Text(guestRoot + dir.absolutePath.removePrefix(root.absolutePath), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = c.ink3,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        if (files.isEmpty()) EmptyState(Icons.Rounded.FolderOpen, "Pasta vazia", "Os arquivos criados pelo agente aparecem aqui.")
        LazyColumn(contentPadding = PaddingValues(horizontal = 12.dp)) {
            items(files, key = { it.absolutePath }) { f ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                    if (f.isDirectory) dir = f else if (isText(f)) editing = f else openExternal(ctx, f)
                }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(fileIcon(f), null, tint = if (f.isDirectory) c.accent else c.ink2)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (f.isFile) Text(humanSize(f.length()), fontSize = 12.sp, color = c.ink3)
                    }
                    if (f.isFile && (f.extension in listOf("html", "htm", "svg", "png", "jpg", "jpeg", "gif", "webp", "pdf"))) {
                        IconButton({ onPreview(f) }) { Icon(Icons.Rounded.Visibility, "Pré-visualizar", tint = c.ink3) }
                    }
                    if (f.isFile) IconButton({ openExternal(ctx, f, share = true) }) { Icon(Icons.Rounded.IosShare, "Compartilhar", tint = c.ink3) }
                }
            }
        }
    }
}

@Composable
private fun FileEditor(f: File, onClose: () -> Unit, onPreview: () -> Unit) {
    val c = Cue.c
    var text by remember(f) { mutableStateOf(runCatching { f.readText() }.getOrDefault("")) }
    var dirty by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Voltar") }
            Text(f.name + if (dirty) " •" else "", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (f.extension in listOf("html", "htm", "svg", "md")) IconButton(onPreview) { Icon(Icons.Rounded.Visibility, "Pré-visualizar") }
            TextButton({ f.writeText(text); dirty = false }, enabled = dirty) { Text("Salvar") }
        }
        val lines = text.count { it == '\n' } + 1
        Row(Modifier.fillMaxSize().background(c.surface).verticalScroll(rememberScrollState())) {
            Text((1..lines).joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = c.ink3,
                modifier = Modifier.background(c.surface2).padding(horizontal = 8.dp, vertical = 8.dp))
            Box(Modifier.horizontalScroll(rememberScrollState())) {
                BasicTextField(text, { text = it; dirty = true }, textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = c.ink),
                    cursorBrush = SolidColor(c.accent), modifier = Modifier.padding(8.dp).widthIn(min = 600.dp))
            }
        }
    }
}

fun isText(f: File) = f.extension.lowercase() in setOf("txt", "md", "py", "js", "ts", "json", "html", "htm", "css", "sh", "kt", "java", "c", "cpp", "h",
    "yml", "yaml", "toml", "xml", "csv", "svg", "ini", "env", "log", "sql", "rs", "go", "rb", "php", "jsx", "tsx", "") && f.length() < 2_000_000

fun fileIcon(f: File) = when {
    f.isDirectory -> Icons.Rounded.Folder
    f.extension in listOf("png", "jpg", "jpeg", "gif", "webp", "svg") -> Icons.Rounded.Image
    f.extension in listOf("html", "htm") -> Icons.Rounded.Language
    f.extension == "pdf" -> Icons.Rounded.PictureAsPdf
    f.extension in listOf("py", "js", "ts", "sh", "kt", "json", "css") -> Icons.Rounded.Code
    else -> Icons.Rounded.Description
}

fun humanSize(b: Long) = when { b < 1024 -> "$b B"; b < 1024 * 1024 -> "${b / 1024} KB"; else -> "%.1f MB".format(b / 1048576.0) }

fun openExternal(ctx: android.content.Context, f: File, share: Boolean = false) {
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
    val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension) ?: "*/*"
    val i = if (share) Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
            else Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { ctx.startActivity(Intent.createChooser(i, f.name)) }
}

/** Terminal avulso (atalho da Home), como o terminal do Acode. */
@Composable
fun StandaloneTerminalScreen(nav: NavController) {
    val c = Cue.c
    Column(Modifier.fillMaxSize().background(Color(0xFF0F1115)).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Voltar", tint = Color.White) }
            Text("Terminal", color = Color.White, style = MaterialTheme.typography.titleMedium)
        }
        TerminalView(key = "standalone", cwd = "/root/workspace", modifier = Modifier.fillMaxSize())
    }
}

@Composable
fun FilesScreen(nav: NavController) {
    val app = CueApp.instance
    Scaffold(containerColor = Cue.c.bg, topBar = { CueTopBar("Arquivos", { nav.popBackStack() }) }) { pad ->
        Box(Modifier.padding(pad)) { FilesPane(app.sandbox.publicDir, "/root", onPreview = { f -> openExternal(nav.context, f) }) }
    }
}
