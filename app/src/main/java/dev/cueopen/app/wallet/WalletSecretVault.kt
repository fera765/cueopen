package dev.cueopen.app.wallet

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
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
 * This is NOT a complete wallet. A vetted Solana recovery phrase/key derivation flow,
 * restore verification and transaction signer must be implemented before mainnet use.
 */
class WalletSecretVault(context: Context) {
    private val appContext = context.applicationContext
    private val file = File(appContext.noBackupFilesDir, SECRET_FILE)

    @Synchronized
    fun hasSecret(): Boolean = file.exists() && file.length() > 0

    /** Refuses replacement to avoid silently losing access to an existing wallet. */
    @Synchronized
    fun create(secret: ByteArray) {
        require(secret.size in MIN_SECRET_BYTES..MAX_SECRET_BYTES) { "Invalid secret length" }
        check(!hasSecret()) { "Wallet secret already exists" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(secret)
        val output = byteArrayOf(iv.size.toByte()) + iv + encrypted
        file.parentFile?.mkdirs()
        file.writeBytes(output)
        output.fill(0)
    }

    /** Caller must zero the returned secret bytes after use. */
    @Synchronized
    fun read(): ByteArray {
        check(hasSecret()) { "Wallet secret not initialized" }
        val input = file.readBytes()
        try {
            require(input.isNotEmpty()) { "Wallet secret file is empty" }
            val ivLength = input[0].toInt() and 0xff
            require(ivLength == GCM_IV_LENGTH && input.size > 1 + ivLength) {
                "Wallet secret file is malformed"
            }
            val iv = input.copyOfRange(1, 1 + ivLength)
            val ciphertext = input.copyOfRange(1 + ivLength, input.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getExistingKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            return cipher.doFinal(ciphertext)
        } finally {
            input.fill(0)
        }
    }

    /** Destructive local deletion; UI must require recovery confirmation first. */
    @Synchronized
    fun deleteLocalSecret() {
        if (file.exists()) {
            file.writeBytes(ByteArray(0))
            file.delete()
        }
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        keyStore.deleteEntry(KEY_ALIAS)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
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
    }
}
