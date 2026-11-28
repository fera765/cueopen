package dev.fluwy.app.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regressão do bug de LOOP sem ação: o agente emitia várias mensagens "vou pesquisar…" sem nunca
 * chamar uma ferramenta. As guardas puras (hasActionIntent + similar) sustentam a correção no loop.
 */
class LoopGuardTest {

    @Test fun detectsActionAnnouncementWithoutTool() {
        // o texto exato do bug
        assertTrue(AgentRunner.hasActionIntent("Vou pesquisar sobre dores mais específicas para refinar a busca."))
        assertTrue(AgentRunner.hasActionIntent("Agora vou verificar os arquivos baixados."))
        assertTrue(AgentRunner.hasActionIntent("Deixa eu testar o endpoint antes."))
        assertTrue(AgentRunner.hasActionIntent("Primeiro vou abrir a página de resultados."))
        // inglês
        assertTrue(AgentRunner.hasActionIntent("I'll search for the latest data now."))
        assertTrue(AgentRunner.hasActionIntent("Let me run the script to confirm."))
    }

    @Test fun ignoresRealFinalAnswers() {
        // respostas finais legítimas (sem anúncio de ação com ferramenta) não podem disparar a guarda
        assertFalse(AgentRunner.hasActionIntent("Pronto. Aqui está o resumo da pesquisa com as 5 dores principais."))
        assertFalse(AgentRunner.hasActionIntent("Vou te explicar o que encontrei: o mercado cresceu 12%."))
        assertFalse(AgentRunner.hasActionIntent("Concluí a análise e salvei tudo no arquivo relatorio.md."))
        assertFalse(AgentRunner.hasActionIntent("Segue a tabela com os resultados."))
    }

    @Test fun similarityCatchesNearDuplicates() {
        val a = "Vou pesquisar sobre dores mais específicas para refinar a busca."
        val b = "Vou pesquisar sobre dores mais específicas para refinar a pesquisa."
        assertTrue(AgentRunner.similar(a, b))
        assertTrue(AgentRunner.similar(a, a))
        assertFalse(AgentRunner.similar(a, "Concluí e entreguei o relatório final em PDF."))
        assertFalse(AgentRunner.similar(a, ""))
    }
}
