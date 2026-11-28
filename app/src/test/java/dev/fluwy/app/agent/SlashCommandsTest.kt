package dev.fluwy.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlashCommandsTest {
    @Test fun `nomes unicos e com barra`() {
        val names = SlashCommands.all.map { it.name }
        assertEquals(names.size, names.toSet().size)
        assertTrue(names.all { it.startsWith("/") && !it.contains(' ') })
    }

    @Test fun `barra sozinha lista tudo agrupado`() {
        val l = SlashCommands.filter("/")
        assertEquals(SlashCommands.all.size, l.size)
        assertEquals(l.map { it.group.ordinal }, l.map { it.group.ordinal }.sorted())
    }

    @Test fun `filtro por prefixo antes de contem`() {
        val l = SlashCommands.filter("/co")
        assertEquals(listOf("/compact", "/connectors"), l.take(2).map { it.name })
        assertTrue(SlashCommands.filter("/compact x").isEmpty())
        assertTrue(SlashCommands.filter("compact").isEmpty())
        assertTrue(SlashCommands.filter("/mem").any { it.name == "/memory" })
    }

    @Test fun `picker de skill e agent`() {
        assertEquals("/skill" to "res", SlashCommands.pickerQuery("/skill res"))
        assertEquals("/agent" to "", SlashCommands.pickerQuery("/agent "))
        assertNull(SlashCommands.pickerQuery("/skills"))
    }

    @Test fun `resolve skill e agent para mencao`() {
        val skills = listOf("Resumo", "Resumo Longo")
        assertEquals("@Resumo Longo deste texto", SlashCommands.resolveArgCommand("/skill resumo longo deste texto", skills, emptyList()))
        assertEquals("@Pesquisador", SlashCommands.resolveArgCommand("/agent @Pesquisador", emptyList(), listOf("Pesquisador")))
        assertNull(SlashCommands.resolveArgCommand("/skill Nada", skills, emptyList()))
        assertNull(SlashCommands.resolveArgCommand("/skills", skills, emptyList()))
    }

    @Test fun `ajuda cita todos os comandos`() {
        val h = SlashCommands.helpMarkdown()
        SlashCommands.all.forEach { assertTrue(it.name, h.contains(it.name)) }
    }
}
