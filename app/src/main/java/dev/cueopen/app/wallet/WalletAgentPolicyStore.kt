package dev.cueopen.app.wallet

import android.content.Context
import java.security.MessageDigest

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

    /** Public-address access is a separate, opt-in capability for each agent. */
    fun canAgentReadAddress(agentId: String): Boolean =
        prefs.getBoolean(agentCapabilityKey(agentId, CAPABILITY_READ_ADDRESS), false)

    fun setAgentReadAddress(agentId: String, enabled: Boolean) {
        setAgentCapability(agentId, CAPABILITY_READ_ADDRESS, enabled)
    }

    /** Agent transfer requests are opt-in and always require a human approval in the wallet UI. */
    fun canAgentRequestTransfer(agentId: String): Boolean =
        prefs.getBoolean(agentCapabilityKey(agentId, CAPABILITY_REQUEST_TRANSFER), false)

    fun setAgentRequestTransfer(agentId: String, enabled: Boolean) {
        setAgentCapability(agentId, CAPABILITY_REQUEST_TRANSFER, enabled)
    }

    /**
     * Returns the persisted policy for a transfer identity. Agent policies are strictly
     * non-autonomous: a grant permits proposing a request, never signing or broadcasting it.
     */
    fun transferPolicy(agentId: String, network: SolanaNetwork): WalletAgentPolicy =
        if (agentId == manualAgentId(network)) manualTransferPolicy(network)
        else {
            val enabled = canAgentRequestTransfer(agentId)
            WalletAgentPolicy(
                agentId = agentId,
                enabled = enabled,
                canTransfer = enabled,
                autonomousExecution = false,
                maxTransferLamports = MAX_AGENT_TRANSFER_LAMPORTS,
                dailyLimitLamports = DAILY_AGENT_TRANSFER_LIMIT_LAMPORTS,
                allowedRecipients = emptySet()
            )
        }

    /** Balance access is a separate, opt-in capability for each agent. */
    fun canAgentReadBalance(agentId: String): Boolean =
        prefs.getBoolean(agentCapabilityKey(agentId, CAPABILITY_READ_BALANCE), false)

    fun setAgentReadBalance(agentId: String, enabled: Boolean) {
        setAgentCapability(agentId, CAPABILITY_READ_BALANCE, enabled)
    }

    private fun setAgentCapability(agentId: String, capability: String, enabled: Boolean) {
        require(agentId.isNotBlank()) { "Agent ID is required" }
        check(prefs.edit().putBoolean(agentCapabilityKey(agentId, capability), enabled).commit()) {
            "Could not persist wallet agent permission"
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
        private const val CAPABILITY_READ_ADDRESS = "read_address"
        private const val CAPABILITY_READ_BALANCE = "read_balance"
        private const val CAPABILITY_REQUEST_TRANSFER = "request_transfer"
        private const val MAX_AGENT_TRANSFER_LAMPORTS = 50_000_000L
        private const val DAILY_AGENT_TRANSFER_LIMIT_LAMPORTS = 100_000_000L

        fun manualAgentId(network: SolanaNetwork): String =
            MANUAL_AGENT_PREFIX + network.name.lowercase()

        private fun enabledKey(network: SolanaNetwork): String =
            manualAgentId(network) + ".transfer_enabled"

        /** Hash IDs in preference keys to avoid storing raw agent identifiers as key names. */
        private fun agentCapabilityKey(agentId: String, capability: String): String {
            require(agentId.isNotBlank()) { "Agent ID is required" }
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(agentId.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            return "agent.$digest.$capability"
        }
    }
}
