package dev.fluwy.app.browser

/**
 * Políticas puras (sem Android) da resiliência do browser — testadas na JVM.
 *
 * - [RendererRecoveryPolicy]: anti-loop da recuperação automática quando o renderer morre ou trava.
 * - [MemoryPressurePolicy]: quais abas estacionar quando o sistema pede memória.
 */
class RendererRecoveryPolicy(
    private val maxPerTab: Int = 2,
    private val maxGlobal: Int = 6,
    private val windowMs: Long = 5 * 60_000L,
) {
    enum class Decision { RECOVER_AND_RETRY, RECOVER_ONLY, GIVE_UP }

    private val perTab = HashMap<String, ArrayDeque<Long>>()
    private val global = ArrayDeque<Long>()

    /**
     * Registra uma falha da aba [tabId] e decide: recriar e repetir a navegação pendente (1ª falha
     * na janela), só recriar sem repetir (2ª) ou desistir (loop: a página derruba o motor sempre).
     */
    @Synchronized
    fun onFailure(tabId: String, now: Long): Decision {
        val q = perTab.getOrPut(tabId) { ArrayDeque() }
        prune(q, now); prune(global, now)
        q.addLast(now); global.addLast(now)
        return when {
            q.size > maxPerTab || global.size > maxGlobal -> Decision.GIVE_UP
            q.size == 1 -> Decision.RECOVER_AND_RETRY
            else -> Decision.RECOVER_ONLY
        }
    }

    /** Navegação nova pedida pelo agente: zera o histórico da aba (uma página nova merece nova chance). */
    @Synchronized
    fun reset(tabId: String) { perTab.remove(tabId) }

    @Synchronized
    fun failuresInWindow(tabId: String, now: Long): Int = perTab[tabId]?.also { prune(it, now) }?.size ?: 0

    private fun prune(q: ArrayDeque<Long>, now: Long) { while (q.isNotEmpty() && now - q.first() > windowMs) q.removeFirst() }
}

object MemoryPressurePolicy {
    /** Níveis de ComponentCallbacks2 (copiados como literais para o teste JVM não depender do Android). */
    const val RUNNING_MODERATE = 5
    const val RUNNING_LOW = 10
    const val RUNNING_CRITICAL = 15
    const val UI_HIDDEN = 20
    const val BACKGROUND = 40
    const val MODERATE = 60
    const val COMPLETE = 80
    /** onLowMemory() tratado como o nível mais grave. */
    const val LOW_MEMORY = COMPLETE

    data class TabState(
        val id: String,
        val live: Boolean,
        val active: Boolean,
        /** Na tela do usuário (anexado a janela e fora do palco invisível). */
        val visible: Boolean,
        /** Agente aguardando esta aba (navegação/eval pendente). */
        val busy: Boolean,
        val lastUsed: Long,
    )

    /** Quantas abas vivas extras (além das protegidas) podem continuar com o motor carregado. */
    fun extraLiveAllowed(level: Int): Int = when {
        level >= MODERATE || level == RUNNING_CRITICAL -> 0
        level >= BACKGROUND || level == RUNNING_LOW -> 1
        else -> Int.MAX_VALUE
    }

    /** Pausa (onPause) abas ocultas não protegidas a partir deste nível. */
    fun shouldPauseHidden(level: Int): Boolean = level >= RUNNING_LOW && level != UI_HIDDEN

    /** Limpa o cache em RAM do WebView só nos níveis graves. */
    fun shouldClearCache(level: Int): Boolean = level == RUNNING_CRITICAL || level >= MODERATE

    /**
     * Abas a estacionar: nunca a ativa, a visível ou a que o agente está usando. Abas ociosas
     * (sem uso há [idleMs]) saem já no nível moderado; nos níveis graves sobra no máximo [extraLiveAllowed].
     */
    fun tabsToPark(tabs: List<TabState>, level: Int, now: Long, idleMs: Long = 60_000L): List<String> {
        if (level == UI_HIDDEN) return emptyList()
        val candidates = tabs.filter { it.live && !it.active && !it.visible && !it.busy }.sortedBy { it.lastUsed }
        val idle = candidates.filter { now - it.lastUsed > idleMs }.map { it.id }.toSet()
        val keep = extraLiveAllowed(level)
        val overflow = if (keep == Int.MAX_VALUE) emptyList() else candidates.dropLast(keep.coerceAtMost(candidates.size)).map { it.id }
        return candidates.map { it.id }.filter { it in idle || it in overflow }
    }
}
