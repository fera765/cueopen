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

internal fun AgentRunner.teamMessage(sessionId: String, agentName: String, message: String): String {
    val sec = store.section(store.session(sessionId)?.sectionId) ?: return "ERRO: esta conversa não é uma seção."
    val member = resolveMember(sec, agentName) ?: return "ERRO: não há agente \"$agentName\" no time."
    if (message.isBlank()) return "ERRO: escreva a orientação."
    val childId = memberSession(sec, member)
    val text = "[Coordenador] $message"
    store.addItem(childId, ChatItem.User(text = text))
    if (isRunning(childId)) {
        coordinatorInbox.getOrPut(childId) { java.util.Collections.synchronizedList(mutableListOf()) }.add(text)
        return "Orientação entregue a ${member.name}; ele aplica no próximo passo."
    }
    // Parado: retoma com a orientação e volta a reportar ao coordenador.
    val before = store.session(childId)?.items?.size ?: 0
    val pill = ChatItem.Step(tool = TEAM_MEMBER_STEP, title = message.lineSequence().first().take(120), args = "$childId|${member.id}", result = "", status = StepStatus.RUNNING)
    store.addItem(sec.sessionId, pill)
    delegations[childId] = Delegation(sec.id, member.id, pill.id, before, message)
    send(childId, text, showUserMessage = false)
    return "${member.name} retomou com a sua orientação."
}

/** Passa uma etapa do plano para outro agente do time (parando quem estava com ela) e a reinicia. */
internal suspend fun AgentRunner.teamReassign(sessionId: String, taskId: String, agentName: String, note: String): String {
    val sec = store.section(store.session(sessionId)?.sectionId) ?: return "ERRO: esta conversa não é uma seção."
    val t = sec.plan.firstOrNull { it.id.equals(taskId.trim(), true) } ?: return "ERRO: etapa \"$taskId\" não existe no plano.\n" + SectionPlanEngine.describe(sec.plan)
    val member = resolveMember(sec, agentName) ?: return "ERRO: não há agente \"$agentName\" no time."
    if (t.status == TaskStatus.DONE) return "ERRO: a etapa ${t.id} já foi aprovada; não há o que reatribuir."
    if (t.status == TaskStatus.CANCELLED) return "ERRO: a etapa ${t.id} foi cancelada. Crie outra com team_plan."
    if (t.agentId == member.id && (t.status == TaskStatus.RUNNING || t.status == TaskStatus.PENDING)) return "A etapa ${t.id} já está com ${member.name}."
    store.agent(t.agentId)?.takeIf { it.id != member.id }?.let { old ->
        val childId = memberSession(sec, old)
        val d = delegations[childId]
        if (d?.taskId.equals(t.id, true)) {
            delegations.remove(childId); coordinatorInbox.remove(childId)
            stop(childId)
            store.addItem(childId, ChatItem.Error(text = "Etapa ${t.id} reatribuída a ${member.name} pelo coordenador."))
            (store.session(sec.sessionId)?.items?.firstOrNull { it.id == d?.pillId } as? ChatItem.Step)?.let { p ->
                store.replaceItem(sec.sessionId, p.copy(status = StepStatus.OK, result = "reatribuída a ${member.name}"))
            }
        }
    }
    editPlan(sec.id) { plan -> plan.map { if (it.id != t.id) it else it.copy(agentId = member.id, agentName = member.name, status = TaskStatus.PENDING,
        attempts = 0, result = "", startedAt = null, endedAt = null,
        note = ("reatribuída de ${t.agentName}" + (if (note.isNotBlank()) ": ${note.trim()}" else "")).take(300)) } }
    planNudges.remove(sec.id); autoNudges.remove(sec.id)
    val started = planDispatch(sec.id)
    return "Etapa ${t.id} reatribuída de ${t.agentName} para ${member.name}. " +
        (if (t.id in started || started.any { it.contains(t.id) }) "${member.name} já começou." else "Ela começa quando as dependências forem aprovadas.") +
        "\n" + SectionPlanEngine.describe(store.section(sec.id)?.plan.orEmpty())
}

internal fun AgentRunner.teamStop(sessionId: String, agentName: String, reason: String): String {
    store.section(store.session(sessionId)?.sectionId)?.let { sec -> resolveMember(sec, agentName)?.let { pausedDelegations.remove(memberSession(sec, it)) } }
    val sec = store.section(store.session(sessionId)?.sectionId) ?: return "ERRO: esta conversa não é uma seção."
    val member = resolveMember(sec, agentName) ?: return "ERRO: não há agente \"$agentName\" no time."
    val childId = memberSession(sec, member)
    val d = delegations.remove(childId)  // antes do stop: o fim da execução não gera relatório duplicado
    coordinatorInbox.remove(childId)
    val wasRunning = isRunning(childId)
    stop(childId)
    store.addItem(childId, ChatItem.Error(text = "Parado pelo coordenador: ${reason.take(200)}"))
    d?.let { del ->
        (store.session(sec.sessionId)?.items?.firstOrNull { it.id == del.pillId } as? ChatItem.Step)?.let { p ->
            store.replaceItem(sec.sessionId, p.copy(status = StepStatus.OK, result = "parado pelo coordenador"))
        }
    }
    val partial = memberReport(member, childId, d?.startIndex ?: 0)
    d?.taskId?.let { tid -> editPlan(sec.id) { SectionPlanEngine.finished(it, tid, ok = true, result = "PARADO: ${reason.take(120)}. $partial", now = System.currentTimeMillis()) } }
    return (if (d?.taskId != null) "Etapa ${d.taskId} foi para validação: aprove, peça redo ou bloqueie com team_review. " else "") + (if (wasRunning) "${member.name} parado. " else "${member.name} já estava parado. ") + partial
}

internal fun AgentRunner.teamStatus(sessionId: String): String {
    val sec = store.section(store.session(sessionId)?.sectionId) ?: return "ERRO: esta conversa não é uma seção."
    return sec.agentIds.mapNotNull { store.agent(it) }.mapIndexed { idx, a ->
        val child = store.state.value.sessions.firstOrNull { it.sectionId == sec.id && it.agentId == a.id && it.id != sec.sessionId }
        val state = when {
            child == null -> "sem tarefas ainda"
            isRunning(child.id) -> "trabalhando"
            child.items.any { it is ChatItem.Approval && it.state == "pending" } -> "aguardando confirmação do usuário"
            child.status == SessionStatus.WAITING_USER -> "aguardando o usuário"
            child.status == SessionStatus.ERROR -> "erro"
            else -> "livre"
        }
        val last = (child?.items?.lastOrNull { it is ChatItem.Assistant } as? ChatItem.Assistant)?.text?.take(300)?.replace('\n', ' ')
        "${idx + 1}. ${a.name} — $state" + (last?.let { "\n   Última resposta: $it" } ?: "")
    }.joinToString("\n").ifBlank { "Time vazio." } +
        (if (sec.plan.isNotEmpty()) "\n\n" + SectionPlanEngine.describe(sec.plan) else "")
}

/** Troca {{secret:nome}} pelos valores do cofre em todos os textos de um objeto de argumentos. */
internal fun AgentRunner.resolveArgs(sessionId: String, o: JsonObject): JsonObject = JsonObject(o.mapValues { (_, v) ->
    val p = v as? kotlinx.serialization.json.JsonPrimitive
    if (p != null && p.isString && p.content.contains("secret:")) kotlinx.serialization.json.JsonPrimitive(SecretVault.resolve(sessionId, p.content))
    else if (v is JsonObject) resolveArgs(sessionId, v) else v
})

internal fun AgentRunner.maybeTitle(sessionId: String, text: String) {
    val s = store.session(sessionId) ?: return
    if (s.title != "Nova tarefa") return
    // Título montado do próprio pedido: sem chamada extra ao modelo.
    store.editSession(sessionId) { it.copy(title = TaskTitle.make(text)) }
}
