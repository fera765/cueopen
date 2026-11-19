package dev.cueopen.app.agent

import dev.cueopen.app.connectors.HubApi
import dev.cueopen.app.connectors.HubConnectors
import dev.cueopen.app.triggers.TriggerLogic
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HubConnectorsTest {
    private val catalogJson = """
        {"generatedAt":"x","count":3,"pieces":[
          {"name":"@activepieces/piece-gmail","version":"0.19.0","displayName":"Gmail","description":"Email","logoUrl":"https://cdn/g.png","categories":[],
           "auth":["OAUTH2"],"authOptional":false,"actions":[["send_email","Send Email","Send an email",["receiver","subject"]],["reply_to_email","Reply","",[]]],"triggers":3},
          {"name":"@activepieces/piece-http","version":"0.13.3","displayName":"HTTP","description":"Requests","logoUrl":"","auth":[],"actions":[["send_request","Send HTTP request","",["method","url"]]],"triggers":0},
          {"name":"@activepieces/piece-google-sheets","version":"0.14.0","displayName":"Google Sheets","description":"Spreadsheets","logoUrl":"","auth":["OAUTH2"],"actions":[["insert_row","Insert Row","",[]]],"triggers":1},
          {"displayName":"sem nome"}
        ]}
    """.trimIndent()

    @Test fun catalogIsParsed() {
        val c = HubApi.parseCatalog(catalogJson)
        assertEquals(3, c.size) // entrada sem "name" é descartada
        val g = c.first()
        assertEquals("0.19.0", g.version)
        assertEquals(listOf("OAUTH2"), g.authTypes)
        assertEquals(2, g.actions.size)
        assertEquals(listOf("receiver", "subject"), g.actions[0].required)
        assertEquals(2, g.summary().actions)
        assertTrue(c[1].authTypes.isEmpty())
    }

    @Test fun catalogSearchRanksTitleFirst() {
        val c = HubApi.parseCatalog(catalogJson)
        assertEquals("Gmail", HubApi.search(c, "gmail").first().displayName)
        assertEquals("Google Sheets", HubApi.search(c, "sheets").first().displayName)
        assertEquals("HTTP", HubApi.search(c, "request").first().displayName) // acha pela ação
        assertTrue(HubApi.search(c, "xyzzy").isEmpty())
        assertEquals(3, HubApi.search(c, "").size)
    }

    @Test fun pieceNamesAreNormalized() {
        assertEquals("@activepieces/piece-gmail", HubApi.pieceName("Gmail"))
        assertEquals("@activepieces/piece-google-sheets", HubApi.pieceName("google sheets"))
        assertEquals("@activepieces/piece-slack", HubApi.pieceName("@activepieces/piece-slack"))
        assertEquals("@activepieces/piece-x", HubApi.pieceName("piece-x"))
    }

    @Test fun fieldsAcceptObjectOrJsonText() {
        assertEquals("a", (HubConnectors.fields(buildJsonObject { put("to", JsonPrimitive("a")) })!!["to"] as JsonPrimitive).content)
        assertEquals("b", (HubConnectors.fields(JsonPrimitive("{\"to\":\"b\"}"))!!["to"] as JsonPrimitive).content)
        assertTrue(HubConnectors.fields(null)!!.isEmpty())
        assertNull(HubConnectors.fields(JsonPrimitive("lixo")))
    }

    /** A saída de conector_executar continua legível pelos gatilhos (JSON depois do cabeçalho). */
    @Test fun runOutputKeepsTriggerFormat() {
        val ok = HubConnectors.formatRun("@activepieces/piece-gmail", buildJsonObject {
            put("ok", true); put("action", "gmail_search_mail"); put("durationMs", 120); put("version", "0.19.0")
            put("output", buildJsonObject { put("messages", buildJsonArray { add(buildJsonObject { put("id", "m1") }); add(buildJsonObject { put("id", "m2") }) }) })
        })
        assertTrue(ok.startsWith("✅"))
        val items = TriggerLogic.items(TriggerLogic.payload(ok)!!)
        assertEquals(2, items.size)
        val err = HubConnectors.formatRun("@activepieces/piece-gmail", buildJsonObject { put("ok", false); put("action", "x"); put("error", "HTTP 403: forbidden") })
        assertTrue(err.startsWith("ERRO"))
        assertTrue(err.contains("403"))
    }

    @Test fun inventoryHasNoSecretsAndFlagsBrokenAccounts() {
        val inv = HubConnectors.inventoryOf(listOf(
            HubConnectors.Conn("abc", "lord@x.com", "@activepieces/piece-gmail", "ACTIVE"),
            HubConnectors.Conn("def", "Equipe", "@activepieces/piece-slack", "ERROR"),
        ))!!
        assertTrue(inv.contains("lord@x.com"))
        assertTrue(inv.contains("gmail"))
        assertTrue(inv.contains("(reconectar)"))
        assertFalse(inv.contains("abc"))
        assertNull(HubConnectors.inventoryOf(emptyList()))
    }
}
