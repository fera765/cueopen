package dev.fluwy.app.vault

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.security.KeyStore
import java.text.Normalizer
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Chaves Pix do Cofre — cifradas no aparelho (mesma chave do Keystore do Cofre), nunca expostas ao agente. */
class PixVault(context: Context) {
    @Serializable
    data class PixKey(val id: String, val type: String, val key: String, val name: String, val city: String, val label: String = "", val addedAt: Long = 0) {
        val masked get() = PixRules.mask(type, key)
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

    fun list(): List<PixKey> = runCatching {
        migrateKey()
        val raw = prefs.getString(ENTRY, null) ?: return emptyList()
        decrypt(raw, key())
    }.getOrDefault(emptyList())

    private fun decrypt(raw: String, k: SecretKey): List<PixKey> {
        val blob = Base64.decode(raw, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, blob, 0, 12)) }
        return json.decodeFromString(ListSerializer(PixKey.serializer()), c.doFinal(blob, 12, blob.size - 12).toString(Charsets.UTF_8))
    }

    /**
     * Migração única: as chaves Pix eram cifradas com a mesma chave do Keystore dos cartões. Recifra com
     * uma chave própria para que a perda de uma não afete a outra. A chave antiga continua com os cartões.
     */
    private fun migrateKey() {
        val raw = prefs.getString(ENTRY, null) ?: return
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (ks.containsAlias(ALIAS)) return
        val old = ks.getKey(SHARED_ALIAS, null) as? SecretKey ?: return
        write(decrypt(raw, old))
    }

    private fun write(list: List<PixKey>) {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val blob = c.iv + c.doFinal(json.encodeToString(ListSerializer(PixKey.serializer()), list).toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString(ENTRY, Base64.encodeToString(blob, Base64.NO_WRAP)).commit()) { "Não foi possível gravar no Cofre." }
    }

    /** Valida, normaliza e guarda. Devolve erro legível ou null. */
    fun add(type: String, rawKey: String, name: String, city: String, label: String): String? {
        val k = PixRules.normalize(type, rawKey) ?: return PixRules.invalidMessage(type)
        if (PixRules.cleanText(name, 25).length < 3) return "Informe o nome do recebedor (como no banco)."
        if (PixRules.cleanText(city, 15).length < 2) return "Informe a cidade do recebedor."
        val all = list()
        if (all.any { it.key == k }) return "Esta chave já está no Cofre."
        write(all + PixKey(java.util.UUID.randomUUID().toString().take(12), type, k, PixRules.cleanText(name, 25), PixRules.cleanText(city, 15), label.trim().take(30), System.currentTimeMillis()))
        return null
    }

    fun remove(id: String) = write(list().filterNot { it.id == id })

    private companion object {
        const val ALIAS = "fluwy_cofre_pix_v1"
        /** Chave dos cartões, usada pelo Pix antes da separação (só para migrar). */
        const val SHARED_ALIAS = "fluwy_cofre_cards_v1"
        const val ENTRY = "pix"
    }
}

/**
 * Regras do Pix (BR Code estático do Banco Central): validação de chave, montagem do "copia e cola" com valor
 * fixo e CRC16, e leitura de um código existente. Funções puras (testáveis).
 */
object PixRules {
    val TYPES = listOf("CPF", "CNPJ", "EMAIL", "TELEFONE", "ALEATORIA")

    fun cleanText(s: String, max: Int): String = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        .replace(Regex("[^A-Za-z0-9 .\\-]"), "").replace(Regex("\\s+"), " ").trim().uppercase().take(max)

    fun cnpjOk(raw: String): Boolean {
        val d = raw.filter { it.isDigit() }
        if (d.length != 14 || d.all { it == d[0] }) return false
        fun dv(n: Int): Int { val w = if (n == 12) intArrayOf(5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2) else intArrayOf(6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2)
            val r = (0 until n).sumOf { (d[it] - '0') * w[it] } % 11; return if (r < 2) 0 else 11 - r }
        return dv(12) == d[12] - '0' && dv(13) == d[13] - '0'
    }

    /** Chave no formato oficial do DICT, ou null se inválida. */
    fun normalize(type: String, raw: String): String? {
        val t = raw.trim()
        return when (type) {
            "CPF" -> t.filter { it.isDigit() }.takeIf { CardRules.cpfOk(it) }
            "CNPJ" -> t.filter { it.isDigit() }.takeIf { cnpjOk(it) }
            "EMAIL" -> t.lowercase().takeIf { Regex("^[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}$").matches(it) && it.length <= 77 }
            "TELEFONE" -> t.filter { it.isDigit() }.let { d -> if (d.length in 10..11) "55$d" else d }.takeIf { it.length in 12..13 && it.startsWith("55") }?.let { "+$it" }
            "ALEATORIA" -> t.lowercase().takeIf { Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$").matches(it) }
            else -> null
        }
    }

    fun invalidMessage(type: String) = when (type) {
        "CPF" -> "CPF inválido."; "CNPJ" -> "CNPJ inválido."; "EMAIL" -> "E-mail inválido."
        "TELEFONE" -> "Telefone inválido (DDD + número)."; else -> "Chave aleatória inválida (formato 8-4-4-4-12)."
    }

    fun mask(type: String, k: String): String = when (type) {
        "CPF" -> "•••.${k.substring(3, 6)}.${k.substring(6, 9)}-••"
        "CNPJ" -> "${k.take(2)}.•••.•••/${k.substring(8, 12)}-••"
        "EMAIL" -> k.substringBefore('@').take(2) + "•••@" + k.substringAfter('@')
        "TELEFONE" -> k.dropLast(4).replace(Regex("\\d(?=\\d{4})"), "•") + k.takeLast(4)
        else -> k.take(8) + "-••••-••••-" + k.takeLast(4)
    }

    private fun tlv(id: String, v: String) = id + v.length.toString().padStart(2, '0') + v

    fun crc16(s: String): String {
        var crc = 0xFFFF
        s.toByteArray(Charsets.UTF_8).forEach { b ->
            crc = crc xor ((b.toInt() and 0xFF) shl 8)
            repeat(8) { crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1; crc = crc and 0xFFFF }
        }
        return "%04X".format(crc)
    }

    /** Valor em centavos → "123.45" (o BR Code usa ponto e 2 casas). */
    fun amountText(cents: Long): String = "%d.%02d".format(cents / 100, cents % 100)

    /**
     * Monta o Pix "copia e cola" com o VALOR FIXO (campo 54) e CRC16 no final: qualquer alteração no código
     * (valor, chave, recebedor) invalida o CRC e o banco recusa o QR.
     */
    fun payload(key: String, name: String, city: String, cents: Long, txid: String = "***", description: String = ""): String {
        // cents = 0 → QR de VALOR LIVRE (sem o campo 54): quem paga digita o valor no banco.
        require(cents in 0..99_999_999_99L) { "Valor inválido." }
        val desc = cleanText(description, 40)
        val account = tlv("00", "br.gov.bcb.pix") + tlv("01", key) + (if (desc.isNotBlank()) tlv("02", desc) else "")
        val tx = txid.filter { it.isLetterOrDigit() }.take(25).ifBlank { "***" }
        val body = tlv("00", "01") + tlv("01", "12") + tlv("26", account) + tlv("52", "0000") + tlv("53", "986") +
            (if (cents > 0) tlv("54", amountText(cents)) else "") + tlv("58", "BR") + tlv("59", cleanText(name, 25)) + tlv("60", cleanText(city, 15)) +
            tlv("62", tlv("05", tx)) + "6304"
        return body + crc16(body)
    }

    /**
     * Monta e CONFERE o código antes de mostrar: a releitura tem que bater em chave, valor (ou ausência dele) e CRC.
     * Usado pela tela do Cofre e pela ferramenta do agente — um só caminho para gerar Pix.
     */
    fun checkedPayload(key: String, name: String, city: String, cents: Long, txid: String, description: String = ""): String {
        val p = payload(key, name, city, cents, txid, description)
        val back = parse(p) ?: error("Falha ao montar o código Pix.")
        check(back.crcOk && back.key == key && back.amount == (if (cents > 0) amountText(cents) else null)) { "O código gerado não passou na conferência." }
        return p
    }

    fun newTxid(): String = "FLW" + java.util.UUID.randomUUID().toString().replace("-", "").take(20).uppercase()

    /** "10", "10,5", "R$ 1.234,56", "1234.56" → centavos; vazio/0 → 0 (valor livre); inválido → null. */
    fun parseCents(raw: String?): Long? {
        val t = raw.orEmpty().replace("R$", "").replace(" ", "").trim()
        if (t.isEmpty()) return 0L
        val norm = when {
            t.contains(',') -> t.replace(".", "").replace(',', '.')
            t.count { it == '.' } > 1 -> t.replace(".", "")
            else -> t
        }
        val v = norm.toBigDecimalOrNull() ?: return null
        if (v.signum() < 0) return null
        val c = runCatching { v.movePointRight(2).setScale(0, java.math.RoundingMode.UNNECESSARY).toLong() }.getOrNull() ?: return null
        return if (c > 99_999_999_99L) null else c
    }

    fun money(cents: Long): String = "R$ " + "%,d".format(java.util.Locale.US, cents / 100).replace(',', '.') + "," + "%02d".format(cents % 100)

    data class Parsed(val key: String, val name: String, val city: String, val amount: String?, val crcOk: Boolean)

    private fun fields(s: String): Map<String, String>? {
        val out = LinkedHashMap<String, String>(); var i = 0
        while (i + 4 <= s.length) {
            val id = s.substring(i, i + 2); val len = s.substring(i + 2, i + 4).toIntOrNull() ?: return null
            if (i + 4 + len > s.length) return null
            out[id] = s.substring(i + 4, i + 4 + len); i += 4 + len
        }
        return out.takeIf { i == s.length }
    }

    /** Lê um Pix copia e cola (ex.: para cadastrar a chave de um QR). Null se não for um BR Code Pix. */
    fun parse(code: String): Parsed? {
        val s = code.trim()
        val f = fields(s) ?: return null
        val acc = f["26"]?.let { fields(it) } ?: return null
        if (acc["00"]?.lowercase() != "br.gov.bcb.pix") return null
        val key = acc["01"] ?: return null
        val crcOk = s.length > 8 && crc16(s.dropLast(4)) == s.takeLast(4).uppercase()
        return Parsed(key, f["59"].orEmpty(), f["60"].orEmpty(), f["54"], crcOk)
    }

    /** Tipo provável de uma chave lida de um QR. */
    fun guessType(key: String): String = when {
        key.startsWith("+") -> "TELEFONE"
        key.contains("@") -> "EMAIL"
        Regex("^[0-9a-f-]{36}$").matches(key) -> "ALEATORIA"
        key.length == 14 -> "CNPJ"
        else -> "CPF"
    }
}
