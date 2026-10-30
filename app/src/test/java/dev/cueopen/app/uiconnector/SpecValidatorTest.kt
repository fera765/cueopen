package dev.cueopen.app.uiconnector

import dev.cueopen.app.uiconnector.iqoption.IqOptionConnector
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpecValidatorTest {

    private fun valid() = UiConnectorSpec(
        id = "demo", name = "Demo", description = "d", version = "1.0",
        loginFields = listOf(LoginField("user", "Usuário"), LoginField("pass", "Senha", FieldType.PASSWORD)),
        settings = listOf(SettingSpec("limit", "Limite", "x", SettingType.NUMBER, "5")),
        tools = listOf(ToolSpec("ping", "Testa", listOf(ParamSpec("host", "Host")))),
    )

    @Test fun `spec valido nao tem problemas`() = assertEquals(emptyList<String>(), SpecValidator.validate(valid()))

    @Test fun `o spec real da IQ Option e valido`() =
        assertEquals(emptyList<String>(), SpecValidator.validate(IqOptionConnector().spec))

    @Test fun `settings legados mantem IDs e novos critérios ficam separados`() {
        val settings = IqOptionConnector().spec.settings.associateBy { it.id }
        assertEquals(setOf(
            "allow_real_agent", "max_amount", "max_open", "daily_stop_loss", "daily_take_profit",
            "max_consec_losses", "cooldown_min", "require_jev", "allow_margin_agent",
            "max_margin_amount", "max_forex_open", "max_leverage", "max_stop_loss_pct", "forex_monitor_interval_sec",
        ), settings.keys)
        assertFalse(settings.keys.any { it.contains("strategy") || it.contains("mhi") || it.contains("masaniello") || it.contains("analysis") })
    }

    @Test fun `id invalido e recusado`() {
        listOf("Demo", "1demo", "de_mo", "d", "demo-x").forEach {
            assertTrue("id '$it' deveria ser recusado", SpecValidator.validate(valid().copy(id = it)).any { p -> p.contains("id ") })
        }
    }

    @Test fun `login sem campo de senha e recusado`() {
        val s = valid().copy(loginFields = listOf(LoginField("user", "Usuário")))
        assertTrue(SpecValidator.validate(s).any { it.contains("PASSWORD") })
    }

    @Test fun `ferramenta duplicada e nome invalido sao recusados`() {
        val dup = valid().copy(tools = listOf(ToolSpec("ping", "a"), ToolSpec("ping", "b")))
        assertTrue(SpecValidator.validate(dup).any { it.contains("duplicada") })
        val bad = valid().copy(tools = listOf(ToolSpec("Ping!", "a")))
        assertTrue(SpecValidator.validate(bad).any { it.contains("inválido") })
    }

    @Test fun `ferramenta sem descricao e recusada porque o LLM precisa dela`() {
        val s = valid().copy(tools = listOf(ToolSpec("ping", "")))
        assertTrue(SpecValidator.validate(s).any { it.contains("description") })
    }

    @Test fun `tipo de parametro invalido e recusado`() {
        val s = valid().copy(tools = listOf(ToolSpec("ping", "ok", listOf(ParamSpec("n", "num", type = "float")))))
        assertTrue(SpecValidator.validate(s).any { it.contains("tipo") })
    }

    @Test fun `settings reservadas e defaults invalidos sao recusados`() {
        val reserved = valid().copy(settings = listOf(SettingSpec("enabled", "x", "x", SettingType.SWITCH, "true")))
        assertTrue(SpecValidator.validate(reserved).any { it.contains("reservado") })
        val badNum = valid().copy(settings = listOf(SettingSpec("limit", "x", "x", SettingType.NUMBER, "abc")))
        assertTrue(SpecValidator.validate(badNum).any { it.contains("NUMBER") })
        val badSwitch = valid().copy(settings = listOf(SettingSpec("flag", "x", "x", SettingType.SWITCH, "talvez")))
        assertTrue(SpecValidator.validate(badSwitch).any { it.contains("SWITCH") })
    }

    @Test fun `nome completo da ferramenta respeita o limite de 64`() {
        val long = valid().copy(tools = listOf(ToolSpec("a" + "b".repeat(30), "ok")))
        assertEquals(emptyList<String>(), SpecValidator.validate(long))
        assertTrue("uic_demo_${"a" + "b".repeat(30)}".length <= 64)
    }

    @Test fun `parseToolName separa id e ferramenta`() {
        assertEquals("iqoption" to "get_candles", SpecValidator.parseToolName("uic_iqoption_get_candles"))
        assertEquals("demo" to "ping", SpecValidator.parseToolName("uic_demo_ping"))
        assertNull(SpecValidator.parseToolName("mcp_github__x"))
        assertNull(SpecValidator.parseToolName("uic_semferramenta"))
        assertNull(SpecValidator.parseToolName("uic__x"))
    }

    @Test fun `redactor remove segredos e ignora valores curtos`() {
        val out = Redactor.redact("senha=hunter22 ssid=abcd1234 ok", listOf("hunter22", "abcd1234", "ok"))
        assertFalse(out.contains("hunter22"))
        assertFalse(out.contains("abcd1234"))
        assertTrue("valores com menos de 4 chars não são redigidos (evita mascarar texto comum)", out.endsWith("ok"))
    }

    @Test fun `ConnectorSettings le tipos com fallback`() {
        val s = ConnectorSettings { mapOf("a" to "true", "n" to "2,5")[it] }
        assertTrue(s.bool("a"))
        assertFalse(s.bool("zz"))
        assertEquals(2.5, s.num("n"), 0.0)
        assertEquals(7.0, s.num("zz", 7.0), 0.0)
    }

    /**
     * Toda ferramenta declarada no spec precisa ter handler no conector. Sem login, as que usam rede
     * lançam "Sessão ... não iniciada" (esperado); nenhuma pode cair em "Ferramenta desconhecida".
     */
    @Test fun `toda ferramenta declarada da IQ Option tem handler`() = runBlocking {
        val c = IqOptionConnector()
        val settings = ConnectorSettings { null }
        for (t in c.spec.tools) {
            val outcome = runCatching { c.callTool(t.name, emptyMap(), settings) }
            outcome.onSuccess { assertFalse("${t.name} sem handler", it.contains("desconhecida")) }
            outcome.onFailure { assertTrue("${t.name}: erro inesperado ${it.message}", it.message.orEmpty().contains("Sessão")) }
        }
        assertEquals("Ferramenta desconhecida: nao_existe", c.callTool("nao_existe", emptyMap(), settings))
    }

    @Test fun `conector novo nao esta logado e nao expoe segredos`() {
        val c = IqOptionConnector()
        assertFalse(c.isLoggedIn)
        assertTrue(c.secrets().isEmpty())
    }
}
