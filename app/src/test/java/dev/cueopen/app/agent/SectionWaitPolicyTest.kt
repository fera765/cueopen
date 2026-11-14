package dev.cueopen.app.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SectionWaitPolicyTest {
    @Test fun `waits for agents are recognised`() {
        listOf("Aguardar Radar concluir", "Aguardar Detetive concluir a etapa", "esperando a entrega do time", "wait for report", "até que o agente termine")
            .forEach { assertTrue(it, SectionWaitPolicy.waitsForTeam(it)) }
        listOf("verificação periódica", "checar o mercado", "")
            .forEach { assertFalse(it, SectionWaitPolicy.waitsForTeam(it)) }
    }

    @Test fun `user interval requests are recognised and plain requests are not`() {
        listOf("A cada 30 minutos verifique as ofertas", "a cada 2 horas faça um resumo", "de hora em hora me avise", "rode isso todo dia", "every 10 minutes check", "a cada hora")
            .forEach { assertTrue(it, SectionWaitPolicy.userAskedInterval(it)) }
        listOf("Crie uma seção com 5 agentes para buscar ofertas", "analise o site da Abacate Pay", "quantos minutos dura o vídeo?")
            .forEach { assertFalse(it, SectionWaitPolicy.userAskedInterval(it)) }
    }
}
