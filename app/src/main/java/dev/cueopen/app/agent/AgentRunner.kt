package dev.cueopen.app.agent

import dev.cueopen.app.browser.BrowserHost
import dev.cueopen.app.data.AgentProfile
import dev.cueopen.app.data.ChatItem
import dev.cueopen.app.data.KnowledgeItem
import dev.cueopen.app.data.LlmMessage
import dev.cueopen.app.data.PlanPhase
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
        store.editSession(sessionId) { it.copy(status = SessionStatus.STOPPED) }
        setActivity(sessionId, Activity())
        browser.setAgentControl(false)
    }

    /** Envia uma mensagem do usuário e (re)inicia o loop. */
    fun send(sessionId: String, text: String, attachments: List<String> = emptyList(), onDone: (() -> Unit)? = null): Job {
        jobs[sessionId]?.cancel()
        store.addItem(sessionId, ChatItem.User(text = text, attachments = attachments))
        val content = if (attachments.isEmpty()) text
        else text + "\n\n[Arquivos anexados no workspace: ${attachments.joinToString()}]"
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

    private suspend fun loop(sessionId: String) {
        val maxSteps = store.settings.maxSteps
        var steps = 0
        while (scope.isActive) {
            val s = store.session(sessionId) ?: return
            val agent = store.agent(s.agentId)
            setActivity(sessionId, Activity(Activity.Kind.THINKING, "Pensando"))
            val sys = LlmMessage("system", systemPrompt(sessionId, agent))
            val reply = llm.chat(listOf(sys) + trimHistory(s.llm), tools = tools(agent), model = agent?.model)
            store.editSession(sessionId) {
                it.copy(llm = it.llm + LlmMessage("assistant", reply.content, toolCallsJson = reply.rawToolCallsJson))
            }
            if (!reply.content.isNullOrBlank()) {
                store.addItem(sessionId, ChatItem.Assistant(text = reply.content.trim(), final = reply.toolCalls.isEmpty()))
            }
            if (reply.toolCalls.isEmpty()) {
                store.editSession(sessionId) { it.copy(status = SessionStatus.DONE) }
                return
            }
            var stopLoop: SessionStatus? = null
            for (call in reply.toolCalls) {
                val result = runTool(sessionId, call)
                store.editSession(sessionId) {
                    it.copy(llm = it.llm + LlmMessage("tool", result.output.take(12_000), toolCallId = call.id, name = call.name))
                }
                if (result.stop != null) stopLoop = result.stop
            }
            val finalStatus = stopLoop
            if (finalStatus != null) {
                store.editSession(sessionId) { it.copy(status = finalStatus) }
                return
            }
            if (++steps >= maxSteps) {
                store.addItem(sessionId, ChatItem.Error(text = "Limite de $maxSteps passos atingido. Envie \"continue\" para seguir."))
                store.editSession(sessionId) { it.copy(status = SessionStatus.WAITING_USER) }
                return
            }
        }
    }

    /** Mantém o contexto enxuto: preserva as últimas mensagens e encurta saídas antigas de ferramentas. */
    private fun trimHistory(h: List<LlmMessage>): List<LlmMessage> {
        val keepFull = 24
        return h.mapIndexed { i, m ->
            if (m.role == "tool" && i < h.size - keepFull) m.copy(content = (m.content ?: "").take(800) + "\n…[truncado]") else m
        }
    }

    private fun systemPrompt(sessionId: String, agent: AgentProfile?): String {
        val st = store.state.value
        val skills: List<Skill> = st.skills.filter { sk ->
            sk.enabled && (agent == null || agent.skillIds.isEmpty() || sk.id in agent.skillIds)
        }
        val knowledge: List<KnowledgeItem> = st.knowledge.filter { it.enabled }
        val date = SimpleDateFormat("EEEE, d 'de' MMMM 'de' yyyy HH:mm", Locale("pt", "BR")).format(Date())
        return buildString {
            appendLine("Você é ${agent?.name ?: "Cue"}, um agente autônomo de uso geral rodando no app CueOpen (Android).")
            appendLine("Data/hora: $date. Usuário: ${st.settings.userName.ifBlank { "não informado" }}.")
            appendLine()
            appendLine("Você tem um COMPUTADOR próprio:")
            appendLine("- Terminal Linux (Alpine, via proot) — ferramenta `shell`. Workspace da tarefa: ${sandbox.guestPath(sessionId)} (diretório padrão). Instale pacotes com `apk add`, `pip install`, `npm i`.")
            appendLine("- Browser real (WebView) — ferramentas browser_*. O usuário vê a tela ao vivo.")
            appendLine("- Editor de arquivos — file_read / file_write / file_list.")
            appendLine()
            appendLine("Modo de trabalho:")
            appendLine("1. Para tarefas com várias etapas, chame `update_plan` primeiro e atualize ao concluir fases.")
            appendLine("2. Aja com as ferramentas em vez de descrever o que faria. Verifique resultados (rode o código, abra a página).")
            appendLine("3. Entregáveis vão para arquivos no workspace; cite os caminhos na resposta final.")
            appendLine("4. Use `ask_user` só quando algo for realmente ambíguo ou irreversível.")
            appendLine("5. Termine com `finish` contendo um resumo curto do resultado. Responda no idioma do usuário.")
            if (!agent?.instructions.isNullOrBlank()) { appendLine(); appendLine("## Instruções do Agent"); appendLine(agent!!.instructions) }
            if (!agent?.memory.isNullOrEmpty()) { appendLine(); appendLine("## Memória do Agent"); agent!!.memory.forEach { appendLine("- $it") } }
            if (skills.isNotEmpty()) {
                appendLine(); appendLine("## Skills disponíveis (siga as instruções quando o pedido se encaixar)")
                skills.forEach { appendLine("### ${it.name}\n${it.description}\n${it.instructions}\n") }
            }
            if (knowledge.isNotEmpty()) {
                appendLine(); appendLine("## Knowledge do usuário (contexto confiável)")
                knowledge.forEach { appendLine("### ${it.title}\n${it.content.take(2000)}\n") }
            }
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
        add(tool("file_write", "Cria ou sobrescreve um arquivo de texto (caminho relativo ao workspace ou absoluto).", buildJsonObject {
            put("path", str("Caminho")); put("content", str("Conteúdo completo"))
        }, listOf("path", "content")))
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
        add(tool("remember", "Salva um fato duradouro na memória deste Agent.", buildJsonObject { put("fact", str("Fato")) }, listOf("fact")))
        add(tool("create_skill", "Cria uma nova skill na biblioteca do usuário.", buildJsonObject {
            put("name", str("Nome")); put("description", str("Descrição curta")); put("instructions", str("Instruções detalhadas"))
        }, listOf("name", "description", "instructions")))
        add(tool("ask_user", "Faz uma pergunta ao usuário e pausa a tarefa até ele responder.", buildJsonObject { put("question", str("Pergunta")) }, listOf("question")))
        add(tool("finish", "Conclui a tarefa com um resumo e a lista de entregáveis.", buildJsonObject {
            put("summary", str("Resumo do resultado")); put("files", strArray("Caminhos dos arquivos entregues"))
        }, listOf("summary")))
    }

    data class ToolResult(val output: String, val stop: SessionStatus? = null)

    private suspend fun runTool(sessionId: String, call: ToolCall): ToolResult {
        val a: JsonObject = runCatching { json.parseToJsonElement(call.argsJson).jsonObject }.getOrDefault(JsonObject(emptyMap()))
        fun s(k: String) = a[k]?.jsonPrimitive?.contentOrNull.orEmpty()
        fun i(k: String) = a[k]?.jsonPrimitive?.intOrNull
        fun b(k: String) = a[k]?.jsonPrimitive?.booleanOrNull ?: false
        val cwd = sandbox.guestPath(sessionId)

        // Ferramentas "silenciosas" com card próprio
        when (call.name) {
            "update_plan" -> {
                val phases = a["phases"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
                val done = i("done_count") ?: 0
                val plan = ChatItem.Plan(goal = s("goal"), phases = phases.mapIndexed { idx, t -> PlanPhase(t, idx < done) })
                val existing = store.session(sessionId)?.items?.lastOrNull { it is ChatItem.Plan }
                if (existing != null) store.replaceItem(sessionId, plan.copy(id = existing.id)) else store.addItem(sessionId, plan)
                return ToolResult("Plano atualizado.")
            }
            "ask_user" -> {
                store.addItem(sessionId, ChatItem.Assistant(text = s("question"), final = true))
                return ToolResult("Pergunta enviada; aguardando resposta.", SessionStatus.WAITING_USER)
            }
            "finish" -> {
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
            "remember" -> "Salvando na memória" to Activity.Kind.THINKING
            "create_skill" -> "Criando skill" to Activity.Kind.THINKING
            else -> call.name to Activity.Kind.THINKING
        }
        val argPreview = when (call.name) {
            "shell" -> s("command")
            "file_write", "file_read", "file_list" -> s("path").ifBlank { cwd }
            "browser_navigate" -> s("url")
            "browser_type" -> s("text")
            "browser_click" -> "#${i("index")}"
            "remember" -> s("fact")
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
                    val f = sandbox.hostFile(s("path"), sessionId)
                    f.parentFile?.mkdirs(); f.writeText(s("content"))
                    "Arquivo salvo: ${s("path")} (${f.length()} bytes)"
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
                "remember" -> {
                    val sess = store.session(sessionId)
                    val ag = store.agent(sess?.agentId)
                    if (ag != null) { store.upsertAgent(ag.copy(memory = ag.memory + s("fact"))); "Memória salva." }
                    else "Esta tarefa não usa um Agent; crie um Agent para ter memória."
                }
                "create_skill" -> {
                    store.upsertSkill(Skill(name = s("name"), description = s("description"), instructions = s("instructions")))
                    "Skill \"${s("name")}\" criada e ativada."
                }
                else -> "Ferramenta desconhecida: ${call.name}"
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
