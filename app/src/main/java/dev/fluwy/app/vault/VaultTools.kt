package dev.fluwy.app.vault

import android.content.Context
import dev.fluwy.app.data.ChatItem

/**
 * Única ponte entre o agente e o Cofre. Regra: nada que sai daqui vai para a IA com dado sensível — só apelidos,
 * tipo e bandeira. Chave Pix, número de cartão, CPF, validade e o código "copia e cola" NUNCA aparecem no texto
 * devolvido; o QR é desenhado pelo app direto no chat a partir do Cofre.
 */
object VaultTools {
    data class KeyRef(val id: String, val label: String, val type: String)
    data class CardRef(val label: String, val brand: String)

    private fun typeName(t: String) = when (t) { "cpf" -> "CPF"; "cnpj" -> "CNPJ"; "email" -> "e-mail"; "phone" -> "celular"; "evp" -> "aleatória"; else -> t }
    fun labelOf(k: KeyRef) = k.label.ifBlank { "Chave ${typeName(k.type)}" }

    /** Texto para a IA: apelidos e ids, sem nenhum dígito da chave ou do cartão. */
    fun describe(keys: List<KeyRef>, cards: List<CardRef>): String = buildString {
        appendLine("Cofre do usuário (dados sensíveis ficam no aparelho e não são mostrados a você):")
        if (keys.isEmpty()) appendLine("- Nenhuma chave Pix cadastrada (o usuário cadastra em Cofre › Pix).")
        keys.forEach { appendLine("- Pix id=${it.id} · \"${labelOf(it)}\" (${typeName(it.type)})") }
        if (cards.isEmpty()) appendLine("- Nenhum cartão cadastrado.")
        cards.forEach { appendLine("- Cartão \"${it.label}\" (${it.brand})") }
        append("Para cobrar, use pix_qr com o id (ou apelido) e o valor. Cartões: você não usa nem digita dados de cartão; pagamentos são feitos pelo próprio usuário.")
    }

    /** Escolhe a chave pelo id ou apelido; com uma chave só, ela é a padrão. */
    fun pick(keys: List<KeyRef>, ref: String): Result<KeyRef> {
        val r = ref.trim()
        if (keys.isEmpty()) return Result.failure(IllegalStateException("Nenhuma chave Pix no Cofre. Peça ao usuário para cadastrar em Cofre › Pix."))
        if (r.isEmpty()) return if (keys.size == 1) Result.success(keys[0]) else Result.failure(IllegalArgumentException("Há ${keys.size} chaves Pix: informe 'chave' com o id ou apelido (veja cofre_listar)."))
        val hit = keys.firstOrNull { it.id == r } ?: keys.firstOrNull { labelOf(it).equals(r, true) }
            ?: keys.filter { labelOf(it).contains(r, true) }.singleOrNull()
        return hit?.let { Result.success(it) } ?: Result.failure(IllegalArgumentException("Chave Pix '$r' não encontrada (veja cofre_listar)."))
    }

    fun list(ctx: Context): String = describe(
        runCatching { PixVault(ctx).list().map { KeyRef(it.id, it.label, it.type) } }.getOrDefault(emptyList()),
        runCatching { CardVault(ctx).list().mapIndexed { i, c -> CardRef("Cartão ${i + 1}", CardRules.brand(c.number)) } }.getOrDefault(emptyList()),
    )

    /** Gera a cobrança: devolve o item do chat (sem chave nem código) e o texto seguro para a IA. */
    fun pixQr(ctx: Context, keyRef: String, amount: String?, description: String): Pair<ChatItem.PixQr?, String> {
        val all = runCatching { PixVault(ctx).list() }.getOrDefault(emptyList())
        val k = pick(all.map { KeyRef(it.id, it.label, it.type) }, keyRef).getOrElse { return null to "ERRO: ${it.message}" }
        val cents = PixRules.parseCents(amount) ?: return null to "ERRO: valor inválido (use reais, ex.: 25,90). Deixe vazio para valor livre."
        val full = all.first { it.id == k.id }
        val txid = PixRules.newTxid()
        // Confere o código agora (mesmo caminho da tela); a IA só recebe o resultado.
        runCatching { PixRules.checkedPayload(full.key, full.name, full.city, cents, txid, description) }.onFailure { return null to "ERRO: ${it.message}" }
        val item = ChatItem.PixQr(keyId = k.id, keyLabel = labelOf(k), cents = cents, description = PixRules.cleanText(description, 40), txid = txid)
        val v = if (cents > 0) "valor fixo ${PixRules.money(cents)}" else "valor livre (quem paga digita)"
        return item to "QR Code Pix mostrado no chat (\"${labelOf(k)}\", $v), com botões para copiar o código e enviar. " +
            "O código e a chave não são mostrados a você: não tente escrevê-los. Diga em 1 frase que o QR está acima."
    }
}
