package dev.fluwy.app.device

/** Snapshots são vinculados à sessão que os leu; uma leitura de outro agente não os sobrescreve. */
internal class AccessibilitySnapshotStore<T> {
    private val bySession = mutableMapOf<String, T>()

    operator fun get(sessionId: String): T? = bySession[sessionId]

    fun put(sessionId: String, snapshot: T) {
        bySession[sessionId] = snapshot
    }

    fun remove(sessionId: String) {
        bySession.remove(sessionId)
    }

    fun removeWhere(predicate: (T) -> Boolean) {
        val iterator = bySession.iterator()
        while (iterator.hasNext()) {
            if (predicate(iterator.next().value)) iterator.remove()
        }
    }

    fun clear() {
        bySession.clear()
    }
}
