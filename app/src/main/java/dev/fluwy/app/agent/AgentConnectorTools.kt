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
 * Executes an already-AUTHORIZED autonomous transfer end to end: local signing, broadcast,
 * and one reconciliation pass. The persisted policy (limits + mandatory allowlist) is
 * re-validated inside the signer/broadcaster, so this never bypasses the local safety gate.
 * Returns a human-readable, honest status — never claims success the network did not confirm.
 */
internal suspend fun AgentRunner.runAutonomousTransfer(
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
internal fun AgentRunner.errorSource(toolName: String): String = when {
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

internal fun AgentRunner.yamlStringMap(raw: String): Map<String, String> {
    if (raw.isBlank()) return emptyMap()
    val root = dev.fluwy.app.uiconnector.custom.ConnectorYamlCodec.parse(raw) as? JsonObject
        ?: throw IllegalArgumentException("a raiz precisa ser um mapa YAML")
    return root.mapValues { (_, value) ->
        (value as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull
            ?: throw IllegalArgumentException("os valores devem ser escalares")
    }
}

internal suspend fun AgentRunner.connectorTool(sessionId: String, name: String, yaml: String, argsYaml: String = "", settingsYaml: String = ""): String {
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

internal suspend fun AgentRunner.proposeConnectorEdit(
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
