package dev.fluwy.app.wallet

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import dev.fluwy.app.net.await
import dev.fluwy.app.util.runCatchingCancellable
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
        .callTimeout(25, TimeUnit.SECONDS)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .build()
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * GET resiliente ao GeckoTerminal: cache curto (evita repetir chamadas), throttle global
     * abaixo do limite público atual de 10 req/min sem chave. Em HTTP 429, não repete a chamada
     * imediatamente; orienta o chamador a aguardar o Retry-After ou a janela pública de quota.
     * Uma chave Demo opcional da CoinGecko habilita o endpoint onchain com quota dedicada.
     * [pathAndQuery] ex.: "/networks/solana/new_pools?page=1".
     */
    private suspend fun geckoGet(pathAndQuery: String): String = withContext(Dispatchers.IO) {
        cacheGet(pathAndQuery)?.let { return@withContext it }
        val key = MarketApiConfig.geckoApiKey?.takeIf { it.isNotBlank() }
        val base = if (key != null) COINGECKO_ONCHAIN_BASE else geckoBase.trimEnd('/')
        val url = base + pathAndQuery
        geckoThrottle(key != null)
        val reqB = Request.Builder().url(url).header("Accept", "application/json").get()
        if (key != null) reqB.header("x-cg-demo-api-key", key)
        client.newCall(reqB.build()).await().use { resp ->
            if (resp.code == 429) {
                throw IOException(geckoRateLimitMessage(resp.header("Retry-After")))
            }
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body?.string().orEmpty()
            if (body.length > MAX_RESPONSE_CHARS) throw IOException("Resposta excede o limite")
            cachePut(pathAndQuery, body)
            body
        }
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
    /**
     * Metadados de exibição (símbolo, nome, preço USD, ícone) de vários tokens numa ÚNICA chamada
     * ao DexScreener (keyless; aceita mints separados por vírgula). Para a lista "Meus ativos".
     */
    suspend fun tokenDisplays(mints: List<String>): Map<String, TokenDisplay> = withContext(Dispatchers.IO) {
        val ids = mints.filter { SolanaAddressValidator.isValidAddress(it) }.distinct()
        if (ids.isEmpty()) return@withContext emptyMap()
        val out = HashMap<String, TokenDisplay>()
        // DexScreener aceita até 30 mints por chamada.
        ids.chunked(30).forEach { chunk ->
            val cacheKey = "disp:" + chunk.joinToString(",")
            val body = cacheGet(cacheKey) ?: runCatchingCancellable {
                val req = Request.Builder().url("$DEXSCREENER_BASE/latest/dex/tokens/" + chunk.joinToString(","))
                    .header("Accept", "application/json").get().build()
                client.newCall(req).await().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val b = resp.body?.string().orEmpty()
                    if (b.isNotBlank() && b.length <= MAX_RESPONSE_CHARS) { cachePut(cacheKey, b); b } else null
                }
            }.getOrNull() ?: return@forEach
            val pairs = runCatching { json.parseToJsonElement(body).jsonObject["pairs"]?.jsonArray }.getOrNull() ?: return@forEach
            // Para cada mint, escolhe o par de maior liquidez.
            chunk.forEach { mint ->
                val best = pairs.filter {
                    val o = it.jsonObject
                    (o["chainId"]?.jsonPrimitive?.contentOrNull) == "solana" &&
                        (o["baseToken"]?.jsonObject?.get("address")?.jsonPrimitive?.contentOrNull) == mint
                }.maxByOrNull { (it.jsonObject["liquidity"] as? JsonObject)?.get("usd")?.jsonPrimitive?.doubleOrNull ?: 0.0 }
                    ?.jsonObject ?: return@forEach
                val baseTok = best["baseToken"]?.jsonObject
                out[mint] = TokenDisplay(
                    mint = mint,
                    symbol = baseTok?.get("symbol")?.jsonPrimitive?.contentOrNull ?: mint.take(4),
                    name = baseTok?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty(),
                    priceUsd = best["priceUsd"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull(),
                    imageUrl = (best["info"] as? JsonObject)?.get("imageUrl")?.jsonPrimitive?.contentOrNull
                )
            }
        }
        out
    }

    /** Melhor par (maior liquidez) do token na Solana via DexScreener, em cache. */
    private suspend fun bestDexPair(mint: String): JsonObject? = withContext(Dispatchers.IO) {
        val cacheKey = "dex:$mint"
        val body = cacheGet(cacheKey) ?: runCatchingCancellable {
            val req = Request.Builder().url("$DEXSCREENER_BASE/latest/dex/tokens/$mint")
                .header("Accept", "application/json").get().build()
            client.newCall(req).await().use { resp ->
                if (!resp.isSuccessful) return@use null
                val b = resp.body?.string().orEmpty()
                if (b.isNotBlank() && b.length <= MAX_RESPONSE_CHARS) { cachePut(cacheKey, b); b } else null
            }
        }.getOrNull() ?: return@withContext null
        val pairs = runCatching { json.parseToJsonElement(body).jsonObject["pairs"]?.jsonArray }.getOrNull() ?: return@withContext null
        selectBestSolanaDexPair(pairs, mint)
    }

    /** Strict lookup used by OHLCV: network/provider failures must not be reported as "no pool". */
    private suspend fun bestDexPairForPool(mint: String): JsonObject? = withContext(Dispatchers.IO) {
        val cacheKey = "dex:$mint"
        val body = cacheGet(cacheKey) ?: run {
            val request = Request.Builder().url("$DEXSCREENER_BASE/latest/dex/tokens/$mint")
                .header("Accept", "application/json").get().build()
            client.newCall(request).await().use { response ->
                if (!response.isSuccessful) throw IOException("DexScreener HTTP ${response.code}")
                val responseBody = response.body?.string().orEmpty()
                if (responseBody.isBlank()) throw IOException("Resposta vazia do DexScreener")
                if (responseBody.length > MAX_RESPONSE_CHARS) throw IOException("Resposta DexScreener excede o limite")
                cachePut(cacheKey, responseBody)
                responseBody
            }
        }
        val pairs = try {
            json.parseToJsonElement(body).jsonObject["pairs"]?.jsonArray
        } catch (failure: Exception) {
            throw IOException("Resposta DexScreener inválida", failure)
        } ?: throw IOException("Resposta DexScreener sem lista de pares")
        try {
            selectBestSolanaDexPair(pairs, mint)
        } catch (failure: Exception) {
            throw IOException("Lista de pares DexScreener inválida", failure)
        }
    }

    private suspend fun dexScreenerToken(mint: String): TokenMarket? {
        val o = bestDexPair(mint) ?: return null
        val baseTok = o["baseToken"]?.jsonObject
        return TokenMarket(
            mint = mint,
            symbol = baseTok?.get("symbol")?.jsonPrimitive?.contentOrNull ?: mint.take(6),
            name = baseTok?.get("name")?.jsonPrimitive?.contentOrNull.orEmpty(),
            priceUsd = o["priceUsd"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull(),
            liquidityUsd = (o["liquidity"] as? JsonObject)?.get("usd")?.jsonPrimitive?.doubleOrNull,
            volume24hUsd = (o["volume"] as? JsonObject)?.get("h24")?.jsonPrimitive?.doubleOrNull,
            priceChange24hPct = (o["priceChange"] as? JsonObject)?.get("h24")?.jsonPrimitive?.doubleOrNull,
            poolCreatedAt = o["pairCreatedAt"]?.jsonPrimitive?.contentOrNull,
            poolAddress = o["pairAddress"]?.jsonPrimitive?.contentOrNull,
            marketCapUsd = o["marketCap"]?.jsonPrimitive?.doubleOrNull ?: o["fdv"]?.jsonPrimitive?.doubleOrNull
        )
    }

    /** A valid empty response means no indexed pool; provider failures remain typed and recoverable. */
    private suspend fun poolForMint(mint: String): String? {
        cacheGet("pool:$mint")?.let { return it }
        var dexFailure: IOException? = null
        val fromDex = try {
            bestDexPairForPool(mint)?.let { pair ->
                pair["pairAddress"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: throw IOException("Par DexScreener sem endereço de pool")
            }
        } catch (failure: IOException) {
            dexFailure = failure
            null
        }
        if (!fromDex.isNullOrBlank()) {
            cachePut("pool:$mint", fromDex)
            return fromDex
        }

        var geckoFailure: IOException? = null
        val fromGecko = try {
            topPoolFor(mint)
        } catch (failure: IOException) {
            geckoFailure = failure
            null
        }
        if (!fromGecko.isNullOrBlank()) {
            cachePut("pool:$mint", fromGecko)
            return fromGecko
        }
        if (dexFailure != null || geckoFailure != null) {
            val details = listOfNotNull(
                dexFailure?.let { "DexScreener: ${it.message?.take(120) ?: "falha"}" },
                geckoFailure?.let { "GeckoTerminal: ${it.message?.take(120) ?: "falha"}" },
            ).joinToString("; ")
            throw MarketPoolProviderException(
                "Não foi possível confirmar se existe pool de liquidez porque um ou mais provedores falharam: $details",
                dexFailure ?: geckoFailure,
            )
        }
        return null
    }

    /**
     * Resumo de TENDÊNCIA real do DexScreener (keyless): preço e variações m5/h1/h6/h24, volume e
     * compras/vendas. NÃO é OHLCV — é usado como alternativa confiável quando as velas falham,
     * sempre rotulado como tal. Dados 100% reais.
     */
    suspend fun dexTrend(mint: String): String? {
        val o = bestDexPair(mint) ?: return null
        val pc = o["priceChange"] as? JsonObject
        val vol = o["volume"] as? JsonObject
        val txns = o["txns"] as? JsonObject
        val sym = o["baseToken"]?.jsonObject?.get("symbol")?.jsonPrimitive?.contentOrNull ?: mint.take(6)
        fun ch(k: String) = pc?.get(k)?.jsonPrimitive?.doubleOrNull
        fun v(k: String) = vol?.get(k)?.jsonPrimitive?.doubleOrNull
        fun flow(k: String): String {
            val t = txns?.get(k) as? JsonObject ?: return "?"
            val b = t["buys"]?.jsonPrimitive?.intOrNull ?: 0; val s = t["sells"]?.jsonPrimitive?.intOrNull ?: 0
            return "${b}c/${s}v"
        }
        return buildString {
            appendLine("Tendência real (DexScreener; NÃO é OHLCV, mas é dado real de mercado) — $sym:")
            o["priceUsd"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.let { appendLine("Preço: US$ " + String.format(java.util.Locale.US, if (it < 1.0) "%.8f" else "%.4f", it)) }
            appendLine("Variação: m5 ${ch("m5")?.let{String.format(java.util.Locale.US,"%+.2f",it)}?:"?"}% | h1 ${ch("h1")?.let{String.format(java.util.Locale.US,"%+.2f",it)}?:"?"}% | h6 ${ch("h6")?.let{String.format(java.util.Locale.US,"%+.2f",it)}?:"?"}% | h24 ${ch("h24")?.let{String.format(java.util.Locale.US,"%+.2f",it)}?:"?"}%")
            appendLine("Volume: h1 US$ ${v("h1")?.let{String.format(java.util.Locale.US,"%,.0f",it)}?:"?"} | h24 US$ ${v("h24")?.let{String.format(java.util.Locale.US,"%,.0f",it)}?:"?"}")
            appendLine("Fluxo (compras/vendas): m5 ${flow("m5")} | h1 ${flow("h1")} | h24 ${flow("h24")}")
            (o["liquidity"] as? JsonObject)?.get("usd")?.jsonPrimitive?.doubleOrNull?.let { appendLine("Liquidez: US$ " + String.format(java.util.Locale.US, "%,.0f", it)) }
        }.trim()
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
        runCatchingCancellable {
            val url = priceBase.trimEnd('/') + "/price/v3?ids=" + ids.joinToString(",")
            val req = Request.Builder().url(url).header("Accept", "application/json").get().build()
            client.newCall(req).await().use { resp ->
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
            runCatchingCancellable { deriveUsdPriceViaQuote(mint) }.getOrNull()?.let { if (it > 0.0) result[mint] = it }
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
        val decimals = runCatchingCancellable {
            SolanaRpcClient(endpoint = SolanaNetwork.MAINNET.rpcEndpoint, network = SolanaNetwork.MAINNET)
                .getTokenDecimals(mint)
        }.getOrDefault(if (mint == SolanaSwapClient.SOL_MINT) 9 else 6)
        val oneToken = Math.pow(10.0, decimals.toDouble()).toLong().coerceAtLeast(1L)
        val quote = runCatchingCancellable { swapClient.quote(mint, SolanaSwapClient.USDC_MINT, oneToken, 100) }.getOrNull()
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
        val pool = poolForMint(mint) ?: throw NoIndexedSolanaPoolException()
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
        val body = geckoGet("/networks/solana/tokens/$mint/pools?page=1")
        val data = try {
            json.parseToJsonElement(body).jsonObject["data"]?.jsonArray
        } catch (failure: Exception) {
            throw IOException("Resposta GeckoTerminal de pools inválida", failure)
        } ?: throw IOException("Resposta GeckoTerminal sem lista de pools")
        if (data.isEmpty()) return null
        return try {
            val best = data.maxByOrNull {
                it.jsonObject["attributes"]?.jsonObject?.get("reserve_in_usd")
                    ?.jsonPrimitive?.doubleOrNull ?: 0.0
            } ?: throw IOException("Resposta GeckoTerminal não trouxe um pool utilizável")
            best.jsonObject["id"]?.jsonPrimitive?.contentOrNull?.removePrefix("solana_")
                ?.takeIf { it.isNotBlank() }
                ?: throw IOException("Pool GeckoTerminal sem identificador")
        } catch (failure: IOException) {
            throw failure
        } catch (failure: Exception) {
            throw IOException("Resposta GeckoTerminal de pools inválida", failure)
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
            val q = runCatchingCancellable { swapClient.quote(inputMint, outputMint, amount, slippageBps) }.getOrNull()
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
            else runCatchingCancellable { geckoGet("/networks/solana/$endpoint?page=$page") }.getOrNull()
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
        runCatchingCancellable { geckoTokenInfo(mint) }.getOrNull()?.let { return@withContext it }
        // 3) Último recurso: preço via Jupiter (sem liquidez/volume), para não voltar vazio
        val price = runCatchingCancellable { priceUsd(mint) }.getOrNull()
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
        private const val MIN_INTERVAL_MS = 8_000L
        private const val CACHE_TTL_MS = 90_000L
        private val geckoMutex = Mutex()
        @Volatile private var lastGeckoCallMs = 0L
        private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, String>>()
    }
}
internal fun geckoRateLimitMessage(
    retryAfterHeader: String?,
    nowMillis: Long = System.currentTimeMillis()
): String {
    val retryAfterSeconds = HttpRetryAfter.delayMillis(retryAfterHeader, nowMillis)
        ?.takeIf { it > 0L }
        ?.let(HttpRetryAfter::secondsCeiling)
    return if (retryAfterSeconds != null) {
        "limite de requisições GeckoTerminal (HTTP 429). Aguarde $retryAfterSeconds s conforme Retry-After."
    } else {
        "limite de requisições GeckoTerminal (HTTP 429). A API pública permite 10 chamadas por minuto; aguarde pelo menos 60 s antes de tentar novamente."
    }
}
/** Configuração opcional do mercado. Chave Demo gratuita da CoinGecko evita rate limit keyless. */
object MarketApiConfig {
    @Volatile var geckoApiKey: String? = null
}

data class TokenDisplay(
    val mint: String,
    val symbol: String,
    val name: String,
    val priceUsd: Double?,
    val imageUrl: String?
)

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

internal fun selectBestSolanaDexPair(
    pairs: Iterable<kotlinx.serialization.json.JsonElement>,
    mint: String,
): JsonObject? = pairs.filter {
    val pair = it.jsonObject
    pair["chainId"]?.jsonPrimitive?.contentOrNull == "solana" &&
        pair["baseToken"]?.jsonObject?.get("address")?.jsonPrimitive?.contentOrNull == mint
}.maxByOrNull {
    (it.jsonObject["liquidity"] as? JsonObject)?.get("usd")?.jsonPrimitive?.doubleOrNull ?: 0.0
}?.jsonObject

internal class NoIndexedSolanaPoolException : IOException(
    "Nenhum pool de liquidez foi retornado pelos indexadores para este token na Solana; pode haver ausência de listagem ou atraso de indexação"
)

internal class MarketPoolProviderException(message: String, cause: Throwable?) : IOException(message, cause)
