package dev.cueopen.app.wallet

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Real market data for day trading. No API key, no synthetic data.
 *
 * - Spot price (USD): Jupiter Price API v3 (free public tier). If a mint has no price entry,
 *   it is derived from a real Jupiter quote against USDC (1 token -> USDC out), never faked.
 * - Candles (OHLCV): GeckoTerminal public API — real on-chain pool candles for Solana. No key.
 * - Depth/liquidity: Solana trading is AMM-based (Jupiter/Raydium/Orca), so there is no central
 *   limit order book for most tokens. Instead of fabricating a book, we expose the REAL price-impact
 *   curve measured by quoting the same pair at increasing sizes against the live aggregator.
 *
 * This client is read-only: it never touches the wallet secret and never signs anything.
 */
class SolanaMarketDataClient(
    private val swapClient: SolanaSwapClient = SolanaSwapClient(),
    private val priceBase: String = JUP_PRICE_BASE,
    private val geckoBase: String = GECKO_BASE,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS)
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** USD spot prices for the given mints. Missing entries are derived from a real quote vs USDC. */
    suspend fun pricesUsd(mints: List<String>): Map<String, Double> = withContext(Dispatchers.IO) {
        val ids = mints.filter { SolanaAddressValidator.isValidAddress(it) }.distinct()
        if (ids.isEmpty()) return@withContext emptyMap()
        val result = LinkedHashMap<String, Double>()
        runCatching {
            val url = priceBase.trimEnd('/') + "/price/v3?ids=" + ids.joinToString(",")
            val req = Request.Builder().url(url).header("Accept", "application/json").get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("Price HTTP ${resp.code}")
                val body = resp.body?.string().orEmpty()
                if (body.length > MAX_RESPONSE_CHARS) throw IOException("Resposta de preço excede o limite")
                val root = json.parseToJsonElement(body).jsonObject
                // v3 shape: { "<mint>": { "usdPrice": 1.23, ... } }  (also tolerate a "data" wrapper)
                val table = (root["data"]?.jsonObject ?: root)
                for (mint in ids) {
                    val entry = table[mint]?.jsonObject ?: continue
                    val p = entry["usdPrice"]?.jsonPrimitive?.doubleOrNull
                        ?: entry["price"]?.jsonPrimitive?.doubleOrNull
                    if (p != null && p > 0.0) result[mint] = p
                }
            }
        }
        // Fallback for any mint the price API did not return: derive from a real USDC quote.
        for (mint in ids) {
            if (result.containsKey(mint)) continue
            runCatching { deriveUsdPriceViaQuote(mint) }.getOrNull()?.let { if (it > 0.0) result[mint] = it }
        }
        result
    }

    suspend fun priceUsd(mint: String): Double? = pricesUsd(listOf(mint))[mint]

    /**
     * Derives a USD price by quoting 1 whole token -> USDC through the live aggregator.
     * Uses real on-chain decimals so the unit amount is correct; returns USDC-out scaled to 6 dp.
     */
    private suspend fun deriveUsdPriceViaQuote(mint: String): Double? {
        if (mint == SolanaSwapClient.USDC_MINT || mint == SolanaSwapClient.USDT_MINT) return 1.0
        val decimals = runCatching {
            SolanaRpcClient(endpoint = SolanaNetwork.MAINNET.rpcEndpoint, network = SolanaNetwork.MAINNET)
                .getTokenDecimals(mint)
        }.getOrDefault(if (mint == SolanaSwapClient.SOL_MINT) 9 else 6)
        val oneToken = Math.pow(10.0, decimals.toDouble()).toLong().coerceAtLeast(1L)
        val quote = runCatching { swapClient.quote(mint, SolanaSwapClient.USDC_MINT, oneToken, 100) }.getOrNull()
            ?: return null
        return quote.outAmountRaw / 1_000_000.0 // USDC has 6 decimals
    }

    /**
     * Real OHLCV candles from GeckoTerminal for the token's deepest Solana pool.
     * [timeframe] in {"day","hour","minute"}; [aggregate] groups base units (e.g. hour+4 = 4h).
     */
    suspend fun candles(
        mint: String,
        timeframe: String = "hour",
        aggregate: Int = 1,
        limit: Int = 100
    ): List<Candle> = withContext(Dispatchers.IO) {
        require(SolanaAddressValidator.isValidAddress(mint)) { "Mint inválido" }
        require(timeframe in setOf("day", "hour", "minute")) { "timeframe deve ser day, hour ou minute" }
        val safeLimit = limit.coerceIn(1, 1000)
        val pool = topPoolFor(mint) ?: throw IOException("Nenhum pool de liquidez encontrado para este token na Solana")
        val url = geckoBase.trimEnd('/') +
            "/networks/solana/pools/$pool/ohlcv/$timeframe?aggregate=${aggregate.coerceAtLeast(1)}&limit=$safeLimit&currency=usd"
        val req = Request.Builder().url(url).header("Accept", "application/json").get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("OHLCV HTTP ${resp.code}")
            val body = resp.body?.string().orEmpty()
            if (body.length > MAX_RESPONSE_CHARS) throw IOException("Resposta OHLCV excede o limite")
            val list = json.parseToJsonElement(body).jsonObject["data"]?.jsonObject
                ?.get("attributes")?.jsonObject?.get("ohlcv_list")?.jsonArray
                ?: throw IOException("OHLCV em formato inválido")
            list.mapNotNull { row ->
                val arr = row.jsonArray
                if (arr.size < 6) return@mapNotNull null
                Candle(
                    tsEpochSeconds = arr[0].jsonPrimitive.doubleOrNull?.toLong() ?: return@mapNotNull null,
                    open = arr[1].jsonPrimitive.doubleOrNull ?: return@mapNotNull null,
                    high = arr[2].jsonPrimitive.doubleOrNull ?: return@mapNotNull null,
                    low = arr[3].jsonPrimitive.doubleOrNull ?: return@mapNotNull null,
                    close = arr[4].jsonPrimitive.doubleOrNull ?: return@mapNotNull null,
                    volumeUsd = arr[5].jsonPrimitive.doubleOrNull ?: 0.0
                )
            }.sortedBy { it.tsEpochSeconds }
        }
    }

    /** Address of the deepest (reserve-ranked) Solana pool for a token, without the "solana_" prefix. */
    private suspend fun topPoolFor(mint: String): String? {
        val url = geckoBase.trimEnd('/') + "/networks/solana/tokens/$mint/pools?page=1"
        val req = Request.Builder().url(url).header("Accept", "application/json").get().build()
        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            val body = resp.body?.string().orEmpty()
            if (body.length > MAX_RESPONSE_CHARS) return@use null
            val data = runCatching { json.parseToJsonElement(body).jsonObject["data"]?.jsonArray }.getOrNull()
                ?: return@use null
            val best = data.maxByOrNull {
                it.jsonObject["attributes"]?.jsonObject?.get("reserve_in_usd")
                    ?.jsonPrimitive?.doubleOrNull ?: 0.0
            } ?: data.firstOrNull() ?: return@use null
            best.jsonObject["id"]?.jsonPrimitive?.content?.removePrefix("solana_")
        }
    }

    /**
     * Real price-impact / depth curve for a pair, measured by quoting increasing sizes against the
     * live Jupiter aggregator. This is the honest AMM equivalent of order-book depth.
     */
    suspend fun depthCurve(
        inputMint: String,
        outputMint: String,
        probeAmountsRaw: List<Long>,
        slippageBps: Int = 100
    ): List<DepthPoint> = withContext(Dispatchers.IO) {
        probeAmountsRaw.filter { it > 0L }.mapNotNull { amount ->
            val q = runCatching { swapClient.quote(inputMint, outputMint, amount, slippageBps) }.getOrNull()
                ?: return@mapNotNull null
            DepthPoint(amount, q.outAmountRaw, q.priceImpactPct, q.routeLabels)
        }
    }

    companion object {
        const val JUP_PRICE_BASE = "https://lite-api.jup.ag"
        const val GECKO_BASE = "https://api.geckoterminal.com/api/v2"
        private const val MAX_RESPONSE_CHARS = 2 * 1024 * 1024
    }
}

data class Candle(
    val tsEpochSeconds: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volumeUsd: Double
)

data class DepthPoint(
    val inAmountRaw: Long,
    val outAmountRaw: Long,
    val priceImpactPct: String,
    val routeLabels: List<String>
)
