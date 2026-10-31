package dev.cueopen.app.wallet

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class SolanaMarketDataClientTest {
    @Test
    fun rateLimitIsNotRetriedAndRetryAfterIsShown() {
        val attempts = AtomicInteger()
        val limitedHttp = OkHttpClient.Builder()
            .addInterceptor { chain ->
                attempts.incrementAndGet()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(429)
                    .message("Too Many Requests")
                    .header("Retry-After", "60")
                    .body("{}".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        val market = SolanaMarketDataClient(
            geckoBase = "https://api.geckoterminal.com/api/v2",
            client = limitedHttp
        )

        val failure = runCatching { runBlocking { market.discoverTokens(limit = 1) } }
            .exceptionOrNull()

        assertEquals(1, attempts.get())
        assertTrue(failure is IOException)
        assertTrue(failure?.message.orEmpty().contains("60 s conforme Retry-After"))
    }

    @Test
    fun publicKeylessQuotaAdviceReflectsCurrentGeckoLimit() {
        assertTrue(geckoRateLimitMessage(null).contains("10 chamadas por minuto"))
        assertTrue(geckoRateLimitMessage("25").contains("Aguarde 25 s"))
        val now = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
        assertTrue(geckoRateLimitMessage("Thu, 01 Jan 2026 00:00:30 GMT", now).contains("Aguarde 30 s conforme Retry-After"))
    }

    @Test
    fun dexScreenerSelectionRequiresMintToBeTheBaseToken() {
        val mint = "So11111111111111111111111111111111111111112"
        val pairs = Json.parseToJsonElement(
            """{"pairs":[
                {"chainId":"solana","pairAddress":"wrong-pool","baseToken":{"address":"OtherMint"},"quoteToken":{"address":"$mint"},"liquidity":{"usd":999999}},
                {"chainId":"solana","pairAddress":"right-pool","baseToken":{"address":"$mint"},"quoteToken":{"address":"OtherMint"},"liquidity":{"usd":1200}},
                {"chainId":"ethereum","pairAddress":"wrong-chain","baseToken":{"address":"$mint"},"liquidity":{"usd":9999999}}
            ]}"""
        ).jsonObject.getValue("pairs").jsonArray

        val selected = selectBestSolanaDexPair(pairs, mint)

        assertEquals("right-pool", selected?.get("pairAddress")?.jsonPrimitive?.content)
        assertNull(selectBestSolanaDexPair(pairs.take(1), mint))
    }
}
