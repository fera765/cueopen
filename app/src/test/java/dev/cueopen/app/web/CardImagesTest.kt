package dev.cueopen.app.web

import dev.cueopen.app.workflow.WfEmail
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cards/notícias/produtos/e-mails sempre com foto; leitura de página com metadados. */
class CardImagesTest {
    private val base = "https://loja.com.br/p/123"
    private fun html(head: String, body: String = "") = "<html><head>$head</head><body>$body</body></html>"

    @Test fun c01_ogImage() { assertEquals("https://cdn.loja.com.br/foto.jpg", PageMeta.extract(html("<meta property=\"og:image\" content=\"https://cdn.loja.com.br/foto.jpg\">"), base).image) }
    @Test fun c02_ogRelativa() { assertEquals("https://loja.com.br/img/a.jpg", PageMeta.extract(html("<meta property=\"og:image\" content=\"/img/a.jpg\">"), base).image) }
    @Test fun c03_protocoloRelativo() { assertEquals("https://cdn.x.com/a.png", PageMeta.extract(html("<meta property=\"og:image\" content=\"//cdn.x.com/a.png\">"), base).image) }
    @Test fun c04_twitter() { assertEquals("https://t.co/i.jpg", PageMeta.extract(html("<meta name=\"twitter:image\" content=\"https://t.co/i.jpg\">"), base).image) }
    @Test fun c05_jsonLdProduto() {
        val m = PageMeta.extract(html("""<script type="application/ld+json">{"@context":"https://schema.org","@type":"Product","name":"Fone X","image":["https://cdn.l.com/fone.jpg"],"offers":{"@type":"Offer","price":"199.90","priceCurrency":"BRL"}}</script>"""), base)
        assertEquals("Fone X", m.title); assertEquals("https://cdn.l.com/fone.jpg", m.image); assertEquals("produto", m.kind); assertEquals("199.90", m.price); assertEquals("BRL", m.currency)
    }
    @Test fun c06_jsonLdNoticiaGraph() {
        val m = PageMeta.extract(html("""<script type="application/ld+json">{"@graph":[{"@type":"WebSite"},{"@type":"NewsArticle","headline":"Chuva em SP","image":{"@type":"ImageObject","url":"https://g1.com/c.jpg"},"datePublished":"2026-11-22T08:00:00-03:00"}]}</script>"""), base)
        assertEquals("Chuva em SP", m.title); assertEquals("https://g1.com/c.jpg", m.image); assertEquals("notícia", m.kind); assertTrue(m.published.startsWith("2026-11-22"))
    }
    @Test fun c07_semMetaPegaMaiorImg() {
        val m = PageMeta.extract(html("", "<img src=\"/logo.png\" width=\"40\"><article><img src=\"/fotos/grande.jpg\" width=\"800\" height=\"500\"></article>"), base)
        assertEquals("https://loja.com.br/fotos/grande.jpg", m.image)
    }
    @Test fun c08_ignoraLogoEPixel() {
        assertFalse(PageMeta.isGoodImage("https://x.com/logo.png")); assertFalse(PageMeta.isGoodImage("https://x.com/pixel.gif"))
        assertFalse(PageMeta.isGoodImage("https://x.com/a.svg")); assertFalse(PageMeta.isGoodImage("data:image/png;base64,AAA")); assertFalse(PageMeta.isGoodImage(""))
        assertTrue(PageMeta.isGoodImage("https://x.com/produtos/tenis-azul.webp?w=600"))
    }
    @Test fun c09_ogLogoCaiParaJsonLd() {
        val m = PageMeta.extract(html("<meta property=\"og:image\" content=\"https://x.com/logo.png\"><script type=\"application/ld+json\">{\"@type\":\"Product\",\"name\":\"A\",\"image\":\"https://x.com/p/a.jpg\"}</script>"), base)
        assertEquals("https://x.com/p/a.jpg", m.image)
    }
    @Test fun c10_metaBloco() {
        val b = PageMeta.Meta(image = "https://x.com/a.jpg", kind = "produto", price = "10", currency = "BRL").block()
        assertTrue(b.contains("Imagem: https://x.com/a.jpg")); assertTrue(b.contains("Preço: BRL 10"))
    }
    @Test fun c11_ogType() {
        assertEquals("notícia", PageMeta.extract(html("<meta property=\"og:type\" content=\"article\">"), base).kind)
        assertEquals("produto", PageMeta.extract(html("<meta property=\"og:type\" content=\"product\">"), base).kind)
    }

    private fun j(s: String) = Json.parseToJsonElement(s) as JsonObject
    @Test fun c12_cardSemFotoPrecisa() {
        assertTrue(CardRules.needsImage(j("{\"title\":\"A\",\"url\":\"https://a.com\"}")))
        assertFalse(CardRules.needsImage(j("{\"title\":\"A\",\"url\":\"https://a.com\",\"image\":\"https://a.com/f.jpg\"}")))
        assertFalse(CardRules.needsImage(j("{\"label\":\"x\",\"value\":\"y\"}")))
    }
    @Test fun c13_aplicaFotoERemoveSemFoto() {
        val root = j("{\"type\":\"cards\",\"items\":[{\"title\":\"A\",\"url\":\"https://a.com\"},{\"title\":\"B\",\"url\":\"https://b.com\"},{\"title\":\"C\",\"url\":\"https://c.com\",\"image\":\"https://c.com/f.jpg\"}]}")
        val out = CardRules.apply(root, mapOf("https://a.com" to "https://a.com/og.jpg")) as JsonObject
        val items = out["items"] as JsonArray
        assertEquals(listOf("A", "C"), items.map { ((it as JsonObject)["title"] as JsonPrimitive).content })
        assertEquals("https://a.com/og.jpg", ((items[0] as JsonObject)["image"] as JsonPrimitive).content)
    }
    @Test fun c14_fotoRuimTrocada() {
        val out = CardRules.apply(j("{\"cards\":[{\"title\":\"A\",\"url\":\"https://a.com\",\"image\":\"https://a.com/logo.png\"}]}"), mapOf("https://a.com" to "https://a.com/p.jpg")) as JsonObject
        assertEquals("https://a.com/p.jpg", (((out["cards"] as JsonArray)[0] as JsonObject)["image"] as JsonPrimitive).content)
    }
    @Test fun c15_naoMexeEmNaoCards() {
        val root = j("{\"blocks\":[{\"type\":\"kv\",\"items\":[{\"key\":\"a\",\"value\":\"b\"}]}]}")
        assertEquals(root, CardRules.apply(root, emptyMap()))
    }
    @Test fun c16_aninhado() {
        val out = CardRules.apply(j("{\"blocks\":[{\"type\":\"list\",\"items\":[{\"title\":\"N\",\"url\":\"https://n.com\"}]}]}"), emptyMap()).toString()
        assertFalse(out.contains("n.com"))
    }

    @Test fun c17_emailLinksSemFoto() {
        val md = "## Notícias\n- [Chuva](https://g1.com/a) — forte\n- ![x](https://i.com/b.jpg) [Sol](https://g1.com/b)\n| [t](https://t.com) |"
        assertEquals(listOf("https://g1.com/a"), WfEmail.linkedItemsWithoutImage(md))
    }
    @Test fun c18_emailComFoto() {
        val out = WfEmail.withImages("- [Chuva](https://g1.com/a) — forte", mapOf("https://g1.com/a" to "https://g1.com/a.jpg"))
        assertEquals("- ![Chuva](https://g1.com/a.jpg) [Chuva](https://g1.com/a) — forte", out)
        val html = WfEmail.toHtml(out)
        assertTrue(html.contains("<img src=\"https://g1.com/a.jpg\"")); assertTrue(html.contains("<a href=\"https://g1.com/a\""))
    }
    @Test fun c19_emailTituloComLink() {
        assertEquals("### ![P](https://p.com/i.jpg) [P](https://p.com)", WfEmail.withImages("### [P](https://p.com)", mapOf("https://p.com" to "https://p.com/i.jpg")))
    }
    @Test fun c20_emailSemImagemEncontradaFicaIgual() { assertEquals("- [A](https://a.com)", WfEmail.withImages("- [A](https://a.com)", emptyMap())) }
}
