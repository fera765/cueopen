package dev.cueopen.app.wallet

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger

class PolymarketInsightsClientTest {
    private val candidate = PolymarketCandidate(
        wallet = "0x1234567890123456789012345678901234567890",
        displayName = "public trader",
        monthlyRank = 2,
        monthlyBoardPnl = 100.0
    )

    @Test
    fun retriesTransientDnsFailureAndExplainsHowToRecover() {
        val attempts = AtomicInteger()
        val failingHttp = OkHttpClient.Builder()
            .addInterceptor {
                attempts.incrementAndGet()
                throw UnknownHostException("Unable to resolve host")
            }
            .build()
        val client = PolymarketInsightsClient(http = failingHttp)

        val failure = runCatching { runBlocking { client.analyzeLast90Days(candidatesPerBoard = 1) } }
            .exceptionOrNull()

        assertEquals(3, attempts.get())
        assertTrue(failure?.message.orEmpty().contains("DNS privado/VPN"))
    }

    @Test
    fun summarizesOnlyClosedPositionsWhoseEntryAndLastEventFallInWindow() {
        val positions = listOf(
            PolymarketClosedPosition("win", "Yes", 25.0, 100, 200),
            PolymarketClosedPosition("loss", "No", -5.0, 110, 190),
            PolymarketClosedPosition("opened earlier", "Yes", 100.0, 99, 190),
            PolymarketClosedPosition("updated later", "No", 100.0, 110, 201)
        )

        val result = summarizePolymarketCandidate(candidate, positions, 100, 200, pageLimitReached = false)

        assertEquals(2, result.closedPositionCount)
        assertEquals(20.0, result.realizedClosedPnlUsdc, 0.0001)
        assertEquals(1, result.positivePositionCount)
        assertEquals(1, result.negativePositionCount)
        assertEquals(50.0, result.positiveRatePercent!!, 0.0001)
        assertEquals(10.0, result.averageClosedPnlUsdc!!, 0.0001)
        assertEquals("leaderboard mensal #2", result.sourceLabel)
    }

    @Test
    fun missingPnlIsNotTreatedAsZeroForWinRateOrAverage() {
        val positions = listOf(
            PolymarketClosedPosition("known", "Yes", 12.0, 100, 110),
            PolymarketClosedPosition("unknown", "No", null, 100, 110)
        )

        val result = summarizePolymarketCandidate(candidate, positions, 100, 200, pageLimitReached = false)

        assertEquals(2, result.closedPositionCount)
        assertEquals(12.0, result.realizedClosedPnlUsdc, 0.0001)
        assertEquals(1, result.missingPnlCount)
        assertNull(result.positiveRatePercent)
        assertNull(result.averageClosedPnlUsdc)
    }

    @Test
    fun mergedCandidateKeepsMonthlyAndAllTimeOriginsSeparate() {
        val allTime = PolymarketCandidate(
            wallet = candidate.wallet,
            displayName = "public trader",
            allTimeRank = 7,
            allTimeBoardPnl = 950.0
        )

        val merged = candidate.merge(allTime)

        assertEquals("leaderboard mensal #2 + leaderboard acumulado #7", merged.sourceLabel())
        assertEquals(100.0, merged.monthlyBoardPnl!!, 0.0001)
        assertEquals(950.0, merged.allTimeBoardPnl!!, 0.0001)
    }
}
