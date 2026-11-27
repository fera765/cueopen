package dev.fluwy.app.wallet

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
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
import dev.fluwy.app.net.await
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

enum class SolanaNetwork(
    val rpcEndpoint: String,
    val displayName: String
) {
    MAINNET("https://api.mainnet-beta.solana.com", "Mainnet"),
    DEVNET("https://api.devnet.solana.com", "Devnet");

    fun transactionExplorerUrl(signature: String): String {
        val base = "https://explorer.solana.com/tx/" + signature
        return if (this == DEVNET) base + "?cluster=devnet" else base
    }

    /**
     * Read-only endpoints tried in order for balance/history. The canonical [rpcEndpoint] is kept
     * first; extra free, no-key public nodes make balance reads resilient to a flaky primary RPC.
     * Signing/sending always uses [rpcEndpoint] only.
     */
    val readEndpoints: List<String>
        get() = when (this) {
            MAINNET -> listOf(rpcEndpoint, "https://solana-rpc.publicnode.com", "https://api.mainnet-beta.solana.com")
            DEVNET -> listOf(rpcEndpoint, "https://api.devnet.solana.com")
        }.distinct()

    companion object {
        fun fromEndpoint(endpoint: String): SolanaNetwork? =
            entries.firstOrNull { it.rpcEndpoint == endpoint }
    }
}

internal class SolanaRpcHttpException(val statusCode: Int) : IOException("Solana RPC HTTP $statusCode")

internal fun isReadEndpointFallbackAllowed(failure: Exception): Boolean = when (failure) {
    is SolanaRpcHttpException -> failure.statusCode in 500..599
    else -> failure is java.net.UnknownHostException ||
        failure is java.net.SocketTimeoutException ||
        failure is java.net.ConnectException
}

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
        .build(),
    val network: SolanaNetwork? = SolanaNetwork.fromEndpoint(endpoint)
) {
    init {
        val parsed = endpoint.toHttpUrlOrNull()
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

    /** Real SPL token balances owned by [owner], read on-chain via getTokenAccountsByOwner (jsonParsed). */
    suspend fun getTokenAccounts(owner: String): List<SplTokenBalance> = withContext(Dispatchers.IO) {
        require(SolanaAddressValidator.isValidAddress(owner)) { "Invalid owner address" }
        val result = mutableMapOf<String, SplTokenBalance>()
        for (programId in listOf(TOKEN_PROGRAM_ID, TOKEN_2022_PROGRAM_ID)) {
            val value = runCatching {
                rpc("getTokenAccountsByOwner", listOf(
                    JsonPrimitive(owner),
                    buildJsonObject { put("programId", programId) },
                    buildJsonObject { put("encoding", "jsonParsed"); put("commitment", "confirmed") }
                )).jsonObject["value"]?.jsonArray
            }.getOrNull() ?: continue
            for (element in value) {
                val info = element.jsonObject["account"]?.jsonObject
                    ?.get("data")?.jsonObject?.get("parsed")?.jsonObject
                    ?.get("info")?.jsonObject ?: continue
                val mint = info["mint"]?.jsonPrimitive?.content ?: continue
                val tokenAmount = info["tokenAmount"]?.jsonObject ?: continue
                val amountRaw = tokenAmount["amount"]?.jsonPrimitive?.content?.toLongOrNull() ?: continue
                val decimals = tokenAmount["decimals"]?.jsonPrimitive?.content?.toIntOrNull() ?: continue
                val uiAmount = tokenAmount["uiAmountString"]?.jsonPrimitive?.content
                    ?: (amountRaw.toDouble() / Math.pow(10.0, decimals.toDouble())).toString()
                if (amountRaw <= 0L) continue
                // Sum duplicates (same mint across multiple token accounts).
                val existing = result[mint]
                result[mint] = if (existing == null) SplTokenBalance(mint, amountRaw, decimals, uiAmount)
                else existing.copy(amountRaw = existing.amountRaw + amountRaw)
            }
        }
        result.values.sortedByDescending { it.amountRaw }
    }

    /** Number of decimals for an SPL token mint, read on-chain. Used only to format amounts. */
    suspend fun getTokenDecimals(mint: String): Int = withContext(Dispatchers.IO) {
        require(SolanaAddressValidator.isValidAddress(mint)) { "Invalid token mint address" }
        val value = rpc("getTokenSupply", listOf(
            JsonPrimitive(mint),
            buildJsonObject { put("commitment", "confirmed") }
        )).jsonObject["value"]?.jsonObject ?: throw IOException("Malformed getTokenSupply response")
        value["decimals"]?.jsonPrimitive?.content?.toIntOrNull()?.takeIf { it in 0..18 }
            ?: throw IOException("Token mint has no valid decimals")
    }

    /** Sends an already-signed serialized transaction. Used by the swap executor. */
    suspend fun sendSignedTransaction(serializedTransaction: ByteArray): String =
        sendPersistedTransaction(serializedTransaction)

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

    /** Recent on-chain signatures for a public address. This is history, not proof of wallet ownership. */
    suspend fun getRecentSignatures(publicKey: String, limit: Int = 10): List<SolanaRecentSignature> =
        withContext(Dispatchers.IO) {
            require(SolanaAddressValidator.isValidAddress(publicKey)) {
                "Invalid 32-byte Solana public key"
            }
            require(limit in 1..25) { "Signature history limit must be between 1 and 25" }
            val result = rpc("getSignaturesForAddress", listOf(
                JsonPrimitive(publicKey),
                buildJsonObject {
                    put("limit", limit)
                    put("commitment", "confirmed")
                }
            ))
            result.jsonArray.map { element ->
                val fields = element.jsonObject
                val signature = fields["signature"]?.jsonPrimitive?.content
                    ?: throw IOException("RPC signature item omitted signature")
                if (!SolanaSignatureValidator.isValid(signature)) {
                    throw IOException("RPC returned invalid transaction signature")
                }
                val slot = fields["slot"]?.jsonPrimitive?.content?.toLongOrNull()
                    ?: throw IOException("RPC signature item omitted slot")
                val blockTime = fields["blockTime"]?.takeUnless { it == JsonNull }
                    ?.jsonPrimitive?.content?.toLongOrNull()
                val error = fields["err"]?.takeUnless { it == JsonNull }?.toString()
                SolanaRecentSignature(signature, slot, blockTime, error)
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
            client.newCall(request).await().use { response ->
                if (!response.isSuccessful) throw SolanaRpcHttpException(response.code)
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
        const val TOKEN_PROGRAM_ID = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA"
        const val TOKEN_2022_PROGRAM_ID = "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb"

        /** Known-mint display symbols (no external price/metadata source used). */
        fun knownSymbol(mint: String): String? = when (mint) {
            "EPjFWdd5AufqSSqeM2qN1xzybapC8G4wEGGkZwyTDt1v" -> "USDC"
            "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB" -> "USDT"
            "So11111111111111111111111111111111111111112" -> "wSOL"
            else -> null
        }

        /** Token balances with endpoint fallback; throws only after all read endpoints fail. */
        suspend fun tokenAccountsWithFallback(network: SolanaNetwork, owner: String): List<SplTokenBalance> {
            var last: Exception? = null
            for (endpoint in network.readEndpoints) {
                try {
                    return SolanaRpcClient(endpoint = endpoint, network = network).getTokenAccounts(owner)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (e: Exception) {
                    if (!isReadEndpointFallbackAllowed(e)) throw e
                    last = e
                }
            }
            throw last ?: IOException("Tokens indisponíveis")
        }

        fun lamportsToSol(lamports: Long): Double = lamports / 1_000_000_000.0

        /** Balance read with bounded endpoint failover; rate limits and client errors are never retried. */
        suspend fun balanceWithFallback(network: SolanaNetwork, address: String): Long {
            var last: Exception? = null
            for (endpoint in network.readEndpoints) {
                try {
                    return SolanaRpcClient(endpoint = endpoint, network = network).getBalanceLamports(address)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (e: Exception) {
                    if (!isReadEndpointFallbackAllowed(e)) throw e
                    last = e
                }
            }
            throw last ?: IOException("Saldo indisponível")
        }

        /** Recent signatures with endpoint fallback; returns empty list only after all endpoints fail. */
        suspend fun recentSignaturesWithFallback(network: SolanaNetwork, address: String, limit: Int = 6): List<SolanaRecentSignature> {
            var last: Exception? = null
            for (endpoint in network.readEndpoints) {
                try {
                    return SolanaRpcClient(endpoint = endpoint, network = network).getRecentSignatures(address, limit)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (e: Exception) {
                    if (!isReadEndpointFallbackAllowed(e)) throw e
                    last = e
                }
            }
            throw last ?: IOException("Histórico indisponível")
        }
    }
}

data class SplTokenBalance(
    val mint: String,
    val amountRaw: Long,
    val decimals: Int,
    val uiAmount: String
)

data class SolanaRecentSignature(
    val signature: String,
    val slot: Long,
    val blockTimeEpochSeconds: Long?,
    val error: String?
)

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
