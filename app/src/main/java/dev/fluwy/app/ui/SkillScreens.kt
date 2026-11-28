package dev.fluwy.app.ui
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.text.BasicTextField
import androidx.navigation.NavController
import dev.fluwy.app.FluwyApp
import dev.fluwy.app.data.*
import dev.fluwy.app.scheduler.Scheduler
import kotlinx.coroutines.launch
import dev.fluwy.app.ui.theme.Fluwy
import dev.fluwy.app.wallet.WalletAgentPolicyStore
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun SkillsScreen(nav: NavController) {
    val app = FluwyApp.instance
    val st by app.store.state.collectAsStateWithLifecycle()
    val c = Fluwy.c
    Scaffold(containerColor = c.bg, topBar = { FluwyTopBar("Skills", { nav.popBackStack() }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { nav.navigate("skill/new") }, containerColor = c.ink, contentColor = c.bg, shape = CircleShape,
                icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("Nova skill") })
        }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                FluwyCard(onClick = {
                    val s = Session(title = "Criar skill"); app.store.upsertSession(s)
                    app.agent.send(s.id, "Quero criar uma nova skill. Me faça perguntas curtas para entender o que ela deve fazer e depois crie com create_skill.")
                    nav.navigate("chat/${s.id}")
                }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.AutoFixHigh, null, tint = c.accent); Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Criar skill conversando", fontWeight = FontWeight.SemiBold)
                            Text("O Skill creator entrevista você e monta a skill.", fontSize = 13.sp, color = c.ink2)
                        }
                        Icon(Icons.Rounded.ChevronRight, null, tint = c.ink3)
                    }
                }
            }
            item { SkillMarketplace() }
            item { SectionLabel("Minhas skills") }
            items(st.skills, key = { it.id }) { s ->
                var confirmDelete by remember(s.id) { mutableStateOf(false) }
                FluwyCard(onClick = { nav.navigate("skill/${s.id}") }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(c.surface2), contentAlignment = Alignment.Center) { Icon(skillIcon(s.icon), null) }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(s.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (s.builtIn) { Spacer(Modifier.width(6.dp)); Text("nativa", fontSize = 11.sp, color = c.ink3) }
                            }
                            Text(s.description, fontSize = 13.sp, color = c.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Switch(s.enabled, { app.store.upsertSkill(s.copy(enabled = it)) }, colors = SwitchDefaults.colors(checkedTrackColor = c.ink, checkedThumbColor = c.bg))
                        if (!s.builtIn) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Rounded.DeleteOutline, "Excluir skill", tint = c.err) }
                    }
                }
                if (confirmDelete) AlertDialog(
                    onDismissRequest = { confirmDelete = false },
                    title = { Text("Excluir skill?") },
                    text = { Text("A skill “${s.name}” será removida da sua biblioteca.") },
                    confirmButton = { TextButton(onClick = { app.store.deleteSkill(s.id); confirmDelete = false }) { Text("Excluir", color = c.err) } },
                    dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } }
                )
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

@Composable
fun SkillEditScreen(nav: NavController, id: String) {
    val app = FluwyApp.instance
    val existing = app.store.state.value.skills.firstOrNull { it.id == id }
    var s by remember { mutableStateOf(existing ?: Skill(name = "", description = "", instructions = "")) }
    val c = Fluwy.c
    Scaffold(containerColor = c.bg, topBar = {
        FluwyTopBar(if (existing == null) "Nova skill" else s.name, { nav.popBackStack() }) {
            if (existing != null && !existing.builtIn) IconButton({ app.store.deleteSkill(id); nav.popBackStack() }) { Icon(Icons.Rounded.Delete, "Excluir", tint = c.err) }
        }
    }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FluwyField(s.name, { s = s.copy(name = it) }, "Nome")
            FluwyField(s.description, { s = s.copy(description = it) }, "Quando usar (descrição curta)", minLines = 2)
            FluwyField(s.instructions, { s = s.copy(instructions = it) }, "Instruções (estilo SKILL.md)", minLines = 10)
            ToggleRow("Ativa", "Disponível para o agente", s.enabled) { s = s.copy(enabled = it) }
            PillButton("Salvar", Icons.Rounded.Check, modifier = Modifier.fillMaxWidth(), enabled = s.name.isNotBlank()) { app.store.upsertSkill(s); nav.popBackStack() }
        }
    }
}

// ============================ AGENDADAS ============================

internal val dateFmt = SimpleDateFormat("dd/MM HH:mm", Locale("pt", "BR"))

// ============================ KNOWLEDGE ============================

@Composable
fun KnowledgeScreen(nav: NavController) {
    val app = FluwyApp.instance
    val st by app.store.state.collectAsStateWithLifecycle()
    val c = Fluwy.c
    Scaffold(containerColor = c.bg, topBar = { FluwyTopBar("Knowledge", { nav.popBackStack() }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { nav.navigate("knowledge/new") }, containerColor = c.ink, contentColor = c.bg, shape = CircleShape,
                icon = { Icon(Icons.Rounded.Add, null) }, text = { Text("Adicionar") })
        }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("Fatos, preferências e contexto que o agente sempre considera — sobre você, sua empresa, seus padrões de entrega.",
                    color = c.ink2, fontSize = 14.sp, modifier = Modifier.padding(bottom = 8.dp))
            }
            if (st.knowledge.isEmpty()) item { EmptyState(Icons.Rounded.LibraryBooks, "Sem knowledge", "Ex.: \"Sempre entregue relatórios em PT-BR com tabela de fontes.\"") }
            items(st.knowledge, key = { it.id }) { k ->
                FluwyCard(onClick = { nav.navigate("knowledge/${k.id}") }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(k.title, fontWeight = FontWeight.SemiBold)
                            Text(k.content, fontSize = 13.sp, color = c.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Switch(k.enabled, { app.store.upsertKnowledge(k.copy(enabled = it)) }, colors = SwitchDefaults.colors(checkedTrackColor = c.ink, checkedThumbColor = c.bg))
                    }
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

@Composable
fun KnowledgeEditScreen(nav: NavController, id: String) {
    val app = FluwyApp.instance
    val existing = app.store.state.value.knowledge.firstOrNull { it.id == id }
    var k by remember { mutableStateOf(existing ?: KnowledgeItem(title = "", content = "")) }
    val c = Fluwy.c
    Scaffold(containerColor = c.bg, topBar = {
        FluwyTopBar(if (existing == null) "Novo knowledge" else k.title, { nav.popBackStack() }) {
            if (existing != null) IconButton({ app.store.deleteKnowledge(id); nav.popBackStack() }) { Icon(Icons.Rounded.Delete, "Excluir", tint = c.err) }
        }
    }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FluwyField(k.title, { k = k.copy(title = it) }, "Título")
            FluwyField(k.content, { k = k.copy(content = it) }, "Conteúdo", minLines = 8)
            PillButton("Salvar", Icons.Rounded.Check, modifier = Modifier.fillMaxWidth(), enabled = k.title.isNotBlank()) { app.store.upsertKnowledge(k); nav.popBackStack() }
        }
    }
}


/** Busca no catálogo público de skills (Skyll, milhares de SKILL.md do GitHub) e instala com um toque. */
@Composable
internal fun SkillMarketplace() {
    val app = FluwyApp.instance
    val st by app.store.state.collectAsStateWithLifecycle()
    val c = Fluwy.c
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<SkillHub.Remote>>(emptyList()) }
    var preview by remember { mutableStateOf<SkillHub.Remote?>(null) }
    fun go() {
        if (q.isBlank()) return
        loading = true; error = null
        scope.launch {
            runCatching { SkillHub.search(q.trim()) }
                .onSuccess { results = it; if (it.isEmpty()) error = "Nenhuma skill encontrada para \"$q\"." }
                .onFailure { error = "Falha ao buscar: ${it.message}" }
            loading = false
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("Marketplace de skills")
        OutlinedTextField(
            value = q, onValueChange = { q = it }, modifier = Modifier.fillMaxWidth(),
            singleLine = true, shape = RoundedCornerShape(20.dp),
            placeholder = { Text("Buscar skills (ex.: pdf, excel, seo…)", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Icon(Icons.Rounded.Search, null, tint = c.ink2) },
            trailingIcon = {
                if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = c.accent)
                else IconButton(onClick = { go() }, enabled = q.isNotBlank()) { Icon(Icons.Rounded.ArrowForward, "Buscar", tint = if (q.isNotBlank()) c.ink else c.ink3) }
            },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { go() }),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.accent, unfocusedBorderColor = c.line, focusedContainerColor = c.surface, unfocusedContainerColor = c.surface)
        )
        // Direto de um repositório público do GitHub (arquivos SKILL.md).
        var repo by remember { mutableStateOf("") }
        var repoLoading by remember { mutableStateOf(false) }
        fun fromGithub() {
            if (repo.isBlank() || repoLoading) return
            repoLoading = true; error = null
            scope.launch {
                runCatching {
                    // Link do GitHub: busca as skills do repositório. URL direta de SKILL.md: usa o instalador (mesma validação do agente).
                    if (dev.fluwy.app.data.GithubSkills.parse(repo) != null) dev.fluwy.app.data.GithubSkills.fetch(repo)
                    else dev.fluwy.app.skills.SkillInstaller.fetch(repo).map { f ->
                        dev.fluwy.app.data.SkillHub.Remote(id = "url:" + f.source, title = f.meta.name, description = f.meta.description, source = f.source, installs = 0, content = f.markdown)
                    }
                }
                    .onSuccess { results = it; if (it.isEmpty()) error = "Não consegui baixar nenhuma skill desse repositório." }
                    .onFailure { error = it.message ?: "Falha ao consultar o GitHub." }
                repoLoading = false
            }
        }
        OutlinedTextField(
            value = repo, onValueChange = { repo = it }, modifier = Modifier.fillMaxWidth(),
            singleLine = true, shape = RoundedCornerShape(20.dp),
            placeholder = { Text("GitHub (owner/repo) ou URL de um SKILL.md", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Icon(Icons.Rounded.Code, null, tint = c.ink2) },
            trailingIcon = {
                if (repoLoading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = c.accent)
                else IconButton(onClick = { fromGithub() }, enabled = repo.isNotBlank()) { Icon(Icons.Rounded.Download, "Buscar skills no repositório", tint = if (repo.isNotBlank()) c.ink else c.ink3) }
            },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Go),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = { fromGithub() }),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.accent, unfocusedBorderColor = c.line, focusedContainerColor = c.surface, unfocusedContainerColor = c.surface)
        )
        Text("Skills de repositórios públicos são instruções escritas por terceiros: leia a prévia antes de adicionar.", color = c.ink3, fontSize = 12.sp)
        error?.let { Text(it, color = c.ink2, fontSize = 13.sp) }
        results.forEach { r ->
            val installed = st.skills.any { it.name.equals(r.title.take(60), true) }
            FluwyCard(onClick = { preview = r }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(r.description, fontSize = 13.sp, color = c.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val meta = listOfNotNull(r.source.takeIf { it.isNotBlank() }?.take(50), r.installs.takeIf { it > 0 }?.let { "$it instalações" }).joinToString(" · ")
                        if (meta.isNotBlank()) Text(meta, fontSize = 11.sp, color = c.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width(8.dp))
                    if (installed) Icon(Icons.Rounded.CheckCircle, "Instalada", tint = c.ok)
                    else Box(Modifier.clip(CircleShape).background(c.ink).clickable { app.store.upsertSkill(SkillHub.toSkill(r)) }.padding(horizontal = 14.dp, vertical = 8.dp)) {
                        Text("Adicionar", color = c.bg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
    preview?.let { r ->
        AlertDialog(onDismissRequest = { preview = null }, title = { Text(r.title) },
            text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(r.description, color = c.ink2, fontSize = 13.sp); Spacer(Modifier.height(8.dp))
                Text(r.content.ifBlank { "(sem conteúdo)" }.take(6000), fontSize = 12.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            } },
            confirmButton = { TextButton({ app.store.upsertSkill(SkillHub.toSkill(r)); preview = null }) { Text("Adicionar") } },
            dismissButton = { TextButton({ preview = null }) { Text("Fechar") } })
    }
}


// ============================ MEMÓRIA ============================
