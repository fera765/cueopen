package dev.fluwy.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLessonsTest {
    @Test fun `lessons are added deduplicated capped and keep other memory facts`() {
        var mem = listOf("Usuário prefere relatórios em tabela")
        mem = AgentLessons.add(mem, "Minerar ofertas", "Salve o arquivo radar.md antes de entregar")
        mem = AgentLessons.add(mem, "Minerar ofertas", "Salve o arquivo radar.md antes de entregar")
        assertEquals(2, mem.size)
        assertEquals("Usuário prefere relatórios em tabela", mem.first())
        repeat(20) { mem = AgentLessons.add(mem, "t$it", "nota $it") }
        assertEquals(1 + AgentLessons.MAX, mem.size)
        assertTrue(mem.last().contains("nota 19"))
        assertTrue(mem.contains("Usuário prefere relatórios em tabela"))
    }
}
