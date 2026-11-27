package dev.fluwy.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskTitleTest {
    @Test fun `titles drop greetings and request filler`() {
        assertEquals("Pesquisar concorrentes de apps de rega", TaskTitle.make("Oi, bom dia! Eu quero que você pesquisar concorrentes de apps de rega."))
        assertEquals("Resumo do relatório", TaskTitle.make("Pode me resumo do relatório?"))
        assertEquals("Nova conversa", TaskTitle.make("   "))
        val long = TaskTitle.make("analise todos os comentários do reddit sobre aplicativos de inventário doméstico e faça um ranking")
        assertTrue(long, long.length <= 49 && long.endsWith("…"))
    }
}
