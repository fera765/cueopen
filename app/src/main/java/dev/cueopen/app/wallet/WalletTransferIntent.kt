package dev.cueopen.app.wallet

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Typed transfer request passed between agent tools and the local transaction engine.
 * This is deliberately not a serialized transaction and cannot be signed by itself.
 */
data class WalletTransferIntent(
    val requestId: String,
    val agentId: String,
    val recipientAddress: String,
    val amountLamports: Long,
    val createdAtEpochMs: Long,
    val memo: String? = null
) {
    init {
        require(requestId.matches(Regex("[A-Za-z0-9_-]{12,96}"))) { "Invalid transfer request ID" }
        require(agentId.isNotBlank()) { "Agent ID is required" }
        require(SolanaAddressValidator.isValidAddress(recipientAddress)) {
            "Recipient must be a valid 32-byte Solana public address"
        }
        require(amountLamports > 0L) { "Amount must be positive" }
        require(createdAtEpochMs > 0L) { "Creation time is required" }
        require(memo == null || memo.length <= 200) { "Memo is too long" }
    }

    companion object {
        private const val LAMPORTS_PER_SOL = 1_000_000_000L

        /** Parses decimal SOL without floating-point rounding. */
        fun fromSol(
            requestId: String,
            agentId: String,
            recipientAddress: String,
            amountSol: String,
            createdAtEpochMs: Long,
            memo: String? = null
        ): WalletTransferIntent {
            val amount = try {
                BigDecimal(amountSol).setScale(9, RoundingMode.UNNECESSARY)
                    .multiply(BigDecimal(LAMPORTS_PER_SOL))
                    .longValueExact()
            } catch (_: Exception) {
                throw IllegalArgumentException("Amount must be a positive SOL value with at most 9 decimals")
            }
            require(amount > 0L) { "Amount must be positive" }
            return WalletTransferIntent(
                requestId = requestId,
                agentId = agentId,
                recipientAddress = recipientAddress,
                amountLamports = amount,
                createdAtEpochMs = createdAtEpochMs,
                memo = memo
            )
        }
    }
}

/**
 * Process-local duplicate guard for early integration/testing only.
 * Production must persist idempotency records across process death and reconcile against
 * chain signatures before retrying; this class is intentionally not production idempotency.
 */
class InMemoryWalletRequestGuard(private val maxEntries: Int = 2_000) {
    private val seen = LinkedHashSet<String>()

    init { require(maxEntries > 0) }

    @Synchronized
    fun claim(requestId: String): Boolean {
        require(requestId.isNotBlank())
        if (!seen.add(requestId)) return false
        while (seen.size > maxEntries) seen.remove(seen.first())
        return true
    }
}
