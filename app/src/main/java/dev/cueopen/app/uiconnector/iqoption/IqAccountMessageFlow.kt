package dev.cueopen.app.uiconnector.iqoption

import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Account-message state and waiter routing shared by the live connector and deterministic fake-
 * transport tests. Login is confirmed by the current profile; local account selection is restricted
 * to a balance ID already present in that profile. Unrelated replies cannot confirm or roll it back.
 */
internal class IqAccountMessageFlow {
    internal data class Waiter(
        val requestId: String?,
        val names: Set<String>,
        val deferred: CompletableDeferred<JsonObject>,
        val requiresActiveBalance: Boolean = false,
        val targetBalanceId: Long? = null,
    )

    internal data class AccountUpdate(
        val eventName: String,
        val serverBalanceId: Long?,
        val accepted: Boolean,
        val activeBalanceId: Long?,
        val stateChanged: Boolean,
    )

    val selection = IqActiveBalanceSelection()
    val balances = ConcurrentHashMap<Long, IqProtocol.Balance>()

    @Volatile
    var authenticated: Boolean = false
        private set

    private val waiters = CopyOnWriteArrayList<Waiter>()

    fun activeBalance(): IqProtocol.Balance? = selection.activeId()?.let { balances[it] }

    /** Operations are eligible only for a known selected wallet in the authenticated profile. */
    fun orderBalance(): IqProtocol.Balance? =
        activeBalance()?.takeIf { authenticated && !selection.switchPending() && !selection.switchFailed() }

    /** Select only a wallet known in this authenticated profile; the request caller routes by ID. */
    fun selectKnownBalanceLocally(balanceId: Long, eventGeneration: Long): Boolean {
        if (!authenticated || eventGeneration != selection.currentGeneration() || !balances.containsKey(balanceId)) return false
        return selection.selectKnownBalanceLocally(balanceId, eventGeneration)
    }

    fun beginConnection(generation: Long): Waiter {
        balances.clear()
        authenticated = false
        selection.beginConnection(generation)
        return registerWaiter(
            requestId = null,
            names = setOf("profile", "balance-changed"),
            requiresActiveBalance = true,
        )
    }

    fun beginSwitch(targetBalanceId: Long, generation: Long): Waiter {
        selection.beginSwitch(targetBalanceId, generation)
        return registerWaiter(
            requestId = null,
            names = setOf("balance-changed", "profile"),
            targetBalanceId = targetBalanceId,
        )
    }

    fun registerWaiter(
        requestId: String?,
        names: Set<String>,
        requiresActiveBalance: Boolean = false,
        targetBalanceId: Long? = null,
    ): Waiter = Waiter(
        requestId = requestId,
        names = names,
        deferred = CompletableDeferred(),
        requiresActiveBalance = requiresActiveBalance,
        targetBalanceId = targetBalanceId,
    ).also(waiters::add)

    fun removeWaiter(waiter: Waiter) {
        waiters.remove(waiter)
    }

    fun finishSwitch(targetBalanceId: Long, confirmed: Boolean, reason: String?) {
        selection.finishSwitch(targetBalanceId, confirmed, reason)
    }

    fun failReconciliation(reason: String) {
        selection.failReconciliation(reason)
    }

    fun markUnauthenticated() {
        authenticated = false
    }

    /** Clear wallet information when a socket is intentionally replaced or the user logs out. */
    fun invalidateAccount() {
        authenticated = false
        balances.clear()
        selection.invalidate()
    }

    /** Fail only waiters belonging to a socket that has actually dropped. */
    fun failWaiters(cause: Throwable) {
        waiters.forEach { it.deferred.completeExceptionally(cause) }
        waiters.clear()
    }

    /**
     * Consume one socket message. The account reducer runs before waiter routing so an initial
     * profile can satisfy login only after its active ID is present in the known balance cache.
     */
    fun onMessage(root: JsonObject, eventGeneration: Long): AccountUpdate? {
        if (eventGeneration != selection.currentGeneration()) return null

        val name = (root["name"] as? JsonPrimitive)?.contentOrNull
        val msg = root["msg"] as? JsonObject
        val beforeId = selection.activeId()
        val wasAuthenticated = authenticated
        var balancesChanged = false
        var update: AccountUpdate? = null

        when (name) {
            "profile" -> {
                val profile = msg?.let(IqProtocol::parseProfile)
                if (profile != null) {
                    profile.balances.forEach { balance ->
                        if (balances.put(balance.id, balance) != balance) balancesChanged = true
                    }
                    val serverId = IqProtocol.confirmedActiveBalanceId(root)
                    val accepted = selection.observeServerConfirmation(serverId, eventGeneration)
                    if (activeBalance() != null) authenticated = true
                    update = AccountUpdate(
                        eventName = "profile",
                        serverBalanceId = serverId,
                        accepted = accepted,
                        activeBalanceId = selection.activeId()?.takeIf { balances.containsKey(it) },
                        stateChanged = balancesChanged || beforeId != selection.activeId() || wasAuthenticated != authenticated,
                    )
                }
            }

            "balance-changed" -> {
                val balance = msg?.let(IqProtocol::parseBalanceChanged)
                if (balance != null) {
                    if (balances.put(balance.id, balance) != balance) balancesChanged = true
                    val serverId = IqProtocol.confirmedActiveBalanceId(root)
                    val accepted = selection.observeServerConfirmation(serverId, eventGeneration)
                    if (activeBalance() != null) authenticated = true
                    update = AccountUpdate(
                        eventName = "balance-changed",
                        serverBalanceId = serverId,
                        accepted = accepted,
                        activeBalanceId = selection.activeId(),
                        stateChanged = balancesChanged || beforeId != selection.activeId() || wasAuthenticated != authenticated,
                    )
                }
            }
        }

        routeToWaiters(root)
        return update
    }

    private fun routeToWaiters(root: JsonObject) {
        val name = (root["name"] as? JsonPrimitive)?.contentOrNull ?: return
        val requestId = (root["request_id"] as? JsonPrimitive)?.contentOrNull
        for (waiter in waiters) {
            // Request messages may omit request_id in some observed protocol variants. If a reply
            // does carry one, however, it must match; this prevents stale IDs matching by name.
            val requestMatches = waiter.requestId == null || requestId == null || requestId == waiter.requestId
            val nameMatches = name in waiter.names
            val targetMatches = waiter.targetBalanceId == null ||
                IqProtocol.confirmedActiveBalanceId(root) == waiter.targetBalanceId
            val accountReady = !waiter.requiresActiveBalance || (authenticated && activeBalance() != null)
            if (requestMatches && nameMatches && targetMatches && accountReady && waiter.deferred.complete(root)) {
                waiters.remove(waiter)
            }
        }
    }
}
