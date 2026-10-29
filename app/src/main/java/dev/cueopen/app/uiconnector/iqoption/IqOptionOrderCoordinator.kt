package dev.cueopen.app.uiconnector.iqoption

import kotlinx.coroutines.sync.Mutex

/** Serializa a reserva e o envio até o resultado do servidor; sempre libera a trava em sucesso/erro/cancelamento. */
class IqOptionOrderCoordinator {
    private val mutex = Mutex()

    suspend fun <T> runReserved(action: suspend () -> T): T {
        mutex.lock()
        try {
            return action()
        } finally {
            mutex.unlock()
        }
    }
}
