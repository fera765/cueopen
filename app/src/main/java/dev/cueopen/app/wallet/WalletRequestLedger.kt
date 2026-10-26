package dev.cueopen.app.wallet

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Durable, non-secret idempotency ledger for transfer intents.
 *
 * This records intent lifecycle only; it does not sign, send, or confirm a transaction,
 * and it does not yet reserve daily spend atomically with WalletAgentPolicy. Callers must
 * reconcile SUBMITTED/UNKNOWN entries with chain state before attempting any retry.
 */
class WalletRequestLedger(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {
    @Synchronized
    fun claim(intent: WalletTransferIntent): LedgerClaim {
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
        return writableDatabase.update(
            TABLE,
            values,
            "request_id = ? AND state = ?",
            arrayOf(requestId, expected.name)
        ) == 1
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
                memo TEXT,
                state TEXT NOT NULL,
                signature TEXT,
                updated_at_ms INTEGER NOT NULL
            )""".trimIndent()
        )
        db.execSQL("CREATE INDEX wallet_request_state_idx ON $TABLE(state)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Keep transfer history intact; future schema changes must use explicit migrations.
        error("Unsupported wallet ledger schema upgrade: $oldVersion -> $newVersion")
    }

    private fun allowedNextStates(state: LedgerState): Set<LedgerState> = when (state) {
        LedgerState.PREPARED -> setOf(LedgerState.AWAITING_APPROVAL, LedgerState.SIGNED, LedgerState.FAILED)
        LedgerState.AWAITING_APPROVAL -> setOf(LedgerState.PREPARED, LedgerState.SIGNED, LedgerState.FAILED)
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
        private const val DATABASE_VERSION = 1
        private const val TABLE = "wallet_transfer_requests"
        private const val MAX_SIGNATURE_LENGTH = 128
    }
}

enum class LedgerState {
    PREPARED,
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
