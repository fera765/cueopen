package dev.fluwy.app.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultCardsParserTest {
    @Test fun keepsStructuredFieldsAndDropsUnsafe() {
        val items = Json.parseToJsonElement("""[
            {"title":"Adaptador <b>Universal</b>","price":"R$ 99,99","oldPrice":"R$ 199,99","image":"javascript:alert(1)","url":"https://ml.com/p/1","badge":"Top rated"},
            {"subtitle":"sem título"},
            {"title":"Kit 2","image":"https://img/x.jpg"}]""")
        val c = ResultCardsParser.parse("product", "Adaptadores", items).getOrThrow()
        assertEquals(2, c.items.size)
        assertEquals("Adaptador Universal", c.items[0].title)
        assertEquals("", c.items[0].image)
        assertEquals("https://ml.com/p/1", c.items[0].url)
        assertEquals(-1, c.selected)
    }

    @Test fun acceptsJsonTextAndUnknownKind() {
        val c = ResultCardsParser.parse("blog", "", JsonPrimitive("""[{"title":"Manchete","date":"18/11"}]""")).getOrThrow()
        assertEquals("search", c.kind)
        assertTrue(ResultCardsParser.parse("news", "", JsonPrimitive("[]")).isFailure)
    }
}

class CardImagesTest {
    @org.junit.Test fun fillsMissingImagesFromPage() = kotlinx.coroutines.runBlocking {
        val cards = ResultCardsParser.parse("product", "", kotlinx.serialization.json.Json.parseToJsonElement(
            """[{"title":"A","url":"https://loja/a"},{"title":"B","image":"https://img/b.jpg","url":"https://loja/b"},{"title":"C"}]""")).getOrThrow()
        val out = CardImages.fill(cards) { url -> if (url.endsWith("/a")) "https://img/a.jpg" else null }
        org.junit.Assert.assertEquals(listOf("https://img/a.jpg", "https://img/b.jpg", ""), out.items.map { it.image })
    }

    @org.junit.Test fun readsOgImage() {
        val html = """<html><head><meta property="og:image" content="/fotos/p1.jpg?x=1&amp;y=2"></head></html>"""
        org.junit.Assert.assertEquals("https://loja.com/fotos/p1.jpg?x=1&y=2", dev.fluwy.app.web.WebTools.imageFromHtml(html, "https://loja.com/produto/1"))
        org.junit.Assert.assertEquals("https://cdn/x.png", dev.fluwy.app.web.WebTools.imageFromHtml("""<meta content="https://cdn/x.png" name="twitter:image">""", "https://a.b"))
    }
}
