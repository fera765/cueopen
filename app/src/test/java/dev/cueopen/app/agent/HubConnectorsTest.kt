package dev.cueopen.app.agent

import dev.cueopen.app.connectors.HubApi
import dev.cueopen.app.connectors.HubConnectors
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HubConnectorsTest {
    @Test fun parsesListConnectionsOutput() {
        val out = "✅ Listed 2 connection(s):\n- externalId: abc123 | displayName: \"Gmail lord@x.com\" | piece: @activepieces/piece-gmail | status: ACTIVE | scope: PROJECT\n" +
            "- externalId: n0t | displayName: \"Notion\" | piece: @activepieces/piece-notion | status: ERROR | scope: PROJECT"
        val c = HubConnectors.parseConnections(out)
        assertEquals(2, c.size)
        assertEquals("abc123", c[0].externalId)
        assertEquals("Gmail lord@x.com", c[0].displayName)
        assertEquals("@activepieces/piece-notion", c[1].pieceName)
        assertEquals("ERROR", c[1].status)
        assertEquals("Google sheets", HubConnectors.appName("@activepieces/piece-google-sheets"))
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

    @Test fun apiErrorsAreReadable() {
        assertEquals("E-mail ou senha do hub incorretos.", HubApi.errorText(401, "{\"code\":\"INVALID_CREDENTIALS\"}"))
        assertTrue(HubApi.errorText(400, "{\"code\":\"INVALID_APP_CONNECTION\",\"params\":{\"error\":\"bad key\"}}").contains("bad key"))
    }
}
