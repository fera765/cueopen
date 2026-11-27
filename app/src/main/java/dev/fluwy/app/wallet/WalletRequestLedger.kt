package dev.fluwy.app.wallet

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Durable, non-secret idempotency ledger for transfer intents.
 *
 * This records intent lifecycle and atomically reserves spend in claimAndAuthorize; it
 * does not sign, send, or confirm transactions. Only a newly Created authorization result
 * may be considered for the next stage; Existing is idempotency information, never a new
 * grant to sign or submit. Callers must reconcile SUBMITTED/UNKNOWN entries with chain state.
 */
class WalletRequestLedger(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {
    /**
     * Low-level ledger insertion for internal state-machine setup only.
     * Never use this result as authorization to sign or submit a transaction.
     * Production signing flow must start with claimAndAuthorize and proceed only for Created.
     */
    @Synchronized
    internal fun claim(intent: WalletTransferIntent): LedgerClaim {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val existing = db.query(
                TABLE,
                arrayOf("agent_id", "recipient", "amount_lamports", "created_at_ms", "memo", "state", "signature", "human_approved_at_ms", "network"),
                "request_id = ?",
                arrayOf(intent.requestId),
                null,
                null,
                null
            ).use { cursor ->
                if (!cursor.moveToFirst()) null else LedgerRecord(
                    requestId = intent.requestId,
                    agentId = cursor.getString(0),
                    recipientAddress = cursor.getString(1),
                    amountLamports = cursor.getLong(2),
                    createdAtEpochMs = cursor.getLong(3),
                    memo = if (cursor.isNull(4)) null else cursor.getString(4),
                    state = LedgerState.valueOf(cursor.getString(5)),
                    signature = if (cursor.isNull(6)) null else cursor.getString(6),
                    humanApprovedAtEpochMs = cursor.getLong(7),
                    network = SolanaNetwork.valueOf(cursor.getString(8))
                )
            }

            if (existing != null) {
                check(existing.matches(intent)) {
                    "Request ID already exists with a different transfer payload"
                }
                db.setTransactionSuccessful()
                return LedgerClaim.Existing(existing)
            }

            val values = ContentValues().apply {
                put("request_id", intent.requestId)
                put("agent_id", intent.agentId)
                put("recipient", intent.recipientAddress)
                put("amount_lamports", intent.amountLamports)
                put("created_at_ms", intent.createdAtEpochMs)
                put("reserved_at_ms", System.currentTimeMillis().coerceAtLeast(1L))
                if (intent.memo == null) putNull("memo") else put("memo", intent.memo)
                put("state", LedgerState.PREPARED.name)
                put("signature", null as String?)
                put("human_approved_at_ms", 0L)
                put("network", intent.network.name)
                put("updated_at_ms", System.currentTimeMillis())
            }
            val inserted = db.insertOrThrow(TABLE, null, values)
            check(inserted != -1L) { "Could not persist transfer request" }
            db.setTransactionSuccessful()
            return LedgerClaim.Created(
                LedgerRecord(
                    requestId = intent.requestId,
                    agentId = intent.agentId,
                    recipientAddress = intent.recipientAddress,
                    amountLamports = intent.amountLamports,
                    createdAtEpochMs = intent.createdAtEpochMs,
                    memo = intent.memo,
                    state = LedgerState.PREPARED,
                    signature = null,
                    network = intent.network
                )
            )
        } finally {
            db.endTransaction()
        }
    }


    /**
     * Atomically evaluates the local agent policy and reserves this request against the
     * configured spending window. All non-failed requests count, including pending and
     * UNKNOWN requests, so concurrent calls cannot overspend the same budget.
     *
     * The caller must supply the local calendar window (start inclusive, end exclusive).
     * This is a reservation primitive, not a transaction signer or chain reconciler.
     */
    @Synchronized
    fun claimAndAuthorize(
        intent: WalletTransferIntent,
        policy: WalletAgentPolicy,
        windowStartEpochMs: Long,
        windowEndEpochMs: Long,
        authorizationTimeEpochMs: Long = System.currentTimeMillis()
    ): LedgerAuthorizationResult {
        require(windowStartEpochMs > 0L && windowEndEpochMs > windowStartEpochMs) {
            "Invalid spending window"
        }
        require(authorizationTimeEpochMs in windowStartEpochMs until windowEndEpochMs) {
            "Authorization time is outside the spending window"
        }
        if (policy.agentId != intent.agentId) {
            return LedgerAuthorizationResult.Rejected(
                PolicyDecision.Denied("Agent identity does not match wallet policy")
            )
        }

        val db = writableDatabase
        db.beginTransaction()
        try {
            val existing = readRecord(db, intent.requestId)
            if (existing != null) {
                check(existing.matches(intent)) {
                    "Request ID already exists with a different transfer payload"
                }
                val spentExcludingThisRequest = sumReservedSpend(
                    db, intent.agentId, windowStartEpochMs, windowEndEpochMs,
                    excludeRequestId = intent.requestId
                )
                val decision = policy.authorize(
                    WalletAction.TRANSFER, intent.amountLamports, intent.recipientAddress,
                    spentExcludingThisRequest
                )
                if (existing.state == LedgerState.FAILED) {
                    db.setTransactionSuccessful()
                    return LedgerAuthorizationResult.Rejected(
                        PolicyDecision.Denied("A failed request ID cannot be reused")
                    )
                }
                // Re-evaluation must never silently bypass a previously required human approval.
                // Every replay after the first authorization is informational only.
                // Policy can be re-evaluated on replay, but an existing request is never a
                // new authorization to sign. If a decision is needed, return Existing only.
                var effectiveRecord = existing
                // Only AUTHORIZED is safe to downgrade here. SIGNING may already have
                // produced bytes in another call; it must become UNKNOWN and be reconciled,
                // never returned to an approval queue that could sign it again.
                if (existing.state == LedgerState.AUTHORIZED && decision != PolicyDecision.Allowed) {
                    val changed = db.update(
                        TABLE,
                        ContentValues().apply {
                            put("state", LedgerState.AWAITING_APPROVAL.name)
                            put("human_approved_at_ms", 0L)
                            put("updated_at_ms", System.currentTimeMillis())
                        },
                        "request_id = ? AND state = ?",
                        arrayOf(intent.requestId, LedgerState.AUTHORIZED.name)
                    )
                    check(changed == 1) { "Could not revoke authorization after policy change" }
                    effectiveRecord = existing.copy(
                        state = LedgerState.AWAITING_APPROVAL,
                        humanApprovedAtEpochMs = 0L
                    )
                }
                // An already-started signing request is never allowed to return to approval;
                // policy revocation must leave it quarantined for reconciliation.
                if (existing.state == LedgerState.SIGNING && decision is PolicyDecision.Denied) {
                    val changed = db.update(
                        TABLE,
                        ContentValues().apply {
                            put("state", LedgerState.UNKNOWN.name)
                            put("updated_at_ms", System.currentTimeMillis())
                        },
                        "request_id = ? AND state = ?",
                        arrayOf(intent.requestId, LedgerState.SIGNING.name)
                    )
                    check(changed == 1) { "Could not quarantine signing request after policy change" }
                    effectiveRecord = existing.copy(state = LedgerState.UNKNOWN)
                }
                db.setTransactionSuccessful()
                return LedgerAuthorizationResult.Existing(effectiveRecord)
            }

            // Repeat-intent deduplication is inside the same SQLite write transaction
            // as authorization/insertion, so concurrent tool calls cannot both pass a
            // process-local pre-check and reserve duplicate transfers.
            if (intent.memo == null && intent.agentId != WalletAgentPolicyStore.manualAgentId(intent.network)) {
                val lowerBound = (authorizationTimeEpochMs - RECENT_INTENT_DEDUPE_WINDOW_MS).coerceAtLeast(1L)
                val duplicateId = db.query(
                    TABLE,
                    arrayOf("request_id"),
                    "request_id != ? AND agent_id = ? AND recipient = ? AND amount_lamports = ? AND network = ? AND memo IS NULL AND state != ? AND created_at_ms >= ?",
                    arrayOf(
                        intent.requestId, intent.agentId, intent.recipientAddress,
                        intent.amountLamports.toString(), intent.network.name,
                        LedgerState.FAILED.name, lowerBound.toString()
                    ),
                    null, null, "created_at_ms DESC", "1"
                ).use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
                if (duplicateId != null) {
                    val duplicate = readRecord(db, duplicateId)
                        ?: error("Duplicate transfer record disappeared during authorization")
                    db.setTransactionSuccessful()
                    return LedgerAuthorizationResult.Existing(duplicate)
                }
            }

            val spent = sumReservedSpend(db, intent.agentId, windowStartEpochMs, windowEndEpochMs)
            val decision = policy.authorize(
                WalletAction.TRANSFER, intent.amountLamports, intent.recipientAddress, spent
            )
            if (decision is PolicyDecision.Denied) {
                db.setTransactionSuccessful()
                return LedgerAuthorizationResult.Rejected(decision)
            }

            val initialState = if (decision == PolicyDecision.Allowed) {
                LedgerState.AUTHORIZED
            } else {
                LedgerState.AWAITING_APPROVAL
            }
            val values = ContentValues().apply {
                put("request_id", intent.requestId)
                put("agent_id", intent.agentId)
                put("recipient", intent.recipientAddress)
                put("amount_lamports", intent.amountLamports)
                put("created_at_ms", intent.createdAtEpochMs)
                put("reserved_at_ms", authorizationTimeEpochMs)
                if (intent.memo == null) putNull("memo") else put("memo", intent.memo)
                put("state", initialState.name)
                put("signature", null as String?)
                put("human_approved_at_ms", 0L)
                put("network", intent.network.name)
                put("updated_at_ms", System.currentTimeMillis())
            }
            db.insertOrThrow(TABLE, null, values)
            val record = LedgerRecord(
                requestId = intent.requestId,
                agentId = intent.agentId,
                recipientAddress = intent.recipientAddress,
                amountLamports = intent.amountLamports,
                createdAtEpochMs = intent.createdAtEpochMs,
                memo = intent.memo,
                state = initialState,
                signature = null,
                network = intent.network
            )
            db.setTransactionSuccessful()
            return LedgerAuthorizationResult.Created(record, decision)
        } finally {
            db.endTransaction()
        }
    }

    private fun readRecord(db: SQLiteDatabase, requestId: String): LedgerRecord? =
        db.query(
            TABLE,
            arrayOf("agent_id", "recipient", "amount_lamports", "created_at_ms", "memo", "state", "signature", "human_approved_at_ms", "network"),
            "request_id = ?",
            arrayOf(requestId),
            null, null, null
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else LedgerRecord(
                requestId = requestId,
                agentId = cursor.getString(0),
                recipientAddress = cursor.getString(1),
                amountLamports = cursor.getLong(2),
                createdAtEpochMs = cursor.getLong(3),
                memo = if (cursor.isNull(4)) null else cursor.getString(4),
                state = LedgerState.valueOf(cursor.getString(5)),
                signature = if (cursor.isNull(6)) null else cursor.getString(6),
                humanApprovedAtEpochMs = cursor.getLong(7),
                network = SolanaNetwork.valueOf(cursor.getString(8))
            )
        }

    private fun sumReservedSpend(
        db: SQLiteDatabase,
        agentId: String,
        windowStartEpochMs: Long,
        windowEndEpochMs: Long,
        excludeRequestId: String? = null
    ): Long {
        val selection = buildString {
            append("agent_id = ? AND reserved_at_ms >= ? AND reserved_at_ms < ? AND state != ?")
            if (excludeRequestId != null) append(" AND request_id != ?")
        }
        val args = mutableListOf(
            agentId, windowStartEpochMs.toString(), windowEndEpochMs.toString(), LedgerState.FAILED.name
        )
        if (excludeRequestId != null) args.add(excludeRequestId)
        return db.rawQuery(
            "SELECT COALESCE(SUM(amount_lamports), 0) FROM $TABLE WHERE $selection",
            args.toTypedArray()
        ).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }
    }

    /**
     * Called only after an explicit human approval action. Rechecks the current policy and
     * spending window inside the same SQLite transaction before promoting the request to
     * AUTHORIZED. A UI click alone is not enough: policy denial always fails closed.
     */
    @Synchronized
    fun approveForSigning(
        requestId: String,
        policy: WalletAgentPolicy,
        windowStartEpochMs: Long,
        windowEndEpochMs: Long,
        approvalTimeEpochMs: Long = System.currentTimeMillis()
    ): LedgerAuthorizationResult {
        require(requestId.isNotBlank())
        require(windowStartEpochMs > 0L && windowEndEpochMs > windowStartEpochMs) {
            "Invalid spending window"
        }
        require(approvalTimeEpochMs in windowStartEpochMs until windowEndEpochMs) {
            "Approval time is outside the spending window"
        }

        val db = writableDatabase
        db.beginTransaction()
        try {
            val record = readRecord(db, requestId)
                ?: run {
                    db.setTransactionSuccessful()
                    return LedgerAuthorizationResult.Rejected(
                        PolicyDecision.Denied("Transfer request does not exist")
                    )
                }
            if (record.state != LedgerState.AWAITING_APPROVAL) {
                db.setTransactionSuccessful()
                return LedgerAuthorizationResult.Rejected(
                    PolicyDecision.Denied("Transfer is not awaiting human approval")
                )
            }
            if (record.agentId != policy.agentId) {
                db.setTransactionSuccessful()
                return LedgerAuthorizationResult.Rejected(
                    PolicyDecision.Denied("Agent identity does not match wallet policy")
                )
            }
            val intent = WalletTransferIntent(
                requestId = record.requestId,
                agentId = record.agentId,
                recipientAddress = record.recipientAddress,
                amountLamports = record.amountLamports,
                createdAtEpochMs = record.createdAtEpochMs,
                memo = record.memo,
                network = record.network
            )
            val spent = sumReservedSpend(
                db, record.agentId, windowStartEpochMs, windowEndEpochMs,
                excludeRequestId = requestId
            )
            val decision = policy.authorize(
                WalletAction.TRANSFER, intent.amountLamports, intent.recipientAddress, spent
            )
            if (decision is PolicyDecision.Denied) {
                db.setTransactionSuccessful()
                return LedgerAuthorizationResult.Rejected(decision)
            }
            val changed = db.update(
                TABLE,
                ContentValues().apply {
                    put("state", LedgerState.AUTHORIZED.name)
                    put("reserved_at_ms", approvalTimeEpochMs)
                    put("human_approved_at_ms", approvalTimeEpochMs)
                    put("updated_at_ms", System.currentTimeMillis())
                },
                "request_id = ? AND state = ?",
                arrayOf(requestId, LedgerState.AWAITING_APPROVAL.name)
            )
            check(changed == 1) { "Transfer state changed during approval" }
            db.setTransactionSuccessful()
            return LedgerAuthorizationResult.Approved(
                record.copy(state = LedgerState.AUTHORIZED, humanApprovedAtEpochMs = approvalTimeEpochMs)
            )
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Start signing only after atomically rechecking the local policy, agent identity,
     * current spending window and reserved spend. The human path may accept
     * RequiresUserApproval only after approveForSigning has already recorded consent.
     */
    internal fun beginSigningAutonomously(
        requestId: String,
        policy: WalletAgentPolicy,
        windowStartEpochMs: Long,
        windowEndEpochMs: Long,
        signingTimeEpochMs: Long = System.currentTimeMillis()
    ): LedgerSigningStartResult = beginSigningInternal(
        requestId, policy, windowStartEpochMs, windowEndEpochMs, signingTimeEpochMs,
        humanApprovalRecorded = false
    )

    internal fun beginSigningAfterHumanApproval(
        requestId: String,
        policy: WalletAgentPolicy,
        windowStartEpochMs: Long,
        windowEndEpochMs: Long,
        signingTimeEpochMs: Long = System.currentTimeMillis()
    ): LedgerSigningStartResult = beginSigningInternal(
        requestId, policy, windowStartEpochMs, windowEndEpochMs, signingTimeEpochMs,
        humanApprovalRecorded = true
    )

    @Synchronized
    private fun beginSigningInternal(
        requestId: String,
        policy: WalletAgentPolicy,
        windowStartEpochMs: Long,
        windowEndEpochMs: Long,
        signingTimeEpochMs: Long,
        humanApprovalRecorded: Boolean
    ): LedgerSigningStartResult {
        require(requestId.isNotBlank())
        require(windowStartEpochMs > 0L && windowEndEpochMs > windowStartEpochMs) {
            "Invalid spending window"
        }
        require(signingTimeEpochMs in windowStartEpochMs until windowEndEpochMs) {
            "Signing time is outside the spending window"
        }

        val db = writableDatabase
        db.beginTransaction()
        try {
            val record = readRecord(db, requestId)
            if (record == null) {
                db.setTransactionSuccessful()
                return LedgerSigningStartResult.Rejected(
                    PolicyDecision.Denied("Transfer request does not exist")
                )
            }
            if (record.state != LedgerState.AUTHORIZED) {
                db.setTransactionSuccessful()
                return LedgerSigningStartResult.Rejected(
                    PolicyDecision.Denied("Transfer is not authorized to start signing")
                )
            }
            if (record.agentId != policy.agentId) {
                db.setTransactionSuccessful()
                return LedgerSigningStartResult.Rejected(
                    PolicyDecision.Denied("Agent identity does not match wallet policy")
                )
            }

            if (humanApprovalRecorded &&
                (record.humanApprovedAtEpochMs <= 0L ||
                    record.humanApprovedAtEpochMs !in windowStartEpochMs until windowEndEpochMs ||
                    record.humanApprovedAtEpochMs > signingTimeEpochMs ||
                    signingTimeEpochMs - record.humanApprovedAtEpochMs > MAX_HUMAN_APPROVAL_AGE_MS)
            ) {
                val changed = db.update(
                    TABLE,
                    ContentValues().apply {
                        put("state", LedgerState.AWAITING_APPROVAL.name)
                        put("human_approved_at_ms", 0L)
                        put("updated_at_ms", System.currentTimeMillis())
                    },
                    "request_id = ? AND state = ?",
                    arrayOf(requestId, LedgerState.AUTHORIZED.name)
                )
                check(changed == 1) { "Could not expire stale human approval" }
                db.setTransactionSuccessful()
                return LedgerSigningStartResult.AwaitingHumanApproval(
                    record.copy(state = LedgerState.AWAITING_APPROVAL, humanApprovedAtEpochMs = 0L)
                )
            }
            if (!humanApprovalRecorded && record.humanApprovedAtEpochMs > 0L) {
                val changed = db.update(
                    TABLE,
                    ContentValues().apply {
                        put("state", LedgerState.AWAITING_APPROVAL.name)
                        put("human_approved_at_ms", 0L)
                        put("updated_at_ms", System.currentTimeMillis())
                    },
                    "request_id = ? AND state = ?",
                    arrayOf(requestId, LedgerState.AUTHORIZED.name)
                )
                check(changed == 1) { "Could not require the recorded human-approval path" }
                db.setTransactionSuccessful()
                return LedgerSigningStartResult.AwaitingHumanApproval(
                    record.copy(state = LedgerState.AWAITING_APPROVAL, humanApprovedAtEpochMs = 0L)
                )
            }

            val intent = WalletTransferIntent(
                requestId = record.requestId,
                agentId = record.agentId,
                recipientAddress = record.recipientAddress,
                amountLamports = record.amountLamports,
                createdAtEpochMs = record.createdAtEpochMs,
                memo = record.memo
            )
            val spent = sumReservedSpend(
                db, record.agentId, windowStartEpochMs, windowEndEpochMs,
                excludeRequestId = requestId
            )
            val decision = policy.authorize(
                WalletAction.TRANSFER, intent.amountLamports, intent.recipientAddress, spent
            )
            if (decision is PolicyDecision.Denied) {
                val changed = db.update(
                    TABLE,
                    ContentValues().apply {
                        put("state", LedgerState.FAILED.name)
                        put("human_approved_at_ms", 0L)
                        put("updated_at_ms", System.currentTimeMillis())
                    },
                    "request_id = ? AND state = ?",
                    arrayOf(requestId, LedgerState.AUTHORIZED.name)
                )
                check(changed == 1) { "Could not fail a request denied before signing" }
                db.setTransactionSuccessful()
                return LedgerSigningStartResult.Rejected(decision)
            }
            if (decision == PolicyDecision.RequiresUserApproval && !humanApprovalRecorded) {
                val changed = db.update(
                    TABLE,
                    ContentValues().apply {
                        put("state", LedgerState.AWAITING_APPROVAL.name)
                        put("human_approved_at_ms", 0L)
                        put("updated_at_ms", System.currentTimeMillis())
                    },
                    "request_id = ? AND state = ?",
                    arrayOf(requestId, LedgerState.AUTHORIZED.name)
                )
                check(changed == 1) { "Could not require human approval before signing" }
                db.setTransactionSuccessful()
                return LedgerSigningStartResult.AwaitingHumanApproval(
                    record.copy(state = LedgerState.AWAITING_APPROVAL)
                )
            }

            val changed = db.update(
                TABLE,
                ContentValues().apply {
                    put("state", LedgerState.SIGNING.name)
                    put("reserved_at_ms", signingTimeEpochMs)
                    put("updated_at_ms", System.currentTimeMillis())
                },
                "request_id = ? AND state = ?",
                arrayOf(requestId, LedgerState.AUTHORIZED.name)
            )
            check(changed == 1) { "Could not claim transfer for signing" }
            db.setTransactionSuccessful()
            return LedgerSigningStartResult.Started(record.copy(state = LedgerState.SIGNING))
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Revalidates policy at broadcast time and moves the reservation into the current
     * spending window atomically. RequiresUserApproval is returned to the caller, which
     * must separately prove that the user confirmed the final broadcast.
     */
    @Synchronized
    internal fun authorizeBroadcast(
        requestId: String,
        policy: WalletAgentPolicy,
        windowStartEpochMs: Long,
        windowEndEpochMs: Long,
        broadcastTimeEpochMs: Long = System.currentTimeMillis()
    ): PolicyDecision {
        require(windowStartEpochMs > 0L && windowEndEpochMs > windowStartEpochMs) {
            "Invalid spending window"
        }
        require(broadcastTimeEpochMs in windowStartEpochMs until windowEndEpochMs) {
            "Broadcast time is outside the spending window"
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val record = readRecord(db, requestId)
                ?: run {
                    db.setTransactionSuccessful()
                    return PolicyDecision.Denied("Transfer request does not exist")
                }
            if (record.state != LedgerState.SIGNED || record.signature.isNullOrBlank()) {
                db.setTransactionSuccessful()
                return PolicyDecision.Denied("Only a durably signed request can be broadcast")
            }
            if (record.agentId != policy.agentId) {
                db.setTransactionSuccessful()
                return PolicyDecision.Denied("Agent identity does not match wallet policy")
            }
            val spent = sumReservedSpend(
                db, record.agentId, windowStartEpochMs, windowEndEpochMs,
                excludeRequestId = requestId
            )
            val decision = policy.authorize(
                WalletAction.TRANSFER, record.amountLamports, record.recipientAddress, spent
            )
            if (decision is PolicyDecision.Denied) {
                db.setTransactionSuccessful()
                return decision
            }
            val changed = db.update(
                TABLE,
                ContentValues().apply {
                    put("reserved_at_ms", broadcastTimeEpochMs)
                    put("updated_at_ms", System.currentTimeMillis())
                },
                "request_id = ? AND state = ? AND signature IS NOT NULL AND signed_transaction IS NOT NULL",
                arrayOf(requestId, LedgerState.SIGNED.name)
            )
            check(changed == 1) { "Could not reserve signed transfer for broadcast" }
            db.setTransactionSuccessful()
            return decision
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Atomically persists the signed transaction bytes and their signature before any network
     * call. The signature must match the first signature in the serialized Solana transaction.
     * This method is the only permitted SIGNING -> SIGNED transition.
     */
    @Synchronized
    internal fun persistSignedTransaction(
        requestId: String,
        signature: String,
        serializedTransaction: ByteArray
    ): Boolean {
        require(requestId.isNotBlank())
        require(SolanaPersistedTransactionValidator.signatureMatches(serializedTransaction, signature)) {
            "Serialized transaction envelope or first signature is invalid"
        }

        val db = writableDatabase
        db.beginTransaction()
        try {
            val record = readRecord(db, requestId) ?: return false
            if (record.state != LedgerState.SIGNING || record.signature != null) return false
            val values = ContentValues().apply {
                put("state", LedgerState.SIGNED.name)
                put("signature", signature)
                put("signed_transaction", serializedTransaction)
                put("updated_at_ms", System.currentTimeMillis())
            }
            val changed = db.update(
                TABLE, values, "request_id = ? AND state = ? AND signature IS NULL",
                arrayOf(requestId, LedgerState.SIGNING.name)
            )
            if (changed != 1) return false
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Releases a signing reservation only when no signature or transaction bytes were
     * durably persisted. This also recovers a quarantined UNKNOWN row with no signature:
     * the executor's invariant is that no network broadcast happens before persistence.
     */
    @Synchronized
    internal fun abortSigningBeforePersistence(requestId: String): Boolean {
        require(requestId.isNotBlank())
        val db = writableDatabase
        db.beginTransaction()
        try {
            val changed = db.update(
                TABLE,
                ContentValues().apply {
                    put("state", LedgerState.FAILED.name)
                    put("human_approved_at_ms", 0L)
                    put("updated_at_ms", System.currentTimeMillis())
                },
                "request_id = ? AND state IN (?, ?) AND signature IS NULL AND signed_transaction IS NULL",
                arrayOf(requestId, LedgerState.SIGNING.name, LedgerState.UNKNOWN.name)
            )
            if (changed != 1) return false
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Cancels only requests that have not entered signing and have no signature or transaction
     * bytes. The state predicate is checked atomically so a concurrent signer wins or cancel wins.
     */
    @Synchronized
    internal fun cancelBeforeSigning(requestId: String): Boolean {
        require(requestId.isNotBlank())
        val db = writableDatabase
        db.beginTransaction()
        try {
            val changed = db.update(
                TABLE,
                ContentValues().apply {
                    put("state", LedgerState.FAILED.name)
                    put("human_approved_at_ms", 0L)
                    put("updated_at_ms", System.currentTimeMillis())
                },
                "request_id = ? AND state IN (?, ?, ?) AND signature IS NULL AND signed_transaction IS NULL",
                arrayOf(
                    requestId,
                    LedgerState.PREPARED.name,
                    LedgerState.AWAITING_APPROVAL.name,
                    LedgerState.AUTHORIZED.name
                )
            )
            if (changed != 1) return false
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Release a transfer amount reservation only after RPC reports a finalized on-chain error
     * for the exact signature persisted in this ledger. Non-final errors remain UNKNOWN.
     */
    @Synchronized
    internal fun markFinalizedChainFailure(
        requestId: String,
        signature: String,
        confirmationStatus: String,
        chainError: String
    ): Boolean {
        require(confirmationStatus == "finalized") { "Only finalized chain failures may release a reservation" }
        require(chainError.isNotBlank()) { "A chain error is required" }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val record = readRecord(db, requestId) ?: return false
            if (record.state !in setOf(LedgerState.SIGNED, LedgerState.SUBMITTED, LedgerState.UNKNOWN) ||
                record.signature != signature
            ) return false
            val changed = db.update(
                TABLE,
                ContentValues().apply {
                    put("state", LedgerState.FAILED.name)
                    put("updated_at_ms", System.currentTimeMillis())
                },
                "request_id = ? AND state = ? AND signature = ?",
                arrayOf(requestId, record.state.name, signature)
            )
            if (changed != 1) return false
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    /** Returns a defensive copy only for a durable signed transaction awaiting reconciliation. */
    @Synchronized
    internal fun signedTransaction(requestId: String): ByteArray? {
        require(requestId.isNotBlank())
        return readableDatabase.query(
            TABLE, arrayOf("state", "signed_transaction"), "request_id = ?",
            arrayOf(requestId), null, null, null
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val state = LedgerState.valueOf(cursor.getString(0))
            if (state !in setOf(LedgerState.SIGNED, LedgerState.SUBMITTED, LedgerState.UNKNOWN) ||
                cursor.isNull(1)
            ) return@use null
            cursor.getBlob(1)?.copyOf()
        }
    }

    /** Compare-and-set transition prevents stale callers from overwriting a newer state. */
    @Synchronized
    fun transition(
        requestId: String,
        expected: LedgerState,
        next: LedgerState,
        signature: String? = null
    ): Boolean {
        require(requestId.isNotBlank())
        require(next in allowedNextStates(expected)) { "Illegal transfer state transition: $expected -> $next" }
        if (next in setOf(LedgerState.SIGNED, LedgerState.SUBMITTED, LedgerState.CONFIRMED) &&
            signature.isNullOrBlank()
        ) {
            throw IllegalArgumentException("A durable chain signature is required for $next")
        }
        if (next == LedgerState.SIGNED) {
            throw IllegalArgumentException("Use persistSignedTransaction to atomically store signed bytes and signature")
        }
        if (next == LedgerState.SIGNING) {
            throw IllegalArgumentException("Use a policy-checked signing claim to enter SIGNING")
        }
        if (signature != null) require(signature.length <= MAX_SIGNATURE_LENGTH) { "Signature is too long" }
        if (next == LedgerState.SIGNED && (signature == null || !SolanaSignatureValidator.isValid(signature))) {
            throw IllegalArgumentException("A valid base58 Solana transaction signature is required")
        }
        // State change and signature invariants are enforced in one SQLite transaction.
        val db = writableDatabase
        db.beginTransaction()
        try {
            val current = readRecord(db, requestId) ?: return false
            if (current.state != expected) return false
            if (signature != null && current.signature != null && current.signature != signature) return false
            if (expected == LedgerState.SIGNED && next == LedgerState.SUBMITTED &&
                signature != current.signature
            ) {
                throw IllegalArgumentException("Submitted signature must match the signature persisted before network submission")
            }
            if (expected in setOf(LedgerState.SIGNED, LedgerState.SUBMITTED, LedgerState.UNKNOWN) &&
                next == LedgerState.CONFIRMED && signature != current.signature
            ) {
                throw IllegalArgumentException("Confirmation signature must match the original transaction signature")
            }
            val values = ContentValues().apply {
                put("state", next.name)
                put("updated_at_ms", System.currentTimeMillis())
                if (signature != null && current.signature == null) put("signature", signature)
            }
            val changed = db.update(
                TABLE, values, "request_id = ? AND state = ?",
                arrayOf(requestId, expected.name)
            )
            if (changed != 1) return false
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    internal fun recentRecords(limit: Int = 20): List<LedgerRecord> {
        require(limit in 1..100)
        val db = readableDatabase
        val requestIds = db.query(
            TABLE, arrayOf("request_id"), null, null, null, null,
            "updated_at_ms DESC", limit.toString()
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
        return requestIds.mapNotNull { readRecord(db, it) }
    }

    /**
     * Detects repeated agent intents created within a short window, even when the model
     * generates a fresh request ID on every tool call. FAILED requests are excluded; all
     * other states remain deduplicated to prevent duplicate sends during retries.
     */
    @Synchronized
    fun findRecentDuplicateIntent(
        intent: WalletTransferIntent,
        nowEpochMs: Long = System.currentTimeMillis(),
        dedupeWindowMs: Long = 120_000L
    ): LedgerRecord? {
        require(nowEpochMs > 0L)
        require(dedupeWindowMs in 1L..3_600_000L)
        if (intent.memo != null || intent.agentId == WalletAgentPolicyStore.manualAgentId(intent.network)) return null
        val lowerBound = (nowEpochMs - dedupeWindowMs).coerceAtLeast(1L)
        val db = readableDatabase
        val requestId = db.query(
            TABLE,
            arrayOf("request_id"),
            "agent_id = ? AND recipient = ? AND amount_lamports = ? AND network = ? AND memo IS NULL AND state != ? AND created_at_ms >= ?",
            arrayOf(
                intent.agentId, intent.recipientAddress, intent.amountLamports.toString(),
                intent.network.name, LedgerState.FAILED.name, lowerBound.toString()
            ),
            null, null, "created_at_ms DESC", "1"
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: return null
        return readRecord(db, requestId)
    }

    @Synchronized
    fun find(requestId: String): LedgerRecord? {
        require(requestId.isNotBlank())
        return readableDatabase.query(
            TABLE,
            arrayOf("agent_id", "recipient", "amount_lamports", "created_at_ms", "memo", "state", "signature", "human_approved_at_ms", "network"),
            "request_id = ?",
            arrayOf(requestId),
            null,
            null,
            null
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else LedgerRecord(
                requestId = requestId,
                agentId = cursor.getString(0),
                recipientAddress = cursor.getString(1),
                amountLamports = cursor.getLong(2),
                createdAtEpochMs = cursor.getLong(3),
                memo = if (cursor.isNull(4)) null else cursor.getString(4),
                state = LedgerState.valueOf(cursor.getString(5)),
                signature = if (cursor.isNull(6)) null else cursor.getString(6),
                humanApprovedAtEpochMs = cursor.getLong(7),
                network = SolanaNetwork.valueOf(cursor.getString(8))
            )
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE $TABLE (
                request_id TEXT NOT NULL PRIMARY KEY,
                agent_id TEXT NOT NULL,
                recipient TEXT NOT NULL,
                amount_lamports INTEGER NOT NULL CHECK(amount_lamports > 0),
                created_at_ms INTEGER NOT NULL CHECK(created_at_ms > 0),
                reserved_at_ms INTEGER NOT NULL CHECK(reserved_at_ms > 0),
                memo TEXT,
                state TEXT NOT NULL,
                signature TEXT,
                human_approved_at_ms INTEGER NOT NULL DEFAULT 0,
                signed_transaction BLOB,
                network TEXT NOT NULL DEFAULT 'MAINNET',
                updated_at_ms INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX wallet_request_state_idx ON $TABLE(state)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // Conservatively preserve prior reservations when migrating existing records.
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN reserved_at_ms INTEGER NOT NULL DEFAULT 1")
            db.execSQL("UPDATE $TABLE SET reserved_at_ms = created_at_ms")
        }
        if (oldVersion < 3) {
            // Existing requests were not accompanied by a durable human-approval marker.
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN human_approved_at_ms INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN signed_transaction BLOB")
        }
        if (oldVersion < 5) {
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN network TEXT NOT NULL DEFAULT 'MAINNET'")
        }
        if (newVersion > 5) {
            error("Unsupported wallet ledger schema upgrade: $oldVersion -> $newVersion")
        }
    }


    private fun allowedNextStates(state: LedgerState): Set<LedgerState> = when (state) {
        LedgerState.PREPARED -> setOf(LedgerState.AWAITING_APPROVAL, LedgerState.FAILED)
        LedgerState.AUTHORIZED -> emptySet()
        LedgerState.AWAITING_APPROVAL -> setOf(LedgerState.FAILED)
        // Once signing has started, failure is ambiguous unless chain state has been reconciled.
        // Keep the reservation for SIGNING/SIGNED/SUBMITTED/UNKNOWN; never release budget blindly.
        LedgerState.SIGNING -> setOf(LedgerState.SIGNED, LedgerState.UNKNOWN)
        LedgerState.SIGNED -> setOf(LedgerState.SUBMITTED, LedgerState.CONFIRMED, LedgerState.UNKNOWN)
        LedgerState.SUBMITTED -> setOf(LedgerState.CONFIRMED, LedgerState.UNKNOWN)
        LedgerState.UNKNOWN -> setOf(LedgerState.CONFIRMED)
        LedgerState.CONFIRMED, LedgerState.FAILED -> emptySet()
    }

    private fun LedgerRecord.matches(intent: WalletTransferIntent): Boolean =
        agentId == intent.agentId &&
            recipientAddress == intent.recipientAddress &&
            amountLamports == intent.amountLamports &&
            createdAtEpochMs == intent.createdAtEpochMs &&
            memo == intent.memo &&
            network == intent.network

    companion object {
        private const val DATABASE_NAME = "fluwy_wallet_requests.db"
        private const val DATABASE_VERSION = 5
        private const val TABLE = "wallet_transfer_requests"
        private const val MAX_SIGNATURE_LENGTH = 128
        private const val RECENT_INTENT_DEDUPE_WINDOW_MS = 120_000L
        private const val MAX_HUMAN_APPROVAL_AGE_MS = 120_000L
    }
}

enum class LedgerState {
    PREPARED,
    AUTHORIZED,
    AWAITING_APPROVAL,
    SIGNING,
    SIGNED,
    SUBMITTED,
    CONFIRMED,
    FAILED,
    UNKNOWN
}

data class LedgerRecord(
    val requestId: String,
    val agentId: String,
    val recipientAddress: String,
    val amountLamports: Long,
    val createdAtEpochMs: Long,
    val memo: String?,
    val state: LedgerState,
    val signature: String?,
    val humanApprovedAtEpochMs: Long = 0L,
    val network: SolanaNetwork = SolanaNetwork.MAINNET
)

sealed interface LedgerClaim {
    data class Created(val record: LedgerRecord) : LedgerClaim
    data class Existing(val record: LedgerRecord) : LedgerClaim
}

sealed interface LedgerSigningStartResult {
    data class Started(val record: LedgerRecord) : LedgerSigningStartResult
    data class AwaitingHumanApproval(val record: LedgerRecord) : LedgerSigningStartResult
    data class Rejected(val decision: PolicyDecision.Denied) : LedgerSigningStartResult
}

sealed interface LedgerAuthorizationResult {
    data class Created(val record: LedgerRecord, val decision: PolicyDecision) : LedgerAuthorizationResult
    /** Existing idempotency record only; this is never a new signing authorization. */
    data class Existing(val record: LedgerRecord) : LedgerAuthorizationResult
    /** Fresh human approval plus policy recheck; only this can promote approval to signing-ready. */
    data class Approved(val record: LedgerRecord) : LedgerAuthorizationResult
    data class Rejected(val decision: PolicyDecision.Denied) : LedgerAuthorizationResult
}
