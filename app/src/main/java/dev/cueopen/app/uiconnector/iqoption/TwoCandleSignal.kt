package dev.cueopen.app.uiconnector.iqoption

/** Confirmação de dois candles fechados no único timeframe associado ao sinal. */
object TwoCandleSignal {
    val SUPPORTED_TIMEFRAMES_SECONDS = setOf(60, 120, 300, 600)

    data class Analysis(
        val timeframeSeconds: Int,
        val candleOpens: List<Long>,
        val direction: TradeDirection,
        val allowed: Boolean,
        val reason: String,
    )

    /**
     * Confirma os dois candles consecutivos mais recentes do [timeframeSeconds], fechados até [cutoffSec]
     * e anteriores à abertura-alvo [targetOpenSec]. Não consulta nem exige concordância de outros timeframes.
     */
    fun analyze(
        bars: List<PriceBar>,
        targetOpenSec: Long,
        cutoffSec: Long,
        timeframeSeconds: Int,
    ): Analysis {
        if (timeframeSeconds !in SUPPORTED_TIMEFRAMES_SECONDS)
            return denied(timeframeSeconds, "timeframe não suportado; escolha 60, 120, 300 ou 600 segundos.")
        if (targetOpenSec % timeframeSeconds != 0L)
            return denied(timeframeSeconds, "o candle-alvo não está alinhado ao timeframe selecionado.")
        if (cutoffSec > targetOpenSec)
            return denied(timeframeSeconds, "o corte de dados passou do candle-alvo; look-ahead bloqueado.")

        val closed = bars.asSequence()
            .filter { it.timeSeconds + timeframeSeconds <= cutoffSec && it.timeSeconds + timeframeSeconds <= targetOpenSec }
            .sortedBy { it.timeSeconds }
            .toList()
        if (closed.any { !it.isValid() }) return denied(timeframeSeconds, "há OHLC inválido em candle fechado.")
        if (closed.map { it.timeSeconds }.distinct().size != closed.size) return denied(timeframeSeconds, "há candles fechados duplicados.")
        if (closed.any { it.timeSeconds % timeframeSeconds != 0L }) return denied(timeframeSeconds, "timestamps de candle fora do alinhamento do timeframe.")

        val pair = closed.takeLast(2)
        if (pair.size != 2) return denied(timeframeSeconds, "faltam dois candles fechados.")
        if (pair[0].timeSeconds + timeframeSeconds != pair[1].timeSeconds)
            return denied(timeframeSeconds, "os dois candles fechados não são consecutivos.")
        val lastExpectedOpen = Math.floorDiv(minOf(cutoffSec, targetOpenSec), timeframeSeconds.toLong()) * timeframeSeconds - timeframeSeconds
        if (pair[1].timeSeconds != lastExpectedOpen)
            return denied(timeframeSeconds, "o candle fechado mais recente está ausente ou obsoleto.")

        val first = color(pair[0])
        val second = color(pair[1])
        val direction = when {
            first == 0 || second == 0 -> TradeDirection.NO_TRADE
            first == 1 && second == 1 -> TradeDirection.CALL
            first == -1 && second == -1 -> TradeDirection.PUT
            else -> TradeDirection.NO_TRADE
        }
        val allowed = direction != TradeDirection.NO_TRADE
        val label = "M${timeframeSeconds / 60}"
        val reason = when {
            first == 0 || second == 0 -> "$label: doji entre os dois candles fechados."
            direction == TradeDirection.NO_TRADE -> "$label: cores divergentes entre os dois candles fechados."
            else -> "$label: candles ${pair[0].timeSeconds} e ${pair[1].timeSeconds} fechados → ${direction.name}."
        }
        return Analysis(timeframeSeconds, pair.map { it.timeSeconds }, direction, allowed, reason)
    }

    private fun color(bar: PriceBar): Int = when {
        bar.close > bar.open -> 1
        bar.close < bar.open -> -1
        else -> 0
    }

    private fun PriceBar.isValid(): Boolean =
        listOf(open, high, low, close, volume).all { it.isFinite() } &&
            high >= maxOf(open, close) && low <= minOf(open, close) && high >= low && volume >= 0.0

    private fun denied(timeframeSeconds: Int, reason: String) =
        Analysis(timeframeSeconds, emptyList(), TradeDirection.NO_TRADE, false, reason)
}
