package dev.cueopen.app.vault

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Cofre de cartões: isolado da camada de IA. Nenhum agente, ferramenta, prompt, log ou snapshot lê esta classe —
 * ela só é usada pela tela do Cofre. Os dados ficam cifrados (AES-256-GCM, chave própria no Android Keystore, que
 * não sai do aparelho) num arquivo separado do resto do app. O CVV nunca é guardado (regra PCI DSS).
 */
class CardVault(context: Context) {
    @Serializable
    data class Card(val id: String, val number: String, val holder: String, val expiry: String, val cpf: String, val addedAt: Long) {
        val last4 get() = number.takeLast(4)
        val brand get() = CardRules.brand(number)
        /** Única forma de exibir: mascarado. */
        val masked get() = "$brand •••• $last4"
    }

    private val prefs = context.applicationContext.getSharedPreferences("fluwy_cofre", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return gen.generateKey()
    }

    fun list(): List<Card> = runCatching {
        val raw = prefs.getString(ENTRY, null) ?: return emptyList()
        val blob = Base64.decode(raw, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob, 0, 12)) }
        json.decodeFromString(ListSerializer(Card.serializer()), cipher.doFinal(blob, 12, blob.size - 12).toString(Charsets.UTF_8))
    }.getOrDefault(emptyList())

    private fun write(cards: List<Card>) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val blob = cipher.iv + cipher.doFinal(json.encodeToString(ListSerializer(Card.serializer()), cards).toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString(ENTRY, Base64.encodeToString(blob, Base64.NO_WRAP)).commit()) { "Não foi possível gravar no Cofre." }
    }

    /** Valida e guarda. Devolve o erro legível ou null. */
    fun add(number: String, holder: String, expiry: String, cpf: String): String? {
        CardRules.validate(number, holder, expiry, cpf)?.let { return it }
        val digits = number.filter { it.isDigit() }
        val cards = list()
        if (cards.any { it.number == digits }) return "Este cartão já está no Cofre."
        write(cards + Card(java.util.UUID.randomUUID().toString().take(12), digits, holder.trim().uppercase(), CardRules.normExpiry(expiry), cpf.filter { it.isDigit() }, System.currentTimeMillis()))
        return null
    }

    fun remove(id: String) = write(list().filterNot { it.id == id })

    private companion object { const val ALIAS = "fluwy_cofre_cards_v1"; const val ENTRY = "cards" }
}

/** Regras puras (testáveis): Luhn, validade, CPF, bandeira. */
object CardRules {
    fun luhn(d: String): Boolean {
        if (d.length !in 13..19) return false
        var sum = 0
        d.reversed().forEachIndexed { i, ch -> var n = ch - '0'; if (i % 2 == 1) { n *= 2; if (n > 9) n -= 9 }; sum += n }
        return sum % 10 == 0
    }

    fun normExpiry(e: String): String { val d = e.filter { it.isDigit() }; return if (d.length >= 4) d.take(2) + "/" + d.substring(2, 4) else e }

    fun expiryOk(e: String, nowYear: Int, nowMonth: Int): Boolean {
        val d = e.filter { it.isDigit() }
        if (d.length != 4) return false
        val m = d.take(2).toInt(); val y = 2000 + d.substring(2).toInt()
        return m in 1..12 && (y > nowYear || (y == nowYear && m >= nowMonth))
    }

    fun cpfOk(raw: String): Boolean {
        val d = raw.filter { it.isDigit() }
        if (d.length != 11 || d.all { it == d[0] }) return false
        fun dv(n: Int): Int { val s = (0 until n).sumOf { (d[it] - '0') * (n + 1 - it) }; val r = s * 10 % 11; return if (r == 10) 0 else r }
        return dv(9) == d[9] - '0' && dv(10) == d[10] - '0'
    }

    fun brand(d: String): String = when {
        d.startsWith("4") -> "Visa"
        Regex("^(5[1-5]|2[2-7])").containsMatchIn(d) -> "Mastercard"
        Regex("^3[47]").containsMatchIn(d) -> "Amex"
        Regex("^(636368|438935|504175|451416|636297|5067|4576|4011|506699)").containsMatchIn(d) -> "Elo"
        Regex("^(606282|3841)").containsMatchIn(d) -> "Hipercard"
        else -> "Cartão"
    }

    fun validate(number: String, holder: String, expiry: String, cpf: String): String? {
        val cal = java.util.Calendar.getInstance()
        return when {
            !luhn(number.filter { it.isDigit() }) -> "Número do cartão inválido."
            holder.trim().length < 3 -> "Informe o nome do titular como está no cartão."
            !expiryOk(expiry, cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH) + 1) -> "Validade inválida ou vencida (use MM/AA)."
            !cpfOk(cpf) -> "CPF inválido."
            else -> null
        }
    }
}
