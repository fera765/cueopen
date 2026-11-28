package dev.fluwy.app.agent

import dev.fluwy.app.llm.ToolCallsShape
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolCallsShapeTest {
    @Test fun acceptsRealWorldShapes() {
        assertEquals(0, ToolCallsShape.normalize(null)!!.size)
        assertEquals(0, ToolCallsShape.normalize(JsonNull)!!.size)
        val one = """{"id":"a","type":"function","function":{"name":"x","arguments":"{}"}}"""
        assertEquals(1, ToolCallsShape.normalize(Json.parseToJsonElement(one))!!.size)
        assertEquals(1, ToolCallsShape.normalize(Json.parseToJsonElement("[$one]"))!!.size)
        assertEquals(1, ToolCallsShape.normalize(JsonPrimitive("[$one]"))!!.size)
        assertEquals(0, ToolCallsShape.normalize(JsonPrimitive(""))!!.size)
        assertNull(ToolCallsShape.normalize(JsonPrimitive("lixo")))
    }
}
