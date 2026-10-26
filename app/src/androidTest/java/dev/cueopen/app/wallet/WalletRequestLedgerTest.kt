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
        try {
            ledger.transition("request_12345678", LedgerState.PREPARED, LedgerState.SIGNED)
            throw AssertionError("Signing directly from PREPARED must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("Illegal transfer state transition"))
        }
        assertTrue(ledger.transition(
            "request_12345678", LedgerState.PREPARED, LedgerState.AWAITING_APPROVAL
        ))
        try {
            ledger.transition(
                "request_12345678", LedgerState.AWAITING_APPROVAL, LedgerState.SIGNED
            )
            throw AssertionError("Human approval state must not sign directly")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("Illegal transfer state transition"))
        }
        val now = System.currentTimeMillis()
        val approved = ledger.approveForSigning(
            "request_12345678", transferPolicy(), now - 10_000L, now + 10_000L, now
        )
        assertTrue(approved is LedgerAuthorizationResult.Approved)
        try {
            ledger.transition(
                "request_12345678", LedgerState.AUTHORIZED, LedgerState.SIGNED, "chain-signature-1"
            )
            throw AssertionError("Direct signing bypass should be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("Illegal transfer state transition"))
        }
        assertTrue(ledger.transition(
            "request_12345678", LedgerState.AUTHORIZED, LedgerState.SIGNING
        ))
        assertTrue(ledger.transition(
            "request_12345678", LedgerState.SIGNING, LedgerState.SIGNED, "5".repeat(88)
        ))
    }

    @Test
    fun submittedAndConfirmedRequireSignature() {
        ledger.claim(intent())
        ledger.transition("request_12345678", LedgerState.PREPARED, LedgerState.AWAITING_APPROVAL)
        val now = System.currentTimeMillis()
        val approved = ledger.approveForSigning(
            "request_12345678", transferPolicy(), now - 10_000L, now + 10_000L, now
        )
        assertTrue(approved is LedgerAuthorizationResult.Approved)
        assertTrue(ledger.transition("request_12345678", LedgerState.AUTHORIZED, LedgerState.SIGNING))
        try {
            ledger.transition("request_12345678", LedgerState.SIGNING, LedgerState.SIGNED)
            throw AssertionError("Expected missing durable signature to be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("signature"))
        }
        assertTrue(ledger.transition(
            "request_12345678", LedgerState.SIGNING, LedgerState.SIGNED, "5".repeat(88)
        ))
        try {
            ledger.transition("request_12345678", LedgerState.SIGNED, LedgerState.SUBMITTED)
            throw AssertionError("Expected missing signature to be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("signature"))
        }
        assertTrue(ledger.transition(
            "request_12345678", LedgerState.SIGNED, LedgerState.SUBMITTED, "5".repeat(88)
        ))
        assertFalse(ledger.transition(
            "request_12345678", LedgerState.SUBMITTED, LedgerState.UNKNOWN, "6".repeat(88)
        ))
        try {
            ledger.transition(
                "request_12345678", LedgerState.SIGNED, LedgerState.SUBMITTED, "6".repeat(88)
            )
            throw AssertionError("Submitted signature must match the persisted signature")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("must match"))
        }
        assertEquals("5".repeat(88), ledger.find("request_12345678")?.signature)
    }

    @Test
    fun confirmationMustPreserveTheOriginalSignature() {
        ledger.claim(intent())
        ledger.transition("request_12345678", LedgerState.PREPARED, LedgerState.AWAITING_APPROVAL)
        val now = System.currentTimeMillis()
        val approval = ledger.approveForSigning(
            "request_12345678", transferPolicy(), now - 10_000L, now + 10_000L, now
        )
        assertTrue(approval is LedgerAuthorizationResult.Approved)
        assertTrue(ledger.transition(
            "request_12345678", LedgerState.AUTHORIZED, LedgerState.SIGNING
        ))
        val signature = "8".repeat(88)
        assertTrue(ledger.transition(
            "request_12345678", LedgerState.SIGNING, LedgerState.SIGNED, signature
        ))
        assertTrue(ledger.transition(
            "request_12345678", LedgerState.SIGNED, LedgerState.SUBMITTED, signature
        ))
        try {
            ledger.transition(
                "request_12345678", LedgerState.SUBMITTED, LedgerState.CONFIRMED, "9".repeat(88)
            )
            throw AssertionError("Confirmation must reference the original transaction signature")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("must match"))
        }
        assertTrue(ledger.transition(
            "request_12345678", LedgerState.SUBMITTED, LedgerState.CONFIRMED, signature
        ))
        assertEquals(LedgerState.CONFIRMED, ledger.find("request_12345678")?.state)
    }

    @Test
    fun signedTransitionRejectsInvalidBase58AndWrongLengthSignature() {
        ledger.claim(intent())
        val now = System.currentTimeMillis()
        val result = ledger.claimAndAuthorize(
            intent(), transferPolicy(autonomous = true), now - 10_000L, now + 10_000L, now
        )
        // Existing claim record is not a fresh authorization; prepare a separate valid request.
        assertTrue(result is LedgerAuthorizationResult.Existing)
        val fresh = ledger.claimAndAuthorize(
            intent(requestId = "request_valid_sig01"),
            transferPolicy(autonomous = true), now - 10_000L, now + 10_000L, now + 1L
        )
        assertTrue(fresh is LedgerAuthorizationResult.Created)
        assertTrue(ledger.transition(
            "request_valid_sig01", LedgerState.AUTHORIZED, LedgerState.SIGNING
        ))
        try {
            ledger.transition(
                "request_valid_sig01", LedgerState.SIGNING, LedgerState.SIGNED, "not-a-signature"
            )
            throw AssertionError("Malformed signature should be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("base58"))
        }
        assertEquals(LedgerState.SIGNING, ledger.find("request_valid_sig01")?.state)
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

    private fun transferPolicy(
        dailyLimit: Long = 100L,
        autonomous: Boolean = false
    ) = WalletAgentPolicy(
        agentId = "test-agent",
        enabled = true,
        canTransfer = true,
        autonomousExecution = autonomous,
        maxTransferLamports = 100L,
        dailyLimitLamports = dailyLimit,
        allowedRecipients = if (autonomous) setOf(recipient) else emptySet()
    )

    @Test
    fun authorizedClaimReservesPendingSpendAgainstDailyLimit() {
        val now = System.currentTimeMillis()
        val start = now - 10_000L
        val end = now + 10_000L
        val first = ledger.claimAndAuthorize(
            intent(requestId = "request_first_001", amountLamports = 60L),
            transferPolicy(), start, end, now
        )
        assertTrue(first is LedgerAuthorizationResult.Created)
        assertEquals(
            LedgerState.AWAITING_APPROVAL,
            (first as LedgerAuthorizationResult.Created).record.state
        )

        val second = ledger.claimAndAuthorize(
            intent(requestId = "request_second_02", amountLamports = 50L),
            transferPolicy(), start, end, now + 1L
        )
        assertTrue(second is LedgerAuthorizationResult.Rejected)
        assertTrue(
            ((second as LedgerAuthorizationResult.Rejected).decision.reason)
                .contains("daily limit")
        )
    }

    @Test
    fun autonomousAuthorizationCreatesAuthorizedStateOnlyWithAllowlist() {
        val now = System.currentTimeMillis()
        val result = ledger.claimAndAuthorize(
            intent(requestId = "request_auto_0001", amountLamports = 25L),
            transferPolicy(autonomous = true),
            now - 10_000L, now + 10_000L, now
        )
        assertTrue(result is LedgerAuthorizationResult.Created)
        assertEquals(
            LedgerState.AUTHORIZED,
            (result as LedgerAuthorizationResult.Created).record.state
        )
        assertTrue(ledger.transition(
            "request_auto_0001", LedgerState.AUTHORIZED, LedgerState.SIGNING
        ))
        assertTrue(ledger.transition(
            "request_auto_0001", LedgerState.SIGNING, LedgerState.SIGNED, "7".repeat(88)
        ))
        assertEquals(
            LedgerState.SIGNED,
            ledger.find("request_auto_0001")?.state
        )
    }

    @Test
    fun mismatchedAgentAndInvalidWindowAreRejected() {
        val now = System.currentTimeMillis()
        val start = now - 10_000L
        val end = now + 10_000L
        val mismatch = ledger.claimAndAuthorize(
            intent(),
            WalletAgentPolicy(agentId = "different-agent", enabled = true, canTransfer = true,
                maxTransferLamports = 100L, dailyLimitLamports = 100L),
            start, end, now
        )
        assertTrue(mismatch is LedgerAuthorizationResult.Rejected)
        try {
            ledger.claimAndAuthorize(
                intent(requestId = "request_window_01"),
                transferPolicy(), end, start, now
            )
            throw AssertionError("Expected invalid window to fail closed")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("window"))
        }
    }

    @Test
    fun approvalRechecksDailyLimitBeforePromotingToSigningReady() {
        val now = System.currentTimeMillis()
        val start = now - 10_000L
        val end = now + 10_000L
        val first = ledger.claimAndAuthorize(
            intent(requestId = "request_budget_001", amountLamports = 60L),
            transferPolicy(), start, end, now
        )
        assertTrue(first is LedgerAuthorizationResult.Created)

        val secondIntent = intent(requestId = "request_budget_002", amountLamports = 50L)
        ledger.claim(secondIntent)
        assertTrue(ledger.transition(
            secondIntent.requestId, LedgerState.PREPARED, LedgerState.AWAITING_APPROVAL
        ))
        val approval = ledger.approveForSigning(
            secondIntent.requestId, transferPolicy(), start, end, now + 1L
        )
        assertTrue(approval is LedgerAuthorizationResult.Rejected)
        assertEquals(LedgerState.AWAITING_APPROVAL, ledger.find(secondIntent.requestId)?.state)
    }

    @Test
    fun callerCannotBackdateAuthorizationIntoAnOldSpendingWindow() {
        val now = System.currentTimeMillis()
        try {
            ledger.claimAndAuthorize(
                intent(requestId = "request_backdate01"),
                transferPolicy(),
                now - 100_000L,
                now - 50_000L,
                authorizationTimeEpochMs = now
            )
            throw AssertionError("Expected authorization outside window to be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("Authorization time"))
        }
    }

}
