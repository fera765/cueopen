package dev.fluwy.app.wallet

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class WalletAuthorizationIdempotencyTest {
    private lateinit var context: Context
    private lateinit var ledger: WalletRequestLedger

    private val recipient = "HAgk14JpMQLgt6rVgv7cBQFJWFto5Dqxi472uT3DKpqk"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("fluwy_wallet_requests.db")
        ledger = WalletRequestLedger(context)
    }

    @After
    fun tearDown() {
        ledger.close()
        context.deleteDatabase("fluwy_wallet_requests.db")
    }

    @Test
    fun duplicateAuthorizationIsLookupOnlyAndRevocationRequiresApproval() {
        val now = System.currentTimeMillis()
        val start = now - 10_000L
        val end = now + 10_000L
        val intent = WalletTransferIntent(
            requestId = "request_revoke_001",
            agentId = "test-agent",
            recipientAddress = recipient,
            amountLamports = 25L,
            createdAtEpochMs = 1L,
            memo = "test"
        )
        val autonomous = WalletAgentPolicy(
            agentId = "test-agent",
            enabled = true,
            canTransfer = true,
            autonomousExecution = true,
            maxTransferLamports = 100L,
            dailyLimitLamports = 100L,
            allowedRecipients = setOf(recipient)
        )
        val manual = autonomous.copy(autonomousExecution = false, allowedRecipients = emptySet())

        val created = ledger.claimAndAuthorize(intent, autonomous, start, end, now)
        assertTrue(created is LedgerAuthorizationResult.Created)
        assertEquals(
            LedgerState.AUTHORIZED,
            (created as LedgerAuthorizationResult.Created).record.state
        )

        val duplicate = ledger.claimAndAuthorize(intent, manual, start, end, now + 1L)
        assertTrue(duplicate is LedgerAuthorizationResult.Existing)
        assertEquals(
            LedgerState.AWAITING_APPROVAL,
            (duplicate as LedgerAuthorizationResult.Existing).record.state
        )
    }
}
