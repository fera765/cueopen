package dev.fluwy.app.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CardRulesTest {
    @Test fun luhnExpiryCpfBrand() {
        assertTrue(CardRules.luhn("4111111111111111"))
        assertFalse(CardRules.luhn("4111111111111112"))
        assertTrue(CardRules.expiryOk("12/30", 2026, 11))
        assertFalse(CardRules.expiryOk("10/26", 2026, 11))
        assertFalse(CardRules.expiryOk("13/30", 2026, 11))
        assertTrue(CardRules.cpfOk("529.982.247-25"))
        assertFalse(CardRules.cpfOk("111.111.111-11"))
        assertEquals("Visa", CardRules.brand("4111111111111111"))
        assertEquals("Mastercard", CardRules.brand("5500000000000004"))
    }

    /** Isolamento: a camada de IA (agente, ferramentas, contexto) não referencia o Cofre de cartões. */
    @Test fun agentLayerNeverTouchesCardVault() {
        val root = listOf(File("src/main/java/dev/fluwy/app"), File("app/src/main/java/dev/fluwy/app")).first { it.exists() }
        val offenders = listOf("agent", "llm", "connectors", "scheduler").flatMap { dir ->
            File(root, dir).walkTopDown().filter { it.isFile && it.extension == "kt" && it.readText().let { t -> t.contains("CardVault") || t.contains("PixVault") } }.map { it.name }.toList()
        }
        assertTrue("Camada de IA referencia o Cofre: $offenders", offenders.isEmpty())
    }
}
