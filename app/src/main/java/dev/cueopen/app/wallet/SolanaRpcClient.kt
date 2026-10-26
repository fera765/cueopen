package dev.cueopen.app.wallet

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Read-only Solana JSON-RPC client. It never receives or handles secret key material.
 * Do not use public RPC endpoints as a production reliability guarantee.
 */
class SolanaRpcClient(
    private val endpoint: String = DEFAULT_MAINNET_RPC,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(12, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
) {
    init {
        require(endpoint.startsWith("https://")) { "RPC endpoint must use HTTPS" }
    }

    suspend fun getBalanceLamports(publicKey: String): Long = withContext(Dispatchers.IO) {
        require(isBase58AddressShape(publicKey)) { "Invalid public key format" }
        val payload = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", "getBalance")
            put("params", kotlinx.serialization.json.buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive(publicKey))
                add(buildJsonObject { put("commitment", "confirmed") })
            })
        }.toString()

        val request = Request.Builder()
            .url(endpoint)
            .post(payload.toRequestBody(JSON_MEDIA_TYPE.toMediaType()))
            .header("Accept", "application/json")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Solana RPC HTTP ${response.code}")
            val body = response.body?.string() ?: throw IOException("Empty Solana RPC response")
            val root = Json.parseToJsonElement(body).jsonObject
            root["error"]?.let { throw IOException("Solana RPC returned an error") }
            val value = root["result"]?.jsonObject?.get("value")?.jsonPrimitive?.content
                ?: throw IOException("Malformed Solana getBalance response")
            value.toLongOrNull()?.takeIf { it >= 0L }
                ?: throw IOException("Invalid lamports value from Solana RPC")
        }
    }

    private fun isBase58AddressShape(value: String): Boolean =
        value.length in 32..44 && value.all { it in BASE58_ALPHABET }

    companion object {
        private const val DEFAULT_MAINNET_RPC = "https://api.mainnet-beta.solana.com"
        private const val JSON_MEDIA_TYPE = "application/json; charset=utf-8"
        private const val BASE58_ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
        fun lamportsToSol(lamports: Long): Double = lamports / 1_000_000_000.0
    }
}
