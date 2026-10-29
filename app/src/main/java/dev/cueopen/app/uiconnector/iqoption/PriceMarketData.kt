package dev.cueopen.app.uiconnector.iqoption

/** OHLCV normalizado. O consumidor garante que a vela está fechada antes de incluí-la na série. */
data class PriceBar(
    val timeSeconds: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double = 0.0,
)

data class PriceSeries(
    val asset: String,
    val timeframeSeconds: Int,
    val bars: List<PriceBar>,
)

/** Fronteira de I/O: implementação de venue entrega apenas candles já fechados e em ordem cronológica. */
fun interface PriceMarketDataSource {
    suspend fun loadClosedCandles(asset: String, timeframeSeconds: Int, limit: Int): PriceSeries
}

/** Orquestra aquisição e análise, sem depender da IQ Option, Android ou transporte de rede. */
class PriceActionPipeline(
    private val source: PriceMarketDataSource,
    private val analyzer: PriceActionAnalyzer = PriceActionAnalyzer(),
) {
    suspend fun load(asset: String, timeframeSeconds: Int, limit: Int): PriceSeries =
        source.loadClosedCandles(asset, timeframeSeconds, limit)

    suspend fun analyze(asset: String, timeframeSeconds: Int, limit: Int): PriceActionAnalysis =
        analyzer.analyze(load(asset, timeframeSeconds, limit))
}
