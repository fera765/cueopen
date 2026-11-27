package dev.fluwy.app.uiconnector

import dev.fluwy.app.uiconnector.iqoption.IqOptionConnector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class IqConnectorSurfaceTest {
    private val connector = IqOptionConnector()

    @Test fun `schema contem somente consulta e trading manual`() {
        assertEquals(listOf("open_assets", "get_candles", "open_option", "open_forex", "close_position", "adjust_forex_stops", "positions", "get_trade_result"), connector.spec.tools.map { it.name })
        assertTrue(SpecValidator.validate(connector.spec).isEmpty())
        val names = connector.spec.settings.map { it.id }
        assertFalse(names.any { it.contains("strategy") || it.contains("mhi") || it.contains("masaniello") })
    }

    @Test fun `nomes legados nao sao mais aceitos pelo dispatcher`() = runBlocking {
        val settings = ConnectorSettings { null }
        for (legacy in listOf("scan_and_arm", "armed_list", "disarm", "candle_clock", "price_analysis", "backtest", "mm_status")) {
            assertEquals("Ferramenta desconhecida: $legacy", connector.callTool(legacy, emptyMap(), settings))
        }
    }
}
