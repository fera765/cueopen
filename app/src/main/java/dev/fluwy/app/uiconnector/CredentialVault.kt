package dev.fluwy.app.uiconnector

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Cofre das credenciais dos UI Connectors. Cifra com AES-256-GCM usando uma chave que vive no
 * Android Keystore (não exportável): o arquivo de preferências sozinho não revela nada. As
 * credenciais só são lidas pelo próprio conector na hora de (re)logar; nunca são entregues ao LLM.
 */
class CredentialVault(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return newKey()
    }

    private fun newKey(): SecretKey {
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun has(connectorId: String): Boolean = prefs.contains(entry(connectorId))

    fun save(connectorId: String, credentials: Map<String, String>) {
        val plain = JsonObject(credentials.mapValues { JsonPrimitive(it.value) }).toString().toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val blob = cipher.iv + cipher.doFinal(plain)
        check(prefs.edit().putString(entry(connectorId), Base64.encodeToString(blob, Base64.NO_WRAP)).commit()) {
            "Não foi possível gravar as credenciais no cofre."
        }
    }

    /** Devolve as credenciais ou null (ausentes ou ilegíveis — ex.: chave do Keystore invalidada). */
    fun load(connectorId: String): Map<String, String>? = runCatching {
        val raw = prefs.getString(entry(connectorId), null) ?: return null
        val blob = Base64.decode(raw, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORM).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, IV_BYTES))
        }
        val plain = cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES).toString(Charsets.UTF_8)
        (Json.parseToJsonElement(plain) as JsonObject).mapValues { it.value.let { v -> (v as JsonPrimitive).contentOrNull.orEmpty() } }
    }.getOrNull()

    fun clear(connectorId: String) {
        prefs.edit().remove(entry(connectorId)).apply()
    }

    private fun entry(id: String) = "cred_$id"

    /**
     * Migração única: recifra as credenciais guardadas com a chave antiga [oldAlias] usando a chave
     * atual e apaga a antiga. Nada sai do aparelho; entradas ilegíveis são mantidas como estão.
     */
    fun migrateKey(oldAlias: String) {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        val old = ks.getKey(oldAlias, null) as? SecretKey ?: return
        val edit = prefs.edit()
        prefs.all.forEach { (k, v) ->
            if (!k.startsWith("cred_") || v !is String) return@forEach
            runCatching {
                val blob = Base64.decode(v, Base64.NO_WRAP)
                val dec = Cipher.getInstance(TRANSFORM).apply { init(Cipher.DECRYPT_MODE, old, GCMParameterSpec(128, blob, 0, IV_BYTES)) }
                val plain = dec.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
                val enc = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
                edit.putString(k, Base64.encodeToString(enc.iv + enc.doFinal(plain), Base64.NO_WRAP))
            }
        }
        if (edit.commit()) ks.deleteEntry(oldAlias)
    }

    private companion object {
        const val PREFS = "uic_vault"
        const val PROVIDER = "AndroidKeyStore"
        const val ALIAS = "fluwy_uic_vault_v1"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
