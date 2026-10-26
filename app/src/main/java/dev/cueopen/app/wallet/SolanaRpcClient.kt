package dev.cueopen.app.wallet

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Bounded-timeout JSON-RPC client. It never receives a key or seed.
 * The submit method is internal so a caller outside the wallet module cannot use this
 * client as a general transaction broadcaster. Public endpoints are not a production SLA.
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
        val parsed = runCatching { okhttp3.HttpUrl.get(endpoint) }.getOrNull()
        require(parsed != null && parsed.isHttps && parsed.username.isEmpty() &&
            parsed.password.isEmpty() && parsed.fragment == null
        ) { "RPC endpoint must be a valid HTTPS URL without embedded credentials or fragments" }
    }

    suspend fun getBalanceLamports(publicKey: String): Long = withContext(Dispatchers.IO) {
        require(SolanaAddressValidator.isValidAddress(publicKey)) {
            "Invalid 32-byte Solana public key"
        }
        val result = rpc("getBalance", listOf(
            JsonPrimitive(publicKey),
            buildJsonObject { put("commitment", "confirmed") }
        ))
        result.jsonObject["value"]?.jsonPrimitive?.content?.toLongOrNull()
            ?.takeIf { it >= 0L }
            ?: throw IOException("Invalid lamports value from Solana RPC")
    }

    /** Recent blockhash plus its expiry height; never store this as signing authorization. */
    suspend fun getLatestBlockhash(): SolanaBlockhash = withContext(Dispatchers.IO) {
        val result = rpc("getLatestBlockhash", listOf(
            buildJsonObject { put("commitment", "confirmed") }
        )).jsonObject["value"]?.jsonObject
            ?: throw IOException("Malformed getLatestBlockhash response")
        val blockhash = result["blockhash"]?.jsonPrimitive?.content
            ?: throw IOException("RPC omitted recent blockhash")
        val expiry = result["lastValidBlockHeight"]?.jsonPrimitive?.content?.toLongOrNull()
            ?: throw IOException("RPC omitted blockhash expiry height")
        if (!SolanaAddressValidator.isValidAddress(blockhash)) {
            throw IOException("RPC returned a malformed 32-byte Base58 blockhash")
        }
        SolanaBlockhash(blockhash, expiry)
    }

    /** Returns null only when RPC reports a null fee; callers must fail closed on null. */
    suspend fun getFeeForMessageLamports(serializedMessage: ByteArray): Long? =
        withContext(Dispatchers.IO) {
            require(serializedMessage.isNotEmpty() && serializedMessage.size <= MAX_TRANSACTION_BYTES) {
                "Serialized message size is invalid"
            }
            val result = rpc("getFeeForMessage", listOf(
                JsonPrimitive(Base64.getEncoder().encodeToString(serializedMessage)),
                buildJsonObject { put("commitment", "confirmed") }
            )).jsonObject
            val feeValue = result["value"] ?: throw IOException("Malformed getFeeForMessage response")
            if (feeValue == JsonNull) return@withContext null
            feeValue.jsonPrimitive.content.toLongOrNull()?.takeIf { it >= 0L }
                ?: throw IOException("Malformed getFeeForMessage result")
        }

    /**
     * Simulates a locally built transaction before it is broadcast. The caller must parse and
     * reject any simulation error; simulation success does not guarantee later confirmation.
     */
    suspend fun simulateSerializedTransaction(serializedTransaction: ByteArray): SolanaSimulation =
        withContext(Dispatchers.IO) {
            require(serializedTransaction.isNotEmpty() &&
                serializedTransaction.size <= MAX_TRANSACTION_BYTES) {
                "Serialized transaction size is invalid"
            }
            val value = rpc("simulateTransaction", listOf(
                JsonPrimitive(Base64.getEncoder().encodeToString(serializedTransaction)),
                buildJsonObject {
                    put("encoding", "base64")
                    put("commitment", "confirmed")
                    put("sigVerify", true)
                    put("replaceRecentBlockhash", false)
                }
            )).jsonObject["value"]?.jsonObject
                ?: throw IOException("Malformed simulateTransaction response")
            val error = value["err"]
            val logs = value["logs"]?.takeUnless { it == JsonNull }?.jsonArray
                ?.mapNotNull { element -> runCatching { element.jsonPrimitive.content }.getOrNull() }
                .orEmpty()
            val units = value["unitsConsumed"]?.takeUnless { it == JsonNull }
                ?.jsonPrimitive?.content?.toLongOrNull()
            if (error != null && error != JsonNull) {
                SolanaSimulation(success = false, error = error.toString(), logs = logs, unitsConsumed = units)
            } else {
                SolanaSimulation(success = true, error = null, logs = logs, unitsConsumed = units)
            }
        }

    /** Status is null when RPC has no record; this is not proof of transaction failure. */
    suspend fun getSignatureStatus(signature: String): SolanaSignatureStatus? =
        withContext(Dispatchers.IO) {
            require(SolanaSignatureValidator.isValid(signature)) { "Invalid Solana transaction signature" }
            val result = rpc("getSignatureStatuses", listOf(
                JsonArray(listOf(JsonPrimitive(signature))),
                buildJsonObject { put("searchTransactionHistory", true) }
            )).jsonObject
            val entries = result["value"]?.jsonArray
                ?: throw IOException("Malformed getSignatureStatuses response")
            val item = entries.firstOrNull() ?: throw IOException("RPC omitted signature status")
            if (item == JsonNull) return@withContext null
            val fields = item.jsonObject
            val slot = fields["slot"]?.jsonPrimitive?.content?.toLongOrNull()
                ?: throw IOException("Signature status has no slot")
            val confirmation = fields["confirmationStatus"]?.takeUnless { it == JsonNull }
                ?.jsonPrimitive?.content
            val error = fields["err"]?.takeUnless { it == JsonNull }?.toString()
            SolanaSignatureStatus(signature, slot, confirmation, error)
        }

    /**
     * Internal broadcast primitive. Caller must first persist the exact local signature as
     * SIGNED and verify the ledger record; after an ambiguous network error it must reconcile
     * that signature and must not blindly resubmit.
     */
    internal suspend fun sendPersistedTransaction(serializedTransaction: ByteArray): String =
        withContext(Dispatchers.IO) {
            require(serializedTransaction.isNotEmpty() &&
                serializedTransaction.size <= MAX_TRANSACTION_BYTES) {
                "Serialized transaction size is invalid"
            }
            rpc("sendTransaction", listOf(
                JsonPrimitive(Base64.getEncoder().encodeToString(serializedTransaction)),
                buildJsonObject {
                    put("encoding", "base64")
                    put("preflightCommitment", "confirmed")
                    put("skipPreflight", false)
                    put("maxRetries", 0)
                }
            )).jsonPrimitive.content.also { signature ->
                if (!SolanaSignatureValidator.isValid(signature)) throw IOException("RPC returned an invalid signature")
            }
        }

    private suspend fun rpc(method: String, params: List<JsonElement>): JsonElement =
        withContext(Dispatchers.IO) {
            val payload = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", 1)
                put("method", method)
                put("params", JsonArray(params))
            }.toString()
            val request = Request.Builder()
                .url(endpoint)
                .post(payload.toRequestBody(JSON_MEDIA_TYPE.toMediaType()))
                .header("Accept", "application/json")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Solana RPC HTTP ${response.code}")
                val body = response.body?.string() ?: throw IOException("Empty Solana RPC response")
                if (body.length > MAX_RESPONSE_CHARS) throw IOException("Solana RPC response exceeds size limit")
                val root = try {
                    Json.parseToJsonElement(body).jsonObject
                } catch (_: Exception) {
                    throw IOException("Malformed JSON-RPC response")
                }
                if (root["error"] != null && root["error"] != JsonNull) {
                    throw IOException("Solana RPC method ${method} failed")
                }
                root["result"] ?: throw IOException("Solana RPC response has no result")
            }
        }


    companion object {
        private const val DEFAULT_MAINNET_RPC = "https://api.mainnet-beta.solana.com"
        private const val JSON_MEDIA_TYPE = "application/json; charset=utf-8"
        private const val MAX_TRANSACTION_BYTES = 1232
        private const val MAX_RESPONSE_CHARS = 512 * 1024

        fun lamportsToSol(lamports: Long): Double = lamports / 1_000_000_000.0
    }
}

data class SolanaBlockhash(val blockhash: String, val lastValidBlockHeight: Long)

data class SolanaSimulation(
    val success: Boolean,
    val error: String?,
    val logs: List<String>,
    val unitsConsumed: Long?
)

data class SolanaSignatureStatus(
    val signature: String,
    val slot: Long,
    val confirmationStatus: String?,
    val error: String?
) {
    val isFinalized: Boolean
        get() = confirmationStatus == "finalized" && error == null

    val failedOnChain: Boolean
        get() = error != null
}
