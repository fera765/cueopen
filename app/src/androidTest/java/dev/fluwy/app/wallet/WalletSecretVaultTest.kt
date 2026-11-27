package dev.fluwy.app.wallet

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import java.io.File
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WalletSecretVaultTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val vault = WalletSecretVault(context)
    private val secretFile = File(context.noBackupFilesDir, "solana-wallet-secret.enc")
    private val tempFile = File(context.noBackupFilesDir, "solana-wallet-secret.enc.tmp")

    @After
    fun cleanUp() {
        // Also removes the test-only Android Keystore alias so cases remain isolated.
        vault.deleteLocalSecret()
        secretFile.delete()
        tempFile.delete()
    }

    @Test
    fun createAndReadRoundTripsSecretWithoutReplacingCallerBuffer() {
        val original = ByteArray(32) { index -> (index + 1).toByte() }
        val expected = original.copyOf()
        try {
            vault.create(original)
            assertTrue(vault.hasSecret())
            val restored = vault.read()
            try {
                assertArrayEquals(expected, restored)
            } finally {
                restored.fill(0)
            }
            assertArrayEquals(expected, original)
            assertFalse(tempFile.exists())
        } finally {
            original.fill(0)
            expected.fill(0)
        }
    }

    @Test
    fun createRefusesToOverwriteExistingSecret() {
        val first = ByteArray(32) { 7 }
        val second = ByteArray(32) { 9 }
        try {
            vault.create(first)
            try {
                vault.create(second)
                throw AssertionError("Expected existing secret to be protected")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message.orEmpty().contains("already exists"))
            }
            val restored = vault.read()
            try {
                assertArrayEquals(first, restored)
            } finally {
                restored.fill(0)
            }
        } finally {
            first.fill(0)
            second.fill(0)
        }
    }

    @Test
    fun authenticatedEncryptionRejectsCiphertextTampering() {
        val secret = ByteArray(32) { index -> (index * 3 + 1).toByte() }
        try {
            vault.create(secret)
            val encrypted = secretFile.readBytes()
            encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 0x01).toByte()
            secretFile.writeBytes(encrypted)
            encrypted.fill(0)
            try {
                val unexpected = vault.read()
                unexpected.fill(0)
                throw AssertionError("Expected AES-GCM authentication to reject tampering")
            } catch (expected: Exception) {
                // Expected: tampered ciphertext fails AEAD authentication and reads fail closed.
                // (If read() did NOT throw, the AssertionError above — an Error, not an Exception —
                // propagates past this catch and correctly fails the test.)
            }
        } finally {
            secret.fill(0)
        }
    }

    @Test
    fun missingSecretFailsClosed() {
        vault.deleteLocalSecret()
        try {
            vault.read()
            throw AssertionError("Expected missing secret to fail")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("not initialized"))
        }
    }
}
