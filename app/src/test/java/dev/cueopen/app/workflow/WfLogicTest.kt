package dev.cueopen.app.workflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class WfLogicTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) = Calendar.getInstance(utc).apply { clear(); set(y, mo - 1, d, h, mi) }.timeInMillis

    @Test fun templateUsaAnteriorIdCampoERotulo() {
        val outputs = mapOf("a1" to "{\"status\":200,\"data\":{\"items\":[{\"name\":\"x\"}]}}", "b2" to "texto puro")
        val labels = mapOf("a1" to "Baixar", "b2" to "Resumo")
        assertEquals("prev=P", WfLogic.render("prev={{prev}}", outputs, labels, "P"))
        assertEquals("200", WfLogic.render("{{a1.status}}", outputs, labels, ""))
        assertEquals("x", WfLogic.render("{{ a1.data.items.0.name }}", outputs, labels, ""))
        assertEquals("texto puro", WfLogic.render("{{Resumo}}", outputs, labels, ""))
        assertEquals("", WfLogic.render("{{naoexiste}}", outputs, labels, ""))
        assertEquals("2026", WfLogic.render("{{trigger.time}}", outputs, labels, "", mapOf("trigger.time" to "2026")))
    }

    @Test fun condicoes() {
        assertTrue(WfLogic.condition("Erro no servidor", "contains", "erro"))
        assertTrue(WfLogic.condition("10", "gt", "9.5"))
        assertFalse(WfLogic.condition("10", "lt", "2"))
        assertTrue(WfLogic.condition("200", "equals", "200.0"))
        assertTrue(WfLogic.condition("", "empty", ""))
        assertTrue(WfLogic.condition("abc123", "regex", "\\d+"))
        assertFalse(WfLogic.condition("x", "regex", "("))
    }

    @Test fun proximoDisparo() {
        val now = at(2026, 11, 20, 10, 0) // sexta
        assertEquals(at(2026, 11, 21, 9, 0), WfLogic.nextFire(mapOf("mode" to "daily", "time" to "09:00"), now, utc))
        assertEquals(at(2026, 11, 20, 18, 30), WfLogic.nextFire(mapOf("mode" to "daily", "time" to "18:30"), now, utc))
        assertEquals(at(2026, 11, 23, 9, 0), WfLogic.nextFire(mapOf("mode" to "weekly", "time" to "09:00", "weekday" to "1"), now, utc)) // segunda
        assertEquals(at(2026, 11, 22, 9, 0), WfLogic.nextFire(mapOf("mode" to "weekly", "time" to "09:00", "weekday" to "7"), now, utc)) // domingo
        assertEquals(now + 15 * 60_000L, WfLogic.nextFire(mapOf("mode" to "interval", "minutes" to "15"), now, utc))
        assertNull(WfLogic.nextFire(mapOf("mode" to "daily", "time" to "25:00"), now, utc))
    }

    @Test fun validacao() {
        assertTrue(WfLogic.problems(Workflow()).any { it.contains("vazio") })
        val t = WfNode(type = "trigger_manual")
        val a = WfNode(type = "agent")
        val p = WfLogic.problems(Workflow(nodes = listOf(t, a), edges = listOf(WfEdge(from = t.id, to = a.id))))
        assertTrue(p.any { it.contains("Instrução") })
        val ok = WfLogic.problems(Workflow(nodes = listOf(t, a.copy(config = mapOf("prompt" to "oi"))), edges = listOf(WfEdge(from = t.id, to = a.id))))
        assertTrue(ok.isEmpty())
        val back = WfLogic.problems(Workflow(nodes = listOf(t, a), edges = listOf(WfEdge(from = a.id, to = t.id))))
        assertTrue(back.any { it.contains("gatilho não pode") })
    }

    @Test fun rotasDaCondicao() {
        val i = WfNode(type = "if"); val s = WfNode(type = "notify"); val n = WfNode(type = "notify")
        val w = Workflow(nodes = listOf(i, s, n), edges = listOf(WfEdge(from = i.id, to = s.id, port = "true"), WfEdge(from = i.id, to = n.id, port = "false")))
        assertEquals(listOf(s.id), WfLogic.nextNodes(w, i.id, "true").map { it.id })
        assertEquals(listOf(n.id), WfLogic.nextNodes(w, i.id, "false").map { it.id })
    }

    @Test fun htmlTextStripsMarkup() {
        val t = WfLogic.htmlText("<html><head><title>x</title></head><body><script>var a=1</script><h1>Olá</h1><p>Mundo &amp; cia</p></body></html>")
        assertTrue(t.contains("Olá")); assertTrue(t.contains("Mundo & cia")); assertFalse(t.contains("var a"))
    }

    @Test fun pathsListNestedKeys() {
        val p = WfLogic.paths("""{"status":"ok","data":{"id":"1","items":[{"name":"a"}]}}""")
        assertTrue("data.id" in p); assertTrue("data.items.0.name" in p)
    }

    @Test fun layoutPutsChainLeftToRight() {
        val t = WfNode(type = "trigger_manual"); val h = WfNode(type = "http"); val n = WfNode(type = "notify")
        val w = WfLogic.layout(Workflow(nodes = listOf(n, h, t), edges = listOf(WfEdge(from = t.id, to = h.id), WfEdge(from = h.id, to = n.id))))
        val x = w.nodes.associate { it.id to it.x }
        assertTrue(x.getValue(t.id) < x.getValue(h.id)); assertTrue(x.getValue(h.id) < x.getValue(n.id))
    }

    @Test fun signatureChangesWithConfig() {
        val a = WfNode(id = "a", type = "notify", config = mapOf("title" to "x"))
        val w = Workflow(nodes = listOf(a))
        assertEquals(WfLogic.signature(w), WfLogic.signature(w.copy(name = "outro")))
        assertFalse(WfLogic.signature(w) == WfLogic.signature(w.copy(nodes = listOf(a.copy(config = mapOf("title" to "y"))))))
    }

    @Test fun problemsFlagUnknownTemplateRefs() {
        val t = WfNode(id = "t1", type = "trigger_manual")
        val n = WfNode(id = "n1", type = "notify", config = mapOf("title" to "x", "text" to "{{g1.data.subject}} {{t1}} {{prev}} {{trigger.time}}"))
        val w = Workflow(nodes = listOf(t, n), edges = listOf(WfEdge(from = t.id, to = n.id)))
        val p = WfLogic.problems(w)
        assertTrue(p.any { it.contains("g1.data.subject") })
        assertFalse(p.any { it.contains("{{t1}}") || it.contains("{{prev}}") || it.contains("trigger.time") })
    }

    @Test fun compactKeepsValidJson() {
        val big = buildString { append("{\"data\":{\"messages\":["); repeat(60) { i -> if (i > 0) append(','); append("{\"id\":\"$i\",\"headers\":{"); repeat(20) { h -> if (h > 0) append(','); append("\"x-h$h\":\"${"v".repeat(400)}\"") }; append(",\"subject\":\"s$i\"},\"body\":\"${"b".repeat(3000)}\"}") }; append("]}}") }
        val out = JsonCompact.shrinkText(big, 30_000)
        assertTrue(out.length <= 40_000)
        val el = kotlinx.serialization.json.Json.parseToJsonElement(out)  // continua JSON válido
        assertEquals("s0", WfLogic.pick(el.toString(), "data.messages.0.headers.subject"))
    }

    @Test fun readableFormatsEmailListWithoutJson() {
        val raw = """{"status":"ok","connector":"Gmail","action":"gmail_search_email","data":{"found":true,"results":{"messages":[
            {"id":"a1","headers":{"subject":"Pedido enviado","from":"Loja <loja@x.com>","date":"Fri, 20 Nov"},"subject":"Pedido enviado","from":"Loja <loja@x.com>","date":"Fri, 20 Nov"},
            {"id":"a2","headers":{"subject":"Reunião","from":"Ana <ana@y.com>","date":"Thu, 19 Nov"}}]}}}"""
        val r = WfFormat.readable(raw)
        assertTrue(r.contains("Pedido enviado")); assertTrue(r.contains("Reunião")); assertTrue(r.contains("Ana"))
        assertFalse(r.contains("{")); assertFalse(r.contains("\"id\""))
        assertEquals("2 e-mails · Pedido enviado", WfFormat.oneLine(raw))
    }

    @Test fun renderHumanizesJsonValues() {
        val out = mapOf("g1" to "{\"data\":{\"items\":[{\"title\":\"A\"},{\"title\":\"B\"}]},\"status\":\"ok\",\"connector\":\"x\"}")
        val r = WfLogic.render("Novos: {{g1}}", out, emptyMap(), "", humanize = WfFormat::oneLine)
        assertEquals("Novos: 2 itens · A", r)
    }

    @Test fun personAndDateAreHumanized() {
        assertEquals("Humans at Magica", WfFormat.person(kotlinx.serialization.json.JsonPrimitive("\"Humans at Magica\" <humans@mail.galaxy.ai>")))
        assertEquals("ana@x.com", WfFormat.person(kotlinx.serialization.json.JsonPrimitive("<ana@x.com>")))
        val d = WfFormat.date("2020-03-04T10:20:00.000Z")!!
        assertTrue(d.contains("/2020") || d.contains("04/03"))
        assertFalse(WfFormat.date("Sat, 21 Nov 2026 00:24:07 +0000")!!.contains("+0000"))
    }

    @Test fun viewBuildsEmailCards() {
        val raw = """{"status":"ok","connector":"Gmail","data":{"results":{"messages":[
            {"id":"1","subject":"Oi","from":"\"Ana\" <ana@x.com>","date":"2020-03-04T10:20:00Z","snippet":"Tudo bem?","attachments":[{"fileName":"a.pdf","mimeType":"application/pdf"},{"fileName":"i.png","mimeType":"image/png","data":"http://127.0.0.1:1/i.png"}]}]}}}"""
        val v = WfView.build("Novos e-mails", "Toque", null, raw, "hoje")
        assertTrue(WfView.isView(v))
        val blocks = WfView.blocksOf(v)
        assertEquals("header", blocks[0]["type"].toString().trim('"'))
        val card = (blocks[1]["items"] as kotlinx.serialization.json.JsonArray)[0] as kotlinx.serialization.json.JsonObject
        assertEquals("\"Oi\"", card["title"].toString()); assertEquals("\"Ana\"", card["subtitle"].toString())
        assertEquals("\"http://127.0.0.1:1/i.png\"", card["image"].toString())
        assertTrue(card["chips"].toString().contains("a.pdf"))
        assertTrue(blocks[0]["subtitle"].toString().contains("1 e-mail"))
    }

    @Test fun templateExpandsAutoBlocks() {
        val tpl = """{"blocks":[{"type":"header","title":"Resumo {{x}}"},{"type":"auto","data":"{{g1}}"}]}"""
        val v = WfView.fromTemplate(tpl, { it.replace("{{x}}", "hoje") }, { """{"data":{"items":[{"title":"A"},{"title":"B"}]}}""" })
        val b = WfView.blocksOf(v)
        assertEquals("\"Resumo hoje\"", b[0]["title"].toString())
        assertEquals("\"list\"", b[1]["type"].toString())
    }

    @Test fun filtersExtractEmailAndDefault() {
        val out = mapOf("g1" to "{\"from\":\"\\\"Ana Souza\\\" <ana@x.com>\",\"subject\":\"Oi\"}")
        assertEquals("ana@x.com", WfLogic.render("{{g1.from | email}}", out, emptyMap(), ""))
        assertEquals("Ana Souza", WfLogic.render("{{g1.from | name}}", out, emptyMap(), ""))
        assertEquals("n/d", WfLogic.render("{{g1.nada | default:n/d}}", out, emptyMap(), ""))
        val w = Workflow(nodes = listOf(WfNode(id = "t1", type = "trigger_manual"), WfNode(id = "n1", type = "notify", config = mapOf("text" to "{{t1.time | upper}}"))),
            edges = listOf(WfEdge(from = "t1", to = "n1")))
        assertFalse(WfLogic.problems(w).any { it.contains("não aponta") })
    }

    @Test fun reviewShowsSingleMailInDetail() {
        val raw = """{"status":"ok","connector":"Gmail","data":{"results":{"messages":[{"id":"1","subject":"Proposta","from":"Bia <bia@y.com>","body_text":"Segue a proposta **anexa**.",
            "attachments":[{"fileName":"proposta.pdf","mimeType":"application/pdf"},{"fileName":"foto.jpg","mimeType":"image/jpeg","data":"http://127.0.0.1:1/f.jpg"}]}]}}}"""
        val blocks = WfView.itemBlocks(raw)
        val types = blocks.map { it["type"].toString().trim('"') }
        assertEquals(listOf("header", "card", "image", "chips", "text"), types)
        assertTrue(blocks[1]["subtitle"].toString().contains("bia@y.com"))
        assertTrue(blocks[4]["text"].toString().contains("\\\\*\\\\*anexa"))
    }
}
