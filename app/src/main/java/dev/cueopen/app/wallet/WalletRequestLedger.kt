package dev.cueopen.app.wallet

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
                arrayOf("agent_id", "recipient", "amount_lamports", "created_at_ms", "memo", "state", "signature"),
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
                    signature = if (cursor.isNull(6)) null else cursor.getString(6)
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
                    signature = null
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
                var effectiveRecord = existing
                if (existing.state == LedgerState.AUTHORIZED &&
                    decision != PolicyDecision.Allowed
                ) {
                    val changed = db.update(
                        TABLE,
                        ContentValues().apply {
                            put("state", LedgerState.AWAITING_APPROVAL.name)
                            put("updated_at_ms", System.currentTimeMillis())
                        },
                        "request_id = ? AND state = ?",
                        arrayOf(intent.requestId, LedgerState.AUTHORIZED.name)
                    )
                    check(changed == 1) { "Could not downgrade authorization after policy change" }
                    effectiveRecord = existing.copy(state = LedgerState.AWAITING_APPROVAL)
                }
                db.setTransactionSuccessful()
                return LedgerAuthorizationResult.Existing(effectiveRecord)
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
                signature = null
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
            arrayOf("agent_id", "recipient", "amount_lamports", "created_at_ms", "memo", "state", "signature"),
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
                signature = if (cursor.isNull(6)) null else cursor.getString(6)
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
        if (next in setOf(LedgerState.SUBMITTED, LedgerState.CONFIRMED) && signature.isNullOrBlank()) {
            throw IllegalArgumentException("A chain signature is required for $next")
        }
        if (signature != null) require(signature.length <= MAX_SIGNATURE_LENGTH) { "Signature is too long" }

        val values = ContentValues().apply {
            put("state", next.name)
            put("updated_at_ms", System.currentTimeMillis())
            if (signature != null) put("signature", signature)
        }
        // Once a chain signature has been recorded, a later caller may not replace it.
        val where = if (signature == null) {
            "request_id = ? AND state = ?"
        } else {
            "request_id = ? AND state = ? AND (signature IS NULL OR signature = ?)"
        }
        val args = if (signature == null) {
            arrayOf(requestId, expected.name)
        } else {
            arrayOf(requestId, expected.name, signature)
        }
        return writableDatabase.update(TABLE, values, where, args) == 1
    }

    @Synchronized
    fun find(requestId: String): LedgerRecord? {
        require(requestId.isNotBlank())
        return readableDatabase.query(
            TABLE,
            arrayOf("agent_id", "recipient", "amount_lamports", "created_at_ms", "memo", "state", "signature"),
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
                signature = if (cursor.isNull(6)) null else cursor.getString(6)
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
        if (newVersion > 2) {
            error("Unsupported wallet ledger schema upgrade: $oldVersion -> $newVersion")
        }
    }

    private fun allowedNextStates(state: LedgerState): Set<LedgerState> = when (state) {
        LedgerState.PREPARED -> setOf(LedgerState.AWAITING_APPROVAL, LedgerState.FAILED)
        LedgerState.AUTHORIZED -> setOf(LedgerState.SIGNED, LedgerState.FAILED)
        LedgerState.AWAITING_APPROVAL -> setOf(LedgerState.SIGNED, LedgerState.FAILED)
        LedgerState.SIGNED -> setOf(LedgerState.SUBMITTED, LedgerState.UNKNOWN, LedgerState.FAILED)
        LedgerState.SUBMITTED -> setOf(LedgerState.CONFIRMED, LedgerState.UNKNOWN, LedgerState.FAILED)
        LedgerState.UNKNOWN -> setOf(LedgerState.CONFIRMED, LedgerState.FAILED)
        LedgerState.CONFIRMED, LedgerState.FAILED -> emptySet()
    }

    private fun LedgerRecord.matches(intent: WalletTransferIntent): Boolean =
        agentId == intent.agentId &&
            recipientAddress == intent.recipientAddress &&
            amountLamports == intent.amountLamports &&
            createdAtEpochMs == intent.createdAtEpochMs &&
            memo == intent.memo

    companion object {
        private const val DATABASE_NAME = "cueopen_wallet_requests.db"
        private const val DATABASE_VERSION = 2
        private const val TABLE = "wallet_transfer_requests"
        private const val MAX_SIGNATURE_LENGTH = 128
    }
}

enum class LedgerState {
    PREPARED,
    AUTHORIZED,
    AWAITING_APPROVAL,
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
    val signature: String?
)

sealed interface LedgerClaim {
    data class Created(val record: LedgerRecord) : LedgerClaim
    data class Existing(val record: LedgerRecord) : LedgerClaim
}

sealed interface LedgerAuthorizationResult {
    data class Created(val record: LedgerRecord, val decision: PolicyDecision) : LedgerAuthorizationResult
    /** Existing idempotency record only; this is never a new signing authorization. */
    data class Existing(val record: LedgerRecord) : LedgerAuthorizationResult
    data class Rejected(val decision: PolicyDecision.Denied) : LedgerAuthorizationResult
}
