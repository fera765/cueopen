package dev.cueopen.app.wallet

import java.math.BigInteger

/**
 * Validates Base58 shape and the 64-byte Solana transaction-signature encoding.
 * This is not cryptographic signature verification; the transaction SDK verifies signatures.
 */
object SolanaSignatureValidator {
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private val radix = BigInteger.valueOf(58L)

    fun isValid(signature: String): Boolean {
        if (signature.length !in MIN_SIGNATURE_TEXT_LENGTH..MAX_SIGNATURE_TEXT_LENGTH) return false
        var value = BigInteger.ZERO
        for (character in signature) {
            val digit = ALPHABET.indexOf(character)
            if (digit < 0) return false
            value = value.multiply(radix).add(BigInteger.valueOf(digit.toLong()))
        }
        val leadingZeroBytes = signature.takeWhile { it == '1' }.length
        val significantBytes = if (value == BigInteger.ZERO) 0 else (value.bitLength() + 7) / 8
        return leadingZeroBytes + significantBytes == SIGNATURE_BYTES
    }

    private const val SIGNATURE_BYTES = 64
    private const val MIN_SIGNATURE_TEXT_LENGTH = 64
    private const val MAX_SIGNATURE_TEXT_LENGTH = 88
}
