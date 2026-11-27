package dev.fluwy.app.agent

import dev.fluwy.app.agent.SkillMatcher.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillMatcherTest {
    private val globo = Candidate("Buscar Corinthians Globo", "Acessa a página do Corinthians no ge.globo e resume as notícias do dia")
    private val slides = Candidate("Slides", "Cria apresentações em HTML")
    private val research = Candidate("Wide Research", "Pesquisa ampla em muitas fontes")
    private val all = listOf(globo, slides, research)

    @Test fun `mention works with spaces without spaces and ignoring case and accents`() {
        assertTrue(SkillMatcher.mentioned("@Buscar Corinthians Globo init", globo.name))
        assertTrue(SkillMatcher.mentioned("roda @buscarcorinthiansglobo agora", globo.name))
        assertTrue(SkillMatcher.mentioned("@buscar-corinthians-globo", globo.name))
        assertTrue(SkillMatcher.mentioned("@Apresentação rápida", "apresentacao rapida"))
        assertFalse(SkillMatcher.mentioned("buscar corinthians globo", globo.name))
        assertFalse(SkillMatcher.mentioned("@outra", globo.name))
    }

    @Test fun `a request that describes the skill activates it without a mention`() {
        assertEquals(listOf(globo), SkillMatcher.select("buscar Corinthians", all))
        assertEquals(listOf(globo), SkillMatcher.select("Use a skill e busque Corinthians", all))
        assertEquals(listOf(globo), SkillMatcher.select("busca as notícias do corinthians na globo", all))
    }

    @Test fun `unrelated requests activate nothing and one common word is not enough`() {
        assertEquals(emptyList<Candidate>(), SkillMatcher.select("qual a previsão do tempo amanhã?", all))
        assertEquals(emptyList<Candidate>(), SkillMatcher.select("o Corinthians joga hoje?", all))
        assertEquals(emptyList<Candidate>(), SkillMatcher.select("monte uns slides sobre vendas", all))
    }

    @Test fun `mentions are all honoured and an ambiguous implied match is left to the agent`() {
        assertEquals(listOf(globo, slides), SkillMatcher.select("@Slides e depois @Buscar Corinthians Globo", all))
        val a = Candidate("Relatório vendas mensal"); val b = Candidate("Relatório vendas semanal")
        assertEquals(emptyList<Candidate>(), SkillMatcher.select("gere o relatório de vendas", listOf(a, b)))
        assertEquals(listOf(b), SkillMatcher.select("gere o relatório de vendas semanal", listOf(a, b)))
    }

    @Test fun `score ranks the skill named in the request first`() {
        val ranked = all.sortedByDescending { SkillMatcher.score("pesquisa ampla sobre o corinthians na globo", it) }
        assertEquals(globo, ranked.first())
    }
}
