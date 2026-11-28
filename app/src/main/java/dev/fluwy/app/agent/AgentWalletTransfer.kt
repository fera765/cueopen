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

// ───────── Exército de agentes (build_team): dezenas de agentes no mesmo projeto, ao vivo no browser ─────────
/** wallet_request_transfer (fora do runTool, que está no limite de tamanho de método da JVM). */
internal suspend fun AgentRunner.walletRequestTransfer(sessionId: String, recipient: String, amountSol: String): String {
    return run {
            // Identity comes from the selected local session, never from LLM arguments.
            val agentId = store.session(sessionId)?.agentId
            val policies = WalletAgentPolicyStore(ctx.applicationContext)
            if (agentId.isNullOrBlank() || store.agent(agentId) == null) {
                "Pedido recusado: esta sessão não está vinculada a um agente local válido."
            } else if (!policies.canAgentRequestTransfer(agentId)) {
                "Pedido recusado: o usuário não concedeu a este agente permissão para solicitar transferências."
            } else {
                val networkPrefs = ctx.applicationContext.getSharedPreferences(
                    "solana_wallet_settings", android.content.Context.MODE_PRIVATE
                )
                val networkResult = runCatching {
                    val stored = networkPrefs.getString("network", null)
                        ?: error("A rede da carteira não foi configurada. Abra a tela Carteira e selecione explicitamente Mainnet ou Devnet.")
                    SolanaNetwork.valueOf(stored)
                }
                if (networkResult.isFailure) {
                    "Pedido bloqueado: abra a carteira e selecione Mainnet ou Devnet antes de solicitar uma transferência."
                } else {
                    val network = networkResult.getOrThrow()
                    val wallet = SolanaWalletRepository(ctx.applicationContext)
                    if (!wallet.hasWallet() || !wallet.backupConfirmed()) {
                        "Pedido bloqueado: carteira indisponível ou backup ainda não confirmado."
                    } else {
                        runCatching {
                            val nowMs = System.currentTimeMillis()
                            val start = java.util.Calendar.getInstance().apply {
                                set(java.util.Calendar.HOUR_OF_DAY, 0)
                                set(java.util.Calendar.MINUTE, 0)
                                set(java.util.Calendar.SECOND, 0)
                                set(java.util.Calendar.MILLISECOND, 0)
                            }
                            val windowStart = start.timeInMillis
                            val windowEnd = (start.clone() as java.util.Calendar).apply {
                                add(java.util.Calendar.DAY_OF_YEAR, 1)
                            }.timeInMillis
                            val intent = dev.fluwy.app.wallet.WalletTransferIntent.fromSol(
                                requestId = java.util.UUID.randomUUID().toString().replace("-", ""),
                                agentId = agentId,
                                recipientAddress = recipient.trim(),
                                amountSol = amountSol.trim(),
                                createdAtEpochMs = nowMs,
                                network = network
                            )
                            val policy = policies.transferPolicy(agentId, network)
                            val ledger = dev.fluwy.app.wallet.WalletRequestLedger(ctx.applicationContext)
                            val duplicate = ledger.findRecentDuplicateIntent(intent, nowMs)
                            if (duplicate != null) {
                                "Pedido duplicado recente detectado; nenhum novo pedido criado. ID: ${duplicate.requestId}; estado: ${duplicate.state}. Revise o pedido existente na tela Carteira."
                            } else {
                                when (val claim = ledger.claimAndAuthorize(
                                    intent, policy, windowStart, windowEnd, nowMs
                                )) {
                                    is dev.fluwy.app.wallet.LedgerAuthorizationResult.Created ->
                                        if (claim.record.state == dev.fluwy.app.wallet.LedgerState.AWAITING_APPROVAL)
                                            "Pedido registrado e aguardando aprovação na tela Carteira. ID: ${intent.requestId}; rede: ${network.displayName}; destinatário: ${intent.recipientAddress}; valor: ${String.format(Locale.US, "%.9f SOL", intent.amountLamports / 1_000_000_000.0)}. Nenhuma assinatura ou transmissão foi realizada."
                                        else
                                            // Autonomous grant: the user enabled automatic transfers for this agent,
                                            // so sign + broadcast without manual approval, still bounded by the
                                            // persisted policy (per-tx/daily limits + mandatory allowlist), which the
                                            // signer re-validates. Report only what the network actually returned.
                                            runAutonomousTransfer(intent, policy, ledger, wallet, network, agentId, windowStart, windowEnd, nowMs)
                                    is dev.fluwy.app.wallet.LedgerAuthorizationResult.Rejected ->
                                        "Pedido recusado pela política local: ${claim.decision.reason}"
                                    is dev.fluwy.app.wallet.LedgerAuthorizationResult.Existing ->
                                        "Pedido duplicado detectado; nenhuma nova autorização criada. ID: ${claim.record.requestId}; estado: ${claim.record.state}."
                                    is dev.fluwy.app.wallet.LedgerAuthorizationResult.Approved ->
                                        "Este pedido já foi aprovado anteriormente; nenhuma nova assinatura foi autorizada."
                                }
                            }
                        }.getOrElse { error ->
                            "Pedido de transferência não criado: ${error.message ?: "erro de validação"}"
                        }
                    }
                }
            }
    }
}
