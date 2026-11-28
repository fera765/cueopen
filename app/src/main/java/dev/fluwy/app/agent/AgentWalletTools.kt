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

/** Carteira Solana e regras de trade. Devolve null quando a ferramenta não é desta família. */
internal suspend fun AgentRunner.walletTools(inv: ToolInvocation): String? = with(inv) {
    when (call.name) {
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
        else -> null
    }
}
