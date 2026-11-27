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

internal fun AgentRunner.autonomyTick() {
    val now = System.currentTimeMillis()
    val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    store.state.value.agents.filter { it.autonomous && it.homeSessionId != null }.forEach { a ->
        // requireDue=true: o ticker verifica o intervalo; o alarme (requireDue=false) é o backup exato.
        if (canBeat(a, now, hour, requireDue = true)) heartbeat(a)
    }
}

internal fun AgentRunner.startHeartbeatCycle(agent: AgentProfile): Job {
    var a = store.agent(agent.id) ?: agent
    if (!a.autonomous || a.homeSessionId == null) return scope.launch { }
    val home = a.homeSessionId?.let { store.session(it) } ?: Session(title = "🤖 ${a.name} · autônomo", agentId = a.id).also {
        store.upsertSession(it); a = a.copy(homeSessionId = it.id); store.upsertAgent(a)
    }
    store.upsertAgent(a.copy(lastBeatAt = System.currentTimeMillis()))
    val sid = home.id
    val before = store.session(sid)!!
    val llmMark = before.llm.size; val itemsMark = before.items.size
    val tokMark = before.tokensIn + before.tokensOut
    val stamp = LocalClock.line()
    val cycleSection = store.state.value.sections.firstOrNull { it.sessionId == sid && it.configured }
    cycleSection?.let { autoDone.remove(it.id); autoNudges.remove(it.id); planNudges.remove(it.id); finalReportAsked.remove(it.id) }
    store.editSession(sid) {
        it.copy(status = SessionStatus.RUNNING, continuous = false,
            llm = it.llm + LlmMessage("user", if (cycleSection != null)
                dev.fluwy.app.prompts.Prompts.render("heartbeat.6", mapOf<String, Any?>("stamp" to (stamp), "lb" to "{{"))
                else dev.fluwy.app.prompts.Prompts.render("heartbeat.5", mapOf<String, Any?>("stamp" to (stamp), "lb" to "{{"))))
    }
    dev.fluwy.app.sandbox.SandboxService.start(ctx, "${a.name}: heartbeat")
    val job = scope.launch {
        var watchdogTimedOut = false
        var cycleFailed = false
        var cancelledByCaller = false
        try {
            // Distingue o watchdog do ciclo de timeouts lançados por uma ferramenta interna.
            val completed = kotlinx.coroutines.withTimeoutOrNull(10 * 60_000L) { loop(sid); true } ?: false
            if (!completed) {
                watchdogTimedOut = true
                executions.cancelSession(sid)
                terminals.get(sid).cancelAgentCommand()
                try {
                    browserFor(sid).cancelNavigation()
                } catch (cancel: kotlinx.coroutines.CancellationException) {
                    throw cancel
                } catch (_: Throwable) { }
                finalizeRunningSteps(sid, dev.fluwy.app.prompts.Prompts.render("heartbeat.1", mapOf<String, Any?>("lb" to "{{")))
                runCatching { dev.fluwy.app.diag.ErrorLog.log("heartbeat", dev.fluwy.app.prompts.Prompts.render("heartbeat.2", mapOf<String, Any?>("lb" to "{{")), sid, "heartbeat") }
                store.addItem(sid, ChatItem.Error(text = dev.fluwy.app.prompts.Prompts.render("heartbeat.3", mapOf<String, Any?>("lb" to "{{"))))
            }
        } catch (c: kotlinx.coroutines.CancellationException) {
            cancelledByCaller = true
            throw c
        } catch (t: Throwable) {
            cycleFailed = true
            runCatching { dev.fluwy.app.diag.ErrorLog.log("heartbeat", "${t.javaClass.simpleName}: ${t.message.orEmpty()}", sid, "heartbeat") }
            store.addItem(sid, ChatItem.Error(text = "Heartbeat falhou: ${t.message}"))
        } finally {
            val after = store.session(sid)
            if (after != null && !cancelledByCaller) {
                val used = ((after.tokensIn + after.tokensOut) - tokMark).coerceAtLeast(0)
                // Falha do ciclo: exceção, watchdog ou qualquer erro registrado durante este ciclo.
                val cycleHadError = watchdogTimedOut || cycleFailed || after.items.drop(itemsMark).any { it is ChatItem.Error }
                var quiet = false
                if (watchdogTimedOut || cycleFailed) {
                    store.editSession(sid) { it.copy(status = SessionStatus.ERROR, continuous = false) }
                } else {
                    val lastItem = after.items.lastOrNull { it is ChatItem.Assistant } as? ChatItem.Assistant
                    val rawLast = lastItem?.text?.trim().orEmpty()
                    // remove qualquer NO_REPLY do texto exibido (nunca mostrar cru)
                    val cleaned = rawLast.replace(Regex("(?i)\\bNO_REPLY\\b"), "").trim()
                    quiet = rawLast.contains("NO_REPLY", true) && cleaned.length < 12
                    if (quiet) {
                        // ciclo sem novidade: não gasta contexto; colapsa marcadores repetidos num só.
                        store.editSession(sid) {
                            var items = it.items.take(itemsMark)
                            if ((items.lastOrNull() as? ChatItem.Step)?.tool == "cycle") items = items.dropLast(1)
                            it.copy(llm = it.llm.take(llmMark),
                                items = items + ChatItem.Step(tool = "cycle", title = "Ciclo $stamp — nada novo", args = "", result = "", status = StepStatus.OK),
                                status = SessionStatus.IDLE)
                        }
                    } else {
                        // trabalhou: limpa o NO_REPLY do texto exibido e mantém o resto visível
                        if (lastItem != null && cleaned != rawLast) store.replaceItem(sid, lastItem.copy(text = cleaned.ifBlank { rawLast }))
                        store.editSession(sid) { it.copy(status = SessionStatus.IDLE) }
                        if (a.notifyOnAction) notifyAgent(a, plainText(cleaned).take(220))
                    }
                }
                store.agent(a.id)?.let { cur ->
                    val day = today()
                    val successfulAction = !watchdogTimedOut && !cycleFailed && !quiet
                    val counted = cur.copy(beats = cur.beats + 1, actions = cur.actions + if (successfulAction) 1 else 0,
                        tokensDay = day, tokensToday = (if (cur.tokensDay == day) cur.tokensToday else 0) + used)
                    val (updated, fatigued) = dev.fluwy.app.device.AgentAutonomyPolicy.recordCycle(counted, cycleHadError)
                    store.upsertAgent(updated)
                    if (fatigued) {
                        dev.fluwy.app.scheduler.Heartbeat.cancel(ctx, cur.id)
                        val msg = dev.fluwy.app.prompts.Prompts.render("heartbeat.4", mapOf<String, Any?>("max_consecutive_failures" to (dev.fluwy.app.device.AgentAutonomyPolicy.MAX_CONSECUTIVE_FAILURES), "lb" to "{{"))
                        runCatching { dev.fluwy.app.diag.ErrorLog.log("heartbeat", msg, sid, "heartbeat") }
                        store.addItem(sid, ChatItem.Error(text = msg))
                        notifyAgent(updated, msg)
                    }
                }
                // Em falha, mantenha o lastBeatAt do início do ciclo para permitir recuperação após reinício.
                if (!watchdogTimedOut && !cycleFailed) {
                    store.agent(a.id)?.takeIf { it.autonomous }?.let { current ->
                        store.upsertAgent(current.copy(lastBeatAt = System.currentTimeMillis()))
                    }
                }
            }
            setActivity(sid, Activity())
            jobs.remove(sid, coroutineContext[Job])
            store.flush()
        }
    }
    jobs[sid] = job
    return job
}
