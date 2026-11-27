package dev.fluwy.app.uiconnector.custom

import dev.fluwy.app.trading.Side
import dev.fluwy.app.uiconnector.ConnectorSettings
import dev.fluwy.app.uiconnector.UiBlock
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class RealtimeTest {

    /** Servidor WebSocket falso: responde como uma corretora (ou como o stream da Binance). */
    private class FakeWs(val server: (msg: String, reply: (String) -> Unit) -> Unit) : WsExec {
        val sent = CopyOnWriteArrayList<String>()
        val urls = CopyOnWriteArrayList<String>()
        var listener: WsListener? = null
        var opens = 0
        override fun open(url: String, headers: Map<String, String>, listener: WsListener): WsConn {
            urls.add(url); opens++
            this.listener = listener
            listener.onOpen()
            return object : WsConn {
                override fun send(text: String): Boolean { sent.add(text); server(text) { listener.onText(it) }; return true }
                override fun close() {}
            }
        }
        fun push(text: String) = listener!!.onText(text)
        fun drop() = listener!!.onClosed("queda simulada")
    }

    private val json = Json { isLenient = true }
    private fun field(m: String, k: String) = (json.parseToJsonElement(m) as JsonObject)[k]?.jsonPrimitive?.content.orEmpty()

    private fun brokerWs() = FakeWs { m, reply ->
        when (field(m, "type")) {
            "auth" -> if (field(m, "token") == "TK-999") reply("""{"type":"balance","data":{"amount":1234.5,"currency":"USD"}}""")
            "subscribe" -> {
                reply("""{"type":"quote","data":{"symbol":"EURUSD","price":1.08451}}""")
                reply("""{"type":"quote","data":{"symbol":"GBPUSD","price":1.27}}""")
                reply("""{"type":"quote","data":{"symbol":"EURUSD","price":1.08460}}""")
            }
            "candles" -> reply("""{"request_id":"${field(m, "request_id")}","data":[{"time":1700000000,"open":1.1,"close":1.2}]}""")
            "order" -> {
                reply("""{"type":"order","data":{"id":"o1","symbol":"${field(m, "symbol")}","side":"${field(m, "side")}","amount":5,"status":"open"}}""")
                reply("""{"request_id":"${field(m, "request_id")}","data":{"id":"o1","status":"open"}}""")
            }
        }
    }

    private val http = object : HttpExec {
        override suspend fun exec(r: HttpReq): HttpRes =
            if (r.url.endsWith("/auth/login")) HttpRes(200, emptyMap(), """{"token":"TK-999"}""") else HttpRes(404, emptyMap(), "{}")
    }
    private val st = ConnectorSettings { k -> if (k == "maxValor") "10" else null }

    @Test fun `corretora em tempo real - login, estado, colecoes, send-await e protecoes`() = runBlocking {
        val ws = brokerWs()
        val c = CustomConnector(CustomDefParser.parse(CustomTemplates.BROKER_WS.json).def!!, http, permissive = true, ws = ws)
        assertTrue(c.login(mapOf("email" to "a@x.com", "password" to "senha-forte")).isSuccess)
        assertEquals("conectou ao entrar", 1, ws.opens)
        assertTrue(ws.sent[0].contains("TK-999"))
        assertTrue(c.secrets().contains("TK-999"))

        val blocks = c.snapshot(st)
        val stats = blocks.filterIsInstance<UiBlock.Stats>().first()
        assertEquals("1234.50 USD", stats.items[0].value)
        val quotes = blocks.filterIsInstance<UiBlock.Table>().first()
        assertEquals("mesma chave substitui; mais recente primeiro", listOf(listOf("EURUSD", "1.08460"), listOf("GBPUSD", "1.27000")), quotes.rows)

        assertEquals("Saldo: 1234.50 USD", c.callTool("saldo", emptyMap(), st))
        assertTrue(c.callTool("cotacoes", emptyMap(), st).contains("GBPUSD: 1.27"))
        val velas = c.callTool("velas", mapOf("ativo" to "EURUSD", "minutos" to "1"), st)
        assertTrue(velas, velas.contains("abre 1.1 fecha 1.2"))
        assertTrue("size tipado como número", ws.sent.any { it.contains("\"size\":1") })

        assertTrue(c.callTool("enviar_ordem", mapOf("ativo" to "EURUSD", "lado" to "buy", "valor" to "50"), st).startsWith("RECUSADO"))
        assertEquals(listOf(dev.fluwy.app.uiconnector.OrderIntent("EURUSD", Side.BUY)), c.orderIntents("enviar_ordem", mapOf("ativo" to "EURUSD", "lado" to "buy", "valor" to "5")))
        val ord = c.callTool("enviar_ordem", mapOf("ativo" to "EURUSD", "lado" to "buy", "valor" to "5"), st)
        assertEquals("Ordem o1: open", ord)
        val ordens = c.snapshot(st).filterIsInstance<UiBlock.Table>()[1]
        assertEquals(listOf("EURUSD", "buy", "5.00", "open"), ordens.rows.first())

        c.logout()
        assertTrue(c.callTool("saldo", emptyMap(), st).startsWith("ERRO"))
    }

    @Test fun `send sem resposta estoura o tempo com erro claro`() = runBlocking {
        val ws = FakeWs { _, _ -> }
        val j = CustomTemplates.BROKER_WS.json.replace("\"timeoutSec\": 10", "\"timeoutSec\": 1")
        val c = CustomConnector(CustomDefParser.parse(j).def!!, http, permissive = true, ws = ws)
        assertTrue(c.login(mapOf("email" to "a", "password" to "b")).isSuccess)
        val out = c.callTool("velas", mapOf("ativo" to "X", "minutos" to "1"), st)
        assertTrue(out, out.startsWith("ERRO") && out.contains("sem resposta"))
    }

    @Test fun `reconecta sozinho e reenvia onOpen`() = runBlocking {
        val ws = brokerWs()
        val c = CustomConnector(CustomDefParser.parse(CustomTemplates.BROKER_WS.json).def!!, http, permissive = true, ws = ws)
        assertTrue(c.login(mapOf("email" to "a", "password" to "b")).isSuccess)
        ws.drop()
        var waited = 0
        while (ws.opens < 2 && waited < 6000) { delay(100); waited += 100 }
        assertEquals(2, ws.opens)
        assertEquals(2, ws.sent.count { it.contains("\"auth\"") })
        c.dispose()
    }

    private fun binanceWs(withData: Boolean) = FakeWs { m, reply ->
        if (withData && m.contains("SUBSCRIBE")) {
            val id = (json.parseToJsonElement(m) as JsonObject)["id"].toString()
            reply("""{"result":null,"id":$id}""")
            reply("""{"e":"24hrTicker","s":"BTCUSDT","c":"65000.10","P":"1.25"}""")
        }
    }

    @Test fun `autoteste de tempo real publico aprova e reprova`() = runBlocking {
        val def = CustomDefParser.parse(CustomTemplates.MARKET_WS.json).def!!
        val okWs = binanceWs(true)
        val c = CustomConnector(def, http, permissive = true, ws = okWs)
        val (passed, report) = c.selfTest(st, mapOf("acompanhar_par" to mapOf("par" to "BNBUSDT"), "preco_rest" to mapOf("par" to "BTCUSDT")))
        assertTrue(report, report.contains("✓ tempo real") && report.contains("✓ coleção precos") && report.contains("✓ ferramenta acompanhar_par"))
        assertTrue("id numérico no SUBSCRIBE", okWs.sent.any { Regex("\"id\":\\d+").containsMatchIn(it) && it.contains("bnbusdt@ticker") })
        assertFalse("REST 404 no fake reprova", passed)
        c.dispose()

        val dead = CustomConnector(def, http, permissive = true, ws = binanceWs(false))
        val (p2, r2) = dead.selfTest(st, emptyMap())
        assertFalse(p2); assertTrue(r2, r2.contains("nenhuma mensagem"))
        dead.dispose()
    }

    @Test fun `validador do bloco realtime`() {
        val base = CustomTemplates.BROKER_WS.json
        fun errs(j: String) = CustomDefParser.parse(j).errors.joinToString("\n")
        assertTrue(errs(base.replace("wss://ws.suacorretora.com/stream", "ws://ws.suacorretora.com/stream")).contains("wss://"))
        assertTrue(errs(base.replace("wss://ws.suacorretora.com/stream", "wss://evil.com/stream")).contains("evil.com"))
        assertTrue(errs(base.replace("wss://ws.suacorretora.com/stream", "wss://ws.suacorretora.com:8443/stream")).contains("443"))
        assertTrue(errs(base.replace("\"source\": \"cotacoes\", \"rows\"", "\"source\": \"naoexiste\", \"rows\"")).contains("naoexiste"))
        assertTrue(errs(base.replace("\"risky\": true, \"opensOrder\": true,", "")).contains("risky"))
        assertTrue(errs(base.replace("{{rt.moeda}}\" }", "{{rt.inexistente}}\" }")).contains("rt.inexistente"))
        assertTrue(errs(base.replace("\"everySec\": 20", "\"everySec\": 1")).contains("everySec"))
        // send sem realtime
        val noRt = CustomTemplates.TOKEN_LOGIN.json.replace("\"request\": { \"path\": \"/items\" } },", "\"send\": { \"message\": {\"a\":1} } },")
        assertTrue(errs(noRt).contains("realtime"))
        // request + send juntos
        val both = base.replace("\"source\": \"cotacoes\", \"list\"", "\"source\": \"cotacoes\", \"request\": {\"path\":\"/q\"}, \"list\"")
        assertTrue(errs(both).contains("só um entre"))
    }
}
