package dev.fluwy.app.vault

import org.junit.Assert.*
import org.junit.Test

class PixAgentTest {
    private val key = "fulano@exemplo.com"

    @Test fun openValueQrHasNoAmountAndValidCrc() {
        val p = PixRules.checkedPayload(key, "FULANO DE TAL", "SAO PAULO", 0, "FLWTEST1")
        assertFalse(p.contains("5406") || Regex("54\\d\\d\\d").containsMatchIn(p.substringBefore("5802")))
        val back = PixRules.parse(p)!!
        assertTrue(back.crcOk); assertNull(back.amount); assertEquals(key, back.key)
    }

    @Test fun fixedValueQrKeepsAmount() {
        val back = PixRules.parse(PixRules.checkedPayload(key, "FULANO", "RIO", 2590, "T1"))!!
        assertEquals("25.90", back.amount); assertTrue(back.crcOk)
    }

    @Test fun parseCents() {
        assertEquals(0L, PixRules.parseCents(""))
        assertEquals(0L, PixRules.parseCents(null))
        assertEquals(2590L, PixRules.parseCents("25,90"))
        assertEquals(2590L, PixRules.parseCents("R$ 25,9"))
        assertEquals(123456L, PixRules.parseCents("1.234,56"))
        assertEquals(123456L, PixRules.parseCents("1234.56"))
        assertEquals(1000L, PixRules.parseCents("10"))
        assertNull(PixRules.parseCents("abc"))
        assertNull(PixRules.parseCents("-5"))
        assertNull(PixRules.parseCents("1,234"))
    }

    @Test fun moneyFormat() { assertEquals("R$ 1.234,56", PixRules.money(123456)) }

    @Test fun agentTextNeverHasSensitiveData() {
        val keys = listOf(VaultTools.KeyRef("abc123", "Loja", "email"), VaultTools.KeyRef("def456", "", "cpf"))
        val txt = VaultTools.describe(keys, listOf(VaultTools.CardRef("Cartão 1", "Visa")))
        assertTrue(txt.contains("abc123") && txt.contains("Loja") && txt.contains("Chave CPF"))
        assertFalse(Regex("\\d{4,}").containsMatchIn(txt.replace("abc123", "").replace("def456", "")))
    }

    @Test fun pickKey() {
        val one = listOf(VaultTools.KeyRef("a1", "Loja", "email"))
        assertEquals("a1", VaultTools.pick(one, "").getOrThrow().id)
        val two = one + VaultTools.KeyRef("b2", "Pessoal", "phone")
        assertTrue(VaultTools.pick(two, "").isFailure)
        assertEquals("b2", VaultTools.pick(two, "pessoal").getOrThrow().id)
        assertEquals("a1", VaultTools.pick(two, "a1").getOrThrow().id)
        assertTrue(VaultTools.pick(two, "nada").isFailure)
        assertTrue(VaultTools.pick(emptyList(), "").isFailure)
    }

    @Test fun pixItemNeverStoresKeyOrCode() {
        val fields = dev.fluwy.app.data.ChatItem.PixQr::class.java.declaredFields.map { it.name }
        assertFalse(fields.any { it == "key" || it == "payload" || it == "code" })
    }
}
