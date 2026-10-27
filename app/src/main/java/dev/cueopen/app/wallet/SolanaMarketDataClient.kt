package dev.cueopen.app.wallet

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
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

    /**
     * GET resiliente ao GeckoTerminal: cache curto (evita repetir chamadas), throttle global
     * (respeita o limite de ~30 req/min keyless) e retry com backoff em HTTP 429. Se o usuário
     * configurar uma chave Demo gratuita da CoinGecko, usa o endpoint onchain com quota dedicada.
     * [pathAndQuery] ex.: "/networks/solana/new_pools?page=1".
     */
    private suspend fun geckoGet(pathAndQuery: String): String = withContext(Dispatchers.IO) {
        cacheGet(pathAndQuery)?.let { return@withContext it }
        val key = MarketApiConfig.geckoApiKey?.takeIf { it.isNotBlank() }
        val base = if (key != null) COINGECKO_ONCHAIN_BASE else geckoBase.trimEnd('/')
        val url = base + pathAndQuery
        var attempt = 0
        var lastErr = "limite de requisições (HTTP 429)"
        while (attempt < 4) {
            geckoThrottle(key != null)
            val reqB = Request.Builder().url(url).header("Accept", "application/json").get()
            if (key != null) reqB.header("x-cg-demo-api-key", key)
            client.newCall(reqB.build()).execute().use { resp ->
                when {
                    resp.isSuccessful -> {
                        val body = resp.body?.string().orEmpty()
                        if (body.length > MAX_RESPONSE_CHARS) throw IOException("Resposta excede o limite")
                        cachePut(pathAndQuery, body)
                        return@withContext body
                    }
                    resp.code == 429 -> {
                        lastErr = "limite de requisições (HTTP 429)"
                        val retryMs = (resp.header("Retry-After")?.toLongOrNull()?.times(1000)
                            ?: (1500L * (attempt + 1))).coerceAtMost(6000L)
                        delay(retryMs)
                    }
                    else -> throw IOException("HTTP ${resp.code}")
                }
            }
            attempt++
        }
        throw IOException(lastErr)
    }

    private suspend fun geckoThrottle(hasKey: Boolean) {
        val interval = if (hasKey) 1300L else MIN_INTERVAL_MS
        geckoMutex.withLock {
            val wait = interval - (System.currentTimeMillis() - lastGeckoCallMs)
            if (wait > 0) delay(wait)
            lastGeckoCallMs = System.currentTimeMillis()
        }
    }

    /**
     * DexScreener (sem API key, limite alto ~300/min): dados de token na Solana. Fonte PRIMÁRIA
     * de token_info/preço para tirar carga do GeckoTerminal e evitar 429. Escolhe o par de maior
     * liquidez. Resiliente: devolve null em falha (o chamador tenta outra fonte).
     */
    private suspend fun dexScreenerToken(mint: String): TokenMarket? = withContext(Dispatchers.IO) {
        val cacheKey = "dex:$mint"
        val body = cacheGet(cacheKey) ?: runCatching {
            val req = Request.Builder().url("$DEXSCREENER_BASE/latest/dex/tokens/$mint")
                .header("Accept", "application/json").get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val b = resp.body?.string().orEmpty()
                if (b.isNotBlank() && b.length <= MAX_RESPONSE_CHARS) { cachePut(cacheKey, b); b } else null
            }
        }.getOrNull() ?: return@withContext null
        val pairs = runCatching { json.parseToJsonElement(body).jsonObject["pairs"]?.jsonArray }.getOrNull() ?: return@withContext null
        val solPairs = pairs.filter { (it.jsonObject["chainId"]?.jsonPrimitive?.contentOrNull) == "solana" }
        val best = solPairs.maxByOrNull {
            (it.jsonObject["liquidity"] as? kotlinx.serialization.json.JsonObject)?.get("usd")?.jsonPrimitive?.doubleOrNull ?: 0.0
        } ?: return@withContext null
        val o = best.jsonObject
        val baseTok = o["baseToken"]?.jsonObject
        TokenMarket(
            mint = mint,
            symbol = baseTok?.get("symbol")?.jsonPrimitive?.contentOrNull ?: mint.take(6),
            name = baseTok?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty(),
            priceUsd = o["priceUsd"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull(),
            liquidityUsd = (o["liquidity"] as? kotlinx.serialization.json.JsonObject)?.get("usd")?.jsonPrimitive?.doubleOrNull,
            volume24hUsd = (o["volume"] as? kotlinx.serialization.json.JsonObject)?.get("h24")?.jsonPrimitive?.doubleOrNull,
            priceChange24hPct = (o["priceChange"] as? kotlinx.serialization.json.JsonObject)?.get("h24")?.jsonPrimitive?.doubleOrNull,
            poolCreatedAt = o["pairCreatedAt"]?.jsonPrimitive?.contentOrNull,
            poolAddress = o["pairAddress"]?.jsonPrimitive?.contentOrNull,
            marketCapUsd = o["marketCap"]?.jsonPrimitive?.doubleOrNull ?: o["fdv"]?.jsonPrimitive?.doubleOrNull
        )
    }

    private fun cacheGet(k: String): String? {
        val e = cache[k] ?: return null
        return if (System.currentTimeMillis() - e.first < CACHE_TTL_MS) e.second else { cache.remove(k); null }
    }
    private fun cachePut(k: String, v: String) {
        if (cache.size > 200) cache.clear()
        cache[k] = System.currentTimeMillis() to v
    }

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
        val body = geckoGet("/networks/solana/pools/$pool/ohlcv/$timeframe?aggregate=${aggregate.coerceAtLeast(1)}&limit=$safeLimit&currency=usd")
        run {
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
        val body = runCatching { geckoGet("/networks/solana/tokens/$mint/pools?page=1") }.getOrNull() ?: return null
        val data = runCatching { json.parseToJsonElement(body).jsonObject["data"]?.jsonArray }.getOrNull()
            ?: return null
        val best = data.maxByOrNull {
            it.jsonObject["attributes"]?.jsonObject?.get("reserve_in_usd")
                ?.jsonPrimitive?.doubleOrNull ?: 0.0
        } ?: data.firstOrNull() ?: return null
        return best.jsonObject["id"]?.jsonPrimitive?.content?.removePrefix("solana_")
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

    /**
     * Descobre tokens de TODO o ecossistema Solana via GeckoTerminal (keyless), ordenados.
     * [sort] = "new" (pools mais novos → tokens recém-lançados, do mais novo ao mais velho),
     * "trending" (em alta agora). Traz preço, liquidez (reserva do pool) e volume 24h REAIS,
     * para o agente achar oportunidades e validar se dá para comprar/vender com liquidez.
     */
    suspend fun discoverTokens(sort: String = "new", limit: Int = 30): List<TokenMarket> = withContext(Dispatchers.IO) {
        val endpoint = when (sort.lowercase()) {
            "trending", "hot", "gainers" -> "trending_pools"
            else -> "new_pools"
        }
        val safeLimit = limit.coerceIn(1, 100)
        val out = LinkedHashMap<String, TokenMarket>()
        // GeckoTerminal pagina de 20 em 20; busca páginas até preencher o limite.
        var page = 1
        while (out.size < safeLimit && page <= 5) {
            // Primeira página propaga erro (ex.: 429) para o chamador; páginas seguintes são best-effort.
            val body = if (page == 1) geckoGet("/networks/solana/$endpoint?page=$page")
            else runCatching { geckoGet("/networks/solana/$endpoint?page=$page") }.getOrNull()
            val data = body?.let { runCatching { json.parseToJsonElement(it).jsonObject["data"]?.jsonArray }.getOrNull() }
            if (data.isNullOrEmpty()) break
            for (pool in data) {
                if (out.size >= safeLimit) break
                runCatching {
                    val attr = pool.jsonObject["attributes"]?.jsonObject ?: return@runCatching
                    val baseId = pool.jsonObject["relationships"]?.jsonObject
                        ?.get("base_token")?.jsonObject?.get("data")?.jsonObject
                        ?.get("id")?.jsonPrimitive?.contentOrNull ?: return@runCatching
                    val mint = baseId.removePrefix("solana_")
                    if (!SolanaAddressValidator.isValidAddress(mint) || out.containsKey(mint)) return@runCatching
                    val poolName = attr["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    val symbol = poolName.substringBefore(" / ").trim().ifBlank { mint.take(6) }
                    out[mint] = TokenMarket(
                        mint = mint,
                        symbol = symbol,
                        name = poolName,
                        priceUsd = attr["base_token_price_usd"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull(),
                        liquidityUsd = attr["reserve_in_usd"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull(),
                        volume24hUsd = (attr["volume_usd"] as? kotlinx.serialization.json.JsonObject)?.get("h24")?.jsonPrimitive?.contentOrNull?.toDoubleOrNull(),
                        priceChange24hPct = (attr["price_change_percentage"] as? kotlinx.serialization.json.JsonObject)?.get("h24")?.jsonPrimitive?.contentOrNull?.toDoubleOrNull(),
                        poolCreatedAt = attr["pool_created_at"]?.jsonPrimitive?.contentOrNull,
                        poolAddress = pool.jsonObject["id"]?.jsonPrimitive?.contentOrNull?.removePrefix("solana_"),
                        marketCapUsd = attr["market_cap_usd"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                            ?: attr["fdv_usd"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                    )
                }
            }
            page++
        }
        out.values.take(safeLimit).toList()
    }

    /**
     * Informações REAIS de um token (preço, liquidez, volume 24h, market cap).
     * Fonte primária: DexScreener (keyless, limite alto). Fallbacks: GeckoTerminal e, por fim,
     * só o preço via Jupiter — nunca falha por rate limit se qualquer fonte responder.
     */
    suspend fun tokenInfo(mint: String): TokenMarket? = withContext(Dispatchers.IO) {
        require(SolanaAddressValidator.isValidAddress(mint)) { "Mint inválido" }
        // 1) DexScreener (primário)
        dexScreenerToken(mint)?.let { return@withContext it }
        // 2) GeckoTerminal (fallback)
        runCatching { geckoTokenInfo(mint) }.getOrNull()?.let { return@withContext it }
        // 3) Último recurso: preço via Jupiter (sem liquidez/volume), para não voltar vazio
        val price = runCatching { priceUsd(mint) }.getOrNull()
        if (price != null && price > 0.0) TokenMarket(mint, mint.take(6), "", price, null, null, null, null, null, null) else null
    }

    private suspend fun geckoTokenInfo(mint: String): TokenMarket? {
        val body = geckoGet("/networks/solana/tokens/$mint")
        return run {
            val attr = json.parseToJsonElement(body).jsonObject["data"]?.jsonObject
                ?.get("attributes")?.jsonObject ?: return null
            TokenMarket(
                mint = mint,
                symbol = attr["symbol"]?.jsonPrimitive?.contentOrNull ?: mint.take(6),
                name = attr["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                priceUsd = attr["price_usd"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull(),
                liquidityUsd = attr["total_reserve_in_usd"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull(),
                volume24hUsd = (attr["volume_usd"] as? kotlinx.serialization.json.JsonObject)?.get("h24")?.jsonPrimitive?.contentOrNull?.toDoubleOrNull(),
                priceChange24hPct = null,
                poolCreatedAt = null,
                poolAddress = null,
                marketCapUsd = attr["market_cap_usd"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                    ?: attr["fdv_usd"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
            )
        }
    }

    companion object {
        const val JUP_PRICE_BASE = "https://lite-api.jup.ag"
        const val GECKO_BASE = "https://api.geckoterminal.com/api/v2"
        // Endpoint onchain da CoinGecko (espelha o GeckoTerminal) quando há chave Demo gratuita.
        const val COINGECKO_ONCHAIN_BASE = "https://api.coingecko.com/api/v3/onchain"
        const val DEXSCREENER_BASE = "https://api.dexscreener.com"
        private const val MAX_RESPONSE_CHARS = 2 * 1024 * 1024
        private const val MIN_INTERVAL_MS = 2500L
        private const val CACHE_TTL_MS = 90_000L
        private val geckoMutex = Mutex()
        @Volatile private var lastGeckoCallMs = 0L
        private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, String>>()
    }
}

/** Configuração opcional do mercado. Chave Demo gratuita da CoinGecko evita rate limit keyless. */
object MarketApiConfig {
    @Volatile var geckoApiKey: String? = null
}

data class TokenMarket(
    val mint: String,
    val symbol: String,
    val name: String,
    val priceUsd: Double?,
    val liquidityUsd: Double?,
    val volume24hUsd: Double?,
    val priceChange24hPct: Double?,
    val poolCreatedAt: String?,
    val poolAddress: String?,
    val marketCapUsd: Double?
)

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
