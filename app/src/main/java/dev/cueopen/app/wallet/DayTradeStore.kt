package dev.cueopen.app.wallet

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persisted day-trade rules (stop-loss / take-profit / limit). Rules describe a REAL swap to run
 * when a REAL USD price crosses a threshold. Nothing here holds secrets; execution is delegated to
 * [SolanaSwapExecutor], which signs locally. Rules are scoped per agent so one agent cannot see or
 * cancel another's rules.
 */
class DayTradeStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    enum class Trigger { PRICE_BELOW, PRICE_ABOVE }

    @Serializable
    enum class Status { ARMED, EXECUTED, FAILED, CANCELLED }

    @Serializable
    data class Rule(
        val id: String,
        val agentId: String,
        val label: String,
        val network: String,
        val inputMint: String,
        val outputMint: String,
        val amountRaw: Long,
        val watchMint: String,
        val trigger: Trigger,
        val targetPriceUsd: Double,
        val slippageBps: Int,
        val status: Status = Status.ARMED,
        val createdAt: Long = System.currentTimeMillis(),
        val lastCheckedAt: Long = 0L,
        val lastPriceUsd: Double = 0.0,
        val executedAt: Long = 0L,
        val resultSignature: String = "",
        val resultNote: String = ""
    )

    fun allForAgent(agentId: String): List<Rule> =
        load().filter { it.agentId == agentId }.sortedByDescending { it.createdAt }

    fun armedForAgent(agentId: String): List<Rule> =
        allForAgent(agentId).filter { it.status == Status.ARMED }

    /** IDs de agentes que têm ao menos uma regra ARMADA (para o avaliador automático de SL/TP). */
    fun armedAgentIds(): List<String> =
        load().filter { it.status == Status.ARMED }.map { it.agentId }.distinct()

    fun get(id: String): Rule? = load().firstOrNull { it.id == id }

    fun upsert(rule: Rule) {
        val next = load().filterNot { it.id == rule.id } + rule
        persist(next)
    }

    /** Cancels an ARMED rule owned by [agentId]. Returns true if a rule was actually cancelled. */
    fun cancel(id: String, agentId: String): Boolean {
        val rule = load().firstOrNull { it.id == id && it.agentId == agentId } ?: return false
        if (rule.status != Status.ARMED) return false
        upsert(rule.copy(status = Status.CANCELLED))
        return true
    }

    private fun load(): List<Rule> = runCatching {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        json.decodeFromString<List<Rule>>(raw)
    }.getOrDefault(emptyList())

    private fun persist(rules: List<Rule>) {
        // Bound storage: keep the most recent rules so a long-running agent cannot grow unbounded.
        val trimmed = rules.sortedByDescending { it.createdAt }.take(MAX_RULES)
        check(prefs.edit().putString(KEY, json.encodeToString(trimmed)).commit()) {
            "Could not persist day-trade rules"
        }
    }

    companion object {
        private const val PREFS = "wallet_day_trade_rules"
        private const val KEY = "rules_v1"
        private const val MAX_RULES = 200
    }
}
