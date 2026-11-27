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

internal fun AgentRunner.systemPrompt(
    sessionId: String,
    agent: AgentProfile?,
    session: Session,
    contextPlan: ToolContextPlanner.Plan? = null,
    allowDeviceTools: Boolean = true,
): String {
    if (session.connectorEditId != null) return connectorEditSystemPrompt(session)
    val st = store.state.value
    val lastUser = session.llm.lastOrNull { it.role == "user" }?.content.orEmpty()
    val query = lastUser + " " + session.summary.take(1500)
    // Skills: as mais relevantes ao pedido primeiro, com teto (o índice inteiro não precisa ir em todo passo)
    val qWords = ToolContextPlanner.norm(query).split(Regex("[^a-z0-9]+")).filter { it.length >= 4 }.toSet()
    // Biblioteca compartilhada: toda skill ativa vale para todo agente. As do agente (skillIds) são só destaque na ordem.
    val pinned = agent?.skillIds.orEmpty().toSet()
    val skills: List<Skill> = st.skills.filter { it.enabled }
        .sortedWith(compareByDescending<Skill> { SkillMatcher.score(lastUser, SkillMatcher.Candidate(it.name, it.description)) }
            .thenByDescending { it.id in pinned }
            .thenByDescending { sk -> ToolContextPlanner.norm(sk.name + " " + sk.description).split(Regex("[^a-z0-9]+")).count { it in qWords } })
        .take(contextPlan?.skillsShown ?: Int.MAX_VALUE)
    val memories = ctxMgr.relevantMemories(query, agent?.id, maxChars = contextPlan?.memoryChars ?: 2400).also { ctxMgr.touch(it) }
    val knowledge = ctxMgr.relevantKnowledge(query, maxChars = contextPlan?.knowledgeChars ?: 4500)
    val notes = ctxMgr.readNotes(sessionId)
    val date = SimpleDateFormat("EEEE, d 'de' MMMM 'de' yyyy", Locale("pt", "BR")).format(Date())
    return run {
        val requestedCaps = if (allowDeviceTools) agent?.deviceCaps.orEmpty() else emptyList()
        val grantedCaps = requestedCaps.filter { dev.fluwy.app.device.DeviceCaps.granted(ctx, it) }
        val allDeviceTools = dev.fluwy.app.device.DeviceCapPolicy.availableTools(requestedCaps, grantedCaps)
        val deviceGroupInContext = contextPlan == null || "device" in contextPlan.groups
        val promptDeviceTools = if (deviceGroupInContext) allDeviceTools else emptySet()
        val walletPolicyForPrompt = WalletAgentPolicyStore(ctx.applicationContext)
        val walletHasGrants = agent != null && (
            walletPolicyForPrompt.canAgentReadAddress(agent.id) ||
                walletPolicyForPrompt.canAgentReadBalance(agent.id) ||
                walletPolicyForPrompt.canAgentRequestTransfer(agent.id) ||
                walletPolicyForPrompt.canAgentDayTrade(agent.id)
            )
        val polymarketConnectorUsable = runCatching {
            val hub = dev.fluwy.app.FluwyApp.instance.uiConnectors
            hub.get(dev.fluwy.app.uiconnector.polymarket.PolymarketPublicConnector.ID)?.let { hub.usableBy(it, agent) } == true
        }.getOrDefault(false)
        val memoryFacts = PromptContextPolicy.boundedFacts(
            agentFacts = agent?.memory.orEmpty().takeLast(10),
            recalledFacts = ctxMgr.describeMemories(memories),
            maxChars = contextPlan?.memoryChars ?: 2_400,
        )
        dev.fluwy.app.prompts.Prompts.render(
            "agent_system",
            mapOf<String, Any?>(
                "fluwy" to (agent?.name ?: "Fluwy"),
                "date" to (date),
                "zone" to (LocalClock.zone()),
                "informado" to (st.settings.userName.ifBlank { "não informado" }),
                "session_id" to (sandbox.guestPath(sessionId)),
                "instruction_hierarchy" to ((PromptContextPolicy.instructionHierarchy).toString()),
                "session_id_2" to (sandbox.guestPath(sessionId)),
                "prompt_line" to ((BrowserRedirect.PROMPT_LINE).toString()),
                "prompt_line_2" to (MultiRequest.PROMPT_LINE),
                "append_line" to (buildString {
    run {
                val hubRef = dev.fluwy.app.connectors.ActivepiecesHub.REF
                val connected = store.state.value.connectors.filter { it.enabled && it.connectedAt != null && (agent?.useConnectorHub != false || it.gatewayRef != hubRef) }
                val hub = connected.firstOrNull { it.gatewayRef == hubRef }
                val mentioned = Mentions.find(lastUserText(session), mentionTargets()).map { h ->
                    when (h.target.kind) {
                        Mentions.Kind.SKILL -> "skill ${h.target.label} (skill_load)"
                        Mentions.Kind.CONNECTOR -> "conector ${h.target.label}"
                        Mentions.Kind.AGENT -> "agent ${h.target.label}"
                        Mentions.Kind.COMMAND -> h.target.label
                        Mentions.Kind.WEBAPI -> if (h.target.id.isBlank()) "API Hub (webapi_list para escolher)" else dev.fluwy.app.prompts.Prompts.render("agent_system.1", mapOf<String, Any?>("target" to (h.target.id), "target_2" to (h.target.id), "lb" to "{{"))
                    }
                }.distinct()
                ConnectorRouting.promptLines(
                    connected.filter { it.gatewayRef != hubRef }.map { it.name to "mcp_" + it.id.replace(Regex("[^A-Za-z0-9_]"), "_") },
                    hubConnected = hub != null, hubInventory = hub?.inventory, mentioned = mentioned,
                ).forEach { appendLine(it) }
            }
    }.removeSuffix("\n")),
                "append_line_2" to (buildString {
    dev.fluwy.app.device.DeviceCapPolicy.agentGuidance(promptDeviceTools).forEach { appendLine("6c. $it") }
    }.removeSuffix("\n")),
                "caminho" to (buildString {
    if (deviceGroupInContext) {
                val missingGrants = requestedCaps.filter { !dev.fluwy.app.device.DeviceCaps.granted(ctx, it) }
                if (missingGrants.isNotEmpty()) appendLine(dev.fluwy.app.prompts.Prompts.render("agent_system.2", mapOf<String, Any?>("lb" to "{{")) + missingGrants.joinToString(", ") { cap -> dev.fluwy.app.device.DeviceCaps.all.firstOrNull { it.id == cap }?.title ?: cap } + dev.fluwy.app.prompts.Prompts.render("agent_system.3", mapOf<String, Any?>("lb" to "{{")))
            }
    }.removeSuffix("\n")),
                "secret" to (buildString {
    SecretVault.names(sessionId).takeIf { it.isNotEmpty() }?.let { appendLine(dev.fluwy.app.prompts.Prompts.render("agent_system.4", mapOf<String, Any?>("lb" to "{{")) + it.joinToString { n -> "{{secret:$n}}" }) }
    }.removeSuffix("\n")),
                "last_user" to (if ((!session.webApiBuilder)) (buildString {
    append(webApiHint(lastUser))
    }.removeSuffix("\n")) else ""),
                "catalog" to (if ((session.webApiBuilder)) ((dev.fluwy.app.browser.WebApiHub.catalog(ctx)).toString()) else ""),
                "max_tests" to (if ((session.scriptBuilder)) (ScriptBuilderPolicy.MAX_TESTS) else ""),
                "example" to (if ((session.scriptBuilder)) ((dev.fluwy.app.browser.UserScriptMeta.EXAMPLE).toString()) else ""),
                "garantido" to (buildString {
    runCatching {
                val hub = dev.fluwy.app.FluwyApp.instance.uiConnectors
                val usable = hub.connectors.filter { hub.usableBy(it, agent) }
                if (usable.isNotEmpty()) {
                    appendLine()
                    appendLine("## UI Connectors disponíveis")
                    usable.forEach { uc ->
                        val st = if (uc.isLoggedIn || hub.hasSavedCredentials(uc.spec.id)) "conectado" else dev.fluwy.app.prompts.Prompts.render("agent_system.5", mapOf<String, Any?>("spec_name" to (uc.spec.name), "lb" to "{{"))
                        appendLine(dev.fluwy.app.prompts.Prompts.render("agent_system.6", mapOf<String, Any?>("spec_name" to (uc.spec.name), "valor" to (st), "spec" to (uc.spec.id), "description" to (uc.spec.description), "lb" to "{{")))
                    }
                    if (hub.hasOrderTools(agent)) {
                        appendLine(dev.fluwy.app.prompts.Prompts.render("agent_system.7", mapOf<String, Any?>("lb" to "{{")))
                        appendLine(dev.fluwy.app.prompts.Prompts.render("agent_system.8", mapOf<String, Any?>("lb" to "{{")))
                    }
                    appendLine(dev.fluwy.app.prompts.Prompts.render("agent_system.9", mapOf<String, Any?>("lb" to "{{")))
                    val connectorWorkspaces = runCatching {
                        sandbox.connectorsDir.listFiles().orEmpty()
                            .filter { it.isDirectory && File(it, "workspace").isDirectory }
                            .map { "/root/connectors/${it.name}/workspace" }
                            .sorted()
                    }.getOrDefault(emptyList())
                    appendLine(dev.fluwy.app.prompts.Prompts.render("agent_system.10", mapOf<String, Any?>("lb" to "{{")))
                    if (connectorWorkspaces.isEmpty()) appendLine(dev.fluwy.app.prompts.Prompts.render("agent_system.11", mapOf<String, Any?>("lb" to "{{")))
                    else connectorWorkspaces.forEach { appendLine("  Disponível: $it") }
                    appendLine(dev.fluwy.app.prompts.Prompts.render("agent_system.12", mapOf<String, Any?>("lb" to "{{")))
                    appendLine(dev.fluwy.app.prompts.Prompts.render("agent_system.13", mapOf<String, Any?>("lb" to "{{")))
                    appendLine(dev.fluwy.app.prompts.Prompts.render("agent_system.14", mapOf<String, Any?>("lb" to "{{")))
                }
            }
    }.removeSuffix("\n")),
                "time" to (buildString {
    session.squadProject?.takeIf { session.squadParentId == null }?.let { proj ->
                appendLine(); appendLine("## Modo exército ativo neste chat")
                appendLine(dev.fluwy.app.prompts.Prompts.render("agent_system.15", mapOf<String, Any?>("proj" to (proj), "proj_2" to (proj), "lb" to "{{")))
                appendLine(dev.fluwy.app.prompts.Prompts.render("agent_system.16", mapOf<String, Any?>("lb" to "{{")))
                appendLine(dev.fluwy.app.prompts.Prompts.render("agent_system.17", mapOf<String, Any?>("lb" to "{{")))
            }
    }.removeSuffix("\n")),
                "mude" to (buildString {
    run {
                val cats = if (agent?.confirmMode == "auto") emptyList()
                    else (agent?.confirmCategories ?: ActionGate.DEFAULT_CATEGORIES).mapNotNull { ActionGate.Category.of(it)?.label?.lowercase() }
                appendLine(); appendLine("## Confirmações")
                appendLine(if (cats.isEmpty()) dev.fluwy.app.prompts.Prompts.render("agent_system.18", mapOf<String, Any?>("lb" to "{{"))
                    else dev.fluwy.app.prompts.Prompts.render("agent_system.19", mapOf<String, Any?>("cats" to (cats.joinToString("; ")), "lb" to "{{")))
                store.section(session.sectionId)?.takeIf { it.coordinatorAgentId != agent?.id && it.sessionId != session.id }?.let { sec ->
                    appendLine(); appendLine(dev.fluwy.app.prompts.Prompts.render("agent_system.20", mapOf<String, Any?>("sec_name" to (sec.name), "lb" to "{{")))
                    appendLine(dev.fluwy.app.prompts.Prompts.render("agent_system.21", mapOf<String, Any?>("lb" to "{{")))
                    appendLine(dev.fluwy.app.prompts.Prompts.render("agent_system.22", mapOf<String, Any?>("lb" to "{{")))
                    appendLine(dev.fluwy.app.prompts.Prompts.render("agent_system.23", mapOf<String, Any?>("lb" to "{{")))
                    appendLine(dev.fluwy.app.prompts.Prompts.render("agent_system.24", mapOf<String, Any?>("lb" to "{{")))
                    appendLine(dev.fluwy.app.prompts.Prompts.render("agent_system.25", mapOf<String, Any?>("lb" to "{{")))
                    appendLine(dev.fluwy.app.prompts.Prompts.render("agent_system.26", mapOf<String, Any?>("lb" to "{{")))
                    appendLine(dev.fluwy.app.prompts.Prompts.render("agent_system.27", mapOf<String, Any?>("lb" to "{{")))
                    appendLine(dev.fluwy.app.prompts.Prompts.render("agent_system.28", mapOf<String, Any?>("lb" to "{{")))
                    appendLine(dev.fluwy.app.prompts.Prompts.render("agent_system.29", mapOf<String, Any?>("lb" to "{{")))
                    appendLine(dev.fluwy.app.prompts.Prompts.render("agent_system.30", mapOf<String, Any?>("lb" to "{{")))
                    appendLine(dev.fluwy.app.prompts.Prompts.render("agent_system.31", mapOf<String, Any?>("lb" to "{{")))
                    appendLine(dev.fluwy.app.prompts.Prompts.render("agent_system.32", mapOf<String, Any?>("lb" to "{{")))
                    if (agent?.confirmMode == "auto") appendLine(dev.fluwy.app.prompts.Prompts.render("agent_system.33", mapOf<String, Any?>("lb" to "{{")))
                    sectionSharedContext(sec, session.id, coordinator = false).takeIf { it.isNotBlank() }?.let { appendLine(); appendLine(it) }
                }
                store.section(session.sectionId)?.takeIf { it.coordinatorAgentId == agent?.id }?.let { sec ->
                    appendLine(); appendLine("## Você coordena a seção ${sec.name}")
                    appendLine("- Time e o que cada um consegue usar: " + sec.agentIds.mapNotNull { store.agent(it) }.joinToString("; ") { a -> a.name + " [" + listOfNotNull("browser".takeIf { a.useBrowser }, "terminal".takeIf { a.useTerminal }).joinToString(" + ").ifBlank { "sem browser nem terminal" } + "]" } + ".")
                    appendLine(dev.fluwy.app.prompts.Prompts.render("agent_system.34", mapOf<String, Any?>("lb" to "{{")))
                    appendLine(dev.fluwy.app.prompts.Prompts.render("agent_system.35", mapOf<String, Any?>("lb" to "{{")))
                    appendLine(dev.fluwy.app.prompts.Prompts.render("agent_system.36", mapOf<String, Any?>("lb" to "{{")))
                    appendLine(dev.fluwy.app.prompts.Prompts.render("agent_system.37", mapOf<String, Any?>("lb" to "{{")))
                    if (sec.plan.isNotEmpty()) appendLine("- Plano atual (persistido):\n" + SectionPlanEngine.describe(sec.plan))
                    appendLine(dev.fluwy.app.prompts.Prompts.render("agent_system.38", mapOf<String, Any?>("lb" to "{{")))
                    appendLine(dev.fluwy.app.prompts.Prompts.render("agent_system.39", mapOf<String, Any?>("lb" to "{{")))
                    appendLine(dev.fluwy.app.prompts.Prompts.render("agent_system.40", mapOf<String, Any?>("lb" to "{{")))
                    appendLine(dev.fluwy.app.prompts.Prompts.render("agent_system.41", mapOf<String, Any?>("lb" to "{{")))
                    appendLine(dev.fluwy.app.prompts.Prompts.render("agent_system.42", mapOf<String, Any?>("lb" to "{{")))
                    if (agent?.confirmMode == "auto") appendLine(dev.fluwy.app.prompts.Prompts.render("agent_system.43", mapOf<String, Any?>("lb" to "{{")))
                    if (sec.scheduleMinutes > 0) appendLine(dev.fluwy.app.prompts.Prompts.render("agent_system.44", mapOf<String, Any?>("schedule_minutes" to (scheduleLabel(sec.scheduleMinutes)), "lb" to "{{")))
                    else appendLine(dev.fluwy.app.prompts.Prompts.render("agent_system.45", mapOf<String, Any?>("lb" to "{{")))
                    appendLine(dev.fluwy.app.prompts.Prompts.render("agent_system.46", mapOf<String, Any?>("lb" to "{{")))
                    appendLine(dev.fluwy.app.prompts.Prompts.render("agent_system.47", mapOf<String, Any?>("lb" to "{{")))
                    appendLine(dev.fluwy.app.prompts.Prompts.render("agent_system.48", mapOf<String, Any?>("lb" to "{{")))
                    appendLine(dev.fluwy.app.prompts.Prompts.render("agent_system.49", mapOf<String, Any?>("lb" to "{{")))
                    appendLine(dev.fluwy.app.prompts.Prompts.render("agent_system.50", mapOf<String, Any?>("lb" to "{{")))
                    appendLine(dev.fluwy.app.prompts.Prompts.render("agent_system.51", mapOf<String, Any?>("lb" to "{{")))
                    appendLine(dev.fluwy.app.prompts.Prompts.render("agent_system.52", mapOf<String, Any?>("lb" to "{{")))
                    appendLine(dev.fluwy.app.prompts.Prompts.render("agent_system.53", mapOf<String, Any?>("lb" to "{{")))
                    appendLine(dev.fluwy.app.prompts.Prompts.render("agent_system.54", mapOf<String, Any?>("lb" to "{{")))
                    appendLine(dev.fluwy.app.prompts.Prompts.render("agent_system.55", mapOf<String, Any?>("lb" to "{{")))
                    appendLine(dev.fluwy.app.prompts.Prompts.render("agent_system.56", mapOf<String, Any?>("lb" to "{{")))
                    appendLine(dev.fluwy.app.prompts.Prompts.render("agent_system.57", mapOf<String, Any?>("lb" to "{{")))
                    sectionSharedContext(sec, session.id, coordinator = true).takeIf { it.isNotBlank() }?.let { appendLine(); appendLine(it) }
                }
                BrowserVisitLog.summary(sessionId).takeIf { it.isNotEmpty() }?.let { visits ->
                    appendLine(); appendLine(dev.fluwy.app.prompts.Prompts.render("agent_system.58", mapOf<String, Any?>("lb" to "{{")))
                    visits.forEach { appendLine(it) }
                    appendLine(dev.fluwy.app.prompts.Prompts.render("agent_system.59", mapOf<String, Any?>("lb" to "{{")))
                }
                if (agent?.useBrowser != false) store.state.value.browserLogins.filter { l -> l.status == "active" && (l.allowedAgentIds.isEmpty() || agent?.id in l.allowedAgentIds) }
                    .takeIf { it.isNotEmpty() }?.let { ls -> appendLine(dev.fluwy.app.prompts.Prompts.render("agent_system.60", mapOf<String, Any?>("lb" to "{{")) +
                        ls.joinToString { it.domain } + dev.fluwy.app.prompts.Prompts.render("agent_system.61", mapOf<String, Any?>("lb" to "{{"))) }
                if (agent?.useBrowser != false) appendLine(dev.fluwy.app.prompts.Prompts.render("agent_system.62", mapOf<String, Any?>("lb" to "{{")))
                if (agent?.useBrowser != false) appendLine(dev.fluwy.app.prompts.Prompts.render("agent_system.63", mapOf<String, Any?>("lb" to "{{")))
                if (agent?.useBrowser != false) appendLine(dev.fluwy.app.prompts.Prompts.render("agent_system.64", mapOf<String, Any?>("lb" to "{{")))
            }
    }.removeSuffix("\n")),
                "agent_instructions" to (if ((!agent?.instructions.isNullOrBlank())) ((PromptContextPolicy.untrustedBlock("agent_instructions", agent!!.instructions, 8_000)).toString()) else ""),
                "skill_description" to (if ((skills.isNotEmpty())) (buildString {
    skills.forEach { sk -> appendLine(PromptContextPolicy.untrustedBlock("skill_description", "${sk.name}: ${sk.description}", 220)) }
    }.removeSuffix("\n")) else ""),
                "memory_facts" to (if ((memoryFacts.isNotEmpty())) ((PromptContextPolicy.untrustedBlock("memory_facts", memoryFacts.joinToString("\n- "), contextPlan?.memoryChars ?: 2_400)).toString()) else ""),
                "isso" to (buildString {
    store.pagesIn(pageSpace(sessionId)).takeIf { it.isNotEmpty() }?.let { ps ->
                appendLine(); appendLine(dev.fluwy.app.prompts.Prompts.render("agent_system.65", mapOf<String, Any?>("lb" to "{{")))
                ps.sortedByDescending { it.updatedAt }.take(15).forEach { p -> appendLine("- ${p.id} · ${p.kind} · \"${p.title}\" · v${p.version}") }
                appendLine(dev.fluwy.app.prompts.Prompts.render("agent_system.66", mapOf<String, Any?>("lb" to "{{")))
            }
    }.removeSuffix("\n")),
                "past_chats_2" to (buildString {
    if (session.summary.isBlank() && session.llm.count { it.role == "user" } <= 1 && session.sectionId == null) {
                val past = MemoryHygiene.pastChats(store.state.value.sessions, session.id, query)
                if (past.isNotEmpty()) {
                    appendLine(); appendLine(dev.fluwy.app.prompts.Prompts.render("agent_system.67", mapOf<String, Any?>("lb" to "{{")))
                    appendLine(PromptContextPolicy.untrustedBlock("past_chats", past.joinToString("\n- "), 1_400))
                }
            }
    }.removeSuffix("\n")),
                "knowledge_2" to (buildString {
    if (knowledge.isNotEmpty()) {
                val knowledgeText = knowledge.joinToString("\n\n") { "Documento: ${it.title}\n${it.content.take(1500)}" }
                appendLine(); appendLine(dev.fluwy.app.prompts.Prompts.render("agent_system.68", mapOf<String, Any?>("lb" to "{{")))
                appendLine(PromptContextPolicy.untrustedBlock("knowledge", knowledgeText, contextPlan?.knowledgeChars ?: 4_500))
            }
    }.removeSuffix("\n")),
                "append_line_3" to (buildString {
    contextPlan?.let { p ->
                val names = runCatching { dev.fluwy.app.FluwyApp.instance.uiConnectors.connectors.associate { it.spec.id to it.spec.name } }.getOrDefault(emptyMap())
                val mcpNames = store.state.value.connectors.associate { connector ->
                    "mcp_${connector.id.replace(Regex("[^A-Za-z0-9_]"), "_")}" to connector.name
                }
                p.index(names, mcpNames).takeIf { it.isNotBlank() }?.let { appendLine(); appendLine(it) }
            }
    }.removeSuffix("\n")),
                "session_summary" to (if ((session.summary.isNotBlank())) ((PromptContextPolicy.untrustedBlock("session_summary", session.summary, 6_000)).toString()) else ""),
                "project_instructions" to (buildString {
    runCatching { java.io.File(sandbox.workspaceFor(sessionId), "AGENTS.md").takeIf { it.isFile && it.length() in 1..200_000 }?.readText() }.getOrNull()             ?.takeIf { it.isNotBlank() }?.let { projectRules ->
                    appendLine(); appendLine(dev.fluwy.app.prompts.Prompts.render("agent_system.69", mapOf<String, Any?>("lb" to "{{")))
                    appendLine(PromptContextPolicy.untrustedBlock("project_instructions", projectRules, 6_000))
                }
    }.removeSuffix("\n")),
                "task_notes" to (if ((notes.isNotBlank())) ((PromptContextPolicy.untrustedBlock("task_notes", notes.takeLast(4_000), 4_000)).toString()) else ""),
                "done_title" to (buildString {
    (session.items.lastOrNull { it is ChatItem.Plan } as? ChatItem.Plan)?.let { p ->
                appendLine(); appendLine(dev.fluwy.app.prompts.Prompts.render("agent_system.70", mapOf<String, Any?>("lb" to "{{")))
                p.phases.forEachIndexed { i, ph -> appendLine("${i + 1}. [${if (ph.done) "x" else " "}] ${ph.title}") }
            }
    }.removeSuffix("\n")),
                "instructions" to (if ((agent != null && agent.autonomous && session.id == agent.homeSessionId)) (agent.mission.ifBlank { agent.instructions.take(400) }) else ""),
                "heartbeat_minutes" to (if ((agent != null && agent.autonomous && session.id == agent.homeSessionId)) (agent.heartbeatMinutes) else ""),
                "lb" to "{{",
            ),
            mapOf(
                "se_worker" to (session.squadParentId == null && !session.title.startsWith("[Worker ")),
                "se_wallet_has_grants" to (walletHasGrants),
                "se_can_agent_autonomous_transfer" to (if ((walletHasGrants)) (agent != null && walletPolicyForPrompt.canAgentAutonomousTransfer(agent.id)) else false),
                "se_can_agent_day_trade" to (if ((walletHasGrants)) (agent != null && walletPolicyForPrompt.canAgentDayTrade(agent.id)) else false),
                "se_polymarket_connector_usable" to (polymarketConnectorUsable),
                "se_web_api_builder" to (!session.webApiBuilder),
                "se_web_api_builder_2" to (session.webApiBuilder),
                "se_script_builder" to (session.scriptBuilder),
                "se_connector_builder" to (session.connectorBuilder),
                "se_connector_builder_2" to (session.connectorBuilder),
                "se_connector_builder_3" to (session.connectorBuilder),
                "se_connector_builder_4" to (session.connectorBuilder),
                "se_connector_builder_5" to (session.connectorBuilder),
                "se_connector_builder_6" to (session.connectorBuilder),
                "se_connector_builder_7" to (session.connectorBuilder),
                "se_instructions" to (!agent?.instructions.isNullOrBlank()),
                "se_skills" to (skills.isNotEmpty()),
                "se_voice_replies" to (session.voiceReplies),
                "se_memory_facts" to (memoryFacts.isNotEmpty()),
                "se_summary" to (session.summary.isNotBlank()),
                "se_notes" to (notes.isNotBlank()),
                "se_home_session_id" to (agent != null && agent.autonomous && session.id == agent.homeSessionId),
                "se_continuous" to (session.continuous),
            ),
        )
    }
}

/** Prompt hermético: a sessão só pode propor alterações de configuração para o ID já vinculado. */
internal fun AgentRunner.connectorEditSystemPrompt(session: Session): String {
    val targetId = session.connectorEditId.orEmpty()
    val hub = dev.fluwy.app.FluwyApp.instance.uiConnectors
    val config = runCatching { hub.editableConfiguration(targetId) }.getOrNull()
    val customDefinition = if (hub.isCustom(targetId)) hub.customYaml(targetId) else null
    val sanitizedDefinition = customDefinition?.let { raw ->
        runCatching { dev.fluwy.app.uiconnector.custom.ConnectorDefinitionEdit.sanitize(raw, session.id) }.getOrNull()
    }?.takeIf { it.length <= 48 * 1024 }
    fun safeText(value: String, limit: Int = 400) =
        if (dev.fluwy.app.uiconnector.custom.ConnectorDefinitionEdit.containsSecretLikeValue(value)) "[conteúdo protegido]" else value.take(limit)
    val encodedConfig = config?.let { c ->
        buildJsonObject {
            put("id", c.id)
            put("name", safeText(c.name, 160))
            put("description", safeText(c.description))
            put("enabled", c.enabled)
            put("agent_required", c.agentRequired)
            put("settings", buildJsonArray {
                c.settings.forEach { setting ->
                    add(buildJsonObject {
                        put("id", setting.id)
                        put("label", safeText(setting.label, 160))
                        put("description", safeText(setting.description))
                        put("type", setting.type.name.lowercase())
                        put("current_value", safeText(setting.value))
                        put("risky", setting.risky)
                    })
                }
            })
        }.toString()
    }
    return run {
        dev.fluwy.app.prompts.Prompts.render(
            "connector_edit_system",
            mapOf<String, Any?>(
                "target_id" to (kotlinx.serialization.json.JsonPrimitive(targetId)),
                "selecionado" to ((if (store.agent(session.agentId)?.useBrowser != false) dev.fluwy.app.prompts.Prompts.render("connector_edit_system.1", mapOf<String, Any?>("lb" to "{{")) else dev.fluwy.app.prompts.Prompts.render("connector_edit_system.2", mapOf<String, Any?>("lb" to "{{"))).toString()),
                "encoded_config" to (if ((!(config == null))) ((encodedConfig).toString()) else ""),
                "sanitized_definition" to (if ((!(config == null))) (buildString {
    when {
                    !hub.isCustom(targetId) -> appendLine(dev.fluwy.app.prompts.Prompts.render("connector_edit_system.3", mapOf<String, Any?>("lb" to "{{")))
                    sanitizedDefinition == null && (customDefinition?.length ?: 0) > 48 * 1024 -> appendLine(dev.fluwy.app.prompts.Prompts.render("connector_edit_system.4", mapOf<String, Any?>("lb" to "{{")))
                    sanitizedDefinition == null -> appendLine(dev.fluwy.app.prompts.Prompts.render("connector_edit_system.5", mapOf<String, Any?>("lb" to "{{")))
                    else -> {
                        appendLine(dev.fluwy.app.prompts.Prompts.render("connector_edit_system.6", mapOf<String, Any?>("lb" to "{{")))
                        appendLine(kotlinx.serialization.json.JsonPrimitive(sanitizedDefinition).toString())
                    }
                }
    }.removeSuffix("\n")) else ""),
                "lb" to "{{",
            ),
            mapOf(
                "se_config" to (config == null),
                "se_fingerprint" to (if ((!(config == null))) (session.connectorEditFingerprint.isNullOrBlank() || session.connectorEditFingerprint != config.fingerprint) else false),
            ),
        )
    }
}

/** Igual ao browser_wait_for do Playwright MCP: esperar dados em vez de reabrir a página. */
internal fun AgentRunner.browserWaitForTool() = tool("browser_wait_for",
    "Espera um texto aparecer (text) ou sumir (text_gone) na página atual, ou apenas aguarda alguns segundos (seconds, máx. 30). " +
        "Use quando a página ainda está carregando dados; é mais barato e confiável do que reabrir a URL. Devolve o snapshot atualizado.",
    buildJsonObject {
        put("text", str("Texto que deve aparecer (opcional)"))
        put("text_gone", str("Texto que deve sumir, ex.: 'Carregando' (opcional)"))
        put("seconds", int("Limite em segundos (1-30; padrão 10)"))
    })
