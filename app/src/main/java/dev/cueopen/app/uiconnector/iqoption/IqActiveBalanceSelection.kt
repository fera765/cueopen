package dev.cueopen.app.uiconnector.iqoption

/** Thread-safe account selection. Starting a switch clears authorization until the exact target is confirmed. */
class IqActiveBalanceSelection {
    private var confirmedId: Long? = null
    private var pendingId: Long? = null
    private var failedSwitch = false

    @Synchronized fun activeId(): Long? = confirmedId

    @Synchronized fun beginSwitch(targetId: Long) {
        pendingId = targetId
        confirmedId = null
    }

    /** Ignores absent IDs and stale/unrelated events while a target switch is pending. */
    @Synchronized fun observeServerConfirmation(id: Long?): Boolean {
        if (id == null) return false
        val pending = pendingId
        if (pending != null) {
            if (pending != id) return false
            confirmedId = id
            pendingId = null
            return true
        }
        if (failedSwitch) return false
        if (confirmedId != null && confirmedId != id) {
            confirmedId = null
            failedSwitch = true
            return false
        }
        confirmedId = id
        return true
    }

    /** A timeout/mismatch latches closed: late uncorrelated events cannot authorize; retry or reconnect is required. */
    @Synchronized fun finishSwitch(targetId: Long, confirmed: Boolean = false) {
        if (confirmed && confirmedId == targetId) {
            pendingId = null
            failedSwitch = false
        } else {
            pendingId = null
            confirmedId = null
            failedSwitch = true
        }
    }

    @Synchronized fun invalidate() {
        confirmedId = null
        pendingId = null
        failedSwitch = false
    }
}
