package dev.fluwy.app.wallet

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SolanaWalletRepositoryTest {
    private lateinit var context: Context
    private lateinit var repository: SolanaWalletRepository
    private lateinit var vault: WalletSecretVault

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        repository = SolanaWalletRepository(context)
        vault = WalletSecretVault(context)
        clearWallet()
    }

    @After
    fun tearDown() {
        clearWallet()
    }

    private fun clearWallet() {
        runCatching { vault.deleteLocalSecret() }
        context.deleteSharedPreferences("solana_wallet_metadata")
    }

    @Test
    fun walletIsNotPersistedUntilBackupPhraseIsConfirmed() {
        val session = repository.beginCreate()
        val phrase = session.phraseForBackup()
        try {
            assertFalse(repository.hasWallet())
            val wrong = phrase.copyOf().also { it[0] = if (it[0] == 'a') 'b' else 'a' }
            try {
                session.confirmBackupAndPersist(wrong)
                throw AssertionError("Mismatched backup phrase must be rejected")
            } catch (expected: IllegalArgumentException) {
                assertFalse(repository.hasWallet())
            } finally {
                wrong.fill('\u0000')
            }

            session.confirmBackupAndPersist(phrase.copyOf())
            assertTrue(repository.hasWallet())
            assertTrue(repository.backupConfirmed())
            assertEquals(session.publicAddress, repository.publicAddress())
        } finally {
            phrase.fill('\u0000')
            session.close()
        }
    }

    @Test
    fun restoringConfirmedPhraseReproducesSameAddress() {
        val created = repository.beginCreate()
        val phrase = created.phraseForBackup()
        val expectedAddress = created.publicAddress
        try {
            created.confirmBackupAndPersist(phrase.copyOf())
        } finally {
            created.close()
        }

        // Simulate moving the same recovery phrase to a clean local wallet installation.
        clearWallet()
        val restored = repository.beginRestore(phrase.copyOf())
        val restoredPhrase = restored.phraseForBackup()
        try {
            assertEquals(expectedAddress, restored.publicAddress)
            restored.confirmBackupAndPersist(restoredPhrase.copyOf())
            assertEquals(expectedAddress, repository.publicAddress())
            assertTrue(repository.backupConfirmed())
        } finally {
            phrase.fill('\u0000')
            restoredPhrase.fill('\u0000')
            restored.close()
        }
    }
}
