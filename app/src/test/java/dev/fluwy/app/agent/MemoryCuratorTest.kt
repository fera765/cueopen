package dev.fluwy.app.agent

import dev.fluwy.app.data.KnowledgeItem
import dev.fluwy.app.data.MemoryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class MemoryCuratorTest {
    @Test fun `termos ignoram acento plural e palavras vazias`() {
        assertEquals(MemoryCurator.terms("Preferências do usuário"), MemoryCurator.terms("preferencia usuarios"))
        assertTrue("para" !in MemoryCurator.terms("para o projeto"))
    }

    @Test fun `categorias canonicas e sinonimos`() {
        assertEquals("preferencia", MemoryCurator.canonicalKind("Preferência"))
        assertEquals("pessoa", MemoryCurator.canonicalKind("contato"))
        assertEquals("ferramenta", MemoryCurator.canonicalKind("tool"))
        assertEquals("", MemoryCurator.canonicalKind("xyz"))
    }

    @Test fun `tags limpas e limitadas`() {
        val t = MemoryCurator.cleanTags(listOf(" Kotlin ", "kotlin", "", "x".repeat(40)) + (1..10).map { "t$it" })
        assertEquals("kotlin", t[0]); assertEquals(MemoryCurator.MAX_TAGS, t.size)
        assertTrue(t.all { it.length <= MemoryCurator.MAX_TAG_LEN })
    }

    @Test fun `duplicata so no mesmo escopo e fato estendido conta como parecido`() {
        val items = listOf(
            MemoryItem(id = "g", text = "O usuário prefere respostas curtas em português"),
            MemoryItem(id = "a", text = "O usuário prefere respostas curtas em português", agentId = "ag"),
        )
        assertEquals("g", MemoryCurator.findDuplicate(items, "Usuário prefere respostas curtas em portugues", null)?.id)
        assertEquals("a", MemoryCurator.findDuplicate(items, "O usuário prefere respostas curtas em português", "ag")?.id)
        assertEquals("g", MemoryCurator.findDuplicate(items, "O usuário prefere respostas curtas em português, sem emojis e diretas", null)?.id)
        assertNull(MemoryCurator.findDuplicate(items, "O projeto usa Kotlin e Compose", null))
    }

    @Test fun `merge preserva o texto mais completo e une tags`() {
        val old = MemoryItem(id = "m", text = "Usa Kotlin no projeto Fluwy", tags = listOf("kotlin"), kind = "projeto", title = "Stack")
        val m = MemoryCurator.merge(old, "Usa Kotlin", listOf("android"), "", "", listOf("m", "x"), 5)
        assertEquals(old.text, m.text); assertEquals(listOf("kotlin", "android"), m.tags)
        assertEquals("Stack", m.title); assertEquals("projeto", m.kind); assertEquals(listOf("x"), m.links); assertEquals(5, m.lastUsedAt)
    }

    @Test fun `poda remove menos usadas e mais antigas`() {
        val items = listOf(MemoryItem(id = "a", text = "a", uses = 5), MemoryItem(id = "b", text = "b", uses = 0, lastUsedAt = 1), MemoryItem(id = "c", text = "c", uses = 0, lastUsedAt = 2))
        assertEquals(listOf("b"), MemoryCurator.overflow(items, max = 2))
        assertTrue(MemoryCurator.overflow(items, max = 3).isEmpty())
    }

    @Test fun `ranking prioriza titulo e cobertura`() {
        val q = MemoryCurator.terms("viagem Lisboa")
        val title = MemoryItem(id = "t", text = "Vai em março", title = "Viagem Lisboa")
        val body = MemoryItem(id = "b", text = "Comentou uma viagem a trabalho sem destino definido ainda")
        val none = MemoryItem(id = "n", text = "Gosta de café")
        val ranked = listOf(none, body, title).sortedByDescending { MemoryCurator.memoryScore(q, it) }
        assertEquals(listOf("t", "b", "n"), ranked.map { it.id })
        assertEquals(0.0, MemoryCurator.memoryScore(q, none), 0.0)
    }

    @Test fun `knowledge ranqueado ignora desativados`() {
        val items = listOf(
            KnowledgeItem(id = "off", title = "Política de troca", content = "troca", enabled = false),
            KnowledgeItem(id = "cat", title = "Catálogo", content = "Preços dos planos"),
            KnowledgeItem(id = "pol", title = "Políticas", content = "Trocas em até 7 dias"),
        )
        val r = MemoryCurator.rankKnowledge(items, "política de troca")
        assertEquals("pol", r.first().first.id); assertTrue(r.none { it.first.id == "off" })
    }

    @Test fun `origem legivel`() {
        val m = MemoryItem(text = "x", source = MemoryCurator.SOURCE_AUTO, createdAt = 0)
        assertEquals("Aprendida automaticamente · 01/01/1970", MemoryCurator.originLabel(m, ZoneId.of("UTC")))
        assertTrue(MemoryCurator.originLabel(MemoryItem(text = "x")).startsWith("Salva ·"))
    }
}
