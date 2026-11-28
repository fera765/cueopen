package dev.fluwy.app.uiconnector.iqoption

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs

/** Chave→texto persistente usada somente pelo ledger de salvaguardas do conector. */
interface KvStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
}

@Serializable
data class RiskState(
    val day: String = "", val dayPnl: Double = 0.0, val dayTrades: Int = 0, val dayWins: Int = 0,
    val consecLosses: Int = 0, val pausedUntil: Long = 0,
)

/** Matemática de exibição e serialização do limite de risco; não toma decisões de mercado. */
object IqTradeRisk {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun encode(state: RiskState) = json.encodeToString(RiskState.serializer(), state)
    fun decode(text: String?) = text?.let { runCatching { json.decodeFromString(RiskState.serializer(), it) }.getOrNull() }

    fun netPnl(result: String?, amount: Double, profit: Double?): Double = when (result?.lowercase()) {
        "win" -> when { profit == null -> 0.0; profit > amount -> profit - amount; else -> profit }
        "loose", "loss", "lose" -> -amount
        else -> 0.0
    }

    fun favorDiff(ticker: String, direction: String, entry: Double?, now: Double?): String {
        if (entry == null || now == null) return "—"
        val raw = if (direction.lowercase() == "put") entry - now else now - entry
        val normalized = ticker.uppercase().removeSuffix("-OTC").replace("/", "")
        val currencies = setOf("USD", "EUR", "GBP", "JPY", "CHF", "CAD", "AUD", "NZD", "SEK", "NOK", "DKK", "SGD", "HKD", "MXN", "ZAR", "TRY", "PLN", "CNH", "INR", "BRL", "HUF", "CZK", "THB", "ILS", "RUB")
        val pip = if (Regex("^[A-Z]{6}$").matches(normalized) && normalized.take(3) in currencies && normalized.takeLast(3) in currencies && entry <= 1000) {
            if ("JPY" in normalized || "HUF" in normalized) 0.01 else 0.0001
        } else null
        val value = if (pip != null) raw / pip else raw
        val sign = if (value > 0) "+" else if (value < 0) "−" else "±"
        val magnitude = abs(value)
        val text = if (pip != null) String.format(java.util.Locale.US, "%.1f", magnitude)
            else java.math.BigDecimal(magnitude).round(java.math.MathContext(4)).stripTrailingZeros().toPlainString()
        return sign + text + if (pip != null) " pips" else " pts"
    }
}
