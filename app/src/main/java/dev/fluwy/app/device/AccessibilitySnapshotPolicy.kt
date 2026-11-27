package dev.fluwy.app.device

/** Decisões sem Android para manter IDs válidos só enquanto sua janela/contexto ainda corresponde. */
internal object AccessibilitySnapshotPolicy {
    fun keepAfterWindowEvent(
        snapshotPackage: String,
        snapshotWindowId: Int,
        eventPackage: String,
        eventWindowId: Int,
    ): Boolean {
        if (eventPackage.isBlank()) return true
        if (snapshotPackage != eventPackage) return false
        return eventWindowId < 0 || snapshotWindowId < 0 || snapshotWindowId == eventWindowId
    }

    fun isExpired(createdAt: Long, now: Long, ttl: Long): Boolean =
        now < createdAt || now - createdAt > ttl

    fun supportsDisplayScreenshot(apiLevel: Int): Boolean = apiLevel >= 30
}
