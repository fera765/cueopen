package dev.cueopen.app.uiconnector.iqoption

import dev.cueopen.app.uiconnector.custom.TechnicalSuite
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs

/** Armazenamento simples chave→texto (SharedPreferences no app; nulo nos testes). */
interface KvStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
}

/**
 * Gerenciamento MASANIELLO: em um ciclo de N operações, o objetivo é acertar K. O valor de cada entrada é
 * calculado para que, ao atingir K acertos (em qualquer ordem) dentro das N, o ciclo feche com o lucro-alvo;
 * se as perdas passarem de N−K, o ciclo encerra perdendo no máximo a banca do ciclo (nunca mais que isso).
 *
 * W(n,k) = fração da banca-alvo necessária com n operações restantes e k acertos faltando, odds o = 1 + payout:
 *   W(n,0) = 1;  W(n,k) = 0 se k > n;  W(n,k) = (W(n−1,k−1) + (o−1)·W(n−1,k)) / o
 *   entrada = banca_atual / W(n,k) · (W(n−1,k−1) − W(n−1,k)) / o
 */
object Masaniello {
    fun w(n: Int, k: Int, odds: Double): Double {
        val t = Array(n + 1) { DoubleArray(k + 1) }
        for (i in 0..n) for (j in 0..k) t[i][j] = when {
            j == 0 -> 1.0
            j > i -> 0.0
            else -> (t[i - 1][j - 1] + (odds - 1) * t[i - 1][j]) / odds
        }
        return t[n][k]
    }

    /** Valor da próxima entrada. null = ciclo encerrado (k == 0 venceu, ou k > n falhou). */
    fun stake(bank: Double, n: Int, k: Int, odds: Double): Double? {
        if (k <= 0 || k > n || bank <= 0) return null
        val wNow = w(n, k, odds)
        if (wNow <= 0) return null
        val s = bank / wNow * (w(n - 1, k - 1, odds) - w(n - 1, k, odds)) / odds
        return s.coerceIn(0.0, bank)
    }

    /** Lucro-alvo do ciclo (banca final se acertar K de N) a partir da banca inicial. */
    fun target(bank: Double, n: Int, k: Int, odds: Double): Double = bank / w(n, k, odds)
}

@Serializable
data class MmState(
    val n: Int, val k: Int, val bank: Double, val startBank: Double,
    val cycle: Int = 1, val wins: Int = 0, val losses: Int = 0, val cyclesWon: Int = 0, val cyclesLost: Int = 0,
    val lastResult: String = "",
)

@Serializable
data class RiskState(
    val day: String = "", val dayPnl: Double = 0.0, val dayTrades: Int = 0, val dayWins: Int = 0,
    val consecLosses: Int = 0, val pausedUntil: Long = 0,
)

object IqRules {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun encodeMm(s: MmState) = json.encodeToString(MmState.serializer(), s)
    fun decodeMm(t: String?) = t?.let { runCatching { json.decodeFromString(MmState.serializer(), it) }.getOrNull() }
    fun encodeRisk(s: RiskState) = json.encodeToString(RiskState.serializer(), s)
    fun decodeRisk(t: String?) = t?.let { runCatching { json.decodeFromString(RiskState.serializer(), it) }.getOrNull() }

    /** Tamanho do pip: pares com JPY 0,01; demais pares de moedas 0,0001; outros ativos (cripto, ações, ouro) não têm pip → null. */
    fun pipSize(ticker: String, price: Double?): Double? {
        val t = ticker.uppercase().removeSuffix("-OTC").replace("/", "")
        if (!Regex("^[A-Z]{6}$").matches(t)) return null
        val ccy = setOf("USD", "EUR", "GBP", "JPY", "CHF", "CAD", "AUD", "NZD", "SEK", "NOK", "DKK", "SGD", "HKD", "MXN", "ZAR", "TRY", "PLN", "CNH", "INR", "BRL", "HUF", "CZK", "THB", "ILS", "RUB")
        if (t.substring(0, 3) !in ccy || t.substring(3) !in ccy) return null
        if (price != null && price > 1000) return null
        return if ("JPY" in t || "HUF" in t) 0.01 else 0.0001
    }

    /**
     * Diferença a FAVOR da operação, com sinal: call ganha se subir, put ganha se cair.
     * Em pips para pares de moedas; em pontos de preço para os demais. Ex.: "+2.5 pips", "−0.25 pips", "+12.4 pts".
     */
    fun favorDiff(ticker: String, direction: String, entry: Double?, now: Double?): String {
        if (entry == null || now == null) return "—"
        val raw = if (direction.lowercase() == "put") entry - now else now - entry
        val pip = pipSize(ticker, entry)
        val v = if (pip != null) raw / pip else raw
        val sign = if (v > 0) "+" else if (v < 0) "−" else "±"
        val mag = abs(v)
        val txt = if (pip != null) String.format(java.util.Locale.US, "%.1f", mag)
            else java.math.BigDecimal(mag).round(java.math.MathContext(4)).stripTrailingZeros().toPlainString()
        return sign + txt + if (pip != null) " pips" else " pts"
    }

    /** Lucro líquido de uma opção encerrada (a IQ às vezes manda o retorno bruto, às vezes o líquido). */
    fun netPnl(result: String?, amount: Double, profit: Double?): Double = when (result?.lowercase()) {
        "win" -> when { profit == null -> 0.0; profit > amount -> profit - amount; else -> profit }
        "loose", "loss", "lose" -> -amount
        else -> 0.0
    }

    /** Sinal da suíte técnica → direção (call/put) ou null; strong = só "Forte compra/venda". */
    fun direction(summary: String, strong: Boolean): String? = when (summary) {
        "Forte compra" -> "call"
        "Forte venda" -> "put"
        "Compra" -> if (strong) null else "call"
        "Venda" -> if (strong) null else "put"
        else -> null
    }

    data class Bar(val open: Double, val high: Double, val low: Double, val close: Double, val volume: Double)

    data class Backtest(
        val trades: Int, val wins: Int, val losses: Int, val ties: Int, val winRate: Double, val breakEven: Double,
        val flatPnl: Double, val mmFinal: Double, val mmCyclesWon: Int, val mmCyclesLost: Int, val maxLossStreak: Int,
        val calls: Int, val puts: Int,
    )

    /**
     * Backtest sobre velas REAIS: em cada vela fechada calcula a suíte técnica com as [window] velas anteriores; se o
     * resumo indicar compra/venda, simula uma operação de 1 vela na vela seguinte (abertura → fechamento).
     * Resultado com stake fixo 1 e com Masaniello (N, K, banca). Não simula a entrada intrabar (contra o candle).
     */
    fun backtest(bars: List<Bar>, payout: Double, strong: Boolean, window: Int = 120,
                 mmN: Int = 10, mmK: Int = 6, mmBank: Double = 10.0): Backtest {
        var wins = 0; var losses = 0; var ties = 0; var calls = 0; var puts = 0
        var streak = 0; var maxStreak = 0; var flat = 0.0
        val odds = 1 + payout
        var mm = MmState(mmN, mmK, mmBank, mmBank)
        var total = mmBank; var cw = 0; var cl = 0
        val step = if (bars.size > 700) 2 else 1 // mantém o backtest rápido em séries longas
        var i = window
        while (i < bars.size - 1) {
            val w = bars.subList(i - window + 1, i + 1)
            val r = TechnicalSuite.analyze(TechnicalSuite.Candles(w.indices.map { it.toDouble() }, w.map { it.open }, w.map { it.high }, w.map { it.low }, w.map { it.close }, w.map { it.volume }))
            val dir = direction(r.summary, strong)
            if (dir != null) {
                val nx = bars[i + 1]
                if (dir == "call") calls++ else puts++
                val res = when { nx.close == nx.open -> "equal"; (nx.close > nx.open) == (dir == "call") -> "win"; else -> "loss" }
                val stake = Masaniello.stake(mm.bank, mm.n, mm.k, odds) ?: 0.0
                when (res) {
                    "win" -> { wins++; streak = 0; flat += payout; mm = mm.copy(n = mm.n - 1, k = mm.k - 1, bank = mm.bank + stake * payout) }
                    "loss" -> { losses++; streak++; maxStreak = maxOf(maxStreak, streak); flat -= 1.0; mm = mm.copy(n = mm.n - 1, bank = mm.bank - stake) }
                    else -> ties++
                }
                if (mm.k <= 0 || mm.k > mm.n || mm.bank <= 1e-9) {
                    if (mm.k <= 0) cw++ else cl++
                    total += mm.bank - mm.startBank
                    mm = MmState(mmN, mmK, mmBank, mmBank)
                }
                i += 1 // a vela usada na operação não gera outro sinal
            }
            i += step
        }
        total += mm.bank - mm.startBank
        val decided = wins + losses
        return Backtest(wins + losses + ties, wins, losses, ties, if (decided == 0) 0.0 else wins.toDouble() / decided,
            1.0 / (1 + payout), flat, total, cw, cl, maxStreak, calls, puts)
    }
}
