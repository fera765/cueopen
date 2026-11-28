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

/**
 * Execução de ferramenta sob o orquestrador: reaproveita resultado idêntico de leitura na mesma execução,
 * aplica prazo máximo (15 min) e registra a operação (duração, sucesso, resumo) no ExecutionLedger.
 */
internal suspend fun AgentRunner.ledgerRun(sessionId: String, call: ToolCall, block: suspend () -> ToolResult): ToolResult {
    val h = ExecutionLedger.hash(call.name + "|" + normArgs(call.argsJson))
    ExecutionLedger.cached(sessionId, call.name, h)?.let { prev ->
        ExecutionLedger.record(sessionId, call.name, h, true, 0, prev, reused = true)
        return ToolResult("[Resultado reaproveitado: você já fez esta mesma chamada nesta execução e nada mudou]\n$prev")
    }
    val t0 = System.currentTimeMillis()
    val res = kotlinx.coroutines.withTimeoutOrNull(15 * 60_000L) { block() }
        ?: ToolResult("ERRO: ${call.name} passou de 15 min e foi interrompida pelo orquestrador. Verifique o estado (arquivo, página, saída) antes de tentar de novo.")
    val ok = !res.output.trimStart().let { it.startsWith("ERRO") || it.startsWith("ERROR") || it.startsWith("RECUSADO") || it.startsWith("❌") }
    ExecutionLedger.record(sessionId, call.name, h, ok, System.currentTimeMillis() - t0, res.output)
    return res
}

// ───────── Ferramentas novas fora do runTool (o método já está perto do limite de 64 KB da JVM) ─────────
internal suspend fun AgentRunner.extraOutputTool(sessionId: String, name: String, a: JsonObject): String {
    fun s(k: String) = a[k]?.jsonPrimitive?.contentOrNull.orEmpty()
    return when (name) {
        "reply_audio" -> {
            // "always": o usuário pediu para responder sempre (ou não mais) em áudio nesta conversa.
            a["always"]?.jsonPrimitive?.booleanOrNull?.let { on -> store.editSession(sessionId) { it.copy(voiceReplies = on) } }
            if (s("text").isBlank()) return if (store.session(sessionId)?.voiceReplies == true) "Modo áudio ligado: as próximas respostas finais virão em áudio automaticamente." else "Modo áudio desligado."
            val audio = dev.fluwy.app.assistant.VoiceReplies.synthesize(ctx, java.io.File(sandbox.workspaceFor(sessionId), "audio"), s("text"))
            if (audio == null) "ERRO: o motor de voz do aparelho não está disponível (sem voz pt-BR instalada). Responda em texto e avise o usuário."
            else { store.addItem(sessionId, audio); "Áudio enviado no chat (${audio.durationMs / 1000}s). Encerre sem escrever mais nada: o usuário já recebeu a resposta em áudio." }
        }
        "notify" -> {
            val p = runCatching { dev.fluwy.app.notify.NotificationCenter.Priority.valueOf(s("priority").uppercase()) }.getOrDefault(dev.fluwy.app.notify.NotificationCenter.Priority.NORMAL)
            val agentName = store.session(sessionId)?.agentId?.let { store.agent(it)?.name }.orEmpty()
            dev.fluwy.app.notify.NotificationCenter.post(s("title").ifBlank { agentName.ifBlank { "Fluwy Bot" } }, s("text"), p, dev.fluwy.app.notify.NotificationCenter.Category.MONITOR, sessionId, agentName)
            if (p == dev.fluwy.app.notify.NotificationCenter.Priority.SILENT) "Registrado no histórico de avisos (sem interromper o usuário)." else "Aviso enviado (${p.name.lowercase()})."
        }
        "show_cards" -> {
            val parsed = ResultCardsParser.parse(s("kind"), s("title"), a["items"])
            if (parsed.isFailure) "ERRO: ${parsed.exceptionOrNull()?.message}" else {
                // Card sem foto: o app busca a imagem principal da página do item (og:image) antes de mostrar.
                val item = CardImages.fill(parsed.getOrThrow()) { url -> dev.fluwy.app.web.WebTools.pageImage(url) }
                store.addItem(sessionId, item)
                "Mostrei ${item.items.size} card(s) no chat" + (item.items.count { it.image.isBlank() }.takeIf { it > 0 }?.let { " ($it sem imagem: na próxima, mande 'image' com a URL da foto que aparece no resultado)" } ?: "") + ". Não repita a lista em texto: escreva no máximo 2 frases (sua recomendação e o próximo passo)." +
                    (if (item.kind == "product") " Se o usuário tocar num card, a escolha chega como [Seleção]." else "")
            }
        }
        "cofre_listar" -> dev.fluwy.app.vault.VaultTools.list(ctx)
        "pix_qr" -> {
            val (item, msg) = dev.fluwy.app.vault.VaultTools.pixQr(ctx, s("chave"), s("valor"), s("descricao"))
            if (item != null) store.addItem(sessionId, item)
            msg
        }
        else -> "ERRO: ferramenta desconhecida."
    }
}

internal suspend fun AgentRunner.browserReadTool(sessionId: String, maxChars: Int, model: String): String {
    val host = browserFor(sessionId)
    val out = host.read(maxChars)
    // OpenBot 0.70: página que abriu sem texto nem controles (canvas/JS) → print automático descrito pelo modelo.
    return if (!out.contains(dev.fluwy.app.browser.BrowserHost.EMPTY_PAGE_MARK)) out else {
        val jpeg = runCatching { host.captureJpeg() }.getOrNull()
        val seen = if (jpeg == null) null else try {
            runCatching { llm.inspectImage("Esta página do navegador carregou sem texto legível para leitura. Descreva em português o que aparece na tela (textos visíveis, botões, imagens, mensagens de erro ou de carregamento). Seja objetivo.", jpeg, model = model, conversationId = sessionId) }.getOrNull()
        } finally { jpeg.fill(0) }
        out + "\n\n--- Print automático (a página não tem texto legível) ---\n" +
            (seen?.takeIf { it.isNotBlank() } ?: "Não foi possível descrever o print (modelo sem visão ou captura vazia). Espere alguns segundos e leia de novo, ou role a página.")
    }
}

internal suspend fun AgentRunner.triggerTool(sessionId: String, name: String, a: JsonObject): String {
    fun s(k: String) = a[k]?.jsonPrimitive?.contentOrNull.orEmpty()
    fun i(k: String) = a[k]?.jsonPrimitive?.intOrNull
    when (name) {
        "trigger_create" -> {
            val fonte = s("fonte").ifBlank { "connector" }
            if (fonte == "connector" && (s("conector").isBlank() || s("acao").isBlank())) return ("ERRO: fonte connector precisa de conector e acao (de leitura).")
            if (fonte == "site" && !s("url").startsWith("http")) return ("ERRO: fonte site precisa de url http(s).")
            val campos = dev.fluwy.app.connectors.HubConnectors.fields(a["campos"]) ?: return ("ERRO: campos precisa ser objeto JSON.")
            val t = dev.fluwy.app.triggers.Trigger(name = s("nome").take(60), sessionId = sessionId, agentId = store.session(sessionId)?.agentId,
                source = fonte, piece = s("conector"), action = s("acao"), input = campos.toString(), connection = s("conexao"), url = s("url"),
                filter = s("filtro").take(200), instruction = s("instrucao").take(800), intervalMinutes = (i("intervalo_min") ?: 15).coerceIn(15, 1440))
            dev.fluwy.app.triggers.TriggerStore.upsert(ctx, t)
            dev.fluwy.app.triggers.TriggerEngine.schedule(ctx, t)
            val first = dev.fluwy.app.triggers.TriggerEngine.check(ctx.applicationContext as dev.fluwy.app.FluwyApp, t.id)
            return ("Gatilho \"${t.name}\" criado (id ${t.id}), verifica a cada ${t.intervalMinutes} min e acorda esta conversa com os eventos novos. 1ª verificação: $first. O usuário gerencia em Agenda › Gatilhos.")
        }
        "trigger_list" -> {
            val mine = dev.fluwy.app.triggers.TriggerStore.load(ctx).filter { it.sessionId == sessionId }
            return (if (mine.isEmpty()) "Nenhum gatilho nesta conversa." else mine.joinToString("\n") { t ->
                "- ${t.id} · ${t.name} · ${if (t.enabled) "ativo" else "pausado"} · a cada ${t.intervalMinutes} min · " +
                    (if (t.source == "site") t.url else "${t.piece}/${t.action}") + (t.lastError?.let { " · último erro: ${it.take(100)}" } ?: "") +
                    " · eventos: ${t.history.sumOf { it.count }}"
            })
        }
        "trigger_delete" -> {
            val t = dev.fluwy.app.triggers.TriggerStore.get(ctx, s("id")) ?: return ("ERRO: gatilho não encontrado (veja trigger_list).")
            dev.fluwy.app.triggers.TriggerEngine.cancel(ctx, t.id); dev.fluwy.app.triggers.TriggerStore.remove(ctx, t.id)
            return ("Gatilho ${t.name} removido.")
        }
    }
    return "ERRO: ferramenta desconhecida."
}

/** Remove marcações Markdown para mostrar em notificação (texto puro). */
internal fun AgentRunner.plainText(md: String): String = md
    .replace(Regex("```[\\s\\S]*?```"), " ")
    .replace(Regex("[*_`#>]"), "")
    .replace(Regex("\\[(.*?)\\]\\(.*?\\)"), "$1")
    .replace(Regex("\\s+"), " ").trim()

internal fun AgentRunner.notifyRaw(title: String, text: String) = runCatching {
    dev.fluwy.app.notify.NotificationCenter.post(title, text, dev.fluwy.app.notify.NotificationCenter.Priority.NORMAL, dev.fluwy.app.notify.NotificationCenter.Category.DONE)
}

internal fun AgentRunner.notifyAgent(a: AgentProfile, text: String) = runCatching {
    dev.fluwy.app.notify.NotificationCenter.post(a.name, text.ifBlank { "Concluiu uma ação" }, dev.fluwy.app.notify.NotificationCenter.Priority.NORMAL, dev.fluwy.app.notify.NotificationCenter.Category.DONE, a.homeSessionId, a.name)
}

// ------------------------------------------------------------------ planejamento de contexto
/** Usa o modelo do agente quando definido; caso contrário, o modelo padrão. */
/** Credenciais dos canais de envio (Configurações › Canais de envio de arquivos). */
internal fun AgentRunner.channelConfig() = store.settings.let { s ->
    dev.fluwy.app.share.ChannelSender.Config(s.telegramBotToken, s.telegramChatId, s.discordWebhookUrl, s.slackBotToken, s.slackChannelId)
}

/** Alternativos do momento: os provedores da cadeia (cada um com seu modelo) + alternativos por modelo. */
internal fun AgentRunner.fallbackChain(): String = dev.fluwy.app.llm.ProviderChain.fallback(store.settings)

/**
 * Plano B da leitura/busca direta: abre no browser do agente e lê a página. Se o agente não usa browser ou o browser
 * não está disponível, devolve o erro original com a explicação.
 */
internal suspend fun AgentRunner.browserFallback(sessionId: String, target: String, directResult: String, maxChars: Int): String {
    val agent = store.agent(store.session(sessionId)?.agentId)
    if (agent?.useBrowser == false) return directResult
    setActivity(sessionId, Activity(Activity.Kind.BROWSER, "Leitura direta falhou: abrindo no browser"))
    val br = browserFor(sessionId)
    val nav = runCatching { br.navigate(target) }.getOrElse { "ERROR: ${it.javaClass.simpleName}" }
    if (nav.startsWith("ERROR") || nav.contains("browser indisponível")) return directResult + "\n\n[O browser também não abriu: ${nav.take(200)}]"
    BrowserVisitLog.record(sessionId, target, nav)
    val page = runCatching { br.read(maxChars, trackRepeat = false) }.getOrDefault("")
    return "[A leitura direta não funcionou (${directResult.lineSequence().first().take(140)}); abri automaticamente no browser:]\n" + nav.take(300) + "\n\n" + page
}

/** Espaço das páginas: a seção inteira compartilha (chat do coordenador); fora de seção, o próprio chat. */
internal fun AgentRunner.pageSpace(sessionId: String): String = store.section(store.session(sessionId)?.sectionId)?.sessionId ?: sessionId

/**
 * Instalação feita pelo app (determinística, como o OpenBot faz antes de entregar a skill ao agente): mostra
 * "Instalando skill", baixa/valida/salva e dá o feedback no chat. Devolve a instrução para o agente continuar
 * (setup e o resto do pedido) ou null quando o pedido era só instalar — ou quando falhou.
 */
internal suspend fun AgentRunner.runSkillInstall(sessionId: String, intent: dev.fluwy.app.skills.SkillInstallIntent.Intent): String? {
    setActivity(sessionId, Activity(Activity.Kind.THINKING, "Instalando skill"))
    val step = ChatItem.Step(tool = "skill_install", title = "Instalando skill", args = intent.source, result = "", status = StepStatus.RUNNING)
    store.addItem(sessionId, step)
    val fetched = runCatching { dev.fluwy.app.skills.SkillInstaller.fetch(intent.source, intent.name, sandbox.workspaceFor(sessionId)) }
    val list = fetched.getOrElse { e ->
        runCatching { dev.fluwy.app.diag.ErrorLog.log("skill", "Instalação de ${intent.source} falhou: ${e::class.java.simpleName}: ${e.message}\n${e.stackTraceToString().take(1500)}", sessionId, "skill_install") }
        store.replaceItem(sessionId, step.copy(status = StepStatus.ERROR, result = e.message.orEmpty().take(300)))
        val msg = "Não consegui instalar a skill de ${intent.source}: ${e.message}.\n\nFormatos aceitos: link do GitHub (owner/repo ou a pasta da skill), URL de um SKILL.md, arquivo .zip (link ou anexo) ou página que tenha um link para SKILL.md."
        store.addItem(sessionId, ChatItem.Assistant(text = msg, final = true))
        store.editSession(sessionId) { it.copy(llm = it.llm + LlmMessage("assistant", msg), status = SessionStatus.DONE) }
        return null
    }
    val installed = list.map { f -> f to installSkill(sessionId, f) }
    store.replaceItem(sessionId, step.copy(status = StepStatus.OK, result = installed.joinToString { it.second.first.name } + " instalada" + if (installed.size > 1) "s" else ""))
    val report = installed.joinToString("\n\n") { (f, pair) ->
        val (sk, written) = pair
        "✅ Skill **${sk.name}** instalada e ativa\n" +
            "- ${sk.description.take(220)}\n" +
            "- Origem: ${f.source}\n" +
            "- Arquivos (${written.size}): ${written.take(8).joinToString()}${if (written.size > 8) "…" else ""} → /root/workspace/skills/${sk.name}/\n" +
            (if (f.meta.problems.isNotEmpty()) "- Validação: " + f.meta.problems.joinToString("; ") + "\n" else "") +
            (if (f.warnings.isNotEmpty()) "- ⚠ Atenção: " + f.warnings.joinToString("; ") + " (vou pedir confirmação antes de rodar isso)\n" else "") +
            "- Para usar: mencione @${sk.name} ou peça a tarefa normalmente."
    }
    store.addItem(sessionId, ChatItem.Assistant(text = report, final = !intent.followUp))
    store.editSession(sessionId) { it.copy(llm = it.llm + LlmMessage("assistant", report)) }
    if (!intent.followUp) {
        store.editSession(sessionId) { it.copy(status = SessionStatus.DONE) }
        return null
    }
    val main = installed.first()
    return "[Sistema] O app já instalou a skill \"${main.second.first.name}\" em /root/workspace/skills/${main.second.first.name}/ (SKILL.md + arquivos). " +
        "Não reinstale, não baixe o SKILL.md de novo e não procure a skill em outro lugar. Agora conclua o resto do pedido do usuário seguindo o SKILL.md abaixo " +
        "(seção de setup/instalação/uso), passo a passo, conferindo cada resultado. \"Minha máquina\"/\"no meu computador\" = este aparelho: comandos no terminal Linux do app (shell; ~ é /root) " +
        "e servidores em localhost abrem no browser do app. Se faltar algo que só o usuário tem (conta, chave, programa), diga exatamente o que falta." +
        (if (main.first.warnings.isNotEmpty()) " ⚠ Antes de rodar comandos marcados como arriscado (${main.first.warnings.joinToString()}), peça confirmação." else "") +
        "\n\n--- SKILL.md ---\n" + main.first.meta.body.take(10_000)
}

/** Caminho em ~/… ou /root/… dentro do sandbox desta conversa (somente leitura). Null se sair dele. */
internal fun AgentRunner.sandboxHomeFile(sessionId: String, raw: String): java.io.File? {
    val p = raw.trim().let { if (it.startsWith("~/")) "/root/" + it.removePrefix("~/") else if (it == "~") "/root" else it }
    if (!p.startsWith("/root") || p.startsWith("/root/connectors")) return null
    return runCatching {
        val home = sandbox.hostFile("/root", sessionId).canonicalFile
        sandbox.hostFile(p, sessionId).canonicalFile.takeIf { it.path == home.path || it.path.startsWith(home.path + java.io.File.separator) }
    }.getOrNull()
}

/**
 * Pedido de login ao usuário (takeover do Manus): um cartão no chat por domínio, com "Entrar e salvar". Se já havia
 * uma sessão salva para o site, ela é marcada como expirada. Depois de salvar, a tarefa é retomada sozinha.
 */
internal fun AgentRunner.requestUserLogin(sessionId: String) {
    val url = runCatching { browserFor(sessionId).ui.value.url }.getOrNull().orEmpty()
    val domain = dev.fluwy.app.browser.BrowserPolicies.hostOf(url).removePrefix("www.")
    if (domain.isBlank()) return
    val items = store.session(sessionId)?.items.orEmpty()
    if (items.takeLast(40).any { it is ChatItem.Step && it.tool == "login_needed" && it.args.contains(domain) }) return
    val existing = store.state.value.browserLogins.firstOrNull { dev.fluwy.app.browser.BrowserPolicies.domainMatches(domain, it.domain) }
    if (existing != null && existing.status == "active") store.editBrowserLogin(existing.id) { it.copy(status = "expired") }
    val login = existing ?: dev.fluwy.app.data.BrowserLogin(name = domain, url = "https://$domain", domain = domain, status = "expired").also { store.upsertBrowserLogin(it) }
    store.addItem(sessionId, ChatItem.Step(tool = "login_needed", title = if (existing != null) "A sessão de $domain expirou" else "$domain pede login",
        args = "${login.id}|$domain", result = "Toque para entrar com a sua conta; a senha fica só no site.", status = StepStatus.OK))
    runCatching { notifyLoginNeeded(sessionId, domain) }
}

internal fun AgentRunner.notifyLoginNeeded(sessionId: String, domain: String) {
    val intent = android.content.Intent(ctx, dev.fluwy.app.MainActivity::class.java)
        .setAction(dev.fluwy.app.SessionNotificationRouting.ACTION_OPEN_SESSION)
        .putExtra(dev.fluwy.app.SessionNotificationRouting.EXTRA_SESSION, sessionId)
        .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
    val pi = android.app.PendingIntent.getActivity(ctx, domain.hashCode(), intent, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
    val n = androidx.core.app.NotificationCompat.Builder(ctx, dev.fluwy.app.FluwyApp.CH_TASKS).setSmallIcon(android.R.drawable.ic_lock_idle_lock)
        .setContentTitle("Login necessário: $domain").setContentText("Entre com a sua conta para o agente continuar.").setContentIntent(pi).setAutoCancel(true).build()
    androidx.core.app.NotificationManagerCompat.from(ctx).notify(domain.hashCode(), n)
}

/** Pasta do app onde ficam os arquivos das skills instaladas. */
internal fun AgentRunner.skillStore(name: String) = java.io.File(ctx.filesDir, "skills/" + dev.fluwy.app.skills.SkillPackage.slugify(name))

/** Salva/atualiza a skill (mantém o histórico) e copia os arquivos para o workspace da conversa. */
internal fun AgentRunner.installSkill(sessionId: String, f: dev.fluwy.app.skills.SkillInstaller.Fetched): Pair<Skill, List<String>> {
    val existing = store.state.value.skills.firstOrNull { !it.builtIn && it.name.equals(f.meta.name, true) }
    val written = dev.fluwy.app.skills.SkillInstaller.writeFolder(skillStore(f.meta.name), f)
    val sk = Skill(id = existing?.id ?: dev.fluwy.app.data.newId(), name = f.meta.name, description = f.meta.description,
        instructions = f.meta.body, enabled = true, source = f.source, files = written,
        revisions = existing?.let { (listOf(it.instructions) + it.revisions).take(10) }.orEmpty())
    store.upsertSkill(sk)
    ensureSkillFiles(sessionId, sk)
    return sk to written
}

/** Copia os arquivos da skill para /root/workspace/skills/<nome>/ (para scripts/templates funcionarem nesta conversa). */
internal fun AgentRunner.ensureSkillFiles(sessionId: String, sk: Skill) {
    if (sk.source.isBlank() && sk.files.isEmpty()) return  // skill criada no app: só instruções
    runCatching {
        val src = skillStore(sk.name).takeIf { it.isDirectory } ?: return
        val dst = java.io.File(sandbox.workspaceFor(sessionId), "skills/" + dev.fluwy.app.skills.SkillPackage.slugify(sk.name))
        src.copyRecursively(dst, overwrite = true)
        dst.walkTopDown().filter { it.isFile && (it.name.endsWith(".sh") || it.name.endsWith(".py")) }.forEach { it.setExecutable(true) }
    }
}

/** Marca da tarefa atual: muda a cada mensagem REAL do usuário (mensagens do coordenador/sistema não zeram a conta). */
internal fun AgentRunner.taskKey(sessionId: String): String {
    val items = store.session(sessionId)?.items.orEmpty()
    val last = items.lastOrNull { it is ChatItem.User && !it.text.startsWith("[") } as? ChatItem.User
    return last?.id ?: "none"
}

internal fun AgentRunner.readCache(sessionId: String, f: java.io.File, offset: Int, limit: Int): String? {
    val key = "$sessionId|${f.absolutePath}|$offset|$limit"
    val stamp = "${f.lastModified()}:${f.length()}:${store.session(sessionId)?.compactions ?: 0}:${ctxMgr.clearEpochOf(sessionId)}"
    if (fileReadSeen.put(key, stamp) == stamp)
        return "[Você já leu exatamente este trecho nesta tarefa e o arquivo não mudou. Use o conteúdo que já está no contexto; para outra parte, mude offset, ou ache a linha com file_search.]"
    // Releitura com outro offset/limite de linhas que JÁ foram lidas (arquivo igual, contexto não limpo): não lê de novo.
    val covKey = "$sessionId|${f.absolutePath}"
    val cov = readCoverage[covKey]?.takeIf { it.first == stamp }?.second
    val want = offset.coerceAtLeast(1)..(offset.coerceAtLeast(1) + limit.coerceIn(1, 1000) - 1)
    val lines = runCatching { f.useLines { it.count() } }.getOrDefault(Int.MAX_VALUE)
    val wantEnd = minOf(want.last, lines)
    if (cov != null && wantEnd >= want.first && (want.first..wantEnd).all { n -> cov.any { n in it } }) {
        val n = redundantReads.merge(covKey, 1, Int::plus) ?: 1
        if (n >= 3) flagRedundantReads(sessionId, f.name, n)
        return "[Releitura desnecessária ($n): as linhas ${want.first}–$wantEnd de ${f.name} já foram lidas nesta tarefa e o arquivo não mudou. " +
            "O conteúdo está no seu contexto/anotações. Pare de reler e avance para a entrega; leia só linhas ainda não lidas.]"
    }
    pendingCoverage = covKey to stamp
    return null
}

/**
 * Orquestração: agente de uma seção relendo o mesmo conteúdo várias vezes → o coordenador é avisado para intervir
 * (redirecionar ou encerrar a tarefa dele). Fora de seção, o próprio aviso da ferramenta já corta o ciclo.
 */
internal fun AgentRunner.flagRedundantReads(sessionId: String, file: String, n: Int) {
    val s = store.session(sessionId) ?: return
    val sec = store.section(s.sectionId) ?: return
    if (sec.sessionId == sessionId || n % 3 != 0) return
    val who = store.agent(s.agentId)?.name ?: "Um agente"
    store.editSession(sec.sessionId) { it.copy(llm = it.llm + LlmMessage("user",
        dev.fluwy.app.prompts.Prompts.render("aux.2", mapOf<String, Any?>("who" to (who), "file" to (file), "valor" to (n), "lb" to "{{")))) }
    runCatching { dev.fluwy.app.diag.ActivityLog.write("orq", dev.fluwy.app.prompts.Prompts.render("aux.1", mapOf<String, Any?>("who" to (who), "file" to (file), "valor" to (n), "lb" to "{{")), sessionId) }
}

/** Ferramentas do criador de user scripts (fora do runTool: o método está no limite de tamanho da JVM). */
internal suspend fun AgentRunner.userscriptTool(sessionId: String, name: String, a: JsonObject): ToolResult {
    fun s(k: String) = a[k]?.jsonPrimitive?.contentOrNull.orEmpty()
    when (name) {
        "userscript_test" -> {
            val code = s("code"); val url = s("url").trim(); val verify = s("verify").trim().trimEnd(';')
            val (meta, err) = dev.fluwy.app.browser.UserScriptMeta.parse(code)
            if (meta == null) return ToolResult("ERRO no cabeçalho: $err\nFormato:\n${dev.fluwy.app.browser.UserScriptMeta.EXAMPLE}")
            if (verify.isBlank()) return ToolResult("ERRO: informe verify — uma expressão JS que prove o efeito do script na página.")
            if (!dev.fluwy.app.browser.UserScriptMatcher.matches(meta, url))
                return ToolResult("ERRO: a url $url não casa com @match/@include (${meta.whereLabel}). Ajuste o @match ou teste numa página certa.")
            val policy = scriptPolicy(sessionId)
            (policy.beforeTest(code) as? ScriptBuilderPolicy.Gate.Deny)?.let { deny ->
                if (deny.stop) { policy.newRound(); return ToolResult(deny.message, SessionStatus.WAITING_USER) }
                return ToolResult(deny.message)
            }
            val requireCode = if (meta.requires.isEmpty()) "" else runCatching { dev.fluwy.app.browser.UserScriptInstaller.downloadRequires(meta.requires) }
                .getOrElse { return ToolResult("ERRO: não consegui baixar um @require: ${it.message}") }
            val nav = browserFor(sessionId).navigate(BrowserVisitLog.canonical(url))
            if (nav.startsWith("ERROR")) return ToolResult(nav)
            val r = browserFor(sessionId).runUserScriptForTest(dev.fluwy.app.browser.BrowserPolicies.fromMeta(meta, code, requireCode, "ia", null), verify)
            val passed = ScriptBuilderPolicy.passed(r.sync, r.asyncErrors, r.verifyTruthy)
            policy.afterTest(code, passed)
            return ToolResult(buildString {
                appendLine(if (passed) "PASSOU ✓ (teste ${policy.testsRun}/${ScriptBuilderPolicy.MAX_TESTS}). Chame userscript_propose com ESTE MESMO código."
                    else "FALHOU ✕ (teste ${policy.testsRun}/${ScriptBuilderPolicy.MAX_TESTS}).")
                appendLine("Execução: " + if (r.sync == "ok") "sem erro síncrono" else r.sync.removePrefix("err:"))
                if (r.asyncErrors.isNotEmpty()) appendLine("Erros na página após o script: " + r.asyncErrors.joinToString(" | "))
                if (r.logs.isNotEmpty()) appendLine("GM_log: " + r.logs.joinToString(" | "))
                appendLine("verify → " + (r.verifyError?.let { "ERRO: $it" } ?: "${r.verifyValue} (${if (r.verifyTruthy) "verdadeiro" else "falso/vazio"})"))
                if (!passed) append("Diagnostique a causa exata antes de mudar o código: use userscript_inspect/browser_js no ponto que falhou (o seletor acha algo? o elemento existe quando o script roda?).")
            }.trimEnd())
        }
        "userscript_propose" -> {
            val code = s("code")
            val (meta, err) = dev.fluwy.app.browser.UserScriptMeta.parse(code)
            if (meta == null) return ToolResult("ERRO no cabeçalho: $err")
            if (!scriptPolicy(sessionId).canPropose(code))
                return ToolResult("RECUSADO: este código não é o que passou no userscript_test. Teste esta versão (com verify) e proponha exatamente o código aprovado.")
            store.addItem(sessionId, ChatItem.UserScriptProposal(name = meta.name, version = meta.version, description = meta.description,
                where = meta.whereLabel, code = code))
            return ToolResult("Card \"Instalar no Browser\" exibido. Diga ao usuário em 1-2 frases o que o script faz e onde roda, e finalize.")
        }
    }
    return ToolResult("ERRO: ferramenta desconhecida $name")
}
