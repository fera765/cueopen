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

internal suspend fun AgentRunner.loop(sessionId: String, maxStepsOverride: Int? = null, tokenBudgetOverride: Long? = null) {
    store.session(sessionId)?.let { existing ->
        if (AccessibilityToolHistory.sanitizePersistedSession(existing) != existing)
            store.editSession(sessionId) { AccessibilityToolHistory.sanitizePersistedSession(it) }
    }
    // Teto explícito (fluxos, workers) vale mesmo acima do limite do chat; antes era cortado para o padrão (14) e os fluxos paravam sem entregar.
    val maxSteps0 = (maxStepsOverride ?: store.settings.maxSteps).coerceIn(1, maxOf(store.settings.maxSteps, maxStepsOverride ?: 0, 1))
    val maxSteps = if (sessionId in finalDelivery) maxSteps0.coerceAtMost(15) else maxSteps0
    val sectionMember = store.session(sessionId)?.let { s -> s.sectionId != null && store.section(s.sectionId)?.sessionId != sessionId } == true
    val startingTokens = store.session(sessionId)?.let { it.tokensIn + it.tokensOut } ?: 0L
    var steps = 0
    val itemsAtRunStart = store.session(sessionId)?.items?.size ?: 0
    var multiRequestChecked = false
    var repeats = 0
    var lastContent = ""
    var lastToolSig = ""
    // Janela das últimas respostas de texto (detecta repetição mesmo alternando frases).
    val recentTexts = ArrayDeque<String>()
    // Quantas vezes seguidas o modelo ANUNCIOU uma ação ("vou pesquisar…") sem chamar ferramenta.
    var intentNoActionTurns = 0
    var emptyReplyTurns = 0
    // Supervisão de agente de Seção: rodadas só com ferramentas, sem nenhuma frase de feedback.
    var silentRounds = 0
    // Detecção de loop de ferramentas: mesma chamada (nome+args) repetida sem progresso.
    val toolLoops = ToolLoopDetector()
    // Assinaturas dos resultados das duas últimas rodadas: texto repetido com resultados mudando é progresso.
    var lastRoundResults = ""
    var prevRoundResults = ""
    // Reutiliza resultados idênticos do builder somente nesta invocação do loop; evita dry-runs/cards duplicados aqui.
    val connectorBuilderResults = ExactToolCallMemo<ToolResult>()
    val invalidAccessibilitySnapshots = dev.fluwy.app.device.InvalidAccessibilitySnapshotGuard()
    val visualInspectionUsed = mutableSetOf<String>()
    val accessibilityReadScreenCompleted = mutableSetOf<String>()
    val transientAccessibilityOutputs = linkedMapOf<String, String>()
    var toolLoopNudges = 0
    var cleanRounds = 0
    val truncatedOutput = LlmTruncationPolicy(maxFragments = if (tokenBudgetOverride == null) 3 else 1)
    val fileToolFailureGuard = AgentFileToolFailureGuard()
    val repeatGuard = RepeatedToolFailureGuard()
    var uselessCompactions = 0
    var roundNo = 0; var lastEpoch = ctxMgr.clearEpochOf(sessionId); var epochChangedAt = -100
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
            val before = s.compactions
            val tokensBefore = ctxMgr.estimateTokens(s.llm) + ctxMgr.estimateTokens(s.summary)
            s = try { ctxMgr.compactIfNeeded(s) } catch (c: kotlinx.coroutines.CancellationException) { throw c } catch (t: Throwable) {
                // Compactar NUNCA pode derrubar o agente: corta o histórico antigo com segurança e segue.
                runCatching { dev.fluwy.app.diag.ErrorLog.log("agent", "Falha na compactação (${t.javaClass.simpleName}: ${t.message}); usando corte seguro.\n" + t.stackTraceToString().take(1_500), sessionId, "compact") }
                ctxMgr.safeTrim(s)
            }
            // Guarda pós-compactação (OpenClaw): se o agente repetir o mesmo loop logo depois, interrompe.
            if (s.compactions > before) toolLoops.armPostCompaction()
            // OpenBot 0.71: compactar sem liberar espaço vira ciclo (um chat chegou a 559 compactações). Na 3ª, para.
            var tokensAfter = ctxMgr.estimateTokens(s.llm) + ctxMgr.estimateTokens(s.summary)
            // O resumo não liberou espaço (o peso está no fim do histórico): corte forte antes de desistir.
            if (CompactionProgress.useless(tokensBefore, tokensAfter, store.settings.contextBudget)) {
                s = ctxMgr.hardShrink(s)
                tokensAfter = ctxMgr.estimateTokens(s.llm) + ctxMgr.estimateTokens(s.summary)
            }
            if (CompactionProgress.useless(tokensBefore, tokensAfter, store.settings.contextBudget)) {
                if (++uselessCompactions >= CompactionProgress.MAX_USELESS) {
                    runCatching { dev.fluwy.app.diag.ErrorLog.log("agent", "Compactação sem efeito $uselessCompactions vezes ($tokensBefore → $tokensAfter tokens; orçamento ${store.settings.contextBudget}); turno encerrado.", sessionId, "compact") }
                    store.addItemUnique(sessionId, ChatItem.Error(text = CompactionProgress.message(tokensAfter, store.settings.contextBudget)))
                    store.editSession(sessionId) { it.copy(status = SessionStatus.WAITING_USER) }
                    return
                }
            } else uselessCompactions = 0
        }
        // Planejamento contextual: decide quais ferramentas/conectores e quanto conhecimento/memória entram neste passo
        val allTools = tools(agent, allowMultiAgent = tokenBudgetOverride == null, builder = s.connectorBuilder, connectorEditId = s.connectorEditId, sectionId = s.sectionId, scriptBuilder = s.scriptBuilder, webApiBuilder = s.webApiBuilder)
        if (s.scriptBuilder || s.webApiBuilder) ToolContextPlanner.load(sessionId, listOf("web_dev"))
        // Pages ficam à mão numa seção (quadro do time) e onde já existe alguma página.
        if (s.sectionId != null || store.pagesIn(pageSpace(sessionId)).isNotEmpty()) ToolContextPlanner.load(sessionId, listOf("pages"))  // browser_js/network/console sempre à mão
    val (contextPlan, stepToolsPlanned) = toolContextPlan(sessionId, s, allTools)
        // Entrega final após "pare": só ler/escrever/enviar/encerrar.
        val stepTools = if (sessionId in finalDelivery) kotlinx.serialization.json.JsonArray(allTools.filter { toolName(it) in FINAL_DELIVERY_TOOLS })
            // Agente do exército: só o que constrói software (arquivos, terminal, pesquisa, leitura do preview). Antes ia o
            // catálogo inteiro (~62 schemas, ~12 mil tokens por chamada: agenda, cofre, Pages, cards…) sem uso nenhum.
            else if (s.squadParentId != null) kotlinx.serialization.json.JsonArray(stepToolsPlanned.filter { toolName(it) in SQUAD_WORKER_TOOLS })
            else stepToolsPlanned
        // O schema enviado ao provider também é uma allowlist de execução para esta volta.
        val exposedToolNames = stepTools.mapNotNull(::toolName).toSet()
        ExecutionLedger.setAllowed(sessionId, exposedToolNames)
        val sys = LlmMessage("system", systemPrompt(sessionId, agent, s, contextPlan, allowDeviceTools = tokenBudgetOverride == null))
        val activeHistory = s.llm.map { AccessibilityToolHistory.forActiveModel(it, transientAccessibilityOutputs) }
        var history = ctxMgr.microCompact(activeHistory, sessionId)
        if (history.firstOrNull()?.role != "user") history = listOf(LlmMessage("user", dev.fluwy.app.prompts.Prompts.render("loop.18", mapOf<String, Any?>("lb" to "{{")))) + history
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
            // 2048 cortava o file_write de um componente inteiro: os argumentos chegavam vazios ({}) ou sem content.
            safeOutput.coerceAtMost(16_000L).toInt()
        } ?: 16_000  // modelos com raciocínio gastam parte disso pensando: 8192 cortava respostas com código grande
        val llmT0 = System.currentTimeMillis()
        val replyRaw = callModelWithRecovery(sessionId, agent, sys, history, stepTools, replyTokenLimit, tokenBudgetOverride) ?: return
        // Nomes de ferramenta "de hábito" (grep, read_file, fetch…) viram a ferramenta real exposta.
        val reply = replyRaw.copy(toolCalls = replyRaw.toolCalls.map { tc -> squadFixArgs(sessionId, tc.copy(name = ToolAliases.resolve(tc.name, exposedToolNames))) })
        val modelId = stepModel(agent)
        dev.fluwy.app.diag.ActivityLog.llm(sessionId, modelId, true, System.currentTimeMillis() - llmT0,
            "tokens in=${reply.usage.prompt} out=${reply.usage.completion} · ferramentas=" + reply.toolCalls.joinToString(",") { it.name }.ifBlank { "nenhuma (texto)" })
        dev.fluwy.app.llm.UsageLedger.record(ctx, store.agent(store.session(sessionId)?.agentId)?.name ?: "Sem agent", modelId,
            dev.fluwy.app.llm.ProviderCatalog.activeId(store.settings), reply.usage.prompt.toLong(), reply.usage.completion.toLong())
        autoResumeRound.remove(sessionId)  // o modelo voltou a responder: zera a escada de retomadas
        if (pendingRecovery.remove(sessionId)) store.session(sessionId)?.items?.filterIsInstance<ChatItem.LlmRecovery>()
            ?.filter { it.state == "waiting" }?.forEach { store.replaceItem(sessionId, it.copy(state = "resolved")) }
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
        if (reply.truncated && reply.toolCalls.isEmpty()) {
            val decision = truncatedOutput.accept(reply.content)
            if (decision == LlmTruncationPolicy.Decision.CONTINUE) {
                store.editSession(sessionId) { it.copy(llm = it.llm + LlmMessage("user",
                    dev.fluwy.app.prompts.Prompts.render("loop.17", mapOf<String, Any?>("lb" to "{{")))) }
                setActivity(sessionId, Activity(Activity.Kind.THINKING, "Continuando resposta cortada"))
                if (++steps >= maxSteps) {
                    val partial = truncatedOutput.partialText().trim()
                    if (partial.isNotBlank()) store.addItem(sessionId, ChatItem.Assistant(text = partial, final = false))
                    store.addItemUnique(sessionId, ChatItem.Error(text = "A resposta foi cortada e o limite de passos foi atingido. Preservei o trecho parcial; envie continuar para retomar."))
                    store.editSession(sessionId) { it.copy(status = SessionStatus.WAITING_USER) }
                    return
                }
                continue
            }
            val partial = truncatedOutput.partialText().trim()
            if (partial.isNotBlank()) store.addItem(sessionId, ChatItem.Assistant(text = partial, final = false))
            runCatching { dev.fluwy.app.diag.ErrorLog.log("agent", "Texto LLM truncado sem continuação útil; trecho parcial preservado.", sessionId, "llm") }
            store.addItemUnique(sessionId, ChatItem.Error(text = "O modelo repetiu o trecho truncado ou atingiu o limite seguro de continuação. Preservei o texto parcial; envie continuar ou reduza o pedido."))
            store.editSession(sessionId) { it.copy(status = SessionStatus.WAITING_USER) }
            return
        }
        val displayContent = truncatedOutput.complete(reply.content)
        if (sectionMember) silentRounds = if (displayContent.isBlank() && reply.toolCalls.isNotEmpty()) silentRounds + 1 else 0
        // Anti-repetição: se o modelo repetir quase o mesmo texto várias vezes, interrompe.
        // Só é repetição real se o texto for quase igual E as ações também forem as mesmas
        val resultLong = round.resultLong
        // (mesmas ferramentas com os mesmos argumentos). Mesma ferramenta com argumentos
        // diferentes (ex.: navegar em URLs distintas) é progresso, não loop.
        val cNow = displayContent.trim()
        val curToolSig = reply.toolCalls.joinToString(";") { it.name + "|" + normArgs(it.argsJson) }
        // Mesma ação só é "parada" se os resultados também não mudaram (polling legítimo devolve dados novos).
        val sameActions = reply.toolCalls.isEmpty() || (curToolSig == lastToolSig && lastRoundResults == prevRoundResults)
        lastToolSig = curToolSig
        // Repetição de texto: compara com a JANELA das últimas respostas (não só a anterior), para pegar
        // o modelo que fica alternando 2–3 frases quase iguais sem progredir. Só conta como repetição se
        // as AÇÕES também forem as mesmas (mesma ferramenta+args, ou nenhuma) — URLs/args diferentes = progresso.
        if (cNow.length > 15 && sameActions && recentTexts.any { similar(cNow, it) }) {
            if (++repeats >= 2) {
                runCatching { dev.fluwy.app.diag.ErrorLog.log("agent", "Modelo entrou em repetição (interrompido).", sessionId, "llm") }
                store.addItemUnique(sessionId, ChatItem.Error(text = "O modelo entrou em repetição. Interrompi para não gastar tokens à toa. Reformule o pedido ou troque de modelo."))
                store.editSession(sessionId) { it.copy(status = SessionStatus.WAITING_USER) }
                return
            }
        } else repeats = 0
        if (cNow.isNotEmpty()) { recentTexts.addLast(cNow); while (recentTexts.size > 3) recentTexts.removeFirst() }
        lastContent = cNow
        if (displayContent.isNotBlank()) {
            val txt = displayContent.trim()
            val isFinal = reply.toolCalls.isEmpty()
            // Evita bolhas repetidas: se a última mensagem do assistente foi uma narração (não-final) quase
            // igual a esta, substitui em vez de empilhar outra igual ("Vou aguardar e verificar…" repetido).
            val lastA = store.session(sessionId)?.items?.lastOrNull { it is ChatItem.Assistant } as? ChatItem.Assistant
            val collapse = lastA != null && !lastA.final && !isFinal && txt.length > 20 && similar(txt, lastA.text.trim())
            // Acabou de responder em áudio (reply_audio): não repete um texto de fecho ("Prontinho!") depois do áudio.
            val afterAudio = isFinal && store.session(sessionId)?.items?.lastOrNull { it !is ChatItem.Step } is ChatItem.Audio
            if (afterAudio) Unit
            else if (collapse) store.replaceItem(sessionId, lastA!!.copy(text = txt))
            else store.addItem(sessionId, ChatItem.Assistant(text = txt, final = isFinal))
        }
        if (reply.toolCalls.isEmpty()) {
            // Regra de AÇÃO OBRIGATÓRIA: o modelo ANUNCIOU uma ação ("vou pesquisar/verificar/testar…") mas
            // não chamou nenhuma ferramenta. Em vez de encerrar (ou só dormir) sem agir, força a execução na
            // próxima volta; se insistir, interrompe. Isso quebra o loop de "anúncios" sem progresso.
            if (cNow.isNotBlank() && hasActionIntent(cNow)) {
                if (++intentNoActionTurns >= 3) {
                    runCatching { dev.fluwy.app.diag.ErrorLog.log("agent", "Modelo anunciou ações sem executá-las (interrompido).", sessionId, "llm") }
                    store.addItemUnique(sessionId, ChatItem.Error(text = "O modelo ficou anunciando ações (\"vou pesquisar…\") sem executá-las. Interrompi para não gastar tokens à toa. Reformule o pedido ou troque de modelo."))
                    store.editSession(sessionId) { it.copy(status = SessionStatus.WAITING_USER) }
                    return
                }
                store.editSession(sessionId) { it.copy(llm = it.llm + LlmMessage("user",
                    dev.fluwy.app.prompts.Prompts.render("loop.16", mapOf<String, Any?>("lb" to "{{")))) }
                if (++steps >= maxSteps) {
                    if (store.session(sessionId)?.continuous == true) { sleepAndWake(sessionId, 60, "pausa após $maxSteps passos"); steps = 0 }
                    else { store.addItemUnique(sessionId, ChatItem.Error(text = "Limite de $maxSteps passos atingido. Envie \"continue\" para seguir.")); store.editSession(sessionId) { it.copy(status = SessionStatus.WAITING_USER) }; return }
                }
                continue
            }
            intentNoActionTurns = 0
            // OpenBot 0.73: turno sem texto e sem ferramenta não é sucesso. Pede a resposta uma vez; se vier vazio
            // de novo, encerra como falha (tarefas agendadas ficam marcadas como "falhou", não "concluída").
            if (cNow.isBlank() && store.session(sessionId)?.continuous != true) {
                if (++emptyReplyTurns >= 2) {
                    runCatching { dev.fluwy.app.diag.ErrorLog.log("agent", "Modelo terminou o turno sem texto e sem ferramentas.", sessionId, "llm") }
                    store.addItemUnique(sessionId, ChatItem.Error(text = "O modelo terminou sem responder nada. Envie \"continue\" para tentar de novo ou troque de modelo."))
                    store.editSession(sessionId) { it.copy(status = SessionStatus.ERROR) }
                    return
                }
                store.editSession(sessionId) { it.copy(llm = it.llm + LlmMessage("user",
                    dev.fluwy.app.prompts.Prompts.render("loop.15", mapOf<String, Any?>("lb" to "{{")))) }
                continue
            }
            if (store.session(sessionId)?.continuous == true) {
                // modo contínuo: sem ferramenta = lembra de dormir até a próxima verificação
                store.editSession(sessionId) { it.copy(llm = it.llm + LlmMessage("user", dev.fluwy.app.prompts.Prompts.render("loop.14", mapOf<String, Any?>("lb" to "{{")))) }
                if (++steps >= maxSteps) { sleepAndWake(sessionId, 5 * 60, "verificação periódica"); steps = 0 }
                continue
            }
            // Várias mensagens do usuário nesta rodada: antes de encerrar, confere uma vez se TODAS foram atendidas.
            if (!multiRequestChecked && tokenBudgetOverride == null) {
                val reqs = store.session(sessionId)?.items?.let { MultiRequest.pending(it, itemsAtRunStart) }.orEmpty()
                if (reqs.size >= 2) {
                    multiRequestChecked = true
                    store.editSession(sessionId) { it.copy(llm = it.llm + LlmMessage("user", MultiRequest.checkPrompt(reqs))) }
                    continue
                }
            }
            store.editSession(sessionId) { it.copy(status = SessionStatus.DONE) }
            if (tokenBudgetOverride == null) store.session(sessionId)?.let { done -> scope.launch { ctxMgr.extractMemories(done) } }
            // Modo voz da conversa: a resposta final também vem falada.
            if (store.session(sessionId)?.voiceReplies == true) speakLastAnswer(sessionId)
            return
        }
        intentNoActionTurns = 0
        val round = RoundState()
        runRoundTools(sessionId, reply, exposedToolNames, modelId, tokenBudgetOverride, round, LoopResources(
            connectorBuilderResults, invalidAccessibilitySnapshots, visualInspectionUsed, accessibilityReadScreenCompleted,
            transientAccessibilityOutputs, fileToolFailureGuard, repeatGuard,
        ))
        var stopLoop = round.stopLoop
        var sleepReq = round.sleepReq
        val tradeIdsAfterSleep = round.tradeIdsAfterSleep
        val resultHashes = round.resultHashes
        val repeatedFileFailure = round.repeatedFileFailure
        val repeatedToolFailure = round.repeatedToolFailure
        val awaitingApproval = round.awaitingApproval
        if (repeatedToolFailure != null && repeatedFileFailure == null) {
            runCatching { dev.fluwy.app.diag.ErrorLog.log("agent", "A mesma chamada de $repeatedToolFailure falhou 5 vezes igual; turno encerrado.", sessionId, repeatedToolFailure) }
            store.addItemUnique(sessionId, ChatItem.Error(text = "Parei: a ferramenta $repeatedToolFailure falhou 5 vezes do mesmo jeito. Ajuste o pedido ou troque de modelo e envie continuar."))
            store.editSession(sessionId) { it.copy(status = SessionStatus.WAITING_USER) }
            return
        }
        if (repeatedFileFailure != null) {
            runCatching { dev.fluwy.app.diag.ErrorLog.log("agent", "Falhas determinísticas de arquivo repetidas; loop interrompido.", sessionId, "file") }
            store.addItemUnique(sessionId, ChatItem.Error(text = "Interrompi novas tentativas de arquivo após falhas repetidas sem progresso. Liste um diretório válido ou forneça o arquivo ausente e envie continuar."))
            store.editSession(sessionId) { it.copy(status = SessionStatus.WAITING_USER) }
            return
        }
        // Loop de ferramentas: mesma ação repetida SEM progresso → avisa o modelo e, só se insistir muito, interrompe.
        // Ferramentas de ambiente (browser, aparelho, shell, conectores) mudam de estado entre chamadas — rolar,
        // executar script, consultar posições: repetir é legítimo. Elas têm limite bem maior e nunca contam
        // "mesmo resultado com argumentos diferentes". Leituras puras (arquivo, memória, spec) seguem o limite rígido.
        prevRoundResults = lastRoundResults
        lastRoundResults = reply.toolCalls.joinToString(";") { it.name + "#" + (resultHashes[it.id] ?: 0) }
        run {
            var tripped: String? = null
            var trippedKind = ToolLoopDetector.Kind.SAME_CALL
            var trippedRepeatable = true
            var trippedStrict = false
            val seqBefore = toolLoops.sequence
            // Ferramenta inexistente/não liberada chamada de novo e de novo: não há o que insistir.
            reply.toolCalls.filter { !ToolCallExecutionPolicy.isExposed(it.name, exposedToolNames) }
                .firstNotNullOfOrNull { toolLoops.unknownTool(it.name) }?.let { t ->
                    if (unknownToolWarned.add(sessionId + "|" + t.tool)) {
                        toolLoops.clearUnknown(t.tool)
                        val coord = coordinatedSection(sessionId) != null
                        store.editSession(sessionId) { it.copy(llm = it.llm + LlmMessage("user", dev.fluwy.app.prompts.Prompts.render("loop.13", mapOf<String, Any?>("tool" to (t.tool), "lb" to "{{")) +
                            (if (coord) dev.fluwy.app.prompts.Prompts.render("loop.11", mapOf<String, Any?>("lb" to "{{"))
                             else dev.fluwy.app.prompts.Prompts.render("loop.12", mapOf<String, Any?>("lb" to "{{"))))) }
                        return@let
                    }
                    runCatching { dev.fluwy.app.diag.ErrorLog.log("agent", "Ferramenta indisponível repetida: ${t.tool} (interrompido).", sessionId, t.tool) }
                    store.addItemUnique(sessionId, ChatItem.Error(text = "O agente insistiu na ferramenta \"${t.tool}\", que não está disponível aqui. Interrompi para não gastar tokens."))
                    store.editSession(sessionId) { it.copy(status = SessionStatus.WAITING_USER) }
                    return
                }
            roundNo++
            val epochNow = ctxMgr.clearEpochOf(sessionId) + (store.session(sessionId)?.compactions ?: 0)
            if (epochNow != lastEpoch) { lastEpoch = epochNow; epochChangedAt = roundNo }
            for (call in reply.toolCalls) {
                if (call.name in controlTools) continue
                val repeatable = isRepeatableTool(call.name)
                val trip = toolLoops.record(
                    name = call.name,
                    normArgs = normArgs(call.argsJson),
                    resultHash = resultHashes[call.id] ?: 0,
                    longResult = call.id in resultLong,
                    callThreshold = repeatToolThreshold(call.name),
                    contentThreshold = if (call.name in CONTENT_REPEAT_TOOLS) 3 else null,
                ) ?: continue
                // Depois de compactar, o conteúdo dos arquivos saiu do contexto: relê-los é necessário, não loop.
                if (trip.kind == ToolLoopDetector.Kind.POST_COMPACTION && call.name in POST_COMPACTION_READS) continue
                // Resultados antigos foram limpos do contexto há pouco: reler o que sumiu é necessário, não loop.
                if (call.name in POST_COMPACTION_READS && roundNo - epochChangedAt <= 10) continue
                tripped = call.name
                trippedKind = trip.kind
                trippedRepeatable = trippedRepeatable && repeatable && trip.kind == ToolLoopDetector.Kind.SAME_CALL
                trippedStrict = trippedStrict || call.name in STRICT_REPEAT_TOOLS
            }
            // Trouxe informação nova nesta rodada: os avisos anteriores deixam de valer.
            if (tripped == null && toolLoops.progressedAt(seqBefore)) { toolLoopNudges = 0; cleanRounds = 0 }
            if (tripped == null) {
                if (++cleanRounds >= 5) { toolLoopNudges = 0; cleanRounds = 0 } // voltou a progredir: zera os avisos
            } else {
                cleanRounds = 0
                val limit = when {
                    trippedKind == ToolLoopDetector.Kind.POST_COMPACTION -> 2  // compactar não quebrou o loop: avisa antes de parar
                    trippedKind == ToolLoopDetector.Kind.NO_PROGRESS || trippedKind == ToolLoopDetector.Kind.PING_PONG -> 2
                    trippedStrict -> 1
                    trippedRepeatable -> 4
                    else -> 2
                }
                if (++toolLoopNudges >= limit) {
                    runCatching { dev.fluwy.app.diag.ErrorLog.log("agent", "Loop de ferramenta sem progresso: $tripped (interrompido).", sessionId, tripped) }
                    val why = when (trippedKind) {
                        ToolLoopDetector.Kind.SAME_CONTENT -> "recebeu o mesmo conteúdo de \"$tripped\" várias vezes, sem nada novo entre as chamadas"
                        ToolLoopDetector.Kind.PING_PONG -> "ficou alternando entre as mesmas duas ações (ping-pong) sem nada novo"
                        ToolLoopDetector.Kind.NO_PROGRESS -> "fez ${ToolLoopDetector.NO_PROGRESS_CALLS}+ chamadas seguidas sem obter nenhuma informação nova"
                        ToolLoopDetector.Kind.POST_COMPACTION -> "voltou ao mesmo loop logo depois da compactação do contexto ($tripped)"
                        else -> "repetiu a mesma ação ($tripped), com os mesmos argumentos e o mesmo resultado, sem progresso entre as chamadas"
                    }
                    store.addItemUnique(sessionId, ChatItem.Error(text = "O agente $why. Interrompi para evitar um loop. Ajuste o pedido/abordagem ou troque de modelo."))
                    store.editSession(sessionId) { it.copy(status = SessionStatus.WAITING_USER) }
                    return
                }
                store.editSession(sessionId) {
                    val head = when (trippedKind) {
                        ToolLoopDetector.Kind.PING_PONG -> dev.fluwy.app.prompts.Prompts.render("loop.10", mapOf<String, Any?>("tripped" to (tripped), "lb" to "{{"))
                        ToolLoopDetector.Kind.NO_PROGRESS -> dev.fluwy.app.prompts.Prompts.render("loop.9", mapOf<String, Any?>("no_progress_calls" to (ToolLoopDetector.NO_PROGRESS_CALLS), "lb" to "{{"))
                        else -> dev.fluwy.app.prompts.Prompts.render("loop.8", mapOf<String, Any?>("tripped" to (tripped), "lb" to "{{"))
                    }
                    it.copy(llm = it.llm + LlmMessage("user", head +
                        dev.fluwy.app.prompts.Prompts.render("loop.6", mapOf<String, Any?>("lb" to "{{")) +
                        dev.fluwy.app.prompts.Prompts.render("loop.7", mapOf<String, Any?>("lb" to "{{"))))
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
        // Orientações do coordenador chegam aqui, num ponto seguro (depois dos resultados das ferramentas).
        coordinatorInbox.remove(sessionId)?.let { msgs ->
            store.editSession(sessionId) { it.copy(llm = it.llm + msgs.map { m -> LlmMessage("user", m) }) }
        }
        drainQueueInto(sessionId)  // mensagens do usuário enfileiradas enquanto o agente trabalhava
        // Tarefa delegada por coordenador de seção: entrega com prazo, em vez de trabalhar para sempre.
        if (sectionMember) {
            if (silentRounds >= MemberSupervision.SILENT_ROUNDS_BEFORE_NUDGE) {
                silentRounds = 0
                store.editSession(sessionId) { it.copy(llm = it.llm + LlmMessage("user",
                    dev.fluwy.app.prompts.Prompts.render("loop.5", mapOf<String, Any?>("lb" to "{{")))) }
            }
            memberCheckpoint(sessionId, steps)
            if (steps == MEMBER_SOFT_STEPS) store.editSession(sessionId) { it.copy(llm = it.llm + LlmMessage("user",
                dev.fluwy.app.prompts.Prompts.render("loop.4", mapOf<String, Any?>("member_soft_steps" to (MEMBER_SOFT_STEPS), "lb" to "{{")))) }
            if (steps >= MEMBER_HARD_STEPS) {
                store.addItemUnique(sessionId, ChatItem.Error(text = "Limite da tarefa delegada ($MEMBER_HARD_STEPS passos) atingido: entreguei ao coordenador o que foi feito até aqui."))
                store.editSession(sessionId) { it.copy(status = SessionStatus.IDLE) }
                return
            }
        }
        // Aviso de proximidade do limite: pede para priorizar a entrega antes do corte abrupto.
        if (steps == (maxSteps - 2).coerceAtLeast(1) && store.session(sessionId)?.continuous != true) {
            store.editSession(sessionId) { it.copy(llm = it.llm + LlmMessage("user", dev.fluwy.app.prompts.Prompts.render("loop.3", mapOf<String, Any?>("lb" to "{{")))) }
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
internal suspend fun AgentRunner.sleepAndWake(
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
    dev.fluwy.app.sandbox.SandboxService.start(ctx, if (preserveContinuous) "Agente em modo contínuo" else "Aguardando vencimento da opção")
    store.flush()
    val early = waitUntil(sessionId, wake, reason)
    wakeUp(sessionId)
    if (early) noteEarlyWake(sessionId)
    val ids = afterTradeIds.ifEmpty { tradeIdsFromWakeReason(store.session(sessionId)?.wakeReason.orEmpty()) }
    if (ids.isNotEmpty()) checkExpiredTradesOnce(sessionId, ids)
}

/** One event-driven local result read after the broker expiry; no repeated network polling. */
internal suspend fun AgentRunner.checkExpiredTradesOnce(sessionId: String, tradeIds: Set<Long>) {
    val session = store.session(sessionId) ?: return
    val agent = store.agent(session.agentId)
    for (id in tradeIds) {
        val result = runCatching {
            dev.fluwy.app.FluwyApp.instance.uiConnectors.callTool(
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
                dev.fluwy.app.prompts.Prompts.render("loop.2", mapOf<String, Any?>("valor" to (id), "safe" to (safe), "lb" to "{{"))))
        }
    }
}

internal fun AgentRunner.tradeIdsFromWakeReason(reason: String): Set<Long> =
    iqOptionTradeWakeTag.find(reason)?.groupValues?.getOrNull(1)?.split(',')
        ?.mapNotNull { it.toLongOrNull()?.takeIf { id -> id > 0L } }?.toSet().orEmpty()

internal fun AgentRunner.wakeUp(sessionId: String) {
    val r = iqOptionTradeWakeTag.replace(store.session(sessionId)?.wakeReason.orEmpty(), "").trim()
    val now = SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()).format(Date())
    store.editSession(sessionId) {
        it.copy(status = SessionStatus.RUNNING, wakeAt = null,
            llm = it.llm + LlmMessage("user", dev.fluwy.app.prompts.Prompts.render("loop.1", mapOf<String, Any?>("now" to (now), "valor" to (r), "lb" to "{{"))))
    }
}

/** Rede salva da carteira (fail-closed). */
internal fun AgentRunner.walletNetworkOrNull(): SolanaNetwork? = runCatching {
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
internal suspend fun AgentRunner.evaluateAllArmedRules() {
    val network = walletNetworkOrNull() ?: return
    if (network != SolanaNetwork.MAINNET) return // day trade opera só na Mainnet
    val dtStore = dev.fluwy.app.wallet.DayTradeStore(ctx.applicationContext)
    val agentIds = dtStore.armedAgentIds()
    if (agentIds.isEmpty()) return
    val wallet = SolanaWalletRepository(ctx.applicationContext)
    if (!wallet.hasWallet() || !wallet.backupConfirmed()) return
    val policies = WalletAgentPolicyStore(ctx.applicationContext)
    val engine = dev.fluwy.app.wallet.DayTradeEngine(ctx.applicationContext, wallet)
    for (agentId in agentIds) {
        if (!policies.canAgentDayTrade(agentId)) continue
        val outcomes = runCatchingCancellable {
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
 * Decide se um agente autônomo deve iniciar um ciclo AGORA. Centraliza as condições das duas vias
 * (ticker em processo e alarme) para que a lógica nunca divirja. Ponto-chave: quando um ciclo é
 * BLOQUEADO pelo orçamento diário de tokens, NÃO falha em silêncio — avisa no chat + log + push
 * uma vez por dia, para o usuário entender por que o agente "parou" (antes isso era invisível e
 * parecia que o agendamento tinha quebrado).
 */
internal fun AgentRunner.canBeat(a: AgentProfile, now: Long, hour: Int, requireDue: Boolean): Boolean {
    if (!a.autonomous) return false
    if (dev.fluwy.app.device.DeviceCaps.BACKGROUND !in a.deviceCaps) return false
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
            runCatching { dev.fluwy.app.diag.ErrorLog.log("heartbeat", msg, home, "set_schedule") }
            runCatching { store.addItem(home, ChatItem.Error(text = msg)); store.flush() }
            runCatching { notifyAgent(a, msg) }
        }
        return false
    }
    return true
}

/**
 * Caminho relativo que não existe na raiz do workspace, mas existe dentro de UMA pasta de projeto
 * (ex.: "src/components" quando o projeto está em "barbearia/"): usa essa pasta. Workers recebiam caminhos
 * do plano relativos ao projeto e erravam a raiz várias vezes seguidas.
 */
internal fun AgentRunner.projectRelative(root: java.io.File, path: String, f: java.io.File?): java.io.File? {
    if (f == null || f.exists()) return f
    val rel = path.trim().removePrefix("/root/workspace/").removePrefix("./").trim('/')
    if (rel.isBlank() || rel.startsWith("/") || rel.contains("..") || rel.startsWith(".fluwy")) return f
    val hits = root.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") && it.name != "node_modules" }
        ?.map { java.io.File(it, rel) }?.filter { it.exists() }.orEmpty()
    return hits.singleOrNull() ?: f
}
internal fun AgentRunner.relNote(root: java.io.File, asked: String, f: java.io.File?): String =
    if (f != null && f.exists() && !java.io.File(root, asked.trim().removePrefix("/root/workspace/").trim('/')).exists() && asked.isNotBlank())
        "(caminho \"$asked\" resolvido para \"${f.relativeTo(root).path}\" — o projeto fica nessa pasta; use esse caminho daqui em diante)\n" else ""

internal fun AgentRunner.today() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

/**
 * Leitura paginada de arquivo: devolve linhas numeradas [offset, offset+limit) com no máximo ~7000 caracteres
 * (abaixo do limite de armazenamento de saídas, então NUNCA gera um novo .fluwy/out — o que antes causava um
 * ciclo infinito: ler a saída truncada gerava outra saída truncada em outro arquivo) e diz onde continuar.
 */
internal fun AgentRunner.readFileChunk(f: java.io.File, shown: String, offset: Int, limit: Int): String {
    if (!f.exists() && ContextArchive.isArchivePath(shown)) return ContextArchive.MISSING
    if (!f.exists()) return "ERRO: arquivo não existe neste workspace: $shown. Liste o diretório pai com file_list uma vez; não repita este caminho. Se o arquivo não aparecer, peça ao usuário para enviá-lo ou informe que está ausente."
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
    pendingCoverage?.let { (key, stamp) -> if (key.endsWith("|" + f.absolutePath)) readCoverage.compute(key) { _, cur ->
        val base = if (cur == null || cur.first != stamp) (stamp to mutableListOf<IntRange>()) else cur
        base.second.add(start..last); base } }
    pendingCoverage = null
    return "$shown — linhas $start–$last de $total (${f.length() / 1024} KB)\n" + sb.toString() + tail
}

internal fun AgentRunner.tfLabel(tf: Int) = when { tf % 3600 == 0 -> "${tf / 3600}h"; tf % 60 == 0 -> "${tf / 60} min"; else -> "${tf}s" }

/** Ferramentas cujo resultado depende do estado do ambiente (repetir pode ser legítimo). */
internal fun AgentRunner.isRepeatableTool(name: String): Boolean {
    if (name in STRICT_REPEAT_TOOLS) return false
    if (name in PURE_REPEAT_TOOLS) return false
    return true // browser_*, device_*, shell, uic_*, mcp_*, wallet/market e demais ferramentas de ambiente
}

/** Normaliza argumentos JSON (chaves ordenadas, espaços ignorados) para comparar chamadas. */
internal fun AgentRunner.normArgs(raw: String): String {
    fun canon(e: kotlinx.serialization.json.JsonElement): String = when (e) {
        is kotlinx.serialization.json.JsonObject -> e.entries.sortedBy { it.key }.joinToString(",", "{", "}") { "\"" + it.key + "\":" + canon(it.value) }
        is kotlinx.serialization.json.JsonArray -> e.joinToString(",", "[", "]") { canon(it) }
        else -> e.toString()
    }
    return runCatching { canon(kotlinx.serialization.json.Json.parseToJsonElement(raw)) }.getOrElse { raw.trim() }
}

/**
 * Chama o modelo com recuperação de falhas: circuit breaker por provedor, rotação de chave, troca para o
 * modelo alternativo, compactação quando o contexto estoura e espera com backoff. Devolve null quando a
 * execução deve parar (a sessão já foi atualizada com o motivo).
 */
internal suspend fun AgentRunner.callModelWithRecovery(
    sessionId: String,
    agent: AgentProfile?,
    sys: LlmMessage,
    history: List<LlmMessage>,
    stepTools: kotlinx.serialization.json.JsonArray,
    replyTokenLimit: Int,
    tokenBudgetOverride: Long?,
): dev.fluwy.app.llm.LlmReply? {
    var currentReplyTokenLimit = replyTokenLimit

    var attempt = 0
    while (true) {
        // Circuit breaker por provedor+modelo: se está aberto (ou em cooldown de rate limit), espera em vez de chamar.
        val breaker = dev.fluwy.app.llm.LlmRecovery.breaker(store.settings.baseUrl, stepModel(agent))
        while (true) {
            val wait = breaker.acquire(System.currentTimeMillis())
            if (wait <= 0) break
            setActivity(sessionId, Activity(Activity.Kind.THINKING, "Provedor instável — aguardando ${(wait / 1000).coerceAtLeast(1)}s para tentar de novo"))
            kotlinx.coroutines.delay(wait.coerceAtMost(15_000))
        }
        try {
            val usedModel = stepModel(agent)
            val response = llm.chat(listOf(sys) + history, tools = stepTools, model = usedModel, maxTokens = currentReplyTokenLimit, conversationId = sessionId)
            breaker.onSuccess()
            dev.fluwy.app.llm.KeyPool.onSuccess(dev.fluwy.app.llm.ProviderRoute.route(store.settings, usedModel).first.apiKey)
            if (usedModel == primaryModel(agent) && dev.fluwy.app.llm.ModelFallback.onSuccess(usedModel))
                store.addItem(sessionId, ChatItem.Step(tool = "model_switch", title = "Modelo principal voltou a responder", args = usedModel, result = "", status = StepStatus.OK))
            if (response.truncated && (response.toolCalls.isNotEmpty() || response.content.isNullOrBlank())) throw dev.fluwy.app.llm.LlmRequestFailure(
                "A resposta foi cortada sem texto recuperável ou com chamadas de ferramenta incompletas; nenhuma ferramenta foi executada.",
                retryable = true,
                responseTruncated = true,
            )
            return response
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            attempt++
            val llmFailure = t as? dev.fluwy.app.llm.LlmRequestFailure
            val retryable = llmFailure?.retryable == true
            val category = dev.fluwy.app.llm.LlmRecovery.classify(t.message, retryable, llmFailure?.responseTruncated == true)
            dev.fluwy.app.llm.PlanUsage.orgIdFrom(t.message)?.let { org -> if (store.settings.opencodeOrgId.isBlank()) store.updateSettings { it.copy(opencodeOrgId = org) } }
            if (llmFailure?.responseTruncated != true) breaker.onFailure(System.currentTimeMillis(), llmFailure?.retryAfterMillis)
            // Contexto estourado: compacta e continua (uma vez a cada 10 min, para nunca virar ciclo).
            if (category == dev.fluwy.app.llm.LlmRecovery.Category.CONTEXT && tokenBudgetOverride == null &&
                System.currentTimeMillis() - (contextOverflowAt[sessionId] ?: 0) > 600_000) {
                contextOverflowAt[sessionId] = System.currentTimeMillis()
                store.session(sessionId)?.let { cur -> runCatching { ctxMgr.compactIfNeeded(cur, force = true) }.getOrElse { ctxMgr.safeTrim(cur) } }
                scope.launch { send(sessionId, continuationDirective(sessionId, automatic = true), showUserMessage = false) }
                return null
            }
            // Limite de requisições no modelo principal: passa na hora para o modelo alternativo (se configurado).
            val primaryNow = primaryModel(agent)
            val failingModel = stepModel(agent)
            val failingKeys = dev.fluwy.app.llm.ProviderRoute.route(store.settings, failingModel).first.apiKey
            if (category == dev.fluwy.app.llm.LlmRecovery.Category.RATE_LIMIT && dev.fluwy.app.llm.KeyPool.rotate(failingKeys)) {
                store.addItem(sessionId, ChatItem.Step(tool = "model_switch", title = "Limite nesta conta: usando a próxima chave do provedor",
                    args = dev.fluwy.app.llm.ProviderRoute.label(failingModel), result = "Mesmo modelo, outra conta.", status = StepStatus.OK))
                attempt = 0
                continue
            }
            if (category == dev.fluwy.app.llm.LlmRecovery.Category.RATE_LIMIT &&
                dev.fluwy.app.llm.ModelFallback.onRateLimit(failingModel, fallbackChain(), primary = primaryNow)) {
                val next = stepModel(agent)
                val back = dev.fluwy.app.llm.ModelFallback.activeUntil(primaryNow)?.let { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it)) }.orEmpty()
                store.addItem(sessionId, ChatItem.Step(tool = "model_switch",
                    title = if (failingModel == primaryNow) "Limite no modelo principal: usando o alternativo" else "Limite no alternativo: usando o próximo da cadeia",
                    args = dev.fluwy.app.llm.ProviderRoute.label(next), result = "Testo o principal ($primaryNow) de novo às $back.", status = StepStatus.OK))
                attempt = 0
                continue
            }
            // Resposta cortada 2× mesmo com mais espaço: quase sempre é o modelo tentando escrever código enorme
            // dentro de UMA chamada (ex.: webapi_test com 8 KB de código). Em vez de suspender 20 min, orienta a
            // dividir o trabalho e segue na hora (até 3 vezes por tarefa).
            if (llmFailure?.responseTruncated == true && attempt >= 2 && (truncNudges.merge(sessionId, 1, Int::plus) ?: 1) <= 3) {
                setActivity(sessionId, Activity(Activity.Kind.THINKING, "Resposta longa demais — dividindo em partes menores"))
                scope.launch { send(sessionId, TRUNCATION_NUDGE, showUserMessage = false, directiveSuffix = " ") }
                return null
            }
            // Erro do provedor/modelo que não passou em até 3 tentativas: vai para o próximo provedor da cadeia.
            // (Sem internet e contexto estourado não se resolvem trocando de provedor.)
            if (llmFailure != null && category != dev.fluwy.app.llm.LlmRecovery.Category.NETWORK &&
                category != dev.fluwy.app.llm.LlmRecovery.Category.CONTEXT &&
                (attempt >= 3 || !dev.fluwy.app.llm.LlmRecovery.shouldRetry(category, attempt, retryable)) &&
                dev.fluwy.app.llm.ModelFallback.onRateLimit(failingModel, fallbackChain(), primary = primaryNow)) {
                val next = stepModel(agent)
                store.addItem(sessionId, ChatItem.Step(tool = "model_switch", title = "Falha no provedor (${category.label}): usando o próximo da cadeia",
                    args = dev.fluwy.app.llm.ProviderRoute.label(next), result = "Depois de $attempt tentativa(s) em ${dev.fluwy.app.llm.ProviderRoute.label(failingModel)}. O principal é testado de novo em alguns minutos.", status = StepStatus.OK))
                attempt = 0
                continue
            }
            if (llmFailure == null || !dev.fluwy.app.llm.LlmRecovery.shouldRetry(category, attempt, retryable)) {
                runCatching { dev.fluwy.app.diag.ErrorLog.log("agent", "LLM falhou após $attempt tentativa(s) [${category.name}]: ${t.javaClass.simpleName}: ${t.message.orEmpty()}", sessionId, "llm") }
                // Erro recuperável → suspende e retoma sozinho em 20 min; permanente → espera o usuário.
                val resumeAt = if (tokenBudgetOverride == null) scheduleAutoResume(sessionId, category) else null
                store.session(sessionId)?.items?.filterIsInstance<ChatItem.LlmRecovery>()?.filter { it.state == "waiting" }
                    ?.forEach { store.replaceItem(sessionId, it.copy(state = "resolved")) }
                store.addItem(sessionId, ChatItem.LlmRecovery(
                    provider = dev.fluwy.app.llm.LlmRecovery.providerLabel(store.settings.baseUrl), model = stepModel(agent),
                    category = category.label, detail = SecretVault.redact(sessionId, t.message.orEmpty()).take(220), attempts = attempt,
                    advice = category.advice, permanent = category.permanent, resumeAt = resumeAt, round = autoResumeRound[sessionId] ?: 0))
                pendingRecovery.add(sessionId)
                if (resumeAt == null) store.editSession(sessionId) { it.copy(status = SessionStatus.WAITING_USER) }
                return null
            }
            if (llmFailure.responseTruncated) {
                currentReplyTokenLimit = (currentReplyTokenLimit.toLong() * 2L).coerceAtMost(32_000L).toInt()
            }
            val delayMs = dev.fluwy.app.llm.LlmRecovery.withJitter(
                dev.fluwy.app.llm.LlmRetryPolicy.delayMillis(attempt, llmFailure.retryAfterMillis), Math.random())
            val waitLabel = (delayMs / 1_000L).coerceAtLeast(1L)
            setActivity(sessionId, Activity(Activity.Kind.THINKING, "${category.label} — aguardando ${waitLabel}s antes de tentar de novo ($attempt/${category.maxAttempts})"))
            kotlinx.coroutines.delay(delayMs)
        }
    }
    @Suppress("UNREACHABLE_CODE") return null
}

/** Estado de uma rodada de ferramentas (uma resposta do modelo). */
internal class RoundState {
    var stopLoop: SessionStatus? = null
    /** Pedido de pausa: (segundos, motivo). */
    var sleepReq: Pair<Int, String>? = null
    val tradeIdsAfterSleep = linkedSetOf<Long>()
    val tradeCallCache = HashMap<String, ToolResult>()
    val resultHashes = HashMap<String, Int>()
    val resultLong = HashSet<String>()
    var repeatedFileFailure: String? = null
    var repeatedToolFailure: String? = null
    val staleSnapshotRecoveryCache = HashMap<String?, String>()
    /** Depois que uma ação pediu confirmação, nada mais desta resposta é executado. */
    var awaitingApproval = false
}

/** Guardas e caches que vivem durante toda a execução do loop. */
internal class LoopResources(
    val connectorBuilderResults: ExactToolCallMemo<ToolResult>,
    val invalidAccessibilitySnapshots: dev.fluwy.app.device.InvalidAccessibilitySnapshotGuard,
    val visualInspectionUsed: MutableSet<String>,
    val accessibilityReadScreenCompleted: MutableSet<String>,
    val transientAccessibilityOutputs: LinkedHashMap<String, String>,
    val fileToolFailureGuard: AgentFileToolFailureGuard,
    val repeatGuard: RepeatedToolFailureGuard,
)

/** Executa as chamadas de ferramenta de uma resposta, em ordem, aplicando as guardas de segurança e repetição. */
internal suspend fun AgentRunner.runRoundTools(
    sessionId: String,
    reply: dev.fluwy.app.llm.LlmReply,
    exposedToolNames: Set<String>,
    modelId: String,
    tokenBudgetOverride: Long?,
    round: RoundState,
    res: LoopResources,
) = with(round) { with(res) {
    for (call in reply.toolCalls) {
        val callWasExposed = ToolCallExecutionPolicy.isExposed(call.name, exposedToolNames)
        val accessibilitySnapshotId = if (callWasExposed && call.name in accessibilitySnapshotBoundTools) runCatching {
            json.parseToJsonElement(call.argsJson).jsonObject["snapshot_id"]?.jsonPrimitive?.contentOrNull
        }.getOrNull() else null
        val blockedSnapshotReuse = callWasExposed && call.name in accessibilitySnapshotBoundTools &&
            invalidAccessibilitySnapshots.shouldBlock(accessibilitySnapshotId)
        val blockedSnapshotRead = if (blockedSnapshotReuse) {
            staleSnapshotRecoveryCache[accessibilitySnapshotId] ?: try {
                dev.fluwy.app.device.DeviceAccessibilityActions.readScreen(sessionId)
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
        val cacheConnectorResult = memoizeConnectorBuilderCall(call.name)
        val cachedConnectorResult = if (cacheConnectorResult) connectorBuilderResults[cacheKey] else null
        val gate = if (!callWasExposed || blockedSnapshotReuse || cachedTradeResult != null || cachedConnectorResult != null) null
            else if (awaitingApproval) ToolResult("NÃO EXECUTADO: uma ação anterior desta resposta aguarda a confirmação do usuário. Espere a resposta antes de continuar.")
            else repeatGuard(sessionId, call) ?: runCatching { actionGate(sessionId, call) }.getOrNull()
        if (gate?.stop == SessionStatus.WAITING_USER) awaitingApproval = true
        val rawResult = if (!callWasExposed) {
            ToolResult("RECUSADO: a ferramenta '${call.name}' não foi disponibilizada neste passo. Nenhuma ação foi executada; continue somente com ferramentas expostas ou carregue a capacidade autorizada.")
        } else if (gate != null) {
            gate
        } else if (toolArgsGuard(call) != null) {
            ToolResult(toolArgsGuard(call)!!)
        } else if (squadModeGuard(sessionId, call) != null) {
            ToolResult(squadModeGuard(sessionId, call)!!)
        } else if (blockedSnapshotReuse) {
            ToolResult("ERRO: Snapshot ausente ou invalidado; nenhuma ação foi executada com o ID já recusado. Use somente IDs da releitura atual:\n${blockedSnapshotRead.orEmpty()}")
        } else try {
            cachedTradeResult ?: cachedConnectorResult ?: ledgerRun(sessionId, call) { runTool(
                sessionId, call, allowDeviceTools = tokenBudgetOverride == null,
                model = modelId, visualInspectionUsed = visualInspectionUsed,
                accessibilityReadScreenCompleted = accessibilityReadScreenCompleted,
            ) }
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            val safeFailure = if (call.name.startsWith("device_accessibility_"))
                "Falha de ferramenta Android; conteúdo de tela omitido por privacidade."
            else "${t.javaClass.simpleName}: ${t.message.orEmpty()}"
            runCatching { dev.fluwy.app.diag.ErrorLog.log(errorSource(call.name), safeFailure, sessionId, call.name) }
            val detail = t.message?.take(300) ?: t.javaClass.simpleName
            val staleFailure = isStaleAccessibilitySnapshotFailure(call.name, detail)
            val refreshedScreen = if (staleFailure) try {
                dev.fluwy.app.device.DeviceAccessibilityActions.readScreen(sessionId)
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
        if (cacheConnectorResult && cachedConnectorResult == null) connectorBuilderResults[cacheKey] = rawResult
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
        val redactedOut = SecretVault.redact(sessionId, result.output)
        // Conectores devolvem JSON bruto (ex.: e-mails com cabeçalhos SMTP e HTML): compacta para alto sinal e
        // arquiva o original — o agente não precisa reler pedaços truncados.
        val shapedOut = if ((call.name.startsWith("mcp_") || call.name.startsWith("uic_")) && redactedOut.length > 1_500) {
            val sh = ToolOutputShaper.shape(redactedOut)
            if (!sh.changed) redactedOut else {
                val ptr = ctxMgr.archive(sessionId, redactedOut)
                sh.text + "\n[Resultado compactado: " +
                    (if (sh.omittedKeys.isNotEmpty()) "campos omitidos " + sh.omittedKeys.take(8).joinToString() + "; " else "") +
                    "textos longos cortados. " + (if (ptr != null) "JSON completo (${redactedOut.length} chars) em $ptr — use file_search nesse arquivo só se precisar de um campo omitido; não chame a ferramenta de novo." else "") + "]"
            }
        } else redactedOut
        val ctxOut = ctxMgr.storeToolOutput(
            sessionId, call.id, shapedOut,
            limit = if (call.name == "connector_spec") 60_000 else 8_000,
            persistLongOutput = !accessibilityOutput, toolName = call.name)
        if (accessibilityOutput) {
            transientAccessibilityOutputs.remove(call.id)
            transientAccessibilityOutputs[call.id] = ctxOut
            while (transientAccessibilityOutputs.size > 6) {
                val oldest = transientAccessibilityOutputs.keys.firstOrNull() ?: break
                transientAccessibilityOutputs.remove(oldest)
            }
        }
        // OpenBot: conteúdo externo vai como dado não confiável + falhas ganham motivo e próximo passo.
        val durableToolOutput = ToolOutcomeAdvisor.decorate(
            call.name, AccessibilityToolHistory.durableResult(call.name, ctxOut), result.output)
            .let { o -> dev.fluwy.app.browser.BrowserDownloads.drain(sessionId)?.let { "$o\n\n$it" } ?: o }
            .let { o ->
                // OpenBot 0.71: a mesma chamada falhando igual várias vezes = troque de abordagem; na 5ª, para o turno.
                when (repeatGuard.record(call.name, normArgs(call.argsJson), rawResult.output)) {
                    RepeatedToolFailureGuard.Verdict.WARN -> "$o\n\n${RepeatedToolFailureGuard.WARN_NOTE}"
                    RepeatedToolFailureGuard.Verdict.STOP -> { repeatedToolFailure = call.name; o }
                    else -> o
                }
            }
        // Como o Manus: página pede login → o app pede ao usuário (o agente nunca digita senha), e a tarefa continua depois.
        if (call.name.startsWith("browser_") && ToolOutcomeAdvisor.classify(call.name, result.output) == ToolOutcomeAdvisor.Reason.LOGIN_WALL)
            runCatching { requestUserLogin(sessionId) }
        store.editSession(sessionId) {
            it.copy(llm = it.llm + LlmMessage("tool", durableToolOutput, toolCallId = call.id, name = call.name))
        }
        if (call.name == "file_read" || call.name == "file_list") {
            repeatedFileFailure = fileToolFailureGuard.record(call.name, normArgs(call.argsJson), rawResult.output)
        }
        if (result.stop != null) stopLoop = result.stop
        if (result.sleepSeconds != null) {
            val current = sleepReq
            if (current == null || result.sleepSeconds > current.first) sleepReq = result.sleepSeconds to result.output
        }
    }
} }
