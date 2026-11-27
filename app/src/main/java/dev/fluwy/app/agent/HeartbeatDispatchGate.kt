package dev.fluwy.app.agent

/** Serializa a verificação de execução ativa e o registro do próximo ciclo. */
internal class HeartbeatDispatchGate {
    private val lock = Any()

    fun <T> dispatchIfIdle(isBusy: () -> Boolean, dispatch: () -> T): T? = synchronized(lock) {
        if (isBusy()) null else dispatch()
    }
}
