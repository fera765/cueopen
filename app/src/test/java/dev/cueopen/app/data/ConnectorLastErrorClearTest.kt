package dev.cueopen.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ConnectorLastErrorClearTest {
    @Test fun `limpa apenas lastError do conector selecionado`() {
        val selected = Connector(
            id = "selected",
            name = "Selected",
            url = "https://selected.example",
            enabled = false,
            accessToken = "token-preservado",
            lastError = "falha antiga",
        )
        val unrelated = Connector(
            id = "unrelated",
            name = "Unrelated",
            url = "https://unrelated.example",
            lastError = "erro não relacionado",
        )
        val original = AppState(connectors = listOf(selected, unrelated))

        val result = original.withConnectorLastErrorCleared("selected")

        assertEquals(selected.copy(lastError = null), result.connectors[0])
        assertEquals(unrelated, result.connectors[1])
        assertEquals(original.settings, result.settings)
        assertEquals(original.agents, result.agents)
    }

    @Test fun `conector sem lastError ou inexistente deixa o estado intocado`() {
        val original = AppState(connectors = listOf(
            Connector(id = "one", name = "One", url = "https://one.example"),
        ))

        assertSame(original, original.withConnectorLastErrorCleared("one"))
        assertSame(original, original.withConnectorLastErrorCleared("missing"))
    }
}
