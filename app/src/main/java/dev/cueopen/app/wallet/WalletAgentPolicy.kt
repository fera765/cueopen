package dev.cueopen.app.wallet

/** Local deny-by-default policy gate. Must be checked by the signer, not only by UI/LLM. */
data class WalletAgentPolicy(
    val agentId: String,
    val enabled: Boolean = false,
    val canReadBalance: Boolean = false,
    val canReceive: Boolean = false,
    val canTrade: Boolean = false,
    val canTransfer: Boolean = false,
    val autonomousExecution: Boolean = false,
    val maxTransferLamports: Long = 0L,
    val dailyLimitLamports: Long = 0L,
    val allowedRecipients: Set<String> = emptySet()
) {
    init {
        require(agentId.isNotBlank())
        require(maxTransferLamports >= 0L)
        require(dailyLimitLamports >= 0L)
    }

    fun authorize(
        action: WalletAction,
        amountLamports: Long = 0L,
        recipient: String? = null,
        spentTodayLamports: Long = 0L
    ): PolicyDecision {
        if (!enabled) return PolicyDecision.Denied("Wallet access is disabled for this agent")
        return when (action) {
            WalletAction.READ_BALANCE ->
                if (canReadBalance) PolicyDecision.Allowed else PolicyDecision.Denied("Balance access is not permitted")
            WalletAction.RECEIVE_ADDRESS ->
                if (canReceive) PolicyDecision.Allowed else PolicyDecision.Denied("Receive access is not permitted")
            WalletAction.TRADE ->
                if (canTrade) PolicyDecision.Allowed else PolicyDecision.Denied("Trading is not permitted")
            WalletAction.TRANSFER -> {
                if (!canTransfer) return PolicyDecision.Denied("Transfers are not permitted")
                if (amountLamports <= 0L) return PolicyDecision.Denied("Transfer amount must be positive")
                if (maxTransferLamports <= 0L || amountLamports > maxTransferLamports) {
                    return PolicyDecision.Denied("Transfer exceeds the per-transaction limit")
                }
                if (spentTodayLamports < 0L) return PolicyDecision.Denied("Invalid daily spending state")
                // Subtraction avoids overflow from adding amount + spent.
                if (dailyLimitLamports <= 0L || spentTodayLamports > dailyLimitLamports ||
                    amountLamports > dailyLimitLamports - spentTodayLamports) {
                    return PolicyDecision.Denied("Transfer exceeds the remaining daily limit")
                }
                if (recipient.isNullOrBlank()) return PolicyDecision.Denied("Recipient is required")
                if (!SolanaAddressValidator.isValidAddress(recipient)) {
                    return PolicyDecision.Denied("Recipient is not a valid Solana public address")
                }
                // Optional allowlist: if the user configured one, restrict to it; if empty, any
                // recipient is permitted. Per-transaction and daily SOL limits always apply.
                if (allowedRecipients.isNotEmpty() && recipient !in allowedRecipients) {
                    return PolicyDecision.Denied("Recipient is not allowlisted")
                }
                if (!autonomousExecution) PolicyDecision.RequiresUserApproval else PolicyDecision.Allowed
            }
        }
    }
}

enum class WalletAction { READ_BALANCE, RECEIVE_ADDRESS, TRADE, TRANSFER }

sealed interface PolicyDecision {
    data object Allowed : PolicyDecision
    data object RequiresUserApproval : PolicyDecision
    data class Denied(val reason: String) : PolicyDecision
}
