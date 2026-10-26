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

    @Synchronized
    fun beginCreate(): WalletSetupSession {
        check(!vault.hasSecret()) { "Wallet already exists" }
        return WalletSetupSession(SolanaWalletKeyDerivation.generate()) { material ->
            persist(material)
        }
    }

    @Synchronized
    fun beginRestore(phrase: CharArray): WalletSetupSession {
        check(!vault.hasSecret()) { "Wallet already exists" }
        return WalletSetupSession(SolanaWalletKeyDerivation.restore(phrase)) { material ->
            persist(material)
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
            require(matchesRecoveryPhrase(confirmation, material.recoveryPhrase)) {
                "Recovery phrase confirmation does not match"
            }
            persist(material)
            completed = true
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

    /**
     * Canonicalizes BIP-39 English phrase input without constructing immutable Strings.
     * Only whitespace is collapsed and ASCII letters are lowercased; mnemonic words
     * themselves are validated by the BIP-39 implementation during generation/restore.
     */
    private fun normalize(value: CharArray): CharArray {
        val normalized = CharArray(value.size)
        var written = 0
        var pendingSpace = false
        for (character in value) {
            if (character.isWhitespace()) {
                if (written > 0) pendingSpace = true
                continue
            }
            if (pendingSpace) {
                normalized[written++] = ' '
                pendingSpace = false
            }
            normalized[written++] = if (character in 'A'..'Z') {
                (character.code + ('a'.code - 'A'.code)).toChar()
            } else {
                character
            }
        }
        return normalized.copyOf(written).also { normalized.fill('\\u0000') }
    }

    private fun matchesRecoveryPhrase(candidate: CharArray, expected: CharArray): Boolean {
        val left = normalize(candidate)
        val right = normalize(expected)
        return try {
            var difference = left.size xor right.size
            val count = maxOf(left.size, right.size)
            for (index in 0 until count) {
                val a = if (index < left.size) left[index].code else 0
                val b = if (index < right.size) right[index].code else 0
                difference = difference or (a xor b)
            }
            difference == 0
        } finally {
            left.fill('\\u0000')
            right.fill('\\u0000')
        }
    }
}
