package dev.cueopen.app.uiconnector.iqoption

/** Regras de tempo do sinal em epoch do servidor; não usa timezone local nem arredonda o alvo para trás. */
object IqSignalTiming {
    const val CANDLE_SECONDS = 60L
    const val ANALYSIS_LEAD_SECONDS = 120L
    /** Primeira revalidação tem margem para I/O; os retries ficam em T−25 e T−10. */
    const val REVALIDATION_LEAD_SECONDS = 50L
    const val PRECONNECT_LEAD_SECONDS = 90L
    const val FINAL_ENTRY_LEAD_SECONDS = 10L
    val REVALIDATION_RETRY_LEADS_SECONDS = listOf(25L, FINAL_ENTRY_LEAD_SECONDS)
    val REVALIDATION_STAGE_LEADS_SECONDS = listOf(REVALIDATION_LEAD_SECONDS) + REVALIDATION_RETRY_LEADS_SECONDS
    private const val MILLIS_PER_SECOND = 1_000L

    /** Próxima abertura alinhada ao timeframe escolhido e pelo menos [leadSeconds] à frente. */
    fun nextOpenAtLeastLead(
        nowServerMs: Long,
        leadSeconds: Long = ANALYSIS_LEAD_SECONDS,
        timeframeSeconds: Int = CANDLE_SECONDS.toInt(),
    ): Long {
        require(nowServerMs >= 0L) { "nowServerMs deve ser epoch não negativo" }
        require(leadSeconds > 0L) { "leadSeconds deve ser positivo" }
        require(timeframeSeconds > 0) { "timeframeSeconds deve ser positivo" }
        val frameMs = Math.multiplyExact(timeframeSeconds.toLong(), MILLIS_PER_SECOND)
        val earliestMs = Math.addExact(nowServerMs, Math.multiplyExact(leadSeconds, MILLIS_PER_SECOND))
        val openFrame = Math.floorDiv(Math.addExact(earliestMs, frameMs - 1L), frameMs)
        return Math.multiplyExact(openFrame, timeframeSeconds.toLong())
    }

    fun analysisAtMs(targetOpenSec: Long, leadSeconds: Long = ANALYSIS_LEAD_SECONDS): Long =
        Math.subtractExact(Math.multiplyExact(targetOpenSec, MILLIS_PER_SECOND), leadSeconds * MILLIS_PER_SECOND)

    fun revalidateAtMs(targetOpenSec: Long, leadSeconds: Long = REVALIDATION_LEAD_SECONDS): Long =
        Math.subtractExact(Math.multiplyExact(targetOpenSec, MILLIS_PER_SECOND), leadSeconds * MILLIS_PER_SECOND)

    fun revalidationStageAtMs(targetOpenSec: Long, stage: Int): Long {
        val lead = REVALIDATION_STAGE_LEADS_SECONDS.getOrNull(stage)
            ?: throw IllegalArgumentException("etapa de revalidação inválida: $stage")
        return revalidateAtMs(targetOpenSec, lead)
    }

    /** Próxima etapa ainda futura; o tempo local pode escolher apenas quando reagendar, nunca autorizar uma ordem. */
    fun nextRevalidationStageAfter(targetOpenSec: Long, firstStage: Int, nowEpochMs: Long): Int? =
        (firstStage.coerceAtLeast(0) until REVALIDATION_STAGE_LEADS_SECONDS.size)
            .firstOrNull { revalidationStageAtMs(targetOpenSec, it) > nowEpochMs }

    /** Acorda o serviço antes de T−50 para aquecer/reconectar a sessão sem envolver o agente. */
    fun preconnectAtMs(targetOpenSec: Long, leadSeconds: Long = PRECONNECT_LEAD_SECONDS): Long =
        Math.subtractExact(Math.multiplyExact(targetOpenSec, MILLIS_PER_SECOND), leadSeconds * MILLIS_PER_SECOND)

    /** A vela mais recente que já deveria estar fechada no instante informado. */
    fun latestClosedCandleOpen(nowServerSec: Long, timeframeSeconds: Long = CANDLE_SECONDS): Long {
        require(timeframeSeconds > 0L) { "timeframeSeconds deve ser positivo" }
        return Math.floorDiv(nowServerSec, timeframeSeconds) * timeframeSeconds - timeframeSeconds
    }

    fun isWithinEntryWindow(nowServerMs: Long, targetOpenSec: Long, maxDelaySeconds: Long): Boolean {
        if (maxDelaySeconds < 0L) return false
        val deltaMs = nowServerMs - targetOpenSec * MILLIS_PER_SECOND
        return deltaMs >= 0L && deltaMs <= maxDelaySeconds * MILLIS_PER_SECOND
    }

    fun isExpectedCandle(candleOpenSec: Long, targetOpenSec: Long): Boolean =
        candleOpenSec == targetOpenSec
}
