package dev.cueopen.app.sessionapp

import dev.cueopen.app.agent.EditHint
import dev.cueopen.app.ui.CueUi
import dev.cueopen.app.ui.CueWidget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionAppStoreTest {
    private fun app(marker: String) = "<!doctype html><html><head><title>t</title></head><body><div id='a'><h1>$marker</h1><ul><li>x</li></ul></div>" +
        "<script>SessionApp.onUpdate(function(s){document.getElementById('a').dataset.n=s.agents?s.agents.length:0;});</script>" + " ".repeat(300) + "</body></html>"

    private fun withStore(block: (SessionAppStore) -> Unit) {
        val dir = kotlin.io.path.createTempDirectory().toFile()
        try { block(SessionAppStore(dir)) } finally { dir.deleteRecursively() }
    }

    @Test fun `each section has its own single file app with versions and rollback`() = withStore { s ->
        assertNull(s.meta("A"))
        assertEquals(1, s.publish("A", app("um"), "Catálogo").version)
        assertEquals(2, s.publish("A", app("dois"), "").version)
        assertEquals("Catálogo", s.meta("A")!!.title)
        assertTrue(s.html("A")!!.contains("dois"))
        assertEquals(listOf(1), s.versions("A"))
        assertEquals(3, s.rollback("A", 1)!!.version)
        assertTrue(s.html("A")!!.contains("um"))
        assertNull(s.rollback("A", 99))
        // isolamento: outra seção não vê nada disso
        assertFalse(s.exists("B")); assertTrue(s.data("B").isEmpty())
        s.publish("B", app("outro"), "B")
        assertTrue(s.html("A")!!.contains("um") && s.html("B")!!.contains("outro"))
    }

    @Test fun `publishing a new version keeps data and app state`() = withStore { s ->
        s.publish("A", app("v1"), "x")
        assertNull(s.setData("A", "ofertas", "[{\"nome\":\"x\",\"preco\":10}]"))
        assertTrue(s.saveState("A", "{\"filtro\":\"barato\"}"))
        s.publish("A", app("v2"), "x")
        assertEquals("[{\"nome\":\"x\",\"preco\":10}]", s.data("A")["ofertas"])
        assertEquals("{\"filtro\":\"barato\"}", s.loadState("A"))
        s.clearRuntime("A")
        assertTrue(s.data("A").isEmpty()); assertEquals("null", s.loadState("A")); assertTrue(s.exists("A"))
        s.delete("A"); assertFalse(s.exists("A"))
    }

    @Test fun `data keys and sizes are limited`() = withStore { s ->
        assertNotNull(s.setData("A", "Chave Inválida", "1"))
        assertNotNull(s.setData("A", "ok", "x".repeat(SessionAppStore.MAX_DATA_VALUE + 1)))
        assertFalse(s.saveState("A", "x".repeat(SessionAppStore.MAX_STATE + 1)))
        // id com "../" é sanitizado: nunca grava fora da pasta base
        assertNull(s.setData("../../fora", "k", "1"))
        assertEquals("1", s.data("fora")["k"])
    }

    @Test fun `validator rejects empty shells and apps without the session api`() {
        assertNull(SessionAppValidator.validate(app("ok")))
        assertNotNull(SessionAppValidator.validate("<html><body></body></html>"))
        assertNotNull(SessionAppValidator.validate(app("x").replace("SessionApp", "Outro")))
        assertNotNull(SessionAppValidator.validate(app("x").replace("</script>", "")))
        assertNotNull(SessionAppValidator.validate("x".repeat(SessionAppStore.MAX_HTML + 1)))
        val boot = SessionAppValidator.withBootstrap(app("x"))
        assertTrue(boot.indexOf("window.SessionApp=") in 1 until boot.indexOf("<body"))
        assertTrue(SessionAppValidator.withBootstrap("<div>sem head</div>").startsWith("<script>"))
    }

    @Test fun `ui kit parses new components composes reports and survives bad input`() {
        val r = CueUi.parse("""{"type":"report","title":"Final","blocks":[{"type":"summary","text":"ok","bullets":["a"]},{"type":"checklist","items":[{"text":"x","done":true},"y"]},
            {"type":"files","items":[{"path":"out/a.md","note":"n"}]},{"type":"report","blocks":[]},{"type":"desconhecido"}]}""", complete = true)
        assertTrue(r is CueWidget.Group)
        val blocks = (r as CueWidget.Group).blocks
        assertEquals(3, blocks.size)
        assertEquals(listOf("x" to true, "y" to false), (blocks[1] as CueWidget.Checklist).items)
        assertTrue(CueUi.parse("""{"type":"timeline","items":[{"when":"10h","title":"a"}]}""", true) is CueWidget.Timeline)
        assertTrue(CueUi.parse("""{"type":"tabs","tabs":[{"title":"A","text":"b"}]}""", true) is CueWidget.Tabs)
        // streaming: bloco ainda incompleto vira esqueleto ou render parcial, nunca erro
        assertFalse(CueUi.parse("""{"type":"compare","options":[{"name":"A","items":["x""", complete = false) is CueWidget.Callout)
        assertTrue(CueUi.parse("{{{", complete = true) is CueWidget.Callout)
    }

    @Test fun `edit hint shows the closest real lines`() {
        val file = "linha um\nfun total(a: Int) = a * 2\nlinha três\nfim"
        val hint = EditHint.nearest(file, "fun total(a: Int) = a * 3")
        assertTrue(hint.contains("2| fun total(a: Int) = a * 2"))
        assertEquals("", EditHint.nearest(file, "zzzz qqqq"))
    }
}
