package dev.fluwy.app.browser

/**
 * Regras puras (testáveis na JVM) da frota de WebViews do app inteiro.
 *
 * Todos os WebViews do app dividem UM processo de renderer: quando o Android o mata por falta de memória,
 * todas as abas de todos os chats caem juntas. Por isso a memória é controlada globalmente, não por chat:
 * orçamento total de WebViews vivos, reciclagem de páginas que ficaram horas abertas e modo de pouca memória
 * depois de mortes repetidas do renderer.
 */
object BrowserFleetPolicy {
    data class Tab(
        val hostId: String,
        val tabId: String,
        val live: Boolean,
        /** Na tela do usuário (não estacionar nunca). */
        val visible: Boolean,
        /** Navegação ou script pendente. */
        val busy: Boolean,
        /** Aba ativa de um chat cujo agente está executando. */
        val inUse: Boolean,
        /** Precisa ficar viva para ouvir eventos (ex.: WhatsApp Web do webhook). */
        val pinned: Boolean,
        val lastUsed: Long,
        val liveSince: Long,
    )

    data class Limits(val budget: Int, val idleMs: Long, val recycleAfterMs: Long, val recycleIdleMs: Long)

    val NORMAL = Limits(budget = 3, idleMs = 10 * MIN, recycleAfterMs = 2 * HOUR, recycleIdleMs = 2 * MIN)
    val LOW_MEMORY = Limits(budget = 2, idleMs = 3 * MIN, recycleAfterMs = 45 * MIN, recycleIdleMs = 1 * MIN)

    /** Abas a estacionar agora (o histórico é salvo; a aba volta ao ser usada). */
    fun toPark(tabs: List<Tab>, now: Long, limits: Limits): List<Tab> {
        val candidates = tabs.filter { it.live && !it.visible && !it.busy && !it.inUse }
        val park = LinkedHashSet<Tab>()
        candidates.filter { !it.pinned && now - it.lastUsed > limits.idleMs }.forEach { park += it }
        candidates.filter { now - it.liveSince > limits.recycleAfterMs && now - it.lastUsed > limits.recycleIdleMs }.forEach { park += it }
        val stillLive = tabs.count { it.live } - park.size
        if (stillLive > limits.budget) {
            candidates.filter { it !in park }
                .sortedWith(compareBy<Tab> { it.pinned }.thenBy { it.lastUsed })
                .take(stillLive - limits.budget)
                .forEach { park += it }
        }
        return park.toList()
    }

    /**
     * Mortes do renderer: várias abas morrendo no mesmo instante são UM evento (processo compartilhado).
     * Dois eventos em [window] ligam o modo de pouca memória.
     */
    class DeathTracker(private val groupMs: Long = 5_000, private val window: Long = 30 * MIN, private val threshold: Int = 2) {
        private val events = ArrayDeque<Long>()

        /** Registra uma morte; devolve true se é um evento novo (não parte de uma queda simultânea já contada). */
        fun record(now: Long): Boolean {
            val last = events.lastOrNull()
            if (last != null && now - last < groupMs) return false
            events.addLast(now)
            while (events.isNotEmpty() && now - events.first() > window) events.removeFirst()
            return true
        }

        fun lowMemory(now: Long): Boolean = events.count { now - it <= window } >= threshold
    }

    /** Espaça a recriação de abas depois de uma queda, para não gerar um novo pico de memória. */
    class RecoveryStagger(private val gapMs: Long = 1_500) {
        private var nextAt = 0L
        fun delayFor(now: Long): Long {
            val at = maxOf(now, nextAt)
            nextAt = at + gapMs
            return at - now
        }
    }

    private const val MIN = 60_000L
    private const val HOUR = 60 * MIN
}
