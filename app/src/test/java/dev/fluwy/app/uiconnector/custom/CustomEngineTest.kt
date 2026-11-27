package dev.fluwy.app.uiconnector.custom

import dev.fluwy.app.trading.Side
import dev.fluwy.app.uiconnector.ConnectorSettings
import dev.fluwy.app.uiconnector.UiBlock
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomEngineTest {
    private val doc = Json.parseToJsonElement("""{"a":{"b":[{"n":1,"s":"x"},{"n":2,"s":"y"}]},"k.e":3,"nul":null}""")

    @Test fun `jsonpath navega`() {
        assertEquals("x", JsonPath.text(JsonPath.first(doc, "$.a.b[0].s")))
        assertEquals("y", JsonPath.text(JsonPath.first(doc, "$.a.b[-1].s")))
        assertEquals(listOf("1", "2"), JsonPath.select(doc, "$.a.b[*].n").map { JsonPath.text(it) })
        assertEquals("3", JsonPath.text(JsonPath.first(doc, "$['k.e']")))
        assertEquals("", JsonPath.text(JsonPath.first(doc, "$.nul")))
        assertTrue(JsonPath.select(doc, "$.nada.x").isEmpty())
        assertFalse(JsonPath.isValid("a.b")); assertFalse(JsonPath.isValid("$.a[")); assertFalse(JsonPath.isValid("$..a"))
    }

    @Test fun `template renderiza e aplica filtros`() {
        val ctx = Template.Ctx(mapOf("email" to "a b@x.com", "n" to "3.14159", "e" to ""), doc)
        assertEquals("a%20b%40x.com", Template.render("{{email|url}}", ctx))
        assertEquals("3.14", Template.render("{{n|num:2}}", ctx))
        assertEquals("3", Template.render("{{n|int}}", ctx))
        assertEquals("fallback", Template.render("{{e|default:fallback}}", ctx))
        assertEquals("x-1", Template.render("{{$.a.b[0].s}}-{{$.a.b[0].n}}", ctx))
        assertEquals("\"a b@x.com\"", Template.render("{{email|json}}", ctx))
        assertEquals("15.0%", Template.render("{{n|pct:1}}".replace("n", "p"), Template.Ctx(mapOf("p" to "15"))))
        assertEquals("", Template.render("{{faltando}}", ctx))
    }

    @Test fun `template tipa numeros no corpo json`() {
        val body = Json.parseToJsonElement("""{"q":"{{v|num}}","t":"{{v}}","f":"{{b|bool}}"}""") as JsonObject
        val r = Template.renderJson(body, Template.Ctx(mapOf("v" to "10", "b" to "true"))) as JsonObject
        assertEquals("10", r["q"].toString()); assertEquals("\"10\"", r["t"].toString()); assertEquals("true", r["f"].toString())
    }

    @Test fun `template valida referencias`() {
        assertTrue(Template.problems("{{a}} {{s.x|num:2}}", setOf("a", "s.x"), false).isEmpty())
        assertTrue(Template.problems("{{zzz}}", setOf("a"), false).isNotEmpty())
        assertTrue(Template.problems("{{$.a}}", setOf("a"), false).isNotEmpty())
        assertTrue(Template.problems("{{$.a}}", setOf("a"), true).isEmpty())
        assertTrue(Template.problems("{{a|xpto}}", setOf("a"), false).isNotEmpty())
        assertTrue(Template.problems("{{a|num:99}}", setOf("a"), false).isNotEmpty())
    }

    @Test fun `url guard`() {
        assertNull(UrlGuard.hostProblem("api.exemplo.com"))
        listOf("localhost", "127.0.0.1", "192.168.0.10", "10.0.0.1", "[::1]", "minha.local", "svc.internal", "semponto", "a..b", "1.2.3.com.99").forEach {
            assertNotNull("host '$it' deveria ser recusado", UrlGuard.hostProblem(it))
        }
        assertEquals("api.exemplo.com", UrlGuard.baseHost("https://api.exemplo.com/v1").first)
        assertNotNull(UrlGuard.baseHost("http://api.exemplo.com").second)
        assertNotNull(UrlGuard.baseHost("https://user:pw@api.exemplo.com").second)
        assertNotNull(UrlGuard.baseHost("https://api.exemplo.com:8443").second)
        assertNull(UrlGuard.check("https://api.exemplo.com/x?y=1", "api.exemplo.com"))
        assertNotNull(UrlGuard.check("https://evil.com/x", "api.exemplo.com"))
        assertNotNull(UrlGuard.check("http://api.exemplo.com/x", "api.exemplo.com"))
        assertNotNull(UrlGuard.check("https://api.exemplo.com@evil.com/x", "api.exemplo.com"))
        assertNotNull(UrlGuard.check("https://api.exemplo.com:444/x", "api.exemplo.com"))
    }

    @Test fun `dns seguro bloqueia redes privadas`() {
        listOf("127.0.0.1", "10.1.2.3", "192.168.1.1", "172.16.0.5", "169.254.169.254", "100.64.0.1", "0.0.0.0", "::1", "fd00::1").forEach {
            assertTrue("$it deveria ser bloqueado", SafeDns.blocked(java.net.InetAddress.getByName(it)))
        }
        assertFalse(SafeDns.blocked(java.net.InetAddress.getByName("8.8.8.8")))
    }

    @Test fun `fixtures JSON legadas permanecem legiveis para compatibilidade`() {
        CustomTemplates.ALL.forEach { t ->
            val p = CustomDefParser.parse(t.json)
            assertEquals("modelo '${t.title}': ${p.errors}", emptyList<String>(), p.errors)
            assertNotNull(p.def)
        }
    }

    private fun mod(json: String, from: String, to: String) = json.replace(from, to)
    private val base = CustomTemplates.TOKEN_LOGIN.json

    @Test fun `validador pega os erros mais comuns`() {
        fun errs(j: String) = CustomDefParser.parse(j).errors.joinToString("\n")
        assertTrue(errs("{").contains("YAML inválido"))
        assertTrue(errs("[]").contains("mapa YAML"))
        assertTrue(errs(mod(base, "https://api.exemplo.com", "http://api.exemplo.com")).contains("https"))
        assertTrue(errs(mod(base, "https://api.exemplo.com", "https://127.0.0.1")).contains("IP"))
        assertTrue(errs(mod(base, "\"type\": \"password\"", "\"type\": \"text\"")).contains("PASSWORD"))
        assertTrue(errs(mod(base, "{{token}}", "{{naoexiste}}")).contains("variável desconhecida"))
        assertTrue(errs(mod(base, "\"method\": \"POST\", \"path\": \"/send\"", "\"method\": \"POST\", \"path\": \"https://evil.com/x\"")).contains("path"))
        assertTrue(errs(mod(base, "\"risky\": true,\n      \"params\"", "\"params\"")).contains("risky"))
        assertTrue(errs(mod(base, "\"maxSetting\": \"maxValor\"", "\"maxSetting\": \"outro\"")).contains("maxSetting"))
        assertTrue(errs(mod(base, "\"id\": \"minhaapi\"", "\"id\": \"Minha_API\"")).contains("id"))
        assertTrue(errs(mod(base, "\"extract\": { \"token\": \"$.token\" }", "\"extract\": { \"token\": \"token\" }")).contains("extract"))
        assertTrue(CustomDefParser.parse("x".repeat(70_000)).errors.single().contains("64 KB"))
    }

    @Test fun `chave desconhecida vira aviso`() {
        val p = CustomDefParser.parse(mod(base, "\"version\": \"1.0\",", "\"version\": \"1.0\", \"bogus\": 1,"))
        assertTrue(p.errors.isEmpty()); assertTrue(p.warnings.any { it.contains("bogus") })
    }

    @Test fun `opensOrder exige orderIntent`() {
        val j = mod(base, "\"risky\": true,\n      \"params\"", "\"risky\": true, \"opensOrder\": true,\n      \"params\"")
        assertTrue(CustomDefParser.parse(j).errors.any { it.contains("orderIntent") })
        val ok = mod(j, "\"limits\"", "\"orderIntent\": {\"asset\":\"{{destino}}\",\"direction\":\"buy\"}, \"limits\"")
        val p = CustomDefParser.parse(ok)
        assertEquals(emptyList<String>(), p.errors)
    }

    @Test fun `orderIntent identifica ordem sem opensOrder`() {
        val existing = mod(CustomTemplates.BROKER_WS.json, "\"risky\": true, \"opensOrder\": true,", "\"risky\": true,")
        val parsed = CustomDefParser.parse(existing)
        assertEquals(emptyList<String>(), parsed.errors)
        val tools = parsed.def!!.tools.map { it.name to it.opensOrder }
        assertTrue("tools inferred from orderIntent: $tools", tools.any { it.second })
    }

    // ---- conector com HTTP falso
    private class Fake(val handler: (HttpReq) -> HttpRes) : HttpExec {
        val calls = ArrayList<HttpReq>()
        override suspend fun exec(r: HttpReq): HttpRes { calls.add(r); return handler(r) }
    }

    private val st = ConnectorSettings { k -> if (k == "maxValor") "10" else null }

    private fun fake() = Fake { r ->
        when {
            r.url.endsWith("/auth/login") -> HttpRes(200, emptyMap(), """{"token":"SEGREDO-123"}""")
            r.url.endsWith("/me") -> if (r.headers["Authorization"] == "Bearer SEGREDO-123") HttpRes(200, emptyMap(), """{"name":"Ana","email":"a@x.com"}""") else HttpRes(401, emptyMap(), "{}")
            r.url.endsWith("/items") -> HttpRes(200, emptyMap(), """{"items":[{"name":"A","value":1.5},{"name":"B","value":2}]}""")
            r.url.endsWith("/send") -> HttpRes(200, emptyMap(), """{"id":"tx1"}""")
            else -> HttpRes(404, emptyMap(), "nf")
        }
    }

    private fun connector(f: Fake): CustomConnector = CustomConnector(CustomDefParser.parse(base).def!!, f, permissive = true)
    private val creds = mapOf("email" to "a@x.com", "password" to "senha-forte")

    @Test fun `login extrai token e monta paineis`() = runBlocking {
        val f = fake(); val c = connector(f)
        assertTrue(c.login(creds).isSuccess); assertTrue(c.isLoggedIn)
        val login = f.calls.first()
        assertEquals("POST", login.method)
        assertTrue(login.body!!.contains("\"password\":\"senha-forte\""))
        assertNull("headers globais nao vao no login", login.headers["Authorization"])
        val blocks = c.snapshot(st)
        assertEquals("Ana", (blocks[0] as UiBlock.Header).title)
        val t = blocks[1] as UiBlock.Table
        assertEquals(listOf(listOf("A", "1.50"), listOf("B", "2.00")), t.rows)
        assertTrue(c.secrets().containsAll(listOf("senha-forte", "SEGREDO-123")))
    }

    @Test fun `login recusado nao conecta`() = runBlocking {
        val c = CustomConnector(CustomDefParser.parse(base).def!!, Fake { HttpRes(401, emptyMap(), "{}") }, permissive = true)
        assertTrue(c.login(creds).isFailure); assertFalse(c.isLoggedIn)
    }

    @Test fun `ferramenta respeita limite e esquema`() = runBlocking {
        val f = fake(); val c = connector(f); c.login(creds)
        assertTrue(c.callTool("enviar", mapOf("destino" to "bob", "valor" to "50"), st).startsWith("RECUSADO"))
        assertTrue(c.callTool("enviar", mapOf("destino" to "bob", "valor" to "0"), st).startsWith("RECUSADO"))
        assertTrue(c.callTool("enviar", mapOf("destino" to "bob", "valor" to "abc"), st).startsWith("ERRO"))
        assertTrue(c.callTool("enviar", mapOf("valor" to "5"), st).startsWith("ERRO"))
        assertEquals(0, f.calls.count { it.url.endsWith("/send") })
        assertEquals("Enviado: tx1", c.callTool("enviar", mapOf("destino" to "bob", "valor" to "5"), st))
        val sent = f.calls.last { it.url.endsWith("/send") }
        assertTrue(sent.body!!.contains("\"amount\":5") && sent.body!!.contains("\"to\":\"bob\""))
    }

    @Test fun `sessao expirada refaz login uma vez`() = runBlocking {
        var first = true
        val f = Fake { r ->
            when {
                r.url.endsWith("/auth/login") -> HttpRes(200, emptyMap(), """{"token":"SEGREDO-123"}""")
                r.url.endsWith("/items") -> if (first) { first = false; HttpRes(401, emptyMap(), "{}") } else HttpRes(200, emptyMap(), """{"items":[]}""")
                else -> HttpRes(200, emptyMap(), """{"name":"Ana","email":"a"}""")
            }
        }
        val c = connector(f); c.login(creds)
        assertTrue(c.callTool("listar_itens", emptyMap(), st).contains("items"))
        assertEquals(2, f.calls.count { it.url.endsWith("/auth/login") })
    }

    @Test fun `bloqueia host diferente e orderIntents`() = runBlocking {
        val def = CustomDefParser.parse(
            mod(mod(base, "\"risky\": true,\n      \"params\"", "\"risky\": true, \"opensOrder\": true,\n      \"params\""), "\"limits\"", "\"orderIntent\": {\"asset\":\"{{destino}}\",\"direction\":\"{{valor}}\"}, \"limits\"")
        ).def!!
        val c = CustomConnector(def, fake(), permissive = true)
        assertTrue(c.orderIntents("enviar", mapOf("destino" to "EURUSD", "valor" to "5")).isEmpty()) // direction invalida
        val def2 = CustomDefParser.parse(mod(mod(base, "\"risky\": true,\n      \"params\"", "\"risky\": true, \"opensOrder\": true,\n      \"params\""), "\"limits\"", "\"orderIntent\": {\"asset\":\"{{destino}}\",\"direction\":\"buy\"}, \"limits\"")).def!!
        val i = CustomConnector(def2, fake(), permissive = true).orderIntents("enviar", mapOf("destino" to "EURUSD", "valor" to "5"))
        assertEquals(1, i.size); assertEquals(Side.BUY, i[0].side); assertEquals("EURUSD", i[0].asset)
    }

    @Test fun `prompt do sdk documenta YAML sem presets JSON e aceita cercas markdown`() {
        assertTrue(SdkPrompt.MARKDOWN.contains("`baseUrl`"))
        assertTrue(SdkPrompt.MARKDOWN.contains("definição YAML"))
        assertFalse(SdkPrompt.MARKDOWN.contains("```json"))
        assertFalse(SdkPrompt.MARKDOWN.contains(CustomTemplates.TOKEN_LOGIN.json))
        assertFalse(SdkPrompt.MARKDOWN.contains("${'$'}{"))
        val fenced = "```json\n" + CustomTemplates.MINIMAL.json + "\n```"
        assertEquals(emptyList<String>(), CustomDefParser.parse(fenced).errors)
    }

    private val rss = """<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0" xmlns:media="http://search.yahoo.com/mrss/"><channel><title>g1</title>
<item><title><![CDATA[Manchete <b>um</b>]]></title><link>https://g1.globo.com/a</link><pubDate>Tue, 27 Oct 2026</pubDate><media:content url="https://img/1.jpg"/></item>
<item><title>Manchete dois</title><link>https://g1.globo.com/b</link></item>
</channel></rss>"""

    @Test fun `xml e rss viram arvore navegavel`() {
        val t = ResponseTree.parse(rss)
        assertEquals(listOf("https://g1.globo.com/a", "https://g1.globo.com/b"), JsonPath.select(t, "$.rss.channel.item[*].link").map { JsonPath.text(it) })
        assertEquals("https://img/1.jpg", JsonPath.text(JsonPath.first(t, "$.rss.channel.item[0]['media:content']['@url']")))
        assertEquals("Manchete um", Template.render("{{$.rss.channel.item[0].title|strip}}", Template.Ctx(emptyMap(), t)))
        // item unico: [*] devolve o proprio elemento
        val one = ResponseTree.parse("<feed><entry><title>So</title></entry></feed>")
        assertEquals(listOf("So"), JsonPath.select(one, "$.feed.entry[*].title").map { JsonPath.text(it) })
        assertEquals("oi", JsonPath.text(JsonPath.first(ResponseTree.parse("oi"), "$.body")))
        assertNull(ResponseTree.xml("<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><x>&e;</x>")?.let { if (JsonPath.text(JsonPath.first(it, "$.x")).contains("root")) it else null })
    }

    @Test fun `filtros strip trunc base64`() {
        val c = Template.Ctx(mapOf("h" to "<p>Olá &amp; mundo</p>", "l" to "abcdefghij", "u" to "id:sec"))
        assertEquals("Olá & mundo", Template.render("{{h|strip}}", c))
        assertEquals("abc…", Template.render("{{l|trunc:3}}", c))
        assertEquals("aWQ6c2Vj", Template.render("{{u|base64}}", c))
    }

    @Test fun `conector rss sem login lista noticias`() = runBlocking {
        val def = CustomDefParser.parse(CustomTemplates.RSS.json).def!!
        val f = Fake { HttpRes(200, mapOf("content-type" to listOf("application/rss+xml")), rss) }
        val c = CustomConnector(def, f, permissive = true)
        assertTrue(c.isLoggedIn)
        val out = c.callTool("ultimas_noticias", emptyMap(), ConnectorSettings { null })
        assertTrue(out, out.startsWith("1. Manchete um — https://g1.globo.com/a"))
        assertTrue(out.contains("2. Manchete dois"))
        val t = c.snapshot(ConnectorSettings { null })[0] as UiBlock.Table
        assertEquals(listOf(listOf("Manchete um"), listOf("Manchete dois")), t.rows)
    }

    @Test fun `oauth com form e segundo host`() = runBlocking {
        val def = CustomDefParser.parse(CustomTemplates.SPOTIFY.json).def!!
        assertEquals(setOf("api.spotify.com", "accounts.spotify.com"), def.hosts)
        val f = Fake { r ->
            when {
                r.url == "https://accounts.spotify.com/api/token" -> HttpRes(200, emptyMap(), """{"access_token":"TOKEN-XYZ","expires_in":3600}""")
                r.url.startsWith("https://api.spotify.com/v1/search") -> HttpRes(200, emptyMap(), """{"artists":{"items":[{"name":"Anitta","external_urls":{"spotify":"https://open.spotify.com/artist/1"}}]}}""")
                else -> HttpRes(404, emptyMap(), "{}")
            }
        }
        val c = CustomConnector(def, f, permissive = true)
        assertTrue(c.login(mapOf("clientId" to "abc", "clientSecret" to "s3cr3t")).isSuccess)
        val tok = f.calls.first()
        assertEquals("application/x-www-form-urlencoded", tok.contentType)
        assertEquals("grant_type=client_credentials&client_id=abc&client_secret=s3cr3t", tok.body)
        val out = c.callTool("buscar", mapOf("termo" to "anitta", "tipo" to "artist"), ConnectorSettings { null })
        assertEquals("1. Anitta — https://open.spotify.com/artist/1", out)
        assertTrue(f.calls.last().url.contains("q=anitta") && f.calls.last().headers["Authorization"] == "Bearer TOKEN-XYZ")
        assertNotNull(UrlGuard.check("https://evil.com/x", def.hosts))
    }

    @Test fun `url absoluta precisa de host declarado`() {
        val bad = CustomTemplates.SPOTIFY.json.replace("\"allowedHosts\": [ \"accounts.spotify.com\" ],", "")
        assertTrue(CustomDefParser.parse(bad).errors.any { it.contains("allowedHosts") })
        val both = CustomTemplates.MINIMAL.json.replace("\"path\": \"/data\"", "\"path\": \"/data\", \"url\": \"https://api.exemplo.com/x\"")
        assertTrue(CustomDefParser.parse(both).errors.any { it.contains("OU") })
        val ipHost = CustomTemplates.SPOTIFY.json.replace("accounts.spotify.com\" ]", "10.0.0.1\" ]")
        assertTrue(CustomDefParser.parse(ipHost).errors.any { it.contains("allowedHosts") })
    }

    @Test fun `xml truncado e entidades ainda sao lidos`() {
        val t = ResponseTree.parse(rss.substring(0, rss.indexOf("<item><title>Manchete dois")) + "<item><title>Corta&amp;do")
        assertEquals("https://g1.globo.com/a", JsonPath.text(JsonPath.first(t, "$.rss.channel.item[0].link")))
        assertEquals("Corta&do", JsonPath.text(JsonPath.first(t, "$.rss.channel.item[1].title")))
        assertEquals("é", JsonPath.text(JsonPath.first(ResponseTree.parse("<a>&#233;</a>"), "$.a")))
        // html nao vira xml
        assertTrue(JsonPath.text(JsonPath.first(ResponseTree.parse("<!DOCTYPE html><html><body>x</body></html>"), "$.body")).contains("<html>"))
    }

    @Test fun `autoteste aprova rss valido e reprova caminho errado com estrutura`() = runBlocking {
        val f = Fake { HttpRes(200, mapOf("content-type" to listOf("text/xml")), rss) }
        val okDef = CustomDefParser.parse(CustomTemplates.RSS.json).def!!
        val (ok, rep) = CustomConnector(okDef, f, permissive = true).selfTest(ConnectorSettings { null }, emptyMap())
        assertTrue(rep, ok)
        val badDef = CustomDefParser.parse(CustomTemplates.RSS.json.replace("$.rss.channel.item[*]", "$.channel.item[*]")).def!!
        val (bad, rep2) = CustomConnector(badDef, f, permissive = true).selfTest(ConnectorSettings { null }, emptyMap())
        assertFalse(bad)
        assertTrue(rep2, rep2.contains("rss") && rep2.contains("✗"))
    }

    @Test fun `autoteste exige exemplos para parametros obrigatorios`() = runBlocking {
        val f = Fake { HttpRes(200, emptyMap(), """{"artists":{"items":[{"name":"A","external_urls":{"spotify":"u"}}]}}""") }
        val def = CustomDefParser.parse(CustomTemplates.SPOTIFY.json.replace("\"login\"", "\"loginX\"")).def
        // sem login a definicao do Spotify fica invalida (token desconhecido) — usa o conector logado
        assertNull(def)
        val sp = CustomConnector(CustomDefParser.parse(CustomTemplates.SPOTIFY.json).def!!, f, permissive = true)
        val (ok0, msg0) = sp.selfTest(ConnectorSettings { null }, emptyMap())
        // guardrails: sem login, painéis/HTTP ficam para depois do login (o resto é validado agora)
        assertTrue(msg0, ok0); assertTrue(msg0, msg0.contains("login"))
    }

    @Test fun `formatos variados viram arvore`() {
        val soap = ResponseTree.parse("""<?xml version="1.0"?><soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"><soap:Body><m:Price xmlns:m="x"><m:Value currency="BRL">10.5</m:Value></m:Price></soap:Body></soap:Envelope>""")
        assertEquals("10.5", JsonPath.text(JsonPath.first(soap, "$['soap:Envelope']['soap:Body']['m:Price']['m:Value']['#text']")))
        assertEquals("BRL", JsonPath.text(JsonPath.first(soap, "$['soap:Envelope']['soap:Body']['m:Price']['m:Value']['@currency']")))
        val atom = ResponseTree.parse("""<feed xmlns="http://www.w3.org/2005/Atom"><entry><title type="text">A</title><link href="https://x/a"/></entry><entry><title>B</title><link href="https://x/b"/></entry></feed>""")
        assertEquals(listOf("https://x/a", "https://x/b"), JsonPath.select(atom, "$.feed.entry[*].link['@href']").map { JsonPath.text(it) })
        assertEquals("A", JsonPath.text(JsonPath.first(atom, "$.feed.entry[0].title['#text']")))
        val sitemap = ResponseTree.parse("<urlset><url><loc>https://s/1</loc></url><url><loc>https://s/2</loc></url></urlset>")
        assertEquals(2, JsonPath.select(sitemap, "$.urlset.url[*].loc").size)
        assertEquals("1", JsonPath.text(JsonPath.first(ResponseTree.parse("cb({\"a\":1});"), "$.a")))
        assertEquals(2, JsonPath.select(ResponseTree.parse("{\"a\":1}\n{\"a\":2}\n"), "$[*].a").size)
        val csv = ResponseTree.parse("nome,preco\n\"Café, forte\",10\nChá,5\n", "text/csv")
        assertEquals(listOf("Café, forte", "Chá"), JsonPath.select(csv, "$.rows[*].nome").map { JsonPath.text(it) })
        val html = ResponseTree.parse("<!doctype html><html><head><title>Oi &amp; tchau</title><script>x()</script></head><body><p>Texto</p></body></html>")
        assertEquals("Oi & tchau", JsonPath.text(JsonPath.first(html, "$.title")))
        assertFalse(JsonPath.text(JsonPath.first(html, "$.text")).contains("x()"))
    }
}
