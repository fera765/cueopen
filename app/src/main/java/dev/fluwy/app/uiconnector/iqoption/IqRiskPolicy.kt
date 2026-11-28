package dev.fluwy.app.uiconnector.iqoption

data class IqRiskLimits(
    val dailyStopLoss: Double,
    val dailyTakeProfit: Double,
    val maxConsecutiveLosses: Int,
    val cooldownMillis: Long,
)

enum class IqRiskBlock { PAUSED, DAILY_STOP_LOSS, DAILY_TAKE_PROFIT, CONSECUTIVE_LOSSES }

data class IqRiskDecision(
    val updatedState: RiskState,
    val block: IqRiskBlock?,
    val pauseUntilMillis: Long? = null,
    val consecutiveLossesAtBlock: Int? = null,
)

/** Monetary risk policy without Android, clock reads, persistence, or order execution. */
object IqRiskPolicy {
    fun evaluate(state: RiskState, nowMillis: Long, limits: IqRiskLimits): IqRiskDecision {
        if (state.pausedUntil > nowMillis) return IqRiskDecision(state, IqRiskBlock.PAUSED, state.pausedUntil)
        if (limits.dailyStopLoss > 0.0 && state.dayPnl <= -limits.dailyStopLoss)
            return IqRiskDecision(state, IqRiskBlock.DAILY_STOP_LOSS)
        if (limits.dailyTakeProfit > 0.0 && state.dayPnl >= limits.dailyTakeProfit)
            return IqRiskDecision(state, IqRiskBlock.DAILY_TAKE_PROFIT)
        if (limits.maxConsecutiveLosses > 0 && state.consecLosses >= limits.maxConsecutiveLosses) {
            val until = nowMillis + limits.cooldownMillis.coerceAtLeast(0L)
            return IqRiskDecision(
                updatedState = state.copy(pausedUntil = until, consecLosses = 0),
                block = IqRiskBlock.CONSECUTIVE_LOSSES,
                pauseUntilMillis = until,
                consecutiveLossesAtBlock = state.consecLosses,
            )
        }
        return IqRiskDecision(state, null)
    }
}
