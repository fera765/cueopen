package dev.cueopen.app.uiconnector.iqoption

/**
 * State reducer for the app-selected wallet. Server events can confirm a wallet; a local selection
 * may select only a balance already present in the current authenticated profile.
 */
class IqActiveBalanceSelection {
    private var generation: Long = 0L
    private var confirmedId: Long? = null
    private var pendingId: Long? = null
    private var failedTargetId: Long? = null
    private var failedMessage: String? = null
    private var awaitingInitialConfirmation = false

    @Synchronized fun activeId(): Long? = confirmedId
    @Synchronized fun switchPending(): Boolean = pendingId != null
    @Synchronized fun switchFailed(): Boolean = failedMessage != null
    @Synchronized fun failureMessage(): String? = failedMessage
    @Synchronized fun requestedTargetId(): Long? = pendingId ?: failedTargetId
    @Synchronized fun currentGeneration(): Long = generation

    /** Called immediately before opening a new authenticated socket. Old-generation callbacks are ignored. */
    @Synchronized fun beginConnection(newGeneration: Long) {
        generation = newGeneration
        confirmedId = null
        pendingId = null
        failedTargetId = null
        failedMessage = null
        awaitingInitialConfirmation = true
    }

    @Synchronized fun beginSwitch(targetId: Long) = beginSwitch(targetId, generation)

    @Synchronized fun beginSwitch(targetId: Long, eventGeneration: Long) {
        if (eventGeneration != generation) return
        pendingId = targetId
        confirmedId = null
        failedTargetId = null
        failedMessage = null
        awaitingInitialConfirmation = false
    }

    /** Select a profile-known wallet locally; order/query callers must still pass its balance ID. */
    @Synchronized fun selectKnownBalanceLocally(targetId: Long, eventGeneration: Long): Boolean {
        if (targetId <= 0L || eventGeneration != generation || confirmedId == null || awaitingInitialConfirmation) return false
        confirmedId = targetId
        pendingId = null
        failedTargetId = null
        failedMessage = null
        awaitingInitialConfirmation = false
        return true
    }

    /**
     * Accepts only an explicit ID from the current socket. While switching, unrelated profiles only
     * update the caller's balance cache; they cannot change authorization. After confirmation, stale
     * events for another wallet are ignored rather than clearing the active wallet.
     */
    @Synchronized fun observeServerConfirmation(id: Long?): Boolean = observeServerConfirmation(id, generation)

    @Synchronized fun observeServerConfirmation(id: Long?, eventGeneration: Long): Boolean {
        if (id == null || eventGeneration != generation) return false
        val pending = pendingId
        if (pending != null) {
            if (pending != id) return false
            confirmedId = id
            pendingId = null
            failedTargetId = null
            failedMessage = null
            awaitingInitialConfirmation = false
            return true
        }
        if (failedMessage != null) {
            // A late, exact ACK on the same socket is still server confirmation of the requested ID.
            if (failedTargetId != id) return false
            confirmedId = id
            failedTargetId = null
            failedMessage = null
            return true
        }
        if (confirmedId != null) return confirmedId == id
        if (!awaitingInitialConfirmation) return false
        confirmedId = id
        awaitingInitialConfirmation = false
        return true
    }

    /** Latches unknown after a timeout/mismatch; no automatic reconnect may silently select the prior wallet. */
    @Synchronized fun finishSwitch(targetId: Long, confirmed: Boolean = false, reason: String? = null) {
        if (confirmed && confirmedId == targetId) {
            pendingId = null
            failedTargetId = null
            failedMessage = null
            awaitingInitialConfirmation = false
        } else {
            pendingId = null
            confirmedId = null
            failedTargetId = targetId
            failedMessage = reason ?: "A carteira solicitada não foi confirmada pelo servidor."
            awaitingInitialConfirmation = false
        }
    }

    /** Failed explicit resynchronization must also remain latched closed. */
    @Synchronized fun failReconciliation(reason: String) {
        pendingId = null
        confirmedId = null
        failedTargetId = null
        failedMessage = reason
        awaitingInitialConfirmation = false
    }

    @Synchronized fun invalidate() {
        confirmedId = null
        pendingId = null
        failedTargetId = null
        failedMessage = null
        awaitingInitialConfirmation = false
    }
}
