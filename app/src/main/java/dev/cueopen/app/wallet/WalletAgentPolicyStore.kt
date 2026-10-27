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
     * Opt-in autonomous execution: when enabled (together with request-transfer), the agent may
     * sign and broadcast a transfer WITHOUT per-transaction human approval. This is only honored
     * by the policy gate when a recipient allowlist is configured (see WalletAgentPolicy.authorize),
     * and always stays bounded by the per-transfer and daily SOL limits. Default: disabled.
     */
    fun canAgentAutonomousTransfer(agentId: String): Boolean =
        prefs.getBoolean(agentCapabilityKey(agentId, CAPABILITY_AUTONOMOUS_TRANSFER), false)

    fun setAgentAutonomousTransfer(agentId: String, enabled: Boolean) {
        setAgentCapability(agentId, CAPABILITY_AUTONOMOUS_TRANSFER, enabled)
    }

    /**
     * Opt-in day-trade capability: lets the agent read live market data (price/candles/liquidity),
     * list SPL holdings, execute swaps, and arm stop-loss/take-profit rules. Swaps funded with SOL
     * stay bounded by the per-transfer SOL limit. Default: disabled. Granting it does not grant
     * SOL transfers to third parties (that is a separate capability).
     */
    fun canAgentDayTrade(agentId: String): Boolean =
        prefs.getBoolean(agentCapabilityKey(agentId, CAPABILITY_DAY_TRADE), false)

    fun setAgentDayTrade(agentId: String, enabled: Boolean) {
        setAgentCapability(agentId, CAPABILITY_DAY_TRADE, enabled)
    }

    /**
     * Returns the persisted policy for a transfer identity. Autonomous execution is only turned on
     * when the user explicitly enabled both request-transfer and autonomous-transfer for this agent;
     * the policy gate still requires a non-empty allowlist before allowing an autonomous signature.
     */
    fun transferPolicy(agentId: String, network: SolanaNetwork): WalletAgentPolicy =
        if (agentId == manualAgentId(network)) manualTransferPolicy(network)
        else {
            val enabled = canAgentRequestTransfer(agentId)
            val autonomous = enabled && canAgentAutonomousTransfer(agentId)
            WalletAgentPolicy(
                agentId = agentId,
                enabled = enabled,
                canTransfer = enabled,
                autonomousExecution = autonomous,
                maxTransferLamports = agentMaxTransferLamports(agentId),
                dailyLimitLamports = agentDailyLimitLamports(agentId),
                allowedRecipients = agentAllowedRecipients(agentId)
            )
        }

    fun agentMaxTransferLamports(agentId: String): Long {
        val value = prefs.getLong(agentCapabilityKey(agentId, "max_transfer_lamports"), MAX_AGENT_TRANSFER_LAMPORTS)
        return value.takeIf { it in 1L..MAX_AGENT_TRANSFER_LAMPORTS } ?: 0L
    }

    fun agentDailyLimitLamports(agentId: String): Long {
        val value = prefs.getLong(agentCapabilityKey(agentId, "daily_limit_lamports"), DAILY_AGENT_TRANSFER_LIMIT_LAMPORTS)
        return value.takeIf { it in 1L..DAILY_AGENT_TRANSFER_LIMIT_LAMPORTS } ?: 0L
    }

    fun agentAllowedRecipients(agentId: String): Set<String> {
        val recipients = prefs.getStringSet(agentCapabilityKey(agentId, "allowed_recipients"), emptySet())
            ?.toSet().orEmpty()
        // Corrupt/edited allowlists fail closed instead of silently becoming unrestricted.
        return if (recipients.all { SolanaAddressValidator.isValidAddress(it) }) recipients else setOf("")
    }

    /** User-configurable lower limits; hard caps prevent accidental unlimited agent grants. */
    fun setAgentTransferLimits(agentId: String, maxPerTransferLamports: Long, dailyLimitLamports: Long) {
        require(agentId.isNotBlank()) { "Agent ID is required" }
        require(maxPerTransferLamports in 1L..MAX_AGENT_TRANSFER_LAMPORTS) {
            "Per-transfer agent limit must be between 1 lamport and 0.05 SOL"
        }
        require(dailyLimitLamports in maxPerTransferLamports..DAILY_AGENT_TRANSFER_LIMIT_LAMPORTS) {
            "Daily agent limit must be at least the per-transfer limit and no more than 0.10 SOL"
        }
        check(prefs.edit()
            .putLong(agentCapabilityKey(agentId, "max_transfer_lamports"), maxPerTransferLamports)
            .putLong(agentCapabilityKey(agentId, "daily_limit_lamports"), dailyLimitLamports)
            .commit()) { "Could not persist agent transfer limits" }
    }

    /** Atomically saves the full agent transfer policy so a partial UI save cannot loosen constraints. */
    fun setAgentTransferSettings(
        agentId: String,
        maxPerTransferLamports: Long,
        dailyLimitLamports: Long,
        recipients: Set<String>
    ) {
        require(agentId.isNotBlank()) { "Agent ID is required" }
        require(maxPerTransferLamports in 1L..MAX_AGENT_TRANSFER_LAMPORTS) {
            "Per-transfer agent limit must be between 1 lamport and 0.05 SOL"
        }
        require(dailyLimitLamports in maxPerTransferLamports..DAILY_AGENT_TRANSFER_LIMIT_LAMPORTS) {
            "Daily agent limit must be at least the per-transfer limit and no more than 0.10 SOL"
        }
        require(recipients.size <= 100) { "Recipient allowlist cannot exceed 100 addresses" }
        require(recipients.all { SolanaAddressValidator.isValidAddress(it) }) {
            "Every allowlisted recipient must be a valid Solana address"
        }
        check(prefs.edit()
            .putLong(agentCapabilityKey(agentId, "max_transfer_lamports"), maxPerTransferLamports)
            .putLong(agentCapabilityKey(agentId, "daily_limit_lamports"), dailyLimitLamports)
            .putStringSet(agentCapabilityKey(agentId, "allowed_recipients"), HashSet(recipients))
            .commit()) { "Could not persist agent transfer settings" }
    }

    /** Empty allowlist means human-reviewed destinations are allowed; non-empty means exact match only. */
    fun setAgentAllowedRecipients(agentId: String, recipients: Set<String>) {
        require(agentId.isNotBlank()) { "Agent ID is required" }
        require(recipients.size <= 100) { "Recipient allowlist cannot exceed 100 addresses" }
        require(recipients.all { SolanaAddressValidator.isValidAddress(it) }) {
            "Every allowlisted recipient must be a valid Solana address"
        }
        check(prefs.edit()
            .putStringSet(agentCapabilityKey(agentId, "allowed_recipients"), HashSet(recipients))
            .commit()) { "Could not persist agent recipient allowlist" }
    }

    /** Balance access is a separate, opt-in capability for each agent. */
    fun canAgentReadBalance(agentId: String): Boolean =
        prefs.getBoolean(agentCapabilityKey(agentId, CAPABILITY_READ_BALANCE), false)

    fun setAgentReadBalance(agentId: String, enabled: Boolean) {
        setAgentCapability(agentId, CAPABILITY_READ_BALANCE, enabled)
    }

    /** Atomically persist the complete per-agent wallet access grant/revocation. */
    fun setAgentCapabilities(
        agentId: String,
        readAddress: Boolean,
        readBalance: Boolean,
        requestTransfer: Boolean,
        autonomousTransfer: Boolean = false,
        dayTrade: Boolean = false
    ) {
        require(agentId.isNotBlank()) { "Agent ID is required" }
        // Autonomous execution can never be on without the base request-transfer grant.
        val autonomous = requestTransfer && autonomousTransfer
        check(prefs.edit()
            .putBoolean(agentCapabilityKey(agentId, CAPABILITY_READ_ADDRESS), readAddress)
            .putBoolean(agentCapabilityKey(agentId, CAPABILITY_READ_BALANCE), readBalance)
            .putBoolean(agentCapabilityKey(agentId, CAPABILITY_REQUEST_TRANSFER), requestTransfer)
            .putBoolean(agentCapabilityKey(agentId, CAPABILITY_AUTONOMOUS_TRANSFER), autonomous)
            .putBoolean(agentCapabilityKey(agentId, CAPABILITY_DAY_TRADE), dayTrade)
            .commit()) { "Could not persist wallet agent permissions" }
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
        private const val CAPABILITY_AUTONOMOUS_TRANSFER = "autonomous_transfer"
        private const val CAPABILITY_DAY_TRADE = "day_trade"
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
