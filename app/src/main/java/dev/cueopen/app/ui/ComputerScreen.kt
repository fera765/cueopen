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
                else -> FilesPane(app.sandbox.workspaceFor(sessionId), "/root/workspace/$sessionId")
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
                // setTextSize cria o renderer (monospace); setTypeface antes disso dá NPE
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
    var menu by remember { mutableStateOf(false) }
    var devtools by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
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
            IconButton({ scope.launch { app.browser.reload() } }) { Icon(if (ui.loading) Icons.Rounded.Close else Icons.Rounded.Refresh, "Recarregar") }
            Box {
                IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, "Mais") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("Página inicial") }, { menu = false; app.browser.home() }, leadingIcon = { Icon(Icons.Rounded.Home, null) })
                    DropdownMenuItem({ Text(if (devtools) "Ocultar Rede/Console" else "Rede e Console") }, { menu = false; devtools = !devtools }, leadingIcon = { Icon(Icons.Rounded.NetworkCheck, null) })
                    DropdownMenuItem({ Text(if (ui.desktop) "Versão mobile" else "Versão desktop") }, { menu = false; app.browser.setDesktop(!ui.desktop) }, leadingIcon = { Icon(Icons.Rounded.DesktopWindows, null) })
                    DropdownMenuItem({ Text("Abrir no navegador do sistema") }, {
                        menu = false
                        runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(ui.url))) }
                    }, leadingIcon = { Icon(Icons.Rounded.OpenInNew, null) })
                }
            }
        }
        // status de conectividade
        if (!ui.online) Row(Modifier.fillMaxWidth().background(c.err.copy(alpha = .15f)).padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text("Sem conexão com a internet", fontSize = 12.sp, color = c.err)
        }
        if (ui.loading) LinearProgressIndicator(progress = { ui.progress / 100f }, Modifier.fillMaxWidth().height(2.dp), color = c.accent)
        if (ui.agentControl) {
            Row(Modifier.fillMaxWidth().background(c.accentSoft).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.SmartToy, null, Modifier.size(16.dp), tint = c.accent); Spacer(Modifier.width(6.dp))
                Text("O agente está controlando o browser", fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text("Assumir", fontSize = 12.sp, color = c.accent, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { app.browser.setAgentControl(false) })
            }
        }
        AndroidView(factory = { c2 -> app.browser.view(c2) }, modifier = Modifier.fillMaxWidth().weight(1f))
        if (devtools) DevToolsPanel(Modifier.fillMaxWidth().height(300.dp))
    }
}

/** Painel estilo DevTools: requisições de rede (com filtro por tipo) e console JS. */
@Composable
private fun DevToolsPanel(modifier: Modifier) {
    val app = CueApp.instance
    val net by app.browser.net.collectAsStateWithLifecycle()
    val console by app.browser.console.collectAsStateWithLifecycle()
    val ui by app.browser.ui.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf("net") }
    var type by remember { mutableStateOf("all") }
    var selected by remember { mutableStateOf<dev.cueopen.app.browser.BrowserHost.NetEntry?>(null) }
    val c = Cue.c
    Column(modifier.background(c.surface).border(1.dp, c.line)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Chip("Rede (${net.size})", tab == "net") { tab = "net" }
            Spacer(Modifier.width(6.dp))
            Chip("Console (${console.size})", tab == "console") { tab = "console" }
            Spacer(Modifier.weight(1f))
            Text(ui.netType, fontSize = 11.sp, color = if (ui.online) c.ok else c.err)
            IconButton({ app.browser.clearNet() }) { Icon(Icons.Rounded.DeleteSweep, "Limpar", Modifier.size(18.dp)) }
        }
        if (tab == "net") {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("all" to "Todos", "doc" to "Doc", "xhr" to "XHR/Fetch", "js" to "JS", "css" to "CSS", "img" to "Img", "font" to "Fonte", "media" to "Mídia", "other" to "Outros").forEach { (k, l) ->
                    Text(l, Modifier.clip(CircleShape).background(if (type == k) c.ink else c.surface2).clickable { type = k }.padding(horizontal = 10.dp, vertical = 4.dp),
                        fontSize = 11.sp, color = if (type == k) c.bg else c.ink)
                }
            }
            val list = net.filter { type == "all" || it.type == type }.asReversed()
            val sel = selected
            if (sel != null) {
                Column(Modifier.fillMaxWidth().padding(10.dp).verticalScroll(rememberScrollState())) {
                    Text("← voltar", color = c.accent, fontSize = 12.sp, modifier = Modifier.clickable { selected = null })
                    Text(sel.url, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                    Text("Método: ${sel.method} · Tipo: ${sel.type} · Status: ${sel.status ?: "—"}", fontSize = 12.sp)
                    Text("Tempo: ${sel.durationMs?.let { "$it ms" } ?: "—"} · Tamanho: ${sel.sizeBytes?.let { humanSize(it) } ?: "—"}", fontSize = 12.sp)
                    sel.error?.let { Text("Erro: $it", color = c.err, fontSize = 12.sp) }
                }
            } else LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.id }) { e ->
                    val bad = (e.status ?: 0) >= 400 || e.error != null
                    Row(Modifier.fillMaxWidth().clickable { selected = e }.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${e.status ?: if (e.error != null) "ERR" else "·"}", Modifier.width(34.dp), fontSize = 11.sp, color = if (bad) c.err else c.ok, fontFamily = FontFamily.Monospace)
                        Text(e.type, Modifier.width(42.dp), fontSize = 11.sp, color = c.ink3)
                        Text(e.url.substringAfter("://").take(120), Modifier.weight(1f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Monospace)
                        Text(e.durationMs?.let { "${it}ms" } ?: "", fontSize = 10.sp, color = c.ink3)
                    }
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                items(console.asReversed()) { m ->
                    Text("[${m.level}] ${m.message}", fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                        color = when (m.level) { "error" -> c.err; "warning" -> c.warn; else -> c.ink2 }, modifier = Modifier.padding(vertical = 2.dp))
                }
            }
        }
    }
}

/** Gerenciador de arquivos + editor de texto do workspace. */
@Composable
fun FilesPane(root: File, guestRoot: String) {
    val c = Cue.c
    val ctx = LocalContext.current
    var dir by remember { mutableStateOf(root) }
    var editing by remember { mutableStateOf<File?>(null) }
    var viewing by remember { mutableStateOf<File?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(2000); tick++ } }
    val files = remember(dir, tick) { (dir.listFiles()?.toList() ?: emptyList()).sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) }
    val e = editing
    if (e != null) { FileEditor(e, onClose = { editing = null }); return }
    val v = viewing
    if (v != null) { FileViewer(v, onClose = { viewing = null }, onEdit = { editing = v; viewing = null }); return }
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
                    if (f.isDirectory) dir = f else viewing = f
                }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(fileIcon(f), null, tint = if (f.isDirectory) c.accent else c.ink2)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (f.isFile) Text(humanSize(f.length()), fontSize = 12.sp, color = c.ink3)
                    }
                    if (f.isFile) IconButton({ openExternal(ctx, f, share = true) }) { Icon(Icons.Rounded.IosShare, "Compartilhar", tint = c.ink3) }
                }
            }
        }
    }
}

@Composable
private fun FileEditor(f: File, onClose: () -> Unit) {
    val c = Cue.c
    var text by remember(f) { mutableStateOf(runCatching { f.readText() }.getOrDefault("")) }
    var dirty by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Voltar") }
            Text(f.name + if (dirty) " •" else "", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
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
        Box(Modifier.padding(pad)) { FilesPane(app.sandbox.publicDir, "/root") }
    }
}
