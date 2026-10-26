package dev.cueopen.app.wallet

import org.sol4k.Base58

/**
 * Checks the persisted transaction envelope before broadcast.
 * This binds the ledger's recorded signature to the bytes being submitted; it is not a
 * substitute for cryptographic verification or parsing/validating every transaction instruction.
 */
internal object SolanaPersistedTransactionValidator {
    private const val MAX_TRANSACTION_BYTES = 1232
    private const val SIGNATURE_COUNT_AND_SIGNATURE_SIZE = 65

    fun signatureMatches(serializedTransaction: ByteArray, expectedSignature: String): Boolean {
        if (serializedTransaction.size !in 66..MAX_TRANSACTION_BYTES ||
            serializedTransaction[0].toInt() != 1 ||
            !SolanaSignatureValidator.isValid(expectedSignature)
        ) return false

        val embeddedSignature = Base58.encode(
            serializedTransaction.copyOfRange(1, SIGNATURE_COUNT_AND_SIGNATURE_SIZE)
        )
        return SolanaSignatureValidator.isValid(embeddedSignature) &&
            embeddedSignature == expectedSignature
    }
}
