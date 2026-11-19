package dev.cueopen.app.agent

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
