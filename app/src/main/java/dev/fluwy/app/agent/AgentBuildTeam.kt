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

internal suspend fun AgentRunner.runBuildTeam(sessionId: String, argsJson: String): String {
    val o = runCatching { org.json.JSONObject(argsJson) }.getOrElse { org.json.JSONObject() }
    val goal = o.optString("goal")
    val tasksJson = o.opt("tasks_json")?.toString().orEmpty()
    val preview = o.optString("preview")
    // Cada chamada reenvia o prompt de sistema (~10 mil tokens): 30 mil davam só 2–3 passos e o agente "concluía" no meio.
    // Agentes não podem morrer no meio por teto: mínimo 800 mil por agente (o líder às vezes pedia 200 mil em ajustes).
    val budgetArg = o.optInt("budget_tokens", 1_500_000).coerceAtLeast(800_000)
    val parallelArg = o.optInt("max_parallel", BuildSquad.PARALLEL)
    val contract = o.optString("contract").trim().take(3_000)
    val arr = runCatching { org.json.JSONArray(tasksJson.trim()) }.getOrElse { return "ERRO: tasks_json deve ser um array JSON de objetos {id, role, task, target, depends_on}." }
    val tasks = (0 until arr.length()).mapNotNull { n ->
        when (val v = arr.opt(n)) {
            is org.json.JSONObject -> v.optString("task").trim().takeIf { it.isNotBlank() }?.let { t ->
                SquadTask(v.optString("id").trim().ifBlank { "t${n + 1}" }, v.optString("role").trim(), t, v.optString("target").trim(),
                    v.optJSONArray("depends_on")?.let { d -> (0 until d.length()).map { d.optString(it).trim() }.filter { it.isNotBlank() } }.orEmpty(),
                    v.optString("file").trim())
            }
            null -> null
            else -> v.toString().trim().takeIf { it.isNotBlank() }?.let { SquadTask("t${n + 1}", "", it, "", emptyList()) }
        }
    }.take(BuildSquad.MAX_TASKS)
    if (tasks.isEmpty()) return "ERRO: informe ao menos uma tarefa em tasks_json."
    if (tasks.map { it.id }.toSet().size != tasks.size) return "ERRO: ids repetidos em tasks_json."
    val ids = tasks.map { it.id }.toSet()
    // Ciclo em depends_on travaria o time: recusa antes de começar.
    run {
        val byId = tasks.associateBy { it.id }; val visiting = mutableSetOf<String>(); val ok = mutableSetOf<String>()
        fun cyc(id: String): Boolean { if (id in ok) return false; if (!visiting.add(id)) return true
            val r = byId[id]?.deps.orEmpty().filter { it in ids }.any { cyc(it) }; visiting.remove(id); ok.add(id); return r }
        if (tasks.any { cyc(it.id) }) return "ERRO: depends_on tem ciclo."
    }
    val budget = budgetArg.coerceIn(150_000, 2_000_000).toLong()
    val parallel = parallelArg.coerceIn(1, 8)
    val personas = BuildSquad.assign(tasks.map { it.role to it.task })
    val parent = store.session(sessionId)
    val parentRequest = parent?.llm?.lastOrNull { it.role == "user" && it.content?.startsWith("[Sistema]") != true }?.content.orEmpty()
    val teamLine = personas.joinToString { "${it.name} (${it.role})" }.take(900)
    val previewNotes = StringBuilder()
    val startedAt = System.currentTimeMillis()
    val squadId = java.util.UUID.randomUUID().toString()
    // O card aparece já na preparação: o usuário vê o app sendo montado antes do time entrar.
    BuildSquad.put(BuildSquad.Squad(squadId, sessionId, goal.ifBlank { "Construindo" }, null, emptyList(),
        notice = if (preview.isNotBlank()) "Preparando o app…" else ""))
    val card = ChatItem.Step(tool = BuildSquad.STEP, title = goal.ifBlank { "Exército de agentes" }.take(120), args = squadId,
        result = "${tasks.size} agentes", status = StepStatus.RUNNING)
    store.addItem(sessionId, card)
    val note: (String) -> Unit = { m -> previewNotes.appendLine(m); BuildSquad.edit(squadId) { it.copy(notice = m.lineSequence().first().take(160)) } }
    val wantTailwind = Regex("(?i)tailwind").containsMatchIn(goal + " " + parentRequest + " " + tasksJson)
    val previewUrl = if (preview.isBlank()) null else squadPreviewUrl(sessionId, preview, note, wantTailwind)
    // Componentes do plano já existem e já estão no App antes do time (a tela mostra cada seção sendo construída).
    val componentFiles = squadProjectDir[sessionId]?.takeIf { previewUrl?.startsWith("http") == true && sessionId !in squadApi }?.let { g ->
        runCatching { wireComponents(sandbox.hostFile(g, sessionId), g, tasks, note) }.getOrDefault(emptyMap())
    }.orEmpty()
    // Regra: o time só entra com o app VIVO no browser. Sem isso, nenhum agente começa.
    var alive = previewUrl != null && (!previewUrl.startsWith("http") ||
        (kotlinx.coroutines.withTimeoutOrNull(240_000) { appAliveInBrowser(sessionId, previewUrl, note) }
            ?: (previewStatus(previewUrl, 15_000).first == 200).also { if (it) note("Checagem do browser passou do tempo, mas o servidor responde; o time vai começar.") }))
    // Servidor morreu durante a checagem (porta fechada): religa uma vez e confere de novo antes de desistir.
    if (!alive && previewUrl?.startsWith("http") == true && previewStatus(previewUrl).first == 0) {
        note("O servidor caiu durante a checagem; religando…")
        if (runCatching { squadPreviewUrl(sessionId, preview, note, wantTailwind) }.getOrNull() != null)
            alive = previewStatus(previewUrl, 15_000).first == 200 || (kotlinx.coroutines.withTimeoutOrNull(180_000) { appAliveInBrowser(sessionId, previewUrl, note) } == true)
    }
    if (preview.isNotBlank() && !alive) {
        BuildSquad.edit(squadId) { it.copy(running = false) }
        store.replaceItem(sessionId, card.copy(result = "O app não ficou de pé; o time não começou.", status = StepStatus.ERROR))
        // Mesma falha de novo: não deixa o líder ficar tentando caminhos/portas às cegas.
        val key = previewNotes.lines().lastOrNull { it.isNotBlank() }.orEmpty().replace(Regex("\\d+"), "#").take(160)
        val n = squadPreviewFails.merge(sessionId, 1 to key) { old, new -> if (old.second == new.second) (old.first + 1) to key else new }!!.first
        val project = squadProjectDir[sessionId]
        return "ERRO: o app não ficou rodando no browser, então o time NÃO começou.\n$previewNotes" +
            (project?.let { "Pasta do projeto usada: $it (o app sobe o servidor nela com a MESMA visão de arquivos do seu terminal).\n" } ?: "") +
            if (n >= 3) "Esta é a ${n}ª falha igual seguida. PARE de chamar build_team e de trocar caminhos. Rode no terminal, na pasta do projeto, `npm install` e depois `npx vite --host 127.0.0.1 --port 5173` (em segundo plano), leia o erro real e corrija; se não conseguir, explique ao usuário o erro exato."
            else "Corrija o projeto (leia o log acima, rode os comandos no terminal) até ele abrir e chame build_team de novo com o mesmo preview (a pasta do projeto)."
    }
    squadPreviewFails.remove(sessionId)
    if (previewUrl != null) note("App no ar em $previewUrl — o time entrou.")
    // Plano único do time: TODOS veem quem faz o quê, o arquivo e o id de cada seção, as âncoras e o contrato do líder.
    // (Antes cada agente só sabia o nome dos colegas e inventava ids, âncoras e nomes diferentes.)
    val squadPlanText = buildString {
        if (contract.isNotBlank()) { appendLine("Contrato do projeto (vale para todos, use exatamente):"); appendLine(contract); appendLine() }
        val secs = componentFiles.values.map { (f, id) -> id to f }
        if (secs.isNotEmpty()) {
            appendLine("Seções da página, nesta ordem (id do elemento raiz = alvo dos links do menu):")
            secs.forEach { (id, f) -> appendLine("- #$id → ${f.substringAfterLast('/')}") }
            appendLine("Links internos usam EXATAMENTE href=\"#<id>\" com estes ids; não crie outros nomes de seção.")
            appendLine()
        }
        appendLine("Quem faz o quê:")
        tasks.forEachIndexed { n, t ->
            appendLine("- ${personas[n].name} (${personas[n].role}): ${t.task.lineSequence().first().take(140)}" + (componentFiles[t.id]?.let { " — ${it.first.substringAfterLast('/')} (#${it.second})" } ?: ""))
        }
    }.take(6_000)
    squadProjectDir[sessionId]?.let { g -> runCatching { File(sandbox.hostFile(g, sessionId), ".fluwy/TIME.md").apply { parentFile?.mkdirs() }.let { f ->
        // Mantém o histórico das rodadas anteriores (edições seguintes do chat), só com o resumo do que foi entregue.
        val prevDone = runCatching { f.readText() }.getOrDefault("").lines().filter { it.startsWith("- ") && (it.contains(" · ok") || it.contains("FALHOU")) }.takeLast(40)
        f.writeText("# Plano do time\n\nObjetivo: $goal\n\n$squadPlanText\n\n## Entregas" + (if (prevDone.isNotEmpty()) "\n" + prevDone.joinToString("\n") else ""))
    } } }
    val workers = tasks.mapIndexed { n, t ->
        val p = personas[n]
        val label = BuildSquad.shortLabel(t.task)
        val compFile = componentFiles[t.id]?.first
        val myId = componentFiles[t.id]?.second
        val taskText = t.task + (compFile?.let { "\n\nSeu arquivo: $it — já existe com um esqueleto e JÁ está importado (default) e renderizado no App. " +
            "Substitua o conteúdo pela seção pronta mantendo `export default function`, e o elemento raiz com id=\"$myId\" (alvo dos links). Não edite o App." } ?: "")
        val child = Session(title = "[Worker ${n + 1}] ${p.name} · $label", agentId = parent?.agentId, squadParentId = sessionId,
            status = SessionStatus.RUNNING,
            llm = listOf(LlmMessage("user", PromptContextPolicy.squadTask(p.name, p.role, goal, taskText, t.target.ifBlank { myId?.let { "#$it" }.orEmpty() },
                teamLine, parentRequest, previewUrl, squadProjectDir[sessionId], wantTailwind, squadPlanText))))
        store.upsertSession(child)
        BuildSquad.Worker(n, p, t.task, label, child.id, t.target.ifBlank { myId?.let { "#$it" }.orEmpty() }, hint = compFile?.let { BuildSquad.hintFromPath(it) }.orEmpty())
    }
    BuildSquad.edit(squadId) { it.copy(preview = previewUrl, workers = workers, notice = "") }

    val browser = if (previewUrl != null) browserFor(sessionId) else null
    val projectGuest = squadProjectDir[sessionId]
    val sem = kotlinx.coroutines.sync.Semaphore(parallel)
    val finished = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<Boolean>>().apply { tasks.forEach { put(it.id, CompletableDeferred()) } }
    val jobs = java.util.Collections.synchronizedList(mutableListOf<Job>())
    val fixes = java.util.concurrent.atomic.AtomicInteger(0)
    val fixedErrors = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Põe um agente para trabalhar (inicial ou chamado no meio, como o "Doc" que corrige erros). */
    fun launch(n: Int, t: SquadTask, w: BuildSquad.Worker) {
        val job = scope.launch {
            val depsOk = t.deps.filter { it in ids && it != t.id }.map { finished[it]?.await() ?: true }.all { it }
            if (!depsOk) {
                BuildSquad.editWorker(squadId, n) { it.copy(status = BuildSquad.St.FAILED, doing = "dependência falhou") }
                store.editSession(w.sessionId) { it.copy(status = SessionStatus.STOPPED) }
                finished[t.id]?.complete(false); return@launch
            }
            var ok = false
            try {
                sem.withPermit {
                    BuildSquad.editWorker(squadId, n) { it.copy(status = BuildSquad.St.RUNNING, doing = "começando") }
                    var myBudget = budget
                    loop(w.sessionId, maxStepsOverride = 60, tokenBudgetOverride = myBudget)
                    // Parou por teto (passos OU tokens) sem terminar: o orquestrador amplia o teto e manda continuar (até 4x).
                    // Antes: 2 voltas só por passos; no teto de tokens a tarefa era reprovada pela metade e as dependentes paravam.
                    fun stoppedByLimit(): Boolean = store.session(w.sessionId)?.let { ss ->
                        val lastErr = ss.items.lastOrNull() as? ChatItem.Error
                        val lastAns = ss.items.lastOrNull { it is ChatItem.Assistant } as? ChatItem.Assistant
                        (ss.status == SessionStatus.WAITING_USER && lastErr?.text?.startsWith("Limite de") == true) ||
                            (lastAns?.text?.startsWith("Progresso parcial preservado") == true && ss.items.lastOrNull() === lastAns)
                    } == true
                    var rounds = 0
                    while (rounds < 4 && stoppedByLimit()) {
                        rounds++
                        myBudget += budget / 2
                        BuildSquad.editWorker(squadId, n) { it.copy(doing = "continuando (orquestrador, rodada ${rounds + 1})") }
                        store.editSession(w.sessionId) { it.copy(status = SessionStatus.RUNNING, llm = it.llm + LlmMessage("user",
                            "[Orquestrador] Você chegou ao teto desta rodada; o teto foi ampliado. Continue de onde parou (não refaça o que já está nos arquivos) e FINALIZE: termine o que falta da SUA tarefa e responda com o resumo (o que fez e quais arquivos).")) }
                        loop(w.sessionId, maxStepsOverride = 60, tokenBudgetOverride = myBudget)
                    }
                    val fin = store.session(w.sessionId)
                    val st = fin?.status
                    // Parou no limite de tokens/passos sem resposta final: NÃO é concluído (antes aparecia ✓ com trabalho pela metade).
                    // Resposta final = texto do agente OU encerramento pela ferramenta finish/complete_step (antes o QA que fechou
                    // com finish aparecia como ✗ mesmo tendo concluído).
                    val lastIsAnswer = fin?.llm?.lastOrNull()?.let { it.role == "assistant" && it.toolCallsJson.isNullOrBlank() && !it.content.isNullOrBlank() } == true ||
                        fin?.llm?.lastOrNull()?.let { it.role == "tool" && (it.name == "finish" || it.name == "complete_step") } == true ||
                        (fin?.items?.lastOrNull { it is ChatItem.Assistant } as? ChatItem.Assistant)?.final == true
                    // Só conta como cortado se a ÚLTIMA resposta é a de corte (uma rodada seguinte pode ter concluído).
                    val cut = (fin?.items?.lastOrNull { it is ChatItem.Assistant } as? ChatItem.Assistant)?.text?.startsWith("Progresso parcial preservado") == true
                    ok = (st == SessionStatus.DONE || st == SessionStatus.IDLE) && lastIsAnswer && !cut
                    // Componente da tarefa precisa continuar exportando default (o App importa assim).
                    val compBroken = componentFiles[t.id]?.first?.takeIf { Regex("""\.(jsx|tsx)$""").containsMatchIn(it) }?.let { g -> runCatching { sandbox.hostFile(g, sessionId).readText() }.getOrNull() }
                        ?.let { !Regex("""export\s+default\b""").containsMatchIn(it) } == true
                    if (ok && compBroken) { ok = false; BuildSquad.editWorker(squadId, n) { it.copy(doing = "componente sem export default") } }
                    if (!ok && (st == SessionStatus.DONE || st == SessionStatus.IDLE))
                        BuildSquad.editWorker(squadId, n) { it.copy(doing = "parou antes de terminar (limite)") }
                }
            } catch (c: kotlinx.coroutines.CancellationException) {
                store.editSession(w.sessionId) { it.copy(status = SessionStatus.STOPPED) }
                BuildSquad.editWorker(squadId, n) { it.copy(status = BuildSquad.St.FAILED, doing = "parado") }
                finished[t.id]?.complete(false)
                throw c
            } catch (e: Throwable) {
                store.addItem(w.sessionId, ChatItem.Error(text = "Agente falhou: " + (e.message ?: e.toString()).take(300)))
                store.editSession(w.sessionId) { it.copy(status = SessionStatus.ERROR) }
            }
            BuildSquad.editWorker(squadId, n) { it.copy(status = if (ok) BuildSquad.St.DONE else BuildSquad.St.FAILED,
                doing = if (ok) "pronto" else it.doing.takeIf { d -> d.startsWith("parou") || d.startsWith("componente") } ?: "falhou") }
            BuildSquad.edit(squadId) { it.copy(log = (it.log + "${w.persona.name}|${w.label}|${if (ok) "ok" else "erro"}").takeLast(60)) }
            // Mural do time (ideia do canal compartilhado do OpenBot): o que cada um entregou fica em .fluwy/TIME.md, e quem
            // começa depois (dependentes, Doc, próximas edições do chat) lê só o resumo em vez de reabrir todos os arquivos.
            runCatching {
                val answer = (store.session(w.sessionId)?.items?.lastOrNull { it is ChatItem.Assistant } as? ChatItem.Assistant)?.text.orEmpty()
                squadProjectDir[sessionId]?.let { g ->
                    val f = File(sandbox.hostFile(g, sessionId), ".fluwy/TIME.md")
                    if (f.parentFile?.isDirectory == true) synchronized(this) {
                        f.appendText("\n- ${java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.US).format(java.util.Date())} · ${w.persona.name} (${w.persona.role}) · ${t.task.take(90)}" +
                            (t.file.takeIf { it.isNotBlank() }?.let { " · arquivo $it" } ?: "") + " · ${if (ok) "ok" else "FALHOU"}" +
                            (answer.takeIf { it.isNotBlank() }?.let { ": " + it.replace(Regex("\\s+"), " ").take(220) } ?: ""))
                    }
                }
            }
            finished[t.id]?.complete(ok)
            runCatching { terminals.close(w.sessionId) }
        }
        jobs += job
        synchronized(childJobsByParent) { childJobsByParent.getOrPut(sessionId) { mutableListOf() }.add(job) }
    }

    /**
     * Erro no preview (overlay do Vite, falha de import, página quebrada): pacote ausente é instalado na hora;
     * o resto vira tarefa de um agente "Doc" que corrige com edição mínima. Mesma mensagem não é atacada duas vezes.
     */
    suspend fun handlePreviewError(err: String, customTask: String? = null) {
        val key = err.replace(Regex("\\d+"), "#").replace(Regex("\\s+"), " ").take(200)
        if (!fixedErrors.add(key)) return
        BuildSquad.edit(squadId) { it.copy(notice = "Erro no preview: " + err.lineSequence().first().take(140)) }
        BuildSquad.edit(squadId) { it.copy(log = (it.log + "Orquestrador|erro detectado: ${err.lineSequence().first().take(60)}|erro").takeLast(60)) }
        val pkg = Regex("""(?:Failed to resolve import|Cannot find (?:module|package)|Could not resolve)\s+["']([^"'./][^"']*)["']""").find(err)?.groupValues?.get(1)
            ?.let { p -> if (p.startsWith("@")) p.split('/').take(2).joinToString("/") else p.substringBefore('/') }
        if (pkg != null && projectGuest != null && Regex("^[@a-z0-9][a-z0-9._/-]{0,80}$").matches(pkg)) {
            BuildSquad.edit(squadId) { it.copy(notice = "Instalando o pacote que faltou: $pkg…") }
            val r = sandbox.exec(dev.fluwy.app.sandbox.InteractivePrompts.ENV + " && npm install --no-audit --no-fund --loglevel=error '$pkg' < /dev/null",
                cwd = projectGuest, timeoutSec = 300, idleTimeoutSec = 180, sessionId = sessionId)
            BuildSquad.edit(squadId) { it.copy(log = (it.log + "Orquestrador|npm install $pkg|${if (r.exitCode == 0) "ok" else "erro"}").takeLast(60)) }
            if (r.exitCode == 0) { browser?.softReload(); BuildSquad.edit(squadId) { it.copy(notice = "") }; return }
        }
        if (fixes.get() >= 6) return
        fixes.incrementAndGet()
        val doc = BuildSquad.FIXER.copy(name = if (fixes.get() == 1) BuildSquad.FIXER.name else "${BuildSquad.FIXER.name} ${fixes.get()}")
        val fixTask = customTask ?: "O preview do projeto QUEBROU com este erro (detectado pelo app no browser):\n" + err.take(1_500) +
            "\n\nEncontre a causa (arquivo e linha costumam estar na mensagem), corrija com a MENOR edição possível e confira que o preview volta " +
            "(curl do arquivo editado = 200). Pacote ausente: npm install <pacote> na pasta do projeto. Não reescreva nem refatore o resto."
        val n = BuildSquad.get(squadId)?.workers?.size ?: return
        val id = "fix${fixes.get()}"
        finished[id] = CompletableDeferred()
        val child = Session(title = "[Worker ${n + 1}] ${doc.name} · Corrigir erro", agentId = parent?.agentId, squadParentId = sessionId,
            status = SessionStatus.RUNNING,
            llm = listOf(LlmMessage("user", PromptContextPolicy.squadTask(doc.name, doc.role, goal, fixTask, "", teamLine, parentRequest, previewUrl, projectGuest, wantTailwind, squadPlanText))))
        store.upsertSession(child)
        val w = BuildSquad.Worker(n, doc, fixTask, "Corrigir erro", child.id)
        BuildSquad.edit(squadId) { it.copy(workers = it.workers + w) }
        launch(n, SquadTask(id, doc.role, fixTask, "", emptyList()), w)
    }

    /** Lê o estado da página: erro visível (overlay do Vite/Next, tela de erro) e há quanto tempo o DOM mudou. */
    suspend fun pageState(): Pair<String?, Long> {
        val raw = runCatching { browser!!.runJs("(function(){var e='';var o=document.querySelector('vite-error-overlay');" +
            "if(o){var r=o.shadowRoot;e=(r&&(r.querySelector('.message-body')||r.querySelector('.message'))||o).textContent||'';var f=r&&r.querySelector('.file');if(f)e+='\\n'+f.textContent;}" +
            "if(!e){var n=document.querySelector('nextjs-portal');if(n&&n.shadowRoot)e=(n.shadowRoot.textContent||'').slice(0,1500);}" +
            "var s=window.__fluwySquad;return JSON.stringify({e:e.trim().slice(0,1500),m:s&&s.lastMut?Date.now()-s.lastMut:-1});})()") }.getOrDefault("")
        // Erros que não abrem o overlay do Vite (ex.: "does not provide an export named 'default'") só aparecem no console.
        val now = System.currentTimeMillis()
        val consoleErr = runCatching { browser!!.console.value }.getOrDefault(emptyList())
            .lastOrNull { it.level == "error" && now - it.time < 8_000 && SQUAD_FATAL_CONSOLE.containsMatchIn(it.message) }
            ?.let { "${it.message.take(600)} (${it.source})" }
        return runCatching {
            val o = org.json.JSONObject(raw)
            (o.optString("e").ifBlank { null } ?: consoleErr) to o.optLong("m", -1)
        }.getOrDefault(consoleErr to -1L)
    }

    // Preview ao vivo: abre a página, mostra cada edição (HMR; se o HMR não refletir, recarrega), redesenha os cursores
    // e vigia erros para corrigir na hora.
    val watchers = mutableListOf<Job>()
    if (browser != null) {
        watchers += scope.launch {
            BuildSquad.squads.map { it[squadId] }.distinctUntilChanged().collect { sq ->
                if (sq != null) browser.pushSquadOverlay(BuildSquad.overlayJson(sq))
            }
        }
        watchers += scope.launch {
            val root = projectGuest?.let { sandbox.hostFile(it, sessionId) } ?: sandbox.workspaceFor(sessionId)
            val pageFile = previewUrl!!.takeIf { it.startsWith("file://") }?.let { File(it.removePrefix("file://")) }
            val dev = previewUrl.startsWith("http")
            var opened = dev  // já aberto e conferido em appAliveInBrowser
            var lastSig = ""
            var changedAt = 0L
            var lastReload = 0L
            var previewBad = false
            var serverRestarts = 0
            var badSince = 0L
            var errSince = 0L
            var lastErr: String? = null
            var tick = 0
            val exts = setOf("html", "htm", "css", "scss", "js", "jsx", "ts", "tsx", "mjs", "vue", "svelte", "svg", "json", "png", "jpg", "jpeg", "webp")
            while (isActive) {
                if (!opened && (pageFile == null || pageFile.exists())) {
                    opened = true
                    runCatching { browser.navigate(previewUrl) }
                    BuildSquad.get(squadId)?.let { browser.pushSquadOverlay(BuildSquad.overlayJson(it)) }
                } else if (opened) {
                    val now = System.currentTimeMillis()
                    val sig = runCatching {
                        root.walkTopDown().maxDepth(6).onEnter { it.name != "node_modules" && it.name != "dist" && !it.name.startsWith(".") }
                            .filter { it.isFile && it.extension.lowercase() in exts }
                            .take(800).fold(0L) { acc, f -> acc * 31 + f.lastModified() + f.length() }.toString()
                    }.getOrDefault("")
                    if (lastSig.isNotEmpty() && sig != lastSig) {
                        changedAt = now
                        if (!dev) browser.softReload()
                    }
                    lastSig = sig
                    if (dev) {
                        val (code, body) = previewStatus(previewUrl)
                        val okNow = if (sessionId in squadApi) code in 100..499 else code in 200..399
                        if (okNow && previewBad) { browser.softReload(); lastReload = now; BuildSquad.edit(squadId) { it.copy(notice = "") } }
                        if (!okNow) {
                            if (badSince == 0L) badSince = now
                            if (now - badSince > 10_000) {
                                val devLog = runCatching { File(sandbox.workspaceFor(sessionId), ".fluwy/devserver.log").readText().takeLast(300) }.getOrDefault("")
                                val msg = (if (code == 0) "O servidor do preview não responde" else "O servidor do preview respondeu HTTP $code") +
                                    (body.trim().takeIf { it.isNotEmpty() }?.let { ": " + it.replace(Regex("\\s+"), " ").take(160) } ?: "")
                                BuildSquad.edit(squadId) { it.copy(notice = msg) }
                                if (previewNotes.indexOf(msg) < 0) previewNotes.appendLine(msg + (if (devLog.isNotBlank()) "\nLog do servidor: " + devLog.trim() else ""))
                            }
                            // Servidor caiu (porta fechada) no meio do trabalho: o app religa sozinho (até 3x), sem gastar um agente nisso.
                            if (code == 0 && now - badSince > 15_000 && serverRestarts < 3 && sessionId !in squadApi) {
                                serverRestarts++
                                BuildSquad.edit(squadId) { it.copy(notice = "O servidor do preview caiu; religando (tentativa $serverRestarts)…") }
                                val back = runCatching { squadPreviewUrl(sessionId, preview, note, wantTailwind) }.getOrNull()
                                if (back != null) { badSince = 0L; browser.softReload(); lastReload = System.currentTimeMillis(); BuildSquad.edit(squadId) { it.copy(notice = "Servidor religado.") } }
                            }
                        } else {
                            badSince = 0L
                            // A cada 2 voltas (~2,4 s): ler a página pesa no WebView do celular (o renderer chegou a travar).
                            val skip = sessionId in squadApi || (++tick % (if (browser.liteMode()) 4 else 2) != 0 && changedAt == 0L)
                            val (err, sinceMut) = if (skip) (null to -1L) else pageState()
                            // Arquivo mudou e a página não mexeu em 2,5 s: o HMR não pegou (watcher do proot); recarrega.
                            if (err == null && changedAt > 0 && now - changedAt in 2_500..20_000 && (sinceMut < 0 || sinceMut > now - changedAt) && now - lastReload > 3_000) {
                                browser.softReload(); lastReload = now; changedAt = 0
                            } else if (sinceMut in 0..(now - changedAt)) changedAt = 0
                            if (skip) Unit
                            else if (err != null) {
                                if (err != lastErr) { lastErr = err; errSince = now }
                                // Erro que persiste 4 s (não é só um salvamento no meio do caminho): orquestrador age.
                                else if (now - errSince >= 4_000) handlePreviewError(err)
                            } else { lastErr = null; errSince = 0 }
                        }
                        previewBad = !okNow
                    }
                }
                kotlinx.coroutines.delay(1_200)
            }
        }
    }

    workers.forEachIndexed { n, w -> launch(n, tasks[n], w) }
    try {
        // Espera todos, inclusive os "Doc" chamados no meio; no fim, confere uma última vez se o preview está sem erro.
        var finalChecks = 0
        var anchorsChecked = false
        var importsChecked = false
        var buildChecked = false
        while (true) {
            val active = synchronized(jobs) { jobs.filter { it.isActive } }
            if (active.isNotEmpty()) { active.joinAll(); continue }
            // Todo componente importado pelo App existe e exporta default (arquivo vazio ou export nomeado derrubava a página).
            if (!importsChecked && projectGuest != null && sessionId !in squadApi) {
                importsChecked = true
                val bad = runCatching { brokenImports(sandbox.hostFile(projectGuest, sessionId)) }.getOrDefault(emptyList())
                if (bad.isNotEmpty()) {
                    handlePreviewError("Imports quebrados: " + bad.joinToString(),
                        "O App não consegue carregar estes componentes: " + bad.joinToString("; ") + ". Corrija cada um para existir e ter `export default function <Nome>()` " +
                            "com a seção completa (mantenha o id do elemento raiz do plano). Se o arquivo estiver vazio, reconstrua a seção conforme o plano do time. Não mexa no resto.")
                    continue
                }
            }
            // Coerência entre os agentes: todo link interno (#id) precisa ter a seção com esse id.
            if (!anchorsChecked && projectGuest != null && sessionId !in squadApi) {
                anchorsChecked = true
                val broken = runCatching { brokenAnchors(sandbox.hostFile(projectGuest, sessionId)) }.getOrNull()
                if (broken != null && broken.first.isNotEmpty()) {
                    handlePreviewError("Âncoras quebradas: " + broken.first.joinToString { "#$it" },
                        "Links internos do site apontam para seções que não existem: " + broken.first.joinToString { "#$it" } +
                            ". Ids que existem: " + broken.second.joinToString { "#$it" }.ifBlank { "(nenhum)" } +
                            ". Alinhe com o plano do time: cada link deve usar o id do elemento raiz da seção certa (corrija o href ou o id, o que for menor e coerente com o plano). Não mude mais nada.")
                    continue
                }
            }
            // Verificação final (como o "check" do OpenBot): build de produção pega erros que o dev server tolera
            // (import inexistente, JSX quebrado, export errado). Erro → Doc corrige; uma rodada só, para não gastar tokens à toa.
            if (!buildChecked && projectGuest != null && sessionId !in squadApi && File(sandbox.hostFile(projectGuest, sessionId), "package.json").isFile) {
                buildChecked = true
                BuildSquad.edit(squadId) { it.copy(notice = "Conferindo o build de produção…") }
                val r = runCatching { sandbox.exec(dev.fluwy.app.sandbox.InteractivePrompts.ENV + " && npx --no-install vite build --logLevel error --emptyOutDir --outDir .fluwy/build-check < /dev/null",
                    cwd = projectGuest, timeoutSec = 240, idleTimeoutSec = 120, sessionId = sessionId) }.getOrNull()
                BuildSquad.edit(squadId) { it.copy(notice = "", log = (it.log + "Orquestrador|build de produção|${if (r?.exitCode == 0) "ok" else if (r == null || r.timedOut) "pulado" else "erro"}").takeLast(60)) }
                if (r != null && !r.timedOut && r.exitCode != 0 && r.output.isNotBlank()) {
                    val err = r.output.lines().filter { it.isNotBlank() && !it.contains("npm warn", true) }.takeLast(25).joinToString("\n").take(1_800)
                    handlePreviewError("Build de produção falhou: " + err.lineSequence().first().take(120),
                        "O build de produção (vite build) falhou com este erro:\n$err\nCorrija só a causa (arquivo e linha indicados), sem refazer seções. Depois rode `npx vite build --logLevel error --outDir .fluwy/build-check` para confirmar.")
                    continue
                }
            }
            if (browser == null || !previewUrl!!.startsWith("http") || finalChecks++ >= 2) break
            kotlinx.coroutines.delay(2_500)
            val err = pageState().first ?: break
            handlePreviewError(err)
            if (synchronized(jobs) { jobs.none { it.isActive } }) break
        }
    } finally {
        synchronized(childJobsByParent) { childJobsByParent.remove(sessionId) }
        watchers.forEach { it.cancel() }
        BuildSquad.edit(squadId) { it.copy(running = false) }
    }
    val sq = BuildSquad.get(squadId)
    val okCount = sq?.done ?: 0
    store.replaceItem(sessionId, card.copy(result = "$okCount de ${workers.size} agentes concluíram",
        status = if (okCount == workers.size) StepStatus.OK else StepStatus.ERROR))
    if (browser != null) scope.launch { kotlinx.coroutines.delay(4_000); browser.pushSquadOverlay(null); if (previewUrl!!.startsWith("file://")) browser.softReload() }
    return buildString {
        appendLine("Exército de agentes: $okCount de ${workers.size} concluíram (até $parallel ao mesmo tempo).")
        sq?.workers?.forEach { w ->
            val answer = (store.session(w.sessionId)?.items?.lastOrNull { it is ChatItem.Assistant } as? ChatItem.Assistant)?.text.orEmpty()
            appendLine("- ${w.persona.name} (${w.persona.role}) · ${w.label}: ${if (w.status == BuildSquad.St.DONE) "ok" else "FALHOU"} — ${answer.ifBlank { w.doing }.replace('\n', ' ').take(260)}")
        }
        append(workspaceChanges(sessionId, startedAt))
        if (previewNotes.isNotBlank()) append("\n" + previewNotes.toString().trim())
        if (previewUrl != null) append("\nPreview: $previewUrl (o usuário acompanhou no browser; o servidor de desenvolvimento continua no ar). Agora abra a página, confira o resultado e corrija o que faltar.")
        else append("\nAgora rode os testes/servidor para validar e corrija o que faltar.")
    }
}

internal suspend fun AgentRunner.runMultiAgentsBatch(sessionId: String, tasksJson: String, repoPathArg: String, requestedWorkers: Int, requestedBudget: Int, allowDirtyWorkspace: Boolean = false): String {
    val tasks = runCatching {
        val arr = org.json.JSONArray(tasksJson)
        (0 until arr.length()).mapNotNull { arr.optString(it).trim().takeIf { task -> task.isNotBlank() } }
    }.getOrElse { return "ERRO: tasks_json deve ser um array JSON de strings." }
    if (tasks.isEmpty()) return "ERRO: informe pelo menos uma subtarefa."

    val count = minOf(tasks.size, requestedWorkers.coerceIn(1, 5))
    val budget = requestedBudget.coerceIn(2000, 12000).toLong()
    val parentPath = sandbox.guestPath(sessionId)
    val terminal = terminals.get(sessionId, parentPath)
    val parentSession = store.session(sessionId)
    val parentRequest = parentSession?.llm?.lastOrNull {
        it.role == "user" && it.content?.startsWith("[Sistema]") != true
    }?.content.orEmpty()
    val parentPlan = (parentSession?.items?.lastOrNull { it is ChatItem.Plan } as? ChatItem.Plan)
        ?.phases?.joinToString("\n") { phase -> "- [${if (phase.done) "x" else " "}] ${phase.title}" }.orEmpty()
    // Não use git worktree dentro de /root/workspace: cada sessão é montada num rootfs
    // público diferente, e a pasta de destino já existe. A cópia é feita no filesystem host.
    val gitRoot: String? = null
    val baseSha: String? = null
    var baseSnapshot: Map<String, String> = emptyMap()

    val workers = tasks.take(count).mapIndexed { index, task ->
        Session(
            title = "[Worker " + (index + 1) + "] " + task.take(42),
            agentId = store.session(sessionId)?.agentId,
            status = SessionStatus.RUNNING,
            llm = listOf(
                LlmMessage("user", PromptContextPolicy.workerTask(
                    task = task,
                    parentRequest = parentRequest,
                    parentSummary = parentSession?.summary.orEmpty(),
                    plan = parentPlan,
                ))
            )
        )
    }
    workers.forEach { store.upsertSession(it) }

    if (gitRoot != null && baseSha != null) {
        val setupErrors = mutableListOf<String>()
        workers.forEach { child ->
            val branchName = "fluwy/agents/" + child.id
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
                        shQuote(gitRoot) + " branch -D " + shQuote("fluwy/agents/" + child.id) +
                        " >/dev/null 2>&1 || true",
                    20L, false
                )
                store.deleteSession(child.id)
            }
            return "Não iniciei workers porque a criação de worktrees falhou:\n" + setupErrors.joinToString("\n")
        }
    } else {
        val source = sandbox.workspaceFor(sessionId)
        // Snapshot do projeto original: é contra ele que as alterações de cada worker serão comparadas na volta.
        baseSnapshot = projectSnapshot(source)
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
                loop(child.id, maxStepsOverride = 30, tokenBudgetOverride = budget)
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
            val patchPath = gitRoot.trimEnd('/') + "/.fluwy-worker-" + (index + 1) + ".patch"
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
            val merge = runCatching { integrateWorker(baseSnapshot, sandbox.workspaceFor(child.id), sandbox.workspaceFor(sessionId), index + 1) }.getOrNull()
            if (merge == null) { integrationFailures++; appendReportLine(report, "Agente " + (index + 1) + ": integração falhou; revise o workspace."); return@forEachIndexed }
            if (merge.conflicts.isEmpty()) integratedCount++ else integrationFailures++
            appendReportLine(report, "Agente " + (index + 1) + ": " + merge.applied.size + " arquivo(s) aplicado(s) ao projeto principal" +
                (if (merge.applied.isNotEmpty()) " (" + merge.applied.take(12).joinToString() + (if (merge.applied.size > 12) ", …" else "") + ")" else "") +
                (if (merge.deleted.isNotEmpty()) "; " + merge.deleted.size + " removido(s)" else "") +
                (if (merge.conflicts.isNotEmpty()) ". CONFLITO em " + merge.conflicts.take(12).joinToString() +
                    ": outro worker (ou você) alterou o mesmo arquivo. O principal ficou como estava e a versão deste worker está em <arquivo>.worker-" + (index + 1) + " — mescle e apague o .worker." else "."))
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
        runCatching { dev.fluwy.app.FluwyApp.instance.closeBrowser(child.id, clearPersistentData = clean) }
        if (clean) {
            if (gitRoot != null && baseSha != null) {
                terminal.runAgentCommand(
                    "git -C " + shQuote(gitRoot) + " worktree remove --force " +
                        shQuote(sandbox.guestPath(child.id)) + " >/dev/null 2>&1 || true; git -C " +
                        shQuote(gitRoot) + " branch -D " + shQuote("fluwy/agents/" + child.id) +
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

internal fun AgentRunner.appendReportLine(report: StringBuilder, line: String) { report.appendLine(line) }

/** Regras do ask_question (fora do runTool, que já está no limite de tamanho do método JVM). */
internal fun AgentRunner.askQuestionGuard(sessionId: String): ToolResult? = run {
            // GUARDRAIL: só existe para uma dúvida real dentro de um pedido do usuário presente —
            // nunca em ciclos autônomos/agendados e no máximo uma vez por mensagem do usuário.
            val sess = store.session(sessionId)
            val items = sess?.items.orEmpty()
            val lastUser = items.indexOfLast { it is ChatItem.User }
            val autonomous = sess?.continuous == true || sess?.llm?.lastOrNull { it.role == "user" }?.content?.startsWith("[Heartbeat") == true
            if (lastUser < 0 || autonomous) return@run ToolResult("RECUSADO: ask_question só pode ser usado quando o usuário pediu uma tarefa e está na conversa. Siga com a suposição mais razoável e diga qual assumiu.")
            // Pedido de entrevista ("faça-me perguntas até entender") libera até 4 rodadas; senão, 1 por pedido.
            val interview = items.filterIsInstance<ChatItem.User>().any { AskTools.wantsInterview(it.text) }
            val rounds = items.drop(lastUser + 1).count { it is ChatItem.Question }
            val pending = items.drop(lastUser + 1).filterIsInstance<ChatItem.Question>().any { it.state == "pending" }
            if (pending) return@run ToolResult("RECUSADO: ainda há perguntas sem resposta. Aguarde o usuário.", SessionStatus.WAITING_USER)
            if (rounds >= (if (interview) 4 else 1)) return@run ToolResult("RECUSADO: você já fez perguntas para este pedido. Use as respostas recebidas (ou uma suposição razoável) e continue a tarefa.")
            null
}

internal fun AgentRunner.shQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"

internal fun AgentRunner.appendTerm(sessionId: String, text: String) {
    val m = _termLog.value
    _termLog.value = m + (sessionId to ((m[sessionId] ?: "") + text).takeLast(60_000))
}

internal fun AgentRunner.finalizeRunningSteps(sessionId: String, reason: String) {
    val steps = store.session(sessionId)?.items?.filterIsInstance<ChatItem.Step>()
        ?.filter { it.status == StepStatus.RUNNING && it.tool != TEAM_MEMBER_STEP }.orEmpty()
    steps.forEach { step ->
        store.replaceItem(sessionId, step.copy(result = reason, status = StepStatus.ERROR))
    }
}

internal fun AgentRunner.setActivity(sessionId: String, a: Activity) {
    _activity.value = _activity.value + (sessionId to a)
}
