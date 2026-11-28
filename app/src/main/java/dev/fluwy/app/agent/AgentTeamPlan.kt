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

/** Mensagem direta entre agentes do time (ou para o coordenador), sem passar por uma nova delegação. */
internal fun AgentRunner.teamPeerMessage(sessionId: String, to: String, message: String): String {
    val session = store.session(sessionId) ?: return "ERRO: conversa não encontrada."
    val sec = store.section(session.sectionId) ?: return "ERRO: esta conversa não é uma seção."
    val me = store.agent(session.agentId)?.name ?: "Agente"
    if (message.isBlank()) return "ERRO: escreva a mensagem."
    if ((peerMessages.merge(sessionId, 1, Int::plus) ?: 1) > 30) return "RECUSADO: limite de 30 mensagens entre agentes nesta conversa. Reporte ao coordenador na sua entrega."
    val toCoordinator = to.trim().lowercase().let { it.startsWith("coord") || it == store.agent(sec.coordinatorAgentId)?.name?.lowercase() }
    val targetId = if (toCoordinator) sec.sessionId else {
        val member = resolveMember(sec, to) ?: return "ERRO: não há agente \"$to\" no time. Time: " + sec.agentIds.mapNotNull { store.agent(it)?.name }.joinToString() + " (ou \"coordenador\")."
        if (member.id == session.agentId) return "ERRO: o destinatário é você mesmo."
        memberSession(sec, member)
    }
    val text = "[Mensagem de $me] " + SecretVault.redact(sessionId, message).take(2_000)
    store.addItem(targetId, ChatItem.User(text = text))
    return if (isRunning(targetId)) {
        coordinatorInbox.getOrPut(targetId) { java.util.Collections.synchronizedList(mutableListOf()) }.add(text)
        "Mensagem entregue; o destinatário lê no próximo passo. Não espere resposta parado: siga a sua tarefa."
    } else {
        store.editSession(targetId) { it.copy(llm = it.llm + LlmMessage("user", text)) }
        "O destinatário não está trabalhando agora; a mensagem ficou registrada na conversa dele e será lida quando ele for acionado. Se você depende da resposta, diga isso na sua entrega ao coordenador."
    }
}

// ───────── Espera do coordenador: ciclo pedido pelo usuário x espera por agentes ─────────

/** Seção cujo chat principal é [sessionId], se este chat for o do coordenador. */
internal fun AgentRunner.coordinatedSection(sessionId: String): Section? = store.state.value.sections.firstOrNull { it.sessionId == sessionId && it.configured }

/** Nenhum agente do time trabalhando, nenhuma delegação aberta e nenhuma etapa em execução: esperar não adianta. */
internal fun AgentRunner.teamIdle(sec: Section): Boolean =
    store.state.value.sessions.none { it.sectionId == sec.id && it.id != sec.sessionId && isRunning(it.id) } &&
        delegations.values.none { it.sectionId == sec.id } &&
        store.section(sec.id)?.plan.orEmpty().none { it.status == TaskStatus.RUNNING }

/** O próprio usuário pediu um intervalo ("a cada 30 min…") neste chat. */
internal fun AgentRunner.userAskedInterval(sessionId: String): Boolean =
    store.session(sessionId)?.llm.orEmpty().filter { it.role == "user" && it.content?.startsWith("[") != true }
        .takeLast(12).any { SectionWaitPolicy.userAskedInterval(it.content.orEmpty()) }

/**
 * Espera que o app pode encerrar sozinho: é do coordenador de uma seção e serve para aguardar o time — o motivo
 * fala em aguardar/concluir/entrega, ou o usuário não pediu nenhum intervalo. Ciclos pedidos pelo usuário são respeitados.
 */
internal fun AgentRunner.cuttableWait(sessionId: String, reason: String): Section? {
    val sec = coordinatedSection(sessionId) ?: return null
    if (reason.contains("[[IQOPTION_TRADES", true)) return null
    return sec.takeIf { SectionWaitPolicy.waitsForTeam(reason) || !userAskedInterval(sessionId) }
}

/** Espera em pedaços curtos; se for uma espera pelo time e o time ficou ocioso, encerra antes do prazo. Retorna true se encerrou antes. */
internal suspend fun AgentRunner.waitUntil(sessionId: String, wake: Long, reason: String): Boolean {
    var idleChecks = 0
    while (System.currentTimeMillis() < wake) {
        val sec = cuttableWait(sessionId, reason)
        if (sec != null) {
            idleChecks = if (teamIdle(sec)) idleChecks + 1 else 0
            if (idleChecks >= 2) return true  // ~6 s ocioso: dá tempo de o relatório do agente ser montado
            kotlinx.coroutines.delay(minOf(3_000L, wake - System.currentTimeMillis()).coerceAtLeast(500))
        } else kotlinx.coroutines.delay(minOf(30_000L, wake - System.currentTimeMillis()).coerceAtLeast(1000))
    }
    return false
}

internal fun AgentRunner.noteEarlyWake(sessionId: String) {
    store.editSession(sessionId) { it.copy(wakeAt = null, llm = it.llm + LlmMessage("user",
        dev.fluwy.app.prompts.Prompts.render("runner.22", mapOf<String, Any?>("lb" to "{{")))) }
}

internal fun AgentRunner.sectionAuto(sec: Section): Boolean = store.agent(sec.coordinatorAgentId)?.confirmMode == "auto"
/** Chat de seção (coordenador ou agente) configurada como 100% autônoma. */
internal fun AgentRunner.autoSectionSession(sessionId: String): Boolean =
    store.session(sessionId)?.let { s -> s.sectionId != null && store.agent(s.agentId)?.confirmMode == "auto" } == true

/**
 * Seção autônoma: o coordenador parou (feedback, pergunta ou fim de um plano) com o time ocioso e sem declarar
 * o objetivo concluído → ordem oculta para seguir sozinho. No máximo [MAX_AUTO_NUDGES] vezes sem o plano mudar.
 */
internal fun AgentRunner.autoContinue(secIn: Section): String? {
    val sec = store.section(secIn.id) ?: return null
    if (!sectionAuto(sec) || sec.id in autoDone) return null
    if (store.state.value.sessions.any { it.sectionId == sec.id && it.id != sec.sessionId && isRunning(it.id) }) return null
    if (delegations.values.any { it.sectionId == sec.id }) return null
    if (sec.plan.any { it.status == TaskStatus.RUNNING || it.status == TaskStatus.REVIEW }) return null
    val sig = SectionPlanEngine.signature(sec.plan)
    val (prev, n) = autoNudges[sec.id] ?: ("" to 0)
    val count = if (prev == sig) n + 1 else 1
    autoNudges[sec.id] = sig to count
    if (count > MAX_AUTO_NUDGES) {
        if (count == MAX_AUTO_NUDGES + 1) store.addItem(sec.sessionId, ChatItem.Error(text =
            dev.fluwy.app.prompts.Prompts.render("runner.20", mapOf<String, Any?>("max_auto_nudges" to (MAX_AUTO_NUDGES), "lb" to "{{"))))
        return null
    }
    return dev.fluwy.app.prompts.Prompts.render("runner.19", mapOf<String, Any?>("lb" to "{{")) +
        "Objetivo completo da seção: " + sec.description.ifBlank { sec.firstTask }.take(500) + "\n" +
        dev.fluwy.app.prompts.Prompts.render("runner.18", mapOf<String, Any?>("lb" to "{{")) +
        dev.fluwy.app.prompts.Prompts.render("runner.21", mapOf<String, Any?>("lb" to "{{"))
}


internal fun AgentRunner.scriptPolicy(sessionId: String) = scriptPolicies.getOrPut(sessionId) { ScriptBuilderPolicy() }

// ───────────────────────── Plano persistido da seção (continuidade automática) ─────────────────────────

/** Leitura-modificação-escrita atômica do plano (vários agentes terminam ao mesmo tempo). */
internal fun AgentRunner.editPlan(sectionId: String, fn: (List<SectionTask>) -> List<SectionTask>) {
    synchronized(planLocks.getOrPut(sectionId) { Any() }) {
        val sec = store.section(sectionId) ?: return
        val next = fn(sec.plan)
        if (next != sec.plan) store.upsertSection(sec.copy(plan = next))
    }
    store.section(sectionId)?.let { syncPlanItem(it) }
}

/** Espelha o plano no chat do coordenador (cartão de progresso acima do campo de mensagem): estados reais. */
internal fun AgentRunner.syncPlanItem(sec: Section) {
    if (sec.plan.isEmpty()) return
    val state = SectionPlanEngine.state(sec.plan)
    val item = ChatItem.Plan(
        id = sec.planItemId ?: newId(),
        goal = (sec.planGoal.ifBlank { "Plano da seção" }).take(80) + " · " + SectionPlanEngine.label(state),
        phases = sec.plan.map { t ->
            PlanPhase("${SectionPlanEngine.icon(t.status)} ${t.id} · ${t.agentName}: ${t.task.lineSequence().first().take(70)}",
                done = t.status == TaskStatus.DONE || t.status == TaskStatus.CANCELLED)
        },
    )
    val exists = store.session(sec.sessionId)?.items?.any { it.id == item.id } == true
    if (exists) store.replaceItem(sec.sessionId, item) else {
        store.addItem(sec.sessionId, item)
        if (sec.planItemId != item.id) store.section(sec.id)?.let { store.upsertSection(it.copy(planItemId = item.id)) }
    }
}

/** Inicia toda etapa pronta (dependências aprovadas), respeitando o limite de paralelismo. */
internal suspend fun AgentRunner.planDispatch(sectionId: String): List<String> {
    val started = mutableListOf<String>()
    val sec = store.section(sectionId) ?: return started
    if (!sec.configured) return started
    for (t in SectionPlanEngine.ready(sec.plan)) {
        val fresh = store.section(sectionId) ?: break
        val text = buildString {
            append(t.task)
            SectionPlanEngine.handoff(fresh.plan, t).takeIf { it.isNotBlank() }?.let { append("\n\n").append(it) }
            if (t.attempts > 0 && t.note.isNotBlank()) append("\n\nAJUSTE pedido pelo coordenador (tentativa ${t.attempts + 1}): ${t.note}. Preserve o que já estava certo.")
        }
        val r = teamDelegate(sec.sessionId, t.agentName, text, wait = false, timeoutMinutes = 15, taskId = t.id)
        if (r.startsWith(DELEGATION_STARTED)) started += "${t.id} (${t.agentName})"
    }
    return started
}

/**
 * Vigia do coordenador: sempre que ele para, confere o plano. Inicia etapas prontas, cobra validação de
 * entregas, pede o relatório final quando tudo foi aprovado e retoma etapas órfãs. Sem progresso após
 * [MAX_PLAN_NUDGES] lembretes, marca o plano como travado e avisa o usuário — nunca fica em loop.
 */
internal fun AgentRunner.planWatchdog(sectionId: String, afterRestart: Boolean = false) {
    val generation = sectionGeneration[sectionId] ?: 0
    scope.launch {
        kotlinx.coroutines.delay(if (afterRestart) 4_000 else 1_500)
        val lock = sectionReportLocks.getOrPut(sectionId) { kotlinx.coroutines.sync.Mutex() }
        lock.withLock {
            if ((sectionGeneration[sectionId] ?: 0) != generation) return@withLock
            val sec = store.section(sectionId) ?: return@withLock
            if (!sec.configured || isRunning(sec.sessionId)) return@withLock
            if (sec.plan.isEmpty()) {
                if (store.session(sec.sessionId)?.status in setOf(SessionStatus.DONE, SessionStatus.IDLE))
                    autoContinue(sec)?.let { send(sec.sessionId, it, showUserMessage = false).join() }
                return@withLock
            }
            val coordStatus = store.session(sec.sessionId)?.status
            // Parado pelo usuário, esperando resposta/confirmação ou com erro: não insiste.
            if (!afterRestart && coordStatus !in setOf(SessionStatus.DONE, SessionStatus.IDLE)) return@withLock
            if (coordStatus == SessionStatus.WAITING_USER) return@withLock
            // Etapa marcada como em execução, mas sem agente rodando nem delegação (app reiniciou/morreu).
            sec.plan.filter { it.status == TaskStatus.RUNNING }.forEach { t ->
                val member = store.agent(t.agentId) ?: return@forEach
                val childId = memberSession(sec, member)
                if (!isRunning(childId) && delegations[childId] == null) resumeOrphanTask(sec, t, member, childId)
            }
            val started = planDispatch(sectionId)
            val plan = store.section(sectionId)?.plan ?: return@withLock
            val state = SectionPlanEngine.state(plan)
            val msg = when (state) {
                SectionPlanEngine.State.VALIDATING -> dev.fluwy.app.prompts.Prompts.render("runner.17", mapOf<String, Any?>("lb" to "{{")) +
                    plan.filter { it.status == TaskStatus.REVIEW }.joinToString("\n") { "- ${it.id} (${it.agentName}): ${it.result.replace(Regex("\\s+"), " ").take(400)}" } +
                    dev.fluwy.app.prompts.Prompts.render("runner.16", mapOf<String, Any?>("lb" to "{{"))
                SectionPlanEngine.State.COMPLETED -> if (finalReportAsked.add(sectionId))
                    (if (sectionAuto(sec)) dev.fluwy.app.prompts.Prompts.render("runner.15", mapOf<String, Any?>("lb" to "{{"))
                    else dev.fluwy.app.prompts.Prompts.render("runner.14", mapOf<String, Any?>("lb" to "{{"))) else null
                SectionPlanEngine.State.FAILED, SectionPlanEngine.State.BLOCKED ->
                    "[Sistema] O plano está ${SectionPlanEngine.label(state)}:\n${SectionPlanEngine.describe(plan)}\n" +
                        dev.fluwy.app.prompts.Prompts.render("runner.13", mapOf<String, Any?>("lb" to "{{"))
                else -> null  // agentes trabalhando: o próximo evento é o relatório deles
            }
            if (started.isNotEmpty()) store.addItem(sec.sessionId, ChatItem.Assistant(text = dev.fluwy.app.prompts.Prompts.render("runner.12", mapOf<String, Any?>("lb" to "{{")) + started.joinToString()))
            if (msg == null) {
                if (started.isEmpty()) autoContinue(sec)?.let { send(sec.sessionId, it, showUserMessage = false).join() }
                return@withLock
            }
            val sig = SectionPlanEngine.signature(plan) + state
            val (prev, n) = planNudges[sectionId] ?: ("" to 0)
            val count = if (prev == sig) n + 1 else 1
            planNudges[sectionId] = sig to count
            if (count > MAX_PLAN_NUDGES && state == SectionPlanEngine.State.VALIDATING && sectionAuto(sec)) {
                // Seção autônoma não pode parar esperando revisão: o app decide (refaz entrega vazia, aprova o resto).
                plan.filter { it.status == TaskStatus.REVIEW }.forEach { t ->
                    val verdict = if (t.result.trim().length < 80 && t.attempts < 2) "redo" else "approve"
                    runCatching { teamReview(sec.sessionId, t.id, verdict, dev.fluwy.app.prompts.Prompts.render("runner.10", mapOf<String, Any?>("lb" to "{{")) + (if (verdict == "redo") dev.fluwy.app.prompts.Prompts.render("runner.11", mapOf<String, Any?>("lb" to "{{")) else "")) }
                }
                store.addItem(sec.sessionId, ChatItem.Assistant(text = dev.fluwy.app.prompts.Prompts.render("runner.9", mapOf<String, Any?>("lb" to "{{"))))
                planNudges.remove(sectionId)
                planWatchdog(sectionId)
                return@withLock
            }
            if (count > MAX_PLAN_NUDGES) {
                if (count == MAX_PLAN_NUDGES + 1) store.addItem(sec.sessionId, ChatItem.Error(text =
                    dev.fluwy.app.prompts.Prompts.render("runner.8", mapOf<String, Any?>("max_plan_nudges" to (MAX_PLAN_NUDGES), "section_plan_engine_label" to (SectionPlanEngine.label(state)), "lb" to "{{"))))
                return@withLock
            }
            send(sec.sessionId, msg, showUserMessage = false).join()
        }
    }
}

/** Retoma uma etapa que estava rodando quando o app fechou: o agente continua do histórico dele, sem refazer. */
internal fun AgentRunner.resumeOrphanTask(sec: Section, t: SectionTask, member: AgentProfile, childId: String) {
    val before = store.session(childId)?.items?.size ?: 0
    val pill = ChatItem.Step(tool = TEAM_MEMBER_STEP, title = "Retomando ${t.id}: " + t.task.lineSequence().first().take(100),
        args = "$childId|${member.id}", result = "", status = StepStatus.RUNNING)
    store.addItem(sec.sessionId, pill)
    delegations[childId] = Delegation(sec.id, member.id, pill.id, before, t.task, taskId = t.id)
    send(childId, dev.fluwy.app.prompts.Prompts.render("runner.6", mapOf<String, Any?>("valor" to (t.id), "lb" to "{{")) +
        dev.fluwy.app.prompts.Prompts.render("runner.7", mapOf<String, Any?>("lb" to "{{")), showUserMessage = false)
}

internal suspend fun AgentRunner.teamPlan(sessionId: String, goal: String, tasksJson: String): String {
    val sec = store.section(store.session(sessionId)?.sectionId) ?: return "ERRO: esta conversa não é uma seção."
    if (sec.plan.any { it.status == TaskStatus.RUNNING })
        return "ERRO: há etapas em execução (${sec.plan.filter { it.status == TaskStatus.RUNNING }.joinToString { it.id }}). Espere os relatórios ou pare com team_stop antes de trocar o plano."
    val arr = runCatching { json.parseToJsonElement(tasksJson).jsonArray }.getOrNull()
        ?: return "ERRO: tasks_json deve ser um array JSON [{\"id\":\"T1\",\"agent\":\"Nome\",\"task\":\"...\",\"depends_on\":[]}]."
    val drafts = arr.mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        fun f(k: String) = runCatching { o[k]?.jsonPrimitive?.contentOrNull }.getOrNull().orEmpty().trim()
        val deps = runCatching { o["depends_on"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() } }.getOrNull().orEmpty()
        SectionPlanEngine.Draft(f("id"), f("agent"), f("task"), deps)
    }
    val team = sec.agentIds.mapNotNull { store.agent(it) }
    SectionPlanEngine.validate(drafts, team.map { it.name }.toSet())?.let { return "ERRO no plano: $it. Corrija e chame team_plan de novo." }
    val tasks = drafts.map { d ->
        val a = team.first { it.name.equals(d.agent, true) }
        SectionTask(id = d.id, agentId = a.id, agentName = a.name, task = d.task, dependsOn = d.dependsOn)
    }
    synchronized(planLocks.getOrPut(sec.id) { Any() }) {
        store.section(sec.id)?.let { store.upsertSection(it.copy(plan = tasks, planGoal = goal.trim().take(200))) }
    }
    planNudges.remove(sec.id); finalReportAsked.remove(sec.id)
    store.section(sec.id)?.let { syncPlanItem(it) }
    val started = planDispatch(sec.id)
    return "Plano salvo (${tasks.size} etapas). " + (if (started.isEmpty()) "Nenhuma etapa pôde começar agora." else "Iniciadas: ${started.joinToString()}.") +
        " O app inicia sozinho cada etapa quando as dependências forem aprovadas por você com team_review; você recebe um relatório a cada entrega.\n" +
        SectionPlanEngine.describe(store.section(sec.id)?.plan.orEmpty())
}

internal suspend fun AgentRunner.teamReview(sessionId: String, taskId: String, verdictRaw: String, note: String): String {
    val sec = store.section(store.session(sessionId)?.sectionId) ?: return "ERRO: esta conversa não é uma seção."
    val verdict = when (verdictRaw.trim().lowercase()) {
        "approve", "aprovar", "ok" -> SectionPlanEngine.Verdict.APPROVE
        "redo", "refazer", "ajustar" -> SectionPlanEngine.Verdict.REDO
        "block", "bloquear" -> SectionPlanEngine.Verdict.BLOCK
        "cancel", "cancelar" -> SectionPlanEngine.Verdict.CANCEL
        else -> return "ERRO: verdict deve ser approve, redo, block ou cancel."
    }
    if (verdict == SectionPlanEngine.Verdict.REDO && note.isBlank()) return "ERRO: para redo diga em note exatamente o que ajustar."
    var err: String? = null
    editPlan(sec.id) { plan -> SectionPlanEngine.review(plan, taskId.trim(), verdict, note).also { err = it.second }.first }
    err?.let { return "ERRO: $it" }
    // Reflexion: a crítica do coordenador vira uma lição curta na memória do agente, usada nas próximas tarefas dele.
    if ((verdict == SectionPlanEngine.Verdict.REDO || verdict == SectionPlanEngine.Verdict.BLOCK) && note.isNotBlank())
        sec.plan.firstOrNull { it.id.equals(taskId.trim(), true) }?.let { t -> store.agent(t.agentId)?.let { a ->
            store.upsertAgent(a.copy(memory = AgentLessons.add(a.memory, t.task, note)))
        } }
    val started = planDispatch(sec.id)
    val plan = store.section(sec.id)?.plan.orEmpty()
    return "Etapa $taskId: ${verdict.name.lowercase()}. " + (if (started.isNotEmpty()) "Iniciadas automaticamente: ${started.joinToString()}. " else "") +
        "\n" + SectionPlanEngine.describe(plan)
}

internal fun AgentRunner.memberReport(member: AgentProfile, childId: String, before: Int): String {
    val child = store.session(childId)
    val newItems = child?.items?.drop(before).orEmpty()
    val answer = newItems.lastOrNull { it is ChatItem.Assistant } as? ChatItem.Assistant
    return buildString {
        when {
            newItems.any { it is ChatItem.Approval && it.state == "pending" } -> append("${member.name} está AGUARDANDO A CONFIRMAÇÃO do usuário no chat dele.\n")
            child?.status == SessionStatus.WAITING_USER -> append("${member.name} está aguardando uma resposta do usuário no chat dele.\n")
            child?.status == SessionStatus.ERROR -> append("${member.name} terminou com erro.\n")
        }
        append("Resposta de ${member.name}:\n")
        val text = answer?.text?.takeIf { it.isNotBlank() }
        if (text != null) append(text.take(4_000))
        else {
            // OpenBot 0.70: terminou sem texto → o coordenador recebe o que foi feito, não um vazio (evita re-delegar).
            val steps = newItems.filterIsInstance<ChatItem.Step>().filter { it.tool !in setOf("model_switch", "undo") }.takeLast(5)
            append("(${member.name} terminou sem escrever uma resposta final.)")
            if (steps.isNotEmpty()) {
                append("\nÚltimos passos executados:")
                steps.forEach { st -> append("\n- ${st.tool}: ${st.title.take(100)} [${st.status.name.lowercase()}]" + (st.result.takeIf { it.isNotBlank() }?.let { " → " + it.replace('\n', ' ').take(160) } ?: "")) }
            }
            append("\nAntes de re-delegar, confira esses passos e os arquivos do workspace listados abaixo: o trabalho pode já estar feito.")
        }
        newItems.filterIsInstance<ChatItem.Error>().lastOrNull()?.let { append("\nÚltimo erro: " + it.text.take(300)) }
    }
}

/**
 * Chamado quando um chat termina uma execução. Se for um agente de seção trabalhando numa delegação,
 * atualiza a pílula e entrega o relatório ao coordenador (um por vez, sem interromper a resposta dele).
 */
internal fun AgentRunner.onRunEnded(sessionId: String) {
    // Entrega final após "pare": nada de vigia do plano, lembretes ou relatório que faria o trabalho recomeçar.
    if (sessionId in finalDelivery) return
    store.session(sessionId)?.sectionId?.let { sid -> store.section(sid)?.takeIf { it.sessionId == sessionId }?.let { planWatchdog(it.id); return } }
    val d = delegations[sessionId] ?: return
    val child = store.session(sessionId) ?: run { delegations.remove(sessionId); return }
    val sec = store.section(d.sectionId) ?: run { delegations.remove(sessionId); return }
    val member = store.agent(d.memberId) ?: run { delegations.remove(sessionId); return }
    val waitingUser = child.status == SessionStatus.WAITING_USER
    val pill = store.session(sec.sessionId)?.items?.firstOrNull { it.id == d.pillId } as? ChatItem.Step
    // Pausado pelo usuário: a tarefa segue aberta; nada é relatado até ele retomar e o agente terminar.
    if (sessionId in pausedDelegations) {
        if (pill != null) store.replaceItem(sec.sessionId, pill.copy(status = StepStatus.RUNNING, result = "pausado"))
        return
    }
    // Falha temporária do modelo com retomada agendada: o agente continua sozinho; nada a relatar ainda.
    if (child.status == SessionStatus.SLEEPING) {
        if (pill != null) store.replaceItem(sec.sessionId, pill.copy(status = StepStatus.RUNNING, result = "retomando sozinho"))
        return
    }
    if (pill != null) store.replaceItem(sec.sessionId, pill.copy(
        status = if (child.status == SessionStatus.ERROR) StepStatus.ERROR else if (waitingUser) StepStatus.RUNNING else StepStatus.OK,
        result = if (waitingUser) "aguardando você" else "",
    ))
    // Esperando o usuário (confirmação/pergunta): mantém a delegação viva para relatar o resultado final depois.
    if (!waitingUser) delegations.remove(sessionId)
    var report = memberReport(member, sessionId, d.startIndex)
    if (d.taskId != null && !waitingUser) {
        val ok = child.status != SessionStatus.ERROR
        val answer = (child.items.drop(d.startIndex).lastOrNull { it is ChatItem.Assistant } as? ChatItem.Assistant)?.text.orEmpty()
        editPlan(sec.id) { SectionPlanEngine.finished(it, d.taskId, ok, answer.ifBlank { report }, System.currentTimeMillis()) }
        val t = store.section(sec.id)?.plan?.firstOrNull { it.id == d.taskId }
        report += "\n\n" + when (t?.status) {
            TaskStatus.REVIEW -> "Etapa ${d.taskId} do plano ENTREGUE. Valide agora: team_review(task_id=\"${d.taskId}\", verdict=approve|redo|block, note). Aprovando, as próximas etapas começam sozinhas."
            TaskStatus.PENDING -> "Etapa ${d.taskId} falhou (${t.note}); o app vai tentar de novo automaticamente."
            TaskStatus.FAILED -> "Etapa ${d.taskId} FALHOU de vez (${t.note}). Reorganize com team_plan ou explique o bloqueio ao usuário."
            else -> "Etapa ${d.taskId}: ${t?.status}."
        }
    }
    if (!waitingUser && d.task.isNotBlank()) {
        val answer = (child.items.drop(d.startIndex).lastOrNull { it is ChatItem.Assistant } as? ChatItem.Assistant)?.text.orEmpty()
        if (answer.isNotBlank()) delivered.getOrPut(member.id) { java.util.Collections.synchronizedList(mutableListOf()) }
            .apply { add(Delivered(d.task, System.currentTimeMillis(), answer)); while (size > 20) removeAt(0) }
    }
    val generation = sectionGeneration[sec.id] ?: 0
    scope.launch {
        val lock = sectionReportLocks.getOrPut(sec.id) { kotlinx.coroutines.sync.Mutex() }
        lock.withLock {
            if ((sectionGeneration[sec.id] ?: 0) != generation) return@withLock  // seção pausada depois deste relatório
            var waited = 0
            // Coordenador em espera (sleep) não segura o relatório: a entrega chegou, então ele é acordado na hora.
            fun coordSleeping() = store.session(sec.sessionId)?.status == SessionStatus.SLEEPING
            while (isRunning(sec.sessionId) && !coordSleeping() && waited < 1_800) { kotlinx.coroutines.delay(2_000); waited += 2 }
            val wokeEarly = coordSleeping()
            if (wokeEarly) store.editSession(sec.sessionId) { it.copy(wakeAt = null, wakeReason = "") }
            send(sec.sessionId, "[Relatório automático — ${member.name}${if (waitingUser) " parou e aguarda o usuário" else " terminou"}]\n$report" + workspaceChanges(child.id, d.startedAt) +
                (if (wokeEarly) "\n[App] Você estava em espera; como o agente terminou antes do prazo, a espera foi encerrada. Continue agora a partir desta entrega." else ""),
                showUserMessage = false).join()
        }
    }
}

/**
 * Contexto compartilhado da seção para o prompt: mensagens do usuário no chat de origem e no do coordenador,
 * respostas a perguntas, nomes dos dados seguros (cofre único da seção) e entregas dos outros agentes.
 */
internal fun AgentRunner.sectionSharedContext(sec: Section, sessionId: String, coordinator: Boolean): String = runCatching {
    val origin = sec.originSessionId?.let { store.session(it) }
    val coord = store.session(sec.sessionId)
    val originMsgs = origin?.items.orEmpty().filterIsInstance<ChatItem.User>().map { it.text }.takeLast(8)
    val coordMsgs = if (coordinator) emptyList() else coord?.items.orEmpty().filterIsInstance<ChatItem.User>().map { it.text }
    val answers = (origin?.items.orEmpty() + coord?.items.orEmpty()).filterIsInstance<ChatItem.Question>()
        .filter { it.state == "answered" }.sortedBy { it.time }
        .flatMap { q -> q.questions.mapIndexedNotNull { i, spec -> q.answers.getOrNull(i)?.takeIf { it.isNotEmpty() }?.let { spec.question to it.joinToString(", ") } } }
    val me = store.session(sessionId)?.agentId
    val deliveries = if (coordinator) emptyList() else sec.agentIds.filter { it != me }.flatMap { id ->
        val name = store.agent(id)?.name ?: return@flatMap emptyList()
        delivered[id]?.let { synchronized(it) { it.takeLast(2).map { d -> SectionSharedContext.Delivery(name, d.task, d.answer) } } }.orEmpty()
    }
    SectionSharedContext.render(sec.firstTask, originMsgs + coordMsgs, answers, SecretVault.names(sessionId), deliveries)
}.getOrDefault("")

internal fun AgentRunner.resolveMember(sec: Section, name: String): AgentProfile? {
    val team = sec.agentIds.mapNotNull { store.agent(it) }
    val w = name.trim()
    return team.firstOrNull { it.name.equals(w, true) } ?: team.firstOrNull { it.name.contains(w, true) || w.contains(it.name, true) }
}

/**
 * Checkpoint para o coordenador: a cada [MemberSupervision.CHECKPOINT_EVERY_STEPS] passos, ou na hora em que
 * aparece um sinal de desvio (falhas seguidas, ação repetida, varredura do sistema). O coordenador decide:
 * deixar seguir (feedback curto), corrigir (team_message) ou parar (team_stop).
 */
internal fun AgentRunner.memberCheckpoint(sessionId: String, steps: Int) {
    val d = delegations[sessionId] ?: return
    val sec = store.section(d.sectionId) ?: return
    val member = store.agent(d.memberId) ?: return
    val child = store.session(sessionId) ?: return
    val recentSteps = child.items.drop(d.startIndex).filterIsInstance<ChatItem.Step>().takeLast(8)
    val risk = MemberSupervision.riskSignal(recentSteps.map { MemberSupervision.Action(it.tool, it.args, it.status == StepStatus.ERROR) })
    val last = lastCheckpointStep[sessionId] ?: 0
    val periodic = steps - last >= MemberSupervision.CHECKPOINT_EVERY_STEPS
    if (!periodic && (risk == null || steps - last < 3)) return
    lastCheckpointStep[sessionId] = steps
    val plan = child.items.lastOrNull { it is ChatItem.Plan } as? ChatItem.Plan
    val feedback = (child.items.drop(d.startIndex).lastOrNull { it is ChatItem.Assistant } as? ChatItem.Assistant)?.text?.take(300)
    val digest = buildString {
        val minutes = (System.currentTimeMillis() - d.startedAt) / 60_000
        append("[Checkpoint — ${member.name}, $steps passos e $minutes min nesta tarefa]\n")
        if (minutes >= MEMBER_SLOW_MINUTES) append("⏱ Passou de $MEMBER_SLOW_MINUTES min: se ele não está perto de entregar, use team_stop e redivida a tarefa.\n")
        append("Tarefa: ${d.task.take(220)}\n")
        if (plan != null) append("Plano dele: " + plan.phases.joinToString(" · ") { (if (it.done) "[x] " else "[ ] ") + it.title.take(60) } + "\n")
        append("Último feedback dele: ${feedback ?: "(nenhum — está só executando ferramentas)"}\n")
        append("Últimas ações:\n" + recentSteps.joinToString("\n") { "- ${it.title}: ${it.args.take(70)}" + if (it.status == StepStatus.ERROR) " ✕" else "" })
        if (risk != null) append("\n⚠ Sinal de desvio: $risk")
        append("\nDecida agora: está no caminho → feedback de 5–30 palavras; desviou ou travou → team_message; já cumpriu ou está perdido → team_stop.")
    }
    val generation = sectionGeneration[sec.id] ?: 0
    scope.launch {
        val lock = sectionReportLocks.getOrPut(sec.id) { kotlinx.coroutines.sync.Mutex() }
        lock.withLock {
            if ((sectionGeneration[sec.id] ?: 0) != generation || delegations[sessionId] == null) return@withLock
            // Coordenador ocupado: checkpoint periódico pode ser descartado; desvio espera até 2 min.
            var waited = 0
            while (isRunning(sec.sessionId) && waited < (if (risk != null) 120 else 0)) { kotlinx.coroutines.delay(2_000); waited += 2 }
            if (isRunning(sec.sessionId)) return@withLock
            send(sec.sessionId, digest, showUserMessage = false).join()
        }
    }
}
