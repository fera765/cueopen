package dev.fluwy.app.wallet

import kotlinx.coroutines.sync.withLock
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Evaluates persisted day-trade rules against REAL market prices and executes the swap when a
 * threshold is crossed. There is no hidden background thread: the agent drives evaluation each
 * cycle (via the trade_* tools / its heartbeat), which keeps the control flow observable and
 * avoids runaway automation. Every outcome is reported honestly — a rule is only marked EXECUTED
 * when the swap actually broadcast, and the on-chain signature is recorded.
 *
 * Safety bounds:
 * - Only agents the user granted the day-trade capability reach this path (checked by the caller).
 * - SOL-funded entries are capped by the agent's per-transfer SOL limit (same cap as transfers), so
 *   an automated buy can never spend more SOL than the user authorized.
 * - Mainnet only (Jupiter has no Devnet liquidity); Devnet rules are rejected.
 */
class DayTradeEngine(
    private val context: Context,
    private val repository: SolanaWalletRepository = SolanaWalletRepository(context.applicationContext),
    private val market: SolanaMarketDataClient = SolanaMarketDataClient()
) {
    private val store = DayTradeStore(context.applicationContext)

    data class EvalOutcome(val rule: DayTradeStore.Rule, val message: String, val executed: Boolean)

    /**
     * Checks every ARMED rule for [agentId]. For each triggered rule, runs the real swap (bounded by
     * [maxEntryLamports] when the entry is SOL-funded). Returns a human-readable line per rule.
     */
    suspend fun evaluateAgentRules(
        agentId: String,
        network: SolanaNetwork,
        maxEntryLamports: Long
    ): List<EvalOutcome> = withContext(Dispatchers.IO) {
        val armed = store.armedForAgent(agentId)
        if (armed.isEmpty()) return@withContext emptyList()
        val watched = armed.map { it.watchMint }.distinct()
        val prices = runCatching { market.pricesUsd(watched) }.getOrDefault(emptyMap())
        val outcomes = ArrayList<EvalOutcome>()
        for (rule in armed) {
            val price = prices[rule.watchMint]
            if (price == null || price <= 0.0) {
                outcomes += EvalOutcome(rule, "Regra ${rule.id}: preço indisponível agora para ${symbol(rule.watchMint)}; nada feito.", false)
                continue
            }
            val fired = when (rule.trigger) {
                DayTradeStore.Trigger.PRICE_BELOW -> price <= rule.targetPriceUsd
                DayTradeStore.Trigger.PRICE_ABOVE -> price >= rule.targetPriceUsd
            }
            if (!fired) {
                store.upsert(rule.copy(lastCheckedAt = System.currentTimeMillis(), lastPriceUsd = price))
                outcomes += EvalOutcome(
                    rule,
                    "Regra ${rule.id} (${rule.label}) ARMADA: ${symbol(rule.watchMint)} está em ${usd(price)}, alvo ${triggerText(rule)}. Aguardando.",
                    false
                )
                continue
            }
            outcomes += executeRule(rule, network, maxEntryLamports, price)
        }
        outcomes
    }

    /** Executes a single rule immediately (used when a trigger fired). Public for direct limit orders. */
    suspend fun executeRule(
        rule: DayTradeStore.Rule,
        network: SolanaNetwork,
        maxEntryLamports: Long,
        observedPriceUsd: Double
    ): EvalOutcome = withContext(Dispatchers.IO) {
        if (network != SolanaNetwork.MAINNET) {
            val note = "Day trade só opera na Mainnet (sem liquidez na Devnet)."
            store.upsert(rule.copy(status = DayTradeStore.Status.FAILED, resultNote = note, lastPriceUsd = observedPriceUsd))
            return@withContext EvalOutcome(rule, "Regra ${rule.id}: $note", false)
        }
        // Cap SOL-funded entries by the user's authorized per-transfer SOL limit.
        if (rule.inputMint == SolanaSwapClient.SOL_MINT && rule.amountRaw > maxEntryLamports) {
            val note = "Entrada de ${sol(rule.amountRaw)} excede o limite autorizado por operação (${sol(maxEntryLamports)}). Ajuste o limite na tela Carteira ou reduza a entrada."
            store.upsert(rule.copy(status = DayTradeStore.Status.FAILED, resultNote = note, lastPriceUsd = observedPriceUsd))
            return@withContext EvalOutcome(rule, "Regra ${rule.id}: $note", false)
        }
        val rpc = SolanaRpcClient(endpoint = network.rpcEndpoint, network = network)
        val executor = SolanaSwapExecutor(context.applicationContext, repository, rpc)
        when (val r = dev.fluwy.app.trading.TradeCoordinator.walletMutex.withLock {
            executor.executeSwap(rule.inputMint, rule.outputMint, rule.amountRaw, rule.slippageBps, network)
        }) {
            is SwapResult.Submitted -> {
                val link = network.transactionExplorerUrl(r.signature)
                val note = "Gatilho atingido a ${usd(observedPriceUsd)}. Swap enviado. Assinatura: ${r.signature}. Explorer: $link"
                store.upsert(rule.copy(
                    status = DayTradeStore.Status.EXECUTED,
                    executedAt = System.currentTimeMillis(),
                    lastPriceUsd = observedPriceUsd,
                    resultSignature = r.signature,
                    resultNote = note
                ))
                EvalOutcome(rule, "Regra ${rule.id} (${rule.label}) EXECUTADA: $note", true)
            }
            is SwapResult.Rejected -> {
                val note = "Gatilho atingido a ${usd(observedPriceUsd)}, mas a troca foi recusada: ${r.reason}"
                store.upsert(rule.copy(status = DayTradeStore.Status.FAILED, lastPriceUsd = observedPriceUsd, resultNote = note))
                EvalOutcome(rule, "Regra ${rule.id}: $note", false)
            }
        }
    }

    private fun triggerText(rule: DayTradeStore.Rule): String = when (rule.trigger) {
        DayTradeStore.Trigger.PRICE_BELOW -> "≤ ${usd(rule.targetPriceUsd)}"
        DayTradeStore.Trigger.PRICE_ABOVE -> "≥ ${usd(rule.targetPriceUsd)}"
    }

    private fun symbol(mint: String): String = SolanaRpcClient.knownSymbol(mint) ?: (mint.take(4) + "…" + mint.takeLast(4))
    private fun usd(v: Double): String = "US$ " + String.format(Locale.US, if (v < 1.0) "%.6f" else "%.4f", v)
    private fun sol(lamports: Long): String = String.format(Locale.US, "%.9f SOL", lamports / 1_000_000_000.0)
}
