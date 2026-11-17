package dev.cueopen.app.ui

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.cueopen.app.CueApp
import dev.cueopen.app.pages.FluwyPage
import dev.cueopen.app.pages.PageHtml
import dev.cueopen.app.ui.theme.Cue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private fun kindLabel(k: String) = when (k) { FluwyPage.KIND_KANBAN -> "Kanban"; FluwyPage.KIND_CHECKLIST -> "Checklist"; FluwyPage.KIND_TABLE -> "Tabela"; FluwyPage.KIND_HTML -> "Página"; else -> "Documento" }
private fun kindIcon(k: String) = when (k) { FluwyPage.KIND_KANBAN -> Icons.Rounded.ViewKanban; FluwyPage.KIND_CHECKLIST -> Icons.Rounded.Checklist; FluwyPage.KIND_TABLE -> Icons.Rounded.TableChart; FluwyPage.KIND_HTML -> Icons.Rounded.Web; else -> Icons.Rounded.Article }
private fun ago(t: Long): String {
    val m = (System.currentTimeMillis() - t) / 60_000
    return when { m < 1 -> "agora"; m < 60 -> "há $m min"; m < 1440 -> "há ${m / 60} h"; else -> java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(t)) }
}

/** Fluwy Pages: biblioteca de páginas (de um chat/seção ou de todos), agrupadas em Hoje / Esta semana / Antigas. */
@Composable
fun PagesScreen(nav: NavController, spaceId: String?) {
    val app = CueApp.instance
    val st by app.store.state.collectAsStateWithLifecycle()
    val c = Cue.c
    // Páginas só existem dentro de um chat/seção: sem conversa, não há lista geral.
    if (spaceId.isNullOrBlank()) { LaunchedEffect(Unit) { nav.popBackStack() }; return }
    var query by remember { mutableStateOf("") }
    val pages = st.pages.filter { it.spaceId == spaceId }
        .filter { query.isBlank() || it.title.contains(query, true) }
        .sortedWith(compareByDescending<FluwyPage> { it.pinned }.thenByDescending { it.updatedAt })
    val now = System.currentTimeMillis()
    val groups = listOf("Fixadas" to pages.filter { it.pinned },
        "Hoje" to pages.filter { !it.pinned && now - it.updatedAt < 86_400_000L },
        "Esta semana" to pages.filter { !it.pinned && now - it.updatedAt in 86_400_000L until 7 * 86_400_000L },
        "Antigas" to pages.filter { !it.pinned && now - it.updatedAt >= 7 * 86_400_000L })
    Scaffold(containerColor = c.bg, topBar = { CueTopBar("Pages desta conversa", { nav.popBackStack() }) }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text("Páginas que os agentes criam e mantêm atualizadas: relatórios, quadros kanban, checklists, tabelas e páginas livres. Peça no chat: \"crie uma página com…\".",
                    fontSize = 12.sp, color = c.ink3)
                Spacer(Modifier.height(6.dp))
                CueField(query, { query = it }, "Buscar por título")
            }
            if (pages.isEmpty()) item { EmptyState(Icons.Rounded.Article, "Nenhuma página ainda", "Peça a um agente para criar uma página, ou use uma seção: o plano do time vira um quadro kanban aqui.") }
            groups.filter { it.second.isNotEmpty() }.forEach { (label, list) ->
                item { SectionLabel(label) }
                items(list, key = { it.id }) { p ->
                    CueCard(onClick = { nav.navigate("page/${p.id}") }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(kindIcon(p.kind), null, tint = c.accent); Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(p.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${kindLabel(p.kind)} · v${p.version} · ${p.updatedBy.ifBlank { "?" }} · ${ago(p.updatedAt)}", fontSize = 12.sp, color = c.ink3)
                            }
                            if (p.pinned) Icon(Icons.Rounded.PushPin, "Fixada", Modifier.size(16.dp), tint = c.ink3)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PageScreen(nav: NavController, pageId: String) {
    val app = CueApp.instance
    val st by app.store.state.collectAsStateWithLifecycle()
    val c = Cue.c
    val ctx = LocalContext.current
    val p = st.pages.firstOrNull { it.id == pageId }
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var ask by remember { mutableStateOf("") }
    var cardOpen by remember { mutableStateOf<String?>(null) }
    var showChat by remember { mutableStateOf(false) }
    fun op(vararg o: kotlinx.serialization.json.JsonObject) { app.store.applyPageOps(pageId, o.toList(), app.store.settings.userName.ifBlank { "Você" }) }
    if (p == null) {
        Scaffold(containerColor = c.bg, topBar = { CueTopBar("Página", { nav.popBackStack() }) }) { pad ->
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) { Text("Página removida.", color = c.ink3) }
        }
        return
    }
    Scaffold(containerColor = c.bg, topBar = {
        CueTopBar(p.title, { nav.popBackStack() }) {
            IconButton({
                runCatching {
                    val f = java.io.File(ctx.cacheDir, "pages").apply { mkdirs() }.resolve(p.title.replace(Regex("[^\\p{L}\\p{N}_-]+"), "_").take(50) + ".html")
                    f.writeText(PageHtml.export(p))
                    val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
                    ctx.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/html")
                        .putExtra(android.content.Intent.EXTRA_STREAM, uri).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION), "Compartilhar página"))
                }
            }) { Icon(Icons.Rounded.IosShare, "Compartilhar") }
            IconButton({ menu = true }) { Icon(Icons.Rounded.MoreVert, "Mais") }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem({ Text("Renomear") }, { menu = false; rename = true }, leadingIcon = { Icon(Icons.Rounded.Edit, null) })
                DropdownMenuItem({ Text(if (p.pinned) "Desafixar" else "Fixar") }, { menu = false; app.store.upsertPage(p.copy(pinned = !p.pinned)) }, leadingIcon = { Icon(Icons.Rounded.PushPin, null) })
                DropdownMenuItem({ Text("Abrir o chat") }, { menu = false; nav.navigate("chat/${p.spaceId}") }, leadingIcon = { Icon(Icons.Rounded.Forum, null) })
                DropdownMenuItem({ Text("Apagar", color = c.err) }, { menu = false; app.store.deletePage(p.id); nav.popBackStack() }, leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = c.err) })
            }
        }
    }, bottomBar = {
        // "Pedir mudança": a mensagem vai para o chat dono da página; o agente edita a mesma página.
        Column(Modifier.fillMaxWidth().background(c.bg).navigationBarsPadding().imePadding()) {
        if (showChat) PageConversation(p.spaceId)
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ showChat = !showChat }) { Icon(if (showChat) Icons.Rounded.ExpandMore else Icons.Rounded.Forum, "Conversa com o agente", tint = c.ink2) }
            Box(Modifier.weight(1f)) { CueField(ask, { ask = it }, "Pedir mudança ao agente") }
            IconButton(enabled = ask.isNotBlank(), onClick = {
                app.agent.sendOrQueue(p.spaceId, "Sobre a página \"${p.title}\" (${p.id}): ${ask.trim()}\n[Edite esta MESMA página: page_read(\"${p.id}\") e page_update(\"${p.id}\"). Não crie outra página.]")
                ask = ""; showChat = true
            }) { Icon(Icons.Rounded.Send, "Enviar", tint = c.accent) }
        }
        }
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Text("${kindLabel(p.kind)} · versão ${p.version} · ${p.updatedBy.ifBlank { "?" }} · ${ago(p.updatedAt)}", fontSize = 12.sp, color = c.ink3, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            when (p.kind) {
                FluwyPage.KIND_KANBAN -> Row(Modifier.fillMaxSize().horizontalScroll(rememberScrollState()).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    p.columns.forEach { col ->
                        val cs = p.cards.filter { it.column == col }
                        Column(Modifier.width(280.dp).fillMaxHeight().clip(RoundedCornerShape(16.dp)).background(c.surface).padding(10.dp).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("$col · ${cs.size}", fontWeight = FontWeight.SemiBold, color = c.ink2, fontSize = 13.sp)
                            cs.forEach { card ->
                                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface2).clickable { cardOpen = card.id }.padding(10.dp)) {
                                    Text(card.title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    if (card.assignee.isNotBlank()) Text("@${card.assignee}", fontSize = 12.sp, color = c.accent)
                                    if (card.note.isNotBlank()) Text(card.note, fontSize = 12.sp, color = c.ink2, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
                FluwyPage.KIND_CHECKLIST -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp)) {
                    items(p.cards, key = { it.id }) { card ->
                        val done = card.column == p.columns.last()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(done, { v -> op(buildJsonObject { put("op", if (v) "check" else "uncheck"); put("card_id", card.id) }) })
                            Column(Modifier.weight(1f)) {
                                Text(card.title, color = if (done) c.ink3 else c.ink)
                                if (card.assignee.isNotBlank() || card.note.isNotBlank()) Text(listOf(card.assignee.takeIf { it.isNotBlank() }?.let { "@$it" }, card.note.takeIf { it.isNotBlank() }).filterNotNull().joinToString(" · "), fontSize = 12.sp, color = c.ink3)
                            }
                        }
                    }
                }
                FluwyPage.KIND_TABLE -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(12.dp)) {
                    val w = 150.dp
                    Row { p.headers.forEach { h -> Text(h, Modifier.width(w).padding(6.dp), fontWeight = FontWeight.SemiBold, fontSize = 13.sp) } }
                    HorizontalDivider(color = c.line)
                    p.rows.forEach { r -> Row { r.forEach { cell -> Text(cell, Modifier.width(w).padding(6.dp), fontSize = 13.sp) } }; HorizontalDivider(color = c.line) }
                }
                FluwyPage.KIND_HTML -> Box(Modifier.weight(1f)) { HtmlPageView(p) }
                else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) { CueMarkdown(p.markdown) }
            }
        }
    }
    if (rename) {
        var t by remember { mutableStateOf(p.title) }
        AlertDialog({ rename = false }, title = { Text("Renomear") }, text = { CueField(t, { t = it }, "Título") },
            confirmButton = { TextButton({ if (t.isNotBlank()) op(buildJsonObject { put("op", "rename"); put("title", t.trim()) }); rename = false }) { Text("Salvar") } },
            dismissButton = { TextButton({ rename = false }) { Text("Cancelar") } })
    }
    cardOpen?.let { cid ->
        val card = p.cards.firstOrNull { it.id == cid } ?: run { cardOpen = null; return@let }
        AlertDialog({ cardOpen = null }, title = { Text(card.title) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (card.note.isNotBlank()) Text(card.note, fontSize = 13.sp)
                Text("Mover para:", fontSize = 12.sp, color = c.ink3)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    p.columns.forEach { col -> FilterChip(card.column == col, { op(buildJsonObject { put("op", "move_card"); put("card_id", card.id); put("column", col) }); cardOpen = null }, label = { Text(col) }) }
                }
                Text("Atualizado por ${card.updatedBy.ifBlank { "?" }} · ${ago(card.updatedAt)}", fontSize = 11.sp, color = c.ink3)
            }
        }, confirmButton = { TextButton({ cardOpen = null }) { Text("Fechar") } },
            dismissButton = { TextButton({ op(buildJsonObject { put("op", "remove_card"); put("card_id", card.id) }); cardOpen = null }) { Text("Remover", color = c.err) } })
    }
}

/** Últimas mensagens do chat dono da página: o usuário conversa com o agente sem sair da página. */
@Composable
private fun PageConversation(spaceId: String) {
    val app = CueApp.instance
    val st by app.store.state.collectAsStateWithLifecycle()
    val c = Cue.c
    val session = st.sessions.firstOrNull { it.id == spaceId } ?: return
    val running = session.status == dev.cueopen.app.data.SessionStatus.RUNNING
    val items = session.items.filter { it is dev.cueopen.app.data.ChatItem.User || it is dev.cueopen.app.data.ChatItem.Assistant || (it is dev.cueopen.app.data.ChatItem.Step && it.tool == "page") }.takeLast(6)
    Column(Modifier.fillMaxWidth().heightIn(max = 260.dp).padding(horizontal = 12.dp).clip(RoundedCornerShape(14.dp)).background(c.surface).verticalScroll(rememberScrollState()).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { i ->
            when (i) {
                is dev.cueopen.app.data.ChatItem.User -> Text("Você: " + i.text.take(300), fontSize = 13.sp, color = c.ink2)
                is dev.cueopen.app.data.ChatItem.Assistant -> Text(i.text.take(600), fontSize = 13.sp)
                is dev.cueopen.app.data.ChatItem.Step -> Text("• " + i.title + " (" + i.result + ")", fontSize = 12.sp, color = c.accent)
                else -> {}
            }
        }
        if (running) Text("Agente trabalhando…", fontSize = 12.sp, color = c.ink3)
        if (items.isEmpty() && !running) Text("Sem mensagens ainda. Peça uma mudança abaixo.", fontSize = 12.sp, color = c.ink3)
    }
}

/**
 * Ponte da página HTML com o app (window.Fluwy): ler/salvar os dados da página e mandar mensagem ao agente.
 * Só existe dentro do visualizador de páginas e só toca na própria página.
 */
private class FluwyBridge(private val pageId: String) {
    private var lastAsk = 0L
    @android.webkit.JavascriptInterface fun getData(): String = CueApp.instance.store.page(pageId)?.data.orEmpty()
    @android.webkit.JavascriptInterface fun saveData(json: String): Boolean {
        if (json.length > 60_000) return false
        val r = CueApp.instance.store.applyPageOps(pageId, listOf(buildJsonObject { put("op", "set_data"); put("data", json) }), CueApp.instance.store.settings.userName.ifBlank { "Você" })
        return r.isSuccess
    }
    @android.webkit.JavascriptInterface fun ask(text: String): Boolean {
        val now = System.currentTimeMillis()
        if (text.isBlank() || now - lastAsk < 3_000) return false
        lastAsk = now
        val p = CueApp.instance.store.page(pageId) ?: return false
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            CueApp.instance.agent.sendOrQueue(p.spaceId, "[Mensagem enviada pela página \"${p.title}\" (${p.id})] ${text.take(2_000)}\n[Se for para mudar a página, use page_update(\"${p.id}\").]")
        }
        return true
    }
    @android.webkit.JavascriptInterface fun pageInfo(): String = CueApp.instance.store.page(pageId)?.let { "{\"id\":\"${it.id}\",\"version\":${it.version}}" } ?: "{}"
}

private const val FLUWY_SHIM = "<script>window.Fluwy={getData:function(){try{return JSON.parse(FluwyBridge.getData()||'null')}catch(e){return FluwyBridge.getData()}}," +
    "saveData:function(d){return FluwyBridge.saveData(typeof d==='string'?d:JSON.stringify(d))},ask:function(t){return FluwyBridge.ask(String(t))}," +
    "info:function(){return JSON.parse(FluwyBridge.pageInfo())}};</script>"

/** Página HTML livre: WebView isolado (sem acesso a arquivos), com JavaScript e a ponte window.Fluwy, sempre com viewport de celular. */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
private fun HtmlPageView(p: FluwyPage) {
    AndroidView(factory = { ctx ->
        WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = WebViewClient()
            addJavascriptInterface(FluwyBridge(p.id), "FluwyBridge")
        }
    }, update = { wv ->
        // Recarrega só quando o HTML muda (salvar dados não reinicia a página do usuário).
        val key = p.html.hashCode()
        if (wv.tag != key) {
            wv.tag = key
            val html = PageHtml.ensureMobile(p.html).let { h -> if (h.contains("<head>", true)) h.replaceFirst(Regex("(?i)<head>"), "<head>$FLUWY_SHIM") else FLUWY_SHIM + h }
            wv.loadDataWithBaseURL("https://pages.fluwy.local/", html, "text/html", "utf-8", null)
        }
    }, modifier = Modifier.fillMaxSize())
}
