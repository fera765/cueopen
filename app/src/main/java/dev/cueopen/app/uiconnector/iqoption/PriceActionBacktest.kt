package dev.cueopen.app.uiconnector.iqoption

/** Backtest descritivo; não modela latência, payout variável, spread, rejeições ou liquidação contratual. */
object PriceActionBacktest {
    data class Result(
        val trades: Int,
        val wins: Int,
        val losses: Int,
        val ties: Int,
        val winRate: Double,
        val breakEven: Double,
        val flatPnl: Double,
        val mmFinal: Double,
        val mmCyclesWon: Int,
        val mmCyclesLost: Int,
        val maxLossStreak: Int,
        val calls: Int,
        val puts: Int,
        val invalidResultCandles: Int = 0,
    )

    /**
     * Em cada índice usa somente candles anteriores/atuais fechados para decidir; simula a próxima vela
     * como entrada na abertura e saída no fechamento. Uma operação por vez; a vela de resultado não gera sinal.
     */
    fun run(
        bars: List<PriceBar>,
        payout: Double,
        strongOnly: Boolean,
        window: Int = 120,
        mmN: Int = 10,
        mmK: Int = 6,
        mmBank: Double = 10.0,
        timeframeSeconds: Int = 60,
    ): Result {
        val safePayout = payout.coerceIn(0.0, 1.0)
        var wins = 0
        var losses = 0
        var ties = 0
        var calls = 0
        var puts = 0
        var invalidResultCandles = 0
        var streak = 0
        var maxStreak = 0
        var flat = 0.0
        val odds = 1.0 + safePayout
        var mm = MmState(mmN.coerceIn(2, 50), mmK.coerceIn(1, mmN.coerceIn(2, 50)), mmBank.coerceAtLeast(0.0), mmBank.coerceAtLeast(0.0))
        var total = mmBank.coerceAtLeast(0.0)
        var cyclesWon = 0
        var cyclesLost = 0
        val analyzer = PriceActionAnalyzer()
        val firstIndex = maxOf(29, window.coerceAtLeast(30) - 1)
        var i = firstIndex
        while (i < bars.size - 1) {
            val start = maxOf(0, i - window.coerceAtLeast(30) + 1)
            val history = bars.subList(start, i + 1)
            val series = PriceSeries("backtest", timeframeSeconds.coerceAtLeast(1), history)
            val analysis = analyzer.analyze(series)
            val next = bars[i + 1]
            val gate = PriceActionExecutionGate.authorize(analysis, analysis.decision.name, strongOnly)
            if (gate.allowed) {
                if (!validResultCandle(bars[i], next, timeframeSeconds.coerceAtLeast(1))) {
                    invalidResultCandles++
                    i++
                    continue
                }
                if (analysis.decision == TradeDirection.CALL) calls++ else puts++
                val result = when {
                    next.close == next.open -> "tie"
                    (next.close > next.open) == (analysis.decision == TradeDirection.CALL) -> "win"
                    else -> "loss"
                }
                val stake = Masaniello.stake(mm.bank, mm.n, mm.k, odds) ?: 0.0
                when (result) {
                    "win" -> {
                        wins++
                        streak = 0
                        flat += safePayout
                        mm = mm.copy(n = mm.n - 1, k = mm.k - 1, bank = mm.bank + stake * safePayout, wins = mm.wins + 1)
                    }
                    "loss" -> {
                        losses++
                        streak++
                        maxStreak = maxOf(maxStreak, streak)
                        flat -= 1.0
                        mm = mm.copy(n = mm.n - 1, bank = mm.bank - stake, losses = mm.losses + 1)
                    }
                    else -> ties++
                }
                if (mm.k <= 0 || mm.k > mm.n || mm.bank <= 1e-9) {
                    if (mm.k <= 0) cyclesWon++ else cyclesLost++
                    total += mm.bank - mm.startBank
                    mm = MmState(mmN.coerceIn(2, 50), mmK.coerceIn(1, mmN.coerceIn(2, 50)), mmBank.coerceAtLeast(0.0), mmBank.coerceAtLeast(0.0))
                }
                i++ // não reutiliza a vela simulada como nova entrada.
            }
            i++
        }
        total += mm.bank - mm.startBank
        val decided = wins + losses
        return Result(
            trades = wins + losses + ties,
            wins = wins,
            losses = losses,
            ties = ties,
            winRate = if (decided == 0) 0.0 else wins.toDouble() / decided,
            breakEven = if (odds <= 1.0) 1.0 else 1.0 / odds,
            flatPnl = flat,
            mmFinal = total,
            mmCyclesWon = cyclesWon,
            mmCyclesLost = cyclesLost,
            maxLossStreak = maxStreak,
            calls = calls,
            puts = puts,
            invalidResultCandles = invalidResultCandles,
        )
    }

    private fun validResultCandle(signal: PriceBar, result: PriceBar, timeframeSeconds: Int): Boolean {
        val prices = listOf(result.open, result.high, result.low, result.close)
        if (prices.any { !it.isFinite() || it <= 0.0 }) return false
        if (!result.volume.isFinite() || result.volume < 0.0) return false
        if (result.low > minOf(result.open, result.close) || result.high < maxOf(result.open, result.close)) return false
        if (result.low > result.high) return false
        val expectedTimestamp = runCatching { Math.addExact(signal.timeSeconds, timeframeSeconds.toLong()) }.getOrNull()
            ?: return false
        return result.timeSeconds == expectedTimestamp
    }
}
