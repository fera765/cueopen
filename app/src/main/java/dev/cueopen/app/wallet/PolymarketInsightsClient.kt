package dev.cueopen.app.wallet

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.TimeUnit

private const val POLYMARKET_BASE_URL = "https://data-api.polymarket.com"
private const val POLYMARKET_CANDIDATES_PER_BOARD = 10
private const val POLYMARKET_POSITIONS_PAGE_SIZE = 1000
private const val POLYMARKET_MAX_POSITION_PAGES = 2
private const val POLYMARKET_WINDOW_SECONDS = 90L * 24L * 60L * 60L
private const val POLYMARKET_MAX_RESPONSE_CHARS = 8 * 1024 * 1024

/** Candidate source fields are leaderboard context only; they are not the 90-day score. */
internal data class PolymarketCandidate(
    val wallet: String,
    val displayName: String,
    val monthlyRank: Int? = null,
    val monthlyBoardPnl: Double? = null,
    val allTimeRank: Int? = null,
    val allTimeBoardPnl: Double? = null
) {
    fun merge(other: PolymarketCandidate): PolymarketCandidate = copy(
        displayName = displayName.ifBlank { other.displayName },
        monthlyRank = monthlyRank ?: other.monthlyRank,
        monthlyBoardPnl = monthlyBoardPnl ?: other.monthlyBoardPnl,
        allTimeRank = allTimeRank ?: other.allTimeRank,
        allTimeBoardPnl = allTimeBoardPnl ?: other.allTimeBoardPnl
    )

    fun sourceLabel(): String = buildList {
        monthlyRank?.let { add("leaderboard mensal #$it") }
        allTimeRank?.let { add("leaderboard acumulado #$it") }
    }.joinToString(" + ").ifBlank { "leaderboard público" }
}

internal data class PolymarketClosedPosition(
    val title: String,
    val outcome: String,
    val realizedPnlUsdc: Double?,
    val firstEntryEpochSeconds: Long?,
    val lastEventEpochSeconds: Long?
)

data class PolymarketWalletInsight(
    val wallet: String,
    val displayName: String,
    val sourceLabel: String,
    val monthlyBoardPnlUsdc: Double?,
    val allTimeBoardPnlUsdc: Double?,
    val realizedClosedPnlUsdc: Double,
    val closedPositionCount: Int,
    val positivePositionCount: Int,
    val negativePositionCount: Int,
    val zeroPositionCount: Int,
    val missingPnlCount: Int,
    val positionsPageLimitReached: Boolean,
    val error: String? = null
) {
    val positiveRatePercent: Double?
        get() = if (closedPositionCount == 0 || missingPnlCount > 0) null
        else positivePositionCount * 100.0 / closedPositionCount

    val averageClosedPnlUsdc: Double?
        get() = if (closedPositionCount == 0 || missingPnlCount > 0) null
        else realizedClosedPnlUsdc / closedPositionCount
}

data class PolymarketInsightsReport(
    val windowStartEpochSeconds: Long,
    val windowEndEpochSeconds: Long,
    val candidateCount: Int,
    val candidates: List<PolymarketWalletInsight>
) {
    val failedWalletCount: Int get() = candidates.count { it.error != null }
    val rankedCandidates: List<PolymarketWalletInsight>
        get() = candidates.sortedByDescending { it.realizedClosedPnlUsdc }

    /** Concise, sourced result suitable for the local agent's tool response. */
    fun toAgentSummary(): String = buildString {
        appendLine("Polymarket — análise pública, somente leitura; janela móvel de 90 dias (90 × 24h).")
        appendLine("Amostra: até 10 carteiras do leaderboard mensal e até 10 do acumulado; $candidateCount carteiras únicas consultadas. Não é um ranking exaustivo do mercado.")
        appendLine("Métrica principal: soma do realized_pnl de posições CLOSED cujo primeiro registro (first_entry_at) e último evento (last_event_at) estão ambos dentro da janela. Não inclui posições abertas, entradas anteriores ao período, nem representa o PnL total da conta em 90 dias.")
        appendLine("Fonte: Polymarket Data API v2 pública, sem autenticação/API key. Leaderboard mensal é uma janela de mês (não 90 dias); leaderboard acumulado é vitalício.")
        if (failedWalletCount > 0) appendLine("Consultas com falha: $failedWalletCount (resultados parciais).")
        rankedCandidates.forEachIndexed { index, item ->
            append("${index + 1}. ${item.displayName.ifBlank { item.wallet }} (${item.wallet}) — ")
            if (item.error == null) {
                append("PnL realizado da coorte fechada: US$ ${formatUsdc(item.realizedClosedPnlUsdc)}; ${item.closedPositionCount} posições")
                item.positiveRatePercent?.let { append("; ${formatUsdc(it)}% de posições positivas") }
            } else {
                append("métricas da janela indisponíveis")
            }
            append("; fonte da amostra: ${item.sourceLabel}.")
            if (item.missingPnlCount > 0) append(" ${item.missingPnlCount} posições sem PnL disponível.")
            if (item.positionsPageLimitReached) append(" Paginação limitada: há mais posições que as páginas lidas.")
            item.error?.let { append(" Consulta desta carteira falhou: $it") }
            item.monthlyBoardPnlUsdc?.let { append(" PnL informado pelo leaderboard mensal (marcado, janela de mês): US$ ${formatUsdc(it)}.") }
            item.allTimeBoardPnlUsdc?.let { append(" PnL informado pelo leaderboard acumulado (vitalício): US$ ${formatUsdc(it)}.") }
            appendLine()
        }
        append("Os dados descrevem resultados passados e não indicam retorno futuro. Nenhuma ordem foi criada, copiada ou enviada.")
    }.trim()
}

/**
 * Public Polymarket Data API v2 reader. It never accesses the user's Solana wallet, submits orders,
 * copies trades, or requires an API key. Only a bounded leaderboard sample is inspected.
 */
class PolymarketInsightsClient(
    private val baseUrl: String = POLYMARKET_BASE_URL,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun analyzeLast90Days(
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000L,
        candidatesPerBoard: Int = POLYMARKET_CANDIDATES_PER_BOARD
    ): PolymarketInsightsReport = withContext(Dispatchers.IO) {
        require(candidatesPerBoard in 1..20) { "A amostra deve conter entre 1 e 20 carteiras por leaderboard." }
        val end = nowEpochSeconds
        val start = end - POLYMARKET_WINDOW_SECONDS
        val candidateMap = linkedMapOf<String, PolymarketCandidate>()

        readLeaderboard("month", candidatesPerBoard).forEach { candidate ->
            candidateMap[candidate.wallet.lowercase(Locale.ROOT)] = candidate
        }
        readLeaderboard("all", candidatesPerBoard).forEach { candidate ->
            val key = candidate.wallet.lowercase(Locale.ROOT)
            candidateMap[key] = candidateMap[key]?.merge(candidate) ?: candidate
        }

        val results = ArrayList<PolymarketWalletInsight>(candidateMap.size)
        for (candidate in candidateMap.values) {
            try {
                val (positions, pageLimitReached) = readClosedPositions(candidate.wallet, start, end)
                results += summarizePolymarketCandidate(candidate, positions, start, end, pageLimitReached)
            } catch (e: PolymarketApiException) {
                if (e.statusCode == 429 || e.statusCode == 503) throw e
                results += summarizePolymarketCandidate(
                    candidate, emptyList(), start, end, pageLimitReached = false,
                    error = e.message?.take(220) ?: "Falha ao consultar posições."
                )
            } catch (e: Exception) {
                results += summarizePolymarketCandidate(
                    candidate, emptyList(), start, end, pageLimitReached = false,
                    error = e.message?.take(220) ?: e.javaClass.simpleName
                )
            }
        }
        PolymarketInsightsReport(start, end, candidateMap.size, results)
    }

    private suspend fun readLeaderboard(period: String, limit: Int): List<PolymarketCandidate> {
        val root = getJson(
            "/v2/leaderboard",
            listOf(
                "time_period" to period,
                "sort_by" to "PNL",
                "category" to "overall",
                "limit" to limit.toString()
            )
        )
        val rows = root.arrayField("data") ?: JsonArray(emptyList())
        return rows.mapNotNull { element ->
            val row = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            val wallet = row.stringField("user_id")?.trim()?.takeIf(::isEvmAddress) ?: return@mapNotNull null
            val name = sanitizePolymarketLabel(row.stringField("user_name")).ifBlank { wallet }
            PolymarketCandidate(
                wallet = wallet,
                displayName = name,
                monthlyRank = if (period == "month") row.intField("rank") else null,
                monthlyBoardPnl = if (period == "month") row.doubleField("pnl") else null,
                allTimeRank = if (period == "all") row.intField("rank") else null,
                allTimeBoardPnl = if (period == "all") row.doubleField("pnl") else null
            )
        }
    }

    private suspend fun readClosedPositions(
        wallet: String,
        startEpochSeconds: Long,
        endEpochSeconds: Long
    ): Pair<List<PolymarketClosedPosition>, Boolean> {
        val collected = ArrayList<PolymarketClosedPosition>()
        var cursor: String? = null
        var hasMore = false
        var page = 0
        do {
            val query = mutableListOf(
                "user" to wallet,
                "status" to "CLOSED",
                "start" to startEpochSeconds.toString(),
                "end" to endEpochSeconds.toString(),
                "sortBy" to "REALIZED_PNL",
                "sortDirection" to "DESC",
                "limit" to POLYMARKET_POSITIONS_PAGE_SIZE.toString()
            )
            cursor?.let { query += "cursor" to it }
            val root = getJson("/v2/positions", query)
            val rows = root.arrayField("data") ?: JsonArray(emptyList())
            rows.forEach { element ->
                val row = runCatching { element.jsonObject }.getOrNull() ?: return@forEach
                collected += PolymarketClosedPosition(
                    title = row.stringField("title").orEmpty(),
                    outcome = row.stringField("outcome").orEmpty(),
                    realizedPnlUsdc = row.doubleField("realized_pnl"),
                    firstEntryEpochSeconds = row.longField("first_entry_at"),
                    lastEventEpochSeconds = row.longField("last_event_at")
                )
            }
            val pagination = root.objectField("pagination")
            hasMore = pagination?.booleanField("has_more") == true
            cursor = pagination?.stringField("next_cursor")?.takeIf { it.isNotBlank() }
            page++
            // A missing cursor cannot be resumed safely. Treat it as a bounded partial result.
            if (hasMore && cursor == null) return collected to true
        } while (hasMore && page < POLYMARKET_MAX_POSITION_PAGES)
        return collected to hasMore
    }

    private suspend fun getJson(path: String, query: List<Pair<String, String>>): JsonObject {
        val urlBuilder = (baseUrl.trimEnd('/') + path).toHttpUrlOrNull()?.newBuilder()
            ?: throw IOException("URL inválida para a Data API da Polymarket.")
        query.forEach { (key, value) -> urlBuilder.addQueryParameter(key, value) }
        val url = urlBuilder.build()
        var attempt = 0
        while (true) {
            throttlePublicDataApi()
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "FluwyBot-Polymarket-Insights/1.0")
                .get()
                .build()
            val response = executeWithDnsRetry(request)
            val code = response.code
            val retryAfterSeconds = response.header("Retry-After")?.toLongOrNull()
            val traceId = response.header("x-trace-id")
            val body = response.use { it.body?.string().orEmpty() }
            if (body.length > POLYMARKET_MAX_RESPONSE_CHARS) throw IOException("Resposta da Polymarket excedeu o limite local de leitura.")
            if (code in 200..299) {
                return runCatching { json.parseToJsonElement(body).jsonObject }
                    .getOrElse { throw IOException("Resposta JSON inválida da Data API da Polymarket.") }
            }
            if (code in setOf(429, 503) && attempt == 0 && retryAfterSeconds != null && retryAfterSeconds in 0..5) {
                attempt++
                delay((retryAfterSeconds * 1000L).coerceAtLeast(1000L))
                continue
            }
            val retryNote = retryAfterSeconds?.let { " Aguarde $it s conforme Retry-After." }.orEmpty()
            val traceNote = traceId?.let { " Referência: $it." }.orEmpty()
            throw PolymarketApiException(code, retryAfterSeconds, "Polymarket HTTP $code.$retryNote$traceNote")
        }
    }

    private suspend fun executeWithDnsRetry(request: Request): okhttp3.Response {
        var retries = 0
        while (true) {
            try {
                return http.newCall(request).execute()
            } catch (e: UnknownHostException) {
                if (retries >= DNS_MAX_RETRIES) {
                    throw IOException(
                        "Não foi possível resolver data-api.polymarket.com. Verifique a conexão, o DNS privado/VPN e tente novamente.",
                        e
                    )
                }
                delay(DNS_RETRY_BASE_DELAY_MS * (retries + 1))
                retries++
            }
        }
    }

    private suspend fun throttlePublicDataApi() {
        globalRequestMutex.withLock {
            val waitMs = (MIN_REQUEST_INTERVAL_MS - (System.currentTimeMillis() - lastRequestAtMs)).coerceAtLeast(0L)
            if (waitMs > 0) delay(waitMs)
            lastRequestAtMs = System.currentTimeMillis()
        }
    }

    private fun isEvmAddress(value: String): Boolean = value.matches(Regex("^0x[a-fA-F0-9]{40}$"))

    companion object {
        // /v2/positions is documented as a heavier route; pace all calls below 10 req/s per process.
        private const val MIN_REQUEST_INTERVAL_MS = 125L
        private const val DNS_MAX_RETRIES = 2
        private const val DNS_RETRY_BASE_DELAY_MS = 300L
        private val globalRequestMutex = Mutex()
        private var lastRequestAtMs = 0L
    }
}

class PolymarketApiException(
    val statusCode: Int,
    val retryAfterSeconds: Long?,
    message: String
) : IOException(message)

internal fun summarizePolymarketCandidate(
    candidate: PolymarketCandidate,
    positions: List<PolymarketClosedPosition>,
    startEpochSeconds: Long,
    endEpochSeconds: Long,
    pageLimitReached: Boolean,
    error: String? = null
): PolymarketWalletInsight {
    val inCohort = positions.filter { position ->
        val firstEntry = position.firstEntryEpochSeconds
        val lastEvent = position.lastEventEpochSeconds
        firstEntry != null && lastEvent != null &&
            firstEntry in startEpochSeconds..endEpochSeconds &&
            lastEvent in startEpochSeconds..endEpochSeconds
    }
    val knownPnl = inCohort.mapNotNull { it.realizedPnlUsdc }
    val missingPnlCount = inCohort.size - knownPnl.size
    return PolymarketWalletInsight(
        wallet = candidate.wallet,
        displayName = candidate.displayName,
        sourceLabel = candidate.sourceLabel(),
        monthlyBoardPnlUsdc = candidate.monthlyBoardPnl,
        allTimeBoardPnlUsdc = candidate.allTimeBoardPnl,
        realizedClosedPnlUsdc = knownPnl.sum(),
        closedPositionCount = inCohort.size,
        positivePositionCount = knownPnl.count { it > 0.0 },
        negativePositionCount = knownPnl.count { it < 0.0 },
        zeroPositionCount = knownPnl.count { it == 0.0 },
        missingPnlCount = missingPnlCount,
        positionsPageLimitReached = pageLimitReached,
        error = error
    )
}

private fun JsonObject.stringField(name: String): String? =
    this[name]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }

private fun JsonObject.doubleField(name: String): Double? =
    this[name]?.let { runCatching { it.jsonPrimitive.doubleOrNull }.getOrNull() }

private fun JsonObject.intField(name: String): Int? =
    this[name]?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() }

private fun JsonObject.longField(name: String): Long? =
    this[name]?.let {
        runCatching { it.jsonPrimitive.contentOrNull?.toLongOrNull() }.getOrNull()
            ?: runCatching { it.jsonPrimitive.doubleOrNull?.toLong() }.getOrNull()
    }

private fun JsonObject.booleanField(name: String): Boolean? =
    this[name]?.let { runCatching { it.jsonPrimitive.booleanOrNull }.getOrNull() }

private fun JsonObject.arrayField(name: String): JsonArray? =
    this[name]?.let { runCatching { it.jsonArray }.getOrNull() }

private fun JsonObject.objectField(name: String): JsonObject? =
    this[name]?.let { runCatching { it.jsonObject }.getOrNull() }

private fun formatUsdc(value: Double): String = String.format(Locale.US, "%,.2f", value)

private fun sanitizePolymarketLabel(value: String?): String = value.orEmpty()
    .replace(Regex("[\\p{Cntrl}]"), " ")
    .replace(Regex("\\s+"), " ")
    .trim()
    .take(64)
