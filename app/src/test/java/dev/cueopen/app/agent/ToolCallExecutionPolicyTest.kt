package dev.cueopen.app.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallExecutionPolicyTest {
    @Test fun `nome disponibilizado no passo pode prosseguir`() {
        assertTrue(ToolCallExecutionPolicy.isExposed("file_read", setOf("file_read", "finish")))
    }

    @Test fun `nome oculto ou desconhecido é bloqueado mesmo se o provider o devolver`() {
        val exposed = setOf("file_read", "finish")
        assertFalse(ToolCallExecutionPolicy.isExposed("wallet_swap", exposed))
        assertFalse(ToolCallExecutionPolicy.isExposed("mcp_unknown__write", exposed))
        assertFalse(ToolCallExecutionPolicy.isExposed("", exposed))
    }
}
