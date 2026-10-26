package dev.cueopen.app.wallet

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WalletRequestLedgerTest {
    private lateinit var context: Context
    private lateinit var ledger: WalletRequestLedger

    private val recipient = "HAgk14JpMQLgt6rVgv7cBQFJWFto5Dqxi472uT3DKpqk"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("cueopen_wallet_requests.db")
        ledger = WalletRequestLedger(context)
    }

    @After
    fun tearDown() {
        ledger.close()
        context.deleteDatabase("cueopen_wallet_requests.db")
    }

    private fun intent(
        requestId: String = "request_12345678",
        recipientAddress: String = recipient,
        amountLamports: Long = 50L
    ) = WalletTransferIntent(
        requestId = requestId,
        agentId = "test-agent",
        recipientAddress = recipientAddress,
        amountLamports = amountLamports,
        createdAtEpochMs = 1L,
        memo = "test"
    )

    @Test
    fun claimPersistsAndDuplicateReturnsExistingRecord() {
        val first = ledger.claim(intent())
        assertTrue(first is LedgerClaim.Created)

        ledger.close()
        ledger = WalletRequestLedger(context)

        val duplicate = ledger.claim(intent())
        assertTrue(duplicate is LedgerClaim.Existing)
        assertEquals(LedgerState.PREPARED, (duplicate as LedgerClaim.Existing).record.state)
    }

    @Test
    fun sameRequestIdWithDifferentPayloadIsRejected() {
        ledger.claim(intent())
        try {
            ledger.claim(intent(amountLamports = 51L))
            throw AssertionError("Expected conflicting payload to be rejected")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("different transfer payload"))
        }
    }

    @Test
    fun transitionsRequireExpectedStateAndApprovalBeforeSigning() {
        ledger.claim(intent())
        assertFalse(ledger.transition(
            "request_12345678", LedgerState.PREPARED, LedgerState.SIGNED
        ))
        assertTrue(ledger.transition(
            "request_12345678", LedgerState.PREPARED, LedgerState.AWAITING_APPROVAL
        ))
        assertTrue(ledger.transition(
            "request_12345678", LedgerState.AWAITING_APPROVAL, LedgerState.SIGNED
        ))
    }

    @Test
    fun submittedAndConfirmedRequireSignature() {
        ledger.claim(intent())
        ledger.transition("request_12345678", LedgerState.PREPARED, LedgerState.AWAITING_APPROVAL)
        ledger.transition("request_12345678", LedgerState.AWAITING_APPROVAL, LedgerState.SIGNED)
        try {
            ledger.transition("request_12345678", LedgerState.SIGNED, LedgerState.SUBMITTED)
            throw AssertionError("Expected missing signature to be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("signature"))
        }
        assertTrue(ledger.transition(
            "request_12345678", LedgerState.SIGNED, LedgerState.SUBMITTED, "chain-signature-1"
        ))
        assertFalse(ledger.transition(
            "request_12345678", LedgerState.SUBMITTED, LedgerState.UNKNOWN, "chain-signature-2"
        ))
        assertEquals("chain-signature-1", ledger.find("request_12345678")?.signature)
    }

    @Test
    fun terminalStatesCannotBeReopened() {
        ledger.claim(intent())
        assertTrue(ledger.transition(
            "request_12345678", LedgerState.PREPARED, LedgerState.FAILED
        ))
        assertFalse(ledger.transition(
            "request_12345678", LedgerState.FAILED, LedgerState.PREPARED
        ))
    }
}
