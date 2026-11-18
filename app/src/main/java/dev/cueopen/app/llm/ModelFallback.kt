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

    /**
     * Cadeia de modelos (como os "combos" de gateways): o principal e depois os alternativos, separados por vírgula.
     * Cada item pode apontar para outro provedor conectado ("groq::llama-3.3-70b", ver ProviderRoute).
     */
    fun chain(primary: String, fallback: String): List<String> =
        (listOf(primary) + fallback.split(',').map { it.trim() }.filter { it.isNotBlank() }).distinct()

    /** Modelo a usar agora: o primeiro da cadeia que não está em espera (se todos estão, o que sai antes da espera). */
    fun resolve(primary: String, fallback: String, now: Long = System.currentTimeMillis()): String {
        val c = chain(primary, fallback)
        if (c.size == 1) return primary
        return c.firstOrNull { (states[it]?.until ?: 0L) <= now } ?: c.minByOrNull { states[it]?.until ?: 0L } ?: primary
    }

    /**
     * [model] bateu o limite: entra em espera e a cadeia passa ao próximo. Retorna false se não há outro modelo
     * para usar (sem alternativo configurado), e nesse caso nada muda.
     */
    fun onRateLimit(model: String, fallback: String, now: Long = System.currentTimeMillis(), primary: String = model): Boolean {
        val c = chain(primary, fallback)
        if (c.size == 1 || model !in c) return false
        val prev = states[model]
        val cooldown = if (prev == null) FIRST_COOLDOWN_MS else (prev.cooldown * 2).coerceAtMost(MAX_COOLDOWN_MS)
        states[model] = State(now + cooldown, cooldown)
        return resolve(primary, fallback, now) != model
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

    /** Modelos em espera agora (bateram limite) e até quando. */
    fun limited(now: Long = System.currentTimeMillis()): Map<String, Long> = states.filterValues { it.until > now }.mapValues { it.value.until }

    private val lastChosen = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * (OpenBot 0.73) O usuário/agente ESCOLHEU um modelo de novo (troca do principal): ele ganha uma tentativa
     * mesmo estando em espera por erro do provedor. Se falhar de novo, volta ao alternativo e a escada de espera
     * continua de onde estava (não recomeça em 10 min). Retorna true quando liberou a tentativa.
     */
    fun chosen(scope: String, model: String, now: Long = System.currentTimeMillis()): Boolean {
        val prev = lastChosen.put(scope, model)
        if (prev == null || prev == model) return false
        val st = states[model] ?: return false
        if (st.until <= now) return false
        states[model] = State(0L, st.cooldown)
        return true
    }

    fun reset() { states.clear(); lastChosen.clear() }
}
