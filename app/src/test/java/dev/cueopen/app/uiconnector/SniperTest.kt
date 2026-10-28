package dev.cueopen.app.uiconnector

import dev.cueopen.app.uiconnector.sniper.SniperPos
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.max

class SniperTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test fun `posicao serializa com trailing e tpArmed`() {
        val p = SniperPos("Mint111", "DOGE2", 0.00001234, 123456789L, 0.02, 1000L, 60.0, 35.0, 0.0, peakPct = 120.0, tpArmed = true)
        val back = json.decodeFromString(ListSerializer(SniperPos.serializer()), json.encodeToString(ListSerializer(SniperPos.serializer()), listOf(p)))
        assertEquals(p, back[0])
    }

    /** Réplica da decisão do conector: tudo em % de lucro REAL (SOL). */
    private fun stop(tpArmed: Boolean, slPct: Double, tpPct: Double, peak: Double, trailing: Double, floor: Double): Double =
        if (!tpArmed) -slPct else max(floor, peak - (if (trailing > 0) trailing else tpPct / 2))

    private fun decide(pnl: Double, tpArmed: Boolean, slPct: Double, tpPct: Double, peak: Double, trailing: Double, floor: Double): String? {
        val s = stop(tpArmed, slPct, tpPct, peak, trailing, floor)
        return when {
            !tpArmed && pnl <= -slPct -> "stop loss"
            tpArmed && pnl <= s -> if (s > 0) "trailing" else "stop"
            else -> null
        }
    }

    @Test fun `antes do TP so o stop fixo vale`() {
        assertEquals("stop loss", decide(-35.0, false, 35.0, 60.0, 0.0, 0.0, 1.0))
        assertNull(decide(-10.0, false, 35.0, 60.0, 0.0, 0.0, 1.0))
        // a 50% ainda não armou TP (60) → não vende, deixa correr
        assertNull(decide(50.0, false, 35.0, 60.0, 50.0, 0.0, 1.0))
    }

    @Test fun `depois do TP sobe o stop para o zero-a-zero e segue o topo`() {
        // TP armado, topo 1000%, trailing 0 → usa tpPct/2 = 30 → stop em 970%; a 1000% segura, a 965% vende no lucro
        assertNull(decide(1000.0, true, 35.0, 60.0, 1000.0, 0.0, 1.0))
        assertEquals("trailing", decide(965.0, true, 35.0, 60.0, 1000.0, 0.0, 1.0))
        // topo baixo (70%), caiu para 5% → stop no piso 1% ainda não; a 1% exatamente, ainda positivo → não perde
        assertEquals("trailing", decide(1.0, true, 35.0, 60.0, 65.0, 0.0, 1.0)) // peak-30=35>1 → stop=35, pnl1<=35
        // garante que nunca fecha abaixo do piso: stop >= floor
        org.junit.Assert.assertEquals(1.0, stop(true, 35.0, 60.0, 10.0, 0.0, 1.0), 1e-9)
    }
}
