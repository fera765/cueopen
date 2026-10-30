package dev.cueopen.app.assistant

import java.util.concurrent.atomic.AtomicBoolean

/** Faz com que resultado final e callbacks tardios de erro não concluam a mesma fala duas vezes. */
internal class AssistantRecognitionGate {
    private val resolved = AtomicBoolean(false)

    val isResolved: Boolean
        get() = resolved.get()

    fun resolve(): Boolean = resolved.compareAndSet(false, true)
}
