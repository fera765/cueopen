package dev.fluwy.app.uiconnector.iqoption

/** Client-side refresh interval for Forex position snapshots; it does not alter broker-side SL/TP. */
object IqForexMonitoringInterval {
    const val DEFAULT_MS = 30_000L
    const val MIN_SECONDS = 5
    const val MAX_SECONDS = 300

    fun milliseconds(seconds: Double?): Long {
        val valid = seconds?.takeIf { it.isFinite() } ?: (DEFAULT_MS / 1000.0)
        return valid.toInt().coerceIn(MIN_SECONDS, MAX_SECONDS) * 1_000L
    }
}
