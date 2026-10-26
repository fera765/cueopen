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
                ?: return LedgerAuthorizationResult.Rejected(
                    PolicyDecision.Denied("Transfer request does not exist")
                )
            if (record.state != LedgerState.AWAITING_APPROVAL) {
                return LedgerAuthorizationResult.Rejected(
                    PolicyDecision.Denied("Transfer is not awaiting human approval")
                )
            }
            if (record.agentId != policy.agentId) {
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
                db.setTransactionSuccessful()
                return LedgerAuthorizationResult.Rejected(decision)
            }
            val changed = db.update(
                TABLE,
                ContentValues().apply {
                    put("state", LedgerState.AUTHORIZED.name)
                    put("reserved_at_ms", approvalTimeEpochMs)
                    put("updated_at_ms", System.currentTimeMillis())
                },
                "request_id = ? AND state = ?",
                arrayOf(requestId, LedgerState.AWAITING_APPROVAL.name)
            )
            check(changed == 1) { "Transfer state changed during approval" }
            db.setTransactionSuccessful()
            return LedgerAuthorizationResult.Approved(record.copy(state = LedgerState.AUTHORIZED))
        } finally {
            db.endTransaction()
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
        if (next == LedgerState.SIGNED && expected != LedgerState.SIGNING) {
            throw IllegalArgumentException("A transfer must enter SIGNING before its signature can be persisted")
        }
        if (next == LedgerState.SIGNING && expected != LedgerState.AUTHORIZED) {
            throw IllegalArgumentException("Only an authorized transfer may enter SIGNING")
        }
        if (signature != null) require(signature.length <= MAX_SIGNATURE_LENGTH) { "Signature is too long" }
        if (next == LedgerState.SIGNED && !isValidSolanaSignature(signature)) {
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

    private fun isValidSolanaSignature(signature: String?): Boolean {
        if (signature.isNullOrBlank() || signature.length !in 80..MAX_SIGNATURE_LENGTH) return false
        return runCatching {
            val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
            var value = java.math.BigInteger.ZERO
            for (character in signature) {
                val digit = alphabet.indexOf(character)
                require(digit >= 0)
                value = value.multiply(java.math.BigInteger.valueOf(58L))
                    .add(java.math.BigInteger.valueOf(digit.toLong()))
            }
            val leadingZeros = signature.takeWhile { it == '1' }.length
            val decodedBytes = (value.bitLength() + 7) / 8
            leadingZeros + decodedBytes == 64
        }.getOrDefault(false)
    }

    private fun allowedNextStates(state: LedgerState): Set<LedgerState> = when (state) {
        LedgerState.PREPARED -> setOf(LedgerState.AWAITING_APPROVAL, LedgerState.FAILED)
        LedgerState.AUTHORIZED -> setOf(LedgerState.SIGNING, LedgerState.FAILED)
        LedgerState.AWAITING_APPROVAL -> setOf(LedgerState.FAILED)
        LedgerState.SIGNING -> setOf(LedgerState.SIGNED, LedgerState.UNKNOWN, LedgerState.FAILED)
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
    /** Fresh human approval plus policy recheck; only this can promote approval to signing-ready. */
    data class Approved(val record: LedgerRecord) : LedgerAuthorizationResult
    data class Rejected(val decision: PolicyDecision.Denied) : LedgerAuthorizationResult
}
