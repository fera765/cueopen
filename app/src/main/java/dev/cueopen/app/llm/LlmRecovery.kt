package dev.cueopen.app.llm

import java.util.concurrent.ConcurrentHashMap

/**
 * Recuperação de erros do provedor de modelos (puro, testável):
 *  - [classify]: cada erro vira uma categoria com estratégia própria (repetir, esperar, ou parar na hora);
 *  - [CircuitBreaker]: provedor instável deixa de receber chamadas por uma janela (CLOSED → OPEN → HALF_OPEN),
 *    compartilhado por todos os agentes — ninguém agrava um rate limit repetindo junto;
 *  - retomada de longo prazo: [AUTO_RESUME_MINUTES] min depois da suspensão, até [AUTO_RESUME_ROUNDS] vezes.
 */
object LlmRecovery {
    enum class Category(val label: String, val permanent: Boolean, val maxAttempts: Int, val advice: String) {
        RATE_LIMIT("limite de requisições (429)", false, 3, "O provedor está limitando as chamadas. O app espera o tempo pedido e tenta de novo."),
        SERVER("falha interna do provedor (5xx)", false, 3, "O provedor está instável. Costuma voltar sozinho em minutos."),
        TIMEOUT("tempo esgotado", false, 3, "O provedor demorou demais para responder."),
        NETWORK("falha de conexão/DNS", false, 3, "Sem conexão com o provedor. Confira a internet do aparelho."),
        STREAM("resposta incompleta", false, 3, "A resposta veio cortada; o app repete com mais espaço de resposta."),
        FORMAT("erro de formato da requisição", false, 2, "O provedor recusou o formato. Se persistir, troque de modelo (nem todos aceitam ferramentas)."),
        CONTEXT("limite de contexto do modelo", false, 1, "A conversa ficou maior que o modelo aceita. O app compacta o histórico e continua."),
        AUTH("chave de API inválida ou sem permissão", true, 1, "Confira a Chave da API em Configurações. Repetir não resolve."),
        CREDITS("créditos insuficientes", true, 1, "Adicione créditos no provedor ou troque de modelo/provedor."),
        MODEL_NOT_FOUND("modelo inexistente ou indisponível", true, 1, "Escolha outro modelo em Configurações."),
        ENDPOINT("endpoint (Base URL) inválido", true, 1, "Confira a Base URL em Configurações."),
        UNKNOWN("erro não classificado", false, 2, "Erro inesperado do provedor."),
    }

    const val AUTO_RESUME_MINUTES = 20
    const val AUTO_RESUME_ROUNDS = 3

    private val httpCode = Regex("\\bHTTP (\\d{3})\\b")
    private val transient = Regex("(?i)upstream|overloaded|temporarily|try again|unavailable|capacity|no endpoints? (found|available)|provider returned error")

    fun classify(message: String?, retryable: Boolean, truncated: Boolean = false): Category {
        val m = message.orEmpty()
        val low = m.lowercase()
        val code = httpCode.find(m)?.groupValues?.get(1)?.toIntOrNull()
        return when {
            truncated -> Category.STREAM
            Regex("context.?length|maximum context|context window|too many tokens|prompt is too long|reduce the length").containsMatchIn(low) -> Category.CONTEXT
            code == 429 || Regex("rate.?limit|too many requests").containsMatchIn(low) -> Category.RATE_LIMIT
            code == 402 || Regex("insufficient (credits|funds|balance|quota)|quota exceeded|billing|payment required").containsMatchIn(low) -> Category.CREDITS
            code == 401 || Regex("invalid api key|incorrect api key|unauthorized|no auth credentials|authentication").containsMatchIn(low) -> Category.AUTH
            code == 403 && !transient.containsMatchIn(m) -> Category.AUTH
            code == 404 && transient.containsMatchIn(m) -> Category.SERVER
            code == 404 || Regex("model.{0,40}(not found|does not exist|not available|no longer)|unknown model|is not a valid model").containsMatchIn(low) ->
                if (low.contains("model")) Category.MODEL_NOT_FOUND else Category.ENDPOINT
            low.contains("base url") || low.contains("use https") || Regex("unknownhost|unable to resolve host|no address associated").containsMatchIn(low) && low.contains("base") -> Category.ENDPOINT
            code == 408 || code == 504 || Regex("timed? ?out|timeout").containsMatchIn(low) -> Category.TIMEOUT
            code != null && code in 500..599 -> Category.SERVER
            Regex("falha de comunicação|unknownhost|unable to resolve|connection (reset|refused|closed)|network|socket|ssl|eof").containsMatchIn(low) -> Category.NETWORK
            code == 400 || code == 422 -> if (transient.containsMatchIn(m)) Category.SERVER else Category.FORMAT
            retryable -> Category.SERVER
            else -> Category.UNKNOWN
        }
    }

    /** Pode tentar de novo agora? (permanentes nunca; cada categoria tem o seu teto) */
    fun shouldRetry(category: Category, attempt: Int, retryable: Boolean): Boolean =
        !category.permanent && attempt < category.maxAttempts && (retryable || category == Category.UNKNOWN || category == Category.FORMAT)

    /** Backoff com jitter (até +25%) para vários agentes não repetirem no mesmo instante. */
    fun withJitter(delayMillis: Long, random01: Double): Long = delayMillis + (delayMillis * 0.25 * random01.coerceIn(0.0, 1.0)).toLong()

    /** Retomada automática após suspensão: 20 min, até 3 rodadas; nunca para erro permanente. */
    fun autoResumeDelayMillis(round: Int, category: Category): Long? =
        if (category.permanent || round !in 1..AUTO_RESUME_ROUNDS) null else AUTO_RESUME_MINUTES * 60_000L

    /** Sem credenciais: só host do endpoint. */
    fun providerLabel(baseUrl: String): String =
        baseUrl.substringAfter("://", baseUrl).substringBefore('/').substringAfterLast('@').ifBlank { "(sem endpoint)" }

    class CircuitBreaker(private val threshold: Int = 4, private val baseOpenMillis: Long = 60_000, private val maxOpenMillis: Long = 600_000) {
        enum class State { CLOSED, OPEN, HALF_OPEN }
        private var failures = 0
        private var trips = 0
        private var openUntil = 0L
        private var probing = false
        var state = State.CLOSED
            private set

        /** 0 = pode chamar agora; senão, quanto esperar. Em HALF_OPEN só uma chamada de teste passa. */
        @Synchronized fun acquire(now: Long): Long = when (state) {
            State.CLOSED -> (openUntil - now).coerceAtLeast(0)   // cooldown de rate limit compartilhado
            State.OPEN -> if (now >= openUntil) { state = State.HALF_OPEN; probing = true; 0 } else openUntil - now
            State.HALF_OPEN -> if (probing) 2_000 else { probing = true; 0 }
        }

        @Synchronized fun onSuccess() { failures = 0; trips = 0; probing = false; openUntil = 0; state = State.CLOSED }

        @Synchronized fun onFailure(now: Long, retryAfterMillis: Long? = null) {
            probing = false
            if (state == State.HALF_OPEN) { trip(now); return }
            failures++
            // Rate limit com Retry-After: todos os agentes esperam o mesmo prazo, sem abrir o circuito.
            if (retryAfterMillis != null) openUntil = maxOf(openUntil, now + retryAfterMillis.coerceAtMost(maxOpenMillis))
            if (failures >= threshold) trip(now)
        }

        private fun trip(now: Long) {
            val window = (baseOpenMillis shl trips.coerceAtMost(6)).coerceAtMost(maxOpenMillis)
            trips++; failures = 0; state = State.OPEN; openUntil = maxOf(openUntil, now + window)
        }
    }

    private val breakers = ConcurrentHashMap<String, CircuitBreaker>()
    /** Um circuito por provedor+modelo. */
    fun breaker(baseUrl: String, model: String): CircuitBreaker = breakers.getOrPut(providerLabel(baseUrl) + "|" + model) { CircuitBreaker() }
}
