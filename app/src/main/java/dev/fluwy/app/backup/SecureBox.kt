package dev.fluwy.app.backup

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Criptografia da parte sensível do backup (cartões, Pix, carteira, chaves de API, sessões do browser).
 *
 * Senha → PBKDF2-HMAC-SHA256 (210 mil iterações, sal aleatório) → 64 bytes: 32 para AES-256-CTR e 32 para
 * HMAC-SHA256 (cifra-depois-autentica). Funciona em fluxo, então serve para dados grandes (perfil do browser)
 * sem carregar tudo na memória. A autenticação é conferida inteira ANTES de decifrar: senha errada ou arquivo
 * alterado nunca produz dados.
 *
 * Formato: "FLWB1" | sal(16) | iv(16) | cifrado | hmac(32) — o HMAC cobre cabeçalho, sal, iv e cifrado.
 */
object SecureBox {
    private val MAGIC = "FLWB1".toByteArray()
    private const val ITERATIONS = 210_000
    private const val SALT = 16
    private const val IV = 16
    private const val TAG = 32

    class WrongPassword : Exception("Senha incorreta ou arquivo de backup alterado.")

    private fun keys(password: CharArray, salt: ByteArray): Pair<SecretKeySpec, SecretKeySpec> {
        val spec = PBEKeySpec(password, salt, ITERATIONS, 512)
        val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return SecretKeySpec(raw.copyOfRange(0, 32), "AES") to SecretKeySpec(raw.copyOfRange(32, 64), "HmacSHA256")
    }

    /** Cifra [input] em [out]. */
    fun encrypt(password: CharArray, input: InputStream, out: OutputStream) {
        val rnd = SecureRandom()
        val salt = ByteArray(SALT).also { rnd.nextBytes(it) }
        val iv = ByteArray(IV).also { rnd.nextBytes(it) }
        val (enc, macKey) = keys(password, salt)
        val cipher = Cipher.getInstance("AES/CTR/NoPadding").apply { init(Cipher.ENCRYPT_MODE, enc, IvParameterSpec(iv)) }
        val mac = Mac.getInstance("HmacSHA256").apply { init(macKey) }
        for (part in listOf(MAGIC, salt, iv)) { out.write(part); mac.update(part) }
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf); if (n < 0) break
            val c = cipher.update(buf, 0, n) ?: continue
            out.write(c); mac.update(c)
        }
        cipher.doFinal()?.takeIf { it.isNotEmpty() }?.let { out.write(it); mac.update(it) }
        out.write(mac.doFinal())
        out.flush()
    }

    /** Confere a senha/integridade de [file] e decifra para [out]. Lança [WrongPassword] se não bater. */
    fun decrypt(password: CharArray, file: File, out: OutputStream) {
        val total = file.length()
        val header = MAGIC.size + SALT + IV
        if (total < header + TAG) throw WrongPassword()
        val head = file.inputStream().use { s -> ByteArray(header).also { readFully(s, it) } }
        if (!head.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) throw IllegalArgumentException("Parte criptografada do backup em formato desconhecido.")
        val salt = head.copyOfRange(MAGIC.size, MAGIC.size + SALT)
        val iv = head.copyOfRange(MAGIC.size + SALT, header)
        val (enc, macKey) = keys(password, salt)
        val bodyLen = total - header - TAG
        // 1) autentica tudo primeiro
        val mac = Mac.getInstance("HmacSHA256").apply { init(macKey) }
        val tag = ByteArray(TAG)
        file.inputStream().buffered().use { s ->
            val h = ByteArray(header); readFully(s, h); mac.update(h)
            copyN(s, bodyLen) { b, n -> mac.update(b, 0, n) }
            readFully(s, tag)
        }
        if (!MessageDigest.isEqual(mac.doFinal(), tag)) throw WrongPassword()
        // 2) só então decifra
        val cipher = Cipher.getInstance("AES/CTR/NoPadding").apply { init(Cipher.DECRYPT_MODE, enc, IvParameterSpec(iv)) }
        file.inputStream().buffered().use { s ->
            s.skipFully(header.toLong())
            copyN(s, bodyLen) { b, n -> cipher.update(b, 0, n)?.let { out.write(it) } }
        }
        cipher.doFinal()?.takeIf { it.isNotEmpty() }?.let { out.write(it) }
        out.flush()
    }

    private fun InputStream.skipFully(n: Long) { var left = n; while (left > 0) { val s = skip(left); if (s <= 0) { if (read() < 0) break; left-- } else left -= s } }

    private fun readFully(s: InputStream, b: ByteArray) {
        var off = 0
        while (off < b.size) { val n = s.read(b, off, b.size - off); if (n < 0) throw WrongPassword(); off += n }
    }

    private inline fun copyN(s: InputStream, count: Long, sink: (ByteArray, Int) -> Unit) {
        val buf = ByteArray(64 * 1024)
        var left = count
        while (left > 0) {
            val n = s.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw WrongPassword()
            sink(buf, n); left -= n
        }
    }
}
