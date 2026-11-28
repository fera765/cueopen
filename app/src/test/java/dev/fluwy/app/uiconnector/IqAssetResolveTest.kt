package dev.fluwy.app.uiconnector

import dev.fluwy.app.uiconnector.iqoption.IqProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IqAssetResolveTest {
    private fun a(id: Int, t: String, open: Boolean = true) = IqProtocol.Asset(id, t, "turbo", open)

    @Test fun inventedOtcSuffixStillResolves() {
        val list = listOf(a(1, "BTCUSD"), a(2, "EURUSD-OTC"), a(3, "EURUSD", open = false), a(4, "ADAUSD"))
        assertEquals(1, IqProtocol.resolveAsset(list, "BTCUSD-OTC")!!.id)   // modelo inventou -OTC
        assertEquals(1, IqProtocol.resolveAsset(list, "btcusd otc")!!.id)
        assertEquals(2, IqProtocol.resolveAsset(list, "EURUSD-OTC")!!.id)
        assertTrue(IqProtocol.suggestAssets(list, "BTCUSDT").contains("BTCUSD"))
    }
}
