package dev.fluwy.app.wallet

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
            recoveryPhrase.fill(0.toChar())
        }
    }

    @Test
    fun restoreRejectsAnInvalidMnemonicChecksum() {
        val invalidPhrase = (
            "abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon abandon"
            ).toCharArray()
        try {
            assertThrows(RuntimeException::class.java) {
                SolanaWalletKeyDerivation.restore(invalidPhrase)
            }
        } finally {
            invalidPhrase.fill(0.toChar())
        }
    }

    @Test
    fun closingKeyMaterialClearsSecretArrays() {
        val material = SolanaWalletKeyDerivation.generate()
        val phrase = material.recoveryPhrase
        val seed = material.secretSeed
        material.close()
        assertEquals(true, phrase.all { it == 0.toChar() })
        assertEquals(true, seed.all { it == 0.toByte() })
    }

    @Test
    fun matchesIndependentSolanaDerivationVector() {
        val phrase = (
            "abandon abandon abandon abandon abandon abandon " +
                "abandon abandon abandon abandon abandon about"
            ).toCharArray()
        try {
            val restored = SolanaWalletKeyDerivation.restore(phrase)
            try {
                assertEquals(
                    "HAgk14JpMQLgt6rVgv7cBQFJWFto5Dqxi472uT3DKpqk",
                    restored.publicAddress
                )
            } finally {
                restored.close()
            }
        } finally {
            phrase.fill(0.toChar())
        }
    }
}
