package dev.cueopen.app.wallet

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import dev.cueopen.app.net.await
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Jupiter aggregator client (free public tier, no API key). It only fetches a quote and asks
 * Jupiter to build a swap transaction; it never receives the wallet secret. Signing happens
 * locally in [SolanaSwapExecutor]. Swaps are a Mainnet feature (Jupiter has no Devnet liquidity).
 */
class SolanaSwapClient(
    private val baseUrl: String = DEFAULT_BASE,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS)
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
) {
    suspend fun quote(
        inputMint: String,
        outputMint: String,
        amountRaw: Long,
        slippageBps: Int
    ): JupiterQuote = withContext(Dispatchers.IO) {
        require(SolanaAddressValidator.isValidAddress(inputMint)) { "Mint de entrada inválido" }
        require(SolanaAddressValidator.isValidAddress(outputMint)) { "Mint de saída inválido" }
        require(amountRaw > 0L) { "Valor deve ser positivo" }
        require(slippageBps in 1..5000) { "Slippage fora do intervalo permitido" }
        val url = baseUrl.trimEnd('/') +
            "/swap/v1/quote?inputMint=$inputMint&outputMint=$outputMint&amount=$amountRaw" +
            "&slippageBps=$slippageBps&restrictIntermediateTokens=true"
        val request = Request.Builder().url(url).header("Accept", "application/json").get().build()
        client.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw IOException("Jupiter quote HTTP ${response.code}")
            val body = response.body?.string() ?: throw IOException("Resposta vazia da cotação")
            if (body.length > MAX_RESPONSE_CHARS) throw IOException("Resposta da cotação excede o limite")
            val root = runCatching { Json.parseToJsonElement(body).jsonObject }
                .getOrElse { throw IOException("Cotação em formato inválido") }
            if (root["error"] != null || root["outAmount"] == null) {
                throw IOException("Nenhuma rota de troca disponível para este par")
            }
            val outAmount = root["outAmount"]?.jsonPrimitive?.content?.toLongOrNull()
                ?: throw IOException("Cotação sem valor de saída")
            val inAmount = root["inAmount"]?.jsonPrimitive?.content?.toLongOrNull() ?: amountRaw
            val priceImpact = root["priceImpactPct"]?.jsonPrimitive?.content ?: "0"
            val routes = runCatching {
                root["routePlan"]?.jsonArray?.mapNotNull {
                    it.jsonObject["swapInfo"]?.jsonObject?.get("label")?.jsonPrimitive?.content
                }.orEmpty()
            }.getOrDefault(emptyList())
            JupiterQuote(root, inAmount, outAmount, priceImpact, routes)
        }
    }

    /** Returns the base64-encoded, unsigned swap transaction built by Jupiter for [userPublicKey]. */
    suspend fun buildSwapTransaction(quoteResponse: JsonObject, userPublicKey: String): String =
        withContext(Dispatchers.IO) {
            require(SolanaAddressValidator.isValidAddress(userPublicKey)) { "Endereço do usuário inválido" }
            val payload = buildJsonObject {
                put("quoteResponse", quoteResponse)
                put("userPublicKey", userPublicKey)
                put("wrapAndUnwrapSol", true)
                put("dynamicComputeUnitLimit", true)
            }.toString()
            val request = Request.Builder()
                .url(baseUrl.trimEnd('/') + "/swap/v1/swap")
                .header("Accept", "application/json")
                .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Jupiter swap HTTP ${response.code}")
                val body = response.body?.string() ?: throw IOException("Resposta vazia do swap")
                if (body.length > MAX_RESPONSE_CHARS) throw IOException("Resposta do swap excede o limite")
                val root = runCatching { Json.parseToJsonElement(body).jsonObject }
                    .getOrElse { throw IOException("Swap em formato inválido") }
                root["swapTransaction"]?.jsonPrimitive?.content
                    ?: throw IOException("Jupiter não retornou a transação de swap")
            }
        }

    companion object {
        const val DEFAULT_BASE = "https://lite-api.jup.ag"
        const val SOL_MINT = "So11111111111111111111111111111111111111112"
        const val USDC_MINT = "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v"
        const val USDT_MINT = "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB"
        private const val MAX_RESPONSE_CHARS = 512 * 1024
    }
}

data class JupiterQuote(
    val rawQuoteResponse: JsonObject,
    val inAmountRaw: Long,
    val outAmountRaw: Long,
    val priceImpactPct: String,
    val routeLabels: List<String>
)
