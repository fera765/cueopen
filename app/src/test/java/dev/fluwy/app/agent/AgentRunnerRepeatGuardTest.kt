package dev.fluwy.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunnerRepeatGuardTest {
    @Test fun `connector builder interrompe chamada identica apos uma repeticao`() {
        assertEquals(2, AgentRunner.repeatToolThreshold("connector_validate"))
        assertEquals(2, AgentRunner.repeatToolThreshold("connector_test"))
        assertEquals(2, AgentRunner.repeatToolThreshold("connector_propose"))
    }

    @Test fun `somente operacoes de validacao teste e proposta recebem memoizacao`() {
        assertTrue(AgentRunner.memoizeConnectorBuilderCall("connector_validate"))
        assertTrue(AgentRunner.memoizeConnectorBuilderCall("connector_test"))
        assertTrue(AgentRunner.memoizeConnectorBuilderCall("connector_propose"))
        assertFalse(AgentRunner.memoizeConnectorBuilderCall("connector_spec"))
        assertFalse(AgentRunner.memoizeConnectorBuilderCall("file_read"))
    }

    @Test fun `memo exato devolve resultado anterior e distingue argumentos diferentes`() {
        val memo = ExactToolCallMemo<String>()
        var executions = 0
        val signature = "connector_test|yaml:abc|args:none"
        val first = memo[signature] ?: "resultado-${++executions}".also { memo[signature] = it }
        val repeated = memo[signature] ?: "resultado-${++executions}".also { memo[signature] = it }

        assertEquals("resultado-1", first)
        assertEquals(first, repeated)
        assertEquals(1, executions)
        assertNull(memo["connector_test|yaml:def|args:none"])
    }

    @Test fun `leitura pura e ferramenta de ambiente mantem limites gerais`() {
        assertEquals(3, AgentRunner.repeatToolThreshold("connector_spec"))
        assertEquals(3, AgentRunner.repeatToolThreshold("file_read"))
        assertEquals(6, AgentRunner.repeatToolThreshold("shell"))
    }

    @Test fun `leituras externas identicas interrompem retries antes de amplificar rate limits`() {
        listOf(
            "wallet_get_balance", "wallet_list_tokens", "market_discover", "market_token_info",
            "market_price", "market_candles", "market_liquidity", "browser_navigate",
        ).forEach { tool ->
            assertEquals(tool, 3, AgentRunner.repeatToolThreshold(tool))
        }
        assertEquals(6, AgentRunner.repeatToolThreshold("wallet_swap"))
    }
}
