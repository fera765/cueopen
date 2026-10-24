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
    private val browser: BrowserHost,
    private val llm: OpenRouter,
    private val terminals: dev.cueopen.app.sandbox.TerminalManager,
    private val ctx: android.content.Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableMapOf<String, Job>()
    private val _activity = MutableStateFlow<Map<String, Activity>>(emptyMap())
    val activity: StateFlow<Map<String, Activity>> = _activity
    /** Saída bruta dos comandos do agente por sessão (aba "Terminal do agente"). */
    private val _termLog = MutableStateFlow<Map<String, String>>(emptyMap())
    val termLog: StateFlow<Map<String, String>> = _termLog
    private val json = Json { ignoreUnknownKeys = true }

    fun isRunning(sessionId: String) = jobs[sessionId]?.isActive == true

    fun stop(sessionId: String) {
        jobs.remove(sessionId)?.cancel()
        store.editSession(sessionId) { it.copy(status = SessionStatus.STOPPED, wakeAt = null) }
        setActivity(sessionId, Activity())
        browser.setAgentControl(false)
    }

    /** Envia uma mensagem do usuário e (re)inicia o loop. */
    fun send(sessionId: String, text: String, attachments: List<String> = emptyList(), onDone: (() -> Unit)? = null): Job {
        jobs[sessionId]?.cancel()
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
                setActivity(sessionId, Activity())
                browser.setAgentControl(false)
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

    private suspend fun loop(sessionId: String) {
        val maxSteps = store.settings.maxSteps
        var steps = 0
        while (scope.isActive) {
            var s = store.session(sessionId) ?: return
            val agent = store.agent(s.agentId)
            setActivity(sessionId, Activity(Activity.Kind.THINKING, "Pensando"))
            // Camada 2: compacta o histórico se passou do orçamento
            if (s.autoCompact && ctxMgr.estimateTokens(s.llm) + ctxMgr.estimateTokens(s.summary) >= store.settings.contextBudget) {
                setActivity(sessionId, Activity(Activity.Kind.THINKING, "Compactando contexto"))
                s = ctxMgr.compactIfNeeded(s)
            }
            val sys = LlmMessage("system", systemPrompt(sessionId, agent, s))
            var history = ctxMgr.microCompact(s.llm)
            if (history.firstOrNull()?.role != "user") history = listOf(LlmMessage("user", "[Contexto anterior compactado no resumo da sessão. Continue de onde parou.]")) + history
            val reply = llm.chat(listOf(sys) + history, tools = tools(agent), model = agent?.model)
            store.editSession(sessionId) {
                it.copy(llm = it.llm + LlmMessage("assistant", reply.content, toolCallsJson = reply.rawToolCallsJson),
                    tokensIn = it.tokensIn + reply.usage.prompt, tokensOut = it.tokensOut + reply.usage.completion,
                    tokensCached = it.tokensCached + reply.usage.cached, llmCalls = it.llmCalls + 1)
            }
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
                store.session(sessionId)?.let { done -> scope.launch { ctxMgr.extractMemories(done) } }
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
                if (finalStatus == SessionStatus.DONE) store.session(sessionId)?.let { done -> scope.launch { ctxMgr.extractMemories(done) } }
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

    /** Agenda o loop de heartbeats (um tick por minuto). Mantido vivo pelo SandboxService + AutonomyWorker. */
    fun startAutonomy() {
        if (autonomyJob?.isActive == true) return
        if (store.state.value.agents.any { it.autonomous }) dev.cueopen.app.sandbox.SandboxService.start(ctx, "Agentes autônomos ativos")
        autonomyJob = scope.launch {
            while (isActive) {
                runCatching { autonomyTick() }
                kotlinx.coroutines.delay(60_000)
            }
        }
    }

    private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun autonomyTick() {
        val now = System.currentTimeMillis()
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        store.state.value.agents.filter { it.autonomous }.forEach { a ->
            val inHours = if (a.activeStartHour <= a.activeEndHour) hour in a.activeStartHour until a.activeEndHour
                          else hour >= a.activeStartHour || hour < a.activeEndHour
            val spent = if (a.tokensDay == today()) a.tokensToday else 0
            val due = now >= (a.lastBeatAt ?: 0) + a.heartbeatMinutes * 60_000L
            val home = a.homeSessionId
            if (inHours && due && spent < a.dailyTokenBudget && (home == null || jobs[home]?.isActive != true)) heartbeat(a)
        }
    }

    /** Dispara um heartbeat agora (também usado pelo botão "Rodar agora"). */
    fun heartbeat(agent: AgentProfile): Job {
        var a = agent
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
                    val last = (after.items.lastOrNull { it is ChatItem.Assistant } as? ChatItem.Assistant)?.text?.trim().orEmpty()
                    val quiet = last.equals("NO_REPLY", true) || last.endsWith("NO_REPLY") && after.items.size - itemsMark <= 2
                    val used = (after.tokensIn + after.tokensOut) - tokMark
                    if (quiet) {
                        // nada mudou: descarta o turno inteiro (contexto não cresce)
                        store.editSession(sid) { it.copy(llm = it.llm.take(llmMark), items = it.items.take(itemsMark), status = SessionStatus.IDLE) }
                    } else {
                        store.editSession(sid) { it.copy(status = SessionStatus.IDLE) }
                        if (a.notifyOnAction) notifyAgent(a, last.take(200))
                    }
                    store.agent(a.id)?.let { cur ->
                        val day = today()
                        store.upsertAgent(cur.copy(beats = cur.beats + 1, actions = cur.actions + if (quiet) 0 else 1,
                            tokensDay = day, tokensToday = (if (cur.tokensDay == day) cur.tokensToday else 0) + used))
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

    private fun notifyAgent(a: AgentProfile, text: String) = runCatching {
        val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
        val pi = android.app.PendingIntent.getActivity(ctx, a.id.hashCode(),
            android.content.Intent(ctx, dev.cueopen.app.MainActivity::class.java).putExtra("session", a.homeSessionId),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(a.id.hashCode(), android.app.Notification.Builder(ctx, dev.cueopen.app.CueApp.CH_TASKS)
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(a.name).setContentText(text.ifBlank { "Concluiu uma ação" })
            .setStyle(android.app.Notification.BigTextStyle().bigText(text)).setContentIntent(pi).setAutoCancel(true).build())
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

    fun connectorTools(): List<JsonObject> = dev.cueopen.app.connectors.Connectors.toolDefinitions(store)

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
            appendLine("3b. Arquivos: use file_write (nunca heredoc gigante no shell). Arquivos grandes em partes com append=true. Depois confira com `ls -la` ou file_read.")
            appendLine("3c. Ao gerar imagem/SVG/HTML/PDF, mostre ao usuário com `show_file`.")
            appendLine("3d. Com plano ativo, chame `complete_step` ao concluir CADA fase, antes de seguir para a próxima.")
            appendLine("4. Anote em `notes_write` o que precisa lembrar durante a tarefa (achados, progresso, próximos passos).")
            appendLine("5. Fatos duradouros sobre o usuário/projeto: `memory_save`. Para lembrar algo antigo: `memory_search`.")
            appendLine("6. Para usar uma skill, chame `skill_load` com o nome e siga as instruções.")
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
                appendLine("5) se nada mudou ou nada precisa ser feito, responda exatamente NO_REPLY (sem ferramentas) — isso economiza tokens.")
                appendLine("Seja econômico: evite repetir pesquisas já anotadas. Nunca chame sleep nem finish no modo autônomo; apenas responda ao terminar.")
            }
            if (session.continuous) { appendLine(); appendLine("## Estado: MODO CONTÍNUO ATIVO — continue o ciclo trabalho → notes_write → sleep.") }
        }
    }

    private fun tools(agent: AgentProfile?) = buildJsonArray {
        val term = agent?.useTerminal != false
        val web = agent?.useBrowser != false
        add(tool("update_plan", "Cria/atualiza o plano da tarefa (fases).", buildJsonObject {
            put("goal", str("Objetivo em uma frase"))
            put("phases", strArray("Fases em ordem"))
            put("done_count", int("Quantas fases já foram concluídas"))
        }, listOf("goal", "phases")))
        if (term) {
            add(tool("shell", "Executa um comando bash no sandbox Linux (Alpine). Retorna stdout+stderr.", buildJsonObject {
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
            add(tool("browser_read", "Lê o texto da página atual e lista elementos interativos numerados.", buildJsonObject {}))
            add(tool("browser_click", "Clica no elemento [índice] de browser_read.", buildJsonObject { put("index", int("Índice")) }, listOf("index")))
            add(tool("browser_type", "Digita num campo [índice]; submit=true envia.", buildJsonObject {
                put("index", int("Índice")); put("text", str("Texto")); put("submit", bool("Enviar formulário"))
            }, listOf("index", "text")))
            add(tool("browser_scroll", "Rola a página.", buildJsonObject { put("direction", str("up ou down")) }))
            add(tool("browser_back", "Volta uma página.", buildJsonObject {}))
            add(tool("browser_js", "Executa JavaScript na página e retorna o resultado.", buildJsonObject { put("code", str("Código JS (expressão)")) }, listOf("code")))
        }
        if (web) {
            add(tool("browser_network", "Lista as requisições de rede recentes da página (URL, método, tipo, status, tempo).", buildJsonObject { put("filter", str("Filtro opcional por texto na URL")) }))
            add(tool("browser_console", "Mostra as mensagens recentes do console JavaScript da página.", buildJsonObject {}))
        }
        add(tool("notes_write", "Escreve no NOTES.md da tarefa (memória de trabalho que sempre volta ao seu contexto).", buildJsonObject {
            put("content", str("Texto")); put("append", bool("true = acrescenta; false = substitui tudo"))
        }, listOf("content")))
        add(tool("memory_save", "Salva um fato duradouro na memória de longo prazo.", buildJsonObject {
            put("fact", str("Fato")); put("scope", str("global ou agent")); put("tags", strArray("Palavras-chave"))
        }, listOf("fact")))
        add(tool("memory_search", "Busca na memória de longo prazo.", buildJsonObject { put("query", str("Busca")) }, listOf("query")))
        add(tool("skill_load", "Carrega as instruções completas de uma skill.", buildJsonObject { put("name", str("Nome da skill")) }, listOf("name")))
        add(tool("sleep", "Modo contínuo: pausa por N minutos e depois retoma automaticamente (monitoramento).", buildJsonObject {
            put("minutes", int("Minutos até a próxima verificação (1-1440)")); put("reason", str("O que fazer ao acordar"))
        }, listOf("minutes", "reason")))
        add(tool("create_skill", "Cria uma nova skill na biblioteca do usuário.", buildJsonObject {
            put("name", str("Nome")); put("description", str("Descrição curta")); put("instructions", str("Instruções detalhadas"))
        }, listOf("name", "description", "instructions")))
        connectorTools().forEach { add(it) }
        add(tool("ask_user", "Faz uma pergunta ao usuário e pausa a tarefa até ele responder.", buildJsonObject { put("question", str("Pergunta")) }, listOf("question")))
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
            "browser_navigate" -> "Navegando" to Activity.Kind.BROWSER
            "browser_read" -> "Lendo página" to Activity.Kind.BROWSER
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
        store.addItem(sessionId, step)
        setActivity(sessionId, Activity(kind, argPreview.take(80)))
        if (kind == Activity.Kind.BROWSER) browser.setAgentControl(true)

        val out: String = try {
            when (call.name) {
                "shell" -> {
                    // tudo roda DENTRO do terminal da tarefa (visível ao vivo no Computador)
                    val r = terminals.get(sessionId, cwd).runAgentCommand(s("command"), (i("timeout") ?: 180).toLong(), b("background"))
                    run {
                        buildString {
                            append(r.output.takeLast(10_000))
                            if (r.timedOut) append("\n[timeout]")
                            append("\n[exit ${r.exitCode}]")
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
                                "OK: ${if (append) "acrescentado em" else "salvo"} $path — total ${f.length()} bytes, $lines linhas."
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
                    browser.navigate(url); browser.read(4000)
                }
                "browser_read" -> browser.read()
                "browser_click" -> browser.click(i("index") ?: -1)
                "browser_type" -> browser.type(i("index") ?: -1, s("text"), b("submit"))
                "browser_scroll" -> browser.scroll(s("direction") != "up")
                "browser_back" -> { browser.back(); "ok" }
                "browser_js" -> browser.runJs(s("code"))
                "browser_network" -> browser.networkSummary(s("filter"))
                "browser_console" -> browser.consoleSummary()
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
            throw c
        } catch (t: Throwable) {
            store.replaceItem(sessionId, step.copy(result = t.message.orEmpty(), status = StepStatus.ERROR))
            return ToolResult("Erro: ${t.message}")
        }
        store.replaceItem(sessionId, step.copy(result = out.take(4000), status = StepStatus.OK))
        return ToolResult(out)
    }

    private fun shQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"

    private fun appendTerm(sessionId: String, text: String) {
        val m = _termLog.value
        _termLog.value = m + (sessionId to ((m[sessionId] ?: "") + text).takeLast(60_000))
    }

    private fun setActivity(sessionId: String, a: Activity) {
        _activity.value = _activity.value + (sessionId to a)
    }

    fun workspace(sessionId: String): File = sandbox.workspaceFor(sessionId)
}
