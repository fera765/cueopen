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
import dev.cueopen.app.files.AtomicTextFile
import dev.cueopen.app.llm.OpenRouter
import dev.cueopen.app.llm.ToolCall
import dev.cueopen.app.llm.bool
import dev.cueopen.app.llm.int
import dev.cueopen.app.llm.str
import dev.cueopen.app.llm.strArray
import dev.cueopen.app.llm.tool
import dev.cueopen.app.sandbox.Sandbox
import dev.cueopen.app.trading.TradeCoordinator
import dev.cueopen.app.uiconnector.iqoption.IqTradeWaitDirective
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.withLock
import dev.cueopen.app.wallet.WalletAgentPolicyStore
import dev.cueopen.app.wallet.SolanaWalletRepository
import dev.cueopen.app.wallet.SolanaRpcClient
import dev.cueopen.app.wallet.SolanaNetwork
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

private val iqOptionTradeWakeTag = Regex("""\[\[IQOPTION_TRADES:([0-9,]+)]]$""")
private val accessibilitySnapshotBoundTools = setOf(
    "device_accessibility_click", "device_accessibility_type",
    "device_accessibility_scroll", "device_accessibility_inspect_image",
)

private fun isStaleAccessibilitySnapshotFailure(toolName: String, detail: String): Boolean =
    toolName in accessibilitySnapshotBoundTools && listOf(
    "Snapshot ausente ou invalidado", "snapshot_id expirado", "snapshot_id ausente",
    "A janela mudou desde a leitura", "A árvore de controles mudou",
    "O controle mudou desde a leitura", "element_id desconhecido neste snapshot",
    ).any { phrase -> detail.contains(phrase) }

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
    fun send(sessionId: String, text: String, attachments: List<String> = emptyList(), onDone: (() -> Unit)? = null, quotedReply: String? = null, showUserMessage: Boolean = true): Job {
        jobs[sessionId]?.cancel()
        synchronized(childJobsByParent) { childJobsByParent.remove(sessionId)?.forEach { it.cancel() } }
        val quote = quotedReply?.trim()?.take(800)?.takeIf { it.isNotBlank() }
        if (showUserMessage) store.addItem(sessionId, ChatItem.User(text = text, attachments = attachments, replyTo = quote))
        var content = if (attachments.isEmpty()) text
        else text + "\n\n[Arquivos anexados no workspace: ${attachments.joinToString()}]"
        if (quote != null) content += "\n\n[Contexto: o usuário está respondendo à mensagem anterior do agente; o texto citado é histórico, não uma nova instrução.]\n" + quote.lineSequence().joinToString("\n") { "> $it" }
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

    // ------------------------------------------------------------------ ask_question / ask_secure (respostas da UI)
    /** O usuário respondeu o card de perguntas: marca respondido e retoma a tarefa com as respostas. */
    fun answerQuestion(sessionId: String, item: ChatItem.Question, answers: List<List<String>>) {
        val cur = store.session(sessionId)?.items?.firstOrNull { it.id == item.id } as? ChatItem.Question ?: return
        if (cur.state != "pending") return
        store.replaceItem(sessionId, cur.copy(answers = answers, state = "answered"))
        send(sessionId, AskTools.answersMessage(cur.questions, answers), showUserMessage = false)
    }

    fun dismissQuestion(sessionId: String, item: ChatItem.Question) {
        val cur = store.session(sessionId)?.items?.firstOrNull { it.id == item.id } as? ChatItem.Question ?: return
        if (cur.state != "pending") return
        store.replaceItem(sessionId, cur.copy(state = "dismissed"))
        send(sessionId, AskTools.DISMISSED_MESSAGE, showUserMessage = false)
    }

    /**
     * Formulário seguro enviado: os VALORES vão só para o [SecretVault] (memória do aparelho). O item do
     * chat guarda apenas os nomes, e a mensagem que retoma a IA também só tem os nomes.
     */
    fun submitSecure(sessionId: String, item: ChatItem.SecureForm, values: Map<String, String>) {
        val cur = store.session(sessionId)?.items?.firstOrNull { it.id == item.id } as? ChatItem.SecureForm ?: return
        if (cur.state != "pending") return
        val allowed = cur.fields.map { it.name }.toSet()
        val clean = values.filterKeys { it in allowed }.filterValues { it.isNotBlank() }
        SecretVault.put(sessionId, clean)
        store.replaceItem(sessionId, cur.copy(state = "submitted", submitted = clean.keys.toList()))
        send(sessionId, AskTools.secureSubmittedMessage(clean.keys.toList()), showUserMessage = false)
    }

    fun cancelSecure(sessionId: String, item: ChatItem.SecureForm) {
        val cur = store.session(sessionId)?.items?.firstOrNull { it.id == item.id } as? ChatItem.SecureForm ?: return
        if (cur.state != "pending") return
        store.replaceItem(sessionId, cur.copy(state = "cancelled"))
        send(sessionId, AskTools.SECURE_CANCELLED, showUserMessage = false)
    }

    /** Troca {{secret:nome}} pelos valores do cofre em todos os textos de um objeto de argumentos. */
    private fun resolveArgs(sessionId: String, o: JsonObject): JsonObject = JsonObject(o.mapValues { (_, v) ->
        val p = v as? kotlinx.serialization.json.JsonPrimitive
        if (p != null && p.isString && p.content.contains("secret:")) kotlinx.serialization.json.JsonPrimitive(SecretVault.resolve(sessionId, p.content))
        else if (v is JsonObject) resolveArgs(sessionId, v) else v
    })

    private fun maybeTitle(sessionId: String, text: String) {
        val s = store.session(sessionId) ?: return
        if (s.title != "Nova tarefa") return
        val fallback = text.lineSequence().first().take(48)
        store.editSession(sessionId) { it.copy(title = fallback) }
        // O título não segura a primeira chamada do agente; atualiza em paralelo quando terminar.
        scope.launch {
            runCatching {
                val t = llm.quick("Crie um título curto (máx 6 palavras, sem aspas, no idioma do pedido) para esta tarefa:\n$text", conversationId = sessionId)
                val clean = t.replace(Regex("^(t[ií]tulo[^:]*:)\\s*", RegexOption.IGNORE_CASE), "").trim().removeSurrounding("\"").removeSurrounding("*").take(60)
                if (clean.isNotBlank() && store.session(sessionId)?.title == fallback) store.editSession(sessionId) { it.copy(title = clean) }
            }
        }
    }

    private val ctxMgr = ContextManager(store, sandbox, llm)

    private suspend fun loop(sessionId: String, maxStepsOverride: Int? = null, tokenBudgetOverride: Long? = null) {
        store.session(sessionId)?.let { existing ->
            if (AccessibilityToolHistory.sanitizePersistedSession(existing) != existing)
                store.editSession(sessionId) { AccessibilityToolHistory.sanitizePersistedSession(it) }
        }
        val maxSteps = (maxStepsOverride ?: store.settings.maxSteps).coerceIn(1, store.settings.maxSteps.coerceAtLeast(1))
        val startingTokens = store.session(sessionId)?.let { it.tokensIn + it.tokensOut } ?: 0L
        var steps = 0
        var repeats = 0
        var lastContent = ""
        var lastToolSig = ""
        // Janela das últimas respostas de texto (detecta repetição mesmo alternando frases).
        val recentTexts = ArrayDeque<String>()
        // Quantas vezes seguidas o modelo ANUNCIOU uma ação ("vou pesquisar…") sem chamar ferramenta.
        var intentNoActionTurns = 0
        // Detecção de loop de ferramentas: mesma chamada (nome+args) repetida sem progresso.
        val toolSigWindow = ArrayDeque<String>()
        val resultSigWindow = ArrayDeque<String>()
        val invalidAccessibilitySnapshots = dev.cueopen.app.device.InvalidAccessibilitySnapshotGuard()
        val visualInspectionUsed = mutableSetOf<String>()
        val accessibilityReadScreenCompleted = mutableSetOf<String>()
        val transientAccessibilityOutputs = linkedMapOf<String, String>()
        var toolLoopNudges = 0
        var cleanRounds = 0
        val controlTools = setOf("tools_load", "sleep", "set_schedule", "ask_user", "ask_question", "ask_secure", "finish", "complete_step", "notes_write", "notes_read")
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
            // TypeSafe Jev: decide quais ferramentas/conectores e quanto knowledge/memória entram neste passo
            val allTools = tools(agent, allowMultiAgent = tokenBudgetOverride == null, builder = s.connectorBuilder)
            val (jplan, stepTools) = jevPlan(sessionId, s, allTools)
            val sys = LlmMessage("system", systemPrompt(sessionId, agent, s, jplan, allowDeviceTools = tokenBudgetOverride == null))
            val activeHistory = s.llm.map { AccessibilityToolHistory.forActiveModel(it, transientAccessibilityOutputs) }
            var history = ctxMgr.microCompact(activeHistory, sessionId)
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
                        return@run llm.chat(listOf(sys) + history, tools = stepTools, model = stepModel(agent), maxTokens = replyTokenLimit, conversationId = sessionId)
                    } catch (c: kotlinx.coroutines.CancellationException) {
                        throw c
                    } catch (t: Throwable) {
                        attempt++
                        val llmFailure = t as? dev.cueopen.app.llm.LlmRequestFailure
                        val retryable = llmFailure?.retryable == true
                        if (!retryable || attempt >= 3) {
                            runCatching { dev.cueopen.app.diag.ErrorLog.log("agent", "LLM falhou após $attempt tentativa(s): ${t.javaClass.simpleName}: ${t.message.orEmpty()}", sessionId, "llm") }
                            val attempts = if (attempt == 1) "Não repeti porque a falha não foi classificada como transitória." else "Tentei $attempt vezes."
                            store.addItemUnique(sessionId, ChatItem.Error(text = "O modelo falhou (${t.message?.take(120)}). $attempts Toque em enviar para continuar, ou troque de modelo em Configurações."))
                            store.editSession(sessionId) { it.copy(status = SessionStatus.WAITING_USER) }
                            return
                        }
                        val delayMs = dev.cueopen.app.llm.LlmRetryPolicy.delayMillis(attempt, llmFailure.retryAfterMillis)
                        val waitLabel = (delayMs / 1_000L).coerceAtLeast(1L)
                        setActivity(sessionId, Activity(Activity.Kind.THINKING, "Erro no modelo — aguardando ${waitLabel}s antes de tentar de novo ($attempt/3)"))
                        kotlinx.coroutines.delay(delayMs)
                    }
                }
                @Suppress("UNREACHABLE_CODE") return
            }
            val modelId = stepModel(agent)
            store.editSession(sessionId) {
                it.copy(llm = it.llm + LlmMessage("assistant", reply.content,
                    toolCallsJson = ToolCallHistoryNormalizer.redactSensitiveToolArguments(reply.rawToolCallsJson)),
                    tokensIn = it.tokensIn + reply.usage.prompt, tokensOut = it.tokensOut + reply.usage.completion,
                    tokensCached = it.tokensCached + reply.usage.cached, llmCalls = it.llmCalls + 1,
                    modelUsed = modelId)
            }
            // O preço vem de uma consulta adicional à rede. Resolva em paralelo para não atrasar a bolha.
            scope.launch {
                val cost = runCatching { llm.estimateCostUsd(modelId, reply.usage) }.getOrNull()?.takeIf { it > 0.0 } ?: return@launch
                store.editSession(sessionId) { it.copy(costUsd = it.costUsd + cost, hasPricedUsage = true) }
            }
            // Anti-repetição: se o modelo repetir quase o mesmo texto várias vezes, interrompe.
            // Só é repetição real se o texto for quase igual E as ações também forem as mesmas
            // (mesmas ferramentas com os mesmos argumentos). Mesma ferramenta com argumentos
            // diferentes (ex.: navegar em URLs distintas) é progresso, não loop.
            val cNow = reply.content?.trim().orEmpty()
            val curToolSig = reply.toolCalls.joinToString(";") { it.name + "|" + normArgs(it.argsJson) }
            val sameActions = reply.toolCalls.isEmpty() || curToolSig == lastToolSig
            lastToolSig = curToolSig
            // Repetição de texto: compara com a JANELA das últimas respostas (não só a anterior), para pegar
            // o modelo que fica alternando 2–3 frases quase iguais sem progredir. Só conta como repetição se
            // as AÇÕES também forem as mesmas (mesma ferramenta+args, ou nenhuma) — URLs/args diferentes = progresso.
            if (cNow.length > 15 && sameActions && recentTexts.any { similar(cNow, it) }) {
                if (++repeats >= 2) {
                    runCatching { dev.cueopen.app.diag.ErrorLog.log("agent", "Modelo entrou em repetição (interrompido).", sessionId, "llm") }
                    store.addItemUnique(sessionId, ChatItem.Error(text = "O modelo entrou em repetição. Interrompi para não gastar tokens à toa. Reformule o pedido ou troque de modelo."))
                    store.editSession(sessionId) { it.copy(status = SessionStatus.WAITING_USER) }
                    return
                }
            } else repeats = 0
            if (cNow.isNotEmpty()) { recentTexts.addLast(cNow); while (recentTexts.size > 3) recentTexts.removeFirst() }
            lastContent = cNow
            if (!reply.content.isNullOrBlank()) {
                val txt = reply.content.trim()
                val isFinal = reply.toolCalls.isEmpty()
                // Evita bolhas repetidas: se a última mensagem do assistente foi uma narração (não-final) quase
                // igual a esta, substitui em vez de empilhar outra igual ("Vou aguardar e verificar…" repetido).
                val lastA = store.session(sessionId)?.items?.lastOrNull { it is ChatItem.Assistant } as? ChatItem.Assistant
                val collapse = lastA != null && !lastA.final && !isFinal && txt.length > 20 && similar(txt, lastA.text.trim())
                if (collapse) store.replaceItem(sessionId, lastA!!.copy(text = txt))
                else store.addItem(sessionId, ChatItem.Assistant(text = txt, final = isFinal))
            }
            if (reply.toolCalls.isEmpty()) {
                // Regra de AÇÃO OBRIGATÓRIA: o modelo ANUNCIOU uma ação ("vou pesquisar/verificar/testar…") mas
                // não chamou nenhuma ferramenta. Em vez de encerrar (ou só dormir) sem agir, força a execução na
                // próxima volta; se insistir, interrompe. Isso quebra o loop de "anúncios" sem progresso.
                if (cNow.isNotBlank() && hasActionIntent(cNow)) {
                    if (++intentNoActionTurns >= 3) {
                        runCatching { dev.cueopen.app.diag.ErrorLog.log("agent", "Modelo anunciou ações sem executá-las (interrompido).", sessionId, "llm") }
                        store.addItemUnique(sessionId, ChatItem.Error(text = "O modelo ficou anunciando ações (\"vou pesquisar…\") sem executá-las. Interrompi para não gastar tokens à toa. Reformule o pedido ou troque de modelo."))
                        store.editSession(sessionId) { it.copy(status = SessionStatus.WAITING_USER) }
                        return
                    }
                    store.editSession(sessionId) { it.copy(llm = it.llm + LlmMessage("user",
                        "[Sistema] Você anunciou uma ação mas NÃO chamou nenhuma ferramenta. Toda intenção de agir (\"vou pesquisar/verificar/testar/abrir/rodar…\") precisa vir COM a chamada de ferramenta correspondente na MESMA resposta. Execute a ferramenta AGORA (ex.: browser_navigate, shell, file_write, uic_*). Se a tarefa já acabou, dê o resultado final SEM anunciar passos; se está bloqueado, chame finish.")) }
                    if (++steps >= maxSteps) {
                        if (store.session(sessionId)?.continuous == true) { sleepAndWake(sessionId, 60, "pausa após $maxSteps passos"); steps = 0 }
                        else { store.addItemUnique(sessionId, ChatItem.Error(text = "Limite de $maxSteps passos atingido. Envie \"continue\" para seguir.")); store.editSession(sessionId) { it.copy(status = SessionStatus.WAITING_USER) }; return }
                    }
                    continue
                }
                intentNoActionTurns = 0
                if (store.session(sessionId)?.continuous == true) {
                    // modo contínuo: sem ferramenta = lembra de dormir até a próxima verificação
                    store.editSession(sessionId) { it.copy(llm = it.llm + LlmMessage("user", "[Sistema] Você está em modo contínuo. Use `sleep` para aguardar a próxima verificação ou `finish` se o usuário pediu para encerrar.")) }
                    if (++steps >= maxSteps) { sleepAndWake(sessionId, 5 * 60, "verificação periódica"); steps = 0 }
                    continue
                }
                store.editSession(sessionId) { it.copy(status = SessionStatus.DONE) }
                if (tokenBudgetOverride == null) store.session(sessionId)?.let { done -> scope.launch { ctxMgr.extractMemories(done) } }
                return
            }
            intentNoActionTurns = 0
            var stopLoop: SessionStatus? = null
            var sleepReq: Pair<Int, String>? = null // (segundos, motivo)
            val tradeIdsAfterSleep = linkedSetOf<Long>()
            val tradeCallCache = HashMap<String, ToolResult>()
            val resultHashes = HashMap<String, Int>()
            val resultLong = HashSet<String>()
            val staleSnapshotRecoveryCache = HashMap<String?, String>()
            for (call in reply.toolCalls) {
                val accessibilitySnapshotId = if (call.name in accessibilitySnapshotBoundTools) runCatching {
                    json.parseToJsonElement(call.argsJson).jsonObject["snapshot_id"]?.jsonPrimitive?.contentOrNull
                }.getOrNull() else null
                val blockedSnapshotReuse = call.name in accessibilitySnapshotBoundTools &&
                    invalidAccessibilitySnapshots.shouldBlock(accessibilitySnapshotId)
                val blockedSnapshotRead = if (blockedSnapshotReuse) {
                    staleSnapshotRecoveryCache[accessibilitySnapshotId] ?: try {
                        dev.cueopen.app.device.DeviceAccessibilityActions.readScreen(sessionId)
                    } catch (c: kotlinx.coroutines.CancellationException) {
                        throw c
                    } catch (t: Throwable) {
                        "A releitura automática falhou: ${t.message?.take(180) ?: t.javaClass.simpleName}."
                    }
                } else null
                if (blockedSnapshotReuse) {
                    accessibilitySnapshotId?.let { staleSnapshotRecoveryCache[it] = blockedSnapshotRead.orEmpty() }
                }
                // Auto-recuperação: uma exceção em qualquer ferramenta vira um erro acionável devolvido
                // ao modelo (nunca derruba o loop do agente).
                val tradeResultTool = call.name == "uic_iqoption_open_option" || call.name == "uic_iqoption_get_trade_result"
                val cacheKey = call.name + "|" + normArgs(call.argsJson)
                val cachedTradeResult = if (tradeResultTool) tradeCallCache[cacheKey] else null
                val rawResult = if (blockedSnapshotReuse) {
                    ToolResult("ERRO: Snapshot ausente ou invalidado; nenhuma ação foi executada com o ID já recusado. Use somente IDs da releitura atual:\n${blockedSnapshotRead.orEmpty()}")
                } else try {
                    cachedTradeResult ?: runTool(
                        sessionId, call, allowDeviceTools = tokenBudgetOverride == null,
                        model = modelId, visualInspectionUsed = visualInspectionUsed,
                        accessibilityReadScreenCompleted = accessibilityReadScreenCompleted,
                    )
                } catch (c: kotlinx.coroutines.CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    val safeFailure = if (call.name.startsWith("device_accessibility_"))
                        "Falha de ferramenta Android; conteúdo de tela omitido por privacidade."
                    else "${t.javaClass.simpleName}: ${t.message.orEmpty()}"
                    runCatching { dev.cueopen.app.diag.ErrorLog.log(errorSource(call.name), safeFailure, sessionId, call.name) }
                    val detail = t.message?.take(300) ?: t.javaClass.simpleName
                    val staleFailure = isStaleAccessibilitySnapshotFailure(call.name, detail)
                    val refreshedScreen = if (staleFailure) try {
                        dev.cueopen.app.device.DeviceAccessibilityActions.readScreen(sessionId)
                    } catch (c: kotlinx.coroutines.CancellationException) {
                        throw c
                    } catch (refreshError: Throwable) {
                        "A releitura automática também falhou: ${refreshError.message?.take(180) ?: refreshError.javaClass.simpleName}."
                    } else ""
                    val output = if (staleFailure) {
                        "ERRO: $detail. Nenhuma ação foi executada. Li a tela automaticamente; descarte todos os IDs antigos e reidentifique o alvo usando esta leitura nova:\n$refreshedScreen"
                    } else {
                        "ERRO ao executar a ferramenta \"${call.name}\": $detail. Diagnostique a causa, ajuste os argumentos/abordagem e tente de outra forma; não repita a mesma chamada. Se estiver bloqueado, chame finish explicando."
                    }
                    ToolResult(output = output)
                }
                if (call.name in accessibilitySnapshotBoundTools ||
                    call.name == "device_accessibility_open_app" || call.name == "device_accessibility_back") {
                    accessibilityReadScreenCompleted.remove(sessionId)
                }
                if (call.name == "device_accessibility_read_screen" &&
                    rawResult.output.startsWith("Tela acessível", ignoreCase = true)) {
                    accessibilityReadScreenCompleted.add(sessionId)
                } else if (isStaleAccessibilitySnapshotFailure(call.name, rawResult.output) &&
                    rawResult.output.contains("snapshot_id=")) {
                    accessibilityReadScreenCompleted.add(sessionId)
                }
                if (call.name in accessibilitySnapshotBoundTools)
                    invalidAccessibilitySnapshots.recordFailure(accessibilitySnapshotId, rawResult.output)
                if (!blockedSnapshotReuse && accessibilitySnapshotId != null &&
                    isStaleAccessibilitySnapshotFailure(call.name, rawResult.output))
                    staleSnapshotRecoveryCache[accessibilitySnapshotId] = rawResult.output
                if (tradeResultTool && cachedTradeResult == null) tradeCallCache[cacheKey] = rawResult
                val consumedWait = if (tradeResultTool) IqTradeWaitDirective.consume(rawResult.output) else null
                val outputText = consumedWait?.output ?: rawResult.output
                val result = rawResult.copy(output = if (cachedTradeResult != null)
                    "Chamada idêntica já executada nesta resposta; resultado reutilizado para evitar uma segunda ordem/consulta.\n$outputText"
                else outputText)
                val tradeWaits = consumedWait?.waits.orEmpty()
                tradeWaits.forEach { tradeIdsAfterSleep += it.tradeId }
                val directiveSeconds = tradeWaits.maxOfOrNull { it.seconds }
                if (directiveSeconds != null) {
                    val current = sleepReq
                    if (current == null || directiveSeconds > current.first)
                        sleepReq = directiveSeconds to "aguardar vencimento da(s) opção(ões) ${tradeWaits.joinToString { it.tradeId.toString() }}; fazer uma única verificação ao final"
                }
                resultHashes[call.id] = result.output.hashCode()
                if (result.output.length > 200) resultLong.add(call.id)
                // a especificação do SDK (connector_spec) vai inteira: cortá-la fazia o agente ler a sobra por arquivo em loop
                // nenhum valor do formulário seguro volta para o modelo, mesmo que uma ferramenta o ecoe
                val accessibilityOutput = call.name.startsWith("device_accessibility_")
                val ctxOut = ctxMgr.storeToolOutput(
                    sessionId, call.id, SecretVault.redact(sessionId, result.output),
                    limit = if (call.name == "connector_spec") 60_000 else 8_000,
                    persistLongOutput = !accessibilityOutput)
                if (accessibilityOutput) {
                    transientAccessibilityOutputs.remove(call.id)
                    transientAccessibilityOutputs[call.id] = ctxOut
                    while (transientAccessibilityOutputs.size > 6) {
                        val oldest = transientAccessibilityOutputs.keys.firstOrNull() ?: break
                        transientAccessibilityOutputs.remove(oldest)
                    }
                }
                val durableToolOutput = AccessibilityToolHistory.durableResult(call.name, ctxOut)
                store.editSession(sessionId) {
                    it.copy(llm = it.llm + LlmMessage("tool", durableToolOutput, toolCallId = call.id, name = call.name))
                }
                if (result.stop != null) stopLoop = result.stop
                if (result.sleepSeconds != null) {
                    val current = sleepReq
                    if (current == null || result.sleepSeconds > current.first) sleepReq = result.sleepSeconds to result.output
                }
            }
            // Loop de ferramentas: mesma ação repetida SEM progresso → avisa o modelo e, só se insistir muito, interrompe.
            // Ferramentas de ambiente (browser, aparelho, shell, conectores) mudam de estado entre chamadas — rolar,
            // executar script, consultar posições: repetir é legítimo. Elas têm limite bem maior e nunca contam
            // "mesmo resultado com argumentos diferentes". Leituras puras (arquivo, memória, spec) seguem o limite rígido.
            run {
                var tripped: String? = null
                var trippedRepeatable = true
                for (call in reply.toolCalls) {
                    if (call.name in controlTools) continue
                    val repeatable = isRepeatableTool(call.name)
                    val sig = call.name + "|" + normArgs(call.argsJson) + "|" + (resultHashes[call.id] ?: 0)
                    toolSigWindow.addLast(sig)
                    while (toolSigWindow.size > 16) toolSigWindow.removeFirst()
                    val same = toolSigWindow.count { it == sig }
                    if (same >= (if (repeatable) 6 else 3)) { tripped = call.name; trippedRepeatable = trippedRepeatable && repeatable }
                    // mesmo conteúdo longo por caminhos diferentes (ex.: reler cópias do mesmo arquivo) — só para leituras puras
                    if (!repeatable && call.id in resultLong) {
                        val rsig = call.name + "#" + resultHashes[call.id]
                        resultSigWindow.addLast(rsig)
                        while (resultSigWindow.size > 10) resultSigWindow.removeFirst()
                        if (resultSigWindow.count { it == rsig } >= 3) { tripped = call.name; trippedRepeatable = false }
                    }
                }
                if (tripped == null) {
                    if (++cleanRounds >= 5) { toolLoopNudges = 0; cleanRounds = 0 } // voltou a progredir: zera os avisos
                } else {
                    cleanRounds = 0
                    val limit = if (trippedRepeatable) 4 else 2
                    if (++toolLoopNudges >= limit) {
                        runCatching { dev.cueopen.app.diag.ErrorLog.log("agent", "Loop de ferramenta sem progresso: $tripped (interrompido).", sessionId, tripped) }
                        store.addItemUnique(sessionId, ChatItem.Error(text = "O agente repetiu exatamente a mesma ação ($tripped), com os mesmos argumentos e o mesmo resultado, várias vezes seguidas. Interrompi para evitar um loop. Ajuste o pedido/abordagem ou troque de modelo."))
                        store.editSession(sessionId) { it.copy(status = SessionStatus.WAITING_USER) }
                        return
                    }
                    store.editSession(sessionId) {
                        it.copy(llm = it.llm + LlmMessage("user", "[Sistema] Você chamou \"$tripped\" várias vezes com os MESMOS argumentos e recebeu o MESMO resultado — nada mudou. " +
                            "Se está esperando algo acontecer (página carregar, requisição chegar), espere de outro jeito (ex.: um passo diferente, outro seletor/script, browser_read) ou mude a abordagem; " +
                            "se a tarefa estiver concluída ou bloqueada, chame finish explicando."))
                    }
                }
            }

            val finalStatus = stopLoop
            if (finalStatus != null) {
                store.editSession(sessionId) { it.copy(status = finalStatus, continuous = if (finalStatus == SessionStatus.DONE) false else it.continuous) }
                if (finalStatus == SessionStatus.DONE && tokenBudgetOverride == null) store.session(sessionId)?.let { done -> scope.launch { ctxMgr.extractMemories(done) } }
                return
            }
            val sl = sleepReq
            if (sl != null) {
                sleepAndWake(sessionId, sl.first, sl.second, afterTradeIds = tradeIdsAfterSleep, preserveContinuous = tradeIdsAfterSleep.isEmpty())
                steps = 0
                continue
            }
            steps++
            // Aviso de proximidade do limite: pede para priorizar a entrega antes do corte abrupto.
            if (steps == (maxSteps - 2).coerceAtLeast(1) && store.session(sessionId)?.continuous != true) {
                store.editSession(sessionId) { it.copy(llm = it.llm + LlmMessage("user", "[Sistema] Você está perto do limite de passos. Priorize concluir o essencial e entregar agora com finish (resumo curto + arquivos). Deixe o não-essencial como próximo passo.")) }
            }
            if (steps >= maxSteps) {
                if (store.session(sessionId)?.continuous == true) { sleepAndWake(sessionId, 60, "pausa automática após $maxSteps passos"); steps = 0; continue }
                store.addItemUnique(sessionId, ChatItem.Error(text = "Limite de $maxSteps passos atingido. Envie \"continue\" para seguir."))
                store.editSession(sessionId) { it.copy(status = SessionStatus.WAITING_USER) }
                return
            }
        }
    }

    /** Modo contínuo: dorme (o serviço em foreground + wakelock mantém o processo vivo) e retoma. */
    private suspend fun sleepAndWake(
        sessionId: String, seconds: Int, reason: String,
        afterTradeIds: Set<Long> = emptySet(), preserveContinuous: Boolean = true,
    ) {
        val wake = System.currentTimeMillis() + seconds.coerceAtLeast(1) * 1000L
        val tag = afterTradeIds.takeIf { it.isNotEmpty() }?.sorted()?.joinToString(",")?.let { " [[IQOPTION_TRADES:$it]]" }.orEmpty()
        val continuous = store.session(sessionId)?.continuous ?: false
        store.editSession(sessionId) {
            it.copy(status = SessionStatus.SLEEPING, wakeAt = wake, wakeReason = reason + tag,
                continuous = if (preserveContinuous) true else continuous)
        }
        setActivity(sessionId, Activity(Activity.Kind.IDLE, "Aguardando até ${java.text.SimpleDateFormat(if (seconds < 120) "HH:mm:ss" else "HH:mm", Locale.getDefault()).format(Date(wake))}"))
        dev.cueopen.app.sandbox.SandboxService.start(ctx, if (preserveContinuous) "Agente em modo contínuo" else "Aguardando vencimento da opção")
        store.flush()
        while (System.currentTimeMillis() < wake) kotlinx.coroutines.delay(minOf(30_000L, wake - System.currentTimeMillis()).coerceAtLeast(1000))
        wakeUp(sessionId)
        val ids = afterTradeIds.ifEmpty { tradeIdsFromWakeReason(store.session(sessionId)?.wakeReason.orEmpty()) }
        if (ids.isNotEmpty()) checkExpiredTradesOnce(sessionId, ids)
    }

    /** One event-driven local result read after the broker expiry; no repeated network polling. */
    private suspend fun checkExpiredTradesOnce(sessionId: String, tradeIds: Set<Long>) {
        val session = store.session(sessionId) ?: return
        val agent = store.agent(session.agentId)
        for (id in tradeIds) {
            val result = runCatching {
                dev.cueopen.app.CueApp.instance.uiConnectors.callTool(
                    agent, agent?.id ?: sessionId, "uic_iqoption_get_trade_result",
                    buildJsonObject { put("id", id) },
                )
            }.getOrElse { "ERRO na única verificação pós-vencimento: ${it.message ?: it.javaClass.simpleName}" }
            val safe = SecretVault.redact(sessionId, result)
            val failed = safe.startsWith("ERRO") || safe.startsWith("RECUSADO")
            store.addItem(sessionId, ChatItem.Step(
                tool = "uic_iqoption_get_trade_result", title = "Verificação única após vencimento",
                args = "opção $id", result = safe.take(4000), status = if (failed) StepStatus.ERROR else StepStatus.OK,
            ))
            store.editSession(sessionId) {
                it.copy(llm = it.llm + LlmMessage("user",
                    "[Sistema] Após aguardar o vencimento da opção $id, fiz uma única verificação do evento de resultado: $safe. Não repita a consulta; se o evento ainda não chegou, explique que o protocolo desta sessão não confirma uma consulta histórica e aguarde uma atualização posterior."))
            }
        }
    }

    private fun tradeIdsFromWakeReason(reason: String): Set<Long> =
        iqOptionTradeWakeTag.find(reason)?.groupValues?.getOrNull(1)?.split(',')
            ?.mapNotNull { it.toLongOrNull()?.takeIf { id -> id > 0L } }?.toSet().orEmpty()

    private fun wakeUp(sessionId: String) {
        val r = iqOptionTradeWakeTag.replace(store.session(sessionId)?.wakeReason.orEmpty(), "").trim()
        val now = SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()).format(Date())
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
        val hasArmedRules = runCatching { dev.cueopen.app.wallet.DayTradeStore(ctx.applicationContext).armedAgentIds().isNotEmpty() }.getOrDefault(false)
        if (store.state.value.agents.any { it.autonomous } || hasArmedRules)
            dev.cueopen.app.sandbox.SandboxService.start(ctx, if (hasArmedRules) "Monitorando stop-loss/take-profit" else "Agentes autônomos ativos")
        if (autonomyJob?.isActive == true) return
        autonomyJob = scope.launch {
            while (isActive) {
                runCatching { autonomyTick() }.onFailure { t ->
                    android.util.Log.e("CueOpen.Autonomy", "Falha no scheduler local", t)
                    runCatching { dev.cueopen.app.diag.ErrorLog.log("scheduler", "autonomyTick: ${t.javaClass.simpleName}: ${t.message.orEmpty()}", null, "heartbeat") }
                }
                // Stop-loss/take-profit AUTOMÁTICO: avalia as regras armadas a cada ~20 s,
                // independente do ciclo do agente, e fecha a posição assim que o alvo é atingido.
                if (System.currentTimeMillis() - lastRuleEvalAt >= 20_000L) {
                    lastRuleEvalAt = System.currentTimeMillis()
                    runCatching { evaluateAllArmedRules() }.onFailure { t ->
                        android.util.Log.e("CueOpen.Autonomy", "Falha ao avaliar regras de trade", t)
                        runCatching { dev.cueopen.app.diag.ErrorLog.log("scheduler", "evaluateAllArmedRules: ${t.javaClass.simpleName}: ${t.message.orEmpty()}", null, "trade") }
                    }
                }
                // Intervalos de 1 minuto precisam de margem; verificar a cada 5 s evita atraso
                // adicional causado pelo ticker de 20 s.
                kotlinx.coroutines.delay(5_000)
            }
        }
    }

    @Volatile private var lastRuleEvalAt = 0L

    /** Rede salva da carteira (fail-closed). */
    private fun walletNetworkOrNull(): SolanaNetwork? = runCatching {
        val stored = ctx.applicationContext
            .getSharedPreferences("solana_wallet_settings", android.content.Context.MODE_PRIVATE)
            .getString("network", null) ?: return null
        SolanaNetwork.valueOf(stored)
    }.getOrNull()

    /**
     * Avaliador AUTOMÁTICO de stop-loss/take-profit: roda fora do ciclo do agente. Para cada agente
     * com regras armadas e capacidade de day trade, checa o preço real e executa a troca no gatilho.
     * O usuário vê o fechamento automático no chat do agente. Só Mainnet (o engine garante).
     */
    private suspend fun evaluateAllArmedRules() {
        val network = walletNetworkOrNull() ?: return
        if (network != SolanaNetwork.MAINNET) return // day trade opera só na Mainnet
        val dtStore = dev.cueopen.app.wallet.DayTradeStore(ctx.applicationContext)
        val agentIds = dtStore.armedAgentIds()
        if (agentIds.isEmpty()) return
        val wallet = SolanaWalletRepository(ctx.applicationContext)
        if (!wallet.hasWallet() || !wallet.backupConfirmed()) return
        val policies = WalletAgentPolicyStore(ctx.applicationContext)
        val engine = dev.cueopen.app.wallet.DayTradeEngine(ctx.applicationContext, wallet)
        for (agentId in agentIds) {
            if (!policies.canAgentDayTrade(agentId)) continue
            val outcomes = runCatching {
                engine.evaluateAgentRules(agentId, network, policies.agentMaxTransferLamports(agentId))
            }.getOrDefault(emptyList())
            val executed = outcomes.filter { it.executed }
            if (executed.isNotEmpty()) {
                val home = store.agent(agentId)?.homeSessionId
                if (home != null && store.session(home) != null) {
                    executed.forEach { o ->
                        store.addItem(home, ChatItem.Step(tool = "trade", title = "Trade automático: ${o.rule.label}", args = o.rule.id, result = o.message, status = StepStatus.OK))
                    }
                    store.flush()
                    store.agent(agentId)?.let { notifyAgent(it, "Trade automático executado: " + executed.joinToString("; ") { it.rule.label }) }
                }
            }
        }
    }

    /**
     * Chamado pelo alarme (AlarmManager) do agendamento. NÃO bloqueia o receiver: dispara o ciclo e a
     * avaliação de regras no próprio escopo do app, para o receiver finalizar rápido e nunca estourar
     * o limite de ~10 s dos BroadcastReceivers (o que quebrava a corrente de agendamento).
     */
    fun triggerHeartbeatFromAlarm(agentId: String) {
        val a = store.agent(agentId) ?: return
        if (!a.autonomous || a.homeSessionId == null) return
        scope.launch {
            runCatching { evaluateAllArmedRules() }.onFailure {
                runCatching { dev.cueopen.app.diag.ErrorLog.log("scheduler", "evaluateAllArmedRules (alarme): ${it.message}", a.homeSessionId, "trade") }
            }
            // O alarme JÁ é o horário agendado → não exigimos "due" (evita perder o ciclo por arredondamento).
            if (canBeat(a, System.currentTimeMillis(), java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY), requireDue = false)) heartbeat(a)
        }
    }

    // Dia (yyyy-MM-dd) em que já avisamos cada agente sobre orçamento esgotado — evita repetir o aviso.
    private val budgetWarnedDay = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * Decide se um agente autônomo deve iniciar um ciclo AGORA. Centraliza as condições das duas vias
     * (ticker em processo e alarme) para que a lógica nunca divirja. Ponto-chave: quando um ciclo é
     * BLOQUEADO pelo orçamento diário de tokens, NÃO falha em silêncio — avisa no chat + log + push
     * uma vez por dia, para o usuário entender por que o agente "parou" (antes isso era invisível e
     * parecia que o agendamento tinha quebrado).
     */
    private fun canBeat(a: AgentProfile, now: Long, hour: Int, requireDue: Boolean): Boolean {
        if (!a.autonomous) return false
        if (dev.cueopen.app.device.DeviceCaps.BACKGROUND !in a.deviceCaps) return false
        val home = a.homeSessionId ?: return false
        if (jobs[home]?.isActive == true) return false
        if (requireDue && now < (a.lastBeatAt ?: 0) + a.heartbeatMinutes * 60_000L) return false
        val inHours = if (a.activeStartHour <= a.activeEndHour) hour in a.activeStartHour until a.activeEndHour
                      else hour >= a.activeStartHour || hour < a.activeEndHour
        if (!inHours) return false
        val day = today()
        val spent = if (a.tokensDay == day) a.tokensToday else 0
        if (spent >= a.dailyTokenBudget) {
            if (budgetWarnedDay[a.id] != day) {
                budgetWarnedDay[a.id] = day
                val msg = "Orçamento diário de tokens do agente atingido (${a.dailyTokenBudget}). A autonomia fica pausada até a virada do dia ou até aumentar o limite. As regras de stop-loss/take-profit continuam sendo monitoradas normalmente."
                runCatching { dev.cueopen.app.diag.ErrorLog.log("heartbeat", msg, home, "set_schedule") }
                runCatching { store.addItem(home, ChatItem.Error(text = msg)); store.flush() }
                runCatching { notifyAgent(a, msg) }
            }
            return false
        }
        return true
    }

    private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    /**
     * Leitura paginada de arquivo: devolve linhas numeradas [offset, offset+limit) com no máximo ~7000 caracteres
     * (abaixo do limite de armazenamento de saídas, então NUNCA gera um novo .cue/out — o que antes causava um
     * ciclo infinito: ler a saída truncada gerava outra saída truncada em outro arquivo) e diz onde continuar.
     */
    private fun readFileChunk(f: java.io.File, shown: String, offset: Int, limit: Int): String {
        if (!f.exists()) return "ERRO: arquivo não existe: $shown. Use file_list para ver o diretório."
        if (f.isDirectory) return "ERRO: $shown é um diretório. Use file_list."
        val head = f.inputStream().use { ins -> ByteArray(4096).let { b -> val n = ins.read(b); if (n <= 0) ByteArray(0) else b.copyOf(n) } }
        if (head.any { it == 0.toByte() }) return "Arquivo binário (${f.length()} bytes): $shown — não dá para ler como texto."
        val start = offset.coerceAtLeast(1)
        val max = limit.coerceIn(1, 1000)
        val sb = StringBuilder()
        var total = 0
        var last = start - 1
        var full = false
        f.bufferedReader().useLines { seq ->
            seq.forEach { line ->
                total++
                if (total < start || full) return@forEach
                if (total >= start + max) { full = true; return@forEach }
                val l = if (line.length > 500) line.take(500) + " …(+${line.length - 500} chars)" else line
                if (sb.length + l.length > 7_000) { full = true; return@forEach }
                sb.append(total.toString().padStart(6)).append("│ ").append(l).append('\n')
                last = total
            }
        }
        if (total == 0) return "$shown — arquivo vazio."
        if (start > total) return "$shown tem só $total linhas; offset=$start está além do fim. Nada novo para ler."
        val tail = if (last < total) "[continua: chame file_read com path=$shown offset=${last + 1} para as próximas linhas]" else "[fim do arquivo]"
        return "$shown — linhas $start–$last de $total (${f.length() / 1024} KB)\n" + sb.toString() + tail
    }

    private object CustomDefParserFence { fun strip(t: String) = dev.cueopen.app.uiconnector.custom.CustomDefParser.stripFences(t) }

    private fun tfLabel(tf: Int) = when { tf % 3600 == 0 -> "${tf / 3600}h"; tf % 60 == 0 -> "${tf / 60} min"; else -> "${tf}s" }

    /** Ferramentas cujo resultado depende do estado do ambiente (repetir pode ser legítimo). */
    private fun isRepeatableTool(name: String): Boolean {
        val pure = setOf("file_read", "file_list", "memory_search", "notes_read", "connector_spec", "skill_load", "connector_validate")
        if (name in pure) return false
        return true // browser_*, device_*, shell, uic_*, mcp_*, wallet/market e demais ferramentas de ambiente
    }

    /** Normaliza argumentos JSON (chaves ordenadas, espaços ignorados) para comparar chamadas. */
    private fun normArgs(raw: String): String {
        fun canon(e: kotlinx.serialization.json.JsonElement): String = when (e) {
            is kotlinx.serialization.json.JsonObject -> e.entries.sortedBy { it.key }.joinToString(",", "{", "}") { "\"" + it.key + "\":" + canon(it.value) }
            is kotlinx.serialization.json.JsonArray -> e.joinToString(",", "[", "]") { canon(it) }
            else -> e.toString()
        }
        return runCatching { canon(kotlinx.serialization.json.Json.parseToJsonElement(raw)) }.getOrElse { raw.trim() }
    }

    /** Detectores puros de loop/degeneração (sem Android) — testáveis na JVM. */
    companion object LoopGuards {
        private val ACTION_INTENT = Regex(
            "(?i)\\b(vou|irei|vamos|deixa eu|deixe-me|agora vou|primeiro vou|em seguida vou|let me|i['’ ]?ll|i will|i'?m going to|going to|gonna)\\b" +
                "[^.\\n]{0,45}?\\b(pesquis|busc|procur|verific|chec|test|analis|olh|consult|abrir|abro|naveg|acess|execut|rod|cria|crio|ger|baix|instal|configur|valid|compil|ler|leia|list|escrev|salv|implement|corrig|investig|continu|search|fetch|open|navigat|run|execut|check|download|install|creat|read|writ|analyz|look)")

        /**
         * O texto ANUNCIA uma ação que deveria usar uma ferramenta ("vou pesquisar/verificar/testar/abrir/rodar…"),
         * em PT ou EN? Serve para a regra de ação obrigatória: anúncio de ação sem chamada de ferramenta = sem progresso.
         * Conservador de propósito: só dispara em verbos de ação que exigem ferramenta (não em "vou te explicar/resumir").
         */
        fun hasActionIntent(text: String): Boolean = ACTION_INTENT.containsMatchIn(text)

        /** Semelhança grosseira entre dois textos (para detectar repetição/degeneração do modelo). */
        fun similar(a: String, b: String): Boolean {
            if (b.isEmpty()) return false
            if (a == b) return true
            val wa = a.lowercase().split(Regex("\\s+")).toSet()
            val wb = b.lowercase().split(Regex("\\s+")).toSet()
            if (wa.isEmpty() || wb.isEmpty()) return false
            val inter = wa.intersect(wb).size.toDouble()
            return inter / maxOf(wa.size, wb.size) > 0.8
        }
    }

    private fun autonomyTick() {
        val now = System.currentTimeMillis()
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        store.state.value.agents.filter { it.autonomous && it.homeSessionId != null }.forEach { a ->
            // requireDue=true: o ticker verifica o intervalo; o alarme (requireDue=false) é o backup exato.
            if (canBeat(a, now, hour, requireDue = true)) heartbeat(a)
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
                // Watchdog: um ciclo nunca pode travar para sempre e bloquear os próximos agendamentos.
                kotlinx.coroutines.withTimeout(10 * 60_000L) { loop(sid) }
            } catch (to: kotlinx.coroutines.TimeoutCancellationException) {
                runCatching { dev.cueopen.app.diag.ErrorLog.log("heartbeat", "Ciclo excedeu 10 min e foi encerrado (watchdog).", sid, "heartbeat") }
                store.addItem(sid, ChatItem.Error(text = "Ciclo excedeu o tempo máximo (10 min) e foi encerrado para liberar o próximo agendamento."))
            } catch (c: kotlinx.coroutines.CancellationException) { throw c
            } catch (t: Throwable) {
                runCatching { dev.cueopen.app.diag.ErrorLog.log("heartbeat", "${t.javaClass.simpleName}: ${t.message.orEmpty()}", sid, "heartbeat") }
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
        val sessionId = dev.cueopen.app.SessionNotificationRouting.validSessionId(a.homeSessionId)
        val key = sessionId ?: "invalid-agent-session:${a.id}"
        val intent = android.content.Intent(ctx, dev.cueopen.app.MainActivity::class.java)
            .setAction(dev.cueopen.app.SessionNotificationRouting.ACTION_OPEN_SESSION)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
        sessionId?.let {
            intent.putExtra(dev.cueopen.app.SessionNotificationRouting.EXTRA_SESSION, it)
                .putExtra(dev.cueopen.app.SessionNotificationRouting.EXTRA_SESSION_ID, it)
                .setData(android.net.Uri.parse(dev.cueopen.app.SessionNotificationRouting.deepLink(it)))
        }
        val stableId = dev.cueopen.app.SessionNotificationRouting.stableId(key)
        val pi = android.app.PendingIntent.getActivity(ctx, stableId, intent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(stableId, android.app.Notification.Builder(ctx, dev.cueopen.app.CueApp.CH_TASKS)
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
    suspend fun createAgentFromPrompt(desc: String, conversationId: String? = null): AgentProfile {
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
            """.trimIndent(), maxTokens = 600, conversationId = conversationId)
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
        val sleepingSessions = store.state.value.sessions.filter { it.status == SessionStatus.SLEEPING || (it.continuous && it.status == SessionStatus.STOPPED && it.wakeAt != null) }
        if (sleepingSessions.isNotEmpty()) dev.cueopen.app.sandbox.SandboxService.start(ctx, "Agente em modo contínuo")
        sleepingSessions.forEach { s ->
            if (jobs[s.id]?.isActive == true) return@forEach
            val wake = s.wakeAt ?: System.currentTimeMillis()
            jobs[s.id] = scope.launch {
                try {
                    while (System.currentTimeMillis() < wake) kotlinx.coroutines.delay(minOf(30_000L, wake - System.currentTimeMillis()).coerceAtLeast(1000))
                    val tradeIds = tradeIdsFromWakeReason(s.wakeReason)
                    wakeUp(s.id)
                    if (tradeIds.isNotEmpty()) checkExpiredTradesOnce(s.id, tradeIds)
                    loop(s.id)
                } catch (c: kotlinx.coroutines.CancellationException) { throw c
                } catch (t: Throwable) {
                    runCatching { dev.cueopen.app.diag.ErrorLog.log("agent", "retomada contínua: ${t.javaClass.simpleName}: ${t.message.orEmpty()}", s.id, "resume") }
                    store.addItem(s.id, ChatItem.Error(text = t.message ?: t.toString()))
                    store.editSession(s.id) { it.copy(status = SessionStatus.ERROR) }
                } finally { setActivity(s.id, Activity()) }
            }
        }
    }

    // ------------------------------------------------------------------ TypeSafe Jev (orquestração)
    private fun openRouterBase() = store.settings.baseUrl.contains("openrouter", true)

    /** Modelo do passo: o do Agent; senão Jev Router (se ligado); senão o padrão. */
    private fun stepModel(agent: AgentProfile?): String =
        JevOrchestrator.routeModel(agent?.model, store.settings.model, store.settings.jevRouting, openRouterBase())

    private fun toolName(o: kotlinx.serialization.json.JsonElement): String =
        runCatching { o.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content }.getOrDefault("")

    private fun jevQuery(session: Session): String {
        val users = session.llm.filter { it.role == "user" && it.content?.startsWith("[Sistema]") != true }.takeLast(3)
        return users.joinToString(" ") { it.content.orEmpty().take(1500) } + " " + session.summary.take(800) + " " + session.title
    }

    /** Plano do passo + esquema de ferramentas já filtrado. */
    private fun jevPlan(sessionId: String, s: Session, all: kotlinx.serialization.json.JsonArray): Pair<JevOrchestrator.Plan, kotlinx.serialization.json.JsonArray> {
        val names = all.map { toolName(it) }
        val hub = runCatching { dev.cueopen.app.CueApp.instance.uiConnectors }.getOrNull()
        val conns = hub?.connectors.orEmpty().filter { c -> names.any { it.startsWith("uic_${c.spec.id}_") } }
            .associate { it.spec.id to (it.spec.name to it.spec.description) }
        val mcp = names.filter { it.startsWith("mcp_") && it.contains("__") }.map { it.substringBefore("__") }.distinct()
            .associateWith { it.removePrefix("mcp_") }
        val used = s.items.filterIsInstance<ChatItem.Step>().map { it.tool }.toSet()
        val plan = JevOrchestrator.plan(JevOrchestrator.Input(
            sessionId = sessionId, query = jevQuery(s), usedTools = used, builder = s.connectorBuilder,
            connectors = conns, mcpServers = mcp,
            historyTokens = ctxMgr.estimateTokens(s.llm) + ctxMgr.estimateTokens(s.summary),
            contextBudget = store.settings.contextBudget, enabled = store.settings.jevOrchestration,
        ))
        val connIdOf = { n: String -> conns.keys.firstOrNull { n.startsWith("uic_${it}_") } }
        val mcpOf = { n: String -> n.substringBefore("__").takeIf { n.contains("__") } }
        val kept = buildJsonArray { all.forEach { t -> if (plan.keepTool(toolName(t), connIdOf, mcpOf)) add(t) } }
        return plan to kept
    }

    private fun systemPrompt(
        sessionId: String,
        agent: AgentProfile?,
        session: Session,
        jplan: JevOrchestrator.Plan? = null,
        allowDeviceTools: Boolean = true,
    ): String {
        val st = store.state.value
        val lastUser = session.llm.lastOrNull { it.role == "user" }?.content.orEmpty()
        val query = lastUser + " " + session.summary.take(1500)
        // Skills: as mais relevantes ao pedido primeiro, com teto (o índice inteiro não precisa ir em todo passo)
        val qWords = JevOrchestrator.norm(query).split(Regex("[^a-z0-9]+")).filter { it.length >= 4 }.toSet()
        val skills: List<Skill> = st.skills.filter { sk ->
            sk.enabled && (agent == null || agent.skillIds.isEmpty() || sk.id in agent.skillIds)
        }.sortedByDescending { sk -> JevOrchestrator.norm(sk.name + " " + sk.description).split(Regex("[^a-z0-9]+")).count { it in qWords } }
            .take(jplan?.skillsShown ?: Int.MAX_VALUE)
        val memories = ctxMgr.relevantMemories(query, agent?.id, maxChars = jplan?.memoryChars ?: 2400).also { ctxMgr.touch(it) }
        val knowledge = ctxMgr.relevantKnowledge(query, maxChars = jplan?.knowledgeChars ?: 4500)
        val notes = ctxMgr.readNotes(sessionId)
        val date = SimpleDateFormat("EEEE, d 'de' MMMM 'de' yyyy", Locale("pt", "BR")).format(Date())
        return buildString {
            // ---- parte estável (favorece cache de prompt) ----
            appendLine("Você é ${agent?.name ?: "Cue"}, um agente autônomo de uso geral rodando no app Fluwy Bot (Android).")
            appendLine("Data: $date. Usuário: ${st.settings.userName.ifBlank { "não informado" }}.")
            appendLine()
            appendLine("Você tem um COMPUTADOR próprio:")
            appendLine("- Terminal Linux (Alpine, via proot) — ferramenta `shell`. Workspace da tarefa: ${sandbox.guestPath(sessionId)}. Instale pacotes com `apk add`, `pip install`, `npm i`. O usuário vê cada comando ao vivo.")
            appendLine("- Browser real (WebView) — ferramentas browser_* (inclui browser_network e browser_console para depurar).")
            appendLine("- Arquivos — file_read / file_write / file_edit / file_list.")
            appendLine()
            appendLine("Modo de trabalho:")
            appendLine("1. Tarefas com várias etapas: chame `update_plan` e atualize ao concluir fases.")
            appendLine("2. Aja com ferramentas em vez de descrever. Verifique resultados.")
            appendLine("2z. AÇÃO OBRIGATÓRIA: NUNCA anuncie um passo (\"vou pesquisar/verificar/testar/abrir/rodar…\") sem, na MESMA resposta, emitir a chamada de ferramenta correspondente. Anúncio sem ferramenta = desperdício e vira loop. Se não vai usar ferramenta, entregue a resposta final direto (sem anunciar) ou chame finish. Não repita a mesma frase de intenção.")
            appendLine("2a. ENTREGA: nunca diga que algo foi feito/funciona sem evidência real da ferramenta (saída, arquivo, status). Antes de `finish`, confira o resultado (ls/file_read/execução). Se não conseguiu validar, diga isso claramente em vez de afirmar sucesso.")
            appendLine("2b. ERRO: se uma ferramenta falhar, leia a mensagem, diagnostique e MUDE a abordagem (outro caminho/comando/argumento). Nunca repita a mesma chamada que falhou. Se ficar bloqueado, finalize explicando o motivo em vez de entrar em loop.")
            appendLine("3. Economize contexto: leia só o necessário (head/grep), mande saídas longas para arquivo.")
            appendLine("3a. Trabalhe APENAS no seu workspace (${sandbox.guestPath(sessionId)}); não leia /root/workspace de outras tarefas.")
            appendLine("3b. Arquivos: para alterar um trecho de arquivo existente, prefira file_edit com old_text e new_text; exige correspondência única e não faz substituição aproximada. Use allow_multiple só quando todas as ocorrências devam mudar. Use file_write para criar/sobrescrever arquivo inteiro; arquivos grandes em partes com append=true. Depois confira com file_read.")
            appendLine("3c. Ao gerar imagem/SVG/HTML/PDF, mostre ao usuário com `show_file`.")
            appendLine("3d. Com plano ativo, chame `complete_step` ao concluir CADA fase, antes de seguir para a próxima.")
            appendLine("3e. EFICIÊNCIA DE CONTEXTO: busque primeiro símbolos/termos e leia apenas trechos necessários; limite saídas; não despeje arquivos/logs inteiros no contexto. Preserve requisitos, decisões, evidências, caminhos, erros e pendências. Não alegue economia percentual sem medir uso real.")
            appendLine("3f. DELEGAÇÃO DE LEITURA: em investigações amplas com fontes independentes, use multi_agents com subtarefas delimitadas, escopo explícito e saída curta (conclusões + caminho/linhas/URLs + incertezas). Não delegue leituras triviais; valide evidências críticas na fonte principal. Se a ferramenta não estiver disponível, não afirme que delegou.")

            appendLine("4. Anote em `notes_write` o que precisa lembrar durante a tarefa (achados, progresso, próximos passos).")
            appendLine("5. Fatos duradouros sobre o usuário/projeto: `memory_save`. Para lembrar algo antigo: `memory_search`.")
            appendLine("6. Para usar uma skill, chame `skill_load` com o nome e siga as instruções.")
            appendLine("6b. AUTONOMIA: se o usuário pedir para monitorar/acompanhar/repetir algo ao longo do tempo (ex.: \"a cada 20 min verifique o mercado\"), chame set_schedule com o intervalo em minutos. Se ele não der intervalo mas a tarefa for contínua, escolha um intervalo razoável e econômico. Para parar, set_schedule minutes=0.")
            val requestedCaps = if (allowDeviceTools) agent?.deviceCaps.orEmpty() else emptyList()
            val grantedCaps = requestedCaps.filter { dev.cueopen.app.device.DeviceCaps.granted(ctx, it) }
            val allDeviceTools = dev.cueopen.app.device.DeviceCapPolicy.availableTools(requestedCaps, grantedCaps)
            val deviceGroupInContext = jplan == null || "device" in jplan.groups
            val promptDeviceTools = if (deviceGroupInContext) allDeviceTools else emptySet()
            dev.cueopen.app.device.DeviceCapPolicy.agentGuidance(promptDeviceTools).forEach { appendLine("6c. $it") }
            if (deviceGroupInContext) {
                val missingGrants = requestedCaps.filter { !dev.cueopen.app.device.DeviceCaps.granted(ctx, it) }
                if (missingGrants.isNotEmpty()) appendLine("6c. Capacidades ligadas no perfil, mas sem autorização Android vigente: " + missingGrants.joinToString(", ") { cap -> dev.cueopen.app.device.DeviceCaps.all.firstOrNull { it.id == cap }?.title ?: cap } + ". Informe a permissão necessária; não use shell, browser, notificações ou outro caminho para contorná-la.")
            }
            appendLine("6c. IQ OPTION: use apenas as ferramentas manuais expostas pelo conector para login, ativos abertos, candles, abrir opção/Forex, fechar Forex e consultar posições/resultados. Não há scanner, estratégia nem execução automática nesse conector; não invente ferramentas removidas nem transforme candles em uma ordem automática.")
            appendLine("7. MODO CONTÍNUO: se o usuário pedir para monitorar, acompanhar, repetir ou rodar por horas/dias, NÃO finalize. Faça a verificação, registre em notes_write e chame `sleep` (minutos até a próxima verificação). Repita até o usuário pausar.")
            appendLine("8. Fora do modo contínuo, termine com `finish` (resumo curto + arquivos). Responda no idioma do usuário.")
            appendLine("8a. DÚVIDA REAL → `ask_question`: só quando o usuário pediu uma tarefa E existe uma dúvida verdadeira que muda o resultado (ex.: qual de duas abordagens, público, formato, escopo) e errar custaria refazer muito trabalho ou algo irreversível. NÃO use para confirmar (\"posso começar?\"), para coisas que você descobre pesquisando/lendo arquivos, nem para preferências triviais — nesses casos assuma o mais razoável e diga o que assumiu. No máximo UMA vez por pedido, com 1–4 perguntas objetivas e 2–4 opções cada (\"Outra resposta\" já aparece). Depois das respostas, siga direto.")
            appendLine("8b0. DADOS SENSÍVEIS → `ask_secure`: para API key, token, e-mail/senha, dados de login ou de formulário, NUNCA peça para o usuário digitar no chat — chame ask_secure com os campos. Você nunca verá os valores; depois use o marcador {{secret:<nome>}} nos argumentos (shell, browser_type, browser_navigate, file_write, file_edit, ferramentas uic_*/mcp_*, connector_test login_json/settings_json/args_json) que o app troca pelo valor na execução. Nunca tente imprimir, ecoar ou adivinhar o valor. Se o usuário colar um segredo no chat, use-o só se necessário e recomende o formulário seguro.")
            SecretVault.names(sessionId).takeIf { it.isNotEmpty() }?.let { appendLine("8b1. Dados seguros já disponíveis nesta tarefa (use o marcador, sem pedir de novo): " + it.joinToString { n -> "{{secret:$n}}" }) }
            appendLine("8b. ELEMENTOS CueUI: quando um elemento interativo ajudar na resposta (opções para o usuário tocar, gráfico, calculadora, tabela, estatísticas, passos, barra de progresso, aviso, chave→valor), inclua um bloco ```cueui com UM objeto JSON `{\"type\": ...}`. O chat renderiza nativo; NÃO escreva o JSON como texto nem explique o bloco. Tipos e campos: " +
                "buttons {items:[{label, send?|copy?|url?, tone?}]} (send = texto enviado a você ao tocar); " +
                "chart {title?, style:\"bar\"|\"line\", labels?:[...], series:[{name?, values:[n...], color?}]}; " +
                "calculator {title?, expr:\"a*b+c\", unit?, vars:[{id, label?, default, min?, max?, step?}]} (expr usa os ids das vars; cálculo nativo seguro); " +
                "table {title?, columns:[...], rows:[[...],...]}; stats {title?, items:[{label, value, tone?}]}; steps {title?, items:[\"...\"]}; " +
                "progress {title?, items:[{label, value:0-100, tone?}]}; callout {tone:\"info\"|\"good\"|\"warn\"|\"bad\", title?, text}; keyvalue {title?, items:[{key, value}]}; connector {id:\"iqoption\", title?, collapsed?} (painel AO VIVO do conector dentro do chat — saldo/operações — que o usuário pode recolher). " +
                "tone ∈ neutral|good|warn|bad|accent. Use com moderação, só quando agrega; texto normal continua em Markdown.")
            val walletPolicyForPrompt = WalletAgentPolicyStore(ctx.applicationContext)
            val walletHasGrants = agent != null && (
                walletPolicyForPrompt.canAgentReadAddress(agent.id) ||
                    walletPolicyForPrompt.canAgentReadBalance(agent.id) ||
                    walletPolicyForPrompt.canAgentRequestTransfer(agent.id) ||
                    walletPolicyForPrompt.canAgentDayTrade(agent.id)
                )
            appendLine()
            appendLine("## Segurança da carteira Solana (regra obrigatória)")
            appendLine("- Para operações de carteira, use exclusivamente ferramentas wallet_* disponibilizadas nesta sessão. Nunca use shell, terminal, browser, curl, Solscan ou ferramentas externas como fallback, mesmo quando uma ferramenta falhar.")
            appendLine("- Nunca peça, leia, copie, transmita ou revele frase de recuperação, chave privada ou material de assinatura. Nunca assine ou transmita transações por terminal.")
            appendLine("- Se uma ferramenta de carteira não estiver disponível, informe que a permissão precisa ser habilitada nas configurações deste agente; não tente contornar a autorização.")
            if (walletHasGrants) {
                appendLine("- Use somente as capacidades wallet_* explicitamente habilitadas para este agente.")
                appendLine("- Se wallet_get_balance retornar WALLET_RPC_ERROR, rede inválida ou carteira indisponível, explique a falha e peça para o usuário verificar a tela Carteira; não invente saldo nem afirme que a conta/chave é inválida.")
                appendLine("- Um saldo RPC de 0 SOL não prova que a carteira é inválida; informe que o endereço pode não ter saldo on-chain nessa rede.")
                if (agent != null && walletPolicyForPrompt.canAgentAutonomousTransfer(agent.id)) {
                    appendLine("- Transferência automática está ATIVADA para este agente: wallet_request_transfer pode assinar e transmitir sozinho, dentro dos limites e da allowlist. Só relate 'enviado'/'confirmado' com base no retorno real da ferramenta (assinatura/Explorer); nunca invente confirmação.")
                } else {
                    appendLine("- wallet_request_transfer apenas cria uma solicitação pendente; nunca diga que os fundos foram enviados antes da aprovação humana e confirmação on-chain.")
                }
                if (agent != null && walletPolicyForPrompt.canAgentDayTrade(agent.id)) {
                    appendLine()
                    appendLine("## Day trade (capacidade habilitada)")
                    appendLine("- Dados de mercado são 100% reais (sem API key): market_discover (tokens de todo o ecossistema Solana, novos→velhos ou em alta, com liquidez e volume), market_token_info (preço/liquidez/volume/market cap de um token), market_price (preço USD ao vivo), market_candles (OHLCV real do pool de maior liquidez), market_liquidity (impacto real por tamanho — AMM não tem order book central). Nunca invente preços, velas ou profundidade; se uma ferramenta retornar MARKET_ERROR, relate o erro e não contorne com shell/curl.")
                    appendLine("- FLUXO OBRIGATÓRIO: (1) descubra oportunidades com market_discover e cheque market_token_info; (2) analise preço/tendência com market_candles; (3) meça liquidez/impacto com market_liquidity (confirme que dá para comprar E vender); (4) SEMPRE chame typesafe_jev_check (venue solana, direction buy ou sell) com os dados reais ANTES de CADA compra e CADA venda e entre APENAS nos sinais ✅ OPERAR; (5) execute com wallet_swap — o app RECUSA qualquer swap sem aprovação válida (uso único, 5 min, mesmo lado); (6) proteja a posição com trade_set_rule (stop-loss price_below e/ou take-profit price_above: regras automáticas são só de SAÍDA); (7) a cada ciclo chame trade_list para avaliar e disparar os gatilhos no preço real.")
                    appendLine("- A validação typesafe_jev_check é IMPOSTA pelo app (não é opcional) para compra e venda manuais via wallet_swap. As saídas automáticas de stop-loss/take-profit e o trailing stop (trade_update_rule) são proteção e dispensam nova validação. A acurácia vem de dados reais; não invente probabilidades.")
                    appendLine("- Se market_candles retornar 'tendência real alternativa' (DexScreener: variações m5/h1/h6/h24, volume, fluxo compra/venda), isso é dado REAL e suficiente para analisar a tendência — use-o normalmente, não repita a chamada em loop.")
                    appendLine("- STOP-LOSS / TAKE-PROFIT são AUTOMÁTICOS: ao abrir uma posição, arme imediatamente trade_set_rule para o stop-loss (price_below) e para o take-profit (price_above). O sistema monitora o preço real sozinho (a cada ~20s) e FECHA a operação automaticamente quando o alvo é atingido — mesmo entre os seus ciclos. Você NÃO precisa ficar acordando para fechar; só arme as regras uma vez.")
                    appendLine("- TRAILING STOP: a cada ciclo, se o preço subiu bem acima da sua entrada, use trade_update_rule para MOVER o stop-loss (regra price_below) para cima e travar lucro. Ex.: entrada US$ 0,55, preço foi a US$ 0,58 → mova o stop-loss para US$ 0,56; se cair, a operação fecha sozinha no lucro. Nunca mova o stop-loss para baixo (isso aumenta a perda). Vale para uma ou várias posições abertas.")
                    appendLine("- wallet_swap executa troca REAL e assinada localmente; entradas em SOL respeitam o limite por operação autorizado pelo usuário. Só diga 'enviado/executado' com base na assinatura retornada (Explorer); jamais afirme lucro ou execução sem a assinatura.")
                    appendLine("- Para monitorar o mercado de forma contínua, use set_schedule (ex.: a cada 5-15 min) e, a cada ciclo, reavalie com market_* e trade_list. Seja econômico e honesto sobre risco: day trade pode gerar perdas; nunca prometa retorno.")
                }
            } else {
                appendLine("- Este agente não possui permissões de carteira concedidas. Não tente consultar nem movimentar fundos; peça ao usuário para habilitar somente as capacidades necessárias nas configurações do agente.")
            }
            if (session.connectorBuilder) appendLine()
            if (session.connectorBuilder) appendLine("## Criar UI Connectors (esta conversa é o criador de conectores)")
            if (session.connectorBuilder) appendLine("- Fluxo obrigatório: chame connector_spec, crie JSON declarativo, use connector_validate até zero erros, depois connector_test e só então connector_propose. connector_test é um DRY-RUN OFFLINE: usa fixtures HTTP/WebSocket e filesystem virtual; nunca usa rede externa, credenciais reais, browser real, setup/comandos gerados ou código arbitrário. Operações file declarativas podem ser testadas no filesystem em memória; conector que dependa de shell/setup falha fechado até existir executor restrito. Segredos/marcadores não são necessários e nunca devem ser pedidos para validar. O relatório distingue mocks de verificações estruturais; nunca diga que uma API real, permissão, login ou execução/arquivo real foi verificado. Se aparecer ✗, corrija e teste novamente; connector_propose fica bloqueado até o mesmo JSON passar. Se falta URL ou esquema da API, pesquise documentação oficial ou pergunte. Para fontes sem endpoint público autorizado/estável, explique a limitação e NÃO proponha scraping nem contorno de login/CAPTCHA.")
            if (session.connectorBuilder) appendLine("- Tempo real e ações externas: realtime/HTTP são conferidos estruturalmente e por transporte simulado; nenhuma mensagem, ordem, publicação ou compra é enviada no teste. Use apenas protocolo documentado oficialmente, risky/limits/requiresSignal quando aplicável, e deixe claro que o handshake real ainda precisa de autorização.")
            if (session.connectorBuilder) appendLine("- CAPACIDADES DO APARELHO (terminal, arquivos, browser): o dry-run usa filesystem e browser substitutos; comandos, scripts, setup e páginas reais NÃO são executados/abertos antes da instalação. Prefira APIs oficiais e compute/file declarativo; declare os limites e não afirme que código de shell ou artefatos gerados dinamicamente passaram em execução real. Scraping de Facebook/Meta Ads Library é bloqueado; somente uma API oficial autorizada poderia ser considerada, e o dry-run offline não comprova token/permissões, portanto não proponha sem validação autorizada posterior.")
            if (session.connectorBuilder) appendLine("- Geradores de copy/VSL: se o conector chamar uma API de geração textual, use outputPolicy.type=vsl; envie duração, idioma e guardrails na requisição, declare 3–12 seções obrigatórias e, quando o usuário der termos proibidos, use forbiddenTermsParam. A validação verifica contagem estimada (WPM × minutos com tolerância), idioma por heurística local (PT/EN/ES/FR), seções e termos proibidos; não é uma garantia de duração falada nem uma revisão semântica de fatos/alegações.")
            if (session.connectorBuilder) appendLine("- CÁLCULOS (indicadores técnicos, estatística, sinais, tabelas derivadas, conversões): use o bloco compute do SDK — expressões com a biblioteca de funções do app (sma, ema, rsi, macd, bollinger, stoch, adx, ichimoku, supertrend, psar, vwap… e map/filter/reduce/zip_rows). Busque os dados reais (ex.: velas) com request/source e calcule tudo no compute; nunca peça ao usuário para calcular nem invente valores. Pedidos como 'todos os indicadores do TradingView' = um conector com compute cobrindo médias, osciladores, volatilidade, tendência e volume, com sinal e resumo (veja o exemplo de indicadores na especificação).")
            runCatching {
                val hub = dev.cueopen.app.CueApp.instance.uiConnectors
                val usable = hub.connectors.filter { hub.usableBy(it, agent) }
                if (usable.isNotEmpty()) {
                    appendLine()
                    appendLine("## UI Connectors disponíveis")
                    usable.forEach { uc ->
                        val st = if (uc.isLoggedIn || hub.hasSavedCredentials(uc.spec.id)) "conectado" else "sem login — peça ao usuário para entrar em Conectores → ${uc.spec.name}"
                        appendLine("- ${uc.spec.name} (${st}): ferramentas uic_${uc.spec.id}_* — ${uc.spec.description}. Use-as sempre que o pedido envolver este serviço.")
                    }
                    if (hub.hasTradingTools(agent)) {
                        // conectores de ordem que dispensam o Jev (setting require_jev OFF) vs. os que exigem
                        val noJev = usable.filter { it.spec.tools.any { t -> t.requiresSignal } && it.spec.settings.any { s2 -> s2.id == "require_jev" } && !hub.settings(it).bool("require_jev") }.map { it.spec.name }
                        val needJev = usable.filter { it.spec.tools.any { t -> t.requiresSignal } && (it.spec.settings.none { s2 -> s2.id == "require_jev" } || hub.settings(it).bool("require_jev")) }.map { it.spec.name }
                        if (needJev.isNotEmpty()) appendLine("- ORDENS em ${needJev.joinToString()}: ANTES de CADA nova ordem chame typesafe_jev_check (venue = id do conector, id = ticker, direction call/put para opções ou buy/sell para Forex) e só opere com ✅ OPERAR; o app recusa ordens sem validação (uso único, 5 min). Siga o lado permitido pela ferramenta e não aplique call/put a Forex.")
                        if (noJev.isNotEmpty()) appendLine("- ORDENS em ${noJev.joinToString()}: TypeSafe Jev está desligado — não chame typesafe_jev_check. Use somente as ferramentas manuais expostas e respeite todas as salvaguardas e limites do conector; isso não ativa estratégia nem execução automática.")
                        appendLine("- Solana day trade e conectores de trading têm capital e posições independentes: não use um para cobrir o outro. Se a resposta disser que outro agente está operando o ativo, aguarde ou escolha outro; faça uma ordem por vez.")
                    }
                    appendLine("- A sessão e as credenciais são gerenciadas pelo app; você nunca vê nem pede senha. Se uma ferramenta responder que é preciso fazer login, avise o usuário.")
                    appendLine("- ARQUIVOS DE CONECTORES: um conector que usa terminal/arquivos guarda o que gera numa pasta própria visível para você em /root/connectors/<id do conector>/workspace (ex.: /root/connectors/ytbaixar/workspace). Você PODE ler, listar e copiar de lá: ex. `ls /root/connectors/ytbaixar/workspace` e `cp /root/connectors/ytbaixar/workspace/<arquivo> /root/workspace/` para trazer ao seu workspace e entregar ao usuário. Se uma capacidade (terminal/arquivos/browser) do conector não estiver autorizada, a ferramenta responde pedindo para o usuário ativar em Conectores → (conector) → Permissões.")
                    appendLine("- Respostas começando com RECUSADO são limites de segurança definidos pelo usuário: respeite-os, não tente contornar (nem trocar de conta/valor para burlar) e explique ao usuário. Operar envolve risco de perda; nunca prometa lucro.")
                }
            }
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
            jplan?.let { p ->
                val names = runCatching { dev.cueopen.app.CueApp.instance.uiConnectors.connectors.associate { it.spec.id to it.spec.name } }.getOrDefault(emptyMap())
                p.index(names, emptyMap()).takeIf { it.isNotBlank() }?.let { appendLine(); appendLine(it) }
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

    private fun tools(agent: AgentProfile?, allowMultiAgent: Boolean = true, builder: Boolean = false) = buildJsonArray {
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
            add(tool("shell", "Executa um comando bash no sandbox Linux (Alpine). Retorna stdout+stderr e o exit code do comando. IMPORTANTE: em sequências, o exit code normal é o do último comando; para que uma etapa com falha interrompa a sequência, use && em vez de ;. Em pipelines, use set -o pipefail quando a falha de qualquer etapa importar. Não masque falhas com cat/echo após um comando separado por ;: confira o exit code da operação principal. GUARDRAILS: antes de executar bash/python/node com caminho de script, confirme que o arquivo existe com file_list/file_read; nunca invente nomes .sh ou caminhos. Antes de cd, confirme que o diretório existe. Se uma execução anterior retornou ENOENT/No such file or directory, não repita o mesmo comando: inspecione o workspace e corrija o caminho.", buildJsonObject {
                put("command", str("Comando a executar"))
                put("timeout", int("Timeout em segundos (padrão 180)"))
                put("background", bool("Rodar em segundo plano (servidores)"))
            }, listOf("command")))
        }
        add(tool("file_write", "Cria/sobrescreve um arquivo de texto (cria as pastas). Arquivos grandes: escreva em partes com append=true (~150 linhas por chamada).", buildJsonObject {
            put("path", str("Caminho do ARQUIVO (relativo ao workspace ou absoluto)")); put("content", str("Conteúdo")); put("append", bool("true = acrescenta ao final"))
        }, listOf("path", "content")))
        add(tool("file_edit", "Substitui um trecho literal em arquivo existente dentro do workspace da tarefa. old_text/new_text são texto cru; a ferramenta tenta literal primeiro e aceita apenas diferenças de whitespace como fallback. Recusa se não houver match único, se houver ambiguidade ou se o arquivo exceder 8 MB. allow_multiple=false por padrão; true altera todas as ocorrências. A gravação é atômica.", buildJsonObject {
            put("path", str("Arquivo existente, relativo ao workspace (ou /root/workspace/...)"))
            put("old_text", str("Trecho exato a localizar; use \\n para quebras de linha no argumento JSON"))
            put("new_text", str("Texto de substituição; pode ser vazio para remover o trecho"))
            put("allow_multiple", bool("true somente se quiser substituir todas as ocorrências; padrão false"))
        }, listOf("path", "old_text", "new_text")))
        add(tool("complete_step", "Marca a próxima fase do plano como concluída. Chame SEMPRE ao terminar cada fase.", buildJsonObject { put("note", str("O que foi feito")) }))
        add(tool("show_file", "Mostra um arquivo gerado (imagem, SVG, HTML, PDF, código) direto no chat para o usuário.", buildJsonObject {
            put("path", str("Caminho do arquivo")); put("caption", str("Legenda curta"))
        }, listOf("path")))
        add(tool("file_read", "Lê um arquivo de texto POR PARTES, com números de linha. Arquivos grandes: leia em sequência usando offset (o resultado diz a próxima linha). Não releia trechos que você já leu.", buildJsonObject {
            put("path", str("Caminho")); put("offset", int("Linha inicial (1 = início; padrão 1)")); put("limit", int("Quantidade de linhas (padrão 250, máx. 1000)"))
        }, listOf("path")))
        add(tool("file_list", "Lista arquivos de um diretório.", buildJsonObject { put("path", str("Diretório (padrão: workspace)")) }))
        if (web) {
            add(tool("browser_navigate", "Abre uma URL (ou termos de busca) no browser. Erro HTTP 4xx/5xx na página principal é falha: não use o conteúdo nem repita a mesma URL; localize um endereço válido.", buildJsonObject { put("url", str("URL ou busca")) }, listOf("url")))
            add(tool("browser_snapshot", "Gera snapshot semântico da página atual com headings, conteúdo, links e controles nomeados; refs/índices expiram após navegação.", buildJsonObject { put("max_chars", int("Limite de caracteres (padrão 9000)")) }))
            add(tool("browser_read", "Compatibilidade: lê o snapshot e o texto da página atual.", buildJsonObject {}))
            add(tool("browser_click", "Clica no elemento [índice] de browser_read. Os índices expiram quando o DOM muda: leia a página imediatamente antes do clique; se o elemento não existir, releia e use um índice novo, sem repetir o antigo.", buildJsonObject { put("index", int("Índice")) }, listOf("index")))
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
        if (builder) add(tool("connector_spec", "Retorna a especificação COMPLETA do formato JSON de UI Connectors do CueOpen (esquema, regras, filtros, exemplos). Chame ANTES de escrever um conector.", buildJsonObject {}))
        if (builder) add(tool("connector_validate", "Valida uma definição JSON de UI Connector com o parser real do app. Retorna OK ou a lista exata de erros (caminho + motivo). Corrija e valide de novo até zero erros.", buildJsonObject {
            put("json", str("Definição JSON completa do conector"))
        }, listOf("json")))
        if (builder) add(tool("connector_test", "Executa dry-run OFFLINE com transporte HTTP/WebSocket e filesystem em mocks; testa schema, guardrails, templates, renderização e políticas de saída. Nunca chama APIs reais, envia ações, abre browser ou executa setup/shell/código gerado; conectores que dependem de shell/setup falham fechado. Para VSL, valida duração estimada, idioma, ordem das seções e termos proibidos. Obrigatório passar antes de connector_propose; o resultado não comprova login/permissões ou disponibilidade real do serviço.", buildJsonObject {
            put("json", str("Definição JSON completa do conector"))
            put("settings_json", str("Opcional: valores de exemplo não secretos para as configurações (settings)"))
            put("args_json", str("Exemplos de entrada não secretos por ferramenta (opcional; se omitidos, o teste usa valores sintéticos), ex.: {\"buscar\":{\"termo\":\"amostra\",\"tipo\":\"artist\"}}"))
        }, listOf("json")))
        if (builder) add(tool("connector_propose", "Mostra ao usuário o card 'Instalar conector' no chat. Só aceita definição que passou pelo dry-run offline com o MESMO JSON e possui atestado válido; o card não alegará que serviço real foi conectado.", buildJsonObject {
            put("json", str("Definição JSON completa e válida do conector"))
        }, listOf("json")))
        if (allowMultiAgent) add(tool("sleep", "Modo contínuo: pausa e depois retoma automaticamente. Use UM modo: minutes, seconds, ou align_candle_seconds. Use seconds para esperas curtas e minutes para verificações recorrentes.", buildJsonObject {
            put("minutes", int("Minutos até a próxima verificação (1-1440)"))
            put("seconds", int("Segundos até a próxima verificação (1-86400) — para esperas curtas e precisas"))
            put("align_candle_seconds", int("Intervalo em segundos para alinhar a próxima verificação a um período"))
            put("lead_seconds", int("Com align_candle_seconds: quantos segundos antes da abertura acordar (padrão 10, 2-30)"))
            put("reason", str("O que fazer ao acordar"))
        }, listOf("reason")))
        if (allowMultiAgent) add(tool("create_skill", "Cria uma nova skill na biblioteca do usuário.", buildJsonObject {
            put("name", str("Nome")); put("description", str("Descrição curta")); put("instructions", str("Instruções detalhadas"))
        }, listOf("name", "description", "instructions")))
        if (allowMultiAgent) add(tool("set_schedule", "Faça o AGENTE trabalhar de forma autônoma e recorrente. Chame quando o usuário pedir para monitorar/acompanhar/repetir algo ao longo do tempo. Defina minutes = intervalo entre verificações (ex.: 20). Use minutes=0 para parar a autonomia.", buildJsonObject {
            put("minutes", int("Minutos entre verificações; 0 para parar")); put("mission", str("O que verificar/fazer a cada ciclo"))
        }, listOf("minutes")))
        // Wallet tools are exposed only to the selected real agent and only for
        // explicitly persisted read grants. No secrets or signing primitives enter LLM context.
        if (allowMultiAgent && agent != null) {
            val walletPolicies = WalletAgentPolicyStore(ctx.applicationContext)
            if (walletPolicies.canAgentReadAddress(agent.id)) {
                add(tool("wallet_get_address", "Consulta o endereço público da carteira Solana local. Não retorna chave privada nem frase de recuperação.", buildJsonObject {}))
            }
            if (walletPolicies.canAgentReadBalance(agent.id)) {
                add(tool("wallet_get_balance", "Consulta o saldo SOL da rede selecionada na tela Carteira. A consulta é somente leitura.", buildJsonObject {}))
            }
            if (walletPolicies.canAgentRequestTransfer(agent.id)) {
                add(tool(
                    "wallet_request_transfer",
                    "Cria um pedido tipado de transferência SOL para aprovação humana. Nunca assina nem transmite; a carteira validará a política local e exigirá aprovação explícita.",
                    buildJsonObject {
                        put("recipient", str("Endereço público Solana do destinatário"))
                        put("amount_sol", str("Valor decimal em SOL, até 9 casas decimais"))
                    },
                    listOf("recipient", "amount_sol")
                ))
            }
            if (walletPolicies.canAgentDayTrade(agent.id)) {
                add(tool("market_price", "Consulta o PREÇO em USD (dados reais, ao vivo) de um ou mais tokens Solana pelo mint. Somente leitura.", buildJsonObject {
                    put("mints", strArray("Lista de endereços de mint (ex.: SOL=So111...112, USDC=EPjF...Dt1v)"))
                }, listOf("mints")))
                add(tool("market_candles", "Histórico OHLCV real (velas) do token no pool de maior liquidez na Solana (GeckoTerminal). Use para analisar tendência antes de operar.", buildJsonObject {
                    put("mint", str("Endereço de mint do token"))
                    put("timeframe", str("day, hour ou minute (padrão hour)"))
                    put("aggregate", int("Agrupa unidades base, ex.: hour+4 = velas de 4h (padrão 1)"))
                    put("limit", int("Quantidade de velas (1-1000; padrão 60)"))
                }, listOf("mint")))
                add(tool("market_liquidity", "Mede a PROFUNDIDADE/impacto de preço real de um par cotando tamanhos crescentes no agregador Jupiter. Em AMMs da Solana não existe order book central; esta é a medida honesta de liquidez.", buildJsonObject {
                    put("input_mint", str("Mint de entrada")); put("output_mint", str("Mint de saída"))
                    put("probe_amounts_raw", strArray("Tamanhos de entrada em unidades mínimas (lamports/unidades do token), ex.: [\"100000000\",\"1000000000\"]"))
                }, listOf("input_mint", "output_mint", "probe_amounts_raw")))
                add(tool("market_discover", "Descobre tokens de TODO o ecossistema Solana (dados reais, GeckoTerminal, sem API key). sort=new lista os recém-lançados do mais novo ao mais velho; sort=trending os em alta. Traz preço, liquidez (reserva) e volume 24h para achar oportunidades e validar se há liquidez para operar.", buildJsonObject {
                    put("sort", str("new (mais novos→mais velhos) ou trending (em alta). Padrão new"))
                    put("limit", int("Quantidade de tokens (1-100; padrão 30)"))
                }))
                add(tool("market_token_info", "Informações reais de UM token pelo mint: preço USD, liquidez total, volume 24h e market cap. Use para validar a possibilidade de comprar/vender (liquidez) antes de operar.", buildJsonObject {
                    put("mint", str("Endereço de mint do token"))
                }, listOf("mint")))
                add(tool("wallet_list_tokens", "Lista os tokens SPL que a carteira realmente possui (mint, saldo, decimais). Somente leitura.", buildJsonObject {}))
                add(tool("wallet_swap", "Executa uma TROCA REAL token→token via Jupiter, assinada localmente (a chave nunca entra no contexto). Só na Mainnet. Entradas em SOL respeitam o limite por operação autorizado. Relate 'enviado' só com a assinatura retornada.", buildJsonObject {
                    put("input_mint", str("Mint de entrada (o que você vende)"))
                    put("output_mint", str("Mint de saída (o que você compra)"))
                    put("amount_raw", str("Valor de entrada em unidades mínimas (lamports para SOL)"))
                    put("slippage_bps", int("Slippage em basis points (1-5000; padrão 100 = 1%)"))
                }, listOf("input_mint", "output_mint", "amount_raw")))
                add(tool("trade_set_rule", "Arma uma regra de day trade (stop-loss/take-profit/ordem limite): quando o preço USD do token observado cruzar o alvo, a troca é executada automaticamente dentro dos limites. Dados de preço 100% reais.", buildJsonObject {
                    put("label", str("Rótulo curto da estratégia"))
                    put("input_mint", str("Mint de entrada (o que será vendido ao disparar)"))
                    put("output_mint", str("Mint de saída (o que será comprado ao disparar)"))
                    put("amount_raw", str("Valor de entrada em unidades mínimas"))
                    put("watch_mint", str("Mint cujo preço USD é observado (normalmente o token da posição)"))
                    put("trigger", str("price_below (dispara quando cai até o alvo) ou price_above (quando sobe até o alvo)"))
                    put("target_price_usd", str("Preço-alvo em USD (decimal)"))
                    put("slippage_bps", int("Slippage em bps (padrão 100)"))
                }, listOf("label", "input_mint", "output_mint", "amount_raw", "watch_mint", "trigger", "target_price_usd")))
                add(tool("trade_list", "Lista as regras de day trade deste agente e o estado atual (armada/executada/falhou/cancelada), com último preço observado. Também AVALIA as regras armadas agora contra o preço real e dispara as que atingiram o alvo.", buildJsonObject {
                    put("evaluate", bool("true (padrão) = checar preços agora e disparar gatilhos atingidos; false = só listar"))
                }))
                add(tool("trade_cancel", "Cancela uma regra de day trade armada deste agente.", buildJsonObject {
                    put("rule_id", str("ID da regra"))
                }, listOf("rule_id")))
                add(tool("trade_update_rule", "Ajusta (move) uma regra de day trade ARMADA deste agente — use para TRAILING STOP: quando o preço sobe, mova o stop-loss para cima para travar lucro. Ex.: entrada 0,55, preço foi a 0,58 → mova o stop-loss para 0,56; se cair, sai no lucro. Só muda o preço-alvo (e opcionalmente o gatilho); a regra continua monitorada e executa sozinha.", buildJsonObject {
                    put("rule_id", str("ID da regra armada a ajustar"))
                    put("target_price_usd", str("Novo preço-alvo em USD (decimal)"))
                    put("trigger", str("Opcional: price_below ou price_above, caso precise mudar o sentido"))
                }, listOf("rule_id", "target_price_usd")))
            }
        }
        // Validação TypeSafe Jev: obrigatória antes de QUALQUER ordem (day trade Solana e conectores de trading).
        // Só oferece a ferramenta quando ela é realmente exigida: day trade Solana, ou um conector de ordem com o Jev LIGADO.
        if (allowMultiAgent && (agent?.let { WalletAgentPolicyStore(ctx.applicationContext).canAgentDayTrade(it.id) } == true ||
                runCatching { dev.cueopen.app.CueApp.instance.uiConnectors.jevRequiredBy(agent) }.getOrDefault(false))) {
            add(tool("typesafe_jev_check", "OBRIGATÓRIO antes de QUALQUER ordem de COMPRA ou VENDA (Solana day trade e IQ Option): avalia cada sinal com o avaliador de acurácia configurado. Jev usa System One; modelo de chat comum usa adapter JSON estrito e NÃO tem a calibração do Jev. Só se os pisos locais de acurácia e confiança forem atingidos o app cria autorização de uso único (5 min). Sem ela, wallet_swap e as ferramentas de ordem dos conectores são RECUSADOS. Envie dados REAIS (preço, candles fechados, tendência, liquidez). Não invente números. Para venue=iqoption o app anexa automaticamente candles fechados, resumo técnico, Price Action e gerenciamento — basta id, venue, direction e horizon_minutes.", buildJsonObject {
                put("signals_json", str("JSON array de sinais: {\"id\":\"<mint Solana ou ticker, ex.: EURUSD>\",\"venue\":\"solana|iqoption\",\"direction\":\"buy|sell (Solana) ou call|put (IQ Option)\",\"horizon_minutes\":5,\"data\":\"resumo com preço, velas OHLCV, tendência, liquidez\"}"))
                put("min_accuracy", int("Acurácia mínima (0-100); o piso de 70 não pode ser rebaixado"))
            }, listOf("signals_json")))
        }
        // Cada ferramenta exige opt-in do agente; leituras/recursos protegidos também exigem grant Android.
        val caps = if (allowMultiAgent) agent?.deviceCaps.orEmpty() else emptyList()
        val grantedCaps = caps.filter { dev.cueopen.app.device.DeviceCaps.granted(ctx, it) }
        val deviceTools = dev.cueopen.app.device.DeviceCapPolicy.availableTools(caps, grantedCaps)
        if ("device_notifications" in deviceTools)
            add(tool("device_notifications", "Lê notificações recentes autorizadas pelo usuário.", buildJsonObject {
                put("filter", str("Filtrar por app ou texto (opcional)")); put("limit", int("Máximo (padrão 30)"))
            }))
        if ("device_notify" in deviceTools)
            add(tool("device_notify", "Mostra uma notificação na barra de status do aparelho.", buildJsonObject {
                put("title", str("Título")); put("text", str("Mensagem"))
            }, listOf("text")))
        if ("device_screenshot" in deviceTools)
            add(tool("device_screenshot", "Captura a tela inteira somente quando o usuário pedir explicitamente um screenshot/print. Para ler a imagem de um post pedido, nunca use captura ampla: primeiro leia a árvore de acessibilidade e, se necessário, use device_accessibility_inspect_image no recorte do post.", buildJsonObject {}))
        if ("device_overlay" in deviceTools)
            add(tool("device_overlay", "Mostra um balão flutuante sobre os outros apps.", buildJsonObject { put("text", str("Texto")) }, listOf("text")))
        if ("device_bluetooth" in deviceTools)
            add(tool("device_bluetooth", "Lista somente dispositivos Bluetooth já pareados; exige a permissão atual de dispositivos próximos e não inicia varredura.", buildJsonObject {}))
        if ("device_bluetooth_pair" in deviceTools)
            add(tool("device_bluetooth_pair", "Abre as Configurações de Bluetooth; você ativa ou pareia manualmente. Não lê dispositivos e não exige BLUETOOTH_CONNECT.", buildJsonObject {}))
        if ("device_calendar_read" in deviceTools)
            add(tool("device_calendar_read", "Lê eventos dos próximos dias sem salvá-los no app.", buildJsonObject { put("days", int("Horizonte em dias (1–30, padrão 7)")) }))
        if ("device_calendar_create" in deviceTools)
            add(tool("device_calendar_create", "Abre um rascunho no editor de calendário do Android sem ler a agenda; o usuário revisa e salva.", buildJsonObject {
                put("title", str("Título do evento")); put("start_epoch_ms", str("Início como epoch em milissegundos")); put("end_epoch_ms", str("Fim como epoch em milissegundos"))
                put("description", str("Descrição opcional")); put("location", str("Local opcional"))
            }, listOf("title", "start_epoch_ms", "end_epoch_ms")))
        if ("device_contacts" in deviceTools)
            add(tool("device_contacts", "Localiza contatos por nome ou número após pedido do usuário; retorna somente nome e telefone, no máximo 10 resultados. Requer READ_CONTACTS e não exporta a agenda.", buildJsonObject { put("query", str("Nome ou número específico para buscar")) }, listOf("query")))
        if ("device_call_log" in deviceTools)
            add(tool("device_call_log", "Lê o histórico recente de chamadas, se o Android/política do dispositivo permitir.", buildJsonObject { put("limit", int("Máximo de registros (1–50, padrão 20)")) }))
        if ("device_location" in deviceTools)
            add(tool("device_location", "Retorna apenas a última localização conhecida; não inicia rastreamento contínuo.", buildJsonObject {}))
        if ("device_dial" in deviceTools)
            add(tool("device_dial", "Abre o discador do Android; o usuário revisa e toca em Ligar. Nunca inicia chamada diretamente.", buildJsonObject { put("phone", str("Número a discar")) }, listOf("phone")))
        if ("device_microphone" in deviceTools)
            add(tool("device_microphone", "Abre reconhecimento de voz do Android com UI visível para o usuário. O áudio não é salvo pelo app.", buildJsonObject {}))
        if ("device_accessibility_open_app" in deviceTools)
            add(tool("device_accessibility_open_app", "Abre um aplicativo instalado pelo nome exato ou pacote. Use apenas quando a tarefa do usuário pedir uma ação nesse app.", buildJsonObject {
                put("app", str("Nome exato do app ou pacote Android"))
            }, listOf("app")))
        if ("device_accessibility_read_screen" in deviceTools)
            add(tool("device_accessibility_read_screen", "Lê somente a árvore de texto/controles visíveis da janela atual; não captura screenshot. Senhas são ocultadas. IDs temporários expiram ou invalidam após mudanças.", buildJsonObject {}))
        if ("device_accessibility_inspect_image" in deviceTools)
            add(tool("device_accessibility_inspect_image", "Fallback visual de uso único: somente quando o usuário pediu ler/analisar um post de app social e a leitura acessível não revelou o conteúdo da imagem. Captura e envia ao modelo apenas o recorte do elemento atual, após revalidar snapshot e janela; nunca salva screenshot no histórico. Requer Android 11+ e modelo com visão.", buildJsonObject {
                put("snapshot_id", str("ID retornado pela leitura de tela atual"))
                put("element_id", int("ID do elemento do post/imagem na leitura atual"))
            }, listOf("snapshot_id", "element_id")))
        if ("device_accessibility_click" in deviceTools)
            add(tool("device_accessibility_click", "Toca um controle visível do snapshot atual. Releia a tela após a ação. Se receber erro de snapshot ausente/invalidado, não repita esse click: chame device_accessibility_read_screen e use somente IDs novos.", buildJsonObject {
                put("snapshot_id", str("ID retornado por device_accessibility_read_screen"))
                put("element_id", int("ID do elemento no snapshot atual"))
            }, listOf("snapshot_id", "element_id")))
        if ("device_accessibility_type" in deviceTools)
            add(tool("device_accessibility_type", "Substitui o texto de um campo editável acessível no snapshot atual; não use para credenciais em texto livre. Para segredos use ask_secure e o marcador correspondente. O texto não é exibido no log da ação.", buildJsonObject {
                put("snapshot_id", str("ID retornado por device_accessibility_read_screen"))
                put("element_id", int("ID do campo editável no snapshot atual"))
                put("text", str("Texto a definir; segredos devem ser marcadores ask_secure"))
            }, listOf("snapshot_id", "element_id", "text")))
        if ("device_accessibility_scroll" in deviceTools)
            add(tool("device_accessibility_scroll", "Rola um controle visível para cima ou para baixo. Releia a tela após a ação.", buildJsonObject {
                put("snapshot_id", str("ID retornado por device_accessibility_read_screen"))
                put("element_id", int("ID do contêiner ou item no snapshot atual"))
                put("direction", str("up ou down"))
            }, listOf("snapshot_id", "element_id", "direction")))
        if ("device_accessibility_back" in deviceTools)
            add(tool("device_accessibility_back", "Aciona Voltar no Android e invalida o snapshot atual.", buildJsonObject {}))
        if (allowMultiAgent) connectorTools().forEach { add(it) }
        // UI Connectors (ex.: IQ Option): só os que este agente pode usar (toggle por agente / "Agent required?").
        runCatching { dev.cueopen.app.CueApp.instance.uiConnectors.toolDefinitions(agent) }.getOrDefault(emptyList()).forEach { add(it) }
        add(tool("tools_load", "Carrega ferramentas que estão FORA do contexto (listadas em 'Ferramentas sob demanda'): grupos (ex.: wallet, device, web_dev, agents, schedule), connector:<id> ou mcp:<servidor>. Ficam disponíveis a partir da próxima resposta, até o fim da tarefa.",
            buildJsonObject { put("names", strArray("Nomes a carregar, ex.: [\"wallet\", \"connector:iqoption\"]")) }, listOf("names")))
        if (allowMultiAgent) add(tool("ask_user", "Faz uma pergunta aberta ao usuário e pausa a tarefa até ele responder. Para escolhas, prefira ask_question.", buildJsonObject { put("question", str("Pergunta")) }, listOf("question")))
        fun objArr(desc: String, props: JsonObject, required: List<String>) = buildJsonObject {
            put("type", "array"); put("description", desc)
            put("items", buildJsonObject {
                put("type", "object"); put("properties", props)
                put("required", buildJsonArray { required.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
            })
        }
        fun boolP(desc: String) = buildJsonObject { put("type", "boolean"); put("description", desc) }
        if (allowMultiAgent) add(tool("ask_question",
            "Mostra ao usuário um card de perguntas de MÚLTIPLA ESCOLHA (1–4 perguntas, uma por página, 2–4 opções cada, com 'Outra resposta' e 'Pular') e pausa até ele responder. " +
                "Use SÓ quando o usuário pediu uma tarefa e há uma dúvida REAL que muda o resultado e não dá para resolver pesquisando ou com uma suposição razoável. Máximo uma vez por pedido.",
            buildJsonObject {
                put("questions", objArr("Perguntas (1 a 4)", buildJsonObject {
                    put("question", str("Pergunta clara e completa, terminando com ?"))
                    put("header", str("Rótulo curto (até 12 letras), ex.: Formato"))
                    put("options", objArr("2 a 4 opções diferentes", buildJsonObject {
                        put("label", str("Texto da opção (1–5 palavras)"))
                        put("description", str("Opcional: o que essa escolha implica"))
                    }, listOf("label")))
                    put("multi_select", boolP("true se o usuário puder marcar várias opções"))
                    put("allow_other", boolP("Mostra 'Outra resposta' com texto livre (padrão true)"))
                }, listOf("question", "options")))
            }, listOf("questions")))
        if (allowMultiAgent) add(tool("ask_secure",
            "Mostra um FORMULÁRIO SEGURO para o usuário digitar API key, token, e-mail/senha ou dados de formulário. Os valores ficam só no aparelho e NUNCA chegam a você: " +
                "depois use {{secret:<name>}} nos argumentos das ferramentas (o app troca pelo valor real na execução e esconde o valor das saídas). Use sempre que precisar de credenciais — nunca peça segredos no chat.",
            buildJsonObject {
                put("title", str("Título do formulário, ex.: Chave da API da Eduzz"))
                put("reason", str("Por que precisa (1 frase), ex.: para testar o conector com dados reais"))
                put("fields", objArr("Campos (1 a 8)", buildJsonObject {
                    put("name", str("Nome snake_case usado no marcador {{secret:name}}, ex.: api_key"))
                    put("label", str("Rótulo mostrado ao usuário"))
                    put("type", buildJsonObject { put("type", "string"); put("enum", buildJsonArray { AskTools.SECURE_TYPES.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }); put("description", "password/token/api_key ficam mascarados") })
                    put("required", boolP("Obrigatório (padrão true)"))
                    put("hint", str("Dica/placeholder opcional, ex.: começa com sk_"))
                }, listOf("name", "label")))
            }, listOf("fields")))
        add(tool("finish", "Conclui a tarefa com um resumo e a lista de entregáveis.", buildJsonObject {
            put("summary", str("Resumo do resultado")); put("files", strArray("Caminhos dos arquivos entregues"))
        }, listOf("summary")))
    }

    data class ToolResult(val output: String, val stop: SessionStatus? = null, val sleepSeconds: Int? = null)

    /**
     * Executes an already-AUTHORIZED autonomous transfer end to end: local signing, broadcast,
     * and one reconciliation pass. The persisted policy (limits + mandatory allowlist) is
     * re-validated inside the signer/broadcaster, so this never bypasses the local safety gate.
     * Returns a human-readable, honest status — never claims success the network did not confirm.
     */
    private suspend fun runAutonomousTransfer(
        intent: dev.cueopen.app.wallet.WalletTransferIntent,
        policy: dev.cueopen.app.wallet.WalletAgentPolicy,
        ledger: dev.cueopen.app.wallet.WalletRequestLedger,
        wallet: dev.cueopen.app.wallet.SolanaWalletRepository,
        network: dev.cueopen.app.wallet.SolanaNetwork,
        agentId: String,
        windowStart: Long,
        windowEnd: Long,
        nowMs: Long
    ): String {
        val rpc = dev.cueopen.app.wallet.SolanaRpcClient(endpoint = network.rpcEndpoint, network = network)
        val executor = dev.cueopen.app.wallet.SolanaTransferExecutor(ctx.applicationContext, wallet, ledger, rpc)
        val amount = String.format(Locale.US, "%.9f SOL", intent.amountLamports / 1_000_000_000.0)
        when (val signed = executor.signAuthorizedTransfer(
            intent.requestId, policy, windowStart, windowEnd, humanApproval = false, signingTimeEpochMs = nowMs
        )) {
            is dev.cueopen.app.wallet.TransferExecutionResult.Signed -> Unit
            is dev.cueopen.app.wallet.TransferExecutionResult.AwaitingApproval ->
                return "Transferência automática não autorizada pela política (ainda exige aprovação manual). Verifique allowlist/limites na tela Carteira. ID: ${intent.requestId}"
            is dev.cueopen.app.wallet.TransferExecutionResult.Rejected ->
                return "Transferência automática recusada ao assinar: ${signed.reason}. ID: ${intent.requestId}"
            else ->
                return "Transferência automática não pôde ser assinada. ID: ${intent.requestId}"
        }
        return when (val broadcast = executor.broadcastPersistedTransfer(
            intent.requestId, agentId, policy, windowStart, windowEnd, humanConfirmed = true
        )) {
            is dev.cueopen.app.wallet.TransferExecutionResult.Submitted -> {
                val link = network.transactionExplorerUrl(broadcast.signature)
                val rec = executor.reconcileSignature(intent.requestId, agentId)
                if (rec is dev.cueopen.app.wallet.TransferExecutionResult.Confirmed)
                    "Transferência automática CONFIRMADA on-chain: $amount para ${intent.recipientAddress} na ${network.displayName}. Assinatura: ${broadcast.signature}. Explorer: $link"
                else
                    "Transferência automática ENVIADA à ${network.displayName}: $amount para ${intent.recipientAddress}. Assinatura: ${broadcast.signature}. Ainda confirmando; acompanhe no Explorer: $link"
            }
            is dev.cueopen.app.wallet.TransferExecutionResult.Unknown ->
                "Transferência automática em estado AMBÍGUO após o envio; não será reenviada automaticamente. Assinatura: ${broadcast.signature}. Reconcilie na tela Carteira. Motivo: ${broadcast.reason}"
            is dev.cueopen.app.wallet.TransferExecutionResult.Rejected ->
                "Transferência automática assinada, mas o envio foi recusado: ${broadcast.reason}. ID: ${intent.requestId}"
            is dev.cueopen.app.wallet.TransferExecutionResult.ChainFailed ->
                "Transferência automática FALHOU on-chain: ${broadcast.reason}. Assinatura: ${broadcast.signature}"
            else ->
                "Transferência automática não concluída. ID: ${intent.requestId}"
        }
    }

    /** Classifica a origem da falha pelo nome da ferramenta, para o log de erros. */
    private fun errorSource(toolName: String): String = when {
        toolName.startsWith("browser_") -> "browser"
        toolName == "shell" -> "terminal"
        toolName.startsWith("wallet_") || toolName.startsWith("trade_") -> "wallet"
        toolName.startsWith("market_") -> "market"
        toolName.startsWith("file_") -> "file"
        toolName.startsWith("device_") -> "device"
        toolName.startsWith("mcp_") -> "connector"
        toolName.startsWith("uic_") -> "uiconnector"
        else -> "tool"
    }

    /** Avalia pelo System One nativo quando for Jev; outros modelos recebem contrato JSON e validação determinística. */
    private suspend fun jevAccuracyCheck(sessionId: String, signalsJson: String, minAccuracy: Int, agentKey: String): String {
        if (signalsJson.isBlank()) return "Informe 'signals_json' com os sinais e seus dados reais."
        val inputs = dev.cueopen.app.trading.SignalIO.parseInputsStrict(signalsJson)
            ?: return "signals_json inválido: envie um array JSON com sinais distintos, id e direction válidos. Nenhuma ordem foi liberada."
        if (inputs.any { dev.cueopen.app.trading.Sides.parse(it.direction) == null })
            return "Direção inválida em um ou mais sinais (use buy/sell ou call/put). Nenhuma ordem foi liberada."
        val s = store.settings
        val model = dev.cueopen.app.trading.SignalEvaluationAdapter.resolveModel(
            s.accuracyModel.ifBlank { "typesafe/jev-router" }, s.baseUrl,
        )
        val effective = maxOf(minAccuracy, TradeCoordinator.gate.floor)
        // Não buscar nem calcular contexto automático da IQ Option: o Jev recebe apenas evidências fornecidas na chamada.
        val missing = inputs.filter { !it.hasEvidence }
        if (missing.isNotEmpty()) return "Dados reais ausentes para: ${missing.joinToString { it.id }}. Nenhuma ordem foi liberada. Envie candles/cotações e o contexto necessário explicitamente."
        val ctxText = ""
        val useSystemOne = dev.cueopen.app.trading.SignalEvaluationAdapter.usesSystemOne(model, s.baseUrl)
        val results = try {
            TradeCoordinator.validationMutex.withLock {
                if (useSystemOne) {
                    val request = dev.cueopen.app.trading.SignalEvaluationAdapter.systemOneRequest(model, signalsJson, ctxText, inputs)
                        ?: throw IllegalArgumentException("não foi possível construir a requisição tipada System One")
                    val raw = llm.systemOne(model, request, conversationId = sessionId)
                    dev.cueopen.app.trading.SignalEvaluationAdapter.parseSystemOneResponse(raw, inputs)
                        ?: throw IllegalArgumentException("System One retornou respostas ausentes ou fora do schema Noul")
                } else {
                    val prompt = dev.cueopen.app.trading.SignalEvaluationAdapter.genericPrompt(signalsJson, ctxText, inputs.map { it.id })
                    val system = LlmMessage("system", "Avalie evidências de forma conservadora. Esta é apenas uma estimativa JSON; não reproduz a calibração de Jev e não executa ordens.")
                    val reply = llm.chat(listOf(system, LlmMessage("user", prompt)), tools = null, model = model, maxTokens = 1200, jsonResponse = true, conversationId = sessionId)
                    dev.cueopen.app.trading.SignalEvaluationAdapter.parseGenericResponse(reply.content.orEmpty(), inputs)
                        ?: throw IllegalArgumentException("resposta JSON inválida, incompleta, duplicada ou fora dos limites")
                }
            }
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            val route = if (useSystemOne) "System One" else "adapter JSON"
            return "ACCURACY_ERROR no $route ($model): ${(t.message ?: t.javaClass.simpleName).take(200)}. Nenhuma ordem foi liberada. Verifique o endpoint, a chave e o modelo avaliador."
        }
        val byId = results.associateBy { dev.cueopen.app.trading.AssetKeys.norm(it.id) }
        return buildString {
            appendLine("Acurácia dos sinais (modelo $model${if (useSystemOne) "; System One" else "; estimativa JSON não calibrada"}; opere só ≥ $effective% e confiança ≥ ${TradeCoordinator.gate.confidenceFloor}%):")
            var approved = 0
            inputs.forEach { sig ->
                val r = byId[dev.cueopen.app.trading.AssetKeys.norm(sig.id)]
                val side = dev.cueopen.app.trading.Sides.parse(sig.direction)
                if (r == null) { appendLine("${sig.id}: sem avaliação do modelo · ⛔ PULAR"); return@forEach }
                if (side == null) { appendLine("${sig.id}: direção inválida (use buy/sell ou call/put) · ⛔ PULAR"); return@forEach }
                val ok = TradeCoordinator.gate.approve(agentKey, sig.venue, sig.id, side, r.accuracy, minAccuracy, confidence = r.confidence)
                if (ok) approved++
                val reason = if (!ok && r.confidence < TradeCoordinator.gate.confidenceFloor) "confiança abaixo do piso local" else r.reason
                appendLine("${sig.id} ${side.name}: acurácia ${r.accuracy.toInt()}% · confiança ${r.confidence.toInt()}% · ${if (ok) "✅ OPERAR" else "⛔ PULAR"}${if (reason.isNotBlank()) " — $reason" else ""}")
            }
            appendLine("Sinais autorizados: $approved/${inputs.size}.")
            appendLine()
            append("Regra local: só sinais acima dos pisos de acurácia e confiança recebem autorização de uso único por ${TradeCoordinator.gate.ttlSeconds / 60} min, no mesmo agente/venue/ativo/lado. Esta checagem apenas avalia; nenhuma ordem é executada por ela.")
        }.trim()
    }

    /** Ferramentas do criador de UI Connectors (só em sessões abertas pelo editor de conectores). */
    /** Atestado do último JSON aprovado no dry-run offline, por sessão do criador. */
    private val builderPassed = java.util.concurrent.ConcurrentHashMap<String, dev.cueopen.app.uiconnector.custom.ConnectorValidationAttestation>()

    private fun strMap(raw: String): Map<String, String> = runCatching {
        (this.json.parseToJsonElement(raw.ifBlank { "{}" }) as JsonObject).mapValues { (_, x) -> (x as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: x.toString() }
    }.getOrDefault(emptyMap())

    private suspend fun connectorTool(sessionId: String, name: String, json: String, argsJson: String = "", loginJson: String = "", settingsJson: String = ""): String {
        if (store.session(sessionId)?.connectorBuilder != true)
            return "Indisponível: crie conectores pela página Conectores → Novo conector → Criar com a IA."
        if (name == "connector_spec") return dev.cueopen.app.uiconnector.custom.SdkPrompt.MARKDOWN
        return run {
                val raw = json.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                val parsed = dev.cueopen.app.uiconnector.custom.CustomDefParser.parse(raw)
                val hub = dev.cueopen.app.CueApp.instance.uiConnectors
                val d = parsed.def
                val clash = d != null && hub.get(d.id) != null && !hub.isCustom(d.id)
                if (d == null) "INVÁLIDO — ${parsed.errors.size} erro(s). Corrija TODOS e chame connector_validate de novo (consulte connector_spec se preciso):\n" +
                        parsed.errors.joinToString("\n") { "- $it" } +
                        (if (parsed.warnings.isNotEmpty()) "\nAvisos:\n" + parsed.warnings.joinToString("\n") { "- $it" } else "")
                else when {
                    clash -> "INVÁLIDO: o id '${d.id}' pertence a um conector do app. Troque o id."
                    name == "connector_test" -> {
                        val toolArgs = runCatching {
                            (this.json.parseToJsonElement(argsJson.ifBlank { "{}" }) as JsonObject).mapValues { (_, v) ->
                                (v as? JsonObject)?.mapValues { (_, x) -> (x as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: x.toString() }.orEmpty()
                            }
                        }.getOrDefault(emptyMap())
                        val settingOverrides = strMap(settingsJson)
                        val result = runCatching {
                            dev.cueopen.app.uiconnector.custom.ConnectorDryRunValidator.validate(raw, d, toolArgs, settingOverrides)
                        }.getOrElse { dev.cueopen.app.uiconnector.custom.ConnectorDryRunValidator.Result(false, "✗ Falha no dry-run: ${it.message ?: it.javaClass.simpleName}", null) }
                        val passed = result.passed && result.attestation != null
                        if (passed) builderPassed[sessionId] = result.attestation!! else builderPassed.remove(sessionId)
                        (if (passed) "PASSOU — dry-run local offline concluído; nenhuma chamada externa nem código gerado foi executado.\n"
                         else "FALHOU — o conector NÃO pode ser proposto assim. Corrija cada ✗ abaixo e rode connector_test de novo:\n") +
                            result.report.take(7000) +
                            (if (passed) "\nLogin, permissões e comportamento real da API serão testados somente quando o usuário conectar/autorizar o conector. Agora chame connector_propose com o MESMO JSON." else "")
                    }
                    name == "connector_propose" && !dev.cueopen.app.uiconnector.custom.ConnectorAttestationPolicy.isInstallable(raw, builderPassed[sessionId]) ->
                        "BLOQUEADO pelos guardrails: este JSON ainda não passou em connector_test. Rode connector_test com este MESMO JSON (com args_json de exemplo para as ferramentas) e só proponha quando PASSOU."
                    name == "connector_validate" -> "OK — definição válida (host ${d.host}, ${d.tools.size} ferramenta(s), ${d.panels.size} painel(is))." +
                        (if (parsed.warnings.isNotEmpty()) "\nAvisos (não bloqueiam; corrija se fizer sentido):\n" + parsed.warnings.joinToString("\n") { "- $it" } else "") +
                        "\nAgora chame connector_test com o MESMO JSON e args_json com valores de exemplo não secretos (ou deixe o teste gerar valores sintéticos)" +
                        " Em seguida connector_test executa apenas mocks offline — sem credenciais reais, rede externa ou código arbitrário."
                    else -> {
                        store.addItem(sessionId, ChatItem.ConnectorProposal(
                            connectorId = d.id, name = d.name, description = d.description, host = d.host,
                            tools = d.tools.map { it.name }, json = raw,
                            capabilities = d.capabilities.toList().sorted(),
                            validation = builderPassed[sessionId],
                        ))
                        "Card de instalação exibido ao usuário (conector '${d.name}', id ${d.id}). Informe que passou em dry-run offline e que API/login real ainda precisam ser autorizados/testados ao conectar. Não afirme que já foi instalado."
                    }
                }
                    }
    }

    private suspend fun runTool(
        sessionId: String,
        call: ToolCall,
        allowDeviceTools: Boolean,
        model: String,
        visualInspectionUsed: MutableSet<String>,
        accessibilityReadScreenCompleted: MutableSet<String>,
    ): ToolResult {
        val parsed = runCatching { json.parseToJsonElement(call.argsJson).jsonObject }
        if (parsed.isFailure && call.argsJson.isNotBlank() && call.argsJson != "{}") {
            store.addItem(sessionId, ChatItem.Step(tool = call.name, title = "Argumentos cortados", args = call.name, result = call.argsJson.takeLast(300), status = StepStatus.ERROR))
            return ToolResult("ERRO: os argumentos de ${call.name} chegaram incompletos (sua resposta foi cortada por tamanho). " +
                "Para arquivos grandes use file_write em partes: a 1ª com append=false e as seguintes com append=true, até ~150 linhas por chamada.")
        }
        val a: JsonObject = parsed.getOrDefault(JsonObject(emptyMap()))
        fun s(k: String) = a[k]?.jsonPrimitive?.contentOrNull.orEmpty()
        dev.cueopen.app.device.DeviceCapPolicy.requiredCapability(call.name)?.let { capability ->
            if (!allowDeviceTools)
                return ToolResult("RECUSADO: ferramentas Android não estão disponíveis nesta execução de worker.")
            val agent = store.agent(store.session(sessionId)?.agentId)
            if (agent == null || capability !in agent.deviceCaps)
                return ToolResult("RECUSADO: este agente não tem opt-in para '${dev.cueopen.app.device.DeviceCaps.all.firstOrNull { it.id == capability }?.title ?: capability}'.")
            if (dev.cueopen.app.device.DeviceCapPolicy.requiresSystemGrant(call.name) &&
                !dev.cueopen.app.device.DeviceCaps.granted(ctx, capability))
                return ToolResult("RECUSADO: a permissão Android desta capability foi revogada ou não foi concedida. Habilite-a nas permissões deste agente e confirme no Android.")
        }
        fun i(k: String) = a[k]?.jsonPrimitive?.intOrNull
        fun b(k: String) = a[k]?.jsonPrimitive?.booleanOrNull ?: false
        fun sl(k: String): List<String> = runCatching {
            (a[k] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        }.getOrDefault(emptyList())
        // Rede salva da carteira (fail-closed: nunca roteia silenciosamente para a Mainnet).
        fun walletNetwork(): SolanaNetwork? = runCatching {
            val stored = ctx.applicationContext
                .getSharedPreferences("solana_wallet_settings", android.content.Context.MODE_PRIVATE)
                .getString("network", null) ?: return null
            SolanaNetwork.valueOf(stored)
        }.getOrNull()
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
                if (dev.cueopen.app.device.DeviceCaps.BACKGROUND !in ag.deviceCaps)
                    return ToolResult("Agendamento não iniciado: primeiro habilite 'Executar em segundo plano' em Permissões do dispositivo no perfil deste Agent. O Android ainda pode interromper ciclos por bateria ou política do aparelho.")
                val mm = m.coerceIn(1, 1440)
                // Eleva o orçamento diário de agentes antigos (criados com o teto baixo de 300k) para
                // não pararem em silêncio em poucos ciclos. Mantém um valor maior já definido pelo usuário.
                store.upsertAgent(ag.copy(autonomous = true, heartbeatMinutes = mm,
                    mission = s("mission").ifBlank { ag.mission }, homeSessionId = sessionId, lastBeatAt = System.currentTimeMillis(),
                    dailyTokenBudget = maxOf(ag.dailyTokenBudget, 20_000_000L)))
                dev.cueopen.app.scheduler.Heartbeat.schedule(ctx, ag.id, mm)
                store.addItem(sessionId, ChatItem.Step(tool = "set_schedule", title = "Agendado: a cada ${mm} min", args = s("mission"), result = "", status = StepStatus.OK))
                return ToolResult("Pronto: o Android vai me acordar sozinho a cada ${mm} min (mesmo com o app fechado) e eu farei a verificação. Nada de você precisar mandar mensagem. Pare com /stop ou desligando a autonomia no Agent.")
            }
            "sleep" -> {
                val align = i("align_candle_seconds")
                val (secs, title, note) = when {
                    align != null && align >= 5 -> {
                        val tf = align.coerceIn(5, 86_400)
                        val lead = (i("lead_seconds") ?: 10).coerceIn(2, 30)
                        val nowMs = System.currentTimeMillis()
                        var nextOpen = (nowMs / 1000 / tf + 1) * tf // próxima abertura (epoch s, alinhada ao tempo gráfico)
                        var wait = (nextOpen * 1000 - nowMs) / 1000.0 - lead
                        if (wait < 0) { nextOpen += tf; wait += tf } // lead já passou: mira a abertura seguinte
                        val hhmmss = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(nextOpen * 1000))
                        Triple(wait.toInt().coerceAtLeast(1), "Aguardando a próxima vela de ${tfLabel(tf)} (abre $hhmmss)",
                            "Acordo ${lead}s antes da próxima fronteira de período ${tfLabel(tf)}, às $hhmmss.")
                    }
                    i("seconds") != null -> { val sc = i("seconds")!!.coerceIn(1, 86_400); Triple(sc, "Aguardando ${sc}s", "") }
                    else -> { val m = (i("minutes") ?: 5).coerceIn(1, 1440); Triple(m * 60, "Aguardando $m min", "") }
                }
                store.addItem(sessionId, ChatItem.Step(tool = "sleep", title = title, args = s("reason"), result = note, status = StepStatus.OK))
                return ToolResult(listOf(s("reason").ifBlank { "verificação" }, note).filter { it.isNotBlank() }.joinToString(" — "), sleepSeconds = secs)
            }
            "connector_spec", "connector_validate", "connector_test", "connector_propose" -> {
                // passo visível no chat: o que está sendo feito, com o quê, e o resultado
                val cname = runCatching { (json.parseToJsonElement(CustomDefParserFence.strip(s("json"))) as JsonObject)["name"]?.jsonPrimitive?.contentOrNull }.getOrNull().orEmpty()
                val testArgs = runCatching {
                    (json.parseToJsonElement(s("args_json").ifBlank { "{}" }) as JsonObject).entries.joinToString("; ") { (tool, v) ->
                        tool + "(" + ((v as? JsonObject)?.entries?.joinToString(", ") { (k, x) -> k + "=" + ((x as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: x.toString()) } ?: "") + ")"
                    }
                }.getOrDefault("")
                val (title, args) = when (call.name) {
                    "connector_spec" -> "Lendo a especificação do SDK" to "formato JSON, regras e exemplos"
                    "connector_validate" -> "Validando a definição do conector" to cname.ifBlank { "JSON" }
                    "connector_test" -> (if (testArgs.isBlank()) "Validando o conector (guardrails + execução real)" else "Validando o conector com exemplos (guardrails + execução real)") to
                        listOf(cname, testArgs).filter { it.isNotBlank() }.joinToString(" · ")
                    else -> "Preparando o card de instalação" to cname
                }
                val step = ChatItem.Step(tool = call.name, title = title, args = args)
                store.addItem(sessionId, step)
                setActivity(sessionId, Activity(Activity.Kind.THINKING, title))
                val out = SecretVault.redact(sessionId, connectorTool(sessionId, call.name, s("json"), s("args_json"), s("login_json"), s("settings_json")))
                val failed = out.startsWith("INVÁLIDO") || out.startsWith("FALHOU") || out.startsWith("BLOQUEADO") || out.startsWith("ERRO")
                val summary = when {
                    call.name == "connector_spec" -> "Especificação carregada (${out.length / 1024} KB)."
                    else -> out
                }
                store.replaceItem(sessionId, step.copy(title = title + (if (failed) " — falhou" else " — ok"), result = summary.take(4000), status = if (failed) StepStatus.ERROR else StepStatus.OK))
                return ToolResult(out)
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
            "tools_load" -> {
                val hub = runCatching { dev.cueopen.app.CueApp.instance.uiConnectors }.getOrNull()
                val connIds = hub?.connectors.orEmpty().map { it.spec.id }.toSet()
                val mcpNames = dev.cueopen.app.connectors.Connectors.toolDefinitions(store).mapNotNull { d -> toolName(d).takeIf { it.contains("__") }?.substringBefore("__")?.removePrefix("mcp_") }.toSet()
                val (ok, bad) = JevOrchestrator.resolveLoad(sl("names"), connIds, mcpNames)
                JevOrchestrator.load(sessionId, ok)
                return ToolResult((if (ok.isNotEmpty()) "Carregado: ${ok.joinToString()}. As ferramentas aparecem na sua próxima resposta." else "Nada carregado.") +
                    (if (bad.isNotEmpty()) " Desconhecidos: ${bad.joinToString()} — use os nomes do índice 'Ferramentas sob demanda'." else ""))
            }
            "ask_question" -> {
                // GUARDRAIL: só existe para uma dúvida real dentro de um pedido do usuário presente —
                // nunca em ciclos autônomos/agendados e no máximo uma vez por mensagem do usuário.
                val sess = store.session(sessionId)
                val items = sess?.items.orEmpty()
                val lastUser = items.indexOfLast { it is ChatItem.User }
                val autonomous = sess?.continuous == true || sess?.llm?.lastOrNull { it.role == "user" }?.content?.startsWith("[Heartbeat") == true
                if (lastUser < 0 || autonomous) return ToolResult("RECUSADO: ask_question só pode ser usado quando o usuário pediu uma tarefa e está na conversa. Siga com a suposição mais razoável e diga qual assumiu.")
                if (items.drop(lastUser + 1).any { it is ChatItem.Question }) return ToolResult("RECUSADO: você já fez perguntas para este pedido. Use as respostas recebidas (ou uma suposição razoável) e continue a tarefa.")
                val (qs, err) = AskTools.parseQuestions(a)
                if (err != null) return ToolResult("ERRO em ask_question: $err")
                store.addItem(sessionId, ChatItem.Question(questions = qs))
                return ToolResult("Perguntas exibidas ao usuário; aguardando as respostas.", SessionStatus.WAITING_USER)
            }
            "ask_secure" -> {
                val (title, fields, err) = AskTools.parseSecure(a)
                if (err != null) return ToolResult("ERRO em ask_secure: $err")
                if (fields.all { SecretVault.has(sessionId, it.name) })
                    return ToolResult("Você JÁ tem esses dados seguros: ${fields.joinToString { "{{secret:${it.name}}}" }}. Use os marcadores direto; não peça de novo.")
                val pending = store.session(sessionId)?.items?.any { it is ChatItem.SecureForm && it.state == "pending" } == true
                if (pending) return ToolResult("Já há um formulário seguro aguardando o usuário. Espere a resposta.", SessionStatus.WAITING_USER)
                store.addItem(sessionId, ChatItem.SecureForm(title = title, reason = s("reason").take(300), fields = fields))
                return ToolResult("Formulário seguro exibido ao usuário; aguardando. Você NÃO verá os valores — depois use " +
                    fields.joinToString { "{{secret:${it.name}}}" } + " nos argumentos das ferramentas.", SessionStatus.WAITING_USER)
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
            "file_write", "file_edit" -> "Editando arquivo" to Activity.Kind.EDITOR
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
            "device_calendar_read" -> "Lendo calendário" to Activity.Kind.THINKING
            "device_call_log" -> "Lendo histórico de ligações" to Activity.Kind.THINKING
            "device_contacts" -> "Pesquisando contatos" to Activity.Kind.THINKING
            "device_location" -> "Consultando última localização" to Activity.Kind.THINKING
            "device_bluetooth", "device_bluetooth_pair" -> "Acessando Bluetooth" to Activity.Kind.THINKING
            "device_dial" -> "Abrindo discador" to Activity.Kind.THINKING
            "device_microphone" -> "Abrindo reconhecimento de voz" to Activity.Kind.THINKING
            "device_screenshot" -> "Capturando tela" to Activity.Kind.THINKING
            "device_accessibility_open_app" -> "Abrindo aplicativo" to Activity.Kind.THINKING
            "device_accessibility_read_screen" -> "Lendo controles visíveis" to Activity.Kind.THINKING
            "device_accessibility_inspect_image" -> "Analisando recorte visual" to Activity.Kind.THINKING
            "device_accessibility_click" -> "Tocando no aplicativo" to Activity.Kind.THINKING
            "device_accessibility_type" -> "Preenchendo campo" to Activity.Kind.THINKING
            "device_accessibility_scroll" -> "Rolando aplicativo" to Activity.Kind.THINKING
            "device_accessibility_back" -> "Voltando no aplicativo" to Activity.Kind.THINKING
            "device_notify", "device_overlay" -> "Avisando no aparelho" to Activity.Kind.THINKING
            "browser_console" -> "Lendo console" to Activity.Kind.BROWSER
            "create_skill" -> "Criando skill" to Activity.Kind.THINKING
            else -> (if (call.name.startsWith("mcp_")) "Conector · " + call.name.substringAfter("__") else call.name) to Activity.Kind.THINKING
        }
        val argPreview = when (call.name) {
            "shell" -> s("command")
            "file_write", "file_edit", "file_read", "file_list" -> s("path").ifBlank { cwd }
            "browser_navigate" -> s("url")
            "browser_type" -> s("text")
            "browser_click" -> "#${i("index")}"
            "remember", "memory_save" -> s("fact")
            "memory_search" -> s("query")
            "notes_write" -> s("content").take(60)
            "device_accessibility_open_app" -> s("app").take(60)
            "device_accessibility_click", "device_accessibility_scroll" -> "element ${i("element_id")?.toString() ?: "?"}"
            "device_accessibility_type" -> "element ${i("element_id")?.toString() ?: "?"} · texto oculto"
            "device_accessibility_inspect_image" -> "element ${i("element_id")?.toString() ?: "?"} · recorte efêmero"
            "create_skill" -> s("name")
            else -> ""
        }
        val step = ChatItem.Step(tool = call.name, title = title, args = argPreview)
        val executionArguments = if (call.name == "device_accessibility_type") buildJsonObject {
            put("snapshot_id", s("snapshot_id"))
            put("element_id", i("element_id") ?: -1)
            put("text", "[oculto]")
        }.toString() else call.argsJson
        val executionId = executions.begin(sessionId, call.name, executionArguments)
        store.addItem(sessionId, step)
        setActivity(sessionId, Activity(kind, argPreview.take(80)))
        if (kind == Activity.Kind.BROWSER) browserFor(sessionId).setAgentControl(true)

        // marcador {{secret:x}} sem valor no cofre (ex.: app foi reiniciado) → pede de novo pelo formulário seguro
        SecretVault.missing(sessionId, call.argsJson.replace("\\\"", "\"")).takeIf { it.isNotEmpty() }?.let { miss ->
            store.replaceItem(sessionId, step.copy(result = "Dados seguros ausentes: " + miss.joinToString(), status = StepStatus.ERROR))
            executions.fail(executionId, "secret ausente")
            return ToolResult("ERRO: não há valor seguro para ${miss.joinToString { "{{secret:$it}}" }} nesta tarefa (o formulário não foi enviado ou o app reiniciou). Peça com ask_secure e tente de novo.")
        }
        val out0: String = try {
            when (call.name) {
                "multi_agents" -> runMultiAgents(sessionId, s("tasks_json"), s("repo_path"), i("max_workers") ?: 3, i("budget_tokens") ?: 6000)
                "wallet_get_address" -> {
                    val agentId = store.session(sessionId)?.agentId
                    val policies = WalletAgentPolicyStore(ctx.applicationContext)
                    when {
                        agentId.isNullOrBlank() -> "ERRO: esta sessão não pertence a um agente."
                        !policies.canAgentReadAddress(agentId) ->
                            "Acesso negado: o usuário não concedeu a este agente permissão para consultar o endereço."
                        else -> {
                            val wallet = SolanaWalletRepository(ctx.applicationContext)
                            if (!wallet.hasWallet() || !wallet.backupConfirmed()) {
                                "Carteira indisponível ou backup ainda não confirmado."
                            } else {
                                "Endereço público Solana: " + (wallet.publicAddress()
                                    ?: "Endereço público indisponível.")
                            }
                        }
                    }
                }
                "wallet_get_balance" -> {
                    val agentId = store.session(sessionId)?.agentId
                    val policies = WalletAgentPolicyStore(ctx.applicationContext)
                    when {
                        agentId.isNullOrBlank() -> "ERRO: esta sessão não pertence a um agente."
                        !policies.canAgentReadBalance(agentId) ->
                            "Acesso negado: o usuário não concedeu a este agente permissão para consultar o saldo."
                        else -> {
                            val wallet = SolanaWalletRepository(ctx.applicationContext)
                            if (!wallet.hasWallet() || !wallet.backupConfirmed()) {
                                "Carteira indisponível ou backup ainda não confirmado."
                            } else {
                                val prefs = ctx.applicationContext.getSharedPreferences("solana_wallet_settings", android.content.Context.MODE_PRIVATE)
                                val networkResult = runCatching {
                                    // Fail closed: never silently route an agent wallet operation to Mainnet.
                                    val storedNetwork = prefs.getString("network", null)
                                        ?: error("A rede da carteira não foi configurada. Abra a tela Carteira e selecione explicitamente Mainnet ou Devnet.")
                                    SolanaNetwork.valueOf(storedNetwork)
                                }
                                if (networkResult.isFailure) {
                                    "Consulta bloqueada: a rede salva da carteira está ausente ou inválida. Abra a carteira e selecione a rede novamente."
                                } else {
                                    val network = networkResult.getOrThrow()
                                    val address = wallet.publicAddress()
                                    if (address == null) {
                                        "Endereço público indisponível."
                                    } else {
                                        try {
                                            val lamports = SolanaRpcClient(endpoint = network.rpcEndpoint, network = network).getBalanceLamports(address)
                                            "Saldo " + network.displayName + ": " + String.format(Locale.US, "%.9f SOL", lamports / 1_000_000_000.0) +
                                                if (lamports == 0L) "\nSaldo RPC retornado como zero. Um endereço válido pode ainda não ter uma conta financiada nesta rede; isso não significa que a frase ou a chave local seja inválida." else ""
                                        } catch (e: Exception) {
                                            "WALLET_RPC_ERROR: Não foi possível consultar o saldo em " + network.displayName +
                                                ". Motivo: " + (e.message ?: e.javaClass.simpleName).take(240) +
                                                ". Não tente contornar esta falha com shell, terminal, curl ou Solscan. Informe o erro e peça para o usuário verificar a rede/conectividade; nenhuma chave ou frase deve ser exposta."
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                "wallet_request_transfer" -> {
                    // Identity comes from the selected local session, never from LLM arguments.
                    val agentId = store.session(sessionId)?.agentId
                    val policies = WalletAgentPolicyStore(ctx.applicationContext)
                    if (agentId.isNullOrBlank() || store.agent(agentId) == null) {
                        "Pedido recusado: esta sessão não está vinculada a um agente local válido."
                    } else if (!policies.canAgentRequestTransfer(agentId)) {
                        "Pedido recusado: o usuário não concedeu a este agente permissão para solicitar transferências."
                    } else {
                        val networkPrefs = ctx.applicationContext.getSharedPreferences(
                            "solana_wallet_settings", android.content.Context.MODE_PRIVATE
                        )
                        val networkResult = runCatching {
                            val stored = networkPrefs.getString("network", null)
                                ?: error("A rede da carteira não foi configurada. Abra a tela Carteira e selecione explicitamente Mainnet ou Devnet.")
                            SolanaNetwork.valueOf(stored)
                        }
                        if (networkResult.isFailure) {
                            "Pedido bloqueado: abra a carteira e selecione Mainnet ou Devnet antes de solicitar uma transferência."
                        } else {
                            val network = networkResult.getOrThrow()
                            val wallet = SolanaWalletRepository(ctx.applicationContext)
                            if (!wallet.hasWallet() || !wallet.backupConfirmed()) {
                                "Pedido bloqueado: carteira indisponível ou backup ainda não confirmado."
                            } else {
                                runCatching {
                                    val nowMs = System.currentTimeMillis()
                                    val start = java.util.Calendar.getInstance().apply {
                                        set(java.util.Calendar.HOUR_OF_DAY, 0)
                                        set(java.util.Calendar.MINUTE, 0)
                                        set(java.util.Calendar.SECOND, 0)
                                        set(java.util.Calendar.MILLISECOND, 0)
                                    }
                                    val windowStart = start.timeInMillis
                                    val windowEnd = (start.clone() as java.util.Calendar).apply {
                                        add(java.util.Calendar.DAY_OF_YEAR, 1)
                                    }.timeInMillis
                                    val intent = dev.cueopen.app.wallet.WalletTransferIntent.fromSol(
                                        requestId = java.util.UUID.randomUUID().toString().replace("-", ""),
                                        agentId = agentId,
                                        recipientAddress = s("recipient").trim(),
                                        amountSol = s("amount_sol").trim(),
                                        createdAtEpochMs = nowMs,
                                        network = network
                                    )
                                    val policy = policies.transferPolicy(agentId, network)
                                    val ledger = dev.cueopen.app.wallet.WalletRequestLedger(ctx.applicationContext)
                                    val duplicate = ledger.findRecentDuplicateIntent(intent, nowMs)
                                    if (duplicate != null) {
                                        "Pedido duplicado recente detectado; nenhum novo pedido criado. ID: ${duplicate.requestId}; estado: ${duplicate.state}. Revise o pedido existente na tela Carteira."
                                    } else {
                                        when (val claim = ledger.claimAndAuthorize(
                                            intent, policy, windowStart, windowEnd, nowMs
                                        )) {
                                            is dev.cueopen.app.wallet.LedgerAuthorizationResult.Created ->
                                                if (claim.record.state == dev.cueopen.app.wallet.LedgerState.AWAITING_APPROVAL)
                                                    "Pedido registrado e aguardando aprovação na tela Carteira. ID: ${intent.requestId}; rede: ${network.displayName}; destinatário: ${intent.recipientAddress}; valor: ${String.format(Locale.US, "%.9f SOL", intent.amountLamports / 1_000_000_000.0)}. Nenhuma assinatura ou transmissão foi realizada."
                                                else
                                                    // Autonomous grant: the user enabled automatic transfers for this agent,
                                                    // so sign + broadcast without manual approval, still bounded by the
                                                    // persisted policy (per-tx/daily limits + mandatory allowlist), which the
                                                    // signer re-validates. Report only what the network actually returned.
                                                    runAutonomousTransfer(intent, policy, ledger, wallet, network, agentId, windowStart, windowEnd, nowMs)
                                            is dev.cueopen.app.wallet.LedgerAuthorizationResult.Rejected ->
                                                "Pedido recusado pela política local: ${claim.decision.reason}"
                                            is dev.cueopen.app.wallet.LedgerAuthorizationResult.Existing ->
                                                "Pedido duplicado detectado; nenhuma nova autorização criada. ID: ${claim.record.requestId}; estado: ${claim.record.state}."
                                            is dev.cueopen.app.wallet.LedgerAuthorizationResult.Approved ->
                                                "Este pedido já foi aprovado anteriormente; nenhuma nova assinatura foi autorizada."
                                        }
                                    }
                                }.getOrElse { error ->
                                    "Pedido de transferência não criado: ${error.message ?: "erro de validação"}"
                                }
                            }
                        }
                    }
                }
                "typesafe_jev_check" -> {
                    val ag = store.agent(store.session(sessionId)?.agentId)
                    val allowed = ag != null && (WalletAgentPolicyStore(ctx.applicationContext).canAgentDayTrade(ag.id) ||
                        dev.cueopen.app.CueApp.instance.uiConnectors.jevRequiredBy(ag))
                    if (!allowed) "Indisponível: a validação TypeSafe Jev não é exigida por nenhum recurso deste agente (day trade Solana desligado e conectores com 'Exigir TypeSafe Jev' off). Opere direto pelas ferramentas do conector."
                    else jevAccuracyCheck(sessionId, s("signals_json"), i("min_accuracy") ?: 70, ag!!.id)
                }
                "market_price", "market_candles", "market_liquidity", "market_discover", "market_token_info",
                "wallet_list_tokens", "wallet_swap", "trade_set_rule", "trade_list", "trade_cancel", "trade_update_rule" -> {
                    val agentId = store.session(sessionId)?.agentId
                    val policies = WalletAgentPolicyStore(ctx.applicationContext)
                    if (agentId.isNullOrBlank() || store.agent(agentId) == null) {
                        "Recusado: esta sessão não está vinculada a um agente local válido."
                    } else if (!policies.canAgentDayTrade(agentId)) {
                        "Acesso negado: o usuário não habilitou a capacidade de day trade para este agente. Peça para ativar nas configurações do agente."
                    } else {
                        val wallet = SolanaWalletRepository(ctx.applicationContext)
                        val market = dev.cueopen.app.wallet.SolanaMarketDataClient()
                        if (call.name.startsWith("market_")) {
                            // Leitura/análise: não tocam a carteira nem a chave.
                            when (call.name) {
                                "market_discover" -> {
                                    val sort = s("sort").ifBlank { "new" }
                                    runCatching { market.discoverTokens(sort, i("limit") ?: 30) }.fold({ tokens ->
                                        if (tokens.isEmpty()) "Nenhum token retornado agora para sort=$sort."
                                        else buildString {
                                            appendLine("Tokens Solana (${if (sort.lowercase().startsWith("trend") || sort.lowercase() == "hot" || sort.lowercase() == "gainers") "em alta" else "mais novos → mais velhos"}), dados reais:")
                                            tokens.forEach { t ->
                                                val price = t.priceUsd?.let { "US$ " + String.format(Locale.US, if (it < 1.0) "%.8f" else "%.4f", it) } ?: "s/preço"
                                                val liq = t.liquidityUsd?.let { "liq US$ " + String.format(Locale.US, "%,.0f", it) } ?: "liq ?"
                                                val vol = t.volume24hUsd?.let { "vol24h US$ " + String.format(Locale.US, "%,.0f", it) } ?: "vol ?"
                                                val chg = t.priceChange24hPct?.let { " | 24h ${String.format(Locale.US, "%+.1f", it)}%" } ?: ""
                                                appendLine("${t.symbol} (${t.mint}): $price | $liq | $vol$chg${t.poolCreatedAt?.let { " | criado $it" } ?: ""}")
                                            }
                                        }.trim()
                                    }, { "MARKET_ERROR ao descobrir tokens: ${(it.message ?: it.javaClass.simpleName).take(200)}." })
                                }
                                "market_token_info" -> {
                                    val mint = s("mint")
                                    runCatching { market.tokenInfo(mint) }.fold({ t ->
                                        if (t == null) "Sem informações para este mint."
                                        else buildString {
                                            appendLine("${t.symbol} — ${t.name} ($mint)")
                                            t.priceUsd?.let { appendLine("Preço: US$ " + String.format(Locale.US, if (it < 1.0) "%.8f" else "%.4f", it)) }
                                            t.liquidityUsd?.let { appendLine("Liquidez total: US$ " + String.format(Locale.US, "%,.0f", it)) }
                                            t.volume24hUsd?.let { appendLine("Volume 24h: US$ " + String.format(Locale.US, "%,.0f", it)) }
                                            t.marketCapUsd?.let { appendLine("Market cap/FDV: US$ " + String.format(Locale.US, "%,.0f", it)) }
                                            appendLine("Liquidez e volume reais permitem avaliar se é possível comprar e vender; valide o impacto com market_liquidity antes de entrar.")
                                        }.trim()
                                    }, { "MARKET_ERROR ao consultar token: ${(it.message ?: it.javaClass.simpleName).take(200)}." })
                                }
                                "market_price" -> {
                                    val mints = sl("mints")
                                    if (mints.isEmpty()) "Informe ao menos um mint em 'mints'."
                                    else runCatching { market.pricesUsd(mints) }.fold({ prices ->
                                        if (prices.isEmpty()) "Nenhum preço disponível agora para os mints informados."
                                        else prices.entries.joinToString("\n") { (m, p) ->
                                            val sym = SolanaRpcClient.knownSymbol(m) ?: m.take(6)
                                            "$sym ($m): US$ " + String.format(Locale.US, if (p < 1.0) "%.6f" else "%.4f", p)
                                        }
                                    }, { "MARKET_ERROR ao consultar preço: ${(it.message ?: it.javaClass.simpleName).take(200)}. Não contorne com shell/curl." })
                                }
                                "market_candles" -> {
                                    val mint = s("mint")
                                    runCatching {
                                        market.candles(mint, s("timeframe").ifBlank { "hour" }, i("aggregate") ?: 1, i("limit") ?: 60)
                                    }.fold({ candles ->
                                        if (candles.isEmpty()) "Sem velas disponíveis para este token."
                                        else buildString {
                                            appendLine("OHLCV real (${candles.size} velas, ts unix | O H L C | volume USD):")
                                            candles.takeLast(60).forEach { c ->
                                                appendLine("${c.tsEpochSeconds} | ${c.open} ${c.high} ${c.low} ${c.close} | ${String.format(Locale.US, "%.0f", c.volumeUsd)}")
                                            }
                                        }.trim()
                                    }, { e ->
                                        // Velas indisponíveis (429/timeout/sem pool): cai para a tendência REAL do
                                        // DexScreener (keyless) — o agente nunca fica sem dado de mercado.
                                        val trend = runCatching { market.dexTrend(mint) }.getOrNull()
                                        if (trend != null) "Velas OHLCV indisponíveis agora (${(e.message ?: "erro").take(80)}). Usando tendência real alternativa:\n\n$trend"
                                        else "MARKET_ERROR ao consultar velas: ${(e.message ?: e.javaClass.simpleName).take(200)}."
                                    })
                                }
                                else -> { // market_liquidity
                                    val input = s("input_mint"); val output = s("output_mint")
                                    val probes = sl("probe_amounts_raw").mapNotNull { it.toLongOrNull() }
                                    if (probes.isEmpty()) "Informe 'probe_amounts_raw' com ao menos um tamanho em unidades mínimas."
                                    else runCatching { market.depthCurve(input, output, probes) }.fold({ curve ->
                                        if (curve.isEmpty()) "Nenhuma rota de liquidez retornada para este par."
                                        else buildString {
                                            appendLine("Profundidade real (impacto por tamanho; AMM, sem order book central):")
                                            curve.forEach { d ->
                                                appendLine("entrada ${d.inAmountRaw} → saída ${d.outAmountRaw} | impacto ${d.priceImpactPct}% | rota ${d.routeLabels.joinToString("+").ifBlank { "direta" }}")
                                            }
                                        }.trim()
                                    }, { "MARKET_ERROR ao medir liquidez: ${(it.message ?: it.javaClass.simpleName).take(200)}." })
                                }
                            }
                        } else if (!wallet.hasWallet() || !wallet.backupConfirmed()) {
                            "Carteira indisponível ou backup ainda não confirmado."
                        } else {
                            val network = walletNetwork()
                            if (network == null) "Rede da carteira não configurada. Abra a tela Carteira e selecione Mainnet ou Devnet."
                            else when (call.name) {
                                "wallet_list_tokens" -> {
                                    val address = wallet.publicAddress()
                                    if (address == null) "Endereço público indisponível."
                                    else runCatching { SolanaRpcClient.tokenAccountsWithFallback(network, address) }.fold({ tokens ->
                                        if (tokens.isEmpty()) "Nenhum token SPL encontrado nesta carteira (${network.displayName})."
                                        else tokens.joinToString("\n") { t ->
                                            val sym = SolanaRpcClient.knownSymbol(t.mint) ?: t.mint.take(6)
                                            "$sym: ${t.uiAmount} (mint ${t.mint}, raw ${t.amountRaw}, dec ${t.decimals})"
                                        }
                                    }, { "WALLET_RPC_ERROR ao listar tokens: ${(it.message ?: it.javaClass.simpleName).take(200)}." })
                                }
                                "wallet_swap" -> {
                                    val amount = s("amount_raw").toLongOrNull()
                                    if (amount == null || amount <= 0L) "amount_raw deve ser um inteiro positivo em unidades mínimas."
                                    else {
                                        val input = s("input_mint"); val output = s("output_mint")
                                        val slippage = (i("slippage_bps") ?: 100).coerceIn(1, 5000)
                                        val cap = policies.agentMaxTransferLamports(agentId)
                                        if (input == dev.cueopen.app.wallet.SolanaSwapClient.SOL_MINT && amount > cap) {
                                            "Recusado: entrada de ${String.format(Locale.US, "%.9f", amount / 1e9)} SOL excede o limite autorizado por operação (${String.format(Locale.US, "%.9f", cap / 1e9)} SOL). Reduza o valor ou ajuste o limite na tela Carteira."
                                        } else {
                                            val legs = dev.cueopen.app.trading.SwapLegs.legs(input, output)
                                            val symbols = runCatching { market.tokenDisplays(legs.map { it.mint }) }.getOrDefault(emptyMap())
                                            val consumed = ArrayList<dev.cueopen.app.trading.SignalGate.Approval>()
                                            var refusal: String? = null
                                            if (legs.isEmpty()) {
                                                refusal = "Recusado: troca entre moedas-base (SOL/USDC/USDT) sem ativo de risco não é uma operação de trade."
                                            } else for (leg in legs) {
                                                val keys = setOf(leg.mint, SolanaRpcClient.knownSymbol(leg.mint).orEmpty(), symbols[leg.mint]?.symbol.orEmpty())
                                                val ap = TradeCoordinator.gate.consume(agentId, "solana", keys, leg.side)
                                                if (ap == null) {
                                                    refusal = "RECUSADO: falta a validação TypeSafe Jev para ${leg.side} de ${symbols[leg.mint]?.symbol ?: leg.mint}. Chame typesafe_jev_check com signals_json=[{\"id\":\"${leg.mint}\",\"venue\":\"solana\",\"direction\":\"${if (leg.side == dev.cueopen.app.trading.Side.BUY) "buy" else "sell"}\",\"data\":\"<dados reais>\"}] e execute só se vier ✅ OPERAR. A aprovação vale ${TradeCoordinator.gate.ttlSeconds / 60} min e serve para UMA ordem."
                                                    break
                                                }
                                                consumed.add(ap)
                                                val holder = TradeCoordinator.claim("solana", leg.mint, agentId)
                                                if (holder != null) { refusal = "RECUSADO: outro agente já está operando este token agora. Aguarde ou escolha outro."; break }
                                            }
                                            if (refusal != null) {
                                                consumed.forEach { TradeCoordinator.gate.restore(it) }
                                                legs.forEach { TradeCoordinator.release("solana", it.mint, agentId) }
                                                refusal!!
                                            } else {
                                                val rpc = SolanaRpcClient(endpoint = network.rpcEndpoint, network = network)
                                                val exec = dev.cueopen.app.wallet.SolanaSwapExecutor(ctx.applicationContext, wallet, rpc)
                                                when (val r = TradeCoordinator.walletMutex.withLock { exec.executeSwap(input, output, amount, slippage, network) }) {
                                                    is dev.cueopen.app.wallet.SwapResult.Submitted ->
                                                        "Swap ENVIADO na ${network.displayName}. Saída estimada: ${r.outAmountRaw} (unidades mínimas). Impacto: ${r.priceImpactPct}%. Rota: ${r.routeLabels.joinToString("+").ifBlank { "direta" }}. Assinatura: ${r.signature}. Explorer: ${network.transactionExplorerUrl(r.signature)}"
                                                    is dev.cueopen.app.wallet.SwapResult.Rejected -> {
                                                        consumed.forEach { TradeCoordinator.gate.restore(it) }
                                                        legs.forEach { TradeCoordinator.release("solana", it.mint, agentId) }
                                                        "Swap recusado: ${r.reason}"
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                "trade_set_rule" -> {
                                    val triggerRaw = s("trigger").lowercase()
                                    val trig = when (triggerRaw) {
                                        "price_below", "below" -> dev.cueopen.app.wallet.DayTradeStore.Trigger.PRICE_BELOW
                                        "price_above", "above" -> dev.cueopen.app.wallet.DayTradeStore.Trigger.PRICE_ABOVE
                                        else -> null
                                    }
                                    val amount = s("amount_raw").toLongOrNull()
                                    val target = s("target_price_usd").toDoubleOrNull()
                                    when {
                                        trig == null -> "trigger deve ser price_below ou price_above."
                                        amount == null || amount <= 0L -> "amount_raw deve ser inteiro positivo em unidades mínimas."
                                        target == null || target <= 0.0 -> "target_price_usd deve ser um decimal positivo."
                                        dev.cueopen.app.trading.SwapLegs.legs(s("input_mint"), s("output_mint")).any { it.side == dev.cueopen.app.trading.Side.BUY } ->
                                            "Recusado: regras automáticas só podem ser de SAÍDA (stop-loss/take-profit: vender o token para SOL/USDC). Para COMPRAR, valide com typesafe_jev_check e use wallet_swap."
                                        !dev.cueopen.app.wallet.SolanaAddressValidator.isValidAddress(s("input_mint")) -> "input_mint inválido."
                                        !dev.cueopen.app.wallet.SolanaAddressValidator.isValidAddress(s("output_mint")) -> "output_mint inválido."
                                        !dev.cueopen.app.wallet.SolanaAddressValidator.isValidAddress(s("watch_mint")) -> "watch_mint inválido."
                                        else -> {
                                            val rule = dev.cueopen.app.wallet.DayTradeStore.Rule(
                                                id = java.util.UUID.randomUUID().toString().take(8),
                                                agentId = agentId,
                                                label = s("label").ifBlank { "regra" }.take(60),
                                                network = network.name,
                                                inputMint = s("input_mint"),
                                                outputMint = s("output_mint"),
                                                amountRaw = amount,
                                                watchMint = s("watch_mint"),
                                                trigger = trig,
                                                targetPriceUsd = target,
                                                slippageBps = (i("slippage_bps") ?: 100).coerceIn(1, 5000)
                                            )
                                            dev.cueopen.app.wallet.DayTradeStore(ctx.applicationContext).upsert(rule)
                                            // Mantém o processo vivo para o monitoramento AUTOMÁTICO de SL/TP (avaliação a cada ~20s).
                                            runCatching { dev.cueopen.app.sandbox.SandboxService.start(ctx, "Monitorando stop-loss/take-profit") }
                                            "Regra de day trade ARMADA (ID: ${rule.id}). O sistema vai monitorar o preço real automaticamente (a cada ~20s) e EXECUTAR a troca sozinho quando o alvo for atingido — você NÃO precisa ficar chamando trade_list para isso acontecer."
                                        }
                                    }
                                }
                                "trade_cancel" -> {
                                    val ok = dev.cueopen.app.wallet.DayTradeStore(ctx.applicationContext).cancel(s("rule_id"), agentId)
                                    if (ok) "Regra ${s("rule_id")} cancelada." else "Nenhuma regra armada com esse ID para este agente."
                                }
                                "trade_update_rule" -> {
                                    val newTarget = s("target_price_usd").toDoubleOrNull()
                                    val newTrig = when (s("trigger").lowercase()) {
                                        "price_below", "below" -> dev.cueopen.app.wallet.DayTradeStore.Trigger.PRICE_BELOW
                                        "price_above", "above" -> dev.cueopen.app.wallet.DayTradeStore.Trigger.PRICE_ABOVE
                                        else -> null
                                    }
                                    when {
                                        newTarget == null || newTarget <= 0.0 -> "target_price_usd deve ser um decimal positivo."
                                        else -> {
                                            val updated = dev.cueopen.app.wallet.DayTradeStore(ctx.applicationContext)
                                                .updateTarget(s("rule_id"), agentId, newTarget, newTrig)
                                            if (updated == null) "Nenhuma regra ARMADA com esse ID para este agente (só é possível mover regras ainda não executadas/canceladas)."
                                            else {
                                                runCatching { dev.cueopen.app.sandbox.SandboxService.start(ctx, "Monitorando stop-loss/take-profit") }
                                                val dir = if (updated.trigger == dev.cueopen.app.wallet.DayTradeStore.Trigger.PRICE_BELOW) "≤" else "≥"
                                                "Regra ${updated.id} (${updated.label}) AJUSTADA: novo alvo $dir US$ ${updated.targetPriceUsd}. Continua sendo monitorada e executa sozinha ao atingir."
                                            }
                                        }
                                    }
                                }
                                else -> { // trade_list (+ avaliação)
                                    val dtStore = dev.cueopen.app.wallet.DayTradeStore(ctx.applicationContext)
                                    val evaluate = a["evaluate"]?.jsonPrimitive?.booleanOrNull ?: true
                                    val evalLines = if (evaluate) {
                                        val engine = dev.cueopen.app.wallet.DayTradeEngine(ctx.applicationContext, wallet, market)
                                        runCatching {
                                            engine.evaluateAgentRules(agentId, network, policies.agentMaxTransferLamports(agentId))
                                        }.getOrDefault(emptyList()).map { it.message }
                                    } else emptyList()
                                    val rules = dtStore.allForAgent(agentId)
                                    buildString {
                                        if (evalLines.isNotEmpty()) { appendLine("== Avaliação agora =="); evalLines.forEach { appendLine(it) }; appendLine() }
                                        if (rules.isEmpty()) append("Nenhuma regra de day trade para este agente.")
                                        else {
                                            appendLine("== Regras ==")
                                            rules.forEach { r ->
                                                val sym = SolanaRpcClient.knownSymbol(r.watchMint) ?: r.watchMint.take(6)
                                                appendLine("${r.id} [${r.status}] ${r.label}: $sym ${if (r.trigger == dev.cueopen.app.wallet.DayTradeStore.Trigger.PRICE_BELOW) "≤" else "≥"} US$ ${r.targetPriceUsd}; últ. preço US$ ${r.lastPriceUsd}${if (r.resultSignature.isNotBlank()) "; sig ${r.resultSignature}" else ""}")
                                            }
                                        }
                                    }.trim()
                                }
                            }
                        }
                    }
                }
                "shell" -> {
                    // Guardrail determinístico: recusa invocações diretas de scripts inexistentes,
                    // em vez de mandar bash executar um caminho alucinado e produzir ENOENT.
                    val command = SecretVault.resolve(sessionId, s("command")).trim()
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
                    ShellOutput.formatExecutionResult(r.output, r.exitCode, r.timedOut)
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
                                val content = SecretVault.resolve(sessionId, s("content"))
                                if (append && f.exists()) f.appendText(content) else f.writeText(content)
                                val lines = f.useLines { it.count() }
                                val preview = runCatching { f.useLines { seq -> seq.take(10).joinToString("\n") } }.getOrDefault("")
                                "OK: ${if (append) "conteúdo acrescentado" else "arquivo salvo"} (${f.name}) — ${f.length()} bytes, $lines linhas.\n[Prévia das primeiras 10 linhas]\n$preview"
                            }
                        }
                    }
                }
                "file_edit" -> {
                    val path = s("path").trim()
                    if (path.isBlank()) "ERRO: informe path."
                    else {
                        val workspace = sandbox.workspaceFor(sessionId).canonicalFile
                        val target = runCatching { sandbox.hostFile(path, sessionId).canonicalFile }.getOrNull()
                        when {
                            target == null -> "ERRO: caminho inválido."
                            target.path != workspace.path && !target.path.startsWith(workspace.path + File.separator) ->
                                "ERRO: file_edit só pode modificar arquivos dentro do workspace desta tarefa."
                            !target.isFile -> "ERRO: arquivo existente não encontrado: $path. Use file_list/file_read antes de editar."
                            else -> {
                                val oldText = SecretVault.resolve(sessionId, s("old_text"))
                                val newText = SecretVault.resolve(sessionId, s("new_text"))
                                val result = AtomicTextFile.replace(target, oldText, newText, b("allow_multiple"))
                                if (!result.success) "ERRO: ${result.error ?: "substituição recusada"}"
                                else {
                                    val mode = if (result.strategy == "exact") "literal" else "whitespace"
                                    "OK: ${target.relativeTo(workspace).path}: ${result.replacements} ocorrência(s) substituída(s) ($mode)."
                                }
                            }
                        }
                    }
                }
                "file_read" -> readFileChunk(sandbox.hostFile(s("path"), sessionId), s("path"), i("offset") ?: 1, i("limit") ?: 250)
                "file_list" -> {
                    val d = if (s("path").isBlank()) sandbox.workspaceFor(sessionId) else sandbox.hostFile(s("path"), sessionId)
                    d.walkTopDown().maxDepth(3).filter { it != d }.take(300)
                        .joinToString("\n") { (if (it.isDirectory) "📁 " else "📄 ") + it.relativeTo(d).path + if (it.isFile) " (${it.length()}b)" else "" }
                        .ifBlank { "(vazio)" }
                }
                "browser_navigate" -> {
                    // Caminhos do sandbox (/root, /public, /home ou file:///root/…) viram o arquivo real do aparelho:
                    // o WebView não enxerga o filesystem do proot (era a causa de net::ERR_FILE_NOT_FOUND).
                    val raw = SecretVault.resolve(sessionId, s("url")).trim()
                    val guest = raw.removePrefix("file://").let { if (raw.startsWith("file://")) it.substringBefore('?').substringBefore('#') else it }
                    val isSandboxPath = guest.startsWith("/root") || guest.startsWith("/public") || guest.startsWith("/home") || (raw.startsWith("file://") && !guest.startsWith("/data/") && !guest.startsWith("/storage/") && !guest.startsWith("/sdcard"))
                    val hostF = if (isSandboxPath) sandbox.hostFile(guest, sessionId) else null
                    if (hostF != null && !hostF.exists()) {
                        "ERROR: arquivo não existe no workspace: $guest. Use file_list para achar o caminho certo (arquivos de conectores ficam em /root/connectors/<id>/workspace)."
                    } else {
                        val url = hostF?.let { "file://" + it.absolutePath } ?: raw
                        val navigation = browserFor(sessionId).navigate(url)
                        if (navigation.startsWith("ERROR:") || navigation.startsWith("TIMEOUT:")) navigation +
                            (if (navigation.contains("ERR_NAME_NOT_RESOLVED")) "\nO domínio não existe/não resolveu: confira a URL (não invente endereços) ou pesquise o site oficial." else "")
                        else {
                            val page = browserFor(sessionId).read(4000)
                            val notFound = Regex("(?i)(t[ií]tulo:[^\\n]*(404|not found|n[aã]o encontrad)|this page doesn.t exist|page not found|p[aá]gina n[aã]o encontrada)").containsMatchIn(page.take(600))
                            (if (notFound) "⚠ A página carregou, mas é um ERRO 404 (não existe). Não use este conteúdo; procure o endereço correto.\n" else "") + navigation + "\n\n" + page
                        }
                    }
                }
                "browser_read", "browser_snapshot" -> browserFor(sessionId).read((i("max_chars") ?: 9000).coerceIn(1000, 20000))
                "browser_click" -> browserFor(sessionId).click(i("index") ?: -1)
                "browser_type" -> browserFor(sessionId).type(i("index") ?: -1, SecretVault.resolve(sessionId, s("text")), b("submit"))
                "browser_scroll" -> browserFor(sessionId).scroll(s("direction") != "up")
                "browser_back" -> { browserFor(sessionId).back(); "ok" }
                "browser_js" -> browserFor(sessionId).runJs(SecretVault.resolve(sessionId, s("code")))
                "device_notifications" -> dev.cueopen.app.device.NotifStore.recent(s("filter"), (i("limit") ?: 30).coerceIn(1, 100))
                    .joinToString("\n") { "[${java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it.time))}] ${it.app}: ${it.title} — ${it.text}".take(300) }
                    .ifBlank { "Nenhuma notificação capturada ainda." }
                "device_calendar_read" -> dev.cueopen.app.device.DeviceActions.calendar(ctx, i("days") ?: 7)
                "device_calendar_create" -> dev.cueopen.app.device.DeviceActions.createCalendarEvent(
                    ctx, s("title"), s("start_epoch_ms").toLongOrNull() ?: 0L, s("end_epoch_ms").toLongOrNull() ?: 0L, s("description"), s("location"))
                "device_contacts" -> dev.cueopen.app.device.DeviceActions.contacts(ctx, s("query"))
                "device_call_log" -> dev.cueopen.app.device.DeviceActions.callLog(ctx, i("limit") ?: 20)
                "device_location" -> dev.cueopen.app.device.DeviceActions.location(ctx)
                "device_bluetooth" -> dev.cueopen.app.device.DeviceActions.bluetoothDevices(ctx)
                "device_bluetooth_pair" -> dev.cueopen.app.device.DeviceActions.openBluetoothSettings(ctx)
                "device_dial" -> dev.cueopen.app.device.DeviceActions.openDialer(ctx, s("phone"))
                "device_microphone" -> dev.cueopen.app.device.DeviceActions.microphone(ctx)
                "device_accessibility_open_app" -> dev.cueopen.app.device.DeviceAccessibilityActions.openApp(ctx, sessionId, s("app"))
                "device_accessibility_read_screen" -> dev.cueopen.app.device.DeviceAccessibilityActions.readScreen(sessionId)
                "device_accessibility_inspect_image" -> {
                    val request = store.session(sessionId)?.items?.lastOrNull { it is ChatItem.User } as? ChatItem.User
                    when {
                        request == null || !dev.cueopen.app.device.AccessibilityVisualInspectionPolicy
                            .explicitlyRequestsPostOrImage(request.text) ->
                            "RECUSADO: captura visual só é permitida como fallback de um pedido explícito para ler/analisar um post de app social."
                        !dev.cueopen.app.device.AccessibilityVisualInspectionPolicy.canInspectImage(
                            request.text,
                            currentScreenWasRead = sessionId in accessibilityReadScreenCompleted,
                            inspectionAlreadyUsed = sessionId in visualInspectionUsed,
                        ) -> if (sessionId in visualInspectionUsed)
                            "RECUSADO: já fiz a única inspeção visual desta tarefa; use o resultado recebido e não capture outras telas/posts."
                        else "RECUSADO: primeiro leia a tela acessível atual e verifique o texto/descrição da imagem; só então use este fallback se a árvore não revelar o conteúdo."
                        else -> {
                            visualInspectionUsed.add(sessionId)
                            val jpeg = try {
                                dev.cueopen.app.device.DeviceAccessibilityActions.inspectImage(
                                    sessionId, s("snapshot_id"), i("element_id") ?: -1,
                                )
                            } catch (t: Throwable) {
                                if (isStaleAccessibilitySnapshotFailure("device_accessibility_inspect_image", t.message.orEmpty()))
                                    visualInspectionUsed.remove(sessionId)
                                throw t
                            }
                            val prompt = "Analise apenas o recorte do elemento do post já solicitado pelo usuário. " +
                                "Descreva o conteúdo visual relevante e qualquer texto legível; diferencie o que é visível do que não dá para afirmar. " +
                                "Responda em português e não faça inferências sobre pessoas, identidade ou contexto além do recorte. " +
                                "Pedido do usuário: ${request.text.take(1200)}"
                            val visual = try {
                                llm.inspectImage(prompt, jpeg, model = model, conversationId = sessionId)
                            } finally {
                                jpeg.fill(0)
                            }
                            "Análise visual temporária do recorte solicitado (imagem não salva no histórico): $visual"
                        }
                    }
                }
                "device_accessibility_click" -> dev.cueopen.app.device.DeviceAccessibilityActions.click(
                    sessionId, s("snapshot_id"), i("element_id") ?: -1)
                "device_accessibility_type" -> {
                    val rawText = s("text")
                    val exactSecret = SecretVault.resolveExact(sessionId, rawText)
                    dev.cueopen.app.device.DeviceAccessibilityActions.typeText(
                        sessionId, s("snapshot_id"), i("element_id") ?: -1,
                        exactSecret ?: SecretVault.resolve(sessionId, rawText), exactSecret != null)
                }
                "device_accessibility_scroll" -> dev.cueopen.app.device.DeviceAccessibilityActions.scroll(
                    sessionId, s("snapshot_id"), i("element_id") ?: -1, s("direction"))
                "device_accessibility_back" -> dev.cueopen.app.device.DeviceAccessibilityActions.back(sessionId)
                "device_notify" -> { notifyRaw(s("title").ifBlank { "CueOpen" }, plainText(s("text"))); "Notificação enviada." }
                "device_overlay" -> { dev.cueopen.app.device.Overlay.show(ctx, s("text")); "Balão exibido." }
                "device_screenshot" -> {
                    val request = store.session(sessionId)?.items?.lastOrNull { it is ChatItem.User } as? ChatItem.User
                    when {
                        request != null && dev.cueopen.app.device.AccessibilityVisualInspectionPolicy
                            .explicitlyRequestsPostOrImage(request.text) ->
                            "RECUSADO: não vou capturar a tela inteira para ler um post. Use a árvore acessível e, somente se ela não revelar a imagem, device_accessibility_inspect_image no recorte atual."
                        request == null || !dev.cueopen.app.device.DeviceScreenshotPolicy
                            .explicitlyRequestsFullScreenCapture(request.text) ->
                            "RECUSADO: screenshot da tela inteira requer pedido explícito do usuário; não use como inspeção automática."
                        else -> {
                            val f = java.io.File(sandbox.workspaceFor(sessionId), "screenshot_${System.currentTimeMillis()}.png")
                            if (dev.cueopen.app.device.ScreenCapture.capture(ctx, f)) {
                                store.addItem(sessionId, ChatItem.Media(path = f.absolutePath, caption = "Captura de tela"))
                                "Captura salva e exibida: ${f.name}"
                            } else "Não foi possível capturar (permissão de captura não concedida)."
                        }
                    }
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
                else -> if (call.name.startsWith("mcp_")) dev.cueopen.app.connectors.Connectors.callTool(store, call.name, resolveArgs(sessionId, a))
                        else if (call.name.startsWith("uic_")) {
                            val ag = store.agent(store.session(sessionId)?.agentId)
                            dev.cueopen.app.CueApp.instance.uiConnectors.callTool(ag, ag?.id ?: sessionId, call.name, resolveArgs(sessionId, a)).let { SecretVault.redact(sessionId, it) }.also { out ->
                                // Falhas reais do conector entram no log de erros (sem expor segredos: já redigido).
                                if (out.startsWith("ERRO") || out.startsWith("RECUSAD"))
                                    runCatching { dev.cueopen.app.diag.ErrorLog.log("uiconnector", out.take(600), sessionId, call.name) }
                            }
                        }
                        else "Ferramenta desconhecida: ${call.name}"
            }
        } catch (c: kotlinx.coroutines.CancellationException) {
            executions.cancel(executionId)
            store.replaceItem(sessionId, step.copy(result = "Execução interrompida.", status = StepStatus.ERROR))
            throw c
        } catch (t: Throwable) {
            val detail = t.message.orEmpty()
            val accessibilityTool = call.name.startsWith("device_accessibility_")
            val staleSnapshot = isStaleAccessibilitySnapshotFailure(call.name, detail)
            val freshScreen = if (staleSnapshot) try {
                dev.cueopen.app.device.DeviceAccessibilityActions.readScreen(sessionId)
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (refreshError: Throwable) {
                "A releitura automática falhou: ${refreshError.message?.take(180) ?: refreshError.javaClass.simpleName}."
            } else ""
            val toolOutput = if (staleSnapshot) {
                "ERRO: $detail. Nenhuma ação foi executada. Atualizei a tela automaticamente; descarte os IDs antigos e reidentifique o alvo com estes dados transitórios:\n$freshScreen"
            } else "Erro: ${detail.take(300)}"
            val safeDetail = if (accessibilityTool) "Falha Android; conteúdo de tela omitido por privacidade." else detail
            executions.fail(executionId, safeDetail)
            runCatching { dev.cueopen.app.diag.ErrorLog.log(errorSource(call.name), safeDetail, sessionId, call.name) }
            store.replaceItem(sessionId, step.copy(result = safeDetail, status = StepStatus.ERROR))
            return ToolResult(toolOutput)
        }
        val out = SecretVault.redact(sessionId, out0)
        val commandFailed = Regex("""\[exit ([1-9]\d*)\]""").containsMatchIn(out)
        val isError = out.startsWith("ERROR:") || out.startsWith("TIMEOUT:") || out.startsWith("ERRO:") || commandFailed
        // Marcadores de falha específicos de ferramentas (não começam com ERRO:) também viram log.
        val markerError = Regex("""(MARKET_ERROR|WALLET_RPC_ERROR|GUARDRAIL|No such file|not found|não encontrad|Recusado|Acesso negado)""")
            .containsMatchIn(out.take(400))
        val accessibilityTool = call.name.startsWith("device_accessibility_")
        val safeExecutionOutput = if (accessibilityTool)
            "Resultado Android transitório omitido dos diagnósticos; nenhum conteúdo de tela foi salvo."
        else out
        if (isError) executions.fail(executionId, safeExecutionOutput) else executions.finish(executionId, safeExecutionOutput)
        if (isError || markerError) {
            val safeLog = if (accessibilityTool) "Falha de ferramenta Android; conteúdo de tela omitido por privacidade." else out.take(1000)
            runCatching { dev.cueopen.app.diag.ErrorLog.log(errorSource(call.name), safeLog, sessionId, call.name) }
        }
        val safeStepOutput = if (accessibilityTool)
            "Resultado de acessibilidade omitido do histórico por privacidade; conteúdo disponível apenas durante a tarefa ativa."
        else out.take(4000)
        store.replaceItem(sessionId, step.copy(result = safeStepOutput, status = if (commandFailed || out.startsWith("ERROR:") || out.startsWith("TIMEOUT:") || out.startsWith("ERRO:")) StepStatus.ERROR else StepStatus.OK))
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
                            "\n\nVocê roda em MODO ISOLADO: tem seu PRÓPRIO terminal, seu PRÓPRIO browser e seu PRÓPRIO workspace — " +
                            "nada do que você fizer no terminal/browser afeta o agente principal nem os outros workers. " +
                            "Trabalhe somente no seu workspace. Não faça merge nem push; não chame multi_agents. " +
                            "Não afirme que testes passaram sem executá-los. Ao concluir, reporte resumo, arquivos alterados, " +
                            "comandos de teste executados, resultados e riscos — o agente principal integra tudo e fecha seu ambiente."
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
            // Encerra os recursos ISOLADOS do subagente (terminal próprio + browser próprio): cada worker
            // roda em modo isolado e, ao terminar, devolve o controle ao agente principal sem deixar
            // sessão de terminal/WebView viva consumindo recursos.
            runCatching { terminals.close(child.id) }
            runCatching { dev.cueopen.app.CueApp.instance.closeBrowser(child.id) }
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
