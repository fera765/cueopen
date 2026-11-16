package dev.cueopen.app.pages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextPatchTest {
    @Test fun `exact whitespace and case tolerant replace`() {
        assertEquals("a NOVO c", TextPatch.replace("a b c", "b", "NOVO").getOrThrow())
        assertEquals("<h1>Olá</h1>\n<p>Novo</p>", TextPatch.replace("<h1>Olá</h1>\n<p>Texto   antigo</p>", "<p>Texto antigo</p>", "<p>Novo</p>").getOrThrow())
        assertEquals("Preço: R$ 20", TextPatch.replace("Preço: R$ 10", "preço: r$ 10", "Preço: R$ 20").getOrThrow())
        assertTrue(TextPatch.replace("x x", "x", "y").exceptionOrNull()!!.message!!.contains("2 vezes"))
        val miss = TextPatch.replace("## Resumo\nO mercado cresceu muito em 2026", "mercado caiu em 2026", "z")
        assertTrue(miss.exceptionOrNull()!!.message!!.contains("O mercado cresceu"))
    }

    @Test fun `section replacement keeps the rest`() {
        val md = "# Relatório\n## Preços\nvelho\n## Conclusão\nfim"
        val out = TextPatch.replaceSection(md, "preços", "novo preço").getOrThrow()
        assertTrue(out.contains("## Preços\nnovo preço")); assertTrue(out.contains("## Conclusão\nfim")); assertTrue(!out.contains("velho"))
        assertTrue(TextPatch.replaceSection(md, "Inexistente", "x").exceptionOrNull()!!.message!!.contains("## Preços"))
    }
}
