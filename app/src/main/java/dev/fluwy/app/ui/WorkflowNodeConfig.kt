package dev.fluwy.app.ui
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.fluwy.app.FluwyApp
import dev.fluwy.app.ui.theme.Fluwy
import dev.fluwy.app.workflow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ConnectorConfig(node: WfNode, cfg: Map<String, String>, upstream: List<WfNode>, onCfg: (Map<String, String>) -> Unit) {
    val c = Fluwy.c
    val app = FluwyApp.instance
    val kind = cfg["source"].orEmpty().ifBlank { WfConnectors.HUB }
    val id = cfg["conector"].orEmpty()
    val action = cfg["acao"].orEmpty()
    var sources by remember { mutableStateOf<List<WfConnectors.Source>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) { sources = runCatching { WfConnectors.sources(app.store) }.getOrDefault(emptyList()) }
    var actions by remember(kind, id) { mutableStateOf<List<WfConnectors.Action>?>(null) }
    LaunchedEffect(kind, id) { actions = if (id.isBlank()) null else runCatching { WfConnectors.actions(app.store, kind, id) }.getOrDefault(emptyList()) }
    var fields by remember(kind, id, action) { mutableStateOf<List<WfConnectors.Field>?>(null) }
    var fieldsErr by remember(kind, id, action) { mutableStateOf<String?>(null) }
    LaunchedEffect(kind, id, action) {
        if (id.isBlank() || action.isBlank()) return@LaunchedEffect
        runCatching { WfConnectors.fields(app.store, kind, id, action, cfg["conexao"].orEmpty()) }
            .onSuccess { fields = it }.onFailure { fieldsErr = it.message; fields = emptyList() }
    }
    val campos = remember(cfg["campos"]) {
        runCatching { kotlinx.serialization.json.Json.parseToJsonElement(cfg["campos"].orEmpty().ifBlank { "{}" }) as kotlinx.serialization.json.JsonObject }.getOrNull()
    }
    var raw by remember(node.id) { mutableStateOf(false) }
    var focus by remember(node.id) { mutableStateOf<String?>(null) }
    var pickAction by remember { mutableStateOf(false) }
    val openGroups = remember { mutableStateMapOf<String, Boolean>() }
    fun value(k: String): String = campos?.get(k)?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: it.toString() }.orEmpty()
    fun setValue(k: String, v: String) {
        val base = campos ?: kotlinx.serialization.json.JsonObject(emptyMap())
        val next = if (v.isEmpty()) base - k else base + (k to kotlinx.serialization.json.JsonPrimitive(v))
        onCfg(cfg + ("campos" to prettyJson.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), kotlinx.serialization.json.JsonObject(next))))
    }

    // 1 · Conector
    SectionLabel("1 · Conector")
    val list = sources
    when {
        list == null -> LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.accent)
        list.isEmpty() -> Text("Nenhum conector ativo. Conecte um app ou servidor MCP em Conectores e volte aqui.", fontSize = 13.sp, color = c.warn)
        else -> {
            val current = list.firstOrNull { it.kind == kind && (it.id.equals(id, true) || it.name.equals(id, true)) && (cfg["conexao"].isNullOrBlank() || it.connection.isBlank() || it.connection == cfg["conexao"]) }
            if (id.isNotBlank() && current == null)
                Text("\"$id\" não está conectado. Escolha um conector ativo abaixo (ou conecte-o em Conectores).", fontSize = 12.sp, color = c.warn)
            // Grupos colapsáveis (MCP pode ter dezenas de servidores): lista com rolagem própria, carregada sob demanda.
            list.groupBy { it.kind }.forEach { (k, items) ->
                val isOpen = openGroups[k] ?: (current?.kind == k || (current == null && k != WfConnectors.MCP && items.size <= 8))
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { openGroups[k] = !isOpen }.padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(WfConnectors.kindLabel(k), fontSize = 13.sp, color = c.ink2, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.width(6.dp))
                    Text("${items.size} ativo" + if (items.size == 1) "" else "s", fontSize = 11.sp, color = c.ink3, modifier = Modifier.clip(RoundedCornerShape(6.dp))
                        .background(c.surface2).padding(horizontal = 6.dp, vertical = 1.dp))
                    Spacer(Modifier.weight(1f))
                    if (!isOpen && current?.kind == k) Text(current?.name.orEmpty(), fontSize = 12.sp, color = c.accent, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 140.dp))
                    Icon(if (isOpen) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = c.ink3)
                }
                if (isOpen) LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(items, key = { it.kind + it.id + it.connection }) { s ->
                        val sel = s == current
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (sel) c.accent.copy(alpha = .10f) else c.surface2)
                            .border(1.dp, if (sel) c.accent else c.line, RoundedCornerShape(14.dp))
                            .clickable {
                                if (!sel) onCfg(cfg + mapOf("source" to s.kind, "conector" to s.id, "conexao" to s.connection) +
                                    (if (s.kind != kind || !s.id.equals(id, true)) mapOf("acao" to "", "campos" to "{}") else emptyMap()))
                            }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xFF2FBF71).copy(alpha = .16f)), contentAlignment = Alignment.Center) {
                                Text(s.name.take(1).uppercase(), fontWeight = FontWeight.Bold, color = Color(0xFF2FBF71))
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(s.name, fontWeight = FontWeight.Medium, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(s.detail + if (!s.ready) " · precisa reconectar" else "", fontSize = 11.sp, color = if (s.ready) c.ink3 else c.warn, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (sel) Icon(Icons.Rounded.CheckCircle, null, tint = c.accent, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
            TextButton({ sources = null; reload++ }) { Icon(Icons.Rounded.Refresh, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Atualizar lista") }
        }
    }

    // 2 · Ação
    if (id.isNotBlank()) {
        SectionLabel("2 · Ação")
        val a = actions?.firstOrNull { it.name == action }
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface2).border(1.dp, c.line, RoundedCornerShape(14.dp))
            .clickable(enabled = !actions.isNullOrEmpty()) { pickAction = true }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(a?.title ?: action.ifBlank { if (actions == null) "Carregando ações…" else if (actions!!.isEmpty()) "Sem ações disponíveis" else "Escolher ação" },
                    fontWeight = FontWeight.Medium, fontSize = 14.sp)
                (a?.description?.takeIf { it.isNotBlank() } ?: action.takeIf { it.isNotBlank() && a == null }?.let { "Nome técnico: $it" })?.let {
                    Text(it, fontSize = 11.sp, color = c.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (actions == null) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.UnfoldMore, null, tint = c.ink3)
        }
    }

    // 3 · Campos
    if (id.isNotBlank() && action.isNotBlank()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { SectionLabel("3 · Campos") }
            TextButton({ raw = !raw }) { Text(if (raw) "Formulário" else "Editar JSON", fontSize = 12.sp) }
        }
        val fs = fields
        when {
            raw || campos == null -> {
                if (campos == null) Text("O JSON dos campos está inválido; corrija abaixo.", fontSize = 12.sp, color = c.err)
                FluwyField(cfg["campos"].orEmpty(), { onCfg(cfg + ("campos" to it)) }, "Campos (JSON)", minLines = 4)
            }
            fs == null -> LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.accent)
            fs.isEmpty() -> Text(fieldsErr?.let { "Não foi possível ler os campos: $it" } ?: "Esta ação não precisa de campos.", fontSize = 12.sp, color = if (fieldsErr != null) c.warn else c.ink3)
            else -> fs.forEach { f ->
                val v = value(f.key)
                if (f.type == "checkbox" || f.type == "boolean") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(f.label + if (f.required) " *" else "", fontSize = 14.sp)
                            if (f.description.isNotBlank()) Text(f.description, fontSize = 11.sp, color = c.ink3, maxLines = 2)
                        }
                        Switch(checked = (v.ifBlank { f.default }).equals("true", true), onCheckedChange = { setValue(f.key, it.toString()) })
                    }
                } else {
                    FluwyField(v, { setValue(f.key, it); focus = f.key }, f.label + (if (f.required) " *" else "") + if (f.default.isNotBlank() && v.isBlank()) " (padrão: ${f.default.take(30)})" else "",
                        minLines = if (f.type.contains("long") || f.type == "json" || f.type == "object" || f.key.contains("body", true) || f.key.contains("content", true)) 3 else 1)
                    if (f.description.isNotBlank()) Text(f.description, fontSize = 11.sp, color = c.ink3, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (f.options.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        f.options.take(30).forEach { (lbl, ov) -> Chip(lbl.take(40), selected = v == ov) { setValue(f.key, ov); focus = f.key } }
                    } else if (f.dynamic) Text("Lista carregada do app quando a conta está conectada; você também pode digitar o valor.", fontSize = 11.sp, color = c.ink3)
                }
            }
        }
        if (!raw && !fields.isNullOrEmpty()) {
            Text("Inserir dados de outros nós" + (focus?.let { " em \"$it\"" } ?: " (edite um campo primeiro)"), fontSize = 12.sp, color = c.ink2)
            Row(Modifier.fillMaxWidth().horizontalScrollIfNeeded(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (listOf("{{prev}}" to "Saída anterior", "{{trigger.time}}" to "Hora do disparo") + upstream.map { "{{${it.id}}}" to WfLogic.label(it) }).forEach { (token, text) ->
                    Chip(text) { focus?.let { k -> setValue(k, value(k) + token) } }
                }
            }
        }
        SectionLabel("Formato da resposta")
        Text(WfConnectors.responseShape(kind, node.id), Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface2).padding(10.dp),
            fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = c.ink2)
    }

    if (pickAction) {
        var q by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { pickAction = false }, containerColor = c.surface, title = { Text("Escolher ação") },
            text = {
                Column {
                    OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), placeholder = { Text("Buscar ação") }, singleLine = true,
                        leadingIcon = { Icon(Icons.Rounded.Search, null) }, shape = RoundedCornerShape(12.dp))
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(actions.orEmpty().filter { q.isBlank() || (it.name + " " + it.title + " " + it.description).contains(q.trim(), true) }, key = { it.name }) { a ->
                            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {
                                pickAction = false
                                if (a.name != action) onCfg(cfg + mapOf("acao" to a.name, "campos" to "{}"))
                            }.padding(vertical = 8.dp, horizontal = 4.dp)) {
                                Text(a.title, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = if (a.name == action) c.accent else c.ink)
                                if (a.description.isNotBlank()) Text(a.description, fontSize = 11.sp, color = c.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }, confirmButton = { TextButton({ pickAction = false }) { Text("Fechar") } })
    }
}

/** Testa o nó sozinho e mostra a resposta real, com os caminhos prontos para usar em outros nós. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NodeTester(w: Workflow, node: WfNode, lastStep: WfStep?) {
    val c = Fluwy.c
    val scope = rememberCoroutineScope()
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    var busy by remember(node.id) { mutableStateOf(false) }
    var res by remember(node.id) { mutableStateOf<NodeResult?>(null) }
    var copied by remember { mutableStateOf<String?>(null) }
    var showRaw by remember(node.id) { mutableStateOf(false) }
    SectionLabel("Testar este nó")
    Text("Roda só este nó agora, com os dados da última execução como entrada. Ações reais (enviar e-mail, postar) acontecem de verdade.", fontSize = 11.sp, color = c.ink3)
    Button({ busy = true; res = null; scope.launch { res = WorkflowEngine.testNode(w, node.id); busy = false } }, enabled = !busy,
        colors = ButtonDefaults.buttonColors(containerColor = c.accent)) {
        if (busy) CircularProgressIndicator(Modifier.size(16.dp), color = c.bg, strokeWidth = 2.dp) else Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp)); Text(if (busy) "Testando…" else "Testar")
    }
    val out = res?.takeIf { it.error == null }?.output ?: lastStep?.takeIf { res == null && it.status == "ok" }?.output
    res?.error?.let { e ->
        Text("Erro: $e", Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.err.copy(alpha = .08f)).padding(10.dp), fontSize = 12.sp, color = c.err)
    }
    if (out != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (res != null) "Resultado do teste" else "Resultado da última execução", fontSize = 12.sp, color = c.ink2, modifier = Modifier.weight(1f))
            if (WfFormat.isJson(out)) TextButton({ showRaw = !showRaw }) { Text(if (showRaw) "Ver leitura clara" else "Dados técnicos", fontSize = 12.sp) }
        }
        if (showRaw) Text(prettyOut(out).take(4_000), Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface2).padding(10.dp),
            fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = c.ink2)
        else Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface2).padding(12.dp)) { FluwyMarkdown(WfFormat.readable(out, maxItems = 10).take(3_000)) }
        val paths = WfLogic.paths(out)
        if (paths.isNotEmpty()) {
            Text("Use em outros nós (toque para copiar)" + (copied?.let { " · copiado $it" } ?: ""), fontSize = 12.sp, color = c.ink2)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                (listOf("") + paths.take(30)).forEach { p ->
                    val token = nodeExpr(WfLogic.label(node), p)
                    Chip(if (p.isEmpty()) "tudo" else p) { clip.setText(androidx.compose.ui.text.AnnotatedString(token)); copied = token }
                }
            }
        }
    }
}

/** `{{ $node["Nome"].json.a[0].b }}` para o caminho "a.0.b" (formato do WfLogic.paths). */
internal fun nodeExpr(label: String, path: String): String {
    val segs = path.split('.').filter { it.isNotEmpty() }.joinToString("") { s ->
        when { s.toIntOrNull() != null -> "[$s]"; Regex("^[A-Za-z_][A-Za-z0-9_]*$").matches(s) -> ".$s"; else -> "[\"" + s.replace("\"", "\\\"") + "\"]" }
    }
    return "{{ \$node[\"" + label.replace("\"", "\\\"") + "\"].json" + segs + " }}"
}

@Composable
internal fun Modifier.horizontalScrollIfNeeded(): Modifier = this.then(Modifier.horizontalScroll(rememberScrollState()))

// ───────────────────────────── Execuções ─────────────────────────────

@Composable
internal fun RunsPanel(w: Workflow, runs: List<WfRun>, nav: NavController, onClose: () -> Unit) {
    val c = Fluwy.c
    var open by remember { mutableStateOf(runs.firstOrNull()?.id) }
    var raw by remember { mutableStateOf(false) }
    Column(Modifier.padding(16.dp, 0.dp, 16.dp, 28.dp).heightIn(max = 640.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Execuções", fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.weight(1f))
            if (runs.any { it.status != "running" && it.status != "waiting" }) TextButton({ WorkflowStore.clearRuns(w.id) }) { Text("Limpar histórico") }
        }
        if (runs.isEmpty()) Text("Nenhuma execução ainda. Toque em Executar.", color = c.ink3, fontSize = 13.sp, modifier = Modifier.padding(vertical = 16.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(runs, key = { it.id }) { r ->
                val col = statusColor(r.status, c) ?: c.ink3
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface2).clickable { open = if (open == r.id) null else r.id }.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(col))
                        Spacer(Modifier.width(8.dp))
                        Text(when (r.status) { "ok" -> "Concluída"; "error" -> "Falhou"; "running" -> "Executando"; "waiting" -> "Aguardando aprovação"; "paused" -> "Pausada"; "queued" -> "Na fila"; else -> "Parada" },
                            fontWeight = FontWeight.SemiBold, color = col, modifier = Modifier.weight(1f))
                        Text("${when (r.trigger) { "schedule" -> "agendada"; "webhook" -> "webhook"; "test" -> if (r.until.isNotBlank()) "parcial" else "teste"; "whatsapp" -> "WhatsApp"; "error" -> "fluxo de erro"; else -> "manual" }} · ${timeFmt.format(Date(r.startedAt))}", fontSize = 11.sp, color = c.ink3)
                    }
                    r.finishedAt?.let { Text("Duração: ${((it - r.startedAt) / 1000).coerceAtLeast(0)} s", fontSize = 11.sp, color = c.ink3) }
                    if (r.error.isNotBlank()) Text(r.error.take(400), fontSize = 12.sp, color = c.err)
                    if (WorkflowEngine.isActive(r.status)) TextButton({ WorkflowEngine.cancel(r.id) }) { Text("Parar execução", color = c.err) }
                    if (open == r.id && r.steps.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Dados técnicos", fontSize = 12.sp, color = c.ink3, modifier = Modifier.weight(1f))
                        Switch(checked = raw, onCheckedChange = { raw = it }, modifier = Modifier.scale(0.8f))
                    }
                    if (open == r.id) r.steps.forEach { s ->
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(nodeIcon(s.type), null, Modifier.size(16.dp), tint = statusColor(s.status, c) ?: c.ink3)
                            Spacer(Modifier.width(6.dp))
                            Text(s.label, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            if (s.status != "running") Text(listOfNotNull("${s.inputItems}→${s.items} itens", if (s.attempts > 1) "${s.attempts}×" else null, if (s.pinned) "fixado" else null).joinToString(" · ") + "  ",
                                fontSize = 10.sp, color = c.ink3)
                            s.finishedAt?.let { Text("${((it - s.startedAt) / 100) / 10.0} s", fontSize = 11.sp, color = c.ink3) }
                        }
                        // Leitura clara por padrão; os dados técnicos (JSON) só quando o usuário pede.
                        if (s.status == "error") Text(s.error.take(800), Modifier.fillMaxWidth().padding(start = 22.dp, top = 2.dp), fontSize = 12.sp, color = c.err)
                        else if (s.output.isNotBlank()) {
                            if (s.error.isNotBlank()) Text(s.error.take(300), Modifier.padding(start = 22.dp, top = 2.dp), fontSize = 11.sp, color = c.warn)
                            if (raw && s.input.isNotBlank()) Text("ENTRADA\n" + prettyOut(s.input).take(1_500), Modifier.fillMaxWidth().padding(start = 22.dp, top = 2.dp), fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace, color = c.ink3)
                            if (raw) Text("SAÍDA\n" + prettyOut(s.output).take(3_000), Modifier.fillMaxWidth().padding(start = 22.dp, top = 2.dp), fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace, color = c.ink3)
                            else if (s.type.startsWith("trigger")) Text(if (r.trigger == "schedule") "Disparado pelo agendamento" else if (r.trigger == "test") "Teste" else "Disparado por você",
                                Modifier.padding(start = 22.dp, top = 2.dp), fontSize = 12.sp, color = c.ink3)
                            else Box(Modifier.fillMaxWidth().padding(start = 22.dp, top = 2.dp)) { FluwyMarkdown(WfFormat.readable(s.output, maxItems = 10).take(2_000)) }
                        }
                        if (r.status == "waiting" && s.status == "running" && s.type == "review" && WorkflowEngine.review(r.id, s.nodeId) != null)
                            Button({ onClose(); nav.navigate("review/${r.id}/${s.nodeId}") }, Modifier.padding(start = 22.dp), colors = ButtonDefaults.buttonColors(containerColor = c.accent)) {
                                Icon(Icons.Rounded.RateReview, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Revisar e aprovar")
                            }
                        if (r.status == "waiting" && s.status == "running" && s.type == "approval" && WorkflowEngine.pendingApproval(r.id, s.nodeId)) Row(Modifier.padding(start = 22.dp)) {
                            Button({ WorkflowEngine.approve(r.id, s.nodeId, true) }, colors = ButtonDefaults.buttonColors(containerColor = c.ok)) { Text("Aprovar") }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton({ WorkflowEngine.approve(r.id, s.nodeId, false) }) { Text("Recusar") }
                        }
                        s.sessionId?.let { sid -> TextButton({ onClose(); nav.navigate("chat/$sid") }, Modifier.padding(start = 14.dp)) { Text("Abrir o chat do agente", fontSize = 12.sp) } }
                    }
                }
            }
        }
    }
}

/** Ponto verde pulsando: fluxo ativo, escutando em tempo real. */
@Composable
internal fun LiveDot(color: Color) {
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "live")
    val a by t.animateFloat(0.35f, 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(900), androidx.compose.animation.core.RepeatMode.Reverse), label = "a")
    Box(Modifier.size(9.dp).clip(CircleShape).background(color.copy(alpha = a)))
}
