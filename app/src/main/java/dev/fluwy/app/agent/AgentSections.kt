package dev.fluwy.app.agent

import dev.fluwy.app.browser.BrowserHost
import dev.fluwy.app.data.AgentProfile
import dev.fluwy.app.data.ChatItem
import dev.fluwy.app.data.KnowledgeItem
import dev.fluwy.app.data.LlmMessage
import dev.fluwy.app.data.PlanPhase
import dev.fluwy.app.data.Session
import dev.fluwy.app.data.Section
import dev.fluwy.app.data.SectionTask
import dev.fluwy.app.data.TaskStatus
import dev.fluwy.app.data.newId
import dev.fluwy.app.data.SessionStatus
import dev.fluwy.app.data.Skill
import dev.fluwy.app.data.StepStatus
import dev.fluwy.app.data.Store
import dev.fluwy.app.files.AtomicTextFile
import dev.fluwy.app.llm.OpenRouter
import dev.fluwy.app.llm.LlmTruncationPolicy
import dev.fluwy.app.llm.ToolCall
import dev.fluwy.app.llm.bool
import dev.fluwy.app.llm.int
import dev.fluwy.app.llm.str
import dev.fluwy.app.llm.strArray
import dev.fluwy.app.llm.tool
import dev.fluwy.app.util.runCatchingCancellable
import dev.fluwy.app.sandbox.Sandbox
import dev.fluwy.app.trading.TradeCoordinator
import dev.fluwy.app.uiconnector.iqoption.IqTradeWaitDirective
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import dev.fluwy.app.wallet.WalletAgentPolicyStore
import dev.fluwy.app.wallet.SolanaWalletRepository
import dev.fluwy.app.wallet.SolanaRpcClient
import dev.fluwy.app.wallet.SolanaNetwork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.HashMap
import java.util.Locale
import dev.fluwy.app.agent.AgentRunner.AgentTask
import dev.fluwy.app.agent.AgentRunner.CustomDefParserFence
import dev.fluwy.app.agent.AgentRunner.Delegation
import dev.fluwy.app.agent.AgentRunner.Delivered
import dev.fluwy.app.agent.AgentRunner.FileChange
import dev.fluwy.app.agent.AgentRunner.QueuedMessage
import dev.fluwy.app.agent.AgentRunner.SectionPermissions
import dev.fluwy.app.agent.AgentRunner.SquadTask
import dev.fluwy.app.agent.AgentRunner.ToolResult
import dev.fluwy.app.agent.AgentRunner.WorkerMerge
import dev.fluwy.app.agent.AgentRunner.WorkerOutcome
import dev.fluwy.app.agent.AgentRunner.LoopGuards.ACTION_INTENT
import dev.fluwy.app.agent.AgentRunner.LoopGuards.BROWSER_WAIT_FOREGROUND_MS
import dev.fluwy.app.agent.AgentRunner.LoopGuards.CONTENT_REPEAT_TOOLS
import dev.fluwy.app.agent.AgentRunner.LoopGuards.DELEGATION_STARTED
import dev.fluwy.app.agent.AgentRunner.LoopGuards.MAX_AUTO_NUDGES
import dev.fluwy.app.agent.AgentRunner.LoopGuards.MAX_EDIT_FAILS
import dev.fluwy.app.agent.AgentRunner.LoopGuards.MAX_PARALLEL_MEMBERS
import dev.fluwy.app.agent.AgentRunner.LoopGuards.MAX_PLAN_NUDGES
import dev.fluwy.app.agent.AgentRunner.LoopGuards.MEMBER_SLOW_MINUTES
import dev.fluwy.app.agent.AgentRunner.LoopGuards.NETWORK_MAX_WAIT_MS
import dev.fluwy.app.agent.AgentRunner.LoopGuards.NETWORK_RETRY_HINT_MS
import dev.fluwy.app.agent.AgentRunner.LoopGuards.POST_COMPACTION_READS
import dev.fluwy.app.agent.AgentRunner.LoopGuards.PURE_REPEAT_TOOLS
import dev.fluwy.app.agent.AgentRunner.LoopGuards.STALE_SNAPSHOT_MINUTES
import dev.fluwy.app.agent.AgentRunner.LoopGuards.STRICT_REPEAT_TOOLS
import dev.fluwy.app.agent.AgentRunner.LoopGuards.hasActionIntent
import dev.fluwy.app.agent.AgentRunner.LoopGuards.memoizeConnectorBuilderCall
import dev.fluwy.app.agent.AgentRunner.LoopGuards.repeatToolThreshold
import dev.fluwy.app.agent.AgentRunner.LoopGuards.similar

internal fun AgentRunner.scheduleLabel(m: Int): String = when {
    m % 1440 == 0 -> if (m == 1440) "uma vez por dia" else "a cada ${m / 1440} dias"
    m % 60 == 0 -> if (m == 60) "a cada hora" else "a cada ${m / 60} horas"
    else -> "a cada $m min"
}
/** Agente da biblioteca pelo id ou pelo nome (sem acento/caixa). Coordenadores de outras seções não entram. */
internal fun AgentRunner.libraryAgent(key: String): AgentProfile? {
    if (key.isBlank()) return null
    fun norm(x: String) = java.text.Normalizer.normalize(x, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
    val k = norm(key)
    val pool = store.state.value.agents.filterNot { it.name.startsWith("Coordenador ·") }
    return pool.firstOrNull { it.id == key } ?: pool.firstOrNull { norm(it.name) == k }
}

/** Lista do time no texto do coordenador (refeita quando entra alguém novo). */
internal fun AgentRunner.rosterOf(sec: Section): String = sec.agentIds.mapNotNull { store.agent(it) }
    .joinToString("\n") { "- ${it.name}: ${it.mission.ifBlank { it.instructions.lineSequence().drop(1).firstOrNull().orEmpty() }}" }

internal fun AgentRunner.createSection(
    sessionId: String, name: String, description: String, coordinatorInstructions: String,
    agentsJson: String, firstTask: String, capsCsv: String, suggestWallet: Boolean,
    autonomous: Boolean = false, scheduleMinutes: Int = 0,
): ToolResult {
    val title = name.trim().take(40)
    if (title.isBlank()) return ToolResult("ERRO: informe o nome da seção.")
    store.state.value.sections.firstOrNull { it.name.equals(title, ignoreCase = true) }?.let { existing ->
        if (existing.configured) return ToolResult("Já existe uma seção ativa chamada \"$title\". Use a existente (está na sidebar) ou peça outro nome ao usuário.")
        // seção criada mas não ativada: mostra o card de autorização de novo
        store.addItem(sessionId, ChatItem.SectionSetup(sectionId = existing.id, sectionName = existing.name,
            agentNames = (listOf(existing.coordinatorAgentId) + existing.agentIds).mapNotNull { store.agent(it)?.name }))
        return ToolResult("A seção \"$title\" já existia sem ativação; mostrei o card de autorização de novo. Aguarde o usuário.", SessionStatus.WAITING_USER)
    }
    val arr = runCatching { json.parseToJsonElement(agentsJson).jsonArray }.getOrNull()
        ?: return ToolResult("ERRO: agents_json deve ser um array JSON de objetos {name, role, description, rules}.")
    if (arr.isEmpty() || arr.size > 12) return ToolResult("ERRO: informe de 1 a 12 agentes.")
    val faces = dev.fluwy.app.ui.mascot.Mascots.builtinIds
    val sectionId = newId()
    val reused = mutableSetOf<String>()
    val members = arr.mapIndexedNotNull { idx, el ->
        val o = el as? JsonObject ?: return@mapIndexedNotNull null
        fun f(k: String) = runCatching { o[k]?.jsonPrimitive?.contentOrNull }.getOrNull().orEmpty().trim()
        fun bo(k: String, d: Boolean) = runCatching { o[k]?.jsonPrimitive?.booleanOrNull }.getOrNull() ?: d
        val n = f("name").ifBlank { "Agente ${idx + 1}" }.take(40)
        // Biblioteca primeiro: agente que já existe (pelo id em "existing" ou pelo mesmo nome) é reaproveitado, não duplicado.
        libraryAgent(f("existing").ifBlank { n })?.let { found -> reused += found.id; return@mapIndexedNotNull found }
        val role = f("role")
        AgentProfile(
            name = n, face = faces[(idx + 1) % faces.size], color = sectionColors[idx % sectionColors.size],
            mission = f("description"),
            instructions = buildString {
                appendLine("Você é $n" + (if (role.isNotBlank()) " ($role)" else "") + ", membro da seção \"$title\".")
                if (f("description").isNotBlank()) appendLine("Função: " + f("description"))
                if (f("rules").isNotBlank()) appendLine("Regras fixas (valem em toda tarefa): " + f("rules"))
                appendLine(dev.fluwy.app.prompts.Prompts.render("runner.29", mapOf<String, Any?>("lb" to "{{")))
            }.trim(),
            useBrowser = bo("needs_browser", true), useTerminal = bo("needs_terminal", true),
        )
    }
    if (members.isEmpty()) return ToolResult(dev.fluwy.app.prompts.Prompts.render("runner.28", mapOf<String, Any?>("lb" to "{{")))
    val roster = members.joinToString("\n") { "- ${it.name}: ${it.mission.ifBlank { it.instructions.lineSequence().drop(1).firstOrNull().orEmpty() }}" }
    val coordinator = AgentProfile(
        name = "Coordenador · $title".take(40), face = "bot", color = sectionColors[members.size % sectionColors.size],
        mission = description,
        instructions = buildString {
            appendLine("Você é o coordenador da seção \"$title\"." + (if (description.isNotBlank()) " Objetivo: $description" else ""))
            appendLine("Seu time:\n$roster")
            appendLine(dev.fluwy.app.prompts.Prompts.render("runner.27", mapOf<String, Any?>("lb" to "{{")))
            appendLine(dev.fluwy.app.prompts.Prompts.render("runner.26", mapOf<String, Any?>("lb" to "{{")))
            if (coordinatorInstructions.isNotBlank()) appendLine("Regras definidas pelo usuário: $coordinatorInstructions")
        }.trim(),
    )
    members.filter { it.id !in reused }.forEach { store.upsertAgent(it) }
    store.upsertAgent(coordinator)
    val chat = Session(title = "📁 $title", agentId = coordinator.id, sectionId = sectionId)
    store.upsertSession(chat)
    // Dados seguros já informados no chat de origem passam a valer para toda a seção.
    SecretVault.snapshot(sessionId).takeIf { it.isNotEmpty() }?.let { SecretVault.put(chat.id, it) }
    val task = firstTask.trim().ifBlank {
        store.session(sessionId)?.llm?.lastOrNull { it.role == "user" && it.content?.startsWith("[") != true }?.content.orEmpty()
    }
    store.upsertSection(Section(id = sectionId, name = title, description = description.trim(), coordinatorAgentId = coordinator.id,
        agentIds = members.map { it.id }, sessionId = chat.id, configured = false, firstTask = task, originSessionId = sessionId,
        autonomous = autonomous || scheduleMinutes > 0, scheduleMinutes = scheduleMinutes.coerceIn(0, 10_080)))
    val allowedCaps = setOf(dev.fluwy.app.device.DeviceCapPolicy.BACKGROUND, dev.fluwy.app.device.DeviceCapPolicy.POST_NOTIFICATIONS,
        dev.fluwy.app.device.DeviceCapPolicy.PHONE_NOTIFICATIONS, dev.fluwy.app.device.DeviceCapPolicy.ACCESSIBILITY, dev.fluwy.app.device.DeviceCapPolicy.SCREEN)
    val caps = (capsCsv.split(',').map { it.trim() } + (if (scheduleMinutes > 0) listOf(dev.fluwy.app.device.DeviceCapPolicy.BACKGROUND) else emptyList())).filter { it in allowedCaps }.distinct()
    store.addItem(sessionId, ChatItem.SectionSetup(sectionId = sectionId, sectionName = title,
        agentNames = listOf(coordinator.name) + members.map { it.name }, suggestedCaps = caps, suggestWallet = suggestWallet, suggestAuto = autonomous || scheduleMinutes > 0))
    return ToolResult(dev.fluwy.app.prompts.Prompts.render("runner.25", mapOf<String, Any?>("title" to (title), "members" to (members.size), "lb" to "{{")) +
        dev.fluwy.app.prompts.Prompts.render("runner.24", mapOf<String, Any?>("lb" to "{{")), SessionStatus.WAITING_USER)
}

/**
 * Zera o estado de execução de um chat: para o agente, fecha o terminal e apaga contexto do modelo,
 * histórico, resumo, contadores, visitas do browser, cofre e pendências de supervisão.
 */
internal suspend fun AgentRunner.wipeRuntime(sessionId: String) {
    val running = jobs[sessionId]
    stop(sessionId)
    pausedDelegations.remove(sessionId)
    // Espera o loop cancelado terminar: senão ele grava passos/mensagens DEPOIS da limpeza.
    kotlinx.coroutines.withTimeoutOrNull(4_000) { running?.join() }
    runCatching { terminals.close(sessionId) }
    BrowserVisitLog.clear(sessionId)
    SecretVault.clear(sessionId)
    delegations.remove(sessionId)
    coordinatorInbox.remove(sessionId)
    lastCheckpointStep.remove(sessionId)
    store.editSession(sessionId) {
        it.copy(llm = emptyList(), items = emptyList(), summary = "", compactions = 0, status = SessionStatus.IDLE,
            wakeAt = null, wakeReason = "", continuous = false)
    }
}

/** Apaga o workspace e o ambiente do chat (recriados vazios no próximo uso). Conectores nunca são apagados. */
internal fun AgentRunner.wipeWorkspace(sessionId: String) {
    if (sandbox.isConnectorSession(sessionId)) return
    runCatching { sandbox.deleteEnvironment(sessionId) }
    runCatching { sandbox.workspaceFor(sessionId).deleteRecursively() }
}

/** Zera a seção inteira: todos os chats do time, workspace compartilhado, terminais, cofre e delegações. */
internal suspend fun AgentRunner.wipeSection(sec: Section) {
    sectionGeneration.merge(sec.id, 1, Int::plus)  // descarta relatórios/checkpoints ainda na fila
    planNudges.remove(sec.id); finalReportAsked.remove(sec.id)
    synchronized(planLocks.getOrPut(sec.id) { Any() }) {
        store.section(sec.id)?.let { store.upsertSection(it.copy(plan = emptyList(), planGoal = "", planItemId = null)) }
    }
    delegations.entries.removeIf { it.value.sectionId == sec.id }
    sec.agentIds.forEach { delivered.remove(it) }
    val chats = store.state.value.sessions.filter { it.sectionId == sec.id || it.id == sec.sessionId }
    kotlinx.coroutines.coroutineScope { chats.map { c -> async { wipeRuntime(c.id) } }.forEach { it.await() } }
    chats.forEach { wipeWorkspace(it.id) }  // o alias aponta todos para o mesmo ambiente section_<id>
    // Dados seguros digitados no chat que criou a seção continuam valendo (pertencem àquele chat).
    sec.originSessionId?.let { o -> SecretVault.snapshot(o).takeIf { it.isNotEmpty() }?.let { SecretVault.put(sec.sessionId, it) } }
}

internal fun AgentRunner.memberSession(sec: Section, member: AgentProfile): String =
    store.state.value.sessions.firstOrNull { it.sectionId == sec.id && it.agentId == member.id && it.id != sec.sessionId }?.id
        ?: Session(title = "${member.name} · ${sec.name}", agentId = member.id, sectionId = sec.id).also { store.upsertSession(it) }.id

internal suspend fun AgentRunner.teamDelegate(sessionId: String, agentName: String, task: String, wait: Boolean, timeoutMinutes: Int,
                                 taskId: String? = null): String {
    val sec = store.section(store.session(sessionId)?.sectionId) ?: return "ERRO: esta conversa não é uma seção."
    if (!sec.configured) return "A seção ainda não foi ativada pelo usuário."
    val team = sec.agentIds.mapNotNull { store.agent(it) }
    val wanted = agentName.trim()
    val member = team.firstOrNull { it.name.equals(wanted, ignoreCase = true) }
        ?: team.firstOrNull { it.name.contains(wanted, ignoreCase = true) || wanted.contains(it.name, ignoreCase = true) }
        ?: return "ERRO: não há agente \"$agentName\" no time. Disponíveis: ${team.joinToString { it.name }}."
    if (task.isBlank()) return "ERRO: descreva a tarefa."
    val childId = memberSession(sec, member)
    if (isRunning(childId)) return "${member.name} ainda está trabalhando na tarefa anterior. Acompanhe com team_status; você receberá o relatório quando ele terminar."
    // Teto de agentes em paralelo (maxChildrenPerAgent do OpenClaw): no celular, muitos browsers/terminais juntos travam tudo.
    val active = delegations.values.count { it.sectionId == sec.id && it.memberId != member.id }
    if (active >= MAX_PARALLEL_MEMBERS) return "Já há $active agentes trabalhando em paralelo (limite $MAX_PARALLEL_MEMBERS). Aguarde um relatório e então delegue a próxima tarefa."
    // Mesmo trabalho já entregue há pouco: devolve o resultado em vez de mandar refazer (evita o ciclo infinito).
    val now0 = System.currentTimeMillis()
    if (taskId == null) delivered[member.id]?.lastOrNull { now0 - it.at < 3 * 3_600_000L && (similar(task.lowercase(), it.task.lowercase()) || task.trim().equals(it.task.trim(), true)) }?.let { d ->
        return "${member.name} JÁ CONCLUIU esta mesma tarefa há ${(now0 - d.at) / 60_000} min. Resultado entregue:\n${d.answer.take(1_800)}\n\n" +
            "Não re-delegue. Use esse resultado e siga para a próxima etapa, ou passe uma tarefa NOVA/ajuste específico (diga exatamente o que mudar)."
    }
    val before = store.session(childId)?.items?.size ?: 0
    // Pílula do agente no chat do coordenador: fica "trabalhando" até ele terminar e abre o chat dele ao tocar.
    val pill = ChatItem.Step(tool = TEAM_MEMBER_STEP, title = task.lineSequence().first().take(120),
        args = "$childId|${member.id}", result = "", status = StepStatus.RUNNING)
    store.addItem(sec.sessionId, pill)
    delegations[childId] = Delegation(sec.id, member.id, pill.id, before, task, taskId = taskId)
    if (taskId != null) editPlan(sec.id) { SectionPlanEngine.started(it, taskId, System.currentTimeMillis()) }
    val files = runCatching {
        val root = sandbox.workspaceFor(childId)
        root.walkTopDown().maxDepth(3).filter { it.isFile && !it.path.contains("/.fluwy/") }
            .take(40).map { it.relativeTo(root).path }.toList()
    }.getOrDefault(emptyList())
    send(childId, "[Tarefa do coordenador da seção ${sec.name}" + (taskId?.let { " · etapa $it do plano" } ?: "") + "]\n$task\n\n" +
        "Arquivos do time em /root/workspace (compartilhado): " + (if (files.isEmpty()) "nenhum ainda." else files.joinToString(", ")) +
        "\nRegras: faça só esta tarefa; dê feedback curto a cada etapa; se faltar insumo, diga o que falta e encerre (o coordenador resolve).")
    if (!wait) return "$DELEGATION_STARTED ${member.name} começou a trabalhar. Dê ao usuário um feedback curto (5 a 30 palavras) e siga coordenando; o relatório dele chega sozinho quando terminar."
    val deadline = System.currentTimeMillis() + timeoutMinutes * 60_000L
    kotlinx.coroutines.delay(1_500)
    while (isRunning(childId) && System.currentTimeMillis() < deadline) kotlinx.coroutines.delay(2_000)
    return if (isRunning(childId)) "${member.name} continua trabalhando (passou de $timeoutMinutes min). O relatório chega sozinho quando terminar."
        else memberReport(member, childId, before)
}


/** Largada da seção: o coordenador monta o plano e o primeiro agente do time começa na hora. */
internal fun AgentRunner.startSection(sec: Section) {
    val raw = sec.firstTask.trim()
    val task = if (raw.isBlank() || WAIT_TASK.containsMatchIn(raw))
        sec.description.trim().takeIf { it.isNotBlank() }?.let { "Começar agora: $it" } ?: "Apresente o time, proponha o plano inicial e comece."
    else raw
    val first = sec.agentIds.firstNotNullOfOrNull { store.agent(it)?.name }
    store.addItem(sec.sessionId, ChatItem.User(text = task))
    send(sec.sessionId, task + "\n\n[Sistema — não mostre nem comente isto no chat] Comece AGORA, sem esperar o usuário: chame team_plan e ponha " +
        (first?.let { "$it (primeiro agente do time)" } ?: "o primeiro agente do time") + " para trabalhar já na primeira etapa. " +
        "Se faltar alguma informação, o agente começa pela parte que não depende dela (levantamento ou pesquisa inicial)" +
        (if (sectionAuto(sec)) " e você decide o restante sozinho." else " enquanto você pergunta ao usuário em paralelo; o time não fica parado esperando."), showUserMessage = false)
}

// ───────── Modo autônomo da seção: ninguém espera o usuário ─────────

/** Fato verificado pelo app para o coordenador: o que mudou no workspace durante a tarefa delegada. */
internal fun AgentRunner.workspaceChanges(sessionId: String, since: Long): String = runCatching {
    val root = sandbox.workspaceFor(sessionId)
    val files = root.walkTopDown().maxDepth(3).onEnter { !it.name.startsWith(".") && it.name != "node_modules" }
        .filter { it.isFile && it.lastModified() >= since - 2_000 }.take(300).toList().sortedByDescending { it.lastModified() }.take(15)
    if (files.isEmpty()) "\n[Verificado pelo app] Nenhum arquivo foi criado ou alterado em /root/workspace durante esta tarefa. Se a etapa exigia um arquivo, use team_review redo pedindo que ele seja salvo."
    else "\n[Verificado pelo app] Arquivos criados ou alterados em /root/workspace nesta tarefa: " + files.joinToString { it.relativeTo(root).path + " (" + it.length() + " B)" }
}.getOrDefault("")

/** true se uma das últimas respostas do chat já traz praticamente o mesmo relatório (evita feedback final em dobro). */
internal fun AgentRunner.alreadyReported(sessionId: String, text: String): Boolean {
    fun words(t: String) = t.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 3 }.toSet()
    val mine = words(text)
    if (mine.size < 12) return false
    return store.session(sessionId)?.items.orEmpty().takeLast(8).filterIsInstance<ChatItem.Assistant>().any { prev ->
        val theirs = words(prev.text)
        theirs.size >= 12 && mine.intersect(theirs).size.toDouble() / minOf(mine.size, theirs.size) >= 0.7
    }
}

/** Conteúdo atual do arquivo para poder desfazer depois; `false` em first = grande demais/binário, não entra no diário. */
internal fun AgentRunner.beforeChange(f: java.io.File): Pair<Boolean, String?> = runCatching {
    when {
        !f.exists() -> true to null
        f.length() > 1_000_000 -> false to null
        else -> true to f.readText()
    }
}.getOrDefault(false to null)
internal fun AgentRunner.recordChange(sessionId: String, f: java.io.File, before: Pair<Boolean, String?>, shown: String) {
    if (!before.first) return
    val q = fileJournal.getOrPut(sessionId) { java.util.ArrayDeque() }
    synchronized(q) { q.addLast(FileChange(f, before.second, shown)); while (q.size > 40) q.removeFirst() }
}

internal fun AgentRunner.fileKey(sessionId: String, f: java.io.File) = sessionId + "|" + runCatching { f.canonicalPath }.getOrDefault(f.absolutePath)
internal fun AgentRunner.noteFileSeen(sessionId: String, f: java.io.File) {
    val key = fileKey(sessionId, f)
    editFails.remove(key)
    fileSeen[key] = runCatching { f.lastModified() }.getOrDefault(0L)
    if (fileSeen.size > 4_000) fileSeen.keys.take(1_000).forEach { fileSeen.remove(it) }
}

internal fun AgentRunner.fileDigest(f: java.io.File): String = runCatching {
    val md = java.security.MessageDigest.getInstance("SHA-1")
    f.inputStream().use { ins -> val buf = ByteArray(65_536); while (true) { val n = ins.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
    md.digest().joinToString("") { "%02x".format(it) }
}.getOrDefault("?" + f.length())
internal fun AgentRunner.projectFiles(root: java.io.File): Sequence<java.io.File> =
    root.walkTopDown().onEnter { it == root || (it.name != ".git" && it.name != ".fluwy" && it.name !in HEAVY_DIRS) }
        .filter { it.isFile && !Regex("\\.worker-\\d+$").containsMatchIn(it.name) }
/** Retrato do projeto no momento em que os workers partem: caminho relativo → digest do conteúdo. */
internal fun AgentRunner.projectSnapshot(root: java.io.File): Map<String, String> =
    runCatching { projectFiles(root).associate { it.relativeTo(root).path to fileDigest(it) } }.getOrDefault(emptyMap())

/**
 * "Push" de um worker para o projeto principal, arquivo a arquivo, comparando três versões: a base (snapshot de
 * partida), a do worker e a atual do principal. Só o worker mudou → aplica. Os dois mudaram de formas diferentes →
 * conflito: o principal fica como está e a versão do worker é salva ao lado como <arquivo>.worker-N.
 */
internal fun AgentRunner.integrateWorker(base: Map<String, String>, source: java.io.File, dest: java.io.File, n: Int): WorkerMerge {
    val applied = mutableListOf<String>(); val deleted = mutableListOf<String>(); val conflicts = mutableListOf<String>()
    val seen = HashSet<String>()
    projectFiles(source).forEach { file ->
        val rel = file.relativeTo(source).path; seen += rel
        val target = java.io.File(dest, rel)
        val mine = fileDigest(file)
        if (base[rel] == mine) return@forEach                      // o worker não tocou neste arquivo
        val theirs = if (target.isFile) fileDigest(target) else null
        when {
            theirs == mine -> Unit                                 // já está igual no principal
            theirs == null && base[rel] == null -> { target.parentFile?.mkdirs(); file.copyTo(target, overwrite = true); applied += rel }
            theirs == base[rel] -> { target.parentFile?.mkdirs(); file.copyTo(target, overwrite = true); applied += rel }
            else -> { target.parentFile?.mkdirs(); file.copyTo(java.io.File(target.parentFile, target.name + ".worker-" + n), overwrite = true); conflicts += rel }
        }
    }
    // Arquivos que existiam na base e o worker apagou: apaga no principal se ninguém mais mexeu neles.
    base.keys.filter { it !in seen && !java.io.File(source, it).exists() }.forEach { rel ->
        val target = java.io.File(dest, rel)
        if (target.isFile && fileDigest(target) == base[rel]) { if (target.delete()) deleted += rel }
    }
    // Dependências instaladas pelo worker (node_modules etc.): só o que ainda não existe no principal.
    HEAVY_DIRS.forEach { dir ->
        val from = java.io.File(source, dir)
        if (from.isDirectory) runCatching { from.walkTopDown().filter { it.isFile }.forEach { f ->
            val t = java.io.File(dest, f.relativeTo(source).path); if (!t.exists()) { t.parentFile?.mkdirs(); f.copyTo(t, overwrite = false) } } }
    }
    return WorkerMerge(applied, deleted, conflicts)
}
