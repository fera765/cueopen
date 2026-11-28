package dev.fluwy.app.prompts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class PromptTemplateTest {
    @Test fun replacesVariables() =
        assertEquals("Olá Ana!", PromptTemplate.render("Olá {{nome}}!", mapOf("nome" to "Ana")))

    @Test fun keepsOrDropsSections() {
        val t = "a\n{{#on}}\nb\n{{/on}}\n{{^on}}\nc\n{{/on}}\nd\n"
        assertEquals("a\nb\nd\n", PromptTemplate.render(t, flags = mapOf("on" to true)))
        assertEquals("a\nc\nd\n", PromptTemplate.render(t, flags = mapOf("on" to false)))
    }

    @Test fun dropsLineThatBecameEmptyButKeepsBlankLines() =
        assertEquals("a\n\nb\n", PromptTemplate.render("a\n{{x}}\n\nb\n", mapOf("x" to "")))

    @Test fun literalBracesSurvive() =
        assertEquals("use {{var}}", PromptTemplate.render("use {{lb}}var}}", mapOf("lb" to "{{")))

    @Test fun everyCatalogIdIsUniqueAndResolvable() {
        val ids = PromptCatalog.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        ids.forEach { assertNotNull(Prompts.text(it)) }
    }
}
