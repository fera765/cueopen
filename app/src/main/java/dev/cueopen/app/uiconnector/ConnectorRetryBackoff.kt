package dev.cueopen.app.uiconnector

/** Backoff monotônico para evitar tentativas de rede em série durante indisponibilidade do provedor. */
class ConnectorRetryBackoff(
    private val baseDelayMs: Long = 30_000L,
    private val maxDelayMs: Long = 5 * 60_000L,
) {
    data class State(val failures: Int, val retryAtElapsedMs: Long, val reason: String)

    private val lock = Any()
    private val states = mutableMapOf<String, State>()

    init {
        require(baseDelayMs > 0L)
        require(maxDelayMs >= baseDelayMs)
    }

    fun recordFailure(key: String, nowElapsedMs: Long, reason: String): State = synchronized(lock) {
        require(key.isNotBlank())
        require(nowElapsedMs >= 0L)
        val failures = (states[key]?.failures ?: 0) + 1
        val exponent = (failures - 1).coerceAtMost(30)
        val delay = runCatching { Math.multiplyExact(baseDelayMs, 1L shl exponent) }
            .getOrDefault(maxDelayMs)
            .coerceAtMost(maxDelayMs)
        val retryAt = if (nowElapsedMs > Long.MAX_VALUE - delay) Long.MAX_VALUE else nowElapsedMs + delay
        State(failures, retryAt, reason.take(180)).also { states[key] = it }
    }

    fun state(key: String): State? = synchronized(lock) { states[key] }

    fun remainingMs(key: String, nowElapsedMs: Long): Long = synchronized(lock) {
        (states[key]?.retryAtElapsedMs?.minus(nowElapsedMs) ?: 0L).coerceAtLeast(0L)
    }

    fun reset(key: String) = synchronized(lock) { states.remove(key); Unit }
}

/** Resultado de cooldown automático; pode ser mostrado ao agente sem logar a cada ferramenta repetida. */
class ConnectorRetryDeferredException(message: String) : IllegalStateException(message)
