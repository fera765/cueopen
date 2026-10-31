package dev.cueopen.app.uiconnector.custom

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectorDefinitionEditTest {
    private val sessionId = "session-edit-123"
    private val original = """
        id: demo
        name: Demo
        description: Painel público
        baseUrl: https://example.com
        headers:
          Authorization: Bearer private-header-token-123
        login:
          fields:
            - id: password
              label: Senha
              type: password
        capabilities:
          - network
        setup:
          command: echo never-run
        settings:
          - id: theme
            label: Tema
            type: text
            default: dark
          - id: api_token
            label: Token API
            type: text
            default: private-setting-token
        tools:
          - name: search
            description: Busca pública
            headers:
              X-Api-Key: private-nested-header
        misc:
          opaque: abcdefghijklmnopqrstuvwxyz1234567890ABCDEFGHIJ
    """.trimIndent()

    @Test fun `sanitização omite credenciais e restore devolve os valores originais`() {
        val sanitized = ConnectorDefinitionEdit.sanitize(original, sessionId)
        assertFalse(sanitized.contains("private-header-token-123"))
        assertFalse(sanitized.contains("private-setting-token"))
        assertFalse(sanitized.contains("private-nested-header"))
        assertFalse(sanitized.contains("echo never-run"))
        assertTrue(sanitized.contains("__CUE_LOCK_"))

        val tree = ConnectorYamlCodec.parse(sanitized) as JsonObject
        val proposal = JsonObject(tree.toMutableMap().apply { put("description", JsonPrimitive("Novo painel")) })
        val restored = ConnectorDefinitionEdit.restore(original, ConnectorYamlCodec.stringify(proposal), sessionId)
        assertTrue(restored.contains("private-header-token-123"))
        assertTrue(restored.contains("private-setting-token"))
        assertTrue(restored.contains("private-nested-header"))
        assertTrue(restored.contains("echo never-run"))
        assertEquals("Novo painel", (ConnectorYamlCodec.parse(restored) as JsonObject)["description"]?.let { (it as JsonPrimitive).content })
    }

    @Test fun `proposta falha se remover marcador ou adicionar campo de credencial`() {
        val sanitized = ConnectorDefinitionEdit.sanitize(original, sessionId)
        val tree = ConnectorYamlCodec.parse(sanitized) as JsonObject
        val withoutLogin = JsonObject(tree.toMutableMap().apply { remove("login") })
        val removed = runCatching {
            ConnectorDefinitionEdit.restore(original, ConnectorYamlCodec.stringify(withoutLogin), sessionId)
        }
        assertTrue(removed.isFailure)

        val addedSecret = JsonObject(tree.toMutableMap().apply { put("api_key", JsonPrimitive("invented-value")) })
        val added = runCatching {
            ConnectorDefinitionEdit.restore(original, ConnectorYamlCodec.stringify(addedSecret), sessionId)
        }
        assertTrue(added.isFailure)
    }

    @Test fun `definição pública sem marcadores pode receber alteração segura`() {
        val publicYaml = "id: public\nname: Public\ndescription: Before\n"
        val sanitized = ConnectorDefinitionEdit.sanitize(publicYaml, sessionId)
        val tree = ConnectorYamlCodec.parse(sanitized) as JsonObject
        val proposal = JsonObject(tree.toMutableMap().apply { put("description", JsonPrimitive("After")) })
        val restored = ConnectorDefinitionEdit.restore(publicYaml, ConnectorYamlCodec.stringify(proposal), sessionId)
        assertEquals("After", (ConnectorYamlCodec.parse(restored) as JsonObject)["description"]?.let { (it as JsonPrimitive).content })
    }
}
