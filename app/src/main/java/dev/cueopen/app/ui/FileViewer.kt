package dev.cueopen.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import dev.cueopen.app.ui.theme.Cue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val imageExt = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
/** Contêineres que o player do Android costuma abrir; o codec interno ainda pode não ser suportado (tratado com mensagem). */
private val videoExt = setOf("mp4", "m4v", "webm", "mkv", "3gp", "mov")
private val audioExt = setOf("mp3", "m4a", "aac", "ogg", "wav", "flac", "opus")

/**
 * Visualizador nativo dos artefatos do agente (nunca usa o browser do agente):
 * imagens com zoom, SVG, PDF e texto/código com syntax highlighting. Baixar (Salvar como…) e compartilhar.
 */
@Composable
fun FileViewer(f: File, onClose: () -> Unit, onEdit: () -> Unit) {
    val c = Cue.c
    val ctx = LocalContext.current
    val ext = f.extension.lowercase()
    val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mime)) { uri ->
        if (uri != null) runCatching {
            ctx.contentResolver.openOutputStream(uri)!!.use { out -> f.inputStream().use { it.copyTo(out) } }
            android.widget.Toast.makeText(ctx, "Arquivo salvo", android.widget.Toast.LENGTH_SHORT).show()
        }.onFailure { android.widget.Toast.makeText(ctx, "Falha ao salvar: ${it.message}", android.widget.Toast.LENGTH_LONG).show() }
    }
    var rendered by remember(f) { mutableStateOf(true) } // markdown: renderizado x código
    Column(Modifier.fillMaxSize().background(c.bg)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Voltar") }
            Column(Modifier.weight(1f)) {
                Text(f.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${humanSize(f.length())} · $ext", fontSize = 11.sp, color = c.ink3)
            }
            if (ext == "md" || ext == "html" || ext == "htm") IconButton({ rendered = !rendered }) { Icon(if (rendered) Icons.Rounded.Code else Icons.Rounded.Article, "Alternar") }
            if (isText(f)) IconButton(onEdit) { Icon(Icons.Rounded.Edit, "Editar") }
            IconButton({ openExternal(ctx, f, share = true) }) { Icon(Icons.Rounded.IosShare, "Compartilhar") }
            FilledTonalButton({ save.launch(f.name) }, shape = RoundedCornerShape(50), contentPadding = PaddingValues(horizontal = 12.dp),
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = c.ink, contentColor = c.bg)) {
                Icon(Icons.Rounded.Download, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Baixar", fontSize = 13.sp)
            }
            Spacer(Modifier.width(6.dp))
        }
        Box(Modifier.fillMaxSize().padding(8.dp).clip(RoundedCornerShape(16.dp)).background(c.surface)) {
            when {
                ext in imageExt -> ImageView(f)
                ext in videoExt || ext in audioExt -> MediaPlayerView(f, isVideo = ext in videoExt)
                ext == "svg" -> SvgView(f)
                ext == "pdf" -> PdfView(f)
                ext in listOf("zip","jar","apk") -> ZipView(f)
                (ext == "html" || ext == "htm") && rendered -> HtmlPreview(f)
                ext == "md" && rendered -> MarkdownDocView(remember(f) { runCatching { f.readText() }.getOrDefault("") }, Modifier.fillMaxSize())
                isText(f) || f.length() < 512_000 && looksText(f) -> CodeView(f)
                else -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(fileIcon(f), null, Modifier.size(48.dp), tint = c.ink3)
                    Spacer(Modifier.height(8.dp))
                    Text("Pré-visualização indisponível para este tipo.", color = c.ink2)
                    TextButton({ openExternal(ctx, f) }) { Text("Abrir com outro app", color = c.accent) }
                }
            }
        }
    }
}

private fun looksText(f: File): Boolean = runCatching {
    val b = f.inputStream().use { it.readNBytesCompat(2048) }
    b.isNotEmpty() && b.count { it == 0.toByte() } == 0
}.getOrDefault(false)

private fun java.io.InputStream.readNBytesCompat(n: Int): ByteArray { val buf = ByteArray(n); val r = read(buf); return if (r <= 0) ByteArray(0) else buf.copyOf(r) }

@Composable
private fun ZoomBox(content: @Composable () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var ox by remember { mutableFloatStateOf(0f) }; var oy by remember { mutableFloatStateOf(0f) }
    Box(Modifier.fillMaxSize().pointerInput(Unit) {
        detectTransformGestures { _, pan, zoom, _ ->
            scale = (scale * zoom).coerceIn(1f, 8f)
            if (scale == 1f) { ox = 0f; oy = 0f } else { ox += pan.x; oy += pan.y }
        }
    }.graphicsLayer { scaleX = scale; scaleY = scale; translationX = ox; translationY = oy }, contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun ImageView(f: File) {
    val bmp by produceState<Bitmap?>(null, f) {
        value = withContext(Dispatchers.IO) {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, o)
            var sample = 1; while (o.outWidth / sample > 2560 || o.outHeight / sample > 2560) sample *= 2
            BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }
    val b = bmp
    if (b == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Cue.c.accent) }
    else ZoomBox { Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
}

@Composable
private fun SvgView(f: File) {
    val bmp by produceState<Bitmap?>(null, f) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val svg = com.caverock.androidsvg.SVG.getFromString(f.readText())
                val w = svg.documentWidth.takeIf { it > 0 } ?: 1024f
                val h = svg.documentHeight.takeIf { it > 0 } ?: 1024f
                val scale = (2048f / maxOf(w, h)).coerceAtMost(4f)
                svg.setDocumentWidth("100%"); svg.setDocumentHeight("100%")
                val bm = Bitmap.createBitmap((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                val cv = android.graphics.Canvas(bm)
                svg.renderToCanvas(cv, android.graphics.RectF(0f, 0f, bm.width.toFloat(), bm.height.toFloat()))
                bm
            }.getOrNull()
        }
    }
    val b = bmp
    if (b == null) CodeView(f) else ZoomBox { Image(b.asImageBitmap(), null, Modifier.fillMaxSize().background(Color.White), contentScale = ContentScale.Fit) }
}

@Composable
private fun PdfView(f: File) {
    val pages by produceState<List<Bitmap>?>(null, f) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                PdfRenderer(ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)).use { r ->
                    (0 until minOf(r.pageCount, 30)).map { i ->
                        r.openPage(i).use { p ->
                            val s = 1600f / p.width
                            Bitmap.createBitmap((p.width * s).toInt(), (p.height * s).toInt(), Bitmap.Config.ARGB_8888).also {
                                it.eraseColor(android.graphics.Color.WHITE)
                                p.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            }
                        }
                    }
                }
            }.getOrDefault(emptyList())
        }
    }
    val l = pages
    if (l == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Cue.c.accent) }
    else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(l) { Image(it.asImageBitmap(), null, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth) }
    }
}

@Composable
private fun CodeView(f: File) {
    val c = Cue.c
    val text = remember(f) { runCatching { f.readText() }.getOrDefault("").take(400_000) }
    val lang = remember(f) { f.extension.lowercase() }
    val palette = if (c.dark) SyntaxPalette.dark else SyntaxPalette.light
    val highlighted = remember(text, lang, c.dark) { Syntax.highlight(text, lang, palette) }
    val lines = remember(text) { text.count { it == '\n' } + 1 }
    Row(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text((1..lines).joinToString("\n"), fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp, color = c.ink3,
            modifier = Modifier.background(c.surface2).padding(horizontal = 8.dp, vertical = 10.dp))
        SelectionContainer(Modifier.horizontalScroll(rememberScrollState())) {
            Text(highlighted, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp, color = c.ink, softWrap = false,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp))
        }
    }
}

data class SyntaxPalette(val keyword: Color, val string: Color, val comment: Color, val number: Color, val type: Color, val tag: Color, val attr: Color) {
    companion object {
        val light = SyntaxPalette(Color(0xFFB0306A), Color(0xFF1E7A3C), Color(0xFF8C8A84), Color(0xFFB45A00), Color(0xFF2457C5), Color(0xFF2457C5), Color(0xFF8A4BBF))
        val dark = SyntaxPalette(Color(0xFFFF7AB2), Color(0xFF8BD49C), Color(0xFF7D7973), Color(0xFFF2A65A), Color(0xFF79B8FF), Color(0xFF79B8FF), Color(0xFFC79BFF))
    }
}

/** Highlighter leve por regex (keywords, strings, comentários, números, tags/atributos). */
object Syntax {
    private val kw = mapOf(
        "kt" to "fun val var class object interface when if else for while return import package private public internal override suspend data sealed enum null true false is in as try catch finally throw",
        "java" to "class public private protected static final void int long boolean if else for while return import package new null true false try catch finally throw extends implements interface",
        "js" to "function const let var if else for while return import from export default class new null undefined true false async await try catch finally throw typeof of in",
        "py" to "def class if elif else for while return import from as with try except finally raise None True False and or not in is lambda yield async await pass break continue global",
        "sh" to "if then else elif fi for in do done while case esac function return export local echo exit set unset",
        "go" to "func package import var const type struct interface if else for range return go defer nil true false map chan select switch case",
        "rs" to "fn let mut pub struct enum impl trait use mod if else for while loop match return true false self Self crate async await",
        "c" to "int char void long short float double struct typedef if else for while return include define static const unsigned sizeof switch case break",
        "css" to "important media keyframes from to",
        "sql" to "select from where insert into values update set delete create table join left right inner on group by order limit and or not null as",
    )
    private fun keywordsFor(lang: String) = when (lang) {
        "ts", "jsx", "tsx", "mjs", "cjs" -> kw["js"]; "kts" -> kw["kt"]; "bash", "zsh" -> kw["sh"]; "cpp", "h", "hpp" -> kw["c"]
        else -> kw[lang]
    }?.split(' ')?.toSet() ?: emptySet()

    fun highlight(src: String, lang: String, p: SyntaxPalette): AnnotatedString = buildAnnotatedString {
        append(src)
        fun paint(re: Regex, style: SpanStyle, group: Int = 0) {
            re.findAll(src).forEach { m -> val r = m.groups[group]?.range ?: return@forEach; addStyle(style, r.first, r.last + 1) }
        }
        if (src.length > 300_000) return@buildAnnotatedString
        when (lang) {
            "html", "htm", "xml", "svg" -> {
                paint(Regex("</?[a-zA-Z][\\w:-]*"), SpanStyle(color = p.tag, fontWeight = FontWeight.SemiBold))
                paint(Regex("\\s([\\w:-]+)="), SpanStyle(color = p.attr), 1)
                paint(Regex("\"[^\"]*\"|'[^']*'"), SpanStyle(color = p.string))
                paint(Regex("<!--[\\s\\S]*?-->"), SpanStyle(color = p.comment, fontStyle = FontStyle.Italic))
            }
            "json" -> {
                paint(Regex("-?\\b\\d+(\\.\\d+)?([eE][+-]?\\d+)?\\b|\\btrue\\b|\\bfalse\\b|\\bnull\\b"), SpanStyle(color = p.number))
                paint(Regex("\"(?:[^\"\\\\]|\\\\.)*\""), SpanStyle(color = p.string))
                paint(Regex("(\"(?:[^\"\\\\]|\\\\.)*\")\\s*:"), SpanStyle(color = p.attr), 1)
            }
            "md" -> {
                paint(Regex("(?m)^#{1,6} .*$"), SpanStyle(color = p.keyword, fontWeight = FontWeight.Bold))
                paint(Regex("\\*\\*[^*]+\\*\\*"), SpanStyle(fontWeight = FontWeight.Bold))
                paint(Regex("`[^`\\n]+`"), SpanStyle(color = p.string))
                paint(Regex("\\[[^\\]]+\\]\\([^)]+\\)"), SpanStyle(color = p.type))
            }
            else -> {
                val keys = keywordsFor(lang)
                if (keys.isNotEmpty()) paint(Regex("\\b(" + keys.joinToString("|") { Regex.escape(it) } + ")\\b"), SpanStyle(color = p.keyword, fontWeight = FontWeight.SemiBold))
                paint(Regex("\\b[A-Z][A-Za-z0-9_]+\\b"), SpanStyle(color = p.type))
                paint(Regex("\\b\\d+(\\.\\d+)?\\b"), SpanStyle(color = p.number))
                paint(Regex("\"(?:[^\"\\\\\\n]|\\\\.)*\"|'(?:[^'\\\\\\n]|\\\\.)*'|`[^`]*`"), SpanStyle(color = p.string))
                val comment = when (lang) {
                    "py", "sh", "bash", "zsh", "yml", "yaml", "toml", "rb", "env", "ini", "conf" -> Regex("(?m)#.*$")
                    "sql" -> Regex("(?m)--.*$")
                    "css" -> Regex("/\\*[\\s\\S]*?\\*/")
                    else -> Regex("(?m)//.*$|/\\*[\\s\\S]*?\\*/")
                }
                paint(comment, SpanStyle(color = p.comment, fontStyle = FontStyle.Italic))
            }
        }
    }
}


/**
 * Preview de HTML do workspace num WebView próprio (não é o browser do agente). Carregado via
 * file:// com acesso a arquivos, então CSS/JS/imagens com caminho relativo da pasta funcionam.
 */
@android.annotation.SuppressLint("SetJavaScriptEnabled")
@Composable
private fun HtmlPreview(f: File) {
    androidx.compose.ui.viewinterop.AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx ->
        android.webkit.WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            @Suppress("DEPRECATION") settings.allowFileAccessFromFileURLs = true
            @Suppress("DEPRECATION") settings.allowUniversalAccessFromFileURLs = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            webViewClient = object : android.webkit.WebViewClient() {
                // links para fora do workspace não saem do preview
                override fun shouldOverrideUrlLoading(view: android.webkit.WebView, request: android.webkit.WebResourceRequest) =
                    request.url.scheme !in setOf("file", "http", "https", "data", "about")
            }
            setBackgroundColor(android.graphics.Color.WHITE)
            loadUrl("file://" + f.absolutePath)
        }
    }, update = { it.tag ?: run { it.tag = f.absolutePath } })
}


/** Lista o conteúdo de um .zip/.jar/.apk com ícone por tipo de arquivo. */
@Composable
private fun ZipView(f: File) {
    val c = Cue.c
    data class Entry(val name: String, val size: Long, val dir: Boolean)
    val entries by produceState<List<Entry>?>(null, f) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                java.util.zip.ZipFile(f).use { z -> z.entries().toList().map { Entry(it.name, it.size, it.isDirectory) } }
                    .sortedWith(compareBy({ !it.dir }, { it.name.lowercase() }))
            }.getOrNull()
        }
    }
    val l = entries
    when {
        l == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = c.accent) }
        l.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Arquivo vazio ou ilegível", color = c.ink2) }
        else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item { Text("${l.count { !it.dir }} arquivos · ${l.count { it.dir }} pastas", fontSize = 12.sp, color = c.ink3, modifier = Modifier.padding(6.dp)) }
            items(l.size) { i ->
                val e = l[i]
                val ff = File(e.name)
                val (icon, tint) = if (e.dir) Icons.Rounded.Folder to c.accent else fileIconTint(ff, c)
                Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, null, Modifier.size(20.dp), tint = tint)
                    Spacer(Modifier.width(10.dp))
                    Text(e.name, Modifier.weight(1f), fontSize = 13.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!e.dir) Text(humanSize(e.size), fontSize = 11.sp, color = c.ink3)
                }
                HorizontalDivider(color = c.line.copy(alpha = .5f))
            }
        }
    }
}

/**
 * Player nativo de vídeo/áudio: reproduz direto do arquivo (streaming do disco, nada é carregado inteiro na
 * memória), com play/pausa, barra de progresso, avançar/voltar 10 s e tempo. Codec não suportado vira mensagem.
 */
@Composable
private fun MediaPlayerView(f: File, isVideo: Boolean) {
    val c = Cue.c
    val ctx = LocalContext.current
    var error by remember(f) { mutableStateOf<String?>(null) }
    var playing by remember(f) { mutableStateOf(false) }
    var position by remember(f) { mutableIntStateOf(0) }
    var duration by remember(f) { mutableIntStateOf(0) }
    var info by remember(f) { mutableStateOf("") }
    val holder = remember(f) { arrayOfNulls<android.widget.VideoView>(1) }
    fun fmt(ms: Int): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }
    LaunchedEffect(f) {
        info = withContext(Dispatchers.IO) {
            runCatching {
                val r = android.media.MediaMetadataRetriever()
                try {
                    r.setDataSource(f.absolutePath)
                    val w = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    val h = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    val mime = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_MIMETYPE).orEmpty()
                    listOfNotNull(f.extension.uppercase(), mime.ifBlank { null }, if (w != null && h != null) "${w}×$h" else null, "%.1f MB".format(f.length() / 1_048_576.0)).joinToString(" · ")
                } finally { r.release() }
            }.getOrDefault(f.extension.uppercase() + " · %.1f MB".format(f.length() / 1_048_576.0))
        }
    }
    LaunchedEffect(f, playing) {
        while (playing) { holder[0]?.let { position = it.currentPosition; if (!it.isPlaying && position >= duration - 300) playing = false }; kotlinx.coroutines.delay(300) }
    }
    DisposableEffect(f) { onDispose { runCatching { holder[0]?.stopPlayback() } } }
    Column(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val e = error
            if (e != null) Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                Text("Não foi possível reproduzir", color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.SemiBold)
                Text(e, color = androidx.compose.ui.graphics.Color.LightGray, fontSize = 12.sp)
                TextButton({ openExternal(ctx, f) }) { Text("Abrir com outro app", color = c.accent) }
            } else androidx.compose.ui.viewinterop.AndroidView(modifier = if (isVideo) Modifier.fillMaxWidth() else Modifier.size(1.dp), factory = { vctx ->
                android.widget.VideoView(vctx).apply {
                    setVideoURI(android.net.Uri.fromFile(f))
                    setOnPreparedListener { mp -> duration = mp.duration.coerceAtLeast(0); seekTo(1) }
                    setOnCompletionListener { playing = false; position = duration }
                    setOnErrorListener { _, what, extra ->
                        error = "Formato ou codec não suportado por este aparelho (${f.extension.uppercase()}, erro $what/$extra)."; playing = false; true
                    }
                    holder[0] = this
                }
            })
            if (!isVideo && error == null) Icon(Icons.Rounded.MusicNote, null, Modifier.size(72.dp), tint = androidx.compose.ui.graphics.Color.White)
        }
        if (error == null) Column(Modifier.fillMaxWidth().background(c.surface).padding(horizontal = 12.dp, vertical = 6.dp)) {
            Slider(value = if (duration > 0) position.toFloat() / duration else 0f,
                onValueChange = { v -> position = (v * duration).toInt(); holder[0]?.seekTo(position) }, enabled = duration > 0)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${fmt(position)} / ${fmt(duration)}", fontSize = 12.sp, color = c.ink2, modifier = Modifier.weight(1f))
                IconButton({ holder[0]?.let { v -> position = (v.currentPosition - 10_000).coerceAtLeast(0); v.seekTo(position) } }) { Icon(Icons.Rounded.Replay10, "Voltar 10 s") }
                IconButton({ holder[0]?.let { v -> if (v.isPlaying) { v.pause(); playing = false } else { if (position >= duration - 300) v.seekTo(0); v.start(); playing = true } } }) {
                    Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pausar" else "Reproduzir", Modifier.size(32.dp))
                }
                IconButton({ holder[0]?.let { v -> position = (v.currentPosition + 10_000).coerceAtMost(duration); v.seekTo(position) } }) { Icon(Icons.Rounded.Forward10, "Avançar 10 s") }
            }
            Text(info, fontSize = 11.sp, color = c.ink3, maxLines = 1)
        }
    }
}

