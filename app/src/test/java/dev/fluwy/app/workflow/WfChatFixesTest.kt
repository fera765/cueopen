package dev.fluwy.app.workflow

import org.junit.Assert.*
import org.junit.Test

class WfChatFixesTest {
    private val rss = """<?xml version="1.0" encoding="UTF-8"?><rss version="2.0" xmlns:media="http://search.yahoo.com/mrss/"><channel><title>Feed</title>""" +
        """<item><title>IA na educação - Agência Brasil</title><link>https://news.google.com/rss/articles/ABC?oc=5</link><pubDate>Sun, 22 Nov 2026 10:00:00 GMT</pubDate><description>&lt;a href="x"&gt;IA na educação&lt;/a&gt;</description><source url="https://agenciabrasil.ebc.com.br">Agência Brasil</source></item>""" +
        """<item><title><![CDATA[Chips & data centers]]></title><link>https://g1.com/a</link><media:content url="https://g1.com/a.jpg"/></item>""" +
        """<item><title></title><link>https://x.com</link></item></channel></rss>"""
    private val atom = """<feed xmlns="http://www.w3.org/2005/Atom"><entry><title>Post A</title><link href="https://b.com/a"/><updated>2026-11-22</updated><summary>Resumo A</summary></entry></feed>"""

    @Test fun detectaRss() = assertTrue(WfFeed.isFeed(rss))
    @Test fun detectaAtom() = assertTrue(WfFeed.isFeed(atom))
    @Test fun naoDetectaHtml() = assertFalse(WfFeed.isFeed("<html><body>oi</body></html>"))
    @Test fun parseRss() { val i = WfFeed.parse(rss); assertEquals(2, i.size); assertEquals("IA na educação", i[0].title); assertEquals("Agência Brasil", i[0].source) }
    @Test fun parseCdataEImagem() { val i = WfFeed.parse(rss)[1]; assertEquals("Chips & data centers", i.title); assertEquals("https://g1.com/a.jpg", i.image) }
    @Test fun resumoIgualTituloDescartado() = assertEquals("", WfFeed.parse(rss)[0].summary)
    @Test fun parseAtom() { val i = WfFeed.parse(atom).single(); assertEquals("https://b.com/a", i.link); assertEquals("Resumo A", i.summary) }
    @Test fun limite() = assertEquals(1, WfFeed.parse(rss, 1).size)
    @Test fun textoCompacto() { val t = WfFeed.toText(WfFeed.parse(rss)); assertTrue(t.startsWith("1. IA na educação — Agência Brasil")); assertTrue(t.length < 600) }
    @Test fun feedGrandeFicaPequeno() {
        val big = "<rss><channel>" + (1..100).joinToString("") { "<item><title>Notícia $it sobre tecnologia</title><link>https://site.com/$it</link><description>${"texto ".repeat(200)}</description></item>" } + "</channel></rss>"
        assertTrue(big.length > 100_000); assertTrue(WfFeed.toText(WfFeed.parse(big, 25)).length < 12_000)
    }
    @Test fun jsonItens() = assertEquals(2, WfFeed.toJson(WfFeed.parse(rss)).size)

    private val outs = mapOf("h1" to """{"exit":0,"output":""}""", "t1" to """{"time":"x"}""")
    @Test fun refVazia() = assertEquals(listOf("{{h1.output}}"), WfLogic.emptyRefs("LISTA: {{h1.output}} em {{t1.time}}", outs, emptyMap(), ""))
    @Test fun refComDefaultIgnorada() = assertTrue(WfLogic.emptyRefs("{{h1.output | default:nada}}", outs, emptyMap(), "").isEmpty())
    @Test fun refInexistente() = assertEquals(1, WfLogic.emptyRefs("{{zz.a}}", outs, emptyMap(), "").size)
    @Test fun semRefs() = assertTrue(WfLogic.emptyRefs("texto fixo", outs, emptyMap(), "").isEmpty())

    @Test fun iaReclamaListaVazia() = assertNotNull(WfQuality.aiOutputProblem("x", "A lista de notícias não foi incluída na mensagem — o campo após \"LISTA:\" está vazio."))
    @Test fun iaReclamaFeed() = assertNotNull(WfQuality.aiOutputProblem("x", "O feed XML não foi incluído. Cole o conteúdo do XML."))
    @Test fun iaNaoRecebi() = assertNotNull(WfQuality.aiOutputProblem("x", "Não recebi a lista de notícias — por favor envie os títulos"))
    @Test fun iaOk() = assertNull(WfQuality.aiOutputProblem("x", "# Briefing de hoje\n- **IA na educação** — resumo. https://a.com"))
    @Test fun agenteNarrando() = assertTrue(WfQuality.agentUnfinished("O artigo é patrocinado — vou descartá-lo. Para a 5ª vaga, vou confirmar a data da matéria no feed da Exame:"))
    @Test fun agenteVouBuscar() = assertTrue(WfQuality.agentUnfinished("O endpoint está rejeitando (400). Vou buscar os links diretos das fontes por um mecanismo de busca, usando os títulos exatos."))
    @Test fun agenteEntregou() = assertFalse(WfQuality.agentUnfinished("# Briefing de hoje\nDomingo — IA na educação.\n- **Uso da IA** exige capacitação. https://a.com\n- **Chips** avançam. https://b.com"))
    @Test fun agenteVazio() = assertTrue(WfQuality.agentUnfinished("  "))

}
