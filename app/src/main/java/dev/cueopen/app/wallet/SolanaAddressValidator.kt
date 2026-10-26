package dev.cueopen.app.wallet

import java.math.BigInteger

/**
 * Strict Solana public-key text validation. A Base58-looking string is not sufficient:
 * decoded addresses must contain exactly 32 bytes.
 *
 * This validates encoding/length only, not account existence or program semantics.
 */
object SolanaAddressValidator {
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private val radix = BigInteger.valueOf(58L)

    fun isValidAddress(address: String): Boolean {
        if (address.isEmpty() || address.length > 44) return false
        var value = BigInteger.ZERO
        for (character in address) {
            val digit = ALPHABET.indexOf(character)
            if (digit < 0) return false
            value = value.multiply(radix).add(BigInteger.valueOf(digit.toLong()))
        }

        val leadingZeroBytes = address.takeWhile { it == '1' }.length
        val significantBytes = if (value == BigInteger.ZERO) 0 else (value.bitLength() + 7) / 8
        return leadingZeroBytes + significantBytes == PUBLIC_KEY_BYTES
    }

    private const val PUBLIC_KEY_BYTES = 32
}
