package dev.cueopen.app.device

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

    fun clear() {
        bySession.clear()
    }
}
