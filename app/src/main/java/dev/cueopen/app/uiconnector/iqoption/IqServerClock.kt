package dev.cueopen.app.uiconnector.iqoption

/** Server-time anchor; freshness is measured with monotonic elapsed time, not wall-clock changes. */
class IqServerClock(private val maxAgeMs: Long = 30_000L) {
    private var epochAtSyncMs = 0L
    private var elapsedAtSyncMs = 0L

    @Synchronized fun update(serverEpochMs: Long, elapsedRealtimeMs: Long): Boolean {
        if (serverEpochMs <= 0L || elapsedRealtimeMs < 0L) return false
        epochAtSyncMs = serverEpochMs
        elapsedAtSyncMs = elapsedRealtimeMs
        return true
    }

    @Synchronized fun nowMs(elapsedRealtimeMs: Long): Long? {
        val age = elapsedRealtimeMs - elapsedAtSyncMs
        if (epochAtSyncMs <= 0L || elapsedAtSyncMs <= 0L || age !in 0..maxAgeMs) return null
        return epochAtSyncMs + age
    }

    fun nowSec(elapsedRealtimeMs: Long): Long? = nowMs(elapsedRealtimeMs)?.div(1000L)

    @Synchronized fun clear() {
        epochAtSyncMs = 0L
        elapsedAtSyncMs = 0L
    }
}
