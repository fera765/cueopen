package dev.fluwy.app.agent

import dev.fluwy.app.data.ChatItem
import dev.fluwy.app.data.LlmMessage
import dev.fluwy.app.data.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryHygieneTest {
    @Test fun `memory facts must be backed by what the user wrote`() {
        val user = listOf("meu projeto Fluwy fica em https://github.com/fera765/fluwy e prefiro respostas curtas")
        assertTrue(MemoryHygiene.factBackedByUser("Projeto Fluwy no GitHub: https://github.com/fera765/fluwy", user))
        assertFalse(MemoryHygiene.factBackedByUser("Projeto Fluwy tem 25 mil usuários", user))
        assertFalse(MemoryHygiene.factBackedByUser("Repositório em https://gitlab.com/x", user))
    }

    @Test fun `expiry and compaction hysteresis`() {
        assertTrue(MemoryHygiene.active("Viagem em dezembro · até 2026-12-31", today = "2026-11-16"))
        assertFalse(MemoryHygiene.active("Promoção ativa · até 2026-11-01", today = "2026-11-16"))
        assertTrue(MemoryHygiene.active("sem data"))
        assertTrue(MemoryHygiene.shouldCompact(25_000, 24_000, null))
        assertFalse(MemoryHygiene.shouldCompact(25_000, 24_000, 22_000))  // 22k*1.25 = 27.5k
        assertTrue(MemoryHygiene.shouldCompact(28_000, 24_000, 22_000))
    }

    @Test fun `excerpt fallback keeps the newest messages within budget`() {
        val head = (1..20).map { LlmMessage(if (it % 2 == 0) "assistant" else "user", "mensagem $it " + "x".repeat(300)) }
        val s = MemoryHygiene.excerptSummary(head, budget = 1_000)
        assertTrue(s.contains("mensagem 20")); assertFalse(s.contains("mensagem 1 ")); assertTrue(s.contains("omitido"))
    }

    @Test fun `past chats need two shared terms for long requests`() {
        val old = Session(id = "a", title = "Radar low ticket", items = listOf(ChatItem.Assistant(text = "Relatório de ofertas low ticket no Brasil com checkout e preços", final = true)))
        val other = Session(id = "b", title = "Receita de bolo", items = listOf(ChatItem.Assistant(text = "farinha e ovos", final = true)))
        val r = MemoryHygiene.pastChats(listOf(old, other), "c", "continue o radar de ofertas low ticket brasileiras e confira o checkout")
        assertEquals(1, r.size); assertTrue(r[0].contains("Radar low ticket"))
        assertTrue(MemoryHygiene.pastChats(listOf(old), "c", "bolo de cenoura com cobertura de chocolate").isEmpty())
    }
}
