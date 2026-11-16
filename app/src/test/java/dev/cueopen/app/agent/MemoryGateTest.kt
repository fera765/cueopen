package dev.cueopen.app.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryGateTest {
    @Test fun `only durable statements trigger extraction`() {
        assertTrue(MemoryGate.worthExtracting(listOf("Prefiro relatórios curtos em tópicos")))
        assertTrue(MemoryGate.worthExtracting(listOf("lembre que meu projeto se chama CueOpen")))
        assertTrue(MemoryGate.worthExtracting(listOf("a partir de agora responda em inglês")))
        assertFalse(MemoryGate.worthExtracting(listOf("pesquise preços de notebooks", "ok, continue")))
    }
}
