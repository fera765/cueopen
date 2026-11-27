package dev.fluwy.app.assistant

import dev.fluwy.app.data.AgentProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AssistantRoutingPolicyTest {
    @Test fun mentionMatchesAgentNameIgnoringCaseAndDiacritics() {
        val trader = AgentProfile(id = "trader", name = "Trader Agent")
        assertEquals(trader.id, AssistantRoutingPolicy.findMentionedAgent("Acione o agente trader agora", listOf(trader))?.id)
        assertEquals(trader.id, AssistantRoutingPolicy.findMentionedAgent("Rode o TRADER AGENT", listOf(trader))?.id)
    }

    @Test fun longerSpecificNameWinsOverGenericSharedWord() {
        val trader = AgentProfile(id = "trader", name = "Trader")
        val market = AgentProfile(id = "market", name = "Trader de Mercado")
        assertEquals(market.id, AssistantRoutingPolicy.findMentionedAgent("Acione o trader de mercado", listOf(trader, market))?.id)
    }

    @Test fun ambiguousNameDoesNotGuess() {
        val first = AgentProfile(id = "one", name = "Trader Agent")
        val second = AgentProfile(id = "two", name = "Trader Bot")
        assertNull(AssistantRoutingPolicy.findMentionedAgent("Acione o agente trader", listOf(first, second)))
    }

    @Test fun genericAgentNameDoesNotMatchEveryUtterance() {
        val agent = AgentProfile(id = "a", name = "Agente Assistente")
        assertNull(AssistantRoutingPolicy.findMentionedAgent("Analise o mercado", listOf(agent)))
    }

    @Test fun screenshotIntentRequiresAnExplicitRequest() {
        assertEquals(true, AssistantRoutingPolicy.explicitlyRequestsScreenshot("Tire um print da tela atual"))
        assertEquals(true, AssistantRoutingPolicy.explicitlyRequestsScreenshot("Faça uma captura de tela"))
        assertEquals(true, AssistantRoutingPolicy.explicitlyRequestsScreenshot("Capture a tela atual"))
        assertEquals(false, AssistantRoutingPolicy.explicitlyRequestsScreenshot("Analise esta notícia"))
    }
}
