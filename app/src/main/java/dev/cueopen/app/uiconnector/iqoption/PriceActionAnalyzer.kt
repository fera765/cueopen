package dev.cueopen.app.uiconnector.iqoption

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/** Saída explícita da etapa de análise/decisão; nenhuma probabilidade ou acurácia é inferida. */
data class PriceActionAnalysis(
    val asset: String,
    val timeframeSeconds: Int,
    val barsAnalyzed: Int,
    val lastBarTimeSeconds: Long?,
    val marketStructure: MarketStructure,
    val decision: TradeDirection,
    val support: Double?,
    val resistance: Double?,
    val bodyToRange: Double?,
    val closeLocation: Double?,
    val averageTrueRange: Double?,
    val volatilityShock: Boolean,
    val setups: Set<PriceSetup>,
    val reasons: List<String>,
    val dataValid: Boolean,
) {
    /** Modo forte: rompimento confirmado por candle forte ou continuação estrutural com corpo >= 70%. */
    val strongSetup: Boolean
        get() = PriceSetup.BREAKOUT in setups ||
            (PriceSetup.TREND_CONTINUATION in setups && (bodyToRange ?: 0.0) >= 0.70)
}

enum class TradeDirection { CALL, PUT, NO_TRADE }
enum class MarketStructure { UPTREND, DOWNTREND, RANGE_OR_UNCONFIRMED, INSUFFICIENT_DATA, INVALID_DATA }
enum class PriceSetup { BREAKOUT, TREND_CONTINUATION, SUPPORT_REJECTION, RESISTANCE_REJECTION }

/**
 * Heurísticas fixas, para auditar e testar — não são um método oficial nem foram calibradas/validadas
 * para ativos, prazos, payout ou contratos específicos. Todos os thresholds devem ser considerados hipóteses.
 */
class PriceActionAnalyzer(
    private val minimumBars: Int = 30,
    private val rangeLookback: Int = 20,
    private val pivotSideBars: Int = 2,
) {
    fun analyze(series: PriceSeries): PriceActionAnalysis {
        val input = series.bars
        if (series.timeframeSeconds <= 0 || input.size < minimumBars) {
            return noTrade(series, MarketStructure.INSUFFICIENT_DATA, "Dados insuficientes: são necessárias pelo menos $minimumBars velas fechadas.")
        }
        val invalid = input.indexOfFirst { !it.isValid() }
        val invalidTime = input.zipWithNext().any { (a, b) ->
            val elapsed = b.timeSeconds - a.timeSeconds
            elapsed <= 0 || elapsed > series.timeframeSeconds * 2L
        }
        if (invalid >= 0 || invalidTime) {
            return noTrade(series, MarketStructure.INVALID_DATA, "Dados inválidos: OHLC não finito/inconsistente ou timestamps fora de ordem; nenhuma entrada autorizada.")
        }

        val bars = input.takeLast(maxOf(minimumBars, rangeLookback + pivotSideBars * 2 + 1))
        val last = bars.last()
        val prior = bars.dropLast(1)
        val priorRange = prior.takeLast(rangeLookback)
        val swingHighs = pivots(bars, highs = true)
        val swingLows = pivots(bars, highs = false)
        val structure = classifyStructure(swingHighs, swingLows)

        val support = swingLows.lastOrNull { it.second <= last.close }?.second
            ?: priorRange.minOfOrNull { it.low }
        val resistance = swingHighs.lastOrNull { it.second >= last.close }?.second
            ?: priorRange.maxOfOrNull { it.high }
        val previousHigh = priorRange.maxOfOrNull { it.high }
        val previousLow = priorRange.minOfOrNull { it.low }

        val candleRange = last.high - last.low
        val body = abs(last.close - last.open)
        val bodyRatio = if (candleRange > 0.0) body / candleRange else 0.0
        val closeLocation = if (candleRange > 0.0) (last.close - last.low) / candleRange else 0.5
        val bullishStrong = last.close > last.open && bodyRatio >= 0.60 && closeLocation >= 0.75
        val bearishStrong = last.close < last.open && bodyRatio >= 0.60 && closeLocation <= 0.25
        val priorTrueRanges = prior.mapIndexed { index, bar -> trueRange(bar, prior, index) }.filter { it.isFinite() && it > 0.0 }
        val medianRange = median(priorTrueRanges.takeLast(rangeLookback))
        val currentTrueRange = trueRange(last, bars, bars.lastIndex)
        val atr = median(priorTrueRanges.takeLast(14))
        val volShock = medianRange != null && currentTrueRange > medianRange * 2.5

        val callSetups = linkedSetOf<PriceSetup>()
        val putSetups = linkedSetOf<PriceSetup>()
        val bullishBreakout = previousHigh != null && last.close > previousHigh && bullishStrong
        val bearishBreakout = previousLow != null && last.close < previousLow && bearishStrong
        if (bullishBreakout) callSetups += PriceSetup.BREAKOUT
        if (bearishBreakout) putSetups += PriceSetup.BREAKOUT

        if (structure == MarketStructure.UPTREND && bullishStrong && last.close > prior.last().close) callSetups += PriceSetup.TREND_CONTINUATION
        if (structure == MarketStructure.DOWNTREND && bearishStrong && last.close < prior.last().close) putSetups += PriceSetup.TREND_CONTINUATION

        val levelBuffer = max((atr ?: medianRange ?: 0.0) * 0.20, abs(last.close) * 1e-8)
        val lowerWick = minOf(last.open, last.close) - last.low
        val upperWick = last.high - max(last.open, last.close)
        val nearSupport = support != null && last.low <= support + levelBuffer
        val nearResistance = resistance != null && last.high >= resistance - levelBuffer
        val bullishRejection = nearSupport && last.close > last.open && closeLocation >= 0.65 && lowerWick >= max(body * 1.5, candleRange * 0.25)
        val bearishRejection = nearResistance && last.close < last.open && closeLocation <= 0.35 && upperWick >= max(body * 1.5, candleRange * 0.25)
        if (bullishRejection && structure != MarketStructure.DOWNTREND) callSetups += PriceSetup.SUPPORT_REJECTION
        if (bearishRejection && structure != MarketStructure.UPTREND) putSetups += PriceSetup.RESISTANCE_REJECTION

        val conflict = callSetups.isNotEmpty() && putSetups.isNotEmpty()
        val decision = when {
            volShock -> TradeDirection.NO_TRADE
            conflict -> TradeDirection.NO_TRADE
            callSetups.isNotEmpty() -> TradeDirection.CALL
            putSetups.isNotEmpty() -> TradeDirection.PUT
            else -> TradeDirection.NO_TRADE
        }
        val setups = when (decision) {
            TradeDirection.CALL -> callSetups
            TradeDirection.PUT -> putSetups
            TradeDirection.NO_TRADE -> callSetups + putSetups
        }
        val reasons = buildList {
            add("Estrutura: ${structureLabel(structure)}; pivôs confirmados, sem usar a vela em formação.")
            add("Última vela fechada: corpo ${pct(bodyRatio)} do range; fechamento a ${pct(closeLocation)} da mínima.")
            if (support != null) add("Suporte aproximado: ${price(support)}.")
            if (resistance != null) add("Resistência aproximada: ${price(resistance)}.")
            if (PriceSetup.BREAKOUT in setups) add("Fechamento rompeu o extremo dos $rangeLookback candles anteriores com candle direcional forte.")
            if (PriceSetup.TREND_CONTINUATION in setups) add("Candle direcional forte alinhado à sequência confirmada de topos e fundos.")
            if (PriceSetup.SUPPORT_REJECTION in setups) add("Rejeição de suporte: pavio inferior e fechamento acima da abertura.")
            if (PriceSetup.RESISTANCE_REJECTION in setups) add("Rejeição de resistência: pavio superior e fechamento abaixo da abertura.")
            if (volShock) add("Não operar: range atual excede 2,5× a mediana recente (filtro heurístico de choque de volatilidade).")
            else if (conflict) add("Não operar: existem setups conflitantes de CALL e PUT na mesma vela.")
            else if (decision == TradeDirection.NO_TRADE) add("Não operar: nenhuma combinação definida de estrutura, nível e candle foi satisfeita.")
            else add("Decisão candidata: ${decision.name}; isso é uma classificação determinística, não uma previsão nem uma garantia de resultado.")
        }
        return PriceActionAnalysis(
            asset = series.asset,
            timeframeSeconds = series.timeframeSeconds,
            barsAnalyzed = bars.size,
            lastBarTimeSeconds = last.timeSeconds,
            marketStructure = structure,
            decision = decision,
            support = support,
            resistance = resistance,
            bodyToRange = bodyRatio,
            closeLocation = closeLocation,
            averageTrueRange = atr,
            volatilityShock = volShock,
            setups = setups,
            reasons = reasons,
            dataValid = true,
        )
    }

    private fun PriceBar.isValid(): Boolean =
        listOf(open, high, low, close, volume).all { it.isFinite() } &&
            high >= max(open, close) && low <= minOf(open, close) && high >= low && volume >= 0.0

    private fun pivots(bars: List<PriceBar>, highs: Boolean): List<Pair<Int, Double>> {
        val result = ArrayList<Pair<Int, Double>>()
        val first = pivotSideBars
        val end = bars.size - pivotSideBars
        for (i in first until end) {
            val value = if (highs) bars[i].high else bars[i].low
            val neighbors = (i - pivotSideBars..i + pivotSideBars).filter { it != i }.map { if (highs) bars[it].high else bars[it].low }
            if (neighbors.isNotEmpty() && if (highs) neighbors.all { value > it } else neighbors.all { value < it }) result += i to value
        }
        return result
    }

    private fun classifyStructure(highs: List<Pair<Int, Double>>, lows: List<Pair<Int, Double>>): MarketStructure {
        if (highs.size < 2 || lows.size < 2) return MarketStructure.RANGE_OR_UNCONFIRMED
        val h = highs.takeLast(2).map { it.second }
        val l = lows.takeLast(2).map { it.second }
        return when {
            h[1] > h[0] && l[1] > l[0] -> MarketStructure.UPTREND
            h[1] < h[0] && l[1] < l[0] -> MarketStructure.DOWNTREND
            else -> MarketStructure.RANGE_OR_UNCONFIRMED
        }
    }

    private fun trueRange(bar: PriceBar, history: List<PriceBar>, index: Int): Double {
        val previousClose = history.getOrNull(index - 1)?.close ?: bar.close
        return max(bar.high - bar.low, max(abs(bar.high - previousClose), abs(bar.low - previousClose)))
    }

    private fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2.0 else sorted[mid]
    }

    private fun noTrade(series: PriceSeries, structure: MarketStructure, reason: String) = PriceActionAnalysis(
        asset = series.asset,
        timeframeSeconds = series.timeframeSeconds,
        barsAnalyzed = series.bars.size,
        lastBarTimeSeconds = series.bars.lastOrNull()?.timeSeconds,
        marketStructure = structure,
        decision = TradeDirection.NO_TRADE,
        support = null,
        resistance = null,
        bodyToRange = null,
        closeLocation = null,
        averageTrueRange = null,
        volatilityShock = false,
        setups = emptySet(),
        reasons = listOf(reason),
        dataValid = false,
    )

    private fun structureLabel(structure: MarketStructure) = when (structure) {
        MarketStructure.UPTREND -> "alta (topos e fundos ascendentes)"
        MarketStructure.DOWNTREND -> "baixa (topos e fundos descendentes)"
        MarketStructure.RANGE_OR_UNCONFIRMED -> "lateral ou não confirmada"
        MarketStructure.INSUFFICIENT_DATA -> "dados insuficientes"
        MarketStructure.INVALID_DATA -> "dados inválidos"
    }

    private fun pct(value: Double): String = String.format(Locale.US, "%.0f%%", value * 100.0)
    private fun price(value: Double): String = java.math.BigDecimal(value).round(java.math.MathContext(8)).stripTrailingZeros().toPlainString()
}
