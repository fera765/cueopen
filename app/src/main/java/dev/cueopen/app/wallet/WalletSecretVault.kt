package dev.cueopen.app.wallet

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypted local secret storage primitive for the CueOpen wallet.
 * Ciphertext is kept in noBackupFilesDir so device-bound ciphertext is not silently
 * restored to another phone without its Android Keystore key.
 *
 * This is NOT a complete wallet. Recovery phrase handling, restore verification and
 * transaction signing must be completed before mainnet use.
 */
class WalletSecretVault(context: Context) {
    private val appContext = context.applicationContext
    private val file = File(appContext.noBackupFilesDir, SECRET_FILE)
    private val tempFile = File(appContext.noBackupFilesDir, "$SECRET_FILE.tmp")

    @Synchronized
    fun hasSecret(): Boolean = file.exists() && file.length() > 0

    /** Refuses replacement and writes ciphertext through a synced temporary file. */
    @Synchronized
    fun create(secret: ByteArray) {
        require(secret.size in MIN_SECRET_BYTES..MAX_SECRET_BYTES) { "Invalid secret length" }
        check(!file.exists()) { "Wallet secret file already exists; inspect or recover it before replacing" }
        val parent = file.parentFile ?: error("Wallet storage directory unavailable")
        check(parent.exists() || parent.mkdirs()) { "Cannot create wallet storage directory" }

        var encrypted: ByteArray? = null
        var output: ByteArray? = null
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val iv = cipher.iv
            require(iv.size == GCM_IV_LENGTH) { "Unexpected AES-GCM IV length" }
            encrypted = cipher.doFinal(secret)
            output = byteArrayOf(iv.size.toByte()) + iv + encrypted

            // A failed write never exposes a partially written final wallet file.
            if (tempFile.exists()) check(tempFile.delete()) { "Cannot clear stale wallet temp file" }
            FileOutputStream(tempFile).use { stream ->
                stream.write(output)
                stream.fd.sync()
            }
            check(!file.exists()) { "Wallet secret file appeared during creation" }
            check(tempFile.renameTo(file)) { "Cannot atomically publish encrypted wallet secret" }
        } finally {
            encrypted?.fill(0)
            output?.fill(0)
            // If publication failed, remove the incomplete temporary ciphertext.
            if (tempFile.exists()) tempFile.delete()
        }
    }

    /** Caller must zero the returned secret bytes after use. */
    @Synchronized
    fun read(): ByteArray {
        check(file.exists()) { "Wallet secret not initialized" }
        check(file.length() in MIN_FILE_BYTES..MAX_FILE_BYTES) { "Wallet secret file has invalid size" }
        val input = file.readBytes()
        var iv: ByteArray? = null
        var ciphertext: ByteArray? = null
        try {
            val ivLength = input[0].toInt() and 0xff
            require(ivLength == GCM_IV_LENGTH && input.size > 1 + ivLength) {
                "Wallet secret file is malformed"
            }
            iv = input.copyOfRange(1, 1 + ivLength)
            ciphertext = input.copyOfRange(1 + ivLength, input.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getExistingKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            return cipher.doFinal(ciphertext)
        } finally {
            input.fill(0)
            iv?.fill(0)
            ciphertext?.fill(0)
        }
    }

    /** Destructive deletion; the UI must require recovery confirmation first. */
    @Synchronized
    fun deleteLocalSecret() {
        if (file.exists()) check(file.delete()) { "Cannot delete encrypted wallet file" }
        if (tempFile.exists()) check(tempFile.delete()) { "Cannot delete temporary wallet file" }
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        keyStore.deleteEntry(KEY_ALIAS)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        // Never silently replace an unexpected existing alias: doing so could make an
        // existing ciphertext permanently unreadable while appearing to create a new key.
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return keyStore.getKey(KEY_ALIAS, null) as? SecretKey
                ?: error("Wallet encryption key alias exists but is not a usable secret key")
        }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun getExistingKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return keyStore.getKey(KEY_ALIAS, null) as? SecretKey
            ?: error("Wallet encryption key missing; restore using the recovery phrase")
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "cueopen.solana.wallet.secret.v1"
        private const val SECRET_FILE = "solana-wallet-secret.enc"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_BITS = 128
        private const val MIN_SECRET_BYTES = 16
        private const val MAX_SECRET_BYTES = 256
        private const val MIN_FILE_BYTES = 1 + GCM_IV_LENGTH + 16 + MIN_SECRET_BYTES
        private const val MAX_FILE_BYTES = 1 + GCM_IV_LENGTH + 16 + MAX_SECRET_BYTES
    }
}
