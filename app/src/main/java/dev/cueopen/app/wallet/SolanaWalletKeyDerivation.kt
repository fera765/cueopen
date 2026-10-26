package dev.cueopen.app.wallet

import cash.z.ecc.android.bip39.Mnemonics.MnemonicCode
import cash.z.ecc.android.bip39.Mnemonics.WordCount
import cash.z.ecc.android.bip39.Mnemonics.toSeed
import org.sol4k.Keypair
import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * BIP-39 + SLIP-0010 Ed25519 key derivation for Solana.
 *
 * Path is deliberately fixed to m/44'/501'/0'/0' until multi-account support is designed.
 * The BIP-39 phrase is the recovery backup; the 32-byte derived seed is what the local vault
 * encrypts. This class never performs network I/O and must never pass secret material to an LLM.
 *
 * Compatibility tests against independently generated wallet vectors are a release requirement.
 */
object SolanaWalletKeyDerivation {
    const val DERIVATION_PATH = "m/44'/501'/0'/0'"
    private val pathIndices = intArrayOf(44, 501, 0, 0)
    private const val HMAC_SHA512 = "HmacSHA512"
    private val masterKey = "ed25519 seed".toByteArray(Charsets.US_ASCII)

    fun generate(): WalletKeyMaterial {
        val mnemonic = MnemonicCode(WordCount.COUNT_24)
        return materialFromMnemonic(mnemonic, generated = true)
    }

    /** The input is copied and cleared internally; callers should also clear their input array. */
    fun restore(recoveryPhrase: CharArray): WalletKeyMaterial {
        require(recoveryPhrase.isNotEmpty()) { "Recovery phrase is required" }
        val phraseCopy = recoveryPhrase.copyOf()
        val mnemonic = try {
            MnemonicCode(phraseCopy)
        } catch (t: Throwable) {
            phraseCopy.fill('\u0000')
            throw t
        }
        return materialFromMnemonic(mnemonic, generated = false)
    }

    private fun materialFromMnemonic(mnemonic: MnemonicCode, generated: Boolean): WalletKeyMaterial {
        var bip39Seed: ByteArray? = null
        var keySeed: ByteArray? = null
        val phraseForDisplay = mnemonic.chars.copyOf()
        try {
            // Generated phrases are valid by construction; restored phrases are checksum-validated.
            bip39Seed = mnemonic.toSeed(validate = !generated)
            keySeed = deriveSolanaSeed(bip39Seed)
            val address = Keypair.fromSecretKey(keySeed).publicKey.toBase58()
            return WalletKeyMaterial(phraseForDisplay, keySeed, address).also {
                keySeed = null // ownership transferred to WalletKeyMaterial
            }
        } catch (t: Throwable) {
            phraseForDisplay.fill('\u0000')
            throw t
        } finally {
            mnemonic.clear()
            bip39Seed?.fill(0)
            keySeed?.fill(0)
        }
    }

    private fun deriveSolanaSeed(seed: ByteArray): ByteArray {
        var digest = hmac(masterKey, seed)
        var privateKey = digest.copyOfRange(0, 32)
        var chainCode = digest.copyOfRange(32, 64)
        digest.fill(0)

        try {
            for (index in pathIndices) {
                val data = ByteArray(1 + privateKey.size + 4)
                data[0] = 0
                privateKey.copyInto(data, destinationOffset = 1)
                ByteBuffer.wrap(data, 1 + privateKey.size, 4)
                    .putInt(index or Int.MIN_VALUE)
                digest = hmac(chainCode, data)
                data.fill(0)

                privateKey.fill(0)
                chainCode.fill(0)
                privateKey = digest.copyOfRange(0, 32)
                chainCode = digest.copyOfRange(32, 64)
                digest.fill(0)
            }
            return privateKey.copyOf()
        } finally {
            privateKey.fill(0)
            chainCode.fill(0)
            digest.fill(0)
        }
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC_SHA512)
        mac.init(SecretKeySpec(key, HMAC_SHA512))
        return mac.doFinal(data)
    }
}

/** Secret-bearing object: do not log, serialize, or expose through data-class toString(). */
class WalletKeyMaterial internal constructor(
    val recoveryPhrase: CharArray,
    val secretSeed: ByteArray,
    val publicAddress: String
) : AutoCloseable {
    override fun close() {
        recoveryPhrase.fill('\u0000')
        secretSeed.fill(0)
    }
}
