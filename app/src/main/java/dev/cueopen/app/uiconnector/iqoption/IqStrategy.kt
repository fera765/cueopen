package dev.cueopen.app.uiconnector.iqoption

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
        val calls: Int, val puts: Int, val invalidResultCandles: Int = 0,
    )

    /**
     * Compatibilidade de API: delega à mesma análise Price Action usada pelo gate de entrada e simula
     * a próxima vela (abertura → fechamento). Não representa payout/execução reais nem entrada intrabar.
     */
    fun backtest(bars: List<Bar>, payout: Double, strong: Boolean, window: Int = 120,
                 mmN: Int = 10, mmK: Int = 6, mmBank: Double = 10.0, timeframeSeconds: Int = 60): Backtest {
        val r = PriceActionBacktest.run(
            bars.mapIndexed { index, bar -> PriceBar(index.toLong() * timeframeSeconds.coerceAtLeast(1), bar.open, bar.high, bar.low, bar.close, bar.volume) },
            payout, strong, window, mmN, mmK, mmBank, timeframeSeconds,
        )
        return Backtest(r.trades, r.wins, r.losses, r.ties, r.winRate, r.breakEven, r.flatPnl,
            r.mmFinal, r.mmCyclesWon, r.mmCyclesLost, r.maxLossStreak, r.calls, r.puts, r.invalidResultCandles)
    }
}

/**
 * Estratégias MHI (velas de 1 min, quadrantes de 5 min: hh:00–04, 05–09…).
 * Análise: as 3 ÚLTIMAS velas do quadrante (minutos 3, 4 e 5). Minoria = entra na cor que apareceu MENOS;
 * Maioria = na cor que apareceu MAIS. MHI 1/2/3 = entrada na 1ª/2ª/3ª vela do quadrante seguinte.
 * Gale 1 e 2 = repetir nas velas seguintes (só estatística: o app não faz martingale sozinho).
 * Doji na análise → sem sinal; doji na entrada conta como perda (conservador).
 */
object Mhi {
    data class M1(val from: Long, val open: Double, val close: Double) {
        val color: Int get() = if (close > open) 1 else if (close < open) -1 else 0
    }
    data class Variant(val name: String, val entryOffset: Int, val minority: Boolean)
    data class Stats(val variant: Variant, val signals: Int, val g0: Int, val g1: Int, val g2: Int) {
        val rateG0 get() = if (signals == 0) 0.0 else g0.toDouble() / signals
        val rateG1 get() = if (signals == 0) 0.0 else (g0 + g1).toDouble() / signals
        val rateG2 get() = if (signals == 0) 0.0 else (g0 + g1 + g2).toDouble() / signals
    }
    data class Signal(val variant: Variant, val direction: String, val entryAt: Long)

    val VARIANTS = listOf(
        Variant("MHI 1 (minoria)", 0, true), Variant("MHI 2 (minoria)", 1, true), Variant("MHI 3 (minoria)", 2, true),
        Variant("MHI 1 (maioria)", 0, false), Variant("MHI 2 (maioria)", 1, false), Variant("MHI 3 (maioria)", 2, false),
    )

    /** Direção pela análise das 3 velas que fecham o quadrante iniciado em [q] − 300. null = sem sinal (faltam velas ou doji). */
    fun direction(byTime: Map<Long, M1>, q: Long, minority: Boolean): String? {
        val a = listOf(q - 180, q - 120, q - 60).map { byTime[it] ?: return null }
        if (a.any { it.color == 0 }) return null
        val greens = a.count { it.color == 1 }
        val majorityUp = greens >= 2
        val up = if (minority) !majorityUp else majorityUp
        return if (up) "call" else "put"
    }

    /** Assertividade histórica com G0, G1 e G2 sobre velas reais FECHADAS. */
    fun backtest(candles: List<M1>, v: Variant): Stats {
        val byTime = candles.associateBy { it.from }
        if (candles.isEmpty()) return Stats(v, 0, 0, 0, 0)
        var signals = 0; var g0 = 0; var g1 = 0; var g2 = 0
        val first = (candles.first().from / 300 + 1) * 300
        var q = first
        val last = candles.last().from
        while (q + 60L * (v.entryOffset + 2) <= last) {
            val dir = direction(byTime, q, v.minority)
            if (dir != null) {
                val want = if (dir == "call") 1 else -1
                val e = q + 60L * v.entryOffset
                val tries = (0..2).map { byTime[e + 60L * it] }
                if (tries.all { it != null }) {
                    signals++
                    when {
                        tries[0]!!.color == want -> g0++
                        tries[1]!!.color == want -> g1++
                        tries[2]!!.color == want -> g2++
                    }
                }
            }
            q += 300
        }
        return Stats(v, signals, g0, g1, g2)
    }

    /** Próximos sinais a partir de agora (relógio do servidor), para cada variante com entrada ainda por vir. */
    fun upcoming(candles: List<M1>, nowSec: Long, windowSec: Int = 10): List<Signal> {
        val closed = candles.filter { it.from + 60 <= nowSec }.associateBy { it.from }
        val q = nowSec / 300 * 300
        return VARIANTS.mapNotNull { v ->
            val e = q + 60L * v.entryOffset
            if (nowSec > e + windowSec) return@mapNotNull null
            val dir = direction(closed, q, v.minority) ?: return@mapNotNull null
            Signal(v, dir, e)
        }
    }
}
