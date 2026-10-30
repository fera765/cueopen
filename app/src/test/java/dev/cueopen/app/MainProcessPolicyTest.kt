package dev.cueopen.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainProcessPolicyTest {
    @Test fun `nome do processo principal deve iniciar dependencias da aplicacao`() {
        assertTrue(MainProcessPolicy.isMainProcess("com.fluwy.bot", "com.fluwy.bot"))
    }

    @Test fun `processo dedicado do assistente nao deve inicializar workers e agentes`() {
        assertFalse(MainProcessPolicy.isMainProcess("com.fluwy.bot", "com.fluwy.bot:assistant"))
    }

    @Test fun `nome do processo desconhecido falha fechado`() {
        assertFalse(MainProcessPolicy.isMainProcess("com.fluwy.bot", null))
    }
}
