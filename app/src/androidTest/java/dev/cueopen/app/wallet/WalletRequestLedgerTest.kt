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
        assertTrue(ledger.beginSigningAfterHumanApproval(
            "request_12345678", transferPolicy(), now - 10_000L, now + 10_000L, now
        ) is LedgerSigningStartResult.Started)
        try {
            ledger.transition("request_12345678", LedgerState.SIGNING, LedgerState.SIGNED,
                "67rpwLCuS5DGA8KGZXKsVQ7dnPb9goRLoKfgGbLfQg9WoLUgNY77E2jT11fem3coV9nAkguBACzrU1iyZM4B8roQ")
            throw AssertionError("A policy-checked signing claim must use a dedicated signer persistence path")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("persistSignedTransaction"))
        }
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
        assertTrue(ledger.beginSigningAfterHumanApproval(
            "request_12345678", transferPolicy(), now - 10_000L, now + 10_000L, now
        ) is LedgerSigningStartResult.Started)
        try {
            ledger.transition("request_12345678", LedgerState.SIGNING, LedgerState.SIGNED, "67rpwLCuS5DGA8KGZXKsVQ7dnPb9goRLoKfgGbLfQg9WoLUgNY77E2jT11fem3coV9nAkguBACzrU1iyZM4B8roQ")
            throw AssertionError("Generic transition must not persist a signature")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("persistSignedTransaction"))
        }
        assertTrue(ledger.persistSignedTransaction(
            "request_12345678", "67rpwLCuS5DGA8KGZXKsVQ7dnPb9goRLoKfgGbLfQg9WoLUgNY77E2jT11fem3coV9nAkguBACzrU1iyZM4B8roQ",
            serializedSignedTransaction("67rpwLCuS5DGA8KGZXKsVQ7dnPb9goRLoKfgGbLfQg9WoLUgNY77E2jT11fem3coV9nAkguBACzrU1iyZM4B8roQ")
        ))
        try {
            ledger.transition("request_12345678", LedgerState.SIGNED, LedgerState.SUBMITTED)
            throw AssertionError("Expected missing signature to be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("signature"))
        }
        assertTrue(ledger.transition(
            "request_12345678", LedgerState.SIGNED, LedgerState.SUBMITTED, "67rpwLCuS5DGA8KGZXKsVQ7dnPb9goRLoKfgGbLfQg9WoLUgNY77E2jT11fem3coV9nAkguBACzrU1iyZM4B8roQ"
        ))
        assertFalse(ledger.transition(
            "request_12345678", LedgerState.SUBMITTED, LedgerState.UNKNOWN, "5n4yLmzWc14iDPJ3vZAszSSXTu1wWQzX5ZtZnVBcrAUHDKBNHEZmUdR9oQpg7JukPQfHqWWN5j8bZwounf3xrsM7"
        ))
        assertFalse(ledger.transition(
            "request_12345678", LedgerState.SUBMITTED, LedgerState.UNKNOWN, "67rpwLCuS5DGA8KGZXKsVQ7dnPb9goRLoKfgGbLfQg9WoLUgNY77E2jT11fem3coV9nAkguBACzrU1iyZM4B8roQ"
        ))
        try {
            ledger.transition(
                "request_12345678", LedgerState.SIGNED, LedgerState.SUBMITTED, "5n4yLmzWc14iDPJ3vZAszSSXTu1wWQzX5ZtZnVBcrAUHDKBNHEZmUdR9oQpg7JukPQfHqWWN5j8bZwounf3xrsM7"
            )
            throw AssertionError("Submitted signature must match the persisted signature")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("must match"))
        }
        assertEquals("67rpwLCuS5DGA8KGZXKsVQ7dnPb9goRLoKfgGbLfQg9WoLUgNY77E2jT11fem3coV9nAkguBACzrU1iyZM4B8roQ", ledger.find("request_12345678")?.signature)
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
        assertTrue(ledger.beginSigningAfterHumanApproval(
            "request_12345678", transferPolicy(), now - 10_000L, now + 10_000L, now
        ) is LedgerSigningStartResult.Started)
        val signature = "4QuYxZ8wHiUWSRDBNgYuzbk6Bvj6nsHECYn7r4ZSc7mLsDz7v2QQV29y11SmWN5XzSBnAnw7mofZyg9ehu37kuXw"
        assertTrue(ledger.persistSignedTransaction(
            "request_12345678", signature, serializedSignedTransaction(signature)
        ))
        assertTrue(ledger.transition(
            "request_12345678", LedgerState.SIGNED, LedgerState.SUBMITTED, signature
        ))
        try {
            ledger.transition(
                "request_12345678", LedgerState.SUBMITTED, LedgerState.CONFIRMED, "5n4yLmzWc14iDPJ3vZAszSSXTu1wWQzX5ZtZnVBcrAUHDKBNHEZmUdR9oQpg7JukPQfHqWWN5j8bZwounf3xrsM7"
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
        val now = System.currentTimeMillis()
        val fresh = ledger.claimAndAuthorize(
            intent(requestId = "request_valid_sig01"),
            transferPolicy(autonomous = true), now - 10_000L, now + 10_000L, now + 1L
        )
        assertTrue(fresh is LedgerAuthorizationResult.Created)
        assertTrue(ledger.beginSigningAutonomously(
            "request_valid_sig01", transferPolicy(autonomous = true),
            now - 10_000L, now + 10_000L, now
        ) is LedgerSigningStartResult.Started)
        try {
            ledger.persistSignedTransaction(
                "request_valid_sig01", "not-a-signature", ByteArray(70) { 1 }
            )
            throw AssertionError("Malformed signature should be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("base58"))
        }
        assertEquals(LedgerState.SIGNING, ledger.find("request_valid_sig01")?.state)
    }

    @Test
    fun ambiguousOnChainStatesCannotBeMarkedFailedToReleaseDailyReservation() {
        val now = System.currentTimeMillis()
        val intent = intent(requestId = "request_ambiguous01", amountLamports = 40L)
        val authorized = ledger.claimAndAuthorize(
            intent, transferPolicy(autonomous = true),
            now - 10_000L, now + 10_000L, now
        )
        assertTrue(authorized is LedgerAuthorizationResult.Created)
        assertTrue(ledger.beginSigningAutonomously(
            intent.requestId, transferPolicy(autonomous = true),
            now - 10_000L, now + 10_000L, now
        ) is LedgerSigningStartResult.Started)
        try {
            ledger.transition(intent.requestId, LedgerState.SIGNING, LedgerState.FAILED)
            throw AssertionError("Signing state must remain reserved when outcome is ambiguous")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("Illegal transfer state transition"))
        }
        assertTrue(ledger.transition(
            intent.requestId, LedgerState.SIGNING, LedgerState.UNKNOWN
        ))
        try {
            ledger.transition(intent.requestId, LedgerState.UNKNOWN, LedgerState.FAILED)
            throw AssertionError("Unknown chain outcome must not release reserved spend")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("Illegal transfer state transition"))
        }
        assertEquals(LedgerState.UNKNOWN, ledger.find(intent.requestId)?.state)
        val second = ledger.claimAndAuthorize(
            intent(requestId = "request_budget_left", amountLamports = 70L),
            transferPolicy(autonomous = true),
            now - 10_000L, now + 10_000L, now + 1L
        )
        assertTrue(second is LedgerAuthorizationResult.Rejected)
    }

    @Test
    fun replayRevokesSigningStateWhenPolicyNoLongerAllowsAutonomousExecution() {
        val now = System.currentTimeMillis()
        val request = intent(requestId = "request_revoke_001", amountLamports = 20L)
        val initial = ledger.claimAndAuthorize(
            request, transferPolicy(autonomous = true),
            now - 10_000L, now + 10_000L, now
        )
        assertTrue(initial is LedgerAuthorizationResult.Created)
        assertTrue(ledger.beginSigningAutonomously(
            request.requestId, transferPolicy(autonomous = true),
            now - 10_000L, now + 10_000L, now
        ) is LedgerSigningStartResult.Started)
        val revokedPolicy = WalletAgentPolicy(
            agentId = "test-agent",
            enabled = false,
            canTransfer = false,
            maxTransferLamports = 100L,
            dailyLimitLamports = 100L,
            allowedRecipients = setOf(recipient)
        )
        val replay = ledger.claimAndAuthorize(
            request, revokedPolicy,
            now - 10_000L, now + 10_000L, now + 1L
        )
        assertTrue(replay is LedgerAuthorizationResult.Existing)
        assertEquals(LedgerState.UNKNOWN, ledger.find(request.requestId)?.state)
    }

    @Test
    fun humanSigningPathRequiresPersistedApprovalTimestamp() {
        val now = System.currentTimeMillis()
        val start = now - 10_000L
        val end = now + 10_000L
        val request = intent(requestId = "request_no_consent01", amountLamports = 20L)
        val created = ledger.claimAndAuthorize(
            request, transferPolicy(autonomous = true), start, end, now
        )
        assertTrue(created is LedgerAuthorizationResult.Created)
        val result = ledger.beginSigningAfterHumanApproval(
            request.requestId, transferPolicy(autonomous = true), start, end, now
        )
        assertTrue(result is LedgerSigningStartResult.AwaitingHumanApproval)
        assertEquals(LedgerState.AWAITING_APPROVAL, ledger.find(request.requestId)?.state)
        assertEquals(0L, ledger.find(request.requestId)?.humanApprovedAtEpochMs)
    }

    @Test
    fun expiredHumanApprovalMustBeRepeatedBeforeSigning() {
        val now = System.currentTimeMillis()
        val start = now - 300_000L
        val end = now + 10_000L
        val request = intent(requestId = "request_old_consent01", amountLamports = 20L)
        val created = ledger.claimAndAuthorize(
            request, transferPolicy(), start, end, now
        )
        assertTrue(created is LedgerAuthorizationResult.Created)
        val approval = ledger.approveForSigning(
            request.requestId, transferPolicy(), start, end, now - 180_000L
        )
        assertTrue(approval is LedgerAuthorizationResult.Approved)
        assertTrue((approval as LedgerAuthorizationResult.Approved).record.humanApprovedAtEpochMs > 0L)
        val startResult = ledger.beginSigningAfterHumanApproval(
            request.requestId, transferPolicy(), start, end, now
        )
        assertTrue(startResult is LedgerSigningStartResult.AwaitingHumanApproval)
        assertEquals(LedgerState.AWAITING_APPROVAL, ledger.find(request.requestId)?.state)
        assertEquals(0L, ledger.find(request.requestId)?.humanApprovedAtEpochMs)
    }

    @Test
    fun terminalStatesCannotBeReopened() {
        ledger.claim(intent())
        assertTrue(ledger.transition(
            "request_12345678", LedgerState.PREPARED, LedgerState.FAILED
        ))
        try {
            ledger.transition("request_12345678", LedgerState.FAILED, LedgerState.PREPARED)
            throw AssertionError("Terminal failed state cannot be reopened")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("Illegal transfer state transition"))
        }
    }

    private fun serializedSignedTransaction(signature: String): ByteArray =
        byteArrayOf(1) + org.sol4k.Base58.decode(signature) + byteArrayOf(1, 2, 3)

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
        assertTrue(ledger.beginSigningAutonomously(
            "request_auto_0001", transferPolicy(autonomous = true),
            now - 10_000L, now + 10_000L, now
        ) is LedgerSigningStartResult.Started)
        assertTrue(ledger.persistSignedTransaction(
            "request_auto_0001", "5n4yLmzWc14iDPJ3vZAszSSXTu1wWQzX5ZtZnVBcrAUHDKBNHEZmUdR9oQpg7JukPQfHqWWN5j8bZwounf3xrsM7",
            serializedSignedTransaction("5n4yLmzWc14iDPJ3vZAszSSXTu1wWQzX5ZtZnVBcrAUHDKBNHEZmUdR9oQpg7JukPQfHqWWN5j8bZwounf3xrsM7")
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
