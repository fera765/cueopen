package dev.cueopen.app.wallet

import android.content.Context

/**
 * Coordinates local wallet setup. Secret material remains in process memory until
 * the user verifies the recovery phrase; no network or LLM calls are made here.
 */
class SolanaWalletRepository(context: Context) {
    private val appContext = context.applicationContext
    private val vault = WalletSecretVault(appContext)
    private val prefs = appContext.getSharedPreferences("solana_wallet_metadata", Context.MODE_PRIVATE)

    @Synchronized
    fun hasWallet(): Boolean = vault.hasSecret()

    @Synchronized
    fun publicAddress(): String? {
        if (!vault.hasSecret()) return null
        return prefs.getString("public_address", null)
            ?: error("Wallet metadata missing; recover using the recovery phrase")
    }

    @Synchronized
    fun backupConfirmed(): Boolean =
        vault.hasSecret() && prefs.getBoolean("backup_confirmed", false)

    /**
     * Repairs lost public metadata only after the supplied recovery phrase derives the
     * exact secret already encrypted in this device's vault. Never replaces the vault.
     */
    @Synchronized
    fun repairMetadataFromRecoveryPhrase(phrase: CharArray): String {
        try {
            check(vault.hasSecret()) { "No local wallet secret exists to recover" }
            val material = SolanaWalletKeyDerivation.restore(phrase)
            var storedSeed: ByteArray? = null
            try {
                storedSeed = vault.read()
                require(constantTimeEquals(storedSeed, material.secretSeed)) {
                    "Recovery phrase does not match the wallet stored on this device"
                }
                check(prefs.edit()
                    .putString("public_address", material.publicAddress)
                    .putBoolean("backup_confirmed", true)
                    .commit()) { "Could not repair wallet metadata" }
                return material.publicAddress
            } finally {
                storedSeed?.fill(0)
                material.close()
            }
        } finally {
            // Consume the caller's phrase even when the vault is missing or unreadable.
            phrase.fill(0.toChar())
        }
    }

    private fun constantTimeEquals(left: ByteArray, right: ByteArray): Boolean {
        var difference = left.size xor right.size
        for (index in 0 until maxOf(left.size, right.size)) {
            val a = if (index < left.size) left[index].toInt() and 0xff else 0
            val b = if (index < right.size) right[index].toInt() and 0xff else 0
            difference = difference or (a xor b)
        }
        return difference == 0
    }

    @Synchronized
    fun beginCreate(): WalletSetupSession {
        check(!vault.hasSecret()) { "Wallet already exists" }
        return WalletSetupSession(SolanaWalletKeyDerivation.generate()) { material ->
            persist(material)
        }
    }

    @Synchronized
    fun beginRestore(phrase: CharArray): WalletSetupSession {
        try {
            check(!vault.hasSecret()) { "Wallet already exists" }
            return WalletSetupSession(SolanaWalletKeyDerivation.restore(phrase)) { material ->
                persist(material)
            }
        } finally {
            // This method consumes the caller-provided phrase, including when restore fails.
            phrase.fill(0.toChar())
        }
    }

    @Synchronized
    private fun persist(material: WalletKeyMaterial) {
        check(!vault.hasSecret()) { "Wallet already exists" }
        var secretWritten = false
        try {
            vault.create(material.secretSeed)
            secretWritten = true
            check(prefs.edit()
                .putString("public_address", material.publicAddress)
                .putBoolean("backup_confirmed", true)
                .commit()) { "Could not save wallet metadata" }
        } catch (error: Throwable) {
            if (secretWritten) runCatching { vault.deleteLocalSecret() }
            prefs.edit().remove("public_address").remove("backup_confirmed").commit()
            throw error
        }
    }
}

/** Short-lived setup object; do not log or serialize it. */
class WalletSetupSession internal constructor(
    private val material: WalletKeyMaterial,
    private val persist: (WalletKeyMaterial) -> Unit
) : AutoCloseable {
    private var completed = false
    private var closed = false

    val publicAddress: String
        get() { check(!closed); return material.publicAddress }

    /** Returns a caller-owned copy; the caller must clear it after display. */
    fun phraseForBackup(): CharArray {
        check(!closed && !completed)
        return material.recoveryPhrase.copyOf()
    }

    @Synchronized
    fun confirmBackupAndPersist(confirmation: CharArray) {
        try {
            check(!closed && !completed) { "Wallet setup session is no longer available" }
            require(RecoveryPhraseConfirmation.matches(confirmation, material.recoveryPhrase)) {
                "Recovery phrase confirmation does not match"
            }
            persist(material)
            completed = true
            // The encrypted vault now owns the persisted copy; erase setup material immediately.
            material.close()
        } finally {
            confirmation.fill('\u0000')
        }
    }

    @Synchronized
    override fun close() {
        if (!closed) {
            closed = true
            material.close()
        }
    }

}
