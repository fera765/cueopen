package dev.cueopen.app.uiconnector

import dev.cueopen.app.uiconnector.iqoption.IqMarketCircuitBreaker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IqMarketCircuitBreakerTest {
    @Test fun `primeira falha abre TTL de dez minutos e bloqueia ciclos repetidos`() {
        val circuit = IqMarketCircuitBreaker()
        val first = circuit.recordFailure("digital", 1_000L, "timeout")
        assertTrue(first.shouldLog)
        assertEquals(601_000L, first.state.retryAtElapsedMs)
        assertTrue(circuit.isOpen("digital", 600_999L))
        assertFalse(circuit.isOpen("digital", 601_000L))
        assertEquals(1, circuit.state("digital")?.failures)
    }

    @Test fun `backoff cresce por categoria e um sucesso valido limpa somente aquela categoria`() {
        val circuit = IqMarketCircuitBreaker()
        val first = circuit.recordFailure("forex", 0L, "transport")
        circuit.recordFailure("crypto", 0L, "timeout")
        val second = circuit.recordFailure("forex", first.state.retryAtElapsedMs, "timeout")
        assertEquals(20 * 60_000L, second.state.retryAtElapsedMs - first.state.retryAtElapsedMs)
        assertTrue(second.shouldLog)

        assertNull(circuit.recordResponse("forex", second.state.retryAtElapsedMs, returnedAssets = 3))
        assertNull(circuit.state("forex"))
        assertTrue(circuit.state("crypto") != null)
    }

    @Test fun `resposta vazia permanece inconclusiva e nao declara suporte ou ausencia garantida`() {
        val circuit = IqMarketCircuitBreaker()
        val decision = circuit.recordResponse("cfd", 100L, returnedAssets = 0)
        assertEquals("empty/unknown", decision?.state?.reason)
        assertTrue(circuit.isOpen("cfd", 101L))
        assertTrue(decision?.shouldLog == true)
    }

    @Test fun `circuitos sao isolados entre categorias`() {
        val circuit = IqMarketCircuitBreaker()
        circuit.recordFailure("digital", 5_000L, "timeout")
        assertTrue(circuit.isOpen("digital", 6_000L))
        assertFalse(circuit.isOpen("forex", 6_000L))
        assertFalse(circuit.isOpen("crypto", 6_000L))
    }

    @Test fun `login logout ou troca de conta explicita limpa estado e invalida probes antigos`() {
        val circuit = IqMarketCircuitBreaker()
        circuit.recordFailure("crypto", 5_000L, "timeout")
        val oldGeneration = circuit.contextGeneration()

        circuit.resetContext()

        assertEquals(oldGeneration + 1, circuit.contextGeneration())
        assertNull(circuit.state("crypto"))
        assertFalse(circuit.isOpen("crypto", 6_000L))
    }

    @Test fun `erro so gera um log ao abrir e outro ao reabrir apos TTL`() {
        val circuit = IqMarketCircuitBreaker()
        val first = circuit.recordFailure("digital", 0L, "timeout")
        assertTrue(first.shouldLog)
        assertTrue(circuit.isOpen("digital", 1L))
        val retry = circuit.recordFailure("digital", first.state.retryAtElapsedMs, "transport")
        assertTrue(retry.shouldLog)
        assertEquals(2, retry.state.failures)
        assertTrue(circuit.isOpen("digital", first.state.retryAtElapsedMs + 1L))
    }
}
