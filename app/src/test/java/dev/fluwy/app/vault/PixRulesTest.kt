package dev.fluwy.app.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PixRulesTest {
    @Test fun normalizesKeys() {
        assertEquals("52998224725", PixRules.normalize("CPF", "529.982.247-25"))
        assertNull(PixRules.normalize("CPF", "111.111.111-11"))
        assertEquals("11222333000181", PixRules.normalize("CNPJ", "11.222.333/0001-81"))
        assertEquals("+5511999998888", PixRules.normalize("TELEFONE", "(11) 99999-8888"))
        assertEquals("ana@x.com", PixRules.normalize("EMAIL", " Ana@X.com "))
        assertNull(PixRules.normalize("ALEATORIA", "123"))
    }

    @Test fun crcMatchesReference() {
        // CRC16/CCITT-FALSE de "123456789" = 29B1 (valor de referência do algoritmo)
        assertEquals("29B1", PixRules.crc16("123456789"))
    }

    @Test fun payloadRoundTripsAndLocksAmount() {
        val code = PixRules.payload("52998224725", "José da Silva", "São Paulo", 12_345, "PED123", "Pedido 123")
        assertTrue(code.contains("5406123.45"))
        val p = PixRules.parse(code)!!
        assertEquals("52998224725", p.key); assertEquals("JOSE DA SILVA", p.name); assertEquals("SAO PAULO", p.city)
        assertEquals("123.45", p.amount); assertTrue(p.crcOk)
        // Mexer no valor quebra o CRC: o código adulterado é detectado.
        val tampered = code.replace("123.45", "923.45")
        assertFalse(PixRules.parse(tampered)!!.crcOk)
        assertNull(PixRules.parse("não é pix"))
    }
}
