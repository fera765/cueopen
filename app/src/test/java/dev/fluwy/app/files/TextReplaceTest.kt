package dev.fluwy.app.files

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextReplaceTest {
    @Test fun `substitui literalmente uma ocorrência com aspas barras cifrão e metacaracteres`() {
        val source = "val s = \"\$1 (a+b)\"\n"
        val result = TextReplace.apply(source, "\"\$1 (a+b)\"", "\"texto novo\"")
        assertTrue(result.error.orEmpty(), result.success)
        assertEquals(1, result.replacements)
        assertEquals("exact", result.strategy)
        assertEquals("val s = \"texto novo\"\n", result.updatedText)
    }

    @Test fun `match exato mantém o padrão CRLF do arquivo na substituição multilinha`() {
        val result = TextReplace.apply("primeiro\r\nantigo\r\nfim\r\n", "antigo", "novo\nsegundo")
        assertTrue(result.error.orEmpty(), result.success)
        assertEquals("primeiro\r\nnovo\r\nsegundo\r\nfim\r\n", result.updatedText)
    }

    @Test fun `fallback aceita whitespace diferente e preserva CRLF e indentação`() {
        val source = "fun main() {\r\n\tval x=1\r\n\treturn x\r\n}\r\n"
        val old = "val x = 1\nreturn x"
        val replacement = "val total = 2\nreturn total"
        val result = TextReplace.apply(source, old, replacement)
        assertTrue(result.error.orEmpty(), result.success)
        assertEquals("whitespace", result.strategy)
        assertEquals("fun main() {\r\n\tval total = 2\r\n\treturn total\r\n}\r\n", result.updatedText)
    }

    @Test fun `correspondência ausente ou ambígua deixa conteúdo sem alteração`() {
        val missing = TextReplace.apply("alpha\n", "beta", "gamma")
        assertFalse(missing.success)
        assertEquals("trecho não encontrado. O arquivo não foi alterado; releia o trecho e tente novamente.", missing.error)

        val source = "x = 1\nx = 1\n"
        val ambiguous = TextReplace.apply(source, "x = 1", "x = 2")
        assertFalse(ambiguous.success)
        assertEquals(2, ambiguous.replacements)
        assertTrue(ambiguous.error.orEmpty().contains("nenhuma alteração"))
    }

    @Test fun `allow_multiple substitui todas as correspondências intencionalmente`() {
        val result = TextReplace.apply("foo foo foo", "foo", "bar", allowMultiple = true)
        assertTrue(result.success)
        assertEquals(3, result.replacements)
        assertEquals("bar bar bar", result.updatedText)
    }

    @Test fun `recusa busca vazia no-op e padrões acima do limite`() {
        assertFalse(TextReplace.apply("abc", "  ", "x").success)
        assertFalse(TextReplace.apply("abc", "a", "a").success)
        assertFalse(TextReplace.apply("abc", "x".repeat(TextReplace.MAX_MATCH_CHARS + 1), "y").success)
    }

    @Test fun `AtomicTextFile atualiza arquivo uma vez e não grava quando o texto não é encontrado`() {
        val dir = Files.createTempDirectory("fluwy-file-edit").toFile()
        try {
            val file = dir.resolve("sample.json").apply { writeText("{\"title\":\"antes\"}\n") }
            val edit = AtomicTextFile.replace(file, "\"antes\"", "\"depois\"")
            assertTrue(edit.error.orEmpty(), edit.success)
            assertEquals("{\"title\":\"depois\"}\n", file.readText())
            val failed = AtomicTextFile.replace(file, "não existe", "x")
            assertFalse(failed.success)
            assertEquals("{\"title\":\"depois\"}\n", file.readText())
            assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
        } finally {
            dir.deleteRecursively()
        }
    }
}
