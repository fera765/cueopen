package dev.cueopen.app.wallet

import android.content.Context

/**
 * Local wallet policy preferences. Per-agent read grants and manual transfer permission
 * are persisted separately; every missing capability defaults to disabled. Agent read grants
 * never authorize transaction creation, signing, or broadcast.
 */
class WalletAgentPolicyStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isManualTransferEnabled(network: SolanaNetwork): Boolean =
        prefs.getBoolean(enabledKey(network), false)

    fun setManualTransferEnabled(network: SolanaNetwork, enabled: Boolean) {
        check(prefs.edit().putBoolean(enabledKey(network), enabled).commit()) {
            "Could not persist wallet transfer permission"
        }
    }

    fun manualTransferPolicy(network: SolanaNetwork): WalletAgentPolicy {
        val enabled = isManualTransferEnabled(network)
        return WalletAgentPolicy(
            agentId = manualAgentId(network),
            enabled = enabled,
            canTransfer = enabled,
            autonomousExecution = false,
            maxTransferLamports = MAX_MANUAL_TRANSFER_LAMPORTS,
            dailyLimitLamports = DAILY_MANUAL_TRANSFER_LIMIT_LAMPORTS,
            allowedRecipients = emptySet()
        )
    }

    companion object {
        private const val PREFS_NAME = "wallet_agent_policies"
        private const val MAX_MANUAL_TRANSFER_LAMPORTS = 50_000_000L
        private const val DAILY_MANUAL_TRANSFER_LIMIT_LAMPORTS = 100_000_000L
        private const val MANUAL_AGENT_PREFIX = "manual-wallet-ui-"

        fun manualAgentId(network: SolanaNetwork): String =
            MANUAL_AGENT_PREFIX + network.name.lowercase()

        private fun enabledKey(network: SolanaNetwork): String =
            manualAgentId(network) + ".transfer_enabled"
    }
}
