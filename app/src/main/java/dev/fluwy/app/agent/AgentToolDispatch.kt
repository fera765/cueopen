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

internal suspend fun AgentRunner.runTool(
    sessionId: String,
    call: ToolCall,
    allowDeviceTools: Boolean,
    model: String,
    visualInspectionUsed: MutableSet<String>,
    accessibilityReadScreenCompleted: MutableSet<String>,
): ToolResult {
    val editSession = store.session(sessionId)?.takeIf { it.connectorEditId != null }
    if (editSession != null) {
        val browserEnabled = store.agent(editSession.agentId)?.useBrowser != false
        if (!ConnectorEditToolPolicy.allows(call.name, browserEnabled))
            return ToolResult("RECUSADO: este chat só pode consultar páginas públicas no browser e preparar uma atualização para o conector selecionado.")
    }
    val parsed = runCatching { json.parseToJsonElement(call.argsJson).jsonObject }
    if (parsed.isFailure && call.argsJson.isNotBlank() && call.argsJson != "{}") {
        val safeError = if (call.name == "connector_edit_propose") "A proposta chegou incompleta; tente novamente com uma alteração menor." else call.argsJson.takeLast(300)
        store.addItem(sessionId, ChatItem.Step(tool = call.name, title = "Argumentos cortados", args = call.name, result = safeError, status = StepStatus.ERROR))
        if (call.name == "connector_edit_propose") return ToolResult("ERRO: a proposta chegou incompleta. Envie uma alteração menor; o conteúdo parcial não foi exibido nem aplicado.")
        return ToolResult("ERRO: os argumentos de ${call.name} chegaram incompletos (sua resposta foi cortada por tamanho). " +
            "Para arquivos grandes use file_write em partes: a 1ª com append=false e as seguintes com append=true, até ~150 linhas por chamada.")
    }
    val a: JsonObject = parsed.getOrDefault(JsonObject(emptyMap()))
    fun s(k: String) = a[k]?.jsonPrimitive?.contentOrNull.orEmpty()
    dev.fluwy.app.device.DeviceCapPolicy.requiredCapability(call.name)?.let { capability ->
        if (!allowDeviceTools)
            return ToolResult("RECUSADO: ferramentas Android não estão disponíveis nesta execução de worker.")
        val agent = store.agent(store.session(sessionId)?.agentId)
        if (agent == null || capability !in agent.deviceCaps)
            return ToolResult("RECUSADO: este agente não tem opt-in para '${dev.fluwy.app.device.DeviceCaps.all.firstOrNull { it.id == capability }?.title ?: capability}'.")
        if (dev.fluwy.app.device.DeviceCapPolicy.requiresSystemGrant(call.name) &&
            !dev.fluwy.app.device.DeviceCaps.granted(ctx, capability))
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
        "create_section" -> return createSection(sessionId, s("name"), s("description"), s("coordinator_instructions"), s("agents_json"),
            s("first_task"), s("suggested_android_caps"), b("suggest_wallet"), b("autonomous"), i("schedule_minutes") ?: 0)
        "team_delegate" -> {
            val sec = store.section(store.session(sessionId)?.sectionId)
            val goal = sec?.planGoal?.ifBlank { null } ?: store.session(sessionId)?.llm?.lastOrNull { it.role == "user" && it.content?.startsWith("[") != true }?.content?.take(600)
            val brief = buildString {
                append(s("task"))
                if (s("output_format").isNotBlank()) append("\n\nFormato da entrega: ").append(s("output_format"))
                if (s("tools_hint").isNotBlank()) append("\nFerramentas/fontes indicadas: ").append(s("tools_hint"))
                if (s("boundaries").isNotBlank()) append("\nLimites (não faça): ").append(s("boundaries"))
                if (!goal.isNullOrBlank()) append("\n\n[Objetivo geral do time, para contexto — sua parte é só a tarefa acima]: ").append(goal)
                append("\n\nEntregue: material completo em arquivo no workspace + resumo curto (até ~250 palavras) com os achados principais e o caminho do arquivo.")
            }
            return ToolResult(teamDelegate(sessionId, s("agent"), brief, b("wait"), (i("timeout_minutes") ?: 15).coerceIn(1, 30)))
        }
        "team_status" -> return ToolResult(teamStatus(sessionId))
        "team_plan" -> return ToolResult(teamPlan(sessionId, s("goal"), s("tasks_json")))
        "team_reassign" -> return ToolResult(teamReassign(sessionId, s("task_id"), s("agent"), s("note")))
        "section_done" -> {
            val sec = store.section(store.session(sessionId)?.sectionId) ?: return ToolResult("ERRO: esta conversa não é uma seção.")
            val open = sec.plan.filter { it.status == TaskStatus.PENDING || it.status == TaskStatus.RUNNING || it.status == TaskStatus.REVIEW }
            if (open.isNotEmpty()) return ToolResult("RECUSADO: ainda há etapas em aberto no plano (${open.joinToString { it.id }}). Conclua/valide essas etapas (ou cancele com team_review) antes de encerrar.")
            autoDone.add(sec.id)
            val summary = s("summary").ifBlank { "Objetivo da seção concluído." }
            // O coordenador costuma escrever o relatório no chat e depois repeti-lo aqui: não mostra duas vezes.
            if (!alreadyReported(sessionId, summary)) store.addItem(sessionId, ChatItem.Assistant(text = summary, final = true))
            return ToolResult("ok", SessionStatus.DONE)
        }
        "userscript_inspect" -> {
            val url = s("url").trim()
            if (url.isNotBlank()) {
                val current = runCatching { browserFor(sessionId).ui.value.url }.getOrDefault("")
                if (BrowserVisitLog.canonical(url) != BrowserVisitLog.canonical(current)) {
                    val nav = browserFor(sessionId).navigate(BrowserVisitLog.canonical(url))
                    if (nav.startsWith("ERROR")) return ToolResult(nav)
                }
            }
            val selectors = s("selectors").split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            val out = browserFor(sessionId).inspectForScript(selectors, s("text").trim())
            if (!out.startsWith("ERROR")) scriptPolicy(sessionId).onInspect()
            return ToolResult(out + if (selectors.isEmpty() && s("text").isBlank()) "\n(Dica: passe selectors e/ou text para localizar o alvo.)" else "")
        }
        "webapi_list", "webapi_call", "webapi_test", "webapi_save", "webapi_probe" -> return webApiToolWithPill(sessionId, call.name, a)
        "userscript_test", "userscript_propose" -> return userscriptTool(sessionId, call.name, a)
        "team_review" -> return ToolResult(teamReview(sessionId, s("task_id"), s("verdict"), s("note")))
        "team_message" -> return ToolResult(teamMessage(sessionId, s("agent"), s("message")))
        "team_stop" -> return ToolResult(teamStop(sessionId, s("agent"), s("reason")))
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
        "trigger_create", "trigger_list", "trigger_delete" -> return ToolResult(triggerTool(sessionId, call.name, a))
        "cron_add" -> {
            val title = s("title").trim().take(60); val prompt = s("prompt").trim()
            if (title.isBlank() || prompt.isBlank()) return ToolResult("ERRO: informe title e prompt.")
            val at = s("at").trim()
            val now = java.util.Calendar.getInstance()
            val start: Long? = runCatching {
                if (Regex("^\\d\\d?:\\d\\d$").matches(at)) {
                    val (h, min) = at.split(":").map { it.toInt() }
                    require(h in 0..23 && min in 0..59)
                    (now.clone() as java.util.Calendar).apply {
                        set(java.util.Calendar.HOUR_OF_DAY, h); set(java.util.Calendar.MINUTE, min); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
                        if (timeInMillis <= now.timeInMillis + 30_000) add(java.util.Calendar.DAY_OF_YEAR, 1)
                    }.timeInMillis
                } else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { isLenient = false }.parse(at.replace('T', ' ').take(16))!!.time
            }.getOrNull()
            if (start == null) return ToolResult("ERRO: horário inválido em at (recebido: \"${at.take(40)}\"). Use \"HH:mm\" ou \"yyyy-MM-dd HH:mm\", no horário local.")
            if (start < System.currentTimeMillis() - 60_000) return ToolResult("ERRO: o horário informado já passou. Informe um horário futuro.")
            val repeat = when (s("repeat").trim().lowercase()) {
                "once" -> dev.fluwy.app.data.Repeat.ONCE; "half_hour" -> dev.fluwy.app.data.Repeat.HALF_HOUR; "hourly" -> dev.fluwy.app.data.Repeat.HOURLY
                "weekly" -> dev.fluwy.app.data.Repeat.WEEKLY; "", "daily" -> dev.fluwy.app.data.Repeat.DAILY
                else -> return ToolResult("ERRO: repeat deve ser once, half_hour, hourly, daily ou weekly.")
            }
            if (store.state.value.scheduled.count { it.enabled } >= 30) return ToolResult("RECUSADO: já existem 30 tarefas agendadas ligadas. Remova alguma com cron_remove antes de criar outra.")
            val task = dev.fluwy.app.data.ScheduledTask(title = title, prompt = prompt, agentId = store.session(sessionId)?.agentId,
                repeat = repeat, startAt = start, sourceSessionId = sessionId)
            store.upsertScheduled(task)
            dev.fluwy.app.scheduler.Scheduler.schedule(ctx, task)
            val whenTxt = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(start))
            store.addItem(sessionId, ChatItem.Step(tool = "cron_add", title = "Agendado: $title", args = "$whenTxt · ${repeat.name.lowercase()}", result = "", status = StepStatus.OK))
            return ToolResult("Tarefa agendada (id ${task.id}): primeiro disparo em $whenTxt, repetição ${repeat.name.lowercase()}. Ela roda numa conversa nova a cada disparo e aparece em Agendadas. O Android pode atrasar alguns minutos por economia de bateria.")
        }
        "cron_list" -> {
            val fmt = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
            val list = store.state.value.scheduled
            return ToolResult(if (list.isEmpty()) "Nenhuma tarefa agendada." else list.joinToString("\n") { t ->
                "- ${t.id} · ${t.title} · ${t.repeat.name.lowercase()} · início ${fmt.format(Date(t.startAt))} · último disparo ${t.lastRunAt?.let { fmt.format(Date(it)) } ?: "nunca"} · ${if (t.enabled) "ligada" else "desligada"}"
            })
        }
        "cron_remove" -> {
            val id = s("id").trim()
            val t = store.state.value.scheduled.firstOrNull { it.id == id } ?: return ToolResult("ERRO: tarefa agendada não encontrada. Confira o id com cron_list.")
            dev.fluwy.app.scheduler.Scheduler.cancel(ctx, t.id)
            store.deleteScheduled(t.id)
            return ToolResult("Tarefa agendada \"${t.title}\" removida.")
        }
        "team_peer_message" -> return ToolResult(teamPeerMessage(sessionId, s("to"), s("message")))
        "skill_list" -> return ToolResult(store.state.value.skills.joinToString("\n") { sk ->
            "- ${sk.name}: ${sk.description.take(140)} [${if (sk.enabled) "ativa" else "desativada"}${if (sk.builtIn) ", embutida" else ""}${if (sk.revisions.isNotEmpty()) ", ${sk.revisions.size} versão(ões) anterior(es)" else ""}]"
        }.ifBlank { "Nenhuma skill na biblioteca." })
        "skill_revise" -> {
            val name = s("name").trim(); val instructions = s("instructions").trim()
            if (name.isBlank() || instructions.isBlank()) return ToolResult("ERRO: informe name e instructions.")
            val sk = store.state.value.skills.firstOrNull { it.name.equals(name, true) }
                ?: return ToolResult("ERRO: skill \"$name\" não encontrada. Use skill_list para ver os nomes exatos.")
            if (sk.builtIn) return ToolResult("RECUSADO: \"${sk.name}\" é uma skill embutida do app e não pode ser alterada. Crie uma nova skill com create_skill.")
            if (sk.instructions == instructions && s("description").isBlank()) return ToolResult("Nada mudou: as instruções enviadas são iguais às atuais.")
            store.upsertSkill(sk.copy(instructions = instructions, description = s("description").trim().ifBlank { sk.description },
                revisions = (listOf(sk.instructions) + sk.revisions).take(5)))
            return ToolResult("Skill \"${sk.name}\" revisada. A versão anterior ficou guardada no histórico (até 5 versões).")
        }
        "set_schedule" -> {
            val m = i("minutes") ?: 0
            val sess = store.session(sessionId); val ag = store.agent(sess?.agentId)
            if (ag == null) return ToolResult("Esta tarefa não tem um Agent. Peça ao usuário para criar/usar um Agent para rodar de forma autônoma.")
            if (m <= 0) {
                store.upsertAgent(ag.copy(autonomous = false))
                dev.fluwy.app.scheduler.Heartbeat.cancel(ctx, ag.id)
                store.addItem(sessionId, ChatItem.Step(tool = "set_schedule", title = "Autonomia desligada", args = "", result = "", status = StepStatus.OK))
                return ToolResult("Autonomia desligada para ${ag.name}.")
            }
            if (dev.fluwy.app.device.DeviceCaps.BACKGROUND !in ag.deviceCaps)
                return ToolResult("Agendamento não iniciado: primeiro habilite 'Executar em segundo plano' em Permissões do dispositivo no perfil deste Agent. O Android ainda pode interromper ciclos por bateria ou política do aparelho.")
            val mm = m.coerceIn(1, 1440)
            // Eleva o orçamento diário de agentes antigos (criados com o teto baixo de 300k) para
            // não pararem em silêncio em poucos ciclos. Mantém um valor maior já definido pelo usuário.
            store.upsertAgent(ag.copy(autonomous = true, heartbeatMinutes = mm,
                mission = s("mission").ifBlank { ag.mission }, homeSessionId = sessionId, lastBeatAt = System.currentTimeMillis(),
                dailyTokenBudget = maxOf(ag.dailyTokenBudget, 20_000_000L)))
            dev.fluwy.app.scheduler.Heartbeat.schedule(ctx, ag.id, mm)
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
            cuttableWait(sessionId, s("reason"))?.takeIf { teamIdle(it) }?.let {
                return ToolResult("Não há o que esperar: nenhum agente do time está trabalhando agora e todas as entregas já chegaram. " +
                    "Siga já — valide as entregas com team_review ou passe a próxima etapa com team_plan/team_delegate. " +
                    "(Ciclos pedidos pelo usuário, como \"a cada 30 min\", continuam valendo; use set_schedule para eles.)")
            }
            store.addItem(sessionId, ChatItem.Step(tool = "sleep", title = title, args = s("reason"), result = note, status = StepStatus.OK))
            return ToolResult(listOf(s("reason").ifBlank { "verificação" }, note).filter { it.isNotBlank() }.joinToString(" — "), sleepSeconds = secs)
        }
        "connector_edit_propose" -> {
            val enabledValue = runCatching { a["enabled"]?.jsonPrimitive?.booleanOrNull }.getOrNull()
            val agentRequiredValue = runCatching { a["agent_required"]?.jsonPrimitive?.booleanOrNull }.getOrNull()
            val step = ChatItem.Step(tool = call.name, title = "Preparando atualização do conector", args = s("summary"))
            store.addItem(sessionId, step)
            val out = proposeConnectorEdit(sessionId, s("summary"), s("settings_json"), enabledValue, agentRequiredValue, s("definition_yaml"))
            val failed = out.startsWith("INVÁLIDO") || out.startsWith("BLOQUEADO")
            store.replaceItem(sessionId, step.copy(
                title = "Preparando atualização do conector" + if (failed) " — bloqueada" else " — card criado",
                result = out.take(4000),
                status = if (failed) StepStatus.ERROR else StepStatus.OK,
            ))
            return ToolResult(out)
        }
        "connector_spec", "connector_validate", "connector_test", "connector_propose" -> {
            // passo visível no chat: o que está sendo feito, com o quê, e o resultado
            val cname = runCatching { dev.fluwy.app.uiconnector.custom.CustomDefParser.parse(s("yaml")).def?.name }.getOrNull().orEmpty()
            val testArgs = runCatching {
                (dev.fluwy.app.uiconnector.custom.ConnectorYamlCodec.parse(s("args_yaml").ifBlank { "{}" }) as JsonObject).entries.joinToString("; ") { (tool, v) ->
                    tool + "(" + ((v as? JsonObject)?.entries?.joinToString(", ") { (k, x) -> k + "=" + ((x as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull ?: x.toString()) } ?: "") + ")"
                }
            }.getOrDefault("")
            val (title, args) = when (call.name) {
                "connector_spec" -> "Lendo a especificação do SDK" to "formato YAML e regras"
                "connector_validate" -> "Validando a definição do conector" to cname.ifBlank { "YAML" }
                "connector_test" -> (if (testArgs.isBlank()) "Validando o conector (guardrails + mocks offline)" else "Validando o conector com amostras (guardrails + mocks offline)") to
                    listOf(cname, testArgs).filter { it.isNotBlank() }.joinToString(" · ")
                else -> "Preparando o card de instalação" to cname
            }
            val step = ChatItem.Step(tool = call.name, title = title, args = args)
            store.addItem(sessionId, step)
            setActivity(sessionId, Activity(Activity.Kind.THINKING, title))
            val out = SecretVault.redact(sessionId, connectorTool(sessionId, call.name, s("yaml"), s("args_yaml"), s("settings_yaml")))
            val failed = out.startsWith("INVÁLIDO") || out.startsWith("FALHOU") || out.startsWith("BLOQUEADO") || out.startsWith("ERRO")
            val summary = when {
                call.name == "connector_spec" -> "Especificação carregada (${out.length / 1024} KB)."
                else -> out
            }
            store.replaceItem(sessionId, step.copy(title = title + (if (failed) " — falhou" else " — ok"), result = summary.take(4000), status = if (failed) StepStatus.ERROR else StepStatus.OK))
            return ToolResult(out)
        }
        "skill_load" -> {
            val wanted = SkillMatcher.norm(s("name")).trim().removePrefix("@")
            val library = store.state.value.skills.filter { it.enabled }
            val sk = library.firstOrNull { SkillMatcher.norm(it.name) == wanted }
                ?: library.firstOrNull { wanted.isNotBlank() && (SkillMatcher.norm(it.name).contains(wanted) || SkillMatcher.norm(it.name).replace(" ", "") == wanted.replace(" ", "").replace("-", "").replace("_", "")) }
                ?: library.maxByOrNull { SkillMatcher.nameHits(s("name"), it.name) }?.takeIf { SkillMatcher.nameHits(s("name"), it.name) >= 2 }
            sk?.let { ensureSkillFiles(sessionId, it) }
            return ToolResult(sk?.let { "# ${it.name}\n${it.instructions}" + (if (it.files.size > 1) "\n\n[Arquivos da skill em /root/workspace/skills/${dev.fluwy.app.skills.SkillPackage.slugify(it.name)}/: ${it.files.joinToString()}]" else "") }
                ?: "Skill não encontrada. Disponíveis: " + store.state.value.skills.joinToString { it.name })
        }
        "ask_user" -> {
            if (autoSectionSession(sessionId)) return ToolResult("MODO AUTÔNOMO: o usuário não vai responder. Decida você mesmo a melhor opção com os dados que tem, registre a escolha em 1 frase e continue o trabalho agora.")
            store.addItem(sessionId, ChatItem.Assistant(text = s("question"), final = true))
            dev.fluwy.app.notify.NotificationCenter.post(store.session(sessionId)?.agentId?.let { store.agent(it)?.name } ?: "Pergunta do agente", s("question"),
                dev.fluwy.app.notify.NotificationCenter.Priority.HIGH, dev.fluwy.app.notify.NotificationCenter.Category.QUESTION, sessionId)
            return ToolResult("Pergunta enviada; aguardando resposta.", SessionStatus.WAITING_USER)
        }
        "tools_load" -> {
            val hub = runCatching { dev.fluwy.app.FluwyApp.instance.uiConnectors }.getOrNull()
            val connIds = hub?.connectors.orEmpty().map { it.spec.id }.toSet()
            val mcpNames = dev.fluwy.app.connectors.Connectors.toolDefinitions(store).mapNotNull { d -> toolName(d).takeIf { it.contains("__") }?.substringBefore("__")?.removePrefix("mcp_") }.toSet()
            val (ok, bad) = ToolContextPlanner.resolveLoad(sl("names"), connIds, mcpNames)
            ToolContextPlanner.load(sessionId, ok)
            return ToolResult((if (ok.isNotEmpty()) "Carregado: ${ok.joinToString()}. As ferramentas aparecem na sua próxima resposta." else "Nada carregado.") +
                (if (bad.isNotEmpty()) " Desconhecidos: ${bad.joinToString()} — use os nomes do índice 'Ferramentas sob demanda'." else ""))
        }
        "ask_question" -> {
            if (autoSectionSession(sessionId)) return ToolResult("MODO AUTÔNOMO: o usuário não vai responder. Decida você mesmo a melhor opção com os dados que tem, registre a escolha em 1 frase e continue o trabalho agora.")
            askQuestionGuard(sessionId)?.let { return it }
            val (qs, err) = AskTools.parseQuestions(a)
            if (err != null) return ToolResult("ERRO em ask_question: $err")
            store.addItem(sessionId, ChatItem.Question(questions = qs))
            dev.fluwy.app.notify.NotificationCenter.post("O agente tem uma pergunta", qs.joinToString(" · ") { it.question }, dev.fluwy.app.notify.NotificationCenter.Priority.HIGH, dev.fluwy.app.notify.NotificationCenter.Category.QUESTION, sessionId)
            return ToolResult("Perguntas exibidas ao usuário; aguardando as respostas.", SessionStatus.WAITING_USER)
        }
        "ask_secure" -> {
            val (title, fields, err) = AskTools.parseSecure(a)
            if (err != null) return ToolResult("ERRO em ask_secure: $err")
            if (fields.all { SecretVault.has(sessionId, it.name) })
                return ToolResult("Você JÁ tem esses dados seguros: ${fields.joinToString { "{{secret:${it.name}}}" }}. Use os marcadores direto; não peça de novo.")
            // Agente de seção: o usuário está no chat do coordenador, então o formulário aparece LÁ (cofre é o mesmo).
            val memberSec = store.section(store.session(sessionId)?.sectionId)?.takeIf { it.sessionId != sessionId && store.session(it.sessionId) != null }
            val formChat = memberSec?.sessionId ?: sessionId
            val pending = store.session(formChat)?.items?.any { it is ChatItem.SecureForm && it.state == "pending" && (it.forSessionId ?: formChat) == sessionId } == true
            if (pending) return ToolResult("Já há um formulário seguro aguardando o usuário. Espere a resposta.", SessionStatus.WAITING_USER)
            store.addItem(formChat, ChatItem.SecureForm(title = title, reason = s("reason").take(300), fields = fields,
                forSessionId = if (memberSec != null) sessionId else null,
                forAgentName = if (memberSec != null) store.session(sessionId)?.agentId?.let { store.agent(it)?.name } else null))
            dev.fluwy.app.notify.NotificationCenter.post("Dado seguro solicitado", "$title — preencha no formulário seguro (o agente não vê os valores).", dev.fluwy.app.notify.NotificationCenter.Priority.HIGH, dev.fluwy.app.notify.NotificationCenter.Category.SECURITY, formChat)
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
            val path = s("path").trim()
            val f = AgentFilePathPolicy.readableWorkspace(
                sandbox.workspaceFor(sessionId), sandbox.connectorsDir, path,
            ) ?: return ToolResult("ERRO: caminho fora dos workspaces permitidos. Use um caminho relativo ao workspace ou /root/workspace/...; arquivos de conectores são somente leitura em /root/connectors/<id>/workspace/....")
            if (!f.exists() || f.isDirectory) return ToolResult("ERRO: arquivo não encontrado: $path. Confirme com file_list; não repita o mesmo caminho se não existir.")
            store.addItem(sessionId, ChatItem.Media(path = f.absolutePath, caption = s("caption")))
            return ToolResult("Arquivo exibido no chat.")
        }
        "finish" -> {
            (store.session(sessionId)?.items?.lastOrNull { it is ChatItem.Plan } as? ChatItem.Plan)?.let { p ->
                store.replaceItem(sessionId, p.copy(phases = p.phases.map { it.copy(done = true) }))
            }
            val files = a["files"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            val text = s("summary") + if (files.isNotEmpty()) "\n\n📎 " + files.joinToString("\n📎 ") else ""
            if (!alreadyReported(sessionId, text)) store.addItem(sessionId, ChatItem.Assistant(text = text, final = true))
            return ToolResult("ok", SessionStatus.DONE)
        }
    }

    val (title0, kind) = ToolActivityLabels.of(call.name)
    val title = stepTitle(call.name, call.argsJson, title0)
    val argPreview = ToolActivityLabels.preview(call.name, ::s, ::i, cwd)
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
    squadTrack(sessionId, call, title, argPreview)

    // marcador {{secret:x}} sem valor no cofre (ex.: app foi reiniciado) → pede de novo pelo formulário seguro
    SecretVault.missing(sessionId, call.argsJson.replace("\\\"", "\"")).takeIf { it.isNotEmpty() }?.let { miss ->
        store.replaceItem(sessionId, step.copy(result = "Dados seguros ausentes: " + miss.joinToString(), status = StepStatus.ERROR))
        executions.fail(executionId, "secret ausente")
        return ToolResult("ERRO: não há valor seguro para ${miss.joinToString { "{{secret:$it}}" }} nesta tarefa (o formulário não foi enviado ou o app reiniciou). Peça com ask_secure e tente de novo.")
    }
    val out0: String = try {
        executeTool(sessionId, call, a, cwd, step, title, kind, executionId, model, visualInspectionUsed, accessibilityReadScreenCompleted)
    } catch (c: kotlinx.coroutines.CancellationException) {
        executions.cancel(executionId)
        store.replaceItem(sessionId, step.copy(result = "Execução interrompida.", status = StepStatus.ERROR))
        throw c
    } catch (t: Throwable) {
        val detail = t.message.orEmpty()
        val accessibilityTool = call.name.startsWith("device_accessibility_") && call.name != "device_accessibility_open_app"
        val staleSnapshot = isStaleAccessibilitySnapshotFailure(call.name, detail)
        val freshScreen = if (staleSnapshot) try {
            dev.fluwy.app.device.DeviceAccessibilityActions.readScreen(sessionId)
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
        logActivity(sessionId, call.name, step, true, safeDetail)
        runCatching { dev.fluwy.app.diag.ErrorLog.log(errorSource(call.name), safeDetail, sessionId, call.name) }
        store.replaceItem(sessionId, step.copy(result = safeDetail, status = StepStatus.ERROR))
        return ToolResult(toolOutput)
    }
    val out = SecretVault.redact(sessionId, out0)
    val commandFailed = Regex("""\[exit ([1-9]\d*)\]""").containsMatchIn(out)
    val isError = out.startsWith("ERROR:") || out.startsWith("TIMEOUT:") || out.startsWith("PARTIAL:") || out.startsWith("ERRO:") || commandFailed
    // Marcadores de falha específicos de ferramentas (não começam com ERRO:) também viram log.
    // Só na 1ª linha e fora de ferramentas que devolvem conteúdo de terceiros (busca, página, arquivo): ali a palavra é dado, não falha.
    val contentTool = call.name.startsWith("web_") || call.name in setOf("file_read", "notes_read", "memory_search", "knowledge_search")
    val markerError = !contentTool && Regex("""(MARKET_ERROR|WALLET_RPC_ERROR|GUARDRAIL|No such file|not found|não encontrad|Recusado|Acesso negado)""")
        .containsMatchIn(out.lineSequence().firstOrNull().orEmpty().take(400))
    val accessibilityTool = call.name.startsWith("device_accessibility_") && call.name != "device_accessibility_open_app"
    val safeExecutionOutput = if (accessibilityTool)
        "Resultado Android transitório omitido dos diagnósticos; nenhum conteúdo de tela foi salvo."
    else out
    if (isError) executions.fail(executionId, safeExecutionOutput) else executions.finish(executionId, safeExecutionOutput)
    logActivity(sessionId, call.name, step, isError || markerError, safeExecutionOutput)
    // Saídas esperadas de testes do terminal (grep sem achado, `test` falso, curl checando se um servidor já está de pé)
    // não são falhas do app: não entram no log de erros.
    val benignProbe = call.name == "shell" && Regex("""\[exit (1|7|22)\]""").containsMatchIn(out) &&
        out.length < 260 && !Regex("(?i)error|erro|traceback|fatal|exception|denied|not found").containsMatchIn(out)
    if ((isError || markerError) && !benignProbe) {
        val safeLog = if (accessibilityTool) "Falha de ferramenta Android; conteúdo de tela omitido por privacidade."
            else if (call.name.startsWith("browser_") && isError && !out.contains("não vale mais"))  // índice velho já se recupera sozinho
                // Falha de browser: grava o diagnóstico completo para a próxima correção ser certeira.
                out.take(900) + "\n\n" + runCatching { browserFor(sessionId).diagnostics() }.getOrDefault("(diagnóstico indisponível)")
            else out.take(1000)
        runCatching { dev.fluwy.app.diag.ErrorLog.log(errorSource(call.name), safeLog, sessionId, call.name) }
    }
    val safeStepOutput = if (accessibilityTool)
        "Resultado de acessibilidade omitido do histórico por privacidade; conteúdo disponível apenas durante a tarefa ativa."
    else out.take(4000)
    store.replaceItem(sessionId, step.copy(result = safeStepOutput, status = if (commandFailed || out.startsWith("ERROR:") || out.startsWith("TIMEOUT:") || out.startsWith("PARTIAL:") || out.startsWith("ERRO:")) StepStatus.ERROR else StepStatus.OK))
    return ToolResult(out)
}


/** Executa a ferramenta [call] e devolve a saída bruta (antes de redigir segredos). */
internal suspend fun AgentRunner.executeTool(
    sessionId: String, call: ToolCall, a: JsonObject, cwd: String, step: ChatItem.Step, title: String,
    kind: Activity.Kind, executionId: String, model: String,
    visualInspectionUsed: MutableSet<String>, accessibilityReadScreenCompleted: MutableSet<String>,
): String {
    fun s(k: String) = a[k]?.jsonPrimitive?.contentOrNull.orEmpty()
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
    return when (call.name) {
        "build_team" -> runBuildTeam(sessionId, call.argsJson)
        "workflow_manage" -> workflowTool(call.argsJson)
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
                                    val lamports = SolanaRpcClient.balanceWithFallback(network, address)
                                    "Saldo " + network.displayName + ": " + String.format(Locale.US, "%.9f SOL", lamports / 1_000_000_000.0) +
                                        if (lamports == 0L) "\nSaldo RPC retornado como zero. Um endereço válido pode ainda não ter uma conta financiada nesta rede; isso não significa que a frase ou a chave local seja inválida." else ""
                                } catch (cancel: kotlinx.coroutines.CancellationException) {
                                    throw cancel
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
        "wallet_request_transfer" -> walletRequestTransfer(sessionId, s("recipient"), s("amount_sol"))
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
                val market = dev.fluwy.app.wallet.SolanaMarketDataClient()
                if (call.name.startsWith("market_")) {
                    // Leitura/análise: não tocam a carteira nem a chave.
                    when (call.name) {
                        "market_discover" -> {
                            val sort = s("sort").ifBlank { "new" }
                            runCatchingCancellable { market.discoverTokens(sort, i("limit") ?: 30) }.fold({ tokens ->
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
                            runCatchingCancellable { market.tokenInfo(mint) }.fold({ t ->
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
                            else runCatchingCancellable { market.pricesUsd(mints) }.fold({ prices ->
                                if (prices.isEmpty()) "Nenhum preço disponível agora para os mints informados."
                                else prices.entries.joinToString("\n") { (m, p) ->
                                    val sym = SolanaRpcClient.knownSymbol(m) ?: m.take(6)
                                    "$sym ($m): US$ " + String.format(Locale.US, if (p < 1.0) "%.6f" else "%.4f", p)
                                }
                            }, { "MARKET_ERROR ao consultar preço: ${(it.message ?: it.javaClass.simpleName).take(200)}. Não contorne com shell/curl." })
                        }
                        "market_candles" -> {
                            val mint = s("mint")
                            runCatchingCancellable {
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
                                val trend = runCatchingCancellable { market.dexTrend(mint) }.getOrNull()
                                val detail = (e.message ?: e.javaClass.simpleName).take(180)
                                val reason = when (e) {
                                    is dev.fluwy.app.wallet.NoIndexedSolanaPoolException -> "nenhum pool foi retornado pelos indexadores; pode haver ausência de listagem ou atraso de indexação"
                                    is dev.fluwy.app.wallet.MarketPoolProviderException -> "falha ao consultar os provedores; ausência de pool não confirmada ($detail)"
                                    else -> detail
                                }
                                if (trend != null) "Velas OHLCV indisponíveis ($reason). Usando tendência real alternativa, que não é OHLCV:\n\n$trend"
                                else when (e) {
                                    is dev.fluwy.app.wallet.NoIndexedSolanaPoolException -> "MARKET_NO_POOL: $reason. Não invente velas nem repita imediatamente; relate que não há OHLCV disponível."
                                    is dev.fluwy.app.wallet.MarketPoolProviderException -> "MARKET_PROVIDER_ERROR: $reason. Respeite o Retry-After se informado e não trate a falha como prova de que não existe pool."
                                    else -> "MARKET_ERROR ao consultar velas: $detail."
                                }
                            })
                        }
                        else -> { // market_liquidity
                            val input = s("input_mint"); val output = s("output_mint")
                            val probes = sl("probe_amounts_raw").mapNotNull { it.toLongOrNull() }
                            if (probes.isEmpty()) "Informe 'probe_amounts_raw' com ao menos um tamanho em unidades mínimas."
                            else runCatchingCancellable { market.depthCurve(input, output, probes) }.fold({ curve ->
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
                            else runCatchingCancellable { SolanaRpcClient.tokenAccountsWithFallback(network, address) }.fold({ tokens ->
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
                                if (input == dev.fluwy.app.wallet.SolanaSwapClient.SOL_MINT && amount > cap) {
                                    "Recusado: entrada de ${String.format(Locale.US, "%.9f", amount / 1e9)} SOL excede o limite autorizado por operação (${String.format(Locale.US, "%.9f", cap / 1e9)} SOL). Reduza o valor ou ajuste o limite na tela Carteira."
                                } else {
                                    val legs = dev.fluwy.app.trading.SwapLegs.legs(input, output)
                                    var refusal: String? = null
                                    if (legs.isEmpty()) {
                                        refusal = "Recusado: troca entre moedas-base (SOL/USDC/USDT) sem ativo de risco não é uma operação de trade."
                                    } else for (leg in legs) {
                                        val holder = TradeCoordinator.claim("solana", leg.mint, agentId)
                                        if (holder != null) { refusal = "RECUSADO: outro agente já está operando este token agora. Aguarde ou escolha outro."; break }
                                    }
                                    if (refusal != null) {
                                        legs.forEach { TradeCoordinator.release("solana", it.mint, agentId) }
                                        refusal!!
                                    } else {
                                        val rpc = SolanaRpcClient(endpoint = network.rpcEndpoint, network = network)
                                        val exec = dev.fluwy.app.wallet.SolanaSwapExecutor(ctx.applicationContext, wallet, rpc)
                                        when (val r = TradeCoordinator.walletMutex.withLock { exec.executeSwap(input, output, amount, slippage, network) }) {
                                            is dev.fluwy.app.wallet.SwapResult.Submitted ->
                                                "Swap ENVIADO na ${network.displayName}. Saída estimada: ${r.outAmountRaw} (unidades mínimas). Impacto: ${r.priceImpactPct}%. Rota: ${r.routeLabels.joinToString("+").ifBlank { "direta" }}. Assinatura: ${r.signature}. Explorer: ${network.transactionExplorerUrl(r.signature)}"
                                            is dev.fluwy.app.wallet.SwapResult.Rejected -> {
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
                                "price_below", "below" -> dev.fluwy.app.wallet.DayTradeStore.Trigger.PRICE_BELOW
                                "price_above", "above" -> dev.fluwy.app.wallet.DayTradeStore.Trigger.PRICE_ABOVE
                                else -> null
                            }
                            val amount = s("amount_raw").toLongOrNull()
                            val target = s("target_price_usd").toDoubleOrNull()
                            when {
                                trig == null -> "trigger deve ser price_below ou price_above."
                                amount == null || amount <= 0L -> "amount_raw deve ser inteiro positivo em unidades mínimas."
                                target == null || target <= 0.0 -> "target_price_usd deve ser um decimal positivo."
                                dev.fluwy.app.trading.SwapLegs.legs(s("input_mint"), s("output_mint")).any { it.side == dev.fluwy.app.trading.Side.BUY } ->
                                    "Recusado: regras automáticas só podem ser de SAÍDA (stop-loss/take-profit: vender o token para SOL/USDC). Use wallet_swap para executar uma troca autorizada."
                                !dev.fluwy.app.wallet.SolanaAddressValidator.isValidAddress(s("input_mint")) -> "input_mint inválido."
                                !dev.fluwy.app.wallet.SolanaAddressValidator.isValidAddress(s("output_mint")) -> "output_mint inválido."
                                !dev.fluwy.app.wallet.SolanaAddressValidator.isValidAddress(s("watch_mint")) -> "watch_mint inválido."
                                else -> {
                                    val rule = dev.fluwy.app.wallet.DayTradeStore.Rule(
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
                                    dev.fluwy.app.wallet.DayTradeStore(ctx.applicationContext).upsert(rule)
                                    // Mantém o processo vivo para o monitoramento AUTOMÁTICO de SL/TP (avaliação a cada ~20s).
                                    runCatching { dev.fluwy.app.sandbox.SandboxService.start(ctx, "Monitorando stop-loss/take-profit") }
                                    "Regra de day trade ARMADA (ID: ${rule.id}). O sistema vai monitorar o preço real automaticamente (a cada ~20s) e EXECUTAR a troca sozinho quando o alvo for atingido — você NÃO precisa ficar chamando trade_list para isso acontecer."
                                }
                            }
                        }
                        "trade_cancel" -> {
                            val ok = dev.fluwy.app.wallet.DayTradeStore(ctx.applicationContext).cancel(s("rule_id"), agentId)
                            if (ok) "Regra ${s("rule_id")} cancelada." else "Nenhuma regra armada com esse ID para este agente."
                        }
                        "trade_update_rule" -> {
                            val newTarget = s("target_price_usd").toDoubleOrNull()
                            val newTrig = when (s("trigger").lowercase()) {
                                "price_below", "below" -> dev.fluwy.app.wallet.DayTradeStore.Trigger.PRICE_BELOW
                                "price_above", "above" -> dev.fluwy.app.wallet.DayTradeStore.Trigger.PRICE_ABOVE
                                else -> null
                            }
                            when {
                                newTarget == null || newTarget <= 0.0 -> "target_price_usd deve ser um decimal positivo."
                                else -> {
                                    val updated = dev.fluwy.app.wallet.DayTradeStore(ctx.applicationContext)
                                        .updateTarget(s("rule_id"), agentId, newTarget, newTrig)
                                    if (updated == null) "Nenhuma regra ARMADA com esse ID para este agente (só é possível mover regras ainda não executadas/canceladas)."
                                    else {
                                        runCatching { dev.fluwy.app.sandbox.SandboxService.start(ctx, "Monitorando stop-loss/take-profit") }
                                        val dir = if (updated.trigger == dev.fluwy.app.wallet.DayTradeStore.Trigger.PRICE_BELOW) "≤" else "≥"
                                        "Regra ${updated.id} (${updated.label}) AJUSTADA: novo alvo $dir US$ ${updated.targetPriceUsd}. Continua sendo monitorada e executa sozinha ao atingir."
                                    }
                                }
                            }
                        }
                        else -> { // trade_list (+ avaliação)
                            val dtStore = dev.fluwy.app.wallet.DayTradeStore(ctx.applicationContext)
                            val evaluate = a["evaluate"]?.jsonPrimitive?.booleanOrNull ?: true
                            val evalLines = if (evaluate) {
                                val engine = dev.fluwy.app.wallet.DayTradeEngine(ctx.applicationContext, wallet, market)
                                runCatchingCancellable {
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
                                        appendLine("${r.id} [${r.status}] ${r.label}: $sym ${if (r.trigger == dev.fluwy.app.wallet.DayTradeStore.Trigger.PRICE_BELOW) "≤" else "≥"} US$ ${r.targetPriceUsd}; últ. preço US$ ${r.lastPriceUsd}${if (r.resultSignature.isNotBlank()) "; sig ${r.resultSignature}" else ""}")
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
            val browserGuard = BrowserRedirect.guard(command)
            if (browserGuard != null) {
                browserGuard
            } else if (ShellCommandPolicy.isWholeSystemSearch(command)) {
                "GUARDRAIL: não vasculhe o sistema inteiro (/, /vendor, /system…): isso leva horas e não encontra os arquivos do agente. " +
                    "Tudo o que importa fica em /root/workspace" + (if (store.session(sessionId)?.sectionId != null) " (compartilhado por todos os agentes da seção)" else "") +
                    ". Use file_list ou `find /root/workspace -maxdepth 3`. Se o arquivo não estiver lá, ele não existe: diga o que falta e encerre a tarefa."
            } else if (missingScript != null) {
                "GUARDRAIL: o script '$missingScript' não existe neste workspace. Não execute novamente. Use file_list/ls para descobrir o caminho real ou file_write para criar o arquivo antes de executá-lo."
            } else if (badCd != null) {
                "GUARDRAIL: o diretório '$badCd' não existe neste workspace. Liste o diretório pai e escolha um caminho existente."
            } else {
            // tudo roda DENTRO do terminal da tarefa (visível ao vivo no Computador)
            // Servidor em primeiro plano prende o terminal e quebra a captura: roda em segundo plano.
            val autoBackground = !b("background") && ShellCommandPolicy.isLongRunningServer(command)
            val r = terminals.get(sessionId, cwd).runAgentCommand(command, (i("timeout") ?: 180).toLong(), b("background") || autoBackground)
            (if (autoBackground) "(Servidor detectado: iniciado em SEGUNDO PLANO para não travar o terminal. Teste com `curl -s http://127.0.0.1:PORTA/` e não inicie outro na mesma porta.)\n" else "") +
                ShellOutput.formatExecutionResult(r.output, r.exitCode, r.timedOut)
            }
        }
        "file_write" -> {
            val path = s("path").trim()
            val root = sandbox.workspaceFor(sessionId)
            val f = AgentFilePathPolicy.taskWorkspace(root, path)
            when {
                path.isBlank() -> "ERRO: informe path."
                f == null -> "ERRO: caminho fora do workspace da tarefa. Use um caminho relativo (ex.: output/index.html) ou /root/workspace/output/index.html; caminhos Android e absolutos externos não são aceitos."
                f.isDirectory -> "ERRO: $path é um diretório. Use um caminho de arquivo, ex.: $path/index.html"
                else -> {
                    var p = f.parentFile
                    while (p != null && !p.exists()) p = p.parentFile
                    if (p != null && p.isFile) "ERRO: ${p.name} existe como arquivo e não pode ser usado como pasta."
                    else {
                        f.parentFile?.mkdirs()
                        val target = AgentFilePathPolicy.taskWorkspace(root, path)
                        if (target == null) "ERRO: o destino deixou de estar confinado ao workspace; nada foi gravado."
                        else {
                            val append = a["append"]?.jsonPrimitive?.booleanOrNull == true
                            val content = SecretVault.resolve(sessionId, s("content"))
                            val beforeWrite = beforeChange(target)
                            if (append && target.exists()) target.appendText(content) else target.writeText(content)
                            recordChange(sessionId, target, beforeWrite, path)
                            noteFileSeen(sessionId, target)
                            val syntaxWarn = SyntaxCheck.problem(target.name, runCatching { target.readText() }.getOrNull())
                                ?.let { "\n⚠ O arquivo foi salvo, mas está com sintaxe inválida: $it. Corrija antes de usá-lo." }.orEmpty()
                            val lines = target.useLines { it.count() }
                            val preview = runCatching { target.useLines { seq -> seq.take(10).joinToString("\n") } }.getOrDefault("")
                            "OK: ${if (append) "conteúdo acrescentado" else "arquivo salvo"} (${target.relativeTo(root.canonicalFile).path}) — ${target.length()} bytes, $lines linhas.$syntaxWarn\n[Prévia das primeiras 10 linhas]\n$preview"
                        }
                    }
                }
            }
        }
        "file_edit" -> {
            val path = s("path").trim()
            if (path.isBlank()) "ERRO: informe path."
            else {
                val workspace = sandbox.workspaceFor(sessionId).canonicalFile
                val target = AgentFilePathPolicy.taskWorkspace(workspace, path)
                when {
                    target == null -> "ERRO: file_edit só aceita caminhos dentro do workspace da tarefa; caminhos Android e absolutos externos são recusados."
                    !target.isFile -> "ERRO: arquivo existente não encontrado: $path. Use file_list/file_read para conferir uma vez antes de editar."
                    else -> {
                        val oldText = SecretVault.resolve(sessionId, s("old_text"))
                        val newText = SecretVault.resolve(sessionId, s("new_text"))
                        val key = fileKey(sessionId, target)
                        val rel = target.relativeTo(workspace).path
                        // Duas falhas seguidas no mesmo arquivo: só volta a editar depois de reler (corta o mini-loop).
                        if ((editFails[key] ?: 0) >= MAX_EDIT_FAILS)
                            ("RECUSADO: $MAX_EDIT_FAILS edições seguidas falharam em $rel, então tentar de novo de memória não vai funcionar. " +
                                "Releia o trecho com file_read (o arquivo pode ter sido alterado por outro agente) e monte old_text copiando o texto lido. " +
                                "Para acrescentar conteúdo no fim do arquivo, use file_write com append=true em vez de file_edit.")
                        else if (oldText == newText) {
                            editFails.merge(key, 1, Int::plus)
                            "ERRO: old_text e new_text são iguais, então não há nada a alterar em $rel. Se o arquivo já está como você quer, siga adiante; " +
                                "se queria mudar algo, new_text precisa conter o texto novo."
                        } else {
                        val beforeEdit = beforeChange(target)
                        val result = AtomicTextFile.replace(target, oldText, newText, b("allow_multiple"))
                        // Como no SWE-agent: uma edição que deixa um arquivo estruturado inválido não passa.
                        val broke = if (result.success) SyntaxCheck.brokenBy(target.name, beforeEdit.second, runCatching { target.readText() }.getOrNull()) else null
                        if (broke != null && beforeEdit.second != null) {
                            runCatching { target.writeText(beforeEdit.second!!) }
                            return ("ERRO: a edição deixaria $rel com sintaxe inválida ($broke). Nada foi alterado. Corrija new_text (aspas, vírgulas, chaves) e tente de novo.")
                        }
                        if (result.success) recordChange(sessionId, target, beforeEdit, rel)
                        if (!result.success) {
                            val fails = editFails.merge(key, 1, Int::plus) ?: 1
                            val changed = fileSeen[key]?.let { seen -> target.lastModified() > seen + 500 } == true
                            "ERRO: ${result.error ?: "substituição recusada"}" +
                                (if (changed) "\nO arquivo foi modificado depois da sua última leitura (outro agente, um comando ou uma gravação sua escreveu nele): o texto que você tem de memória está desatualizado. Releia com file_read antes de editar."
                                    else if (fileSeen[key] == null) "\nVocê ainda não leu este arquivo nesta tarefa: leia o trecho com file_read e copie old_text do texto lido." else "") +
                                (if (fails >= MAX_EDIT_FAILS) "\nEsta foi a ${fails}ª falha seguida neste arquivo: a próxima edição só será aceita depois de um file_read dele." else "") +
                                runCatching { EditHint.nearest(target.readText(), oldText) }.getOrDefault("").let { if (it.isBlank()) "" else "\n$it" }
                        }
                        else {
                            editFails.remove(key); noteFileSeen(sessionId, target)
                            val mode = if (result.strategy == "exact") "literal" else "whitespace"
                            "OK: ${target.relativeTo(workspace).path}: ${result.replacements} ocorrência(s) substituída(s) ($mode)."
                        }
                        }
                    }
                }
            }
        }
        "file_read" -> {
            val path = s("path").trim()
            // Além do workspace: leitura em ~/ e /root/ do sandbox desta conversa (skills instalam apps em ~/pasta).
            val target = projectRelative(sandbox.workspaceFor(sessionId), path, AgentFilePathPolicy.readableWorkspace(
                sandbox.workspaceFor(sessionId), sandbox.connectorsDir, path,
            ) ?: sandboxHomeFile(sessionId, path))
            if (target == null) "ERRO: caminho inválido (recebido: \"${path.take(160)}\"). Use caminho relativo ao workspace ou /root/workspace/...; para conector, informe o ID completo em /root/connectors/<id>/workspace/.... Não tente caminhos absolutos Android ou caminhos alternativos fora dessas raízes."
            else {
                noteFileSeen(sessionId, target)  // leitura feita: libera novas edições e marca a versão vista
                val archiveRefusal = if (ContextArchive.isArchivePath(path) && target.exists() && !b("stale_ok"))
                    archiveReads.check(sessionId, taskKey(sessionId), target.name, target.length()) else null
                val staleMin = ctxMgr.staleBrowserSnapshotMinutes(path)
                // Continuação de uma saída recém-cortada (mesmo passo de trabalho) é leitura atual, não releitura.
                if (archiveRefusal != null) archiveRefusal
                else if (staleMin != null && staleMin >= STALE_SNAPSHOT_MINUTES && !b("stale_ok"))
                    "RECUSADO: este arquivo é um snapshot ANTIGO do browser (capturado há $staleMin min) e a página pode ter mudado. " +
                        "Para o estado atual, use browser_read (ou browser_navigate, se precisar reabrir). " +
                        "Só releia este snapshot se o usuário ou o coordenador pediu exatamente esse conteúdo antigo — nesse caso repita com stale_ok=true."
                else readCache(sessionId, target, i("offset") ?: 1, i("limit") ?: 250) ?: (if (staleMin != null && staleMin >= STALE_SNAPSHOT_MINUTES) "[Snapshot antigo do browser, capturado há $staleMin min — pode não refletir a página atual]\n" else "") +
                    readFileChunk(target, path, i("offset") ?: 1, i("limit") ?: 250)
            }
        }
        "file_search" -> {
            val root = sandbox.workspaceFor(sessionId)
            val p = s("path").trim()
            val start = if (p.isBlank()) root else projectRelative(root, p, AgentFilePathPolicy.readableWorkspace(root, sandbox.connectorsDir, p, allowRoot = true))
            if ((start == null || !start.exists()) && ContextArchive.isArchivePath(p)) ContextArchive.MISSING
            else if (start == null || !start.exists()) "ERRO: caminho não existe: $p. Omita path para buscar no workspace inteiro."
            else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { FileSearch.search(root, start, s("pattern"), b("regex"), (i("max") ?: 50).coerceIn(5, 200)) }
        }
        "page_create" -> {
            val by = store.agent(store.session(sessionId)?.agentId)?.name ?: "Agente"
            fun arr(k: String) = (a[k] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }.orEmpty()
            // Uma página por conversa/seção: se já existe, nunca cria outra.
            val dup = store.pagesIn(pageSpace(sessionId)).minByOrNull { it.createdAt }
            if (dup != null && store.session(sessionId)?.sectionId != null)
                "RECUSADO: esta seção trabalha numa única página compartilhada: \"${dup.title}\" (${dup.id}, ${dup.kind}). Todos os agentes atualizam essa mesma página: " +
                    "leia com page_read(\"${dup.id}\") e mude só a sua parte com page_update(\"${dup.id}\", ops=[...]) — por exemplo uma seção com o seu nome (replace_section/append_markdown) ou os seus cartões."
            else if (dup != null) "RECUSADO: esta conversa já tem a sua única página: \"${dup.title}\" (${dup.id}, ${dup.kind}). Leia com page_read e altere com page_update(page_id=\"${dup.id}\"). " +
                "Para outro formato (ex.: o usuário pediu um kanban) use a op set_kind nessa mesma página; para outro tema, rename + o novo conteúdo."
            else dev.fluwy.app.pages.PageOps.create(pageSpace(sessionId), s("title"), s("kind"), by, s("markdown"), s("html"), arr("columns"), arr("headers")).fold({ p ->
                if (store.pagesIn(p.spaceId).size >= 60) "RECUSADO: esta conversa já tem 60 páginas; atualize uma existente." else {
                    store.upsertPage(p.copy(creatorAgentId = store.session(sessionId)?.agentId))
                    store.addItem(sessionId, ChatItem.Step(tool = "page", title = "Página criada: ${p.title}", args = p.id, result = p.kind, status = StepStatus.OK))
                    "Criada: ${p.id} (${p.kind}). O usuário já pode abrir em Pages. Atualize com page_update(page_id=\"${p.id}\", ops=[...])."
                }
            }, { "ERRO: ${it.message}" })
        }
        "page_update" -> {
            val by = store.agent(store.session(sessionId)?.agentId)?.name ?: "Agente"
            val ops = (a["ops"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { it as? kotlinx.serialization.json.JsonObject }
                ?: runCatching { kotlinx.serialization.json.Json.parseToJsonElement(s("ops")).jsonArray.map { it.jsonObject } }.getOrNull()
            val id = s("page_id")
            if (ops.isNullOrEmpty()) "ERRO: envie ops como lista de objetos, ex.: [{\"op\":\"move_card\",\"card_id\":\"c_1\",\"column\":\"Feito\"}]."
            else if (store.page(id) == null) "ERRO: página \"$id\" não existe. Páginas desta conversa: " +
                store.pagesIn(pageSpace(sessionId)).joinToString { "${it.id} (\"${it.title}\")" }.ifBlank { "nenhuma" }
            else store.applyPageOps(id, ops, by).fold({ p ->
                val last = store.session(sessionId)?.items?.lastOrNull() as? ChatItem.Step
                if (last?.tool == "page" && last.args == p.id) store.replaceItem(sessionId, last.copy(title = "Página atualizada: ${p.title}", result = "versão ${p.version}"))
                else store.addItem(sessionId, ChatItem.Step(tool = "page", title = "Página atualizada: ${p.title}", args = p.id, result = "versão ${p.version}", status = StepStatus.OK))
                "OK: ${p.title} agora na versão ${p.version} (${ops.size} operação(ões))."
            }, { "ERRO: ${it.message}" })
        }
        "page_read" -> (store.page(s("page_id")) ?: dev.fluwy.app.pages.PageOps.sameTitle(store.state.value.pages, pageSpace(sessionId), s("page_id")))
            ?.let { dev.fluwy.app.pages.PageOps.render(it) }
            ?: ("ERRO: página \"${s("page_id")}\" não existe. Páginas desta conversa: " +
                store.pagesIn(pageSpace(sessionId)).joinToString { "${it.id} (\"${it.title}\")" }.ifBlank { "nenhuma — crie com page_create" })
        "page_list" -> store.pagesIn(pageSpace(sessionId)).sortedByDescending { it.updatedAt }.joinToString("\n") { p ->
            "${p.id} · ${p.kind} · \"${p.title}\" · v${p.version} · ${p.cards.size} cartões"
        }.ifBlank { "Nenhuma página ainda. Crie com page_create." }
        "web_search" -> dev.fluwy.app.web.WebTools.search(s("query"), i("max") ?: 8).let { r ->
            // Busca direta bloqueada/vazia: abre a busca no browser na hora, sem o agente precisar tentar de novo.
            if (r.startsWith("ERRO") || r.startsWith("Nenhum resultado na busca")) browserFallback(sessionId, s("query"), r, 9_000) else r
        }
        "web_fetch" -> {
            val url = SecretVault.resolve(sessionId, s("url")).trim()
            val max = (i("max_chars") ?: 12_000).coerceIn(2_000, 40_000)
            dev.fluwy.app.web.WebTools.fetch(url, max).let { r ->
                // Erro ou página que depende de JS/login: segue automaticamente pelo browser.
                val missing = Regex("^ERRO: HTTP (404|410)\\b").containsMatchIn(r)
                val failed = (r.startsWith("ERRO") && !r.startsWith("ERRO: informe") && !missing) || r.contains("[Pouco texto extraído")
                if (failed) browserFallback(sessionId, url, r, max.coerceAtMost(20_000)) else r
            }
        }
        "file_list" -> {
            val path = s("path").trim()
            val root = sandbox.workspaceFor(sessionId)
            val d = if (path.isBlank()) root else projectRelative(root, path, AgentFilePathPolicy.readableWorkspace(
                root, sandbox.connectorsDir, path, allowRoot = true,
            ) ?: sandboxHomeFile(sessionId, path))
            fun listing(dir: java.io.File) = dir.walkTopDown().maxDepth(3).filter { it != dir && !it.path.contains("/.fluwy/") && it.name != ".fluwy" }.take(300)
                .joinToString("\n") { (if (it.isDirectory) "📁 " else "📄 ") + it.relativeTo(dir).path + if (it.isFile) " (${it.length()}b)" else "" }
                .ifBlank { "(vazio)" }
            when {
                // Caminho fora do workspace: em vez de um erro que leva a novas tentativas, mostra a raiz real.
                d == null -> "AVISO: '$path' está fora do workspace (só /root/workspace e /root/connectors/<id>/workspace são listáveis). " +
                    "Conteúdo de /root/workspace — use estes caminhos relativos:\n" + listing(root)
                !d.exists() || !d.isDirectory -> "ERRO: diretório não existe: $path. Conteúdo de /root/workspace:\n" + listing(root)
                else -> relNote(root, path, d) + listing(d)
            }
        }
        "browser_navigate" -> {
            // Caminhos do sandbox (/root, /public, /home ou file:///root/…) viram o arquivo real do aparelho:
            // o WebView não enxerga o filesystem do proot (era a causa de net::ERR_FILE_NOT_FOUND).
            browserMode(sessionId, s("mode"))
            val raw = SecretVault.resolve(sessionId, s("url")).trim()
            val guest = raw.removePrefix("file://").let { if (raw.startsWith("file://")) it.substringBefore('?').substringBefore('#') else it }
            val isSandboxPath = guest.startsWith("/root") || guest.startsWith("/public") || guest.startsWith("/home") || (raw.startsWith("file://") && !guest.startsWith("/data/") && !guest.startsWith("/storage/") && !guest.startsWith("/sdcard"))
            val hostF = if (isSandboxPath) sandbox.hostFile(guest, sessionId) else null
            val hubGuard = if (hostF != null) null else hubBrowserGuard(sessionId, raw)
            if (hubGuard != null) hubGuard
            else if (hostF != null && !hostF.exists()) {
                "ERROR: arquivo não existe no workspace: $guest. Use file_list para achar o caminho certo (arquivos de conectores ficam em /root/connectors/<id>/workspace)."
            } else {
                val url = hostF?.let { "file://" + it.absolutePath } ?: BrowserVisitLog.canonical(raw)
                // Sessão de login salva (Browser › Sessões): só agentes autorizados entram com ela.
                val loginDenied = if (hostF != null) null else runCatching {
                    val login = dev.fluwy.app.browser.BrowserPolicies.loginFor(dev.fluwy.app.browser.BrowserPolicies.hostOf(url), store.state.value.browserLogins)
                    if (login == null || login.status == "revoked") null
                    else dev.fluwy.app.browser.BrowserPolicies.loginDenial(login, store.session(sessionId)?.agentId)
                        ?.let { "RECUSADO: $it. Não tente entrar por conta própria; peça ao usuário para liberar em Browser › Sessões ou siga sem esse site." }
                        ?: run { store.editBrowserLogin(login.id) { it.copy(lastUsedAt = System.currentTimeMillis(), useCount = it.useCount + 1) }; null }
                }.getOrNull()
                // Guarda-corpo: não reabrir a mesma busca/página em looping (mesmo com parâmetros reordenados).
                val current = runCatching { browserFor(sessionId).ui.value.url }.getOrNull()
                val repeated = BrowserVisitLog.check(sessionId, url, current)
                if (loginDenied != null) loginDenied else if (repeated != null) repeated else run {
                var navigation = browserFor(sessionId).navigate(url)
                // App fechado e sem permissão de sobreposição: em vez de devolver o erro (o agente insistia
                // e gastava tokens), ESPERA o app voltar para a tela — sem custo — e navega de novo sozinho.
                if (navigation.contains("browser indisponível com o app fechado")) {
                    setActivity(sessionId, Activity(Activity.Kind.BROWSER, "Browser em espera: abra o app para continuar"))
                    val deadline = System.currentTimeMillis() + BROWSER_WAIT_FOREGROUND_MS
                    while (!dev.fluwy.app.browser.OffscreenStage.foreground && System.currentTimeMillis() < deadline) kotlinx.coroutines.delay(1_500)
                    navigation = if (dev.fluwy.app.browser.OffscreenStage.foreground) { kotlinx.coroutines.delay(800); browserFor(sessionId).navigate(url) }
                        else "ERROR: o app ficou fechado por ${BROWSER_WAIT_FOREGROUND_MS / 60_000} min e o browser não funciona assim. NÃO navegue de novo: " +
                            "siga com o que não depende do browser ou use sleep (10+ min) e tente depois. O usuário pode liberar 'Sobrepor a outros apps' para o browser rodar com o app fechado."
                }
                BrowserVisitLog.record(sessionId, url, navigation)
                if (navigation.startsWith("ERROR:")) navigation
                else if (navigation.startsWith("TIMEOUT:")) {
                    // Como o Playwright MCP: mesmo lento, devolve o que a página mostra agora.
                    val now = runCatching { browserFor(sessionId).read(2500, trackRepeat = false) }.getOrDefault("")
                    navigation + (if (now.isNotBlank()) "\n\n[Estado atual da página]\n$now" else "")
                }
                else {
                    val page = browserFor(sessionId).read(4000, trackRepeat = false)
                    val notFound = Regex("(?i)(t[ií]tulo:[^\\n]*(404|not found|n[aã]o encontrad)|this page doesn.t exist|page not found|p[aá]gina n[aã]o encontrada)").containsMatchIn(page.take(600))
                    val loginHint = runCatching {
                        val finalHost = dev.fluwy.app.browser.BrowserPolicies.hostOf(browserFor(sessionId).ui.value.url.ifBlank { url })
                        dev.fluwy.app.browser.BrowserPolicies.noLoginHint(finalHost, page, store.state.value.browserLogins)
                    }.getOrNull()
                    (if (notFound) "⚠ A página carregou, mas é um ERRO 404 (não existe). Não use este conteúdo; procure o endereço correto.\n" else "") + navigation + "\n\n" + page +
                        (loginHint?.let { "\n\n$it" } ?: "")
                }
                }
            }
        }
        "browser_new_tab" -> browserFor(sessionId).newTab(SecretVault.resolve(sessionId, s("url")))
        "browser_select_tab" -> browserFor(sessionId).selectTabForAgent(s("tab_id"))
        "browser_close_tab" -> browserFor(sessionId).closeTabForAgent(s("tab_id"))
        "reply_audio", "notify", "show_cards", "cofre_listar", "pix_qr" -> extraOutputTool(sessionId, call.name, a)
        "conectores_listar" -> dev.fluwy.app.connectors.HubConnectors.list(store)
        "conector_acoes" -> dev.fluwy.app.connectors.HubConnectors.actions(store, s("conector"), s("acao"), s("busca"))
        "conector_executar" -> dev.fluwy.app.connectors.HubConnectors.run(store, s("conector"), s("acao"),
            resolveArgs(sessionId, a)["campos"], s("conexao"),
            full = runCatching { a["completo"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase() == "true" }.getOrDefault(false))
        "browser_read", "browser_snapshot" -> browserReadTool(sessionId, (i("max_chars") ?: 9000).coerceIn(1000, 20000), model)
        "browser_wait_for" -> {
            val br = browserFor(sessionId)
            val waited = br.waitFor(s("text"), s("text_gone"), i("seconds") ?: 10)
            waited + "\n\n" + br.read(4000)
        }
        "browser_click" -> browserFor(sessionId).click(i("index") ?: -1)
        "browser_type" -> browserFor(sessionId).type(i("index") ?: -1, SecretVault.resolve(sessionId, s("text")), b("submit"))
        "browser_scroll" -> browserFor(sessionId).scroll(s("direction") != "up")
        // Voltar invalida os índices: já devolve a leitura nova (antes o agente clicava no índice velho e errava em sequência).
        "browser_back" -> { browserFor(sessionId).back(); "ok: voltou. Página atual (use estes índices):\n" + browserReadTool(sessionId, 6000, model) }
        "browser_js" -> browserFor(sessionId).runJs(SecretVault.resolve(sessionId, s("code")))
        "device_notifications" -> dev.fluwy.app.device.NotifStore.recent(s("filter"), (i("limit") ?: 30).coerceIn(1, 100))
            .joinToString("\n") { n ->
                val head = "[${java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(n.time))}]" + (if (n.id.isNotBlank()) " id=${n.id}" else "") +
                    " ${n.app}: ${n.title}" + (if (n.replyable) " [pode responder]" else "")
                if (n.conversation.isNotEmpty()) head + "\n" + n.conversation.joinToString("\n") { "    $it" }.take(1_500)
                else "$head — ${n.text}".take(300)
            }
            .ifBlank { "Nenhuma notificação capturada ainda." }
        "device_notification_reply" -> dev.fluwy.app.device.NotificationReply.send(ctx, s("id"), SecretVault.resolve(sessionId, s("text")))
        "send_file", "device_share_file" -> {
            val f = AgentFilePathPolicy.readableWorkspace(sandbox.workspaceFor(sessionId), sandbox.connectorsDir, s("path"))
            if (f == null || !f.isFile) "ERRO: arquivo não encontrado no workspace (recebido: \"${s("path").take(120)}\"). Confira com file_list."
            else if (call.name == "send_file") {
                val ch = dev.fluwy.app.share.ChannelSender.channel(s("channel"))
                if (ch == null) "ERRO: canal inválido. Use: " + dev.fluwy.app.share.ChannelSender.configured(channelConfig()).joinToString { it.id }
                else dev.fluwy.app.share.ChannelSender.send(ch, channelConfig(), f, SecretVault.resolve(sessionId, s("caption")))
            } else dev.fluwy.app.share.ChannelSender.shareToApp(ctx, s("app"), f, s("text"), s("phone"))
        }
        "device_open_link" -> {
            val url = s("url").trim()
            val scheme = url.substringBefore(':').lowercase()
            if (scheme !in setOf("https", "http", "spotify", "geo", "mailto", "tel", "whatsapp", "market", "youtube", "vnd.youtube", "uber", "instagram", "fb", "tg", "sms", "smsto"))
                "RECUSADO: tipo de link não permitido ($scheme). Use links https ou de apps conhecidos (spotify:, geo:, mailto:, tel:, whatsapp:)."
            else runCatching {
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                val target = runCatching { ctx.packageManager.resolveActivity(intent, 0)?.loadLabel(ctx.packageManager)?.toString() }.getOrNull()
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { ctx.startActivity(intent) }
                kotlinx.coroutines.delay(1_500)
                "Abri o link" + (target?.let { " em $it" } ?: "") + ". Se precisar tocar em um botão (ex.: Enviar), use device_accessibility_tap."
            }.getOrElse { "ERRO: nenhum app do aparelho abriu esse link (${it.javaClass.simpleName})." }
        }
        "device_accessibility_tap" -> {
            val screen = dev.fluwy.app.device.DeviceAccessibilityActions.readScreen(sessionId)
            val hit = dev.fluwy.app.device.ScreenMatch.find(screen, s("text"), i("occurrence") ?: 1)
            if (hit == null) {
                val labels = dev.fluwy.app.device.ScreenMatch.elements(screen).mapNotNull { it.second.takeIf { l -> l.isNotBlank() } }.distinct().take(25)
                "Não achei \"${s("text").take(60)}\" na tela atual. Textos visíveis: " + labels.joinToString(" | ").take(700)
            } else {
                val out = dev.fluwy.app.device.DeviceAccessibilityActions.click(sessionId, hit.snapshotId, hit.elementId)
                "Toquei em \"${hit.label.take(60)}\". " + out.take(200)
            }
        }
        "device_calendar_read" -> dev.fluwy.app.device.DeviceActions.calendar(ctx, i("days") ?: 7)
        "device_calendar_create" -> dev.fluwy.app.device.DeviceActions.createCalendarEvent(
            ctx, s("title"), s("start_epoch_ms").toLongOrNull() ?: 0L, s("end_epoch_ms").toLongOrNull() ?: 0L, s("description"), s("location"))
        "device_contacts" -> dev.fluwy.app.device.DeviceActions.contacts(ctx, s("query"))
        "device_call_log" -> dev.fluwy.app.device.DeviceActions.callLog(ctx, i("limit") ?: 20)
        "device_location" -> dev.fluwy.app.device.DeviceActions.location(ctx)
        "device_bluetooth" -> dev.fluwy.app.device.DeviceActions.bluetoothDevices(ctx)
        "device_bluetooth_pair" -> dev.fluwy.app.device.DeviceActions.openBluetoothSettings(ctx)
        "device_dial" -> dev.fluwy.app.device.DeviceActions.openDialer(ctx, s("phone"))
        "device_microphone" -> dev.fluwy.app.device.DeviceActions.microphone(ctx)
        "device_accessibility_open_app" -> dev.fluwy.app.device.DeviceAccessibilityActions.openApp(ctx, sessionId, s("app"))
        "device_accessibility_read_screen" -> dev.fluwy.app.device.DeviceAccessibilityActions.readScreen(sessionId)
        "device_accessibility_inspect_image" -> {
            val request = store.session(sessionId)?.items?.lastOrNull { it is ChatItem.User } as? ChatItem.User
            when {
                request == null || !dev.fluwy.app.device.AccessibilityVisualInspectionPolicy
                    .explicitlyRequestsPostOrImage(request.text) ->
                    "RECUSADO: captura visual só é permitida como fallback de um pedido explícito para ler/analisar um post de app social."
                !dev.fluwy.app.device.AccessibilityVisualInspectionPolicy.canInspectImage(
                    request.text,
                    currentScreenWasRead = sessionId in accessibilityReadScreenCompleted,
                    inspectionAlreadyUsed = sessionId in visualInspectionUsed,
                ) -> if (sessionId in visualInspectionUsed)
                    "RECUSADO: já fiz a única inspeção visual desta tarefa; use o resultado recebido e não capture outras telas/posts."
                else "RECUSADO: primeiro leia a tela acessível atual e verifique o texto/descrição da imagem; só então use este fallback se a árvore não revelar o conteúdo."
                else -> {
                    visualInspectionUsed.add(sessionId)
                    val jpeg = try {
                        dev.fluwy.app.device.DeviceAccessibilityActions.inspectImage(
                            sessionId, s("snapshot_id"), i("element_id") ?: -1,
                        )
                    } catch (t: Throwable) {
                        if (isStaleAccessibilitySnapshotFailure(dev.fluwy.app.prompts.Prompts.render("vision.4", mapOf<String, Any?>("lb" to "{{")), t.message.orEmpty()))
                            visualInspectionUsed.remove(sessionId)
                        throw t
                    }
                    val prompt = dev.fluwy.app.prompts.Prompts.render("vision.3", mapOf<String, Any?>("lb" to "{{")) +
                        dev.fluwy.app.prompts.Prompts.render("vision.2", mapOf<String, Any?>("lb" to "{{")) +
                        dev.fluwy.app.prompts.Prompts.render("vision.1", mapOf<String, Any?>("lb" to "{{")) +
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
        "device_accessibility_click" -> dev.fluwy.app.device.DeviceAccessibilityActions.click(
            sessionId, s("snapshot_id"), i("element_id") ?: -1)
        "device_accessibility_type" -> {
            val rawText = s("text")
            val exactSecret = SecretVault.resolveExact(sessionId, rawText)
            dev.fluwy.app.device.DeviceAccessibilityActions.typeText(
                sessionId, s("snapshot_id"), i("element_id") ?: -1,
                exactSecret ?: SecretVault.resolve(sessionId, rawText), exactSecret != null)
        }
        "device_accessibility_scroll" -> dev.fluwy.app.device.DeviceAccessibilityActions.scroll(
            sessionId, s("snapshot_id"), i("element_id") ?: -1, s("direction"))
        "device_accessibility_back" -> dev.fluwy.app.device.DeviceAccessibilityActions.back(sessionId)
        "device_notify" -> { notifyRaw(s("title").ifBlank { "Fluwy" }, plainText(s("text"))); "Notificação enviada." }
        "device_overlay" -> { dev.fluwy.app.device.Overlay.show(ctx, s("text")); "Balão exibido." }
        "device_screenshot" -> {
            val request = store.session(sessionId)?.items?.lastOrNull { it is ChatItem.User } as? ChatItem.User
            when {
                request != null && dev.fluwy.app.device.AccessibilityVisualInspectionPolicy
                    .explicitlyRequestsPostOrImage(request.text) ->
                    "RECUSADO: não vou capturar a tela inteira para ler um post. Use a árvore acessível e, somente se ela não revelar a imagem, device_accessibility_inspect_image no recorte atual."
                request == null || !dev.fluwy.app.device.DeviceScreenshotPolicy
                    .explicitlyRequestsFullScreenCapture(request.text) ->
                    "RECUSADO: screenshot da tela inteira requer pedido explícito do usuário; não use como inspeção automática."
                else -> {
                    val f = java.io.File(sandbox.workspaceFor(sessionId), "screenshot_${System.currentTimeMillis()}.png")
                    if (dev.fluwy.app.device.ScreenCapture.capture(ctx, f)) {
                        store.addItem(sessionId, ChatItem.Media(path = f.absolutePath, caption = "Captura de tela"))
                        "Captura salva e exibida: ${f.name}"
                    } else "Não foi possível capturar (permissão de captura não concedida)."
                }
            }
        }
        "browser_network" -> browserFor(sessionId).networkSummary(s("filter"))
        "browser_console" -> browserFor(sessionId).consoleSummary()
        "live_server" -> {
            val project = s("project").ifBlank { store.session(sessionId)?.title.orEmpty() }.ifBlank { "site" }
            when (s("action").lowercase().ifBlank { "publish" }) {
                "status" -> dev.fluwy.app.live.LiveServer.status()
                "stop", "unpublish", "despublicar", "kill", "down" -> dev.fluwy.app.live.LiveServer.stop(s("project").ifBlank { project })
                else -> {
                    val dir = s("dir").trim()
                    val port = a["port"]?.jsonPrimitive?.intOrNull
                    val dirHost = if (dir.isNotEmpty()) sandbox.hostFile(dir, sessionId).absolutePath else null
                    dev.fluwy.app.live.LiveServer.publish(project, dirHost, if (dirHost == null) port else null)
                }
            }
        }
        "notes_write" -> {
            val f = ctxMgr.notesFile(sessionId)
            if (a["append"]?.jsonPrimitive?.booleanOrNull != false && f.exists()) f.appendText("\n" + s("content")) else f.writeText(s("content"))
            "NOTES.md atualizado (${f.length()} bytes)."
        }
        "memory_save", "remember" -> {
            val sess = store.session(sessionId)
            val agentScoped = s("scope") == "agent" && sess?.agentId != null
            val tags = a["tags"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            val fact = s("fact").trim()
            // OpenBot 0.73: em vez de cortar em silêncio, diz o limite para o agente salvar fatos menores.
            ContextManager.memoryTooLong(fact) ?: ctxMgr.saveMemory(fact, if (agentScoped) sess?.agentId else null, tags,
                s("title"), s("kind"), a["links"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty())
        }
        "memory_search" -> ctxMgr.describeMemories(ctxMgr.searchMemories(s("query"), store.session(sessionId)?.agentId))
            .joinToString("\n") { "- $it" }.ifBlank { "Nada encontrado." }
        "remember_legacy" -> {
            val sess = store.session(sessionId)
            val ag = store.agent(sess?.agentId)
            if (ag != null) { store.upsertAgent(ag.copy(memory = ag.memory + s("fact"))); "Memória salva." }
            else "Esta tarefa não usa um Agent; crie um Agent para ter memória."
        }
        "skill_install" -> {
            val fetched = runCatching { dev.fluwy.app.skills.SkillInstaller.fetch(s("source"), s("name").ifBlank { null }, sandbox.workspaceFor(sessionId)) }
            fetched.fold({ list ->
                list.joinToString("\n\n━━━━━━━━\n\n") { f ->
                    val (sk, written) = installSkill(sessionId, f)
                    "OK: skill \"${sk.name}\" instalada e ativa (${f.source}).\n" +
                        "Arquivos em /root/workspace/skills/${sk.name}/: ${written.joinToString()}" +
                        (if (f.meta.problems.isNotEmpty()) "\nAjustes na validação: " + f.meta.problems.joinToString("; ") else "") +
                        (if (f.warnings.isNotEmpty()) "\n⚠ Atenção antes de executar: " + f.warnings.joinToString("; ") + ". Confirme com o usuário antes de rodar esses comandos." else "") +
                        "\n\n--- SKILL.md (siga para concluir o pedido do usuário) ---\n" + f.meta.body.take(8_000)
                }
            }, { "ERRO ao instalar a skill: ${it.message}. Confira o link (público? tem SKILL.md?)." })
        }
        "create_skill" -> {
            store.upsertSkill(Skill(name = s("name"), description = s("description"), instructions = s("instructions")))
            "Skill \"${s("name")}\" criada e ativada."
        }
        else -> if (call.name.startsWith("mcp_")) dev.fluwy.app.connectors.Connectors.callTool(store, call.name, resolveArgs(sessionId, a))
                else if (call.name.startsWith("uic_")) {
                    val ag = store.agent(store.session(sessionId)?.agentId)
                    dev.fluwy.app.FluwyApp.instance.uiConnectors.callTool(
                        ag, ag?.id ?: sessionId, call.name, resolveArgs(sessionId, a),
                        dev.fluwy.app.uiconnector.UiConnectorCallContext(sessionId),
                    ).let { SecretVault.redact(sessionId, it) }.also { out ->
                        // Falhas reais do conector entram no log de erros (sem expor segredos: já redigido).
                        if (out.startsWith("ERRO") || out.startsWith("RECUSAD"))
                            runCatching { dev.fluwy.app.diag.ErrorLog.log("uiconnector", out.take(600), sessionId, call.name) }
                    }
                }
                else "Ferramenta desconhecida: ${call.name}"
    }
}