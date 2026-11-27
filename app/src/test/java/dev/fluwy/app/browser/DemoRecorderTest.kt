package dev.fluwy.app.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoRecorderTest {
    @Test fun `urls lose query and fragment so typed terms are never stored`() {
        assertEquals("https://site.com/busca", DemoRecorder.sanitizeUrl("https://site.com/busca?q=meu+segredo&x=1#topo"))
        assertEquals("Página: https://site.com/a", DemoRecorder.navigation("https://site.com/a?token=abc"))
    }

    @Test fun `events become readable steps and inputs never carry a value`() {
        assertEquals("Tocar em o botão \"Entrar\"", DemoRecorder.describe("""{"k":"click","tag":"button","text":"  Entrar  ","label":""}"""))
        assertEquals("Tocar em o link \"Preços\"", DemoRecorder.describe("""{"k":"click","tag":"a","text":"","label":"Preços"}"""))
        val input = DemoRecorder.describe("""{"k":"input","field":"E-mail","type":"email","value":"eu@exemplo.com"}""")!!
        assertTrue(input.contains("\"E-mail\""))
        assertFalse(input.contains("eu@exemplo.com"))
        assertEquals("Enviar o formulário", DemoRecorder.describe("""{"k":"submit"}"""))
        assertNull(DemoRecorder.describe("""{"k":"outro"}"""))
        assertNull(DemoRecorder.describe("não é json"))
    }

    @Test fun `steps are deduplicated capped and the script never reads field values`() {
        var steps = emptyList<String>()
        steps = DemoRecorder.append(steps, "a"); steps = DemoRecorder.append(steps, "a"); steps = DemoRecorder.append(steps, "b")
        assertEquals(listOf("a", "b"), steps)
        repeat(200) { steps = DemoRecorder.append(steps, "s$it") }
        assertEquals(DemoRecorder.MAX_STEPS, steps.size)
        val js = DemoRecorder.script("PFX")
        assertTrue(js.contains("'PFX'"))
        assertFalse(js.contains(".value"))
    }

    @Test fun `prompt lists the steps as data and escapes angle brackets`() {
        val p = DemoRecorder.toPrompt(listOf("Tocar em o botão \"<b>x</b>\"", "Enviar o formulário"))
        assertTrue(p.contains("1. Tocar em o botão \"&lt;b&gt;x&lt;/b&gt;\""))
        assertTrue(p.contains("2. Enviar o formulário"))
        assertTrue(p.contains("create_skill"))
    }
}
