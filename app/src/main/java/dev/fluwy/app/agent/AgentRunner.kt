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

internal val iqOptionTradeWakeTag = Regex("""\[\[IQOPTION_TRADES:([0-9,]+)]]$""")
/**
 * Loop do agente estilo Manus/Fluwy: LLM com function calling + ferramentas que agem no sandbox
 * Linux local (shell/arquivos) e no WebView (browser). Roda até o modelo responder sem tool calls,
 * chamar `finish`, pedir input (`ask_user`) ou estourar o limite de passos.
 */
class AgentRunner(
    internal val store: Store,
    internal val sandbox: Sandbox,
    internal val browserFor: (String) -> BrowserHost,
    internal val llm: OpenRouter,
    internal val terminals: dev.fluwy.app.sandbox.TerminalManager,
    internal val ctx: android.content.Context,
) {
    init { ExecutionLedger.dirResolver = { id -> sandbox.workspaceFor(id) } }
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Registro explícito do ciclo de vida de cada chamada de ferramenta. */
    val executions = ToolExecutionManager()
    internal val jobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    internal val heartbeatDispatchGate = HeartbeatDispatchGate()
    internal val childJobsByParent = mutableMapOf<String, MutableList<Job>>()
    internal val _activity = MutableStateFlow<Map<String, Activity>>(emptyMap())
    val activity: StateFlow<Map<String, Activity>> = _activity
    /** Saída bruta dos comandos do agente por sessão (aba "Terminal do agente"). */
    internal val _termLog = MutableStateFlow<Map<String, String>>(emptyMap())
    val termLog: StateFlow<Map<String, String>> = _termLog
    internal val json = Json { ignoreUnknownKeys = true }

    fun isRunning(sessionId: String) = jobs[sessionId]?.isActive == true

    /**
     * Interrompe a execução atual. A autonomia do Agent só é pausada quando [pauseAutonomy] é true
     * (/stop explícito ou parar um chat ocioso). Antes, qualquer parada — tocar ■ no meio de um
     * passo, o botão da notificação do terminal — desligava a autonomia sem o usuário perceber.
     */
    /** Agentes de seção pausados à mão pelo usuário no meio de uma tarefa delegada (a tarefa continua aberta). */
    internal val pausedDelegations = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    /** Ferramentas indisponíveis já explicadas ao modelo nesta execução (avisa uma vez antes de interromper). */
    internal val unknownToolWarned = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** "Sair do app": interrompe toda execução em andamento (a autonomia fica como estava e volta ao reabrir). */
    fun stopEverything() {
        jobs.keys.toList().forEach { runCatching { stop(it) } }
        autoResumeJobs.values.forEach { it.cancel() }; autoResumeJobs.clear()
    }

    fun stop(sessionId: String, pauseAutonomy: Boolean = false) {
        // Parar um agente que está numa tarefa do coordenador é uma PAUSA: a tarefa não conta como concluída.
        // (Pausa da seção, team_stop e reatribuição removem a delegação antes de chamar stop.)
        if (delegations[sessionId] != null) pausedDelegations.add(sessionId)
        autoResumeJobs.remove(sessionId)?.cancel(); autoResumeRound.remove(sessionId)
        executions.cancelSession(sessionId)
        // Primeiro envia Ctrl+C ao PTY ativo; cancelar só a coroutine deixava o processo vivo.
        terminals.get(sessionId).cancelAgentCommand()
        scope.launch { runCatching { browserFor(sessionId).cancelNavigation() } }
        jobs.remove(sessionId)?.cancel()
        synchronized(childJobsByParent) { childJobsByParent.remove(sessionId)?.forEach { it.cancel() } }
        if (pauseAutonomy) store.state.value.agents.filter { it.homeSessionId == sessionId && it.autonomous }.forEach {
            store.upsertAgent(it.copy(autonomous = false)); dev.fluwy.app.scheduler.Heartbeat.cancel(ctx, it.id)
        }
        finalizeRunningSteps(sessionId, "Execução interrompida pelo usuário.")
        store.editSession(sessionId) { it.copy(status = SessionStatus.STOPPED, wakeAt = null) }
        setActivity(sessionId, Activity())
        browserFor(sessionId).setAgentControl(false)
    }

    // ───────── Fila de mensagens do usuário (FIFO), com pausar e cancelar ─────────
    data class QueuedMessage(val id: String, val text: String, val quote: String? = null, val attachments: List<String> = emptyList())
    /** Mensagens aguardando entrega, por chat (a tela do chat mostra e permite cancelar). */
    val messageQueue = MutableStateFlow<Map<String, List<QueuedMessage>>>(emptyMap())
    /** Chats com a fila pausada: nada é entregue até retomar. */
    val pausedQueues = MutableStateFlow<Set<String>>(emptySet())
    internal val queueLock = Any()

    /**
     * Agente no meio de um trabalho: a mensagem não cancela nada — entra na fila e chega a ele no próximo passo seguro.
     * Agente parado ou em espera: envia na hora, como antes.
     */
    fun sendOrQueue(sessionId: String, text: String, quotedReply: String? = null, attachments: List<String> = emptyList()): Job {
        runCatching { dev.fluwy.app.notify.NotificationCenter.markRead(sessionId) } // respondeu: some da barra
        // Ordem de parar: interrompe tudo na hora (não entra na fila) e, se pedida, faz só a entrega final.
        StopIntent.parse(text).takeIf { it.stop }?.let { return stopAndDeliver(sessionId, text, it.deliver, quotedReply) }
        val session = store.session(sessionId)
        val busy = isRunning(sessionId) && session?.status == SessionStatus.RUNNING
        val hasQueue = messageQueue.value[sessionId].orEmpty().isNotEmpty()
        if ((!busy && !hasQueue) || text.trim().startsWith("/")) return send(sessionId, text, attachments, quotedReply = quotedReply)
        val quote = quotedReply?.trim()?.take(800)?.takeIf { it.isNotBlank() }
        synchronized(queueLock) {
            val cur = messageQueue.value
            messageQueue.value = cur + (sessionId to (cur[sessionId].orEmpty() + QueuedMessage(newId(), text, quote, attachments)).takeLast(20))
        }
        val running = jobs[sessionId]
        // Se o trabalho terminar antes de a fila ser lida, o que sobrou é enviado em seguida (nada se perde).
        return scope.launch { running?.join(); kotlinx.coroutines.delay(300); flushQueueIfIdle(sessionId) }
    }

    /** Chats numa entrega final após "pare": ferramentas restritas, sem delegar/navegar/agendar e sem retomar a autonomia. */
    internal val finalDelivery = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Ferramentas permitidas na entrega final (ler o que já existe, escrever o arquivo pedido, enviar e encerrar). */
    internal val FINAL_DELIVERY_TOOLS = setOf("file_read", "file_list", "file_write", "file_edit", "notes_read", "notes_write", "memory_search",
        "team_status", "send_file", "finish", "complete_step")

    /**
     * "Pare (e me entregue X)": para coordenador, agentes, ciclo agendado, agendas desta conversa e fila; depois, se o
     * usuário pediu algo, roda UMA execução curta só para entregar e termina. Nada volta a rodar sozinho.
     */
    fun stopAndDeliver(sessionId: String, text: String, deliver: Boolean, quotedReply: String? = null): Job {
        val sec = store.state.value.sections.firstOrNull { it.sessionId == sessionId } ?: store.section(store.session(sessionId)?.sectionId)
        synchronized(queueLock) { messageQueue.value = messageQueue.value - sessionId }
        if (sec != null) pauseSection(sec.id) else stop(sessionId, pauseAutonomy = true)
        // Agendas criadas nesta conversa também param (o usuário pediu para parar o processo).
        val chatIds = setOfNotNull(sessionId, sec?.sessionId) + store.state.value.sessions.filter { sec != null && it.sectionId == sec.id }.map { it.id }
        val paused = store.state.value.scheduled.filter { it.enabled && it.sourceSessionId in chatIds }
        paused.forEach { t -> store.upsertScheduled(t.copy(enabled = false)); runCatching { dev.fluwy.app.scheduler.Scheduler.cancel(ctx, t.id) } }
        val target = sec?.sessionId ?: sessionId
        // Modo contínuo desligado: sem dormir-e-acordar nem retomada ao reabrir o app.
        chatIds.forEach { id -> store.editSession(id) { it.copy(continuous = false, wakeAt = null) } }
        val stoppedNote = "Parado a seu pedido: " + (if (sec != null) "coordenador, agentes e próximo ciclo" else "execução e autonomia") +
            (if (paused.isNotEmpty()) " e ${paused.size} agenda(s) desta conversa" else "") + " foram interrompidos."
        if (!deliver) {
            store.addItem(target, ChatItem.User(text = text, replyTo = quotedReply))
            store.addItem(target, ChatItem.Assistant(text = "$stoppedNote Nada volta a rodar até você pedir.", final = true))
            return scope.launch { }
        }
        finalDelivery.add(target)
        store.addItem(target, ChatItem.Step(tool = "stop", title = "Parado a seu pedido — entregando o que você pediu", args = "", result = stoppedNote, status = StepStatus.OK))
        val directive = "\n\n[ORDEM DO USUÁRIO: PARAR E ENTREGAR. $stoppedNote Agora faça SOMENTE a entrega pedida nesta mensagem, usando o que já foi feito " +
            "(leia arquivos/notas do workspace se precisar; crie o arquivo pedido com file_write). Não delegue, não navegue, não pesquise de novo, não agende " +
            "e não retome o trabalho anterior. Ao terminar a entrega, chame finish com o resultado (e o caminho do arquivo, se criou um).]"
        return send(target, text, quotedReply = quotedReply, directiveSuffix = directive)
    }

    internal fun takeQueue(sessionId: String): List<QueuedMessage> = synchronized(queueLock) {
        if (sessionId in pausedQueues.value) return emptyList()
        val cur = messageQueue.value
        val list = cur[sessionId].orEmpty()
        if (list.isNotEmpty()) messageQueue.value = cur - sessionId
        list
    }

    internal fun queuedAsPrompt(q: QueuedMessage) = dev.fluwy.app.prompts.Prompts.render("runner.34", mapOf<String, Any?>("lb" to "{{")) +
        "a menos que diga para cancelar, trocar ou corrigir. Se for uma correção/complemento do pedido atual, ajuste o rumo; se for outro pedido, faça também, depois ou em paralelo.]\n${q.text}" +
        (if (q.attachments.isNotEmpty()) "\n\n[Arquivos anexados no workspace: ${q.attachments.joinToString()}]" else "") +
        (if (q.quote != null) "\n[O usuário respondia a esta mensagem sua: ${q.quote}]" else "")

    /** Entrega a fila ao agente que está rodando (chamado num ponto seguro do loop). */
    internal fun drainQueueInto(sessionId: String) {
        val list = takeQueue(sessionId)
        if (list.isEmpty()) return
        store.session(sessionId)?.sectionId?.let { autoNudges.remove(it); autoDone.remove(it) }
        list.forEach { q ->
            store.addItem(sessionId, ChatItem.User(text = q.text, attachments = q.attachments, replyTo = q.quote))
            skillsFor(q.text).forEach { sk -> store.addItem(sessionId, ChatItem.Step(tool = "skill_load", title = "Skill ativa: ${sk.name}", args = sk.description.take(90), result = "", status = StepStatus.OK)) }
        }
        store.editSession(sessionId) { it.copy(llm = it.llm + list.map { q -> LlmMessage("user", queuedAsPrompt(q) + skillsFor(q.text).joinToString("") { sk -> skillBlock(sk) }) }) }
    }

    /** Agente parado com mensagens na fila (e fila não pausada): envia tudo como um novo pedido. */
    internal fun flushQueueIfIdle(sessionId: String) {
        if (isRunning(sessionId)) return
        val list = takeQueue(sessionId)
        if (list.isEmpty()) return
        list.forEach { store.addItem(sessionId, ChatItem.User(text = it.text, attachments = it.attachments, replyTo = it.quote)) }
        send(sessionId, MultiRequest.combine(list.map { it.text }), list.flatMap { it.attachments }.distinct(), showUserMessage = false)
    }

    /** Remove uma mensagem da fila antes de ela chegar ao agente. */
    fun cancelQueued(sessionId: String, messageId: String) = synchronized(queueLock) {
        val cur = messageQueue.value
        val left = cur[sessionId].orEmpty().filterNot { it.id == messageId }
        messageQueue.value = if (left.isEmpty()) cur - sessionId else cur + (sessionId to left)
    }

    /** Pausa ou retoma a entrega da fila de um chat. Ao retomar com o agente parado, a fila é enviada na hora. */
    fun setQueuePaused(sessionId: String, paused: Boolean) {
        pausedQueues.value = if (paused) pausedQueues.value + sessionId else pausedQueues.value - sessionId
        if (!paused) flushQueueIfIdle(sessionId)
    }

    /** Mensagens ainda na fila de um chat. */
    fun queuedCount(sessionId: String): Int = messageQueue.value[sessionId]?.size ?: 0

    /** Skills que este pedido aciona (ver [SkillMatcher]). */
    internal fun skillsFor(text: String): List<Skill> {
        val enabled = store.state.value.skills.filter { it.enabled }
        return SkillMatcher.select(text, enabled.map { SkillMatcher.Candidate(it.name, it.description) })
            .mapNotNull { c -> enabled.firstOrNull { it.name == c.name } }.distinctBy { it.id }
    }
    internal fun skillBlock(sk: Skill) = "\n\n[Skill ativada para este pedido: ${sk.name}. Siga as instruções abaixo para cumprir o pedido; os valores que o usuário informou entram como parâmetros.]\n${sk.instructions}"

    /** Envia uma mensagem do usuário e (re)inicia o loop. */
    fun send(sessionId: String, text: String, attachments: List<String> = emptyList(), onDone: (() -> Unit)? = null, quotedReply: String? = null, showUserMessage: Boolean = true,
             directiveSuffix: String = ""): Job {
        // Mensagem comum depois de uma entrega final: volta ao normal (a parada valeu só para aquela ordem).
        if (directiveSuffix.isBlank()) finalDelivery.remove(sessionId)
        autoResumeJobs.remove(sessionId)?.cancel()  // o usuário (ou a retomada) agiu: cancela a retomada agendada
        if (showUserMessage && directiveSuffix.isBlank()) { webApiTests.remove(sessionId); truncNudges.remove(sessionId) }  // nova mensagem do usuário: libera os testes de Web API
        unknownToolWarned.removeIf { it.startsWith("$sessionId|") }
        jobs[sessionId]?.cancel()
        synchronized(childJobsByParent) { childJobsByParent.remove(sessionId)?.forEach { it.cancel() } }
        val quote = quotedReply?.trim()?.take(800)?.takeIf { it.isNotBlank() }
        if (showUserMessage) store.addItem(sessionId, ChatItem.User(text = text, attachments = attachments, replyTo = quote))
        if (showUserMessage) store.session(sessionId)?.sectionId?.let { autoNudges.remove(it); autoDone.remove(it) }
        // Usuário retomou um agente pausado no meio de uma tarefa: a mesma tarefa continua e será relatada ao terminar.
        if (pausedDelegations.remove(sessionId)) delegations[sessionId]?.let { d ->
            store.section(d.sectionId)?.let { sec ->
                (store.session(sec.sessionId)?.items?.firstOrNull { it.id == d.pillId } as? ChatItem.Step)?.let { pill ->
                    store.replaceItem(sec.sessionId, pill.copy(status = StepStatus.RUNNING, result = ""))
                }
            }
        }
        // Mensagem do usuário numa seção arquivada a traz de volta.
        if (showUserMessage) store.state.value.sections.firstOrNull { it.sessionId == sessionId && it.archived }?.let { store.upsertSection(it.copy(archived = false)) }
        // Mensagem do usuário retoma a seção pausada, inclusive o ciclo agendado (exceto a ordem de parar e entregar).
        if (showUserMessage && sessionId !in finalDelivery) store.state.value.sections.firstOrNull { it.sessionId == sessionId && it.configured && it.scheduleMinutes > 0 }?.let { sec ->
            store.agent(sec.coordinatorAgentId)?.takeIf { !it.autonomous }?.let { c ->
                store.upsertAgent(c.copy(autonomous = true, heartbeatMinutes = sec.scheduleMinutes, homeSessionId = sec.sessionId, lastBeatAt = System.currentTimeMillis()))
                runCatching { dev.fluwy.app.scheduler.Heartbeat.schedule(ctx, c.id, sec.scheduleMinutes) }
            }
        }
        var content = if (attachments.isEmpty()) text
        else text + "\n\n[Arquivos anexados no workspace: ${attachments.joinToString()}]"
        if (quote != null) content += "\n\n[Contexto: o usuário está respondendo à mensagem anterior do agente; o texto citado é histórico, não uma nova instrução.]\n" + quote.lineSequence().joinToString("\n") { "> $it" }
        // @Skill → injeta as instruções só desta skill (divulgação progressiva); @Agent → troca o agente da tarefa
        val st0 = store.state.value
        // Biblioteca compartilhada: qualquer skill ativa pode ser acionada em qualquer chat, por @menção ou pelo sentido do pedido.
        val activated = if (showUserMessage) skillsFor(text) else emptyList()
        activated.forEach { sk ->
            content += skillBlock(sk)
            ensureSkillFiles(sessionId, sk)
            store.addItem(sessionId, ChatItem.Step(tool = "skill_load", title = "Skill ativa: ${sk.name}", args = sk.description.take(90), result = "", status = StepStatus.OK))
        }
        // "@Nome" que é uma skill acionada não troca o agente do chat, mesmo que exista um agente com o mesmo nome.
        st0.agents.firstOrNull { a -> text.contains("@" + a.name, ignoreCase = true) && activated.none { SkillMatcher.norm(it.name) == SkillMatcher.norm(a.name) } }?.let { a ->
            store.editSession(sessionId) { it.copy(agentId = a.id) }
        }
        // "continuar / prossiga / retome": seguir de onde parou, nunca recomeçar o pedido original.
        if (showUserMessage && directiveSuffix.isBlank() && ContinuationPolicy.isContinueIntent(text)) content += "\n\n" + continuationDirective(sessionId, automatic = false)
        // Pedido de instalar skill: o APP instala (sem o modelo improvisar com curl/find) antes do loop; ver runSkillInstall.
        val installIntent = if (showUserMessage) dev.fluwy.app.skills.SkillInstallIntent.detect(text) else null
        if (installIntent != null) ToolContextPlanner.load(sessionId, listOf("skills_admin"))
        content += directiveSuffix
        store.editSession(sessionId) { it.copy(llm = it.llm + LlmMessage("user", content), status = SessionStatus.RUNNING) }
        dev.fluwy.app.sandbox.SandboxService.start(ctx, "Executando tarefa…")
        val job = scope.launch {
            try {
                maybeTitle(sessionId, text)
                if (installIntent != null) {
                    val out = runSkillInstall(sessionId, installIntent)
                    if (out == null) return@launch  // só instalar (ou falhou): o feedback já está no chat, sem chamar o modelo
                    store.editSession(sessionId) { it.copy(llm = it.llm + LlmMessage("user", out)) }
                }
                // Orquestrador: cada rodada é uma execução registrada (tarefa, objetivo, operações, estado final).
                ExecutionLedger.begin(sessionId, store.session(sessionId)?.items?.lastOrNull { it is ChatItem.User }?.id ?: "auto", text.take(500))
                try { loop(sessionId) } finally {
                    val st = store.session(sessionId)
                    ExecutionLedger.end(sessionId, st?.status?.name ?: "STOPPED",
                        (st?.items?.lastOrNull { it is ChatItem.Assistant } as? ChatItem.Assistant)?.text.orEmpty())
                }
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                // Mensagem legível + stack trace no log de erros (antes aparecia só "dev.fluwy.app.agent.X").
                runCatching { dev.fluwy.app.diag.ErrorLog.log("agent", "Erro interno ${t.javaClass.name}: ${t.message}\n" + t.stackTraceToString().take(2_500), sessionId, "loop") }
                store.addItem(sessionId, ChatItem.Error(text = "Erro interno (${t.javaClass.simpleName}" + (t.message?.let { ": ${it.take(160)}" } ?: "") + "). Detalhes no log de erros; envie uma mensagem para continuar."))
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
                if (jobs.values.none { it.isActive }) dev.fluwy.app.sandbox.SandboxService.start(ctx, "Terminal ativo")
                onDone?.invoke()
                // só relata quando nenhuma outra execução assumiu este chat (ex.: aprovação reenviou a tarefa)
                if (jobs[sessionId]?.isActive != true) runCatching { onRunEnded(sessionId) }
            }
        }
        jobs[sessionId] = job
        return job
    }

    // ------------------------------------------------------------------ auto-estabilização e continuidade
    internal val autoResumeRound = java.util.concurrent.ConcurrentHashMap<String, Int>()
    internal val autoResumeJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()

    internal val pendingRecovery = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    internal val contextOverflowAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Suspende e agenda a retomada automática (20 min, até 3 rodadas) após falha RECUPERÁVEL do provedor.
     * Enviar mensagem, "Tentar agora" ou parar cancela. Retorna o horário da retomada, ou null (erro
     * permanente ou rodadas esgotadas → fica aguardando o usuário).
     */
    internal fun scheduleAutoResume(sessionId: String, category: dev.fluwy.app.llm.LlmRecovery.Category): Long? {
        // Sem internet não é falha do modelo: não gasta as rodadas de retomada e volta sozinho assim que a rede voltar.
        val network = category == dev.fluwy.app.llm.LlmRecovery.Category.NETWORK
        val round = (autoResumeRound[sessionId] ?: 0) + (if (network) 0 else 1)
        val wait = if (network) NETWORK_RETRY_HINT_MS
            else dev.fluwy.app.llm.LlmRecovery.autoResumeDelayMillis(round, category) ?: run { autoResumeRound.remove(sessionId); return null }
        if (!network) autoResumeRound[sessionId] = round
        val wakeAt = System.currentTimeMillis() + wait
        store.editSession(sessionId) { it.copy(status = SessionStatus.SLEEPING, wakeAt = wakeAt,
            wakeReason = if (network) "aguardando a internet voltar" else "retomada automática após falha do modelo") }
        autoResumeJobs.remove(sessionId)?.cancel()
        autoResumeJobs[sessionId] = scope.launch {
            if (network) {
                // Espera a conexão: confere a rede do aparelho e o DNS a cada poucos segundos, por até NETWORK_MAX_WAIT_MS.
                val deadline = System.currentTimeMillis() + NETWORK_MAX_WAIT_MS
                kotlinx.coroutines.delay(5_000)
                while (System.currentTimeMillis() < deadline && !internetReady()) {
                    val cur = store.session(sessionId)
                    if (cur == null || cur.status != SessionStatus.SLEEPING || cur.wakeAt != wakeAt) { autoResumeJobs.remove(sessionId); return@launch }
                    kotlinx.coroutines.delay(8_000)
                }
                if (!internetReady()) {
                    autoResumeJobs.remove(sessionId)
                    store.editSession(sessionId) { if (it.status == SessionStatus.SLEEPING && it.wakeAt == wakeAt) it.copy(status = SessionStatus.WAITING_USER, wakeAt = null, wakeReason = "") else it }
                    store.addItemUnique(sessionId, ChatItem.Error(text = "Sem internet há ${NETWORK_MAX_WAIT_MS / 3_600_000} h. O trabalho ficou guardado no ponto em que parou; envie \"continue\" quando a conexão voltar."))
                    return@launch
                }
                kotlinx.coroutines.delay(2_000)  // rede recém-voltou: dá um instante para estabilizar
            } else {
                // Falha do modelo (ex.: limite 429): se o usuário trocar o modelo ou o provedor, tenta de novo na hora.
                // O app nunca troca de modelo sozinho.
                val modelAtFailure = modelKey(sessionId)
                val until = System.currentTimeMillis() + wait
                while (System.currentTimeMillis() < until) {
                    kotlinx.coroutines.delay(minOf(3_000L, until - System.currentTimeMillis()).coerceAtLeast(200))
                    val cur = store.session(sessionId)
                    if (cur == null || cur.status != SessionStatus.SLEEPING || cur.wakeAt != wakeAt) { autoResumeJobs.remove(sessionId); return@launch }
                    if (modelKey(sessionId) != modelAtFailure) {
                        store.addItem(sessionId, ChatItem.Step(tool = "model_switch", title = "Modelo trocado: tentando de novo agora", args = stepModel(store.agent(cur.agentId)), result = "", status = StepStatus.OK))
                        break
                    }
                }
            }
            autoResumeJobs.remove(sessionId)
            val s = store.session(sessionId) ?: return@launch
            if (s.status != SessionStatus.SLEEPING || s.wakeAt != wakeAt || isRunning(sessionId)) return@launch  // usuário já agiu
            store.editSession(sessionId) { it.copy(wakeAt = null, wakeReason = "") }
            send(sessionId, continuationDirective(sessionId, automatic = true), showUserMessage = false)
        }
        return wakeAt
    }

    /** Modelo e provedor que este chat usaria agora (mudam quando o usuário troca nas configurações). */
    internal fun modelKey(sessionId: String): String =
        stepModel(store.agent(store.session(sessionId)?.agentId)) + "@" + store.settings.baseUrl

    /** Aparelho com rede validada e DNS respondendo para o provedor do modelo (ou, na dúvida, para um host público). */
    internal fun internetReady(): Boolean {
        val connected = runCatching {
            val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
            val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return@runCatching false) ?: return@runCatching false
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }.getOrDefault(true)
        if (!connected) return false
        val host = runCatching { java.net.URI(store.settings.baseUrl).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: "dns.google"
        return runCatching { java.net.InetAddress.getByName(host); true }.getOrDefault(false)
    }

    /** Botão "Tentar agora" do card de recuperação: retoma do último ponto seguro, sem refazer o que foi feito. */
    fun retryAfterLlmFailure(sessionId: String) {
        store.session(sessionId)?.items?.filterIsInstance<ChatItem.LlmRecovery>()?.filter { it.state == "waiting" }
            ?.forEach { store.replaceItem(sessionId, it.copy(state = "resolved")) }
        send(sessionId, continuationDirective(sessionId, automatic = false), showUserMessage = false)
    }

    /** Botão "Cancelar" do card de recuperação. */
    fun cancelLlmRecovery(sessionId: String) {
        store.session(sessionId)?.items?.filterIsInstance<ChatItem.LlmRecovery>()?.filter { it.state == "waiting" }
            ?.forEach { store.replaceItem(sessionId, it.copy(state = "cancelled", resumeAt = null)) }
        autoResumeRound.remove(sessionId)
        stop(sessionId)
    }

    /** Instrução de continuidade: seguir da próxima etapa com base no que já foi feito, sem recomeçar. */
    internal fun continuationDirective(sessionId: String, automatic: Boolean): String {
        val s = store.session(sessionId)
        val plan = s?.items?.lastOrNull { it is ChatItem.Plan } as? ChatItem.Plan
        val lastAnswer = (s?.items?.lastOrNull { it is ChatItem.Assistant } as? ChatItem.Assistant)?.text?.take(500)
        val lastError = (s?.items?.lastOrNull { it is ChatItem.Error } as? ChatItem.Error)?.text?.take(200)
        val sec = store.section(s?.sectionId)?.takeIf { it.sessionId == sessionId }
        return buildString {
            append(if (automatic) dev.fluwy.app.prompts.Prompts.render("runner.32", mapOf<String, Any?>("lb" to "{{")) else dev.fluwy.app.prompts.Prompts.render("runner.33", mapOf<String, Any?>("lb" to "{{")))
            append(" Não recomece do zero nem repita o pedido original: revise o que já foi feito, identifique a última etapa concluída e execute a próxima.")
            if (plan != null) append("\nPlano: " + plan.phases.joinToString(" · ") { (if (it.done) "[x] " else "[ ] ") + it.title })
            if (lastError != null) append("\nO que interrompeu o trabalho: $lastError")
            if (lastAnswer != null) append("\nSua última resposta: $lastAnswer")
            if (sec != null) {
                append("\nEstado do time agora:\n").append(teamStatus(sessionId))
                append("\nO que já foi entregue não precisa ser delegado de novo: acione apenas quem falta ou peça ajustes específicos. Se tudo estiver pronto, entregue o relatório final e pare.")
            }
        }
    }

    // ------------------------------------------------------------------ confirmação de ações sensíveis
    /**
     * Portão por Agent: classifica a chamada (rótulo do botão, nome da ferramenta do conector, comando)
     * e, se a categoria exigir confirmação, cria o card e NÃO executa. Aprovação vale uma vez, por 30 min.
     * @return null para executar normalmente; um ToolResult para bloquear/aguardar.
     */
    internal suspend fun actionGate(sessionId: String, call: dev.fluwy.app.llm.ToolCall): ToolResult? {
        val sess = store.session(sessionId) ?: return null
        val agent = store.agent(sess.agentId)
        // Política por ferramenta do conector (como no Executor): "ask" sempre pede aprovação; "allow" nunca pede.
        val mcpPolicy = if (call.name.startsWith("mcp_")) dev.fluwy.app.connectors.Connectors.policyFor(store, call.name) else null
        if (mcpPolicy == dev.fluwy.app.connectors.Connectors.POLICY_ALLOW) return null
        if (agent?.confirmMode == "auto" && mcpPolicy != dev.fluwy.app.connectors.Connectors.POLICY_ASK) return null
        val args = runCatching { json.parseToJsonElement(call.argsJson).jsonObject }.getOrNull()
        fun arg(k: String) = runCatching { args?.get(k)?.jsonPrimitive?.content }.getOrNull().orEmpty()
        var host: String? = null
        val label: String? = when (call.name) {
            "browser_click" -> browserFor(sessionId).describe(arg("index").toIntOrNull() ?: -1)?.let { host = it.first; it.second }
            "browser_type" -> if (arg("submit") != "true") return null
                else browserFor(sessionId).describe(arg("index").toIntOrNull() ?: -1)?.let { host = it.first; it.second }
            "device_notification_reply" -> { host = dev.fluwy.app.device.NotificationReply.describe(arg("id")); arg("text") }
            "send_file" -> { host = dev.fluwy.app.share.ChannelSender.channel(arg("channel"))?.label; arg("path").substringAfterLast('/') }
            "device_accessibility_tap" -> arg("text")
            "device_accessibility_click" -> runCatching {
                dev.fluwy.app.device.DeviceAccessibilityActions.describe(sessionId, arg("snapshot_id"), arg("element_id").toIntOrNull() ?: -1)
            }.getOrNull()?.let { host = it.first; it.second }
            "browser_js" -> arg("code")
            "shell" -> arg("command")
            else -> null
        }
        val category = ActionGate.classify(call.name, label)
            ?: (if (mcpPolicy == dev.fluwy.app.connectors.Connectors.POLICY_ASK) ActionGate.Category.SEND else null) ?: return null
        if (mcpPolicy != dev.fluwy.app.connectors.Connectors.POLICY_ASK && !ActionGate.requiresConfirmation(agent?.confirmMode, agent?.confirmCategories, category)) return null
        val key = ActionGate.approvalKey(call.name, normArgs(call.argsJson), label, host)
        val now = System.currentTimeMillis()
        val recent = sess.items.filterIsInstance<ChatItem.Approval>()
            .filter { it.callKey == key && now - it.time < ActionGate.DECISION_TTL_MS }
        recent.lastOrNull { it.state == "approved" }?.let { ok ->
            store.replaceItem(sessionId, ok.copy(state = "used"))  // vale uma única execução
            return null
        }
        if (recent.any { it.state == "denied" })
            return ToolResult("NEGADO pelo usuário: \"${category.label}\" não foi autorizado. Não tente de novo nem por outro caminho; siga sem essa ação ou finalize explicando.")
        if (recent.any { it.state == "pending" })
            return ToolResult("Aguardando a confirmação do usuário para esta ação. Nada foi executado.", SessionStatus.WAITING_USER)
        val summary = ActionGate.summary(category, call.name, label, host)
        store.addItem(sessionId, ChatItem.Approval(tool = call.name, callKey = key, category = category.id, summary = summary))
        dev.fluwy.app.notify.NotificationCenter.post("Aprovação necessária" + (agent?.name?.let { " · $it" } ?: ""), "Uma ação está aguardando sua aprovação: $summary",
            dev.fluwy.app.notify.NotificationCenter.Priority.HIGH, dev.fluwy.app.notify.NotificationCenter.Category.APPROVAL, sessionId, agent?.name.orEmpty(), dedupeKey = "approval|$sessionId|$key")
        return ToolResult("AGUARDANDO CONFIRMAÇÃO do usuário (${category.label}): $summary. Nada foi executado. " +
            "Quando ele aprovar você receberá uma mensagem; então leia a tela de novo e repita a MESMA ação. Se ele negar, não insista.", SessionStatus.WAITING_USER)
    }

    // ------------------------------------------------------------------ Seções (time de Agents)
    /** Permissões escolhidas pelo usuário no card de autorização da seção. */
    data class SectionPermissions(
        val browser: Boolean, val terminal: Boolean, val wallet: Boolean,
        val uiConnectorIds: List<String>, val deviceCaps: List<String>, val fullAuto: Boolean,
    )

    internal val WAIT_TASK = Regex("^\\W*(aguard|esper|wait|await|fico no aguardo)", RegexOption.IGNORE_CASE)
    internal val sectionColors = listOf(0xFFFF6B3DL, 0xFF4D8DFFL, 0xFF2FBF71L, 0xFFFFC53DL, 0xFFB57BFFL, 0xFFFF5C8AL, 0xFF2EC4C4L, 0xFF9C978EL)

    /**
     * Adiciona um agente (da biblioteca ou recém-criado) a uma seção: entra em agentIds, a lista "Seu time" do coordenador
     * é refeita e o coordenador é avisado no chat da seção — passa a poder delegar para ele na hora.
     */
    fun addAgentToSection(sectionId: String, agentId: String): String {
        val sec = store.section(sectionId) ?: return "Seção não encontrada."
        val ag = store.agent(agentId) ?: return "Agente não encontrado."
        if (agentId in sec.agentIds) return "${ag.name} já faz parte da seção."
        val updated = sec.copy(agentIds = sec.agentIds + agentId)
        store.upsertSection(updated)
        store.agent(sec.coordinatorAgentId)?.let { coord ->
            val ins = coord.instructions
            val start = ins.indexOf("Seu time:\n")
            val newIns = if (start >= 0) {
                val end = ins.indexOf("\nUse team_plan", start).takeIf { it > 0 } ?: ins.length
                ins.substring(0, start) + "Seu time:\n" + rosterOf(updated) + ins.substring(end)
            } else ins + "\nSeu time:\n" + rosterOf(updated)
            store.upsertAgent(coord.copy(instructions = newIns))
        }
        store.session(sec.sessionId)?.let { chat ->
            store.editSession(chat.id) { it.copy(llm = it.llm + LlmMessage("user", "[Sistema] ${ag.name} entrou no time desta seção" +
                (if (ag.mission.isNotBlank()) " (função: ${ag.mission})" else "") + dev.fluwy.app.prompts.Prompts.render("runner.31", mapOf<String, Any?>("lb" to "{{")))) }
        }
        return "${ag.name} adicionado à seção ${sec.name}."
    }

    /** Tira um agente do time (e, se [delete], apaga da biblioteca); a lista do coordenador é refeita e ele é avisado. */
    fun removeAgentFromSection(sectionId: String, agentId: String, delete: Boolean) {
        val sec = store.section(sectionId) ?: return
        val ag = store.agent(agentId)
        val updated = sec.copy(agentIds = sec.agentIds - agentId, memberOrder = sec.memberOrder - agentId)
        store.upsertSection(updated)
        store.agent(sec.coordinatorAgentId)?.let { coord ->
            val ins = coord.instructions; val start = ins.indexOf("Seu time:\n")
            if (start >= 0) {
                val end = ins.indexOf("\nUse team_plan", start).takeIf { it > 0 } ?: ins.length
                store.upsertAgent(coord.copy(instructions = ins.substring(0, start) + "Seu time:\n" + rosterOf(updated) + ins.substring(end)))
            }
        }
        store.session(sec.sessionId)?.let { chat -> store.editSession(chat.id) { it.copy(llm = it.llm + LlmMessage("user",
            dev.fluwy.app.prompts.Prompts.render("runner.30", mapOf<String, Any?>("agente" to (ag?.name ?: "Um agente"), "lb" to "{{")))) } }
        store.state.value.sessions.filter { it.sectionId == sectionId && it.agentId == agentId }.forEach { stop(it.id, pauseAutonomy = true) }
        if (delete) { dev.fluwy.app.scheduler.Heartbeat.cancel(ctx, agentId); store.deleteAgent(agentId) }
    }

    /** Aplica as permissões do card a todos os Agents da seção e dá a largada no coordenador. Retorna o chat da seção. */
    fun activateSection(sessionId: String, item: ChatItem.SectionSetup, p: SectionPermissions): String? {
        val cur = store.session(sessionId)?.items?.firstOrNull { it.id == item.id } as? ChatItem.SectionSetup ?: return null
        if (cur.state != "pending") return store.section(cur.sectionId)?.sessionId
        val sec = store.section(cur.sectionId) ?: return null
        val wallet = WalletAgentPolicyStore(ctx.applicationContext)
        (sec.agentIds + sec.coordinatorAgentId).forEach { id ->
            store.agent(id)?.let { a ->
                val coord = id == sec.coordinatorAgentId  // o coordenador só delega
                store.upsertAgent(a.copy(
                    useBrowser = p.browser && a.useBrowser && !coord, useTerminal = p.terminal && a.useTerminal && !coord,
                    deviceCaps = p.deviceCaps, uiConnectorIds = p.uiConnectorIds,
                    confirmMode = if (p.fullAuto) "auto" else "ask",
                ))
                runCatching {
                    wallet.setAgentCapabilities(id, readAddress = p.wallet, readBalance = p.wallet, requestTransfer = p.wallet,
                        autonomousTransfer = false, dayTrade = false)
                }
            }
        }
        store.replaceItem(sessionId, cur.copy(state = "activated"))
        store.upsertSection(sec.copy(configured = true))
        if (sec.scheduleMinutes > 0) store.agent(sec.coordinatorAgentId)?.let { c ->
            // Agendamento feito pelo app (não depende de o modelo lembrar de chamar set_schedule).
            store.upsertAgent(c.copy(autonomous = true, heartbeatMinutes = sec.scheduleMinutes, homeSessionId = sec.sessionId,
                mission = sec.description.ifBlank { sec.firstTask }.take(600), lastBeatAt = System.currentTimeMillis(),
                dailyTokenBudget = maxOf(c.dailyTokenBudget, 20_000_000L)))
            runCatching { dev.fluwy.app.scheduler.Heartbeat.schedule(ctx, c.id, sec.scheduleMinutes) }
            store.addItem(sec.sessionId, ChatItem.Step(tool = "set_schedule", title = "Agendado: " + scheduleLabel(sec.scheduleMinutes), args = "", result = "", status = StepStatus.OK))
        }
        startSection(sec)
        return sec.sessionId
    }

    /** Arquiva a seção: pausa tudo (inclusive o ciclo agendado) e a tira da lista principal. Nada é apagado. */
    fun archiveSection(sectionId: String) {
        val sec = store.section(sectionId) ?: return
        if (sec.configured) pauseSection(sectionId)
        store.section(sectionId)?.let { store.upsertSection(it.copy(archived = true)) }
    }

    /** Restaura uma seção arquivada. Ela volta parada; uma mensagem no chat retoma o trabalho e o agendamento. */
    fun restoreSection(sectionId: String) {
        store.section(sectionId)?.let { store.upsertSection(it.copy(archived = false)) }
    }

    fun cancelSectionSetup(sessionId: String, item: ChatItem.SectionSetup) {
        val cur = store.session(sessionId)?.items?.firstOrNull { it.id == item.id } as? ChatItem.SectionSetup ?: return
        if (cur.state != "pending") return
        store.replaceItem(sessionId, cur.copy(state = "cancelled"))
    }

    /**
     * Reinício do zero de um chat comum (/clear): contexto, histórico, workspace, terminal e dados seguros.
     */
    suspend fun resetSession(sessionId: String) {
        val s = store.session(sessionId) ?: return
        val sec = store.section(s.sectionId)
        if (sec != null) { wipeSection(sec); return }
        wipeRuntime(sessionId)
        wipeWorkspace(sessionId)
    }

    /** Limpar contexto da seção: apaga TUDO (conversa, workspace, terminais, cofre) e deixa o time parado. */
    fun clearSectionContext(sectionId: String) {
        val sec = store.section(sectionId) ?: return
        scope.launch { wipeSection(sec) }
    }

    /** Reinicia a seção do zero e o coordenador recomeça do pedido original. */
    fun restartSection(sectionId: String) {
        val sec = store.section(sectionId) ?: return
        scope.launch {
            wipeSection(sec)
            if (sec.configured) startSection(sec)
        }
    }

    /** Delegações em andamento: chat do agente → (seção, pílula no chat do coordenador, índice dos itens ao começar). */
    internal data class Delegation(val sectionId: String, val memberId: String, val pillId: String, val startIndex: Int, val task: String = "",
        val startedAt: Long = System.currentTimeMillis(), val taskId: String? = null)
    /** Entregas concluídas por agente (tarefa, quando, resultado) — impede re-delegar o mesmo trabalho. */
    internal data class Delivered(val task: String, val at: Long, val answer: String)
    internal val delivered = java.util.concurrent.ConcurrentHashMap<String, MutableList<Delivered>>()
    internal val delegations = java.util.concurrent.ConcurrentHashMap<String, Delegation>()
    internal val sectionReportLocks = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()
    /** Muda a cada pausa da seção: relatórios enfileirados antes da pausa são descartados. */
    internal val sectionGeneration = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /**
     * Pausa em cascata: para o coordenador e TODOS os agentes da seção na hora, descarta relatórios
     * pendentes (para ninguém religar o coordenador sozinho) e marca as pílulas como pausadas.
     */
    fun pauseSection(sectionId: String) {
        val sec = store.section(sectionId) ?: return
        sectionGeneration.merge(sectionId, 1, Int::plus)
        delegations.entries.removeIf { it.value.sectionId == sectionId }
        // Pausa manual vale na hora e inclui o próximo ciclo agendado: nada volta a rodar até o usuário mandar mensagem.
        val teamIds = (sec.agentIds + sec.coordinatorAgentId).toSet()
        store.state.value.agents.filter { it.id in teamIds && it.autonomous }.forEach { a ->
            if (a.id == sec.coordinatorAgentId && sec.scheduleMinutes <= 0) store.section(sectionId)?.let { store.upsertSection(it.copy(scheduleMinutes = a.heartbeatMinutes.coerceIn(1, 10_080))) }
            store.upsertAgent(a.copy(autonomous = false))
            runCatching { dev.fluwy.app.scheduler.Heartbeat.cancel(ctx, a.id) }
        }
        autoNudges.remove(sectionId); planNudges.remove(sectionId)
        store.state.value.sessions.filter { it.sectionId == sectionId || it.id == sec.sessionId }.forEach { stop(it.id, pauseAutonomy = true) }
        store.session(sec.sessionId)?.items?.filterIsInstance<ChatItem.Step>()
            ?.filter { it.tool == TEAM_MEMBER_STEP && it.status == StepStatus.RUNNING }
            ?.forEach { store.replaceItem(sec.sessionId, it.copy(status = StepStatus.OK, result = "pausado")) }
        val pausedText = "Seção pausada: coordenador, agentes e próximo ciclo parados. Envie uma mensagem para retomar."
        if ((store.session(sec.sessionId)?.items?.lastOrNull() as? ChatItem.Error)?.text != pausedText) store.addItem(sec.sessionId, ChatItem.Error(text = pausedText))
    }

    internal val autoNudges = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Int>>()
    /** Seções autônomas cujo coordenador declarou o objetivo inteiro entregue (section_done). */
    internal val autoDone = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    // ───────── Desfazer (/undo): estado anterior de cada arquivo que o agente alterou ─────────
    internal class FileChange(val file: java.io.File, val previous: String?, val shown: String)
    internal val fileJournal = java.util.concurrent.ConcurrentHashMap<String, java.util.ArrayDeque<FileChange>>()
    /** Desfaz a última alteração de arquivo feita pelo agente neste chat (file_write/file_edit). */
    fun undoLastFileChange(sessionId: String): String {
        val q = fileJournal[sessionId]
        val change = q?.let { synchronized(it) { it.pollLast() } } ?: return "Nada para desfazer: o agente não alterou arquivos neste chat desde que o app abriu."
        val ok = runCatching {
            if (change.previous == null) { if (change.file.exists()) change.file.delete() }
            else { change.file.parentFile?.mkdirs(); change.file.writeText(change.previous) }
            true
        }.getOrDefault(false)
        if (!ok) return "Não consegui desfazer a alteração em ${change.shown}."
        val what = if (change.previous == null) "arquivo removido (ele tinha sido criado pelo agente)" else "conteúdo anterior restaurado"
        store.addItem(sessionId, ChatItem.Step(tool = "undo", title = "Desfeito: ${change.shown}", args = what, result = "", status = StepStatus.OK))
        store.editSession(sessionId) { it.copy(llm = it.llm + LlmMessage("user", dev.fluwy.app.prompts.Prompts.render("runner.23", mapOf<String, Any?>("shown" to (change.shown), "what" to (what), "lb" to "{{")))) }
        val left = q?.size ?: 0
        return "Desfeito: ${change.shown} — $what." + (if (left > 0) " Ainda há $left alteração(ões) que podem ser desfeitas." else "")
    }

    // ───────── Edição de arquivos: quem leu o quê, e falhas seguidas por arquivo ─────────
    internal val editFails = java.util.concurrent.ConcurrentHashMap<String, Int>()
    internal val fileSeen = java.util.concurrent.ConcurrentHashMap<String, Long>()
    // ───────── Workers em paralelo: snapshot do projeto e integração de volta ─────────
    internal val HEAVY_DIRS = setOf("node_modules", ".gradle", "__pycache__", ".venv", "venv", ".next", ".turbo", ".cache")
    internal data class WorkerMerge(val applied: List<String>, val deleted: List<String>, val conflicts: List<String>)
    internal val peerMessages = java.util.concurrent.ConcurrentHashMap<String, Int>()
    internal val scriptPolicies = java.util.concurrent.ConcurrentHashMap<String, ScriptBuilderPolicy>()
    internal val planLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()
    internal val planNudges = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Int>>()
    internal val finalReportAsked = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Na abertura do app: retoma planos que estavam em andamento. */
    fun recoverSections() {
        store.state.value.sections.filter { it.configured && it.plan.isNotEmpty() &&
            SectionPlanEngine.state(it.plan) != SectionPlanEngine.State.COMPLETED }.forEach { planWatchdog(it.id, afterRestart = true) }
    }

    /** Orientações do coordenador aguardando o próximo ponto seguro do loop do agente. */
    internal val coordinatorInbox = java.util.concurrent.ConcurrentHashMap<String, MutableList<String>>()
    internal val lastCheckpointStep = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** Resposta do card de confirmação. */
    fun decideApproval(sessionId: String, item: ChatItem.Approval, approve: Boolean) {
        val cur = store.session(sessionId)?.items?.firstOrNull { it.id == item.id } as? ChatItem.Approval ?: return
        if (cur.state != "pending") return
        store.replaceItem(sessionId, cur.copy(state = if (approve) "approved" else "denied"))
        val msg = if (approve) dev.fluwy.app.prompts.Prompts.render("runner.5", mapOf<String, Any?>("summary" to (cur.summary), "lb" to "{{"))
            else dev.fluwy.app.prompts.Prompts.render("runner.4", mapOf<String, Any?>("summary" to (cur.summary), "lb" to "{{"))
        send(sessionId, msg, showUserMessage = false)
    }

    // ------------------------------------------------------------------ ask_question / ask_secure (respostas da UI)
    /** O usuário respondeu o card de perguntas: marca respondido e retoma a tarefa com as respostas. */
    fun answerQuestion(sessionId: String, item: ChatItem.Question, answers: List<List<String>>) {
        runCatching { dev.fluwy.app.notify.NotificationCenter.markRead(sessionId) } // respondeu: some da barra
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
        // Seções criadas a partir deste chat herdam o dado na hora (o cofre da seção é compartilhado pelos agentes).
        store.state.value.sections.filter { it.originSessionId == sessionId }.forEach { SecretVault.put(it.sessionId, clean) }
        store.replaceItem(sessionId, cur.copy(state = "submitted", submitted = clean.keys.toList()))
        val target = cur.forSessionId?.takeIf { store.session(it) != null }
        if (target != null) {
            // Pedido por um agente da seção: o cofre já é o mesmo; retoma o agente e avisa o coordenador.
            SecretVault.put(target, clean)
            send(target, AskTools.secureSubmittedMessage(clean.keys.toList()), showUserMessage = false)
            if (!isRunning(sessionId)) store.addItem(sessionId, ChatItem.Assistant(text = "🔒 Dados enviados para ${cur.forAgentName ?: "o agente"}: " + clean.keys.joinToString { "{{secret:$it}}" }))
        } else send(sessionId, AskTools.secureSubmittedMessage(clean.keys.toList()), showUserMessage = false)
    }

    fun cancelSecure(sessionId: String, item: ChatItem.SecureForm) {
        val cur = store.session(sessionId)?.items?.firstOrNull { it.id == item.id } as? ChatItem.SecureForm ?: return
        if (cur.state != "pending") return
        store.replaceItem(sessionId, cur.copy(state = "cancelled"))
        send(cur.forSessionId?.takeIf { store.session(it) != null } ?: sessionId, AskTools.SECURE_CANCELLED, showUserMessage = false)
    }

    internal val ctxMgr = ContextManager(store, sandbox, llm)

    // ===================== AUTONOMIA 24/7 (heartbeat) =====================
    internal var autonomyJob: Job? = null

    /**
     * Autonomia com DOIS mecanismos para nunca falhar:
     *  (1) ticker em processo a cada 20s — dispara no horário certo enquanto o app/serviço vive
     *      (precisão de segundos, ideal para intervalos curtos como 2 min);
     *  (2) AlarmManager — backup que reacorda o app mesmo depois de morto/boot.
     */
    fun startAutonomy() {
        dev.fluwy.app.scheduler.Heartbeat.rescheduleAll(ctx)
        val hasArmedRules = runCatching { dev.fluwy.app.wallet.DayTradeStore(ctx.applicationContext).armedAgentIds().isNotEmpty() }.getOrDefault(false)
        if (store.state.value.agents.any { it.autonomous } || hasArmedRules)
            dev.fluwy.app.sandbox.SandboxService.start(ctx, if (hasArmedRules) "Monitorando stop-loss/take-profit" else "Agentes autônomos ativos")
        if (autonomyJob?.isActive == true) return
        autonomyJob = scope.launch {
            while (isActive) {
                runCatching { autonomyTick() }.onFailure { t ->
                    android.util.Log.e("Fluwy.Autonomy", "Falha no scheduler local", t)
                    runCatching { dev.fluwy.app.diag.ErrorLog.log("scheduler", "autonomyTick: ${t.javaClass.simpleName}: ${t.message.orEmpty()}", null, "heartbeat") }
                }
                // Stop-loss/take-profit AUTOMÁTICO: avalia as regras armadas a cada ~20 s,
                // independente do ciclo do agente, e fecha a posição assim que o alvo é atingido.
                if (System.currentTimeMillis() - lastRuleEvalAt >= 20_000L) {
                    lastRuleEvalAt = System.currentTimeMillis()
                    runCatching { evaluateAllArmedRules() }.onFailure { t ->
                        android.util.Log.e("Fluwy.Autonomy", "Falha ao avaliar regras de trade", t)
                        runCatching { dev.fluwy.app.diag.ErrorLog.log("scheduler", "evaluateAllArmedRules: ${t.javaClass.simpleName}: ${t.message.orEmpty()}", null, "trade") }
                    }
                }
                // Intervalos de 1 minuto precisam de margem; verificar a cada 5 s evita atraso
                // adicional causado pelo ticker de 20 s.
                kotlinx.coroutines.delay(5_000)
            }
        }
    }

    @Volatile internal var lastRuleEvalAt = 0L

    /**
     * Chamado pelo alarme (AlarmManager) do agendamento. NÃO bloqueia o receiver: dispara o ciclo e a
     * avaliação de regras no próprio escopo do app, para o receiver finalizar rápido e nunca estourar
     * o limite de ~10 s dos BroadcastReceivers (o que quebrava a corrente de agendamento).
     */
    fun triggerHeartbeatFromAlarm(agentId: String) {
        val a = store.agent(agentId) ?: return
        if (!a.autonomous || a.homeSessionId == null) return
        scope.launch {
            runCatchingCancellable { evaluateAllArmedRules() }.onFailure {
                runCatching { dev.fluwy.app.diag.ErrorLog.log("scheduler", "evaluateAllArmedRules (alarme): ${it.message}", a.homeSessionId, "trade") }
            }
            // O alarme JÁ é o horário agendado → não exigimos "due" (evita perder o ciclo por arredondamento).
            if (canBeat(a, System.currentTimeMillis(), java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY), requireDue = false)) heartbeat(a)
        }
    }

    // Dia (yyyy-MM-dd) em que já avisamos cada agente sobre orçamento esgotado — evita repetir o aviso.
    internal val budgetWarnedDay = java.util.concurrent.ConcurrentHashMap<String, String>()

    internal object CustomDefParserFence { fun strip(t: String) = dev.fluwy.app.uiconnector.custom.CustomDefParser.stripFences(t) }

    /** Detectores puros de loop/degeneração (sem Android) — testáveis na JVM. */
    companion object LoopGuards {
        const val MAX_PARALLEL_MEMBERS = 4
        /** Quanto o browser_navigate espera o app voltar ao primeiro plano antes de desistir. */
        const val BROWSER_WAIT_FOREGROUND_MS = 15 * 60_000L
        /** Prefixo de retorno do team_delegate quando o agente realmente começou (o despacho do plano confere). */
        const val DELEGATION_STARTED = "OK:"
        /** Lembretes ao coordenador sem nenhuma mudança no plano antes de declarar o plano travado. */
        const val MAX_PLAN_NUDGES = 2
        const val MAX_AUTO_NUDGES = 3
        /** Queda de internet: prazo mostrado no chat e teto de espera pela volta da conexão. */
        const val NETWORK_RETRY_HINT_MS = 2 * 60_000L
        const val NETWORK_MAX_WAIT_MS = 12 * 3_600_000L
        /** Falhas seguidas de file_edit no mesmo arquivo antes de exigir uma releitura. */
        const val MAX_EDIT_FAILS = 2
        /** Depois disso, um snapshot de browser arquivado é tratado como antigo e não é relido sem pedido explícito. */
        const val STALE_SNAPSHOT_MINUTES = 3L
        const val MEMBER_SLOW_MINUTES = 30L
        internal val STRICT_REPEAT_TOOLS = setOf("connector_validate", "connector_test", "connector_propose", "connector_edit_propose")
        /** Leituras de conteúdo: o mesmo texto longo voltando por caminhos diferentes é falta de progresso. */
        /** Leituras que, logo depois de uma compactação, são refeitas por necessidade (o conteúdo saiu do contexto). */
        internal val POST_COMPACTION_READS = setOf("file_read", "file_list", "notes_read", "memory_search", "skill_load", "team_status", "browser_read", "browser_snapshot", "app_read")
        internal val CONTENT_REPEAT_TOOLS = setOf("file_read", "file_list", "memory_search", "notes_read", "connector_spec", "skill_load")
        internal val PURE_REPEAT_TOOLS = setOf(
            "file_read", "file_list", "memory_search", "notes_read", "connector_spec", "skill_load",
            "wallet_get_balance", "wallet_list_tokens", "market_discover", "market_token_info",
            "market_price", "market_candles", "market_liquidity", "browser_navigate",
        )

        /** Duas chamadas idênticas de builder com o mesmo resultado já indicam ausência de progresso. */
        fun repeatToolThreshold(name: String): Int = when {
            name in STRICT_REPEAT_TOOLS -> 2
            name in PURE_REPEAT_TOOLS -> 3
            else -> 6
        }

        fun memoizeConnectorBuilderCall(name: String): Boolean = name in STRICT_REPEAT_TOOLS

        internal val ACTION_INTENT = Regex(
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

    /**
     * Dispara um ciclo autônomo. Só roda se o agente JÁ tem uma sessão (homeSessionId) iniciada pelo
     * usuário via set_schedule numa conversa — nunca cria sessão sozinho (evita auto-início ao criar).
     */
    fun heartbeat(agent: AgentProfile): Job {
        val dispatched = heartbeatDispatchGate.dispatchIfIdle(
            isBusy = {
                val current = store.agent(agent.id) ?: agent
                current.homeSessionId?.let { jobs[it]?.isActive == true } == true
            },
            dispatch = { startHeartbeatCycle(agent) },
        )
        if (dispatched != null) return dispatched
        val current = store.agent(agent.id) ?: agent
        return current.homeSessionId?.let { jobs[it]?.takeIf(Job::isActive) } ?: scope.launch { }
    }

    /** Gera o áudio da última resposta final e adiciona no chat (sem bloquear o loop). */
    fun speakLastAnswer(sessionId: String, itemId: String? = null) {
        val target = store.session(sessionId)?.items?.let { items ->
            (if (itemId != null) items.firstOrNull { it.id == itemId } else items.lastOrNull { it is ChatItem.Assistant && it.final }) as? ChatItem.Assistant
        } ?: return
        scope.launch {
            // Indicador "Gerando áudio…" (o card da ferramenta não aparece; no fim fica só o áudio).
            val step = ChatItem.Step(tool = "reply_audio", title = "Gerando áudio", args = "")
            store.addItem(sessionId, step)
            val audio = runCatching { dev.fluwy.app.assistant.VoiceReplies.synthesize(ctx, java.io.File(sandbox.workspaceFor(sessionId), "audio"), target.text, target.id) }.getOrNull()
            store.replaceItem(sessionId, step.copy(status = StepStatus.OK))
            if (audio != null) store.addItem(sessionId, audio)
            else store.addItemUnique(sessionId, ChatItem.Error(text = "Não consegui gerar o áudio: instale uma voz em português em Configurações do Android › Saída de texto para voz."))
        }
    }

    // ===================== COMANDOS DO COMPOSER =====================
    /** Compacta o contexto da sessão agora (/compact). */
    suspend fun compactNow(sessionId: String): String {
        val s = store.session(sessionId) ?: return "Sessão não encontrada"
        val before = ctxMgr.estimateTokens(s.llm)
        val after = try { ctxMgr.compactIfNeeded(s, force = true) } catch (c: kotlinx.coroutines.CancellationException) { throw c } catch (t: Throwable) {
            runCatching { dev.fluwy.app.diag.ErrorLog.log("agent", "Falha no /compact (${t.javaClass.simpleName}): ${t.message}", sessionId, "compact") }
            ctxMgr.safeTrim(s)
        }
        val now = ctxMgr.estimateTokens(after.llm) + ctxMgr.estimateTokens(after.summary)
        return "Contexto compactado: ~$before → ~$now tokens."
    }

    /** Pausa a autonomia de um agente: cancela alarme/ciclos. Só volta quando o usuário reativar. */
    fun pauseAgent(agentId: String) {
        store.agent(agentId)?.let {
            store.upsertAgent(it.copy(autonomous = false))
            dev.fluwy.app.scheduler.Heartbeat.cancel(ctx, agentId)
            it.homeSessionId?.let { sid -> jobs.remove(sid)?.cancel(); store.editSession(sid) { s -> s.copy(status = SessionStatus.STOPPED) } }
        }
    }

    fun connectorTools(): List<JsonObject> = dev.fluwy.app.connectors.Connectors.toolDefinitions(store)

    /**
     * Cria e configura um Agent completo a partir de uma descrição em linguagem natural (@create-agent).
     * Usa o modelo rápido para gerar nome, personalidade, instruções, missão, autonomia e skills sugeridas.
     */
    suspend fun createAgentFromPrompt(desc: String, conversationId: String? = null): AgentProfile {
        val skillNames = store.state.value.skills.filter { it.enabled }.joinToString(", ") { it.name }
        val raw = runCatching {
            llm.quick(dev.fluwy.app.prompts.Prompts.render("runner.3", mapOf<String, Any?>("skill_names" to (skillNames), "desc" to (desc), "lb" to "{{")), maxTokens = 600, conversationId = conversationId)
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
            name = name, face = str("face", "jelly").ifBlank { "jelly" },
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
        if (sleepingSessions.isNotEmpty()) dev.fluwy.app.sandbox.SandboxService.start(ctx, "Agente em modo contínuo")
        sleepingSessions.forEach { s ->
            if (jobs[s.id]?.isActive == true) return@forEach
            val wake = s.wakeAt ?: System.currentTimeMillis()
            jobs[s.id] = scope.launch {
                try {
                    val early = waitUntil(s.id, wake, s.wakeReason)
                    val tradeIds = tradeIdsFromWakeReason(s.wakeReason)
                    wakeUp(s.id)
                    if (early) noteEarlyWake(s.id)
                    if (tradeIds.isNotEmpty()) checkExpiredTradesOnce(s.id, tradeIds)
                    loop(s.id)
                } catch (c: kotlinx.coroutines.CancellationException) { throw c
                } catch (t: Throwable) {
                    runCatching { dev.fluwy.app.diag.ErrorLog.log("agent", "retomada contínua: ${t.javaClass.simpleName}: ${t.message.orEmpty()}", s.id, "resume") }
                    store.addItem(s.id, ChatItem.Error(text = t.message ?: t.toString()))
                    store.editSession(s.id) { it.copy(status = SessionStatus.ERROR) }
                } finally { setActivity(s.id, Activity()) }
            }
        }
    }

    /** O usuário entrou e salvou a sessão: libera o site e retoma a tarefa. */
    fun onUserLoggedIn(sessionId: String, domain: String) {
        store.addItem(sessionId, ChatItem.Step(tool = "login_done", title = "Sessão de $domain salva", args = domain, result = "o agente continua", status = StepStatus.OK))
        sendOrQueue(sessionId, dev.fluwy.app.prompts.Prompts.render("runner.2", mapOf<String, Any?>("domain" to (domain), "lb" to "{{")))
    }

    internal val archiveReads = ArchiveReadGuard()

    /** Mesma leitura (arquivo, offset, limite) sem o arquivo ter mudado e sem compactação no meio: devolve um aviso curto em vez do trecho de novo. */
    internal val fileReadSeen = java.util.concurrent.ConcurrentHashMap<String, String>()
    /** Linhas já lidas por sessão+arquivo (válidas enquanto o arquivo, compactações e limpezas não mudam). */
    internal val readCoverage = java.util.concurrent.ConcurrentHashMap<String, Pair<String, MutableList<IntRange>>>()
    internal val redundantReads = java.util.concurrent.ConcurrentHashMap<String, Int>()
    @Volatile internal var pendingCoverage: Pair<String, String>? = null

    internal val truncNudges = java.util.concurrent.ConcurrentHashMap<String, Int>()
    internal val TRUNCATION_NUDGE = dev.fluwy.app.prompts.Prompts.render("runner.1", mapOf<String, Any?>("lb" to "{{")) +
        "Não repita a mesma chamada gigante. Divida: escreva códigos/arquivos grandes em partes com file_write (append) de até ~150 linhas cada; " +
        "para Web APIs, salve o código num arquivo e chame webapi_test/webapi_save com code_file=<caminho> em vez de colar o código inteiro; " +
        "pense menos e aja em passos curtos. Continue a tarefa de onde parou."
    internal val repeatCounts = java.util.concurrent.ConcurrentHashMap<String, Int>()
    /** Última versão de Web API que passou no teste, por sessão (salvar exige teste aprovado do mesmo código). */
    internal val webApiPassed = java.util.concurrent.ConcurrentHashMap<String, Int>()
    internal val webApiTests = java.util.concurrent.ConcurrentHashMap<String, Int>()
    /** Tudo o que pode ser marcado com @: skills, conectores (MCP e UI) e agents. */
    fun mentionTargets(): List<Mentions.Target> {
        val st = store.state.value
        val out = ArrayList<Mentions.Target>()
        st.skills.filter { it.enabled }.forEach { out += Mentions.Target(it.name, Mentions.Kind.SKILL, it.id) }
        st.connectors.filter { it.enabled && it.connectedAt != null }.forEach {
            out += Mentions.Target(it.name, Mentions.Kind.CONNECTOR, "mcp:" + it.id.replace(Regex("[^A-Za-z0-9_]"), "_"))
        }
        runCatching { dev.fluwy.app.FluwyApp.instance.uiConnectors.connectors }.getOrNull().orEmpty().forEach {
            out += Mentions.Target(it.spec.name, Mentions.Kind.CONNECTOR, "connector:" + it.spec.id)
        }
        st.agents.forEach { out += Mentions.Target(it.name, Mentions.Kind.AGENT, it.id) }
        // @APIHUB e @APIHUB:<slug> (Web API escolhida pelo usuário).
        out += Mentions.Target("APIHUB", Mentions.Kind.WEBAPI, "")
        runCatching { dev.fluwy.app.browser.WebApiHub.enabled(ctx) }.getOrDefault(emptyList()).forEach { out += Mentions.Target("APIHUB:" + it.slug, Mentions.Kind.WEBAPI, it.slug) }
        return out
    }

    data class ToolResult(val output: String, val stop: SessionStatus? = null, val sleepSeconds: Int? = null)

    /** Ferramentas do criador de UI Connectors (só em sessões abertas pelo editor de conectores). */
    /** Atestado da última definição YAML aprovada no dry-run offline, por sessão do criador. */
    internal val builderPassed = java.util.concurrent.ConcurrentHashMap<String, dev.fluwy.app.uiconnector.custom.ConnectorValidationAttestation>()

    internal data class SquadTask(val id: String, val role: String, val task: String, val target: String, val deps: List<String>, val file: String = "")

    /**
     * Chamada de arquivo incompleta (resposta cortada pelo limite de saída do modelo): NÃO executa. Antes, file_write sem
     * content gravava o arquivo VAZIO (ex.: Footer.jsx sem export default derrubou a página) e "{}" repetia em loop.
     */
    internal val SQUAD_FATAL_CONSOLE = Regex("Uncaught|SyntaxError|does not provide an export named|Failed to resolve import|Failed to fetch dynamically imported module|is not defined")

    /**
     * Tarefa avulsa com teto de passos e de tokens (nó Agente dos fluxos). Antes o nó usava um chat comum sem limite:
     * uma "Notícias do dia" chegou a 28 chamadas e 680 mil tokens navegando no Google Notícias.
     */
    suspend fun runBudgeted(sessionId: String, text: String, maxSteps: Int, budgetTokens: Long) {
        store.addItem(sessionId, ChatItem.User(text = text))
        store.editSession(sessionId) { it.copy(status = SessionStatus.RUNNING, llm = it.llm + LlmMessage("user", text)) }
        loop(sessionId, maxStepsOverride = maxSteps, tokenBudgetOverride = budgetTokens)
    }

    /** Pasta do projeto que o exército deste chat está construindo (caminho do sandbox). */
    fun projectDirOf(sessionId: String): String? = squadProjectDir[sessionId] ?: store.session(sessionId)?.squadProject?.also { squadProjectDir[sessionId] = it }

    /** Schemas de TODAS as ferramentas (como o modelo as recebe), para o arquivo "Dados" das Configurações. */
    fun toolSchemasForExport(): String = tools(null, allowMultiAgent = true).toString()

    internal val SQUAD_WORKER_TOOLS = setOf("file_read", "file_write", "file_edit", "file_list", "file_search", "shell", "web_search", "web_fetch",
        "browser_navigate", "browser_read", "browser_snapshot", "browser_scroll", "browser_wait_for", "browser_console", "browser_js",
        "update_plan", "notes_write", "finish", "complete_step", "tools_load")

    /** Chat excluído: derruba o servidor de desenvolvimento do exército e esquece o estado dele. */
    fun releaseSession(sessionId: String) {
        squadDevServers.remove(sessionId)?.let { (port, proc) ->
            runCatching { proc.destroy() }
            runCatching { kotlinx.coroutines.runBlocking { killPort(port, sessionId) } }
        }
        squadProjectDir.remove(sessionId); squadApi.remove(sessionId); squadPreviewFails.remove(sessionId)
    }

    /** Servidores de desenvolvimento do exército (um por chat), que continuam no ar depois do time terminar. */
    internal val squadDevServers = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Process>>()
    /** Falhas seguidas de preview por chat: (quantas, assinatura da falha). */
    internal val squadPreviewFails = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, String>>()
    /** Chats cujo preview é uma API (sem página para desenhar). */
    internal val squadApi = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    /** Pasta do projeto (caminho do sandbox) que o time está construindo, por chat. */
    internal val squadProjectDir = java.util.concurrent.ConcurrentHashMap<String, String>()

    internal data class AgentTask(
        val id: String,
        val task: String,
        val dependsOn: List<String>
    )

    /**
     * Compact worker-output contract, used by the orchestrator when summarizing parallel work.
     * Structured reports reduce ambiguity between model completion and verified repository changes.
     */
    internal data class WorkerOutcome(
        val sessionId: String,
        val task: String,
        val status: SessionStatus?,
        val summary: String,
        val errors: List<String>,
        val tokenUsage: Long
    ) {
        val completed: Boolean get() = status == SessionStatus.DONE && errors.isEmpty()
    }

    fun workspace(sessionId: String): File = sandbox.workspaceFor(sessionId)
}
