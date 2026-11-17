package dev.cueopen.app.connectors

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LocalMcpCatalogTest {
    private val servers by lazy {
        val f = listOf(File("src/main/assets/mcp/catalog.json"), File("app/src/main/assets/mcp/catalog.json")).first { it.exists() }
        Json.parseToJsonElement(f.readText()).jsonObject["servers"]!!.jsonArray.map { it.jsonObject }
    }

    @Test fun catalogIsLargeAndRunnableInAlpine() {
        assertTrue("catálogo pequeno: ${servers.size}", servers.size >= 200)
        servers.forEach { s ->
            assertTrue(s["cmd"]!!.jsonPrimitive.content in setOf("npx", "uvx", "python", "python3", "node"))
        }
    }

    @Test fun idsAreUniqueAndUrlSafe() {
        val ids = servers.map { it["id"]!!.jsonPrimitive.content }
        assertEquals(ids.size, ids.toSet().size)
        ids.forEach { assertTrue(it, Regex("[a-z0-9_-]{1,48}").matches(it)) }
    }

    @Test fun everyPlaceholderHasAParam() {
        val ph = Regex("\\$\\{([A-Za-z0-9_]+)\\}")
        servers.forEach { s ->
            val keys = s["params"]!!.jsonArray.map { (it as JsonObject)["key"]!!.jsonPrimitive.content }.toSet()
            val texts = s["args"]!!.jsonArray.map { it.jsonPrimitive.content } + (s["env"] as JsonObject).values.map { it.jsonPrimitive.content }
            texts.flatMap { t -> ph.findAll(t).map { it.groupValues[1] }.toList() }.forEach { assertTrue("${s["id"]}: $it", it in keys) }
        }
    }

    @Test fun safeNameAndRefRoundTrip() {
        assertEquals("my-server", LocalMcpGateway.safeName("My Server!"))
        assertEquals("github", LocalMcpGateway.nameOf(LocalMcpGateway.ref("github")))
        assertEquals(null, LocalMcpGateway.nameOf("smithery:x/y"))
        assertEquals("http://127.0.0.1:8790/servers/github/mcp", LocalMcpGateway.mcpUrl("github"))
    }
}
