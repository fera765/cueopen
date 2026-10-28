package dev.cueopen.app.uiconnector

import dev.cueopen.app.uiconnector.sniper.SniperPos
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SniperTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test fun `posicao serializa e desserializa`() {
        val p = SniperPos("Mint111", "DOGE2", 0.00001234, 123456789L, 0.02, 1_000L, 60.0, 35.0, 0.0, 0.0)
        val s = json.encodeToString(ListSerializer(SniperPos.serializer()), listOf(p))
        val back = json.decodeFromString(ListSerializer(SniperPos.serializer()), s)
        assertEquals(p, back[0])
        assertFalse(back[0].closed)
    }

    @Test fun `regra de saida take profit stop trailing`() {
        fun decide(pnl: Double, peak: Double, tp: Double, sl: Double, trail: Double): String? {
            val hitTp = pnl >= tp
            val hitSl = pnl <= -sl
            val hitTrail = trail > 0 && peak > 0 && pnl <= peak - trail && peak >= tp * 0.5
            return if (hitTp) "take profit" else if (hitSl) "stop loss" else if (hitTrail) "trailing stop" else null
        }
        assertEquals("take profit", decide(65.0, 65.0, 60.0, 35.0, 0.0))
        assertEquals("stop loss", decide(-40.0, 0.0, 60.0, 35.0, 0.0))
        assertEquals(null, decide(10.0, 10.0, 60.0, 35.0, 0.0))
        // trailing: subiu a 50% (>= tp*0.5=30), caiu 20pp para 30% com trail 15 → vende
        assertEquals("trailing stop", decide(30.0, 50.0, 60.0, 35.0, 15.0))
        assertEquals(null, decide(45.0, 50.0, 60.0, 35.0, 15.0))
    }
}
