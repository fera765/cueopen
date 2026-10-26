package dev.cueopen.app.wallet

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.sol4k.Base58

class SolanaPersistedTransactionValidatorTest {
    private val signatureBytes = ByteArray(64)
    private val signature = Base58.encode(signatureBytes)

    private fun transaction(): ByteArray =
        ByteArray(66).also {
            it[0] = 1 // compact signature count: exactly one signature
            signatureBytes.copyInto(it, destinationOffset = 1)
        }

    @Test
    fun acceptsTransactionWhenEmbeddedSignatureMatchesLedger() {
        assertTrue(SolanaPersistedTransactionValidator.signatureMatches(transaction(), signature))
    }

    @Test
    fun rejectsSignatureMismatch() {
        assertFalse(
            SolanaPersistedTransactionValidator.signatureMatches(transaction(), "2".repeat(64))
        )
    }

    @Test
    fun rejectsUnsupportedEnvelopeAndOversizedPayload() {
        val badHeader = transaction().also { it[0] = 2 }
        assertFalse(SolanaPersistedTransactionValidator.signatureMatches(badHeader, signature))
        assertFalse(
            SolanaPersistedTransactionValidator.signatureMatches(ByteArray(65), signature)
        )
        assertFalse(
            SolanaPersistedTransactionValidator.signatureMatches(ByteArray(1233), signature)
        )
        assertFalse(
            SolanaPersistedTransactionValidator.signatureMatches(transaction(), "not-a-signature")
        )
    }
}
