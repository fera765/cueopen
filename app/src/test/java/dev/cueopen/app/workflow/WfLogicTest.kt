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
}
