package dev.cueopen.app.uiconnector

import dev.cueopen.app.uiconnector.iqoption.IqProtocol
import dev.cueopen.app.uiconnector.iqoption.IqProtocol.LoginOutcome
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Testes do protocolo com mensagens no formato documentado por clientes open source da plataforma.
 * Validam a NOSSA interpretação (parsing/regras); a conexão real com a IQ Option só pode ser
 * verificada com uma conta, no aparelho.
 */
class IqProtocolTest {

    private fun obj(s: String): JsonObject = IqProtocol.parseObject(s)!!

    // ------------------------------------------------------------------ login
    @Test fun `login com ssid no corpo`() {
        val r = IqProtocol.parseLogin(200, """{"code":"success","ssid":"abc123"}""", emptyList())
        assertEquals(LoginOutcome.Success("abc123"), r)
    }

    @Test fun `login com ssid so no cookie`() {
        val r = IqProtocol.parseLogin(200, "{}", listOf("platform=9; Path=/", "ssid=xyz789; Path=/; HttpOnly"))
        assertEquals(LoginOutcome.Success("xyz789"), r)
    }

    @Test fun `cookie ssid deleted nao conta como sessao`() {
        val r = IqProtocol.parseLogin(200, "{}", listOf("ssid=deleted; Path=/"))
        assertTrue(r is LoginOutcome.Failure)
    }

    @Test fun `credenciais invalidas viram mensagem amigavel sem vazar nada`() {
        val r = IqProtocol.parseLogin(401, """{"code":"invalid_credentials"}""", emptyList())
        assertTrue(r is LoginOutcome.Failure)
        assertTrue((r as LoginOutcome.Failure).message.contains("incorretos"))
    }

    @Test fun `2fa e detectado`() {
        val r = IqProtocol.parseLogin(200, """{"code":"verify","method":"sms","token":"t"}""", emptyList())
        assertTrue(r is LoginOutcome.TwoFactor)
    }

    @Test fun `rate limit e erro de servidor`() {
        assertTrue((IqProtocol.parseLogin(429, "{}", emptyList()) as LoginOutcome.Failure).message.contains("Muitas tentativas"))
        assertTrue((IqProtocol.parseLogin(503, "html", emptyList()) as LoginOutcome.Failure).message.contains("indisponível"))
    }

    @Test fun `corpo de login usa identifier e password`() {
        val o = obj(IqProtocol.loginBody("a@b.com", "p\"w"))
        assertEquals("a@b.com", o["identifier"]!!.jsonPrimitive.content)
        assertEquals("p\"w", o["password"]!!.jsonPrimitive.content)
    }

    // ------------------------------------------------------------------ entrada
    @Test fun `profile traz contas e conta ativa`() {
        val msg = obj("""{"msg":{"balance_id":111,"balances":[
            {"id":111,"type":4,"amount":10000.5,"currency":"USD"},
            {"id":222,"type":1,"amount":3,"currency":"BRL"}]}}""")["msg"]!!.jsonObject
        val p = IqProtocol.parseProfile(msg)!!
        assertEquals(111L, p.activeBalanceId)
        assertEquals(2, p.balances.size)
        assertTrue(p.balances.first { it.id == 111L }.isPractice)
        assertTrue(p.balances.first { it.id == 222L }.isReal)
        assertEquals(10000.5, p.balances.first { it.id == 111L }.amount, 0.0)
        assertEquals("BRL", p.balances.first { it.id == 222L }.currency)
    }

    @Test fun `profile sem contas e ignorado`() = assertNull(IqProtocol.parseProfile(obj("""{"balances":[]}""")))

    @Test fun `balance-changed aninhado e plano`() {
        val nested = IqProtocol.parseBalanceChanged(obj("""{"current_balance":{"id":5,"type":1,"amount":9.5,"currency":"USD"}}"""))!!
        assertEquals(5L, nested.id); assertTrue(nested.isReal); assertEquals(9.5, nested.amount, 0.0)
        val flat = IqProtocol.parseBalanceChanged(obj("""{"id":6,"type":4,"amount":1,"currency":"USD"}"""))!!
        assertTrue(flat.isPractice)
    }

    @Test fun `so profile ou balance-changed explicito confirma a carteira ativa`() {
        val profile = obj("""{"name":"profile","msg":{"balance_id":222,"balances":[{"id":222,"type":4,"amount":10}]}}""")
        assertEquals(222L, IqProtocol.confirmedActiveBalanceId(profile))
        val changed = obj("""{"name":"balance-changed","msg":{"current_balance":{"id":333,"type":1,"amount":2}}}""")
        assertEquals(333L, IqProtocol.confirmedActiveBalanceId(changed))
        val flat = obj("""{"name":"balance-changed","msg":{"id":333,"type":1,"amount":2}}""")
        assertNull(IqProtocol.confirmedActiveBalanceId(flat))
        val scalar = obj("""{"name":"balance-changed","msg":{"id":333,"type":1,"amount":2,"current_balance":2}}""")
        assertNull(IqProtocol.confirmedActiveBalanceId(scalar))
        assertNull(IqProtocol.confirmedActiveBalanceId(obj("""{"name":"result","msg":{"id":333}}""")))
    }

    @Test fun `initialization-data lista ativos abertos e fechados`() {
        val msg = obj("""{"turbo":{"actives":{
            "1":{"name":"front.EURUSD","enabled":true,"is_suspended":false},
            "2":{"name":"front.GBPUSD","enabled":true,"is_suspended":true}}},
            "binary":{"actives":{"1":{"name":"front.EURUSD","enabled":false},"76":{"ticker":"EURUSD-OTC","enabled":true}}}}""")
        val a = IqProtocol.parseAssets(msg).associateBy { it.ticker }
        assertTrue(a.getValue("EURUSD").open)
        assertEquals("turbo", a.getValue("EURUSD").kind)
        assertFalse(a.getValue("GBPUSD").open)
        assertTrue(a.getValue("EURUSD-OTC").open)
        assertEquals(76, a.getValue("EURUSD-OTC").id)
    }

    @Test fun `velas sao lidas e invalidas ignoradas`() {
        val msg = obj("""{"candles":[
            {"from":1700000000,"open":1.1,"close":1.2,"min":1.0,"max":1.3,"volume":50},
            {"from":1700000060,"close":1.25},
            {"open":1}]}""")
        val c = IqProtocol.parseCandles(msg)
        assertEquals(2, c.size)
        assertEquals(1.3, c[0].max, 0.0)
        assertEquals(1.25, c[1].open, 0.0)
    }

    @Test fun `ack de ordem aceita e recusada`() {
        val ok = IqProtocol.parseOptionAck(obj("""{"name":"option","request_id":"r","status":2000,"msg":{"id":987654}}"""))
        assertEquals(987654L, ok.optionId); assertNull(ok.error)
        val bad = IqProtocol.parseOptionAck(obj("""{"name":"option","request_id":"r","status":4000,"msg":{"message":"active suspended"}}"""))
        assertNull(bad.optionId); assertEquals("active suspended", bad.error)
        val unknown = IqProtocol.parseOptionAck(obj("""{"name":"option","status":5000}"""))
        assertNull(unknown.optionId); assertTrue(unknown.error!!.contains("5000"))
    }

    @Test fun `option-closed traz resultado`() {
        val c = IqProtocol.parseOptionClosed(obj("""{"name":"option-closed","msg":{"option_id":42,"win":"win","profit_amount":1.85}}"""))!!
        assertEquals(42L, c.optionId); assertEquals("win", c.result); assertEquals(1.85, c.profit!!, 0.0)
        assertNull(IqProtocol.parseOptionClosed(obj("""{"name":"option-closed","msg":{"foo":1}}""")))
    }

    // ------------------------------------------------------------------ saída
    @Test fun `mensagem ssid e troca de conta`() {
        val s = obj(IqProtocol.ssidMessage("S1"))
        assertEquals("ssid", s["name"]!!.jsonPrimitive.content); assertEquals("S1", s["msg"]!!.jsonPrimitive.content)
        val c = obj(IqProtocol.changeBalanceMessage(77))
        assertEquals("api_profile_changebalance", c["name"]!!.jsonPrimitive.content)
        assertEquals(77L, c["msg"]!!.jsonObject["balance_id"]!!.jsonPrimitive.long)
    }

    @Test fun `mensagem de ordem carrega a conta e a direcao`() {
        val m = obj(IqProtocol.openOptionMessage("r1", 111, 1, "CALL", 1_700_000_040, 2.5, turbo = true))
        assertEquals("sendMessage", m["name"]!!.jsonPrimitive.content)
        assertEquals("r1", m["request_id"]!!.jsonPrimitive.content)
        val inner = m["msg"]!!.jsonObject
        assertEquals("binary-options.open-option", inner["name"]!!.jsonPrimitive.content)
        val b = inner["body"]!!.jsonObject
        assertEquals(111L, b["user_balance_id"]!!.jsonPrimitive.long)
        assertEquals(1L, b["active_id"]!!.jsonPrimitive.long)
        assertEquals(3L, b["option_type_id"]!!.jsonPrimitive.long)
        assertEquals("call", b["direction"]!!.jsonPrimitive.content)
        assertEquals(1_700_000_040L, b["expired"]!!.jsonPrimitive.long)
        assertEquals(2.5, b["price"]!!.jsonPrimitive.content.toDouble(), 0.0)
    }

    @Test fun `mensagem de velas e dados iniciais`() {
        val b = obj(IqProtocol.getCandlesMessage("r2", 5, 60, 30, 1_700_000_000))["msg"]!!.jsonObject
        assertEquals("get-candles", b["name"]!!.jsonPrimitive.content)
        assertEquals("2.0", b["version"]!!.jsonPrimitive.content)
        assertEquals(30L, b["body"]!!.jsonObject["count"]!!.jsonPrimitive.long)
        assertNotNull(obj(IqProtocol.initializationDataMessage("r3"))["msg"])
    }

    // ------------------------------------------------------------------ regras
    @Test fun `expiracao respeita o minuto e a antecedencia de 30s`() {
        assertEquals(1_700_000_040L, IqProtocol.expirationFor(1_700_000_000, 1))   // faltam 40 s → próximo minuto
        assertEquals(1_700_000_280L, IqProtocol.expirationFor(1_700_000_000, 5))
        assertEquals(1_700_000_100L, IqProtocol.expirationFor(1_700_000_010, 1))   // faltam 30 s → pula um minuto
        assertEquals(1_700_000_040L, IqProtocol.expirationFor(1_700_000_000, 0))   // minutos fora da faixa → mínimo 1
    }

    @Test fun `turbo ate 5 minutos`() {
        assertTrue(IqProtocol.isTurbo(1)); assertTrue(IqProtocol.isTurbo(5)); assertFalse(IqProtocol.isTurbo(6))
    }

    @Test fun `timeframes aceitos`() {
        assertEquals(60, IqProtocol.timeframeSeconds("60")); assertEquals(60, IqProtocol.timeframeSeconds("1m"))
        assertEquals(300, IqProtocol.timeframeSeconds("5m")); assertEquals(3600, IqProtocol.timeframeSeconds("1h"))
        assertEquals(30, IqProtocol.timeframeSeconds("30s"))
        assertNull(IqProtocol.timeframeSeconds("7")); assertNull(IqProtocol.timeframeSeconds("abc"))
    }

    @Test fun `resolucao de ativo ignora caixa e separadores`() {
        val list = listOf(
            IqProtocol.Asset(1, "EURUSD", "turbo", true),
            IqProtocol.Asset(76, "EURUSD-OTC", "turbo", true),
            IqProtocol.Asset(2, "GBPUSD", "turbo", false),
        )
        assertEquals(1, IqProtocol.resolveAsset(list, "eur/usd")!!.id)
        assertEquals(76, IqProtocol.resolveAsset(list, "EURUSD-OTC")!!.id)
        assertEquals(2, IqProtocol.resolveAsset(list, "gbp usd")!!.id)
        assertNull(IqProtocol.resolveAsset(list, "XYZ"))
        assertNull(IqProtocol.resolveAsset(list, ""))
    }

    @Test fun `lixo nao quebra o parser`() {
        assertNull(IqProtocol.parseObject("não é json"))
        assertNull(IqProtocol.parseObject("[1,2]"))
        assertTrue(IqProtocol.parseCandles(obj("{}")).isEmpty())
        assertTrue(IqProtocol.parseAssets(obj("{}")).isEmpty())
    }
}
