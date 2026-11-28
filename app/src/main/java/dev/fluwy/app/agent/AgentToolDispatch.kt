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
    val inv = ToolInvocation(sessionId, call, a, cwd, step, title, kind, executionId, model, visualInspectionUsed, accessibilityReadScreenCompleted)
    (walletTools(inv) ?: workspaceTools(inv) ?: browserTools(inv) ?: deviceTools(inv))?.let { return it }
    return when (call.name) {
        "build_team" -> runBuildTeam(sessionId, call.argsJson)
        "workflow_manage" -> workflowTool(call.argsJson)
        "multi_agents" -> runMultiAgents(sessionId, s("tasks_json"), s("repo_path"), i("max_workers") ?: 3, i("budget_tokens") ?: 6000)
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
        "notes_read" -> ctxMgr.readNotes(sessionId).ifBlank { "NOTES.md está vazio: nada registrado com notes_write nesta tarefa." }.takeLast(12_000)
        "knowledge_search" -> ctxMgr.relevantKnowledge(s("query"), k = (i("limit") ?: 4).coerceIn(1, 8), maxChars = 8_000)
            .joinToString("\n\n") { "## ${it.title}\n${it.content.take(2_000)}" }
            .ifBlank { "Nada encontrado na base de conhecimento para \"${s("query")}\". Tente outros termos ou siga sem esse material." }
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