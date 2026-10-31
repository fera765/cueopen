package dev.cueopen.app.agent

import dev.cueopen.app.data.MemoryItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryRecallPolicyTest {
    @Test fun `agente recebe memórias globais e próprias mas não de outro agente`() {
        val memories = listOf(
            MemoryItem(id = "global", text = "Projeto Android usa Kotlin", agentId = null),
            MemoryItem(id = "mine", text = "Repositório do projeto em Kotlin", agentId = "agent-a"),
            MemoryItem(id = "other", text = "Projeto Android usa Kotlin", agentId = "agent-b"),
        )
        val ids = MemoryRecallPolicy.select(memories, "projeto Kotlin", "agent-a").map { it.id }.toSet()
        assertTrue(ids.contains("global"))
        assertTrue(ids.contains("mine"))
        assertFalse(ids.contains("other"))
    }

    @Test fun `sessão sem agente não recebe memórias privadas de agentes`() {
        val memories = listOf(
            MemoryItem(id = "global", text = "Preferência de idioma português"),
            MemoryItem(id = "private", text = "Preferência de idioma português", agentId = "agent-a"),
        )
        assertEquals(listOf("global"), MemoryRecallPolicy.select(memories, "idioma português", null).map { it.id })
    }

    @Test fun `fatos recentes do próprio agente continuam acessíveis e limite de caracteres é respeitado`() {
        val memories = listOf(
            MemoryItem(id = "old", text = "antigo", agentId = "agent-a", createdAt = 1),
            MemoryItem(id = "recent", text = "novo", agentId = "agent-a", createdAt = 2),
            MemoryItem(id = "large", text = "projeto Kotlin Android", tags = listOf("tag-long"), agentId = null),
        )
        val selected = MemoryRecallPolicy.select(memories, "termo totalmente ausente", "agent-a", maxChars = 5)
        assertEquals(listOf("recent"), selected.map { it.id })
        assertTrue(selected.sumOf { it.text.length + it.tags.sumOf(String::length) } <= 5)

        val oversizedTag = MemoryRecallPolicy.select(
            listOf(MemoryItem(id = "tagged", text = "Kotlin", tags = listOf("tag-long"))),
            "Kotlin", agentId = null, maxChars = 10,
        )
        assertTrue(oversizedTag.isEmpty())
    }
}
