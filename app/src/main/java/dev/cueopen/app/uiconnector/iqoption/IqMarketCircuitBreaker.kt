package dev.cueopen.app.uiconnector.iqoption

/** Circuit breaker em tempo monotônico: falha não prova que o mercado não seja suportado. */
class IqMarketCircuitBreaker(
    private val baseDelayMs: Long = 10 * 60_000L,
    private val maxDelayMs: Long = 6 * 60 * 60_000L,
) {
    data class State(val failures: Int, val retryAtElapsedMs: Long, val reason: String, val lastLoggedAtElapsedMs: Long?)
    data class FailureDecision(val state: State, val shouldLog: Boolean)

    private val lock = Any()
    private val states = mutableMapOf<String, State>()
    private var contextGeneration = 0L

    fun contextGeneration(): Long = synchronized(lock) { contextGeneration }

    fun isOpen(category: String, nowElapsedMs: Long): Boolean = synchronized(lock) {
        states[category]?.let { nowElapsedMs < it.retryAtElapsedMs } ?: false
    }

    fun retryAt(category: String): Long? = synchronized(lock) { states[category]?.retryAtElapsedMs }
    fun state(category: String): State? = synchronized(lock) { states[category] }

    fun recordFailure(category: String, nowElapsedMs: Long, reason: String): FailureDecision = synchronized(lock) {
        val previous = states[category]
        val failures = (previous?.failures ?: 0) + 1
        val exponent = (failures - 1).coerceAtMost(30)
        val delay = runCatching { Math.multiplyExact(baseDelayMs, 1L shl exponent) }.getOrDefault(maxDelayMs).coerceAtMost(maxDelayMs)
        val shouldLog = previous == null || nowElapsedMs >= previous.retryAtElapsedMs
        val updated = State(
            failures = failures,
            retryAtElapsedMs = nowElapsedMs + delay,
            reason = reason,
            lastLoggedAtElapsedMs = if (shouldLog) nowElapsedMs else previous?.lastLoggedAtElapsedMs,
        )
        states[category] = updated
        FailureDecision(updated, shouldLog)
    }

    /** Só uma resposta não vazia e estruturalmente válida limpa a falha; vazia é inconclusiva. */
    fun recordResponse(category: String, nowElapsedMs: Long, returnedAssets: Int): FailureDecision? {
        if (returnedAssets > 0) {
            synchronized(lock) { states.remove(category) }
            return null
        }
        return recordFailure(category, nowElapsedMs, "empty/unknown")
    }

    /** Login/reconexão explícita, logout ou troca de conta confirmada invalida circuitos antigos. */
    fun resetContext() = synchronized(lock) {
        contextGeneration++
        states.clear()
    }

    fun activeStatuses(nowElapsedMs: Long): Map<String, State> = synchronized(lock) {
        states.filterValues { it.retryAtElapsedMs > nowElapsedMs }.toMap()
    }
}
