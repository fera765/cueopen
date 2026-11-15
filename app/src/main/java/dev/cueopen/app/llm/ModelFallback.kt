package dev.cueopen.app.llm

/**
 * Modelo alternativo para limite de requisições (429): quando o modelo principal (do agente ou o rápido) bate o limite,
 * as chamadas passam para o alternativo por um tempo; depois o principal é testado de novo. Se ainda estiver limitado,
 * volta ao alternativo com uma espera maior (10 → 20 → 40 → 60 min); assim que responder, segue no principal.
 */
object ModelFallback {
    const val FIRST_COOLDOWN_MS = 10 * 60_000L
    const val MAX_COOLDOWN_MS = 60 * 60_000L
    private data class State(val until: Long, val cooldown: Long)
    private val states = java.util.concurrent.ConcurrentHashMap<String, State>()

    /** Modelo a usar agora para o principal [primary]. */
    fun resolve(primary: String, fallback: String, now: Long = System.currentTimeMillis()): String {
        if (fallback.isBlank() || fallback == primary) return primary
        val st = states[primary] ?: return primary
        return if (now < st.until) fallback else primary
    }

    /** O principal bateu o limite: liga o alternativo. Retorna false se não há alternativo configurado (ou já é ele). */
    fun onRateLimit(primary: String, fallback: String, now: Long = System.currentTimeMillis()): Boolean {
        if (fallback.isBlank() || fallback == primary) return false
        val prev = states[primary]
        val cooldown = if (prev == null) FIRST_COOLDOWN_MS else (prev.cooldown * 2).coerceAtMost(MAX_COOLDOWN_MS)
        states[primary] = State(now + cooldown, cooldown)
        return true
    }

    /** O principal respondeu de novo: volta a ser usado e a escada de espera zera. Retorna true se estava em alternativo. */
    fun onSuccess(model: String, now: Long = System.currentTimeMillis()): Boolean {
        val st = states[model] ?: return false
        if (now < st.until) return false  // respondeu o alternativo, não o principal
        states.remove(model)
        return true
    }

    /** Até quando o alternativo fica no lugar do principal (null = não está). */
    fun activeUntil(primary: String, now: Long = System.currentTimeMillis()): Long? = states[primary]?.until?.takeIf { it > now }

    fun reset() = states.clear()
}
