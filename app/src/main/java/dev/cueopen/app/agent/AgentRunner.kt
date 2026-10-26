package dev.cueopen.app.agent

import dev.cueopen.app.browser.BrowserHost
import dev.cueopen.app.data.AgentProfile
import dev.cueopen.app.data.ChatItem
import dev.cueopen.app.data.KnowledgeItem
import dev.cueopen.app.data.LlmMessage
import dev.cueopen.app.data.PlanPhase
import dev.cueopen.app.data.Session
import dev.cueopen.app.data.SessionStatus
import dev.cueopen.app.data.Skill
import dev.cueopen.app.data.StepStatus
import dev.cueopen.app.data.Store
import dev.cueopen.app.llm.OpenRouter
import dev.cueopen.app.llm.ToolCall
import dev.cueopen.app.llm.bool
import dev.cueopen.app.llm.int
import dev.cueopen.app.llm.str
import dev.cueopen.app.llm.strArray
import dev.cueopen.app.llm.tool
import dev.cueopen.app.sandbox.Sandbox
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
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** O que o "computador" do agente está fazendo agora (mostrado no card ao vivo do chat). */
data class Activity(val kind: Kind = Kind.IDLE, val detail: String = "") {
    enum class Kind { IDLE, THINKING, TERMINAL, BROWSER, EDITOR, SEARCH }
}

/**
 * Loop do agente estilo Manus/Cue: LLM com function calling + ferramentas que agem no sandbox
 * Linux local (shell/arquivos) e no WebView (browser). Roda até o modelo responder sem tool calls,
 * chamar `finish`, pedir input (`ask_user`) ou estourar o limite de passos.
 */
class AgentRunner(
    private val store: Store,
    private val sandbox: Sandbox,
    private val browserFor: (String) -> BrowserHost,
    private val llm: OpenRouter,
    private val terminals: dev.cueopen.app.sandbox.TerminalManager,
    private val ctx: android.content.Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Registro explícito do ciclo de vida de cada chamada de ferramenta. */
    val executions = ToolExecutionManager()
    private val jobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val childJobsByParent = mutableMapOf<String, MutableList<Job>>()
    private val _activity = MutableStateFlow<Map<String, Activity>>(emptyMap())
    val activity: StateFlow<Map<String, Activity>> = _activity
    /** Saída bruta dos comandos do agente por sessão (aba "Terminal do agente"). */
    private val _termLog = MutableStateFlow<Map<String, String>>(emptyMap())
    val termLog: StateFlow<Map<String, String>> = _termLog
    private val json = Json { ignoreUnknownKeys = true }

    fun isRunning(sessionId: String) = jobs[sessionId]?.isActive == true

    fun stop(sessionId: String) {
        executions.cancelSession(sessionId)
        // Primeiro envia Ctrl+C ao PTY ativo; cancelar só a coroutine deixava o processo vivo.
        terminals.get(sessionId).cancelAgentCommand()
        scope.launch { runCatching { browserFor(sessionId).cancelNavigation() } }
        jobs.remove(sessionId)?.cancel()
        synchronized(childJobsByParent) { childJobsByParent.remove(sessionId)?.forEach { it.cancel() } }
        store.state.value.agents.filter { it.homeSessionId == sessionId && it.autonomous }.forEach {
            store.upsertAgent(it.copy(autonomous = false)); dev.cueopen.app.scheduler.Heartbeat.cancel(ctx, it.id)
        }
        finalizeRunningSteps(sessionId, "Execução interrompida pelo usuário.")
        store.editSession(sessionId) { it.copy(status = SessionStatus.STOPPED, wakeAt = null) }
        setActivity(sessionId, Activity())
        browserFor(sessionId).setAgentControl(false)
    }

    /** Envia uma mensagem do usuário e (re)inicia o loop. */
    fun send(sessionId: String, text: String, attachments: List<String> = emptyList(), onDone: (() -> Unit)? = null): Job {
        jobs[sessionId]?.cancel()
        synchronized(childJobsByParent) { childJobsByParent.remove(sessionId)?.forEach { it.cancel() } }
        store.addItem(sessionId, ChatItem.User(text = text, attachments = attachments))
        var content = if (attachments.isEmpty()) text
        else text + "\n\n[Arquivos anexados no workspace: ${attachments.joinToString()}]"
        // @Skill → injeta as instruções só desta skill (divulgação progressiva); @Agent → troca o agente da tarefa
        val st0 = store.state.value
        st0.skills.filter { sk -> text.contains("@" + sk.name, ignoreCase = true) }.forEach { sk ->
            content += "\n\n[Skill solicitada pelo usuário: ${sk.name}]\n${sk.instructions}"
        }
        st0.agents.firstOrNull { a -> text.contains("@" + a.name, ignoreCase = true) }?.let { a ->
            store.editSession(sessionId) { it.copy(agentId = a.id) }
        }
        store.editSession(sessionId) { it.copy(llm = it.llm + LlmMessage("user", content), status = SessionStatus.RUNNING) }
        dev.cueopen.app.sandbox.SandboxService.start(ctx, "Executando tarefa…")
        val job = scope.launch {
            try {
                maybeTitle(sessionId, text)
                loop(sessionId)
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                store.addItem(sessionId, ChatItem.Error(text = t.message ?: t.toString()))
                store.editSession(sessionId) { it.copy(status = SessionStatus.ERROR) }
            } finally {
                // Só o job que ainda pertence a esta sessão pode reconciliar os passos dela.
                // Um job antigo cancelado não deve encerrar as pílulas de uma nova mensagem.
                if (jobs[sessionId] === coroutineContext[Job]) {
                    finalizeRunningSteps(sessionId, "Execução encerrada sem retorno da ferramenta.")
                    setActivity(sessionId, Activity())
                }
                browserFor(sessionId).setAgentControl(false)
                jobs.remove(sessionId, coroutineContext[Job])
                if (jobs.values.none { it.isActive }) dev.cueopen.app.sandbox.SandboxService.start(ctx, "Terminal ativo")
                onDone?.invoke()
            }
        }
        jobs[sessionId] = job
        return job
    }

    private suspend fun maybeTitle(sessionId: String, text: String) {
        val s = store.session(sessionId) ?: return
        if (s.title != "Nova tarefa") return
        val fallback = text.lineSequence().first().take(48)
        store.editSession(sessionId) { it.copy(title = fallback) }
        runCatching {
            val t = llm.quick("Crie um título curto (máx 6 palavras, sem aspas, no idioma do pedido) para esta tarefa:\n$text")
            val clean = t.replace(Regex("^(t[ií]tulo[^:]*:)\\s*", RegexOption.IGNORE_CASE), "").trim().removeSurrounding("\"").removeSurrounding("*").take(60)
            if (clean.isNotBlank()) store.editSession(sessionId) { it.copy(title = clean) }
        }
    }

    private val ctxMgr = ContextManager(store, sandbox, llm)

    private suspend fun loop(sessionId: String, maxStepsOverride: Int? = null, tokenBudgetOverride: Long? = null) {
        val maxSteps = (maxStepsOverride ?: store.settings.maxSteps).coerceIn(1, store.settings.maxSteps.coerceAtLeast(1))
        val startingTokens = store.session(sessionId)?.let { it.tokensIn + it.tokensOut } ?: 0L
        var steps = 0
        var repeats = 0
        var lastContent = ""
        while (scope.isActive) {
            val workerUsed = if (tokenBudgetOverride != null) {
                ((store.session(sessionId)?.let { it.tokensIn + it.tokensOut } ?: startingTokens) - startingTokens).coerceAtLeast(0)
            } else 0L
            if (tokenBudgetOverride != null && workerUsed >= tokenBudgetOverride - 128L) {
                // Finalização segura: não faz outra chamada que possa ultrapassar o orçamento.
                val partial = store.session(sessionId)?.items?.lastOrNull { it is ChatItem.Assistant } as? ChatItem.Assistant
                if (partial == null || partial.text.isBlank()) store.addItem(sessionId, ChatItem.Assistant(
                    text = "Progresso parcial preservado nos arquivos e nas etapas concluídas. Encerrando antes do limite de tokens para evitar estouro.",
                    final = true
                ))
                store.editSession(sessionId) { it.copy(status = SessionStatus.DONE) }
                return
            }
            var s = store.session(sessionId) ?: return
            val agent = store.agent(s.agentId)
            setActivity(sessionId, Activity(Activity.Kind.THINKING, "Pensando"))
            if (s.autoCompact && ctxMgr.estimateTokens(s.llm) + ctxMgr.estimateTokens(s.summary) >= store.settings.contextBudget) {
                setActivity(sessionId, Activity(Activity.Kind.THINKING, "Compactando contexto"))
                s = ctxMgr.compactIfNeeded(s)
            }
            val sys = LlmMessage("system", systemPrompt(sessionId, agent, s))
            var history = ctxMgr.microCompact(s.llm)
            if (history.firstOrNull()?.role != "user") history = listOf(LlmMessage("user", "[Contexto anterior compactado no resumo da sessão. Continue de onde parou.]")) + history
            // Resiliência: tenta a chamada; em erro, reenvia (backoff) e só desiste após 3 falhas.
            val replyTokenLimit = tokenBudgetOverride?.let { budget ->
                val used = ((store.session(sessionId)?.let { it.tokensIn + it.tokensOut } ?: startingTokens) - startingTokens).coerceAtLeast(0)
                val remaining = budget - used
                val estimatedInput = ctxMgr.estimateTokens(listOf(sys) + history).toLong()
                // Reservamos espaço para a entrada estimada e margem de segurança; nunca forçamos 256 tokens
                // quando o orçamento restante é menor do que isso.
                val safeOutput = remaining - estimatedInput - 128L
                if (safeOutput < 128L) {
                    store.editSession(sessionId) { it.copy(status = SessionStatus.DONE) }
                    return
                }
                safeOutput.coerceAtMost(2048L).toInt()
            } ?: 16000
            val reply = run {
                var attempt = 0
                while (true) {
                    try {
                        return@run llm.chat(listOf(sys) + history, tools = tools(agent, allowMultiAgent = tokenBudgetOverride == null), model = agent?.model, maxTokens = replyTokenLimit)
                    } catch (c: kotlinx.coroutines.CancellationException) {
                        throw c
                    } catch (t: Throwable) {
                        attempt++
                        if (attempt >= 3) {
                            store.addItem(sessionId, ChatItem.Error(text = "O modelo falhou (${t.message?.take(120)}). Tentei 3 vezes. Toque em enviar para continuar, ou troque de modelo em Configurações."))
                            store.editSession(sessionId) { it.copy(status = SessionStatus.WAITING_USER) }
                            return
                        }
                        setActivity(sessionId, Activity(Activity.Kind.THINKING, "Erro no modelo — tentando de novo ($attempt/3)"))
                        kotlinx.coroutines.delay(1500L * attempt)
                    }
                }
                @Suppress("UNREACHABLE_CODE") return
            }
            val modelId = agent?.model?.takeIf { it.isNotBlank() } ?: store.settings.model
            val callCostUsd = runCatching { llm.estimateCostUsd(modelId, reply.usage) }.getOrNull()
            store.editSession(sessionId) {
                it.copy(llm = it.llm + LlmMessage("assistant", reply.content, toolCallsJson = reply.rawToolCallsJson),
                    tokensIn = it.tokensIn + reply.usage.prompt, tokensOut = it.tokensOut + reply.usage.completion,
                    tokensCached = it.tokensCached + reply.usage.cached, llmCalls = it.llmCalls + 1,
                    modelUsed = modelId,
                    costUsd = it.costUsd + (callCostUsd?.takeIf { cost -> cost > 0.0 } ?: 0.0),
                    hasPricedUsage = it.hasPricedUsage || (callCostUsd != null && callCostUsd > 0.0))
            }
            // Anti-repetição: se o modelo repetir quase o mesmo texto várias vezes, interrompe.
            val cNow = reply.content?.trim().orEmpty()
            if (cNow.length > 40 && similar(cNow, lastContent)) {
                if (++repeats >= 2) {
                    store.addItem(sessionId, ChatItem.Error(text = "O modelo entrou em repetição. Interrompi para não gastar tokens à toa. Reformule o pedido ou troque de modelo."))
                    store.editSession(sessionId) { it.copy(status = SessionStatus.WAITING_USER) }
                    return
                }
            } else repeats = 0
            lastContent = cNow
            if (!reply.content.isNullOrBlank()) {
                store.addItem(sessionId, ChatItem.Assistant(text = reply.content.trim(), final = reply.toolCalls.isEmpty()))
            }
            if (reply.toolCalls.isEmpty()) {
                if (store.session(sessionId)?.continuous == true) {
                    // modo contínuo: sem ferramenta = lembra de dormir até a próxima verificação
                    store.editSession(sessionId) { it.copy(llm = it.llm + LlmMessage("user", "[Sistema] Você está em modo contínuo. Use `sleep` para aguardar a próxima verificação ou `finish` se o usuário pediu para encerrar.")) }
                    if (++steps >= maxSteps) { sleepAndWake(sessionId, 5, "verificação periódica"); steps = 0 }
                    continue
                }
                store.editSession(sessionId) { it.copy(status = SessionStatus.DONE) }
                if (tokenBudgetOverride == null) store.session(sessionId)?.let { done -> scope.launch { ctxMgr.extractMemories(done) } }
                return
            }
            var stopLoop: SessionStatus? = null
            var sleepReq: Pair<Int, String>? = null
            for (call in reply.toolCalls) {
                val result = runTool(sessionId, call)
                val ctxOut = ctxMgr.storeToolOutput(sessionId, call.id, result.output)
                store.editSession(sessionId) {
                    it.copy(llm = it.llm + LlmMessage("tool", ctxOut, toolCallId = call.id, name = call.name))
                }
                if (result.stop != null) stopLoop = result.stop
                if (result.sleepMinutes != null) sleepReq = result.sleepMinutes to result.output
            }
            val finalStatus = stopLoop
            if (finalStatus != null) {
                store.editSession(sessionId) { it.copy(status = finalStatus, continuous = if (finalStatus == SessionStatus.DONE) false else it.continuous) }
                if (finalStatus == SessionStatus.DONE && tokenBudgetOverride == null) store.session(sessionId)?.let { done -> scope.launch { ctxMgr.extractMemories(done) } }
                return
            }
            val sl = sleepReq
            if (sl != null) { sleepAndWake(sessionId, sl.first, sl.second); steps = 0; continue }
            if (++steps >= maxSteps) {
                if (store.session(sessionId)?.continuous == true) { sleepAndWake(sessionId, 1, "pausa automática após $maxSteps passos"); steps = 0; continue }
                store.addItem(sessionId, ChatItem.Error(text = "Limite de $maxSteps passos atingido. Envie \"continue\" para seguir."))
                store.editSession(sessionId) { it.copy(status = SessionStatus.WAITING_USER) }
                return
            }
        }
    }

    /** Modo contínuo: dorme (o serviço em foreground + wakelock mantém o processo vivo) e retoma. */
    private suspend fun sleepAndWake(sessionId: String, minutes: Int, reason: String) {
        val wake = System.currentTimeMillis() + minutes * 60_000L
        store.editSession(sessionId) { it.copy(status = SessionStatus.SLEEPING, wakeAt = wake, wakeReason = reason, continuous = true) }
        setActivity(sessionId, Activity(Activity.Kind.IDLE, "Aguardando até ${java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(wake))}"))
        dev.cueopen.app.sandbox.SandboxService.start(ctx, "Agente em modo contínuo")
        store.flush()
        while (System.currentTimeMillis() < wake) kotlinx.coroutines.delay(minOf(30_000L, wake - System.currentTimeMillis()).coerceAtLeast(1000))
        wakeUp(sessionId)
    }

    private fun wakeUp(sessionId: String) {
        val r = store.session(sessionId)?.wakeReason.orEmpty()
        val now = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date())
        store.editSession(sessionId) {
            it.copy(status = SessionStatus.RUNNING, wakeAt = null,
                llm = it.llm + LlmMessage("user", "[Sistema $now] Hora de continuar (motivo do sleep: $r). Faça a próxima verificação/etapa."))
        }
    }

    // ===================== AUTONOMIA 24/7 (heartbeat) =====================
    private var autonomyJob: Job? = null

    /**
     * Autonomia com DOIS mecanismos para nunca falhar:
     *  (1) ticker em processo a cada 20s — dispara no horário certo enquanto o app/serviço vive
     *      (precisão de segundos, ideal para intervalos curtos como 2 min);
     *  (2) AlarmManager — backup que reacorda o app mesmo depois de morto/boot.
     */
    fun startAutonomy() {
        dev.cueopen.app.scheduler.Heartbeat.rescheduleAll(ctx)
        if (store.state.value.agents.any { it.autonomous }) dev.cueopen.app.sandbox.SandboxService.start(ctx, "Agentes autônomos ativos")
        if (autonomyJob?.isActive == true) return
        autonomyJob = scope.launch {
            while (isActive) {
                runCatching { autonomyTick() }.onFailure { t ->
                    android.util.Log.e("CueOpen.Autonomy", "Falha no scheduler local", t)
                }
                // Intervalos de 1 minuto precisam de margem; verificar a cada 5 s evita atraso
                // adicional causado pelo ticker de 20 s.
                kotlinx.coroutines.delay(5_000)
            }
        }
    }

    private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    /** Semelhança grosseira entre dois textos (para detectar repetição/degeneração do modelo). */
    private fun similar(a: String, b: String): Boolean {
        if (b.isEmpty()) return false
        if (a == b) return true
        val wa = a.lowercase().split(Regex("\\s+")).toSet()
        val wb = b.lowercase().split(Regex("\\s+")).toSet()
        if (wa.isEmpty() || wb.isEmpty()) return false
        val inter = wa.intersect(wb).size.toDouble()
        return inter / maxOf(wa.size, wb.size) > 0.8
    }

    private fun autonomyTick() {
        val now = System.currentTimeMillis()
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        store.state.value.agents.filter { it.autonomous && it.homeSessionId != null }.forEach { a ->
            val inHours = if (a.activeStartHour <= a.activeEndHour) hour in a.activeStartHour until a.activeEndHour
                          else hour >= a.activeStartHour || hour < a.activeEndHour
            val spent = if (a.tokensDay == today()) a.tokensToday else 0
            val due = now >= (a.lastBeatAt ?: 0) + a.heartbeatMinutes * 60_000L
            val home = a.homeSessionId!!
            if (inHours && due && spent < a.dailyTokenBudget && jobs[home]?.isActive != true) heartbeat(a)
        }
    }

    /**
     * Dispara um ciclo autônomo. Só roda se o agente JÁ tem uma sessão (homeSessionId) iniciada pelo
     * usuário via set_schedule numa conversa — nunca cria sessão sozinho (evita auto-início ao criar).
     */
    fun heartbeat(agent: AgentProfile): Job {
        var a = store.agent(agent.id) ?: agent
        if (!a.autonomous || a.homeSessionId == null) return scope.launch { }
        val requestedHome = a.homeSessionId!!
        // AlarmManager e ticker podem disparar juntos. Uma sessão só pode ter um ciclo ativo.
        jobs[requestedHome]?.takeIf { it.isActive }?.let { return it }
        val home = a.homeSessionId?.let { store.session(it) } ?: Session(title = "🤖 ${a.name} · autônomo", agentId = a.id).also {
            store.upsertSession(it); a = a.copy(homeSessionId = it.id); store.upsertAgent(a)
        }
        store.upsertAgent(a.copy(lastBeatAt = System.currentTimeMillis()))
        val sid = home.id
        val before = store.session(sid)!!
        val llmMark = before.llm.size; val itemsMark = before.items.size
        val tokMark = before.tokensIn + before.tokensOut
        val stamp = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date())
        store.editSession(sid) {
            it.copy(status = SessionStatus.RUNNING, continuous = false,
                llm = it.llm + LlmMessage("user", "[Heartbeat $stamp] Verifique sua missão e faça o que for necessário agora. Se nada precisar ser feito, responda NO_REPLY."))
        }
        dev.cueopen.app.sandbox.SandboxService.start(ctx, "${a.name}: heartbeat")
        val job = scope.launch {
            try {
                loop(sid)
            } catch (c: kotlinx.coroutines.CancellationException) { throw c
            } catch (t: Throwable) {
                store.addItem(sid, ChatItem.Error(text = "Heartbeat falhou: ${t.message}"))
            } finally {
                val after = store.session(sid)
                if (after != null) {
                    val lastItem = after.items.lastOrNull { it is ChatItem.Assistant } as? ChatItem.Assistant
                    val rawLast = lastItem?.text?.trim().orEmpty()
                    // remove qualquer NO_REPLY do texto exibido (nunca mostrar cru)
                    val cleaned = rawLast.replace(Regex("(?i)\\bNO_REPLY\\b"), "").trim()
                    val quiet = rawLast.contains("NO_REPLY", true) && cleaned.length < 12
                    val used = (after.tokensIn + after.tokensOut) - tokMark
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
                    store.agent(a.id)?.let { cur ->
                        val day = today()
                        store.upsertAgent(cur.copy(beats = cur.beats + 1, actions = cur.actions + if (quiet) 0 else 1,
                            tokensDay = day, tokensToday = (if (cur.tokensDay == day) cur.tokensToday else 0) + used))
                    }
                }
                store.agent(a.id)?.takeIf { it.autonomous }?.let { current ->
                    store.upsertAgent(current.copy(lastBeatAt = System.currentTimeMillis()))
                }
                setActivity(sid, Activity())
                jobs.remove(sid, coroutineContext[Job])
                store.flush()
            }
        }
        jobs[sid] = job
        return job
    }

    /** Remove marcações Markdown para mostrar em notificação (texto puro). */
    private fun plainText(md: String): String = md
        .replace(Regex("```[\\s\\S]*?```"), " ")
        .replace(Regex("[*_`#>]"), "")
        .replace(Regex("\\[(.*?)\\]\\(.*?\\)"), "$1")
        .replace(Regex("\\s+"), " ").trim()

    private fun notifyRaw(title: String, text: String) = runCatching {
        val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
        nm.notify((System.nanoTime() and 0xffffff).toInt(), android.app.Notification.Builder(ctx, dev.cueopen.app.CueApp.CH_TASKS)
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(text)
            .setStyle(android.app.Notification.BigTextStyle().bigText(plainText(text))).setContentText(plainText(text).take(120)).setAutoCancel(true).build())
    }

    private fun notifyAgent(a: AgentProfile, text: String) = runCatching {
        val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
        val pi = android.app.PendingIntent.getActivity(ctx, a.id.hashCode(),
            android.content.Intent(ctx, dev.cueopen.app.MainActivity::class.java).putExtra("session", a.homeSessionId),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(a.id.hashCode(), android.app.Notification.Builder(ctx, dev.cueopen.app.CueApp.CH_TASKS)
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(a.name).setContentText(text.ifBlank { "Concluiu uma ação" })
            .setStyle(android.app.Notification.BigTextStyle().bigText(plainText(text))).setContentText(plainText(text).take(120)).setContentIntent(pi).setAutoCancel(true).build())
    }

    // ===================== COMANDOS DO COMPOSER =====================
    /** Compacta o contexto da sessão agora (/compact). */
    suspend fun compactNow(sessionId: String): String {
        val s = store.session(sessionId) ?: return "Sessão não encontrada"
        val before = ctxMgr.estimateTokens(s.llm)
        val after = ctxMgr.compactIfNeeded(s, force = true)
        val now = ctxMgr.estimateTokens(after.llm) + ctxMgr.estimateTokens(after.summary)
        return "Contexto compactado: ~$before → ~$now tokens."
    }

    /** Pausa a autonomia de um agente: cancela alarme/ciclos. Só volta quando o usuário reativar. */
    fun pauseAgent(agentId: String) {
        store.agent(agentId)?.let {
            store.upsertAgent(it.copy(autonomous = false))
            dev.cueopen.app.scheduler.Heartbeat.cancel(ctx, agentId)
            it.homeSessionId?.let { sid -> jobs.remove(sid)?.cancel(); store.editSession(sid) { s -> s.copy(status = SessionStatus.STOPPED) } }
        }
    }

    fun connectorTools(): List<JsonObject> = dev.cueopen.app.connectors.Connectors.toolDefinitions(store)

    /**
     * Cria e configura um Agent completo a partir de uma descrição em linguagem natural (@create-agent).
     * Usa o modelo rápido para gerar nome, personalidade, instruções, missão, autonomia e skills sugeridas.
     */
    suspend fun createAgentFromPrompt(desc: String): AgentProfile {
        val skillNames = store.state.value.skills.filter { it.enabled }.joinToString(", ") { it.name }
        val raw = runCatching {
            llm.quick("""
                Gere a configuração de um agente de IA a partir do pedido do usuário. Responda SOMENTE com JSON válido:
                {"name":"curto, 2-3 palavras","face":"wink|smile|side|dot","color":0-7,
                 "instructions":"instruções detalhadas de como o agente age, tom, objetivos (2-5 frases)",
                 "mission":"missão permanente se for para rodar continuamente, senão vazio",
                 "autonomous":true|false, "heartbeatMinutes":0,
                 "useTerminal":true|false, "useBrowser":true|false,
                 "skills":["nomes exatos entre: $skillNames"]}
                Se o usuário pedir monitoramento/execução recorrente, defina autonomous=true e heartbeatMinutes com o intervalo pedido (em minutos).
                Pedido: $desc
            """.trimIndent(), maxTokens = 600)
        }.getOrDefault("")
        val js = raw.substringAfter('{', "").let { if (it.isNotEmpty()) "{$it".substringBeforeLast('}') + "}" else "" }
        val o = runCatching { json.parseToJsonElement(js).jsonObject }.getOrNull()
        fun str(k: String, d: String = "") = o?.get(k)?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() } ?: d
        fun bool(k: String, d: Boolean) = o?.get(k)?.let { runCatching { it.jsonPrimitive.booleanOrNull }.getOrNull() } ?: d
        fun intf(k: String, d: Int) = o?.get(k)?.let { runCatching { it.jsonPrimitive.content.toDouble().toInt() }.getOrNull() } ?: d
        val colors = listOf(0xFFFF6B3DL, 0xFF4D8DFFL, 0xFF2FBF71L, 0xFFFFC53DL, 0xFFB57BFFL, 0xFFFF5C8AL, 0xFF2EC4C4L, 0xFF9C978EL)
        val wantSkills = o?.get("skills")?.let { runCatching { it.jsonArray.map { e -> e.jsonPrimitive.content } }.getOrNull() }.orEmpty()
        val skillIds = store.state.value.skills.filter { sk -> wantSkills.any { it.equals(sk.name, true) } }.map { it.id }
        val name = str("name", desc.take(24)).ifBlank { "Novo Agente" }
        val hb = intf("heartbeatMinutes", 0)
        val agent = AgentProfile(
            name = name, face = str("face", "wink").ifBlank { "wink" },
            color = colors.getOrElse(intf("color", 0).coerceIn(0, 7)) { 0xFFFF6B3DL },
            instructions = str("instructions", desc), mission = str("mission"),
            autonomous = false, heartbeatMinutes = if (hb in 1..1440) hb else 30,  // criar NUNCA inicia sozinho; liga via conversa
            useTerminal = bool("useTerminal", true), useBrowser = bool("useBrowser", true),
            skillIds = skillIds,
        )
        store.upsertAgent(agent)
        return agent
    }

    /** Ao reabrir o app (ou o serviço reiniciar o processo), retoma agentes contínuos. */
    fun resumeSleeping() {
        store.state.value.sessions.filter { it.status == SessionStatus.SLEEPING || (it.continuous && it.status == SessionStatus.STOPPED && it.wakeAt != null) }.forEach { s ->
            if (jobs[s.id]?.isActive == true) return@forEach
            val wake = s.wakeAt ?: System.currentTimeMillis()
            dev.cueopen.app.sandbox.SandboxService.start(ctx, "Agente em modo contínuo")
            jobs[s.id] = scope.launch {
                try {
                    while (System.currentTimeMillis() < wake) kotlinx.coroutines.delay(minOf(30_000L, wake - System.currentTimeMillis()).coerceAtLeast(1000))
                    wakeUp(s.id); loop(s.id)
                } catch (c: kotlinx.coroutines.CancellationException) { throw c
                } catch (t: Throwable) {
                    store.addItem(s.id, ChatItem.Error(text = t.message ?: t.toString()))
                    store.editSession(s.id) { it.copy(status = SessionStatus.ERROR) }
                } finally { setActivity(s.id, Activity()) }
            }
        }
    }

    private fun systemPrompt(sessionId: String, agent: AgentProfile?, session: Session): String {
        val st = store.state.value
        val skills: List<Skill> = st.skills.filter { sk ->
            sk.enabled && (agent == null || agent.skillIds.isEmpty() || sk.id in agent.skillIds)
        }
        val lastUser = session.llm.lastOrNull { it.role == "user" }?.content.orEmpty()
        val query = lastUser + " " + session.summary.take(1500)
        val memories = ctxMgr.relevantMemories(query, agent?.id).also { ctxMgr.touch(it) }
        val knowledge = ctxMgr.relevantKnowledge(query)
        val notes = ctxMgr.readNotes(sessionId)
        val date = SimpleDateFormat("EEEE, d 'de' MMMM 'de' yyyy", Locale("pt", "BR")).format(Date())
        return buildString {
            // ---- parte estável (favorece cache de prompt) ----
            appendLine("Você é ${agent?.name ?: "Cue"}, um agente autônomo de uso geral rodando no app CueOpen (Android).")
            appendLine("Data: $date. Usuário: ${st.settings.userName.ifBlank { "não informado" }}.")
            appendLine()
            appendLine("Você tem um COMPUTADOR próprio:")
            appendLine("- Terminal Linux (Alpine, via proot) — ferramenta `shell`. Workspace da tarefa: ${sandbox.guestPath(sessionId)}. Instale pacotes com `apk add`, `pip install`, `npm i`. O usuário vê cada comando ao vivo.")
            appendLine("- Browser real (WebView) — ferramentas browser_* (inclui browser_network e browser_console para depurar).")
            appendLine("- Arquivos — file_read / file_write / file_list.")
            appendLine()
            appendLine("Modo de trabalho:")
            appendLine("1. Tarefas com várias etapas: chame `update_plan` e atualize ao concluir fases.")
            appendLine("2. Aja com ferramentas em vez de descrever. Verifique resultados.")
            appendLine("3. Economize contexto: leia só o necessário (head/grep), mande saídas longas para arquivo.")
            appendLine("3a. Trabalhe APENAS no seu workspace (${sandbox.guestPath(sessionId)}); não leia /root/workspace de outras tarefas.")
            appendLine("3b. Arquivos: use file_write (nunca heredoc gigante no shell). Arquivos grandes em partes com append=true. Depois confira com `ls -la` ou file_read.")
            appendLine("3c. Ao gerar imagem/SVG/HTML/PDF, mostre ao usuário com `show_file`.")
            appendLine("3d. Com plano ativo, chame `complete_step` ao concluir CADA fase, antes de seguir para a próxima.")
            appendLine("3e. EFICIÊNCIA DE CONTEXTO: busque primeiro símbolos/termos e leia apenas trechos necessários; limite saídas; não despeje arquivos/logs inteiros no contexto. Preserve requisitos, decisões, evidências, caminhos, erros e pendências. Não alegue economia percentual sem medir uso real.")
            appendLine("3f. DELEGAÇÃO DE LEITURA: em investigações amplas com fontes independentes, use multi_agents com subtarefas delimitadas, escopo explícito e saída curta (conclusões + caminho/linhas/URLs + incertezas). Não delegue leituras triviais; valide evidências críticas na fonte principal. Se a ferramenta não estiver disponível, não afirme que delegou.")

            appendLine("4. Anote em `notes_write` o que precisa lembrar durante a tarefa (achados, progresso, próximos passos).")
            appendLine("5. Fatos duradouros sobre o usuário/projeto: `memory_save`. Para lembrar algo antigo: `memory_search`.")
            appendLine("6. Para usar uma skill, chame `skill_load` com o nome e siga as instruções.")
            appendLine("6b. AUTONOMIA: se o usuário pedir para monitorar/acompanhar/repetir algo ao longo do tempo (ex.: \"a cada 20 min verifique o mercado\"), chame set_schedule com o intervalo em minutos. Se ele não der intervalo mas a tarefa for contínua, escolha um intervalo razoável e econômico. Para parar, set_schedule minutes=0.")
            val caps = agent?.deviceCaps.orEmpty()
            if (caps.isNotEmpty()) appendLine("6c. Você tem acesso ao aparelho: " + caps.joinToString(", ") { dev.cueopen.app.device.DeviceCaps.all.firstOrNull { d -> d.id == it }?.title ?: it } + ". Use as ferramentas device_* quando fizer sentido.")
            appendLine("7. MODO CONTÍNUO: se o usuário pedir para monitorar, acompanhar, repetir ou rodar por horas/dias, NÃO finalize. Faça a verificação, registre em notes_write e chame `sleep` (minutos até a próxima verificação). Repita até o usuário pausar.")
            appendLine("8. Fora do modo contínuo, termine com `finish` (resumo curto + arquivos). `ask_user` só se for ambíguo ou irreversível. Responda no idioma do usuário.")
            if (!agent?.instructions.isNullOrBlank()) { appendLine(); appendLine("## Instruções do Agent"); appendLine(agent!!.instructions) }
            if (skills.isNotEmpty()) {
                appendLine(); appendLine("## Skills disponíveis (carregue com skill_load)")
                skills.forEach { appendLine("- ${it.name}: ${it.description.take(160)}") }
            }
            // ---- parte dinâmica ----
            val agentMem = agent?.memory.orEmpty()
            if (agentMem.isNotEmpty() || memories.isNotEmpty()) {
                appendLine(); appendLine("## Memória relevante")
                agentMem.takeLast(10).forEach { appendLine("- $it") }
                memories.forEach { appendLine("- ${it.text}") }
            }
            if (knowledge.isNotEmpty()) {
                appendLine(); appendLine("## Knowledge do usuário (relevante)")
                knowledge.forEach { appendLine("### ${it.title}\n${it.content.take(1500)}\n") }
            }
            if (session.summary.isNotBlank()) {
                appendLine(); appendLine("## Resumo da sessão até aqui (histórico antigo compactado)"); appendLine(session.summary.take(6000))
            }
            if (notes.isNotBlank()) {
                appendLine(); appendLine("## Suas anotações da tarefa (NOTES.md)"); appendLine(notes.takeLast(4000))
            }
            (session.items.lastOrNull { it is ChatItem.Plan } as? ChatItem.Plan)?.let { p ->
                appendLine(); appendLine("## Plano atual (marque cada fase com complete_step ao terminá-la)")
                p.phases.forEachIndexed { i, ph -> appendLine("${i + 1}. [${if (ph.done) "x" else " "}] ${ph.title}") }
            }
            if (agent != null && agent.autonomous && session.id == agent.homeSessionId) {
                appendLine(); appendLine("## MODO AUTÔNOMO 24/7")
                appendLine("Missão permanente: ${agent.mission.ifBlank { agent.instructions.take(400) }}")
                appendLine("Você é acordado a cada ${agent.heartbeatMinutes} min (heartbeat). Em cada heartbeat:")
                appendLine("1) leia suas anotações (NOTES.md acima) para saber o estado; 2) faça só o que for necessário AGORA;")
                appendLine("3) registre progresso/estado com notes_write (curto); 4) fatos duradouros com memory_save;")
                appendLine("5) sempre entregue uma atualização ÚTIL e NOVA, somando ao que já existe nas notas — nunca repita o que já foi dito. Só responda NO_REPLY (sozinho) se, de fato, não houver absolutamente nada novo e notificar de novo seria spam.")
                appendLine("6) SEMPRE avance a partir do que já existe (leia o workspace/NOTES) e melhore a entrega; NÃO refaça o que já está pronto.")
                appendLine("7) RESILIÊNCIA: se uma ferramenta falhar, leia o erro, ajuste a abordagem e tente outro caminho; nunca pare por causa de um erro — contorne e continue.")
                appendLine("Seja econômico: evite repetir pesquisas já anotadas. Nunca chame sleep nem finish no modo autônomo; apenas responda ao terminar.")
            }
            if (session.continuous) { appendLine(); appendLine("## Estado: MODO CONTÍNUO ATIVO — continue o ciclo trabalho → notes_write → sleep.") }
        }
    }

    private fun tools(agent: AgentProfile?, allowMultiAgent: Boolean = true) = buildJsonArray {
        if (allowMultiAgent) add(tool("multi_agents", "Executa todas as subtarefas em ondas com no máximo cinco workers simultâneos. Cada worker usa workspace isolado; resultados parciais não são integrados automaticamente e conflitos são preservados. tasks_json aceita strings independentes ou objetos {id, task, depends_on}; dependências são executadas em ondas. budget_tokens é por worker.", buildJsonObject {
            put("tasks_json", str("JSON array de strings independentes ou objetos {id, task, depends_on:[ids]}; dependências rodam em ondas e ciclos são rejeitados"))
            put("repo_path", str("Caminho opcional do repositório Git dentro do sandbox; padrão workspace atual"))
            put("max_workers", int("Quantidade de workers (1-5; padrão 3)"))
            put("budget_tokens", int("Orçamento por worker (2000-12000; padrão 6000)"))
        }, listOf("tasks_json")))
        val term = agent?.useTerminal != false
        val web = agent?.useBrowser != false && allowMultiAgent
        add(tool("update_plan", "Cria/atualiza o plano da tarefa (fases).", buildJsonObject {
            put("goal", str("Objetivo em uma frase"))
            put("phases", strArray("Fases em ordem"))
            put("done_count", int("Quantas fases já foram concluídas"))
        }, listOf("goal", "phases")))
        if (term) {
            add(tool("shell", "Executa um comando bash no sandbox Linux (Alpine). Retorna stdout+stderr. GUARDRAILS: antes de executar bash/python/node com caminho de script, confirme que o arquivo existe com file_list/file_read; nunca invente nomes .sh ou caminhos. Antes de cd, confirme que o diretório existe. Se uma execução anterior retornou ENOENT/No such file or directory, não repita o mesmo comando: inspecione o workspace e corrija o caminho.", buildJsonObject {
                put("command", str("Comando a executar"))
                put("timeout", int("Timeout em segundos (padrão 180)"))
                put("background", bool("Rodar em segundo plano (servidores)"))
            }, listOf("command")))
        }
        add(tool("file_write", "Cria/sobrescreve um arquivo de texto (cria as pastas). Arquivos grandes: escreva em partes com append=true (~150 linhas por chamada).", buildJsonObject {
            put("path", str("Caminho do ARQUIVO (relativo ao workspace ou absoluto)")); put("content", str("Conteúdo")); put("append", bool("true = acrescenta ao final"))
        }, listOf("path", "content")))
        add(tool("complete_step", "Marca a próxima fase do plano como concluída. Chame SEMPRE ao terminar cada fase.", buildJsonObject { put("note", str("O que foi feito")) }))
        add(tool("show_file", "Mostra um arquivo gerado (imagem, SVG, HTML, PDF, código) direto no chat para o usuário.", buildJsonObject {
            put("path", str("Caminho do arquivo")); put("caption", str("Legenda curta"))
        }, listOf("path")))
        add(tool("file_read", "Lê um arquivo de texto.", buildJsonObject { put("path", str("Caminho")) }, listOf("path")))
        add(tool("file_list", "Lista arquivos de um diretório.", buildJsonObject { put("path", str("Diretório (padrão: workspace)")) }))
        if (web) {
            add(tool("browser_navigate", "Abre uma URL (ou termos de busca) no browser.", buildJsonObject { put("url", str("URL ou busca")) }, listOf("url")))
            add(tool("browser_snapshot", "Gera snapshot semântico da página atual com headings, conteúdo, links e controles nomeados; refs/índices expiram após navegação.", buildJsonObject { put("max_chars", int("Limite de caracteres (padrão 9000)")) }))
            add(tool("browser_read", "Compatibilidade: lê o snapshot e o texto da página atual.", buildJsonObject {}))
            add(tool("browser_click", "Clica no elemento [índice] de browser_read.", buildJsonObject { put("index", int("Índice")) }, listOf("index")))
            add(tool("browser_type", "Digita num campo [índice]; submit=true envia.", buildJsonObject {
                put("index", int("Índice")); put("text", str("Texto")); put("submit", bool("Enviar formulário"))
            }, listOf("index", "text")))
            add(tool("browser_scroll", "Rola a página.", buildJsonObject { put("direction", str("up ou down")) }))
            add(tool("browser_back", "Volta uma página.", buildJsonObject {}))
            add(tool("browser_js", "Executa JavaScript na página e retorna o resultado.", buildJsonObject { put("code", str("Código JS (expressão)")) }, listOf("code")))
        }
        if (web) {
            add(tool("browser_network", "Inspeciona requisições da página: método, URL, status, tempo, headers e payloads fetch/XHR capturados (limitados).", buildJsonObject { put("filter", str("Filtro opcional por texto na URL")) }))
            add(tool("browser_console", "Mostra as mensagens recentes do console JavaScript da página.", buildJsonObject {}))
        }
        add(tool("notes_write", "Escreve no NOTES.md da tarefa (memória de trabalho que sempre volta ao seu contexto).", buildJsonObject {
            put("content", str("Texto")); put("append", bool("true = acrescenta; false = substitui tudo"))
        }, listOf("content")))
        if (allowMultiAgent) add(tool("memory_save", "Salva um fato duradouro na memória de longo prazo.", buildJsonObject {
            put("fact", str("Fato")); put("scope", str("global ou agent")); put("tags", strArray("Palavras-chave"))
        }, listOf("fact")))
        add(tool("memory_search", "Busca na memória de longo prazo.", buildJsonObject { put("query", str("Busca")) }, listOf("query")))
        add(tool("skill_load", "Carrega as instruções completas de uma skill.", buildJsonObject { put("name", str("Nome da skill")) }, listOf("name")))
        if (allowMultiAgent) add(tool("sleep", "Modo contínuo: pausa por N minutos e depois retoma automaticamente (monitoramento).", buildJsonObject {
            put("minutes", int("Minutos até a próxima verificação (1-1440)")); put("reason", str("O que fazer ao acordar"))
        }, listOf("minutes", "reason")))
        if (allowMultiAgent) add(tool("create_skill", "Cria uma nova skill na biblioteca do usuário.", buildJsonObject {
            put("name", str("Nome")); put("description", str("Descrição curta")); put("instructions", str("Instruções detalhadas"))
        }, listOf("name", "description", "instructions")))
        if (allowMultiAgent) add(tool("set_schedule", "Faça o AGENTE trabalhar de forma autônoma e recorrente. Chame quando o usuário pedir para monitorar/acompanhar/repetir algo ao longo do tempo. Defina minutes = intervalo entre verificações (ex.: 20). Use minutes=0 para parar a autonomia.", buildJsonObject {
            put("minutes", int("Minutos entre verificações; 0 para parar")); put("mission", str("O que verificar/fazer a cada ciclo"))
        }, listOf("minutes")))
        // Capacidades do dispositivo liberadas para este agente
        val caps = if (allowMultiAgent) agent?.deviceCaps.orEmpty() else emptyList()
        if (dev.cueopen.app.device.DeviceCaps.NOTIF_READ in caps)
            add(tool("device_notifications", "Lê as notificações recentes do aparelho (apps como WhatsApp, Gmail…).", buildJsonObject {
                put("filter", str("Filtrar por app ou texto (opcional)")); put("limit", int("Máximo (padrão 30)"))
            }))
        if (dev.cueopen.app.device.DeviceCaps.NOTIF_POST in caps)
            add(tool("device_notify", "Mostra uma notificação na barra de status do aparelho.", buildJsonObject {
                put("title", str("Título")); put("text", str("Mensagem"))
            }, listOf("text")))
        if (dev.cueopen.app.device.DeviceCaps.SCREEN in caps)
            add(tool("device_screenshot", "Captura a tela atual do aparelho e mostra no chat.", buildJsonObject {}))
        if (dev.cueopen.app.device.DeviceCaps.OVERLAY in caps)
            add(tool("device_overlay", "Mostra um balão flutuante sobre os outros apps.", buildJsonObject { put("text", str("Texto")) }, listOf("text")))
        if (allowMultiAgent) connectorTools().forEach { add(it) }
        if (allowMultiAgent) add(tool("ask_user", "Faz uma pergunta ao usuário e pausa a tarefa até ele responder.", buildJsonObject { put("question", str("Pergunta")) }, listOf("question")))
        add(tool("finish", "Conclui a tarefa com um resumo e a lista de entregáveis.", buildJsonObject {
            put("summary", str("Resumo do resultado")); put("files", strArray("Caminhos dos arquivos entregues"))
        }, listOf("summary")))
    }

    data class ToolResult(val output: String, val stop: SessionStatus? = null, val sleepMinutes: Int? = null)

    private suspend fun runTool(sessionId: String, call: ToolCall): ToolResult {
        val parsed = runCatching { json.parseToJsonElement(call.argsJson).jsonObject }
        if (parsed.isFailure && call.argsJson.isNotBlank() && call.argsJson != "{}") {
            store.addItem(sessionId, ChatItem.Step(tool = call.name, title = "Argumentos cortados", args = call.name, result = call.argsJson.takeLast(300), status = StepStatus.ERROR))
            return ToolResult("ERRO: os argumentos de ${call.name} chegaram incompletos (sua resposta foi cortada por tamanho). " +
                "Para arquivos grandes use file_write em partes: a 1ª com append=false e as seguintes com append=true, até ~150 linhas por chamada.")
        }
        val a: JsonObject = parsed.getOrDefault(JsonObject(emptyMap()))
        fun s(k: String) = a[k]?.jsonPrimitive?.contentOrNull.orEmpty()
        fun i(k: String) = a[k]?.jsonPrimitive?.intOrNull
        fun b(k: String) = a[k]?.jsonPrimitive?.booleanOrNull ?: false
        val cwd = sandbox.guestPath(sessionId)

        // Ferramentas "silenciosas" com card próprio
        when (call.name) {
            "update_plan" -> {
                val phases = a["phases"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
                val done = i("done_count") ?: 0
                val prev = (store.session(sessionId)?.items?.lastOrNull { it is ChatItem.Plan } as? ChatItem.Plan)?.phases.orEmpty()
                val plan = ChatItem.Plan(goal = s("goal"), phases = phases.mapIndexed { idx, t ->
                    PlanPhase(t, idx < done || prev.any { it.title == t && it.done }) })
                val existing = store.session(sessionId)?.items?.lastOrNull { it is ChatItem.Plan }
                if (existing != null) store.replaceItem(sessionId, plan.copy(id = existing.id)) else store.addItem(sessionId, plan)
                return ToolResult("Plano atualizado.")
            }
            "set_schedule" -> {
                val m = i("minutes") ?: 0
                val sess = store.session(sessionId); val ag = store.agent(sess?.agentId)
                if (ag == null) return ToolResult("Esta tarefa não tem um Agent. Peça ao usuário para criar/usar um Agent para rodar de forma autônoma.")
                if (m <= 0) {
                    store.upsertAgent(ag.copy(autonomous = false))
                    dev.cueopen.app.scheduler.Heartbeat.cancel(ctx, ag.id)
                    store.addItem(sessionId, ChatItem.Step(tool = "set_schedule", title = "Autonomia desligada", args = "", result = "", status = StepStatus.OK))
                    return ToolResult("Autonomia desligada para ${ag.name}.")
                }
                val mm = m.coerceIn(1, 1440)
                store.upsertAgent(ag.copy(autonomous = true, heartbeatMinutes = mm,
                    mission = s("mission").ifBlank { ag.mission }, homeSessionId = sessionId, lastBeatAt = System.currentTimeMillis()))
                dev.cueopen.app.scheduler.Heartbeat.schedule(ctx, ag.id, mm)
                store.addItem(sessionId, ChatItem.Step(tool = "set_schedule", title = "Agendado: a cada ${mm} min", args = s("mission"), result = "", status = StepStatus.OK))
                return ToolResult("Pronto: o Android vai me acordar sozinho a cada ${mm} min (mesmo com o app fechado) e eu farei a verificação. Nada de você precisar mandar mensagem. Pare com /stop ou desligando a autonomia no Agent.")
            }
            "sleep" -> {
                val m = (i("minutes") ?: 5).coerceIn(1, 1440)
                store.addItem(sessionId, ChatItem.Step(tool = "sleep", title = "Aguardando $m min", args = s("reason"), result = "", status = StepStatus.OK))
                return ToolResult(s("reason").ifBlank { "verificação" }, sleepMinutes = m)
            }
            "skill_load" -> {
                val sk = store.state.value.skills.firstOrNull { it.name.equals(s("name"), true) }
                    ?: store.state.value.skills.firstOrNull { it.name.contains(s("name"), true) }
                return ToolResult(sk?.let { "# ${it.name}\n${it.instructions}" } ?: "Skill não encontrada. Disponíveis: " + store.state.value.skills.joinToString { it.name })
            }
            "ask_user" -> {
                store.addItem(sessionId, ChatItem.Assistant(text = s("question"), final = true))
                return ToolResult("Pergunta enviada; aguardando resposta.", SessionStatus.WAITING_USER)
            }
            "complete_step" -> {
                val plan = store.session(sessionId)?.items?.lastOrNull { it is ChatItem.Plan } as? ChatItem.Plan
                    ?: return ToolResult("Não há plano. Use update_plan primeiro.")
                val idx = plan.phases.indexOfFirst { !it.done }
                if (idx < 0) return ToolResult("Todas as fases já estão concluídas.")
                store.replaceItem(sessionId, plan.copy(phases = plan.phases.mapIndexed { i, p -> if (i == idx) p.copy(done = true) else p }))
                val next = plan.phases.getOrNull(idx + 1)?.title
                return ToolResult("Fase ${idx + 1}/${plan.phases.size} concluída." + (next?.let { " Próxima: $it" } ?: " Plano completo."))
            }
            "show_file" -> {
                val f = sandbox.hostFile(s("path"), sessionId)
                if (!f.exists() || f.isDirectory) return ToolResult("ERRO: arquivo não encontrado: ${s("path")}")
                store.addItem(sessionId, ChatItem.Media(path = f.absolutePath, caption = s("caption")))
                return ToolResult("Arquivo exibido no chat.")
            }
            "finish" -> {
                (store.session(sessionId)?.items?.lastOrNull { it is ChatItem.Plan } as? ChatItem.Plan)?.let { p ->
                    store.replaceItem(sessionId, p.copy(phases = p.phases.map { it.copy(done = true) }))
                }
                val files = a["files"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
                val text = s("summary") + if (files.isNotEmpty()) "\n\n📎 " + files.joinToString("\n📎 ") else ""
                store.addItem(sessionId, ChatItem.Assistant(text = text, final = true))
                return ToolResult("ok", SessionStatus.DONE)
            }
        }

        val (title, kind) = when (call.name) {
            "shell" -> "Executando comando" to Activity.Kind.TERMINAL
            "file_write" -> "Editando arquivo" to Activity.Kind.EDITOR
            "file_read" -> "Lendo arquivo" to Activity.Kind.EDITOR
            "file_list" -> "Listando arquivos" to Activity.Kind.EDITOR
            "multi_agents" -> "Delegando subtarefas em paralelo" to Activity.Kind.THINKING
            "browser_navigate" -> "Navegando" to Activity.Kind.BROWSER
            "browser_read", "browser_snapshot" -> "Lendo snapshot da página" to Activity.Kind.BROWSER
            "browser_click" -> "Clicando" to Activity.Kind.BROWSER
            "browser_type" -> "Digitando" to Activity.Kind.BROWSER
            "browser_scroll" -> "Rolando página" to Activity.Kind.BROWSER
            "browser_back" -> "Voltando" to Activity.Kind.BROWSER
            "browser_js" -> "Executando script na página" to Activity.Kind.BROWSER
            "remember", "memory_save" -> "Salvando na memória" to Activity.Kind.THINKING
            "memory_search" -> "Buscando na memória" to Activity.Kind.THINKING
            "notes_write" -> "Anotando" to Activity.Kind.EDITOR
            "browser_network" -> "Inspecionando rede" to Activity.Kind.BROWSER
            "complete_step" -> "Concluindo fase" to Activity.Kind.THINKING
            "set_schedule" -> "Agendando autonomia" to Activity.Kind.THINKING
            "device_notifications" -> "Lendo notificações" to Activity.Kind.THINKING
            "device_screenshot" -> "Capturando tela" to Activity.Kind.THINKING
            "device_notify", "device_overlay" -> "Avisando no aparelho" to Activity.Kind.THINKING
            "browser_console" -> "Lendo console" to Activity.Kind.BROWSER
            "create_skill" -> "Criando skill" to Activity.Kind.THINKING
            else -> (if (call.name.startsWith("mcp_")) "Conector · " + call.name.substringAfter("__") else call.name) to Activity.Kind.THINKING
        }
        val argPreview = when (call.name) {
            "shell" -> s("command")
            "file_write", "file_read", "file_list" -> s("path").ifBlank { cwd }
            "browser_navigate" -> s("url")
            "browser_type" -> s("text")
            "browser_click" -> "#${i("index")}"
            "remember", "memory_save" -> s("fact")
            "memory_search" -> s("query")
            "notes_write" -> s("content").take(60)
            "create_skill" -> s("name")
            else -> ""
        }
        val step = ChatItem.Step(tool = call.name, title = title, args = argPreview)
        val executionId = executions.begin(sessionId, call.name, call.argsJson)
        store.addItem(sessionId, step)
        setActivity(sessionId, Activity(kind, argPreview.take(80)))
        if (kind == Activity.Kind.BROWSER) browserFor(sessionId).setAgentControl(true)

        val out: String = try {
            when (call.name) {
                "multi_agents" -> runMultiAgents(sessionId, s("tasks_json"), s("repo_path"), i("max_workers") ?: 3, i("budget_tokens") ?: 6000)
                "shell" -> {
                    // Guardrail determinístico: recusa invocações diretas de scripts inexistentes,
                    // em vez de mandar bash executar um caminho alucinado e produzir ENOENT.
                    val command = s("command").trim()
                    val directScript = Regex("""(?:^|[;&|]\\s*)(?:bash|sh|python3?|node)\\s+(['"]?)(/[^\\s'";&|]+)""")
                        .find(command)?.groupValues?.get(2)
                    val missingScript = directScript?.let { path ->
                        val f = sandbox.hostFile(path, sessionId)
                        if (!f.exists() || !f.isFile) path else null
                    }
                    val badCd = Regex("""(?:^|[;&|]\\s*)cd\\s+(['"]?)(/[^\\s'";&|]+)""")
                        .find(command)?.groupValues?.get(2)?.let { path ->
                            val f = sandbox.hostFile(path, sessionId)
                            if (!f.exists() || !f.isDirectory) path else null
                        }
                    if (missingScript != null) {
                        "GUARDRAIL: o script '$missingScript' não existe neste workspace. Não execute novamente. Use file_list/ls para descobrir o caminho real ou file_write para criar o arquivo antes de executá-lo."
                    } else if (badCd != null) {
                        "GUARDRAIL: o diretório '$badCd' não existe neste workspace. Liste o diretório pai e escolha um caminho existente."
                    } else {
                    // tudo roda DENTRO do terminal da tarefa (visível ao vivo no Computador)
                    val r = terminals.get(sessionId, cwd).runAgentCommand(command, (i("timeout") ?: 180).toLong(), b("background"))
                    run {
                        buildString {
                            append(r.output.takeLast(10_000))
                            if (r.timedOut) append("\n[timeout]")
                            append("\n[exit ${r.exitCode}]")
                        }
                    }
                    }
                }
                "file_write" -> {
                    val path = s("path").trim()
                    val f = sandbox.hostFile(path, sessionId)
                    when {
                        path.isBlank() -> "ERRO: informe path."
                        f.isDirectory -> "ERRO: $path é um diretório. Use um caminho de arquivo, ex.: $path/index.html"
                        else -> {
                            var p = f.parentFile
                            while (p != null && !p.exists()) p = p.parentFile
                            if (p != null && p.isFile) "ERRO: ${p.name} existe como arquivo e não pode ser usado como pasta."
                            else {
                                f.parentFile?.mkdirs()
                                val append = a["append"]?.jsonPrimitive?.booleanOrNull == true
                                if (append && f.exists()) f.appendText(s("content")) else f.writeText(s("content"))
                                val lines = f.useLines { it.count() }
                                val preview = runCatching { f.useLines { seq -> seq.take(10).joinToString("\n") } }.getOrDefault("")
                                "OK: ${if (append) "conteúdo acrescentado" else "arquivo salvo"} (${f.name}) — ${f.length()} bytes, $lines linhas.\n[Prévia das primeiras 10 linhas]\n$preview"
                            }
                        }
                    }
                }
                "file_read" -> sandbox.hostFile(s("path"), sessionId).let { if (it.exists()) it.readText().take(20_000) else "Arquivo não existe." }
                "file_list" -> {
                    val d = if (s("path").isBlank()) sandbox.workspaceFor(sessionId) else sandbox.hostFile(s("path"), sessionId)
                    d.walkTopDown().maxDepth(3).filter { it != d }.take(300)
                        .joinToString("\n") { (if (it.isDirectory) "📁 " else "📄 ") + it.relativeTo(d).path + if (it.isFile) " (${it.length()}b)" else "" }
                        .ifBlank { "(vazio)" }
                }
                "browser_navigate" -> {
                    val url = s("url").let { u -> if (u.startsWith("/root")) "file://" + sandbox.hostFile(u, sessionId).absolutePath else u }
                    val navigation = browserFor(sessionId).navigate(url)
                    if (navigation.startsWith("ERROR:") || navigation.startsWith("TIMEOUT:")) navigation
                    else navigation + "\n\n" + browserFor(sessionId).read(4000)
                }
                "browser_read", "browser_snapshot" -> browserFor(sessionId).read((i("max_chars") ?: 9000).coerceIn(1000, 20000))
                "browser_click" -> browserFor(sessionId).click(i("index") ?: -1)
                "browser_type" -> browserFor(sessionId).type(i("index") ?: -1, s("text"), b("submit"))
                "browser_scroll" -> browserFor(sessionId).scroll(s("direction") != "up")
                "browser_back" -> { browserFor(sessionId).back(); "ok" }
                "browser_js" -> browserFor(sessionId).runJs(s("code"))
                "device_notifications" -> dev.cueopen.app.device.NotifStore.recent(s("filter"), (i("limit") ?: 30).coerceIn(1, 100))
                    .joinToString("\n") { "[${java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it.time))}] ${it.app}: ${it.title} — ${it.text}".take(300) }
                    .ifBlank { "Nenhuma notificação capturada ainda." }
                "device_notify" -> { notifyRaw(s("title").ifBlank { "CueOpen" }, plainText(s("text"))); "Notificação enviada." }
                "device_overlay" -> { dev.cueopen.app.device.Overlay.show(ctx, s("text")); "Balão exibido." }
                "device_screenshot" -> {
                    val f = java.io.File(sandbox.workspaceFor(sessionId), "screenshot_${System.currentTimeMillis()}.png")
                    if (dev.cueopen.app.device.ScreenCapture.capture(ctx, f)) { store.addItem(sessionId, ChatItem.Media(path = f.absolutePath, caption = "Captura de tela")); "Captura salva e exibida: ${f.name}" }
                    else "Não foi possível capturar (permissão de captura não concedida)."
                }
                "browser_network" -> browserFor(sessionId).networkSummary(s("filter"))
                "browser_console" -> browserFor(sessionId).consoleSummary()
                "notes_write" -> {
                    val f = ctxMgr.notesFile(sessionId)
                    if (a["append"]?.jsonPrimitive?.booleanOrNull != false && f.exists()) f.appendText("\n" + s("content")) else f.writeText(s("content"))
                    "NOTES.md atualizado (${f.length()} bytes)."
                }
                "memory_save", "remember" -> {
                    val sess = store.session(sessionId)
                    val agentScoped = s("scope") == "agent" && sess?.agentId != null
                    val tags = a["tags"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
                    ctxMgr.saveMemory(s("fact"), if (agentScoped) sess?.agentId else null, tags)
                }
                "memory_search" -> ctxMgr.searchMemories(s("query"), store.session(sessionId)?.agentId)
                    .joinToString("\n") { "- ${it.text}" }.ifBlank { "Nada encontrado." }
                "remember_legacy" -> {
                    val sess = store.session(sessionId)
                    val ag = store.agent(sess?.agentId)
                    if (ag != null) { store.upsertAgent(ag.copy(memory = ag.memory + s("fact"))); "Memória salva." }
                    else "Esta tarefa não usa um Agent; crie um Agent para ter memória."
                }
                "create_skill" -> {
                    store.upsertSkill(Skill(name = s("name"), description = s("description"), instructions = s("instructions")))
                    "Skill \"${s("name")}\" criada e ativada."
                }
                else -> if (call.name.startsWith("mcp_")) dev.cueopen.app.connectors.Connectors.callTool(store, call.name, a)
                        else "Ferramenta desconhecida: ${call.name}"
            }
        } catch (c: kotlinx.coroutines.CancellationException) {
            executions.cancel(executionId)
            store.replaceItem(sessionId, step.copy(result = "Execução interrompida.", status = StepStatus.ERROR))
            throw c
        } catch (t: Throwable) {
            executions.fail(executionId, t.message.orEmpty())
            store.replaceItem(sessionId, step.copy(result = t.message.orEmpty(), status = StepStatus.ERROR))
            return ToolResult("Erro: ${t.message}")
        }
        val commandFailed = Regex("""\[exit ([1-9]\d*)\]""").containsMatchIn(out)
        if (out.startsWith("ERROR:") || out.startsWith("TIMEOUT:") || out.startsWith("ERRO:") || commandFailed) {
            executions.fail(executionId, out)
        } else {
            executions.finish(executionId, out)
        }
        store.replaceItem(sessionId, step.copy(result = out.take(4000), status = if (commandFailed || out.startsWith("ERROR:") || out.startsWith("TIMEOUT:") || out.startsWith("ERRO:")) StepStatus.ERROR else StepStatus.OK))
        return ToolResult(out)
    }


    /** Orquestra tarefas paralelas com workspaces isolados e estados explícitos. */
    /**
     * Parallel agent orchestration: each worker executes in isolation, reports structured outcomes,
     * and only then participates in integration. Model completion alone does not mean task success.
     */
    /**
     * Dependency-aware scheduler. Strings remain supported as independent tasks; object entries can
     * declare id, task/title and depends_on. Each ready wave is bounded by max_workers.
     */
    private suspend fun runMultiAgents(sessionId: String, tasksJson: String, repoPathArg: String, requestedWorkers: Int, requestedBudget: Int): String {
        val tasks = runCatching {
            val arr = org.json.JSONArray(tasksJson)
            (0 until arr.length()).mapNotNull { index ->
                when (val item = arr.opt(index)) {
                    is String -> item.trim().takeIf { it.isNotBlank() }?.let {
                        AgentTask("task-" + (index + 1), it, emptyList())
                    }
                    is org.json.JSONObject -> {
                        val id = item.optString("id").trim().ifBlank { "task-" + (index + 1) }
                        val task = item.optString("task").trim().ifBlank { item.optString("title").trim() }
                        val depsJson = item.optJSONArray("depends_on")
                        val deps = if (depsJson == null) emptyList() else
                            (0 until depsJson.length()).map { depsJson.optString(it).trim() }.filter { it.isNotBlank() }
                        if (task.isBlank()) null else AgentTask(id, task, deps)
                    }
                    else -> null
                }
            }
        }.getOrElse { return "ERRO: tasks_json deve ser um array JSON de strings ou objetos {id, task, depends_on}." }
        if (tasks.isEmpty()) return "ERRO: informe pelo menos uma subtarefa."
        if (tasks.map { it.id }.toSet().size != tasks.size) return "ERRO: cada subtarefa precisa ter um id único."
        val ids = tasks.map { it.id }.toSet()
        val invalid = tasks.firstOrNull { task -> task.dependsOn.any { it !in ids || it == task.id } }
        if (invalid != null) return "ERRO: dependência inválida na tarefa '" + invalid.id + "'. Confira depends_on e evite autorreferência."
        if (hasDependencyCycle(tasks)) return "ERRO: o plano contém um ciclo de dependências; nenhuma tarefa foi iniciada."

        val workerLimit = requestedWorkers.coerceIn(1, 5)
        val pending = tasks.toMutableList()
        val completed = linkedSetOf<String>()
        val failed = linkedSetOf<String>()
        val report = StringBuilder()
        appendReportLine(report, "Plano: " + tasks.size + " subtarefa(s), até " + workerLimit + " worker(s) simultâneos.")
        var hasRunBatch = false
        while (pending.isNotEmpty()) {
            val blocked = pending.filter { task -> task.dependsOn.any { it in failed } }
            if (blocked.isNotEmpty()) {
                blocked.forEach { task ->
                    pending.remove(task)
                    failed += task.id
                    appendReportLine(report, "Tarefa '" + task.id + "' bloqueada: uma dependência falhou.")
                }
            }
            val ready = pending.filter { task -> task.dependsOn.all { it in completed } }
            if (ready.isEmpty()) {
                if (pending.isNotEmpty()) {
                    appendReportLine(report, "ERRO: não há tarefas executáveis; dependências pendentes: " +
                        pending.joinToString { it.id + " <- " + it.dependsOn.joinToString(",") } + ".")
                    break
                }
                continue
            }
            ready.chunked(workerLimit).forEachIndexed { batchIndex, batch ->
                appendReportLine(report, "")
                appendReportLine(report, "## Onda · lote " + (batchIndex + 1) + " · " + batch.joinToString { it.id })
                val payload = org.json.JSONArray().apply { batch.forEach { put(it.task) } }.toString()
                val result = runMultiAgentsBatch(
                    sessionId = sessionId,
                    tasksJson = payload,
                    repoPathArg = repoPathArg,
                    requestedWorkers = workerLimit,
                    requestedBudget = requestedBudget,
                    allowDirtyWorkspace = hasRunBatch
                )
                hasRunBatch = true
                appendReportLine(report, result)
                val success = !result.startsWith("ERRO:") &&
                    !result.startsWith("Multi Agents pausado:") &&
                    !result.contains("ATENÇÃO:")
                batch.forEach { task ->
                    pending.remove(task)
                    if (success) completed += task.id else failed += task.id
                }
            }
        }
        appendReportLine(report, "")
        appendReportLine(report, "Resumo do plano: " + completed.size + " concluída(s), " + failed.size + " falha(s)/bloqueada(s).")
        if (failed.isNotEmpty()) appendReportLine(report, "Revisar tarefas: " + failed.joinToString(", ") + ".")
        return report.toString()
    }

    private fun hasDependencyCycle(tasks: List<AgentTask>): Boolean {
        val byId = tasks.associateBy { it.id }
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        fun visit(id: String): Boolean {
            if (id in visiting) return true
            if (id in visited) return false
            visiting += id
            val cycle = byId[id]?.dependsOn.orEmpty().any { visit(it) }
            visiting -= id
            visited += id
            return cycle
        }
        return tasks.any { visit(it.id) }
    }

    private suspend fun runMultiAgentsBatch(sessionId: String, tasksJson: String, repoPathArg: String, requestedWorkers: Int, requestedBudget: Int, allowDirtyWorkspace: Boolean = false): String {
        val tasks = runCatching {
            val arr = org.json.JSONArray(tasksJson)
            (0 until arr.length()).mapNotNull { arr.optString(it).trim().takeIf { task -> task.isNotBlank() } }
        }.getOrElse { return "ERRO: tasks_json deve ser um array JSON de strings." }
        if (tasks.isEmpty()) return "ERRO: informe pelo menos uma subtarefa."

        val count = minOf(tasks.size, requestedWorkers.coerceIn(1, 5))
        val budget = requestedBudget.coerceIn(2000, 12000).toLong()
        val parentPath = sandbox.guestPath(sessionId)
        val terminal = terminals.get(sessionId, parentPath)
        // Não use git worktree dentro de /root/workspace: cada sessão é montada num rootfs
        // público diferente, e a pasta de destino já existe. A cópia é feita no filesystem host.
        val gitRoot: String? = null
        val baseSha: String? = null

        val workers = tasks.take(count).mapIndexed { index, task ->
            Session(
                title = "[Worker " + (index + 1) + "] " + task.take(42),
                agentId = store.session(sessionId)?.agentId,
                status = SessionStatus.RUNNING,
                llm = listOf(
                    LlmMessage(
                        "user",
                        "Você é um subagente temporário do CueOpen. Tarefa: " + task +
                            "\n\nTrabalhe somente no seu workspace/branch. Não faça merge nem push; não chame multi_agents. " +
                            "Não afirme que testes passaram sem executá-los. Ao concluir, reporte resumo, arquivos alterados, " +
                            "comandos de teste executados, resultados e riscos."
                    )
                )
            )
        }
        workers.forEach { store.upsertSession(it) }

        if (gitRoot != null && baseSha != null) {
            val setupErrors = mutableListOf<String>()
            workers.forEach { child ->
                val branchName = "cue/agents/" + child.id
                val target = sandbox.guestPath(child.id)
                val cmd = "git -C " + shQuote(gitRoot) + " worktree add -b " +
                    shQuote(branchName) + " " + shQuote(target) + " " + shQuote(baseSha)
                val result = terminal.runAgentCommand(cmd, 45L, false)
                if (result.exitCode != 0) setupErrors += child.id + ": " + result.output.takeLast(350)
            }
            if (setupErrors.isNotEmpty()) {
                workers.forEach { child ->
                    terminal.runAgentCommand(
                        "git -C " + shQuote(gitRoot) + " worktree remove --force " +
                            shQuote(sandbox.guestPath(child.id)) + " >/dev/null 2>&1 || true; git -C " +
                            shQuote(gitRoot) + " branch -D " + shQuote("cue/agents/" + child.id) +
                            " >/dev/null 2>&1 || true",
                        20L, false
                    )
                    store.deleteSession(child.id)
                }
                return "Não iniciei workers porque a criação de worktrees falhou:\n" + setupErrors.joinToString("\n")
            }
        } else {
            val source = sandbox.workspaceFor(sessionId)
            for (child in workers) {
                val target = sandbox.workspaceFor(child.id)
                val copied = runCatching { source.copyRecursively(target, overwrite = true); true }.getOrElse { false }
                if (!copied) {
                    workers.forEach { store.deleteSession(it.id) }
                    return "Não iniciei os workers: falhou a cópia isolada do workspace para " + child.id + "."
                }
            }
        }

        val workerSteps = workers.associate { child ->
            val workerNumber = child.title.removePrefix("[Worker ").substringBefore("]")
            val taskLabel = child.title.substringAfter("] ", child.title).take(48)
            val step = ChatItem.Step(
                tool = "multi_agent_worker",
                title = "Agente " + workerNumber + " · " + taskLabel,
                args = "Na fila",
                result = "Workspace isolado preparado.",
                status = StepStatus.RUNNING
            )
            store.addItem(sessionId, step)
            child.id to step
        }

        val outcomes = java.util.concurrent.ConcurrentHashMap<String, WorkerOutcome>()
        val workerJobs = workers.mapIndexed { workerIndex, child ->
            scope.launch {
                val parentStep = workerSteps.getValue(child.id)
                val initial = store.session(child.id)
                val initialTokens = initial?.let { it.tokensIn + it.tokensOut } ?: 0L
                try {
                    store.replaceItem(sessionId, parentStep.copy(args = "Executando", result = "Agente em execução no workspace isolado."))
                    loop(child.id, maxStepsOverride = 6, tokenBudgetOverride = budget)
                    val finished = store.session(child.id)
                    val assistant = finished?.items?.lastOrNull { it is ChatItem.Assistant } as? ChatItem.Assistant
                    val errors = finished?.items?.filterIsInstance<ChatItem.Error>()?.map { it.text }.orEmpty()
                    val workerStatus = finished?.status
                    val usage = ((finished?.tokensIn ?: 0L) + (finished?.tokensOut ?: 0L) - initialTokens).coerceAtLeast(0L)
                    val outcome = WorkerOutcome(child.id, tasks[workerIndex], workerStatus, assistant?.text.orEmpty(), errors, usage)
                    outcomes[child.id] = outcome
                    val label = when {
                        outcome.completed -> "Pronto para integração"
                        workerStatus == SessionStatus.STOPPED -> "Cancelado"
                        workerStatus == SessionStatus.WAITING_USER -> "Precisa de intervenção"
                        workerStatus == SessionStatus.ERROR || errors.isNotEmpty() -> "Falhou"
                        else -> "Parcial / não validado"
                    }
                    val details = "Estado: " + label + " (" + (workerStatus ?: "desconhecido") + "). Uso registrado: " +
                        usage + " tokens. Testes agregados ainda não executados.\n" +
                        (outcome.summary.ifBlank { errors.firstOrNull() ?: "Nenhum relatório final foi produzido." })
                    store.replaceItem(sessionId, parentStep.copy(args = label, result = details.take(1800),
                        status = if (outcome.completed) StepStatus.OK else StepStatus.ERROR))
                } catch (cancel: kotlinx.coroutines.CancellationException) {
                    store.editSession(child.id) { it.copy(status = SessionStatus.STOPPED) }
                    outcomes[child.id] = WorkerOutcome(child.id, tasks[workerIndex], SessionStatus.STOPPED, "",
                        listOf("Subtarefa cancelada."), 0L)
                    store.replaceItem(sessionId, parentStep.copy(args = "Cancelado", result = "Subtarefa cancelada.",
                        status = StepStatus.ERROR))
                    throw cancel
                } catch (t: Throwable) {
                    val message = (t.message ?: t.toString()).take(300)
                    store.addItem(child.id, ChatItem.Error(text = "Worker falhou: " + message))
                    store.editSession(child.id) { it.copy(status = SessionStatus.ERROR) }
                    outcomes[child.id] = WorkerOutcome(child.id, tasks[workerIndex], SessionStatus.ERROR, "", listOf(message), 0L)
                    store.replaceItem(sessionId, parentStep.copy(args = "Falhou", result = message, status = StepStatus.ERROR))
                }
            }
        }
        synchronized(childJobsByParent) { childJobsByParent[sessionId] = workerJobs.toMutableList() }
        try {
            workerJobs.joinAll()
        } finally {
            synchronized(childJobsByParent) { childJobsByParent.remove(sessionId) }
        }

        val report = StringBuilder().apply {
            appendLine("Parallel Agent Engine: " + workers.size + " worker(s), orçamento configurado de " + budget + " tokens por worker.")
            appendLine("Conclusão textual não certifica compilação nem testes do projeto agregado.")
            workers.forEachIndexed { index, child ->
                val outcome = outcomes[child.id]
                appendLine()
                appendLine("### Agente " + (index + 1) + " · " + (outcome?.status ?: "desconhecido"))
                appendLine("Tarefa: " + tasks[index])
                appendLine("Resultado: " + (outcome?.summary ?: "Nenhum relatório final foi produzido.").take(1200))
                if (outcome?.errors?.isNotEmpty() == true) appendLine("Erros: " + outcome.errors.joinToString(" | ").take(400))
            }
        }

        var integrationFailures = 0
        var integratedCount = 0
        if (gitRoot != null && baseSha != null) {
            workers.forEachIndexed { index, child ->
                val outcome = outcomes[child.id]
                if (outcome?.completed != true) {
                    integrationFailures++
                    appendReportLine(report, "Agente " + (index + 1) + ": não integrado automaticamente porque o resultado não terminou sem erros.")
                    return@forEachIndexed
                }
                val patchPath = gitRoot.trimEnd('/') + "/.cueopen-worker-" + (index + 1) + ".patch"
                val createPatch = terminal.runAgentCommand(
                    "git -C " + shQuote(sandbox.guestPath(child.id)) +
                        " add -N -A >/dev/null 2>&1 || true; git -C " +
                        shQuote(sandbox.guestPath(child.id)) + " diff --binary " + shQuote(baseSha) +
                        " -- > " + shQuote(patchPath) + " && test -s " + shQuote(patchPath),
                    30L, false
                )
                if (createPatch.exitCode == 0) {
                    val apply = terminal.runAgentCommand(
                        "git -C " + shQuote(gitRoot) + " apply --3way " + shQuote(patchPath),
                        45L, false
                    )
                    if (apply.exitCode == 0) {
                        terminal.runAgentCommand("rm -f " + shQuote(patchPath), 10L, false)
                        integratedCount++
                        appendReportLine(report, "Agente " + (index + 1) + ": patch aplicado ao workspace principal.")
                    } else {
                        integrationFailures++
                        appendReportLine(report, "Agente " + (index + 1) + ": CONFLITO; patch preservado em " + patchPath + ".")
                    }
                } else {
                    appendReportLine(report, "Agente " + (index + 1) + ": nenhum diff aplicável foi gerado.")
                }
            }
            if (integratedCount > 0) {
                val status = terminal.runAgentCommand("git -C " + shQuote(gitRoot) + " status --short", 15L, false)
                appendReportLine(report, "Workspace após integração: " + status.output.lines().count { it.isNotBlank() } +
                    " caminho(s) alterado(s). Testes/build agregados ainda não executados.")
            }
        } else {
            workers.forEachIndexed { index, child ->
                val outcome = outcomes[child.id]
                if (outcome?.completed != true) {
                    integrationFailures++
                    appendReportLine(report, "Agente " + (index + 1) + ": artefatos não integrados; resultado parcial preservado.")
                    return@forEachIndexed
                }
                val source = sandbox.workspaceFor(child.id)
                val dest = sandbox.workspaceFor(sessionId)
                val synced = runCatching {
                    source.walkTopDown()
                        .filter { it.isFile && !it.toRelativeString(source).startsWith(".git/") && !it.toRelativeString(source).startsWith(".cue/") && !Regex("""\\.worker-\\d+$""").containsMatchIn(it.name) }
                        .forEach { file ->
                            val rel = file.relativeTo(source).path
                            val target = File(dest, rel)
                            target.parentFile?.mkdirs()
                            if (!target.exists()) file.copyTo(target, overwrite = false)
                            else if (target.readBytes().contentEquals(file.readBytes()).not()) {
                                file.copyTo(File(target.parentFile, target.name + ".worker-" + (index + 1)), overwrite = true)
                            }
                        }
                    true
                }.getOrElse { false }
                if (synced) integratedCount++ else integrationFailures++
                appendReportLine(report, "Agente " + (index + 1) + ": " +
                    if (synced) "sincronização concluída; divergências com sufixo .worker-" + (index + 1) + "."
                    else "sincronização parcial; revisar workspace.")
            }
        }

        if (gitRoot != null && baseSha != null && integratedCount > 0) {
            val status = terminal.runAgentCommand("git -C " + shQuote(gitRoot) + " status --short", 15L, false)
            appendReportLine(report, "Workspace agregado: " + status.output.lines().count { it.isNotBlank() } + " caminho(s) alterado(s).")
            appendReportLine(report, "Build e testes agregados: ainda não executados por este orquestrador.")
        }
        if (integrationFailures > 0) {
            appendReportLine(report, "ATENÇÃO: " + integrationFailures + " worker(s) precisam de revisão.")
        }

        workers.forEachIndexed { index, child ->
            val outcome = outcomes[child.id]
            val clean = outcome?.completed == true && integrationFailures == 0
            if (clean) {
                if (gitRoot != null && baseSha != null) {
                    terminal.runAgentCommand(
                        "git -C " + shQuote(gitRoot) + " worktree remove --force " +
                            shQuote(sandbox.guestPath(child.id)) + " >/dev/null 2>&1 || true; git -C " +
                            shQuote(gitRoot) + " branch -D " + shQuote("cue/agents/" + child.id) +
                            " >/dev/null 2>&1 || true",
                        30L, false
                    )
                }
                store.deleteSession(child.id)
            } else {
                appendReportLine(report, "Worker " + (index + 1) + " preservado para inspeção: " + child.id +
                    " (estado=" + (outcome?.status ?: "desconhecido") + ").")
            }
        }
        return report.toString()
    }

    private data class AgentTask(
        val id: String,
        val task: String,
        val dependsOn: List<String>
    )

    /**
     * Compact worker-output contract, used by the orchestrator when summarizing parallel work.
     * Structured reports reduce ambiguity between model completion and verified repository changes.
     */
    private data class WorkerOutcome(
        val sessionId: String,
        val task: String,
        val status: SessionStatus?,
        val summary: String,
        val errors: List<String>,
        val tokenUsage: Long
    ) {
        val completed: Boolean get() = status == SessionStatus.DONE && errors.isEmpty()
    }

    private fun appendReportLine(report: StringBuilder, line: String) { report.appendLine(line) }

    private fun shQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"

    private fun appendTerm(sessionId: String, text: String) {
        val m = _termLog.value
        _termLog.value = m + (sessionId to ((m[sessionId] ?: "") + text).takeLast(60_000))
    }

    private fun finalizeRunningSteps(sessionId: String, reason: String) {
        val steps = store.session(sessionId)?.items?.filterIsInstance<ChatItem.Step>()
            ?.filter { it.status == StepStatus.RUNNING }.orEmpty()
        steps.forEach { step ->
            store.replaceItem(sessionId, step.copy(result = reason, status = StepStatus.ERROR))
        }
    }

    private fun setActivity(sessionId: String, a: Activity) {
        _activity.value = _activity.value + (sessionId to a)
    }

    fun workspace(sessionId: String): File = sandbox.workspaceFor(sessionId)
}
