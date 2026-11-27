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
    internal fun scheduleLabel(m: Int): String = when {
        m % 1440 == 0 -> if (m == 1440) "uma vez por dia" else "a cada ${m / 1440} dias"
        m % 60 == 0 -> if (m == 60) "a cada hora" else "a cada ${m / 60} horas"
        else -> "a cada $m min"
    }
    internal val sectionColors = listOf(0xFFFF6B3DL, 0xFF4D8DFFL, 0xFF2FBF71L, 0xFFFFC53DL, 0xFFB57BFFL, 0xFFFF5C8AL, 0xFF2EC4C4L, 0xFF9C978EL)

    /** Agente da biblioteca pelo id ou pelo nome (sem acento/caixa). Coordenadores de outras seções não entram. */
    internal fun libraryAgent(key: String): AgentProfile? {
        if (key.isBlank()) return null
        fun norm(x: String) = java.text.Normalizer.normalize(x, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
        val k = norm(key)
        val pool = store.state.value.agents.filterNot { it.name.startsWith("Coordenador ·") }
        return pool.firstOrNull { it.id == key } ?: pool.firstOrNull { norm(it.name) == k }
    }

    /** Lista do time no texto do coordenador (refeita quando entra alguém novo). */
    internal fun rosterOf(sec: Section): String = sec.agentIds.mapNotNull { store.agent(it) }
        .joinToString("\n") { "- ${it.name}: ${it.mission.ifBlank { it.instructions.lineSequence().drop(1).firstOrNull().orEmpty() }}" }

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

    internal fun createSection(
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
     * Zera o estado de execução de um chat: para o agente, fecha o terminal e apaga contexto do modelo,
     * histórico, resumo, contadores, visitas do browser, cofre e pendências de supervisão.
     */
    internal suspend fun wipeRuntime(sessionId: String) {
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
    internal fun wipeWorkspace(sessionId: String) {
        if (sandbox.isConnectorSession(sessionId)) return
        runCatching { sandbox.deleteEnvironment(sessionId) }
        runCatching { sandbox.workspaceFor(sessionId).deleteRecursively() }
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

    /** Zera a seção inteira: todos os chats do time, workspace compartilhado, terminais, cofre e delegações. */
    internal suspend fun wipeSection(sec: Section) {
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

    internal fun memberSession(sec: Section, member: AgentProfile): String =
        store.state.value.sessions.firstOrNull { it.sectionId == sec.id && it.agentId == member.id && it.id != sec.sessionId }?.id
            ?: Session(title = "${member.name} · ${sec.name}", agentId = member.id, sectionId = sec.id).also { store.upsertSession(it) }.id

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

    internal suspend fun teamDelegate(sessionId: String, agentName: String, task: String, wait: Boolean, timeoutMinutes: Int,
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
    internal fun startSection(sec: Section) {
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

    internal val autoNudges = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Int>>()
    /** Seções autônomas cujo coordenador declarou o objetivo inteiro entregue (section_done). */
    internal val autoDone = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Fato verificado pelo app para o coordenador: o que mudou no workspace durante a tarefa delegada. */
    internal fun workspaceChanges(sessionId: String, since: Long): String = runCatching {
        val root = sandbox.workspaceFor(sessionId)
        val files = root.walkTopDown().maxDepth(3).onEnter { !it.name.startsWith(".") && it.name != "node_modules" }
            .filter { it.isFile && it.lastModified() >= since - 2_000 }.take(300).toList().sortedByDescending { it.lastModified() }.take(15)
        if (files.isEmpty()) "\n[Verificado pelo app] Nenhum arquivo foi criado ou alterado em /root/workspace durante esta tarefa. Se a etapa exigia um arquivo, use team_review redo pedindo que ele seja salvo."
        else "\n[Verificado pelo app] Arquivos criados ou alterados em /root/workspace nesta tarefa: " + files.joinToString { it.relativeTo(root).path + " (" + it.length() + " B)" }
    }.getOrDefault("")

    /** true se uma das últimas respostas do chat já traz praticamente o mesmo relatório (evita feedback final em dobro). */
    internal fun alreadyReported(sessionId: String, text: String): Boolean {
        fun words(t: String) = t.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 3 }.toSet()
        val mine = words(text)
        if (mine.size < 12) return false
        return store.session(sessionId)?.items.orEmpty().takeLast(8).filterIsInstance<ChatItem.Assistant>().any { prev ->
            val theirs = words(prev.text)
            theirs.size >= 12 && mine.intersect(theirs).size.toDouble() / minOf(mine.size, theirs.size) >= 0.7
        }
    }

    // ───────── Desfazer (/undo): estado anterior de cada arquivo que o agente alterou ─────────
    internal class FileChange(val file: java.io.File, val previous: String?, val shown: String)
    internal val fileJournal = java.util.concurrent.ConcurrentHashMap<String, java.util.ArrayDeque<FileChange>>()
    /** Conteúdo atual do arquivo para poder desfazer depois; `false` em first = grande demais/binário, não entra no diário. */
    internal fun beforeChange(f: java.io.File): Pair<Boolean, String?> = runCatching {
        when {
            !f.exists() -> true to null
            f.length() > 1_000_000 -> false to null
            else -> true to f.readText()
        }
    }.getOrDefault(false to null)
    internal fun recordChange(sessionId: String, f: java.io.File, before: Pair<Boolean, String?>, shown: String) {
        if (!before.first) return
        val q = fileJournal.getOrPut(sessionId) { java.util.ArrayDeque() }
        synchronized(q) { q.addLast(FileChange(f, before.second, shown)); while (q.size > 40) q.removeFirst() }
    }

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
    internal fun fileKey(sessionId: String, f: java.io.File) = sessionId + "|" + runCatching { f.canonicalPath }.getOrDefault(f.absolutePath)
    internal fun noteFileSeen(sessionId: String, f: java.io.File) {
        val key = fileKey(sessionId, f)
        editFails.remove(key)
        fileSeen[key] = runCatching { f.lastModified() }.getOrDefault(0L)
        if (fileSeen.size > 4_000) fileSeen.keys.take(1_000).forEach { fileSeen.remove(it) }
    }

    // ───────── Workers em paralelo: snapshot do projeto e integração de volta ─────────
    internal val HEAVY_DIRS = setOf("node_modules", ".gradle", "__pycache__", ".venv", "venv", ".next", ".turbo", ".cache")
    internal fun fileDigest(f: java.io.File): String = runCatching {
        val md = java.security.MessageDigest.getInstance("SHA-1")
        f.inputStream().use { ins -> val buf = ByteArray(65_536); while (true) { val n = ins.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrDefault("?" + f.length())
    internal fun projectFiles(root: java.io.File): Sequence<java.io.File> =
        root.walkTopDown().onEnter { it == root || (it.name != ".git" && it.name != ".fluwy" && it.name !in HEAVY_DIRS) }
            .filter { it.isFile && !Regex("\\.worker-\\d+$").containsMatchIn(it.name) }
    /** Retrato do projeto no momento em que os workers partem: caminho relativo → digest do conteúdo. */
    internal fun projectSnapshot(root: java.io.File): Map<String, String> =
        runCatching { projectFiles(root).associate { it.relativeTo(root).path to fileDigest(it) } }.getOrDefault(emptyMap())

    internal data class WorkerMerge(val applied: List<String>, val deleted: List<String>, val conflicts: List<String>)
    /**
     * "Push" de um worker para o projeto principal, arquivo a arquivo, comparando três versões: a base (snapshot de
     * partida), a do worker e a atual do principal. Só o worker mudou → aplica. Os dois mudaram de formas diferentes →
     * conflito: o principal fica como está e a versão do worker é salva ao lado como <arquivo>.worker-N.
     */
    internal fun integrateWorker(base: Map<String, String>, source: java.io.File, dest: java.io.File, n: Int): WorkerMerge {
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

    internal val peerMessages = java.util.concurrent.ConcurrentHashMap<String, Int>()
    /** Mensagem direta entre agentes do time (ou para o coordenador), sem passar por uma nova delegação. */
    internal fun teamPeerMessage(sessionId: String, to: String, message: String): String {
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
    internal fun coordinatedSection(sessionId: String): Section? = store.state.value.sections.firstOrNull { it.sessionId == sessionId && it.configured }

    /** Nenhum agente do time trabalhando, nenhuma delegação aberta e nenhuma etapa em execução: esperar não adianta. */
    internal fun teamIdle(sec: Section): Boolean =
        store.state.value.sessions.none { it.sectionId == sec.id && it.id != sec.sessionId && isRunning(it.id) } &&
            delegations.values.none { it.sectionId == sec.id } &&
            store.section(sec.id)?.plan.orEmpty().none { it.status == TaskStatus.RUNNING }

    /** O próprio usuário pediu um intervalo ("a cada 30 min…") neste chat. */
    internal fun userAskedInterval(sessionId: String): Boolean =
        store.session(sessionId)?.llm.orEmpty().filter { it.role == "user" && it.content?.startsWith("[") != true }
            .takeLast(12).any { SectionWaitPolicy.userAskedInterval(it.content.orEmpty()) }

    /**
     * Espera que o app pode encerrar sozinho: é do coordenador de uma seção e serve para aguardar o time — o motivo
     * fala em aguardar/concluir/entrega, ou o usuário não pediu nenhum intervalo. Ciclos pedidos pelo usuário são respeitados.
     */
    internal fun cuttableWait(sessionId: String, reason: String): Section? {
        val sec = coordinatedSection(sessionId) ?: return null
        if (reason.contains("[[IQOPTION_TRADES", true)) return null
        return sec.takeIf { SectionWaitPolicy.waitsForTeam(reason) || !userAskedInterval(sessionId) }
    }

    /** Espera em pedaços curtos; se for uma espera pelo time e o time ficou ocioso, encerra antes do prazo. Retorna true se encerrou antes. */
    internal suspend fun waitUntil(sessionId: String, wake: Long, reason: String): Boolean {
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

    internal fun noteEarlyWake(sessionId: String) {
        store.editSession(sessionId) { it.copy(wakeAt = null, llm = it.llm + LlmMessage("user",
            dev.fluwy.app.prompts.Prompts.render("runner.22", mapOf<String, Any?>("lb" to "{{")))) }
    }

    internal fun sectionAuto(sec: Section): Boolean = store.agent(sec.coordinatorAgentId)?.confirmMode == "auto"
    /** Chat de seção (coordenador ou agente) configurada como 100% autônoma. */
    internal fun autoSectionSession(sessionId: String): Boolean =
        store.session(sessionId)?.let { s -> s.sectionId != null && store.agent(s.agentId)?.confirmMode == "auto" } == true

    /**
     * Seção autônoma: o coordenador parou (feedback, pergunta ou fim de um plano) com o time ocioso e sem declarar
     * o objetivo concluído → ordem oculta para seguir sozinho. No máximo [MAX_AUTO_NUDGES] vezes sem o plano mudar.
     */
    internal fun autoContinue(secIn: Section): String? {
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


    internal val scriptPolicies = java.util.concurrent.ConcurrentHashMap<String, ScriptBuilderPolicy>()
    internal fun scriptPolicy(sessionId: String) = scriptPolicies.getOrPut(sessionId) { ScriptBuilderPolicy() }

    // ───────────────────────── Plano persistido da seção (continuidade automática) ─────────────────────────

    internal val planLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()
    internal val planNudges = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Int>>()
    internal val finalReportAsked = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Leitura-modificação-escrita atômica do plano (vários agentes terminam ao mesmo tempo). */
    internal fun editPlan(sectionId: String, fn: (List<SectionTask>) -> List<SectionTask>) {
        synchronized(planLocks.getOrPut(sectionId) { Any() }) {
            val sec = store.section(sectionId) ?: return
            val next = fn(sec.plan)
            if (next != sec.plan) store.upsertSection(sec.copy(plan = next))
        }
        store.section(sectionId)?.let { syncPlanItem(it) }
    }

    /** Espelha o plano no chat do coordenador (cartão de progresso acima do campo de mensagem): estados reais. */
    internal fun syncPlanItem(sec: Section) {
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
    internal suspend fun planDispatch(sectionId: String): List<String> {
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
    internal fun planWatchdog(sectionId: String, afterRestart: Boolean = false) {
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
    internal fun resumeOrphanTask(sec: Section, t: SectionTask, member: AgentProfile, childId: String) {
        val before = store.session(childId)?.items?.size ?: 0
        val pill = ChatItem.Step(tool = TEAM_MEMBER_STEP, title = "Retomando ${t.id}: " + t.task.lineSequence().first().take(100),
            args = "$childId|${member.id}", result = "", status = StepStatus.RUNNING)
        store.addItem(sec.sessionId, pill)
        delegations[childId] = Delegation(sec.id, member.id, pill.id, before, t.task, taskId = t.id)
        send(childId, dev.fluwy.app.prompts.Prompts.render("runner.6", mapOf<String, Any?>("valor" to (t.id), "lb" to "{{")) +
            dev.fluwy.app.prompts.Prompts.render("runner.7", mapOf<String, Any?>("lb" to "{{")), showUserMessage = false)
    }

    /** Na abertura do app: retoma planos que estavam em andamento. */
    fun recoverSections() {
        store.state.value.sections.filter { it.configured && it.plan.isNotEmpty() &&
            SectionPlanEngine.state(it.plan) != SectionPlanEngine.State.COMPLETED }.forEach { planWatchdog(it.id, afterRestart = true) }
    }

    internal suspend fun teamPlan(sessionId: String, goal: String, tasksJson: String): String {
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

    internal suspend fun teamReview(sessionId: String, taskId: String, verdictRaw: String, note: String): String {
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

    internal fun memberReport(member: AgentProfile, childId: String, before: Int): String {
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
    internal fun onRunEnded(sessionId: String) {
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
    internal fun sectionSharedContext(sec: Section, sessionId: String, coordinator: Boolean): String = runCatching {
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

    /** Orientações do coordenador aguardando o próximo ponto seguro do loop do agente. */
    internal val coordinatorInbox = java.util.concurrent.ConcurrentHashMap<String, MutableList<String>>()
    internal val lastCheckpointStep = java.util.concurrent.ConcurrentHashMap<String, Int>()

    internal fun resolveMember(sec: Section, name: String): AgentProfile? {
        val team = sec.agentIds.mapNotNull { store.agent(it) }
        val w = name.trim()
        return team.firstOrNull { it.name.equals(w, true) } ?: team.firstOrNull { it.name.contains(w, true) || w.contains(it.name, true) }
    }

    /**
     * Checkpoint para o coordenador: a cada [MemberSupervision.CHECKPOINT_EVERY_STEPS] passos, ou na hora em que
     * aparece um sinal de desvio (falhas seguidas, ação repetida, varredura do sistema). O coordenador decide:
     * deixar seguir (feedback curto), corrigir (team_message) ou parar (team_stop).
     */
    internal fun memberCheckpoint(sessionId: String, steps: Int) {
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

    internal fun teamMessage(sessionId: String, agentName: String, message: String): String {
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
    internal suspend fun teamReassign(sessionId: String, taskId: String, agentName: String, note: String): String {
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

    internal fun teamStop(sessionId: String, agentName: String, reason: String): String {
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

    internal fun teamStatus(sessionId: String): String {
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

    /** Troca {{secret:nome}} pelos valores do cofre em todos os textos de um objeto de argumentos. */
    internal fun resolveArgs(sessionId: String, o: JsonObject): JsonObject = JsonObject(o.mapValues { (_, v) ->
        val p = v as? kotlinx.serialization.json.JsonPrimitive
        if (p != null && p.isString && p.content.contains("secret:")) kotlinx.serialization.json.JsonPrimitive(SecretVault.resolve(sessionId, p.content))
        else if (v is JsonObject) resolveArgs(sessionId, v) else v
    })

    internal fun maybeTitle(sessionId: String, text: String) {
        val s = store.session(sessionId) ?: return
        if (s.title != "Nova tarefa") return
        // Título montado do próprio pedido: sem chamada extra ao modelo.
        store.editSession(sessionId) { it.copy(title = TaskTitle.make(text)) }
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

    /**
     * Executes an already-AUTHORIZED autonomous transfer end to end: local signing, broadcast,
     * and one reconciliation pass. The persisted policy (limits + mandatory allowlist) is
     * re-validated inside the signer/broadcaster, so this never bypasses the local safety gate.
     * Returns a human-readable, honest status — never claims success the network did not confirm.
     */
    internal suspend fun runAutonomousTransfer(
        intent: dev.fluwy.app.wallet.WalletTransferIntent,
        policy: dev.fluwy.app.wallet.WalletAgentPolicy,
        ledger: dev.fluwy.app.wallet.WalletRequestLedger,
        wallet: dev.fluwy.app.wallet.SolanaWalletRepository,
        network: dev.fluwy.app.wallet.SolanaNetwork,
        agentId: String,
        windowStart: Long,
        windowEnd: Long,
        nowMs: Long
    ): String {
        val rpc = dev.fluwy.app.wallet.SolanaRpcClient(endpoint = network.rpcEndpoint, network = network)
        val executor = dev.fluwy.app.wallet.SolanaTransferExecutor(ctx.applicationContext, wallet, ledger, rpc)
        val amount = String.format(Locale.US, "%.9f SOL", intent.amountLamports / 1_000_000_000.0)
        when (val signed = executor.signAuthorizedTransfer(
            intent.requestId, policy, windowStart, windowEnd, humanApproval = false, signingTimeEpochMs = nowMs
        )) {
            is dev.fluwy.app.wallet.TransferExecutionResult.Signed -> Unit
            is dev.fluwy.app.wallet.TransferExecutionResult.AwaitingApproval ->
                return "Transferência automática não autorizada pela política (ainda exige aprovação manual). Verifique allowlist/limites na tela Carteira. ID: ${intent.requestId}"
            is dev.fluwy.app.wallet.TransferExecutionResult.Rejected ->
                return "Transferência automática recusada ao assinar: ${signed.reason}. ID: ${intent.requestId}"
            else ->
                return "Transferência automática não pôde ser assinada. ID: ${intent.requestId}"
        }
        return when (val broadcast = executor.broadcastPersistedTransfer(
            intent.requestId, agentId, policy, windowStart, windowEnd, humanConfirmed = true
        )) {
            is dev.fluwy.app.wallet.TransferExecutionResult.Submitted -> {
                val link = network.transactionExplorerUrl(broadcast.signature)
                val rec = executor.reconcileSignature(intent.requestId, agentId)
                if (rec is dev.fluwy.app.wallet.TransferExecutionResult.Confirmed)
                    "Transferência automática CONFIRMADA on-chain: $amount para ${intent.recipientAddress} na ${network.displayName}. Assinatura: ${broadcast.signature}. Explorer: $link"
                else
                    "Transferência automática ENVIADA à ${network.displayName}: $amount para ${intent.recipientAddress}. Assinatura: ${broadcast.signature}. Ainda confirmando; acompanhe no Explorer: $link"
            }
            is dev.fluwy.app.wallet.TransferExecutionResult.Unknown ->
                "Transferência automática em estado AMBÍGUO após o envio; não será reenviada automaticamente. Assinatura: ${broadcast.signature}. Reconcilie na tela Carteira. Motivo: ${broadcast.reason}"
            is dev.fluwy.app.wallet.TransferExecutionResult.Rejected ->
                "Transferência automática assinada, mas o envio foi recusado: ${broadcast.reason}. ID: ${intent.requestId}"
            is dev.fluwy.app.wallet.TransferExecutionResult.ChainFailed ->
                "Transferência automática FALHOU on-chain: ${broadcast.reason}. Assinatura: ${broadcast.signature}"
            else ->
                "Transferência automática não concluída. ID: ${intent.requestId}"
        }
    }

    /** Classifica a origem da falha pelo nome da ferramenta, para o log de erros. */
    internal fun errorSource(toolName: String): String = when {
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

    /** Ferramentas do criador de UI Connectors (só em sessões abertas pelo editor de conectores). */
    /** Atestado da última definição YAML aprovada no dry-run offline, por sessão do criador. */
    internal val builderPassed = java.util.concurrent.ConcurrentHashMap<String, dev.fluwy.app.uiconnector.custom.ConnectorValidationAttestation>()

    internal fun yamlStringMap(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        val root = dev.fluwy.app.uiconnector.custom.ConnectorYamlCodec.parse(raw) as? JsonObject
            ?: throw IllegalArgumentException("a raiz precisa ser um mapa YAML")
        return root.mapValues { (_, value) ->
            (value as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
                ?: throw IllegalArgumentException("os valores devem ser escalares")
        }
    }

    internal suspend fun connectorTool(sessionId: String, name: String, yaml: String, argsYaml: String = "", settingsYaml: String = ""): String {
        if (store.session(sessionId)?.connectorBuilder != true)
            return "Indisponível: crie conectores pela página Conectores → Novo conector → Criar com a IA."
        if (name == "connector_spec") return dev.fluwy.app.uiconnector.custom.SdkPrompt.MARKDOWN
        return run {
                val raw = dev.fluwy.app.uiconnector.custom.CustomDefParser.stripFences(yaml)
                val parsed = dev.fluwy.app.uiconnector.custom.CustomDefParser.parse(raw)
                val hub = dev.fluwy.app.FluwyApp.instance.uiConnectors
                val d = parsed.def
                val clash = d != null && hub.get(d.id) != null && !hub.isCustom(d.id)
                if (d == null) "INVÁLIDO — ${parsed.errors.size} erro(s). Corrija TODOS e chame connector_validate de novo (consulte connector_spec se preciso):\n" +
                        parsed.errors.joinToString("\n") { "- $it" } +
                        (if (parsed.warnings.isNotEmpty()) "\nAvisos:\n" + parsed.warnings.joinToString("\n") { "- $it" } else "")
                else when {
                    clash -> "INVÁLIDO: o id '${d.id}' pertence a um conector do app. Troque o id."
                    name == "connector_test" -> {
                        val toolArgs = try {
                            if (argsYaml.isBlank()) emptyMap() else {
                                val root = dev.fluwy.app.uiconnector.custom.ConnectorYamlCodec.parse(argsYaml) as? JsonObject
                                    ?: throw IllegalArgumentException("a raiz precisa ser um mapa YAML por nome de ferramenta")
                                root.mapValues { (_, value) ->
                                    val values = value as? JsonObject ?: throw IllegalArgumentException("cada ferramenta precisa mapear argumentos em um mapa YAML")
                                    values.mapValues { (_, v) -> (v as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: v.toString() }
                                }
                            }
                        } catch (t: Exception) {
                            return "INVÁLIDO — args_yaml: ${t.message ?: "sintaxe incorreta"}. Corrija as amostras ou remova args_yaml para usar valores sintéticos."
                        }
                        val settingOverrides = try { yamlStringMap(settingsYaml) } catch (t: Exception) {
                            return "INVÁLIDO — settings_yaml: ${t.message ?: "sintaxe incorreta"}. Corrija o mapa ou remova settings_yaml."
                        }
                        val result = runCatching {
                            dev.fluwy.app.uiconnector.custom.ConnectorDryRunValidator.validate(raw, d, toolArgs, settingOverrides)
                        }.getOrElse { dev.fluwy.app.uiconnector.custom.ConnectorDryRunValidator.Result(false, "✗ Falha no dry-run: ${it.message ?: it.javaClass.simpleName}", null) }
                        val passed = result.passed && result.attestation != null
                        if (passed) builderPassed[sessionId] = result.attestation!! else builderPassed.remove(sessionId)
                        (if (passed) "PASSOU — dry-run local offline concluído; nenhuma chamada externa nem código gerado foi executado.\n"
                         else "FALHOU — o conector NÃO pode ser proposto assim. Corrija os erros no YAML e rode connector_test com a definição corrigida; não repita a mesma chamada após o mesmo erro:\n") +
                            result.report.take(7000) +
                            (if (passed) "\nLogin, permissões e comportamento real da API serão testados somente quando o usuário conectar/autorizar o conector. Agora chame connector_propose com o MESMO YAML." else "")
                    }
                    name == "connector_propose" && !dev.fluwy.app.uiconnector.custom.ConnectorAttestationPolicy.isInstallable(raw, builderPassed[sessionId]) ->
                        "BLOQUEADO pelos guardrails: este YAML ainda não passou em connector_test. Rode connector_test com este MESMO YAML e só proponha quando PASSOU."
                    name == "connector_validate" -> "OK — definição YAML válida (host ${d.host}, ${d.tools.size} ferramenta(s), ${d.panels.size} painel(is))." +
                        (if (parsed.warnings.isNotEmpty()) "\nAvisos (não bloqueiam; corrija se fizer sentido):\n" + parsed.warnings.joinToString("\n") { "- $it" } else "") +
                        "\nAgora chame connector_test com o MESMO YAML e, opcionalmente, args_yaml/settings_yaml com amostras não secretas. O teste usa apenas mocks offline — sem credenciais reais, rede externa ou código arbitrário."
                    else -> {
                        store.addItem(sessionId, ChatItem.ConnectorProposal(
                            connectorId = d.id, name = d.name, description = d.description, host = d.host,
                            tools = d.tools.map { it.name }, yaml = raw,
                            capabilities = d.capabilities.toList().sorted(),
                            validation = builderPassed[sessionId],
                        ))
                        "Card de instalação exibido ao usuário (conector '${d.name}', id ${d.id}). Informe que passou em dry-run offline e que API/login real ainda precisam ser autorizados/testados ao conectar. Não afirme que já foi instalado."
                    }
                }
                    }
    }

    internal suspend fun proposeConnectorEdit(
        sessionId: String,
        summary: String,
        settingsJson: String,
        enabled: Boolean?,
        agentRequired: Boolean?,
        definitionYaml: String,
    ): String {
        val session = store.session(sessionId) ?: return "BLOQUEADO: conversa não encontrada."
        val targetId = session.connectorEditId ?: return "BLOQUEADO: esta conversa não está vinculada a um conector."
        val expected = session.connectorEditFingerprint?.takeIf { it.isNotBlank() }
            ?: return "BLOQUEADO: não há estado-base para esta proposta; reabra o chat pelo conector."
        val hub = dev.fluwy.app.FluwyApp.instance.uiConnectors
        val config = hub.editableConfiguration(targetId) ?: return "BLOQUEADO: o conector selecionado não está mais instalado."
        if (config.fingerprint != expected)
            return "BLOQUEADO: a configuração mudou desde que o chat foi aberto. Reabra o chat do conector para revisar o estado atual."

        val safeConnectorName = if (dev.fluwy.app.uiconnector.custom.ConnectorDefinitionEdit.containsSecretLikeValue(config.name))
            "conector selecionado" else config.name.take(160)
        val cleanSummary = summary.trim().take(240).ifBlank { "Atualizar configurações de $safeConnectorName" }
        if (dev.fluwy.app.uiconnector.custom.ConnectorDefinitionEdit.containsSecretLikeValue(cleanSummary))
            return "INVÁLIDO: o resumo parece conter uma credencial. Remova-a e não inclua segredos na conversa."

        var safeDefinition: String? = null
        var proposedDef: dev.fluwy.app.uiconnector.custom.CustomDef? = null
        if (definitionYaml.isNotBlank()) {
            if (!hub.isCustom(targetId)) return "BLOQUEADO: definições de conectores compilados pelo app não podem ser alteradas neste chat."
            val original = hub.customYaml(targetId) ?: return "BLOQUEADO: a definição customizada não está disponível."
            if (original.length > 48 * 1024) return "BLOQUEADO: a definição é grande demais para edição segura neste chat."
            val restored = try {
                dev.fluwy.app.uiconnector.custom.ConnectorDefinitionEdit.restore(original, definitionYaml, sessionId)
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (_: Exception) {
                return "INVÁLIDO: a definição proposta não passou nas validações de segurança. Revise sem incluir credenciais."
            }
            val oldTree = runCatching { dev.fluwy.app.uiconnector.custom.ConnectorYamlCodec.parse(original) }.getOrNull()
                ?: return "BLOQUEADO: não foi possível ler a definição atual."
            val newTree = runCatching { dev.fluwy.app.uiconnector.custom.ConnectorYamlCodec.parse(restored) }.getOrNull()
                ?: return "INVÁLIDO: a definição proposta não pode ser interpretada."
            if (oldTree != newTree) {
                val parsed = dev.fluwy.app.uiconnector.custom.CustomDefParser.parse(restored)
                val newDef = parsed.def ?: return "INVÁLIDO: a definição proposta não passou no schema do conector."
                if (newDef.id != targetId) return "INVÁLIDO: a proposta precisa manter o id do conector selecionado."
                val oldDef = dev.fluwy.app.uiconnector.custom.CustomDefParser.parse(original).def
                    ?: return "BLOQUEADO: a definição instalada não está válida."
                if (newDef.login != oldDef.login || newDef.headers != oldDef.headers || newDef.capabilities != oldDef.capabilities || newDef.setup != oldDef.setup)
                    return "BLOQUEADO: login, cabeçalhos, permissões e setup não podem ser alterados no chat."
                val dryRun = dev.fluwy.app.uiconnector.custom.ConnectorDryRunValidator.validate(restored, newDef)
                if (!dryRun.passed)
                    return "INVÁLIDO: a definição não passou no dry-run local com mocks. Nenhuma rede, ação real ou credencial foi usada."
                safeDefinition = runCatching {
                    dev.fluwy.app.uiconnector.custom.ConnectorDefinitionEdit.sanitize(restored, sessionId)
                }.getOrElse { return "INVÁLIDO: não foi possível preparar um preview sanitizado da definição." }
                proposedDef = newDef
            }
        }

        val parsed = runCatching { json.parseToJsonElement(settingsJson).jsonObject }
            .getOrElse { return "INVÁLIDO: settings_json precisa ser um objeto JSON. Corrija a proposta." }
        val editable = LinkedHashMap<String, Pair<String, dev.fluwy.app.uiconnector.SettingType>>()
        if (proposedDef == null) {
            config.settings.forEach { editable[it.id] = it.label to it.type }
        } else {
            val original = hub.customYaml(targetId).orEmpty()
            val originalIds = runCatching {
                dev.fluwy.app.uiconnector.custom.CustomDefParser.parse(original).def?.settings?.map { it.id }?.toSet().orEmpty()
            }.getOrDefault(emptySet())
            val currentlyEditableIds = config.settings.map { it.id }.toSet()
            proposedDef!!.settings.forEach { spec ->
                if (hub.isSecretSetting(spec) || dev.fluwy.app.uiconnector.custom.ConnectorDefinitionEdit.containsSecretLikeValue(spec.default)) return@forEach
                val safeExisting = spec.id in originalIds && spec.id in currentlyEditableIds
                val safeNew = spec.id !in originalIds
                if (safeExisting || safeNew) editable[spec.id] = spec.label to spec.type
            }
        }
        val proposed = LinkedHashMap<String, String>()
        for ((key, value) in parsed) {
            val spec = editable[key] ?: return "INVÁLIDO: '$key' não é uma configuração editável deste conector. Use apenas os IDs listados no contexto."
            val primitive = value as? kotlinx.serialization.json.JsonPrimitive
                ?: return "INVÁLIDO: o valor de '${spec.first}' deve ser um texto, booleano ou número simples."
            val text = primitive.contentOrNull ?: return "INVÁLIDO: valor inválido para '${spec.first}'."
            if (text.length > 4096) return "INVÁLIDO: o valor de '${spec.first}' é longo demais."
            if (dev.fluwy.app.uiconnector.custom.ConnectorDefinitionEdit.containsSecretLikeValue(text))
                return "INVÁLIDO: '${spec.first}' parece conter uma credencial. Não inclua nem gere segredos no chat."
            when (spec.second) {
                dev.fluwy.app.uiconnector.SettingType.SWITCH -> if (text.lowercase() !in setOf("true", "false"))
                    return "INVÁLIDO: '${spec.first}' aceita somente true ou false."
                dev.fluwy.app.uiconnector.SettingType.NUMBER -> if (text.replace(',', '.').toDoubleOrNull()?.isFinite() != true)
                    return "INVÁLIDO: '${spec.first}' precisa ser um número válido."
                dev.fluwy.app.uiconnector.SettingType.TEXT -> Unit
            }
            val currentValue = config.settings.firstOrNull { it.id == key }?.value
                ?: proposedDef?.settings?.firstOrNull { it.id == key }?.default
            if (text != currentValue) proposed[key] = text
        }
        val nextEnabled = enabled?.takeIf { it != config.enabled }
        val nextAgentRequired = agentRequired?.takeIf { it != config.agentRequired }
        if (proposed.isEmpty() && nextEnabled == null && nextAgentRequired == null && safeDefinition == null)
            return "INVÁLIDO: a proposta não contém mudanças diferentes da configuração atual."
        store.addItem(sessionId, ChatItem.ConnectorEditProposal(
            connectorId = targetId,
            connectorName = safeConnectorName,
            summary = cleanSummary,
            settings = proposed,
            enabled = nextEnabled,
            agentRequired = nextAgentRequired,
            definitionYaml = safeDefinition,
            baseFingerprint = expected,
        ))
        return "Card de atualização criado para '$safeConnectorName'. Nenhuma configuração foi aplicada ainda; aguarde a confirmação do usuário no card."
    }

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
