package dev.cueopen.app.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SolanaWalletKeyDerivationTest {
    @Test
    fun generatedWalletRestoresToTheSameAddress() {
        val generated = SolanaWalletKeyDerivation.generate()
        val recoveryPhrase = generated.recoveryPhrase.copyOf()
        val expectedAddress = generated.publicAddress
        try {
            assertEquals(24, recoveryPhrase.count { it == ' ' } + 1)
            val restored = SolanaWalletKeyDerivation.restore(recoveryPhrase)
            try {
                assertEquals(expectedAddress, restored.publicAddress)
            } finally {
                restored.close()
            }
        } finally {
            generated.close()
            recoveryPhrase.fill('\u0000')
        }
    }

    @Test
    fun restoreRejectsAnInvalidMnemonicChecksum() {
        val invalidPhrase = (
            "abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon abandon"
            ).toCharArray()
        try {
            assertThrows(IllegalArgumentException::class.java) {
                SolanaWalletKeyDerivation.restore(invalidPhrase)
            }
        } finally {
            invalidPhrase.fill('\u0000')
        }
    }

    @Test
    fun closingKeyMaterialClearsSecretArrays() {
        val material = SolanaWalletKeyDerivation.generate()
        val phrase = material.recoveryPhrase
        val seed = material.secretSeed
        material.close()
        assertEquals(true, phrase.all { it == '\u0000' })
        assertEquals(true, seed.all { it == 0.toByte() })
    }
}
