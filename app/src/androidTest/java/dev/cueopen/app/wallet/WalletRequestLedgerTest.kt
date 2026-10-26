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
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient

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
        context.getSharedPreferences("wallet_agent_policies", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private fun intent(
        requestId: String = "request_12345678",
        recipientAddress: String = recipient,
        amountLamports: Long = 50L,
        createdAtEpochMs: Long = 1L,
        memo: String? = "test"
    ) = WalletTransferIntent(
        requestId = requestId,
        agentId = "test-agent",
        recipientAddress = recipientAddress,
        amountLamports = amountLamports,
        createdAtEpochMs = createdAtEpochMs,
        memo = memo
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
            assertTrue(expected.message.orEmpty().contains("Invalid Solana transaction signature"))
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
        assertTrue(ledger.abortSigningBeforePersistence(request.requestId))
        assertEquals(LedgerState.FAILED, ledger.find(request.requestId)?.state)
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
    fun signedTransactionBytesAreDurableAndSignatureBound() {
        val now = System.currentTimeMillis()
        val request = intent(requestId = "request_durable_tx01", amountLamports = 20L)
        val created = ledger.claimAndAuthorize(
            request, transferPolicy(autonomous = true),
            now - 10_000L, now + 10_000L, now
        )
        assertTrue(created is LedgerAuthorizationResult.Created)
        assertTrue(ledger.beginSigningAutonomously(
            request.requestId, transferPolicy(autonomous = true),
            now - 10_000L, now + 10_000L, now
        ) is LedgerSigningStartResult.Started)

        val signature = "67rpwLCuS5DGA8KGZXKsVQ7dnPb9goRLoKfgGbLfQg9WoLUgNY77E2jT11fem3coV9nAkguBACzrU1iyZM4B8roQ"
        val bytes = serializedSignedTransaction(signature)
        assertTrue(ledger.persistSignedTransaction(request.requestId, signature, bytes))
        assertEquals(LedgerState.SIGNED, ledger.find(request.requestId)?.state)
        assertTrue(bytes.contentEquals(ledger.signedTransaction(request.requestId)))
        assertFalse(ledger.abortSigningBeforePersistence(request.requestId))
        assertFalse(ledger.persistSignedTransaction(request.requestId, signature, bytes))
        try {
            ledger.persistSignedTransaction(
                request.requestId,
                "5n4yLmzWc14iDPJ3vZAszSSXTu1wWQzX5ZtZnVBcrAUHDKBNHEZmUdR9oQpg7JukPQfHqWWN5j8bZwounf3xrsM7",
                bytes
            )
            throw AssertionError("A signature mismatch must be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("does not match"))
        }
    }

    @Test
    fun knownSigningFailureCanReleaseReservationOnlyBeforeSignaturePersistence() {
        val now = System.currentTimeMillis()
        val request = intent(requestId = "request_abort_sign01", amountLamports = 20L)
        val created = ledger.claimAndAuthorize(
            request, transferPolicy(autonomous = true),
            now - 10_000L, now + 10_000L, now
        )
        assertTrue(created is LedgerAuthorizationResult.Created)
        assertTrue(ledger.beginSigningAutonomously(
            request.requestId, transferPolicy(autonomous = true),
            now - 10_000L, now + 10_000L, now
        ) is LedgerSigningStartResult.Started)
        assertTrue(ledger.abortSigningBeforePersistence(request.requestId))
        assertEquals(LedgerState.FAILED, ledger.find(request.requestId)?.state)
        assertFalse(ledger.abortSigningBeforePersistence(request.requestId))
    }

    @Test
    fun onlyFinalizedChainFailureCanReleaseSignedTransferReservation() {
        val now = System.currentTimeMillis()
        val request = intent(requestId = "request_chain_fail01", amountLamports = 20L)
        val created = ledger.claimAndAuthorize(
            request, transferPolicy(autonomous = true),
            now - 10_000L, now + 10_000L, now
        )
        assertTrue(created is LedgerAuthorizationResult.Created)
        assertTrue(ledger.beginSigningAutonomously(
            request.requestId, transferPolicy(autonomous = true),
            now - 10_000L, now + 10_000L, now
        ) is LedgerSigningStartResult.Started)
        val signature = "67rpwLCuS5DGA8KGZXKsVQ7dnPb9goRLoKfgGbLfQg9WoLUgNY77E2jT11fem3coV9nAkguBACzrU1iyZM4B8roQ"
        assertTrue(ledger.persistSignedTransaction(
            request.requestId, signature, serializedSignedTransaction(signature)
        ))
        try {
            ledger.markFinalizedChainFailure(
                request.requestId, signature, "confirmed", "InstructionError"
            )
            throw AssertionError("A non-final chain error must not release the reservation")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("finalized"))
        }
        assertEquals(LedgerState.SIGNED, ledger.find(request.requestId)?.state)
        assertTrue(ledger.markFinalizedChainFailure(
            request.requestId, signature, "finalized", "InstructionError"
        ))
        assertEquals(LedgerState.FAILED, ledger.find(request.requestId)?.state)
        assertFalse(ledger.markFinalizedChainFailure(
            request.requestId, signature, "finalized", "InstructionError"
        ))
    }

    @Test
    fun cancellationReleasesOnlyRequestsThatHaveNotStartedSigning() {
        val now = System.currentTimeMillis()
        val cancelRequest = intent(requestId = "request_cancel_001", amountLamports = 20L)
        val created = ledger.claimAndAuthorize(
            cancelRequest, transferPolicy(),
            now - 10_000L, now + 10_000L, now
        )
        assertTrue(created is LedgerAuthorizationResult.Created)
        assertTrue(ledger.cancelBeforeSigning(cancelRequest.requestId))
        assertEquals(LedgerState.FAILED, ledger.find(cancelRequest.requestId)?.state)

        val signingRequest = intent(requestId = "request_cancel_002", amountLamports = 20L)
        val autoCreated = ledger.claimAndAuthorize(
            signingRequest, transferPolicy(autonomous = true),
            now - 10_000L, now + 10_000L, now
        )
        assertTrue(autoCreated is LedgerAuthorizationResult.Created)
        assertTrue(ledger.beginSigningAutonomously(
            signingRequest.requestId, transferPolicy(autonomous = true),
            now - 10_000L, now + 10_000L, now
        ) is LedgerSigningStartResult.Started)
        assertFalse(ledger.cancelBeforeSigning(signingRequest.requestId))
        assertEquals(LedgerState.SIGNING, ledger.find(signingRequest.requestId)?.state)
    }

    @Test
    fun recentRecordsIncludesPendingAndTerminalRequests() {
        val now = System.currentTimeMillis()
        val pending = intent(requestId = "request_history_01", amountLamports = 10L)
        val terminal = intent(requestId = "request_history_02", amountLamports = 15L)
        assertTrue(ledger.claimAndAuthorize(
            pending, transferPolicy(), now - 10_000L, now + 10_000L, now
        ) is LedgerAuthorizationResult.Created)
        assertTrue(ledger.claimAndAuthorize(
            terminal, transferPolicy(), now - 10_000L, now + 10_000L, now + 1L
        ) is LedgerAuthorizationResult.Created)
        assertTrue(ledger.cancelBeforeSigning(terminal.requestId))
        val recentIds = ledger.recentRecords(10).map { it.requestId }.toSet()
        assertTrue(recentIds.contains(pending.requestId))
        assertTrue(recentIds.contains(terminal.requestId))
    }

    @Test
    fun executorCannotBroadcastOrReconcileAnotherAgentsRequest() = runBlocking {
        val now = System.currentTimeMillis()
        val request = intent(requestId = "request_agent_guard01", amountLamports = 20L, memo = null)
        val created = ledger.claimAndAuthorize(
            request, transferPolicy(autonomous = true),
            now - 10_000L, now + 10_000L, now
        )
        assertTrue(created is LedgerAuthorizationResult.Created)
        assertTrue(ledger.beginSigningAutonomously(
            request.requestId, transferPolicy(autonomous = true),
            now - 10_000L, now + 10_000L, now
        ) is LedgerSigningStartResult.Started)
        val signature = "67rpwLCuS5DGA8KGZXKsVQ7dnPb9goRLoKfgGbLfQg9WoLUgNY77E2jT11fem3coV9nAkguBACzrU1iyZM4B8roQ"
        assertTrue(ledger.persistSignedTransaction(
            request.requestId, signature, serializedSignedTransaction(signature)
        ))
        val executor = SolanaTransferExecutor(
            context,
            SolanaWalletRepository(context),
            ledger,
            SolanaRpcClient(endpoint = "https://rpc.test", client = OkHttpClient())
        )
        val policy = transferPolicy(autonomous = true)
        val windowStart = now - 10_000L
        val windowEnd = now + 10_000L
        val broadcast = executor.broadcastPersistedTransfer(
            request.requestId, "other-agent", policy, windowStart, windowEnd, humanConfirmed = false
        )
        val reconcile = executor.reconcileSignature(request.requestId, "other-agent")
        assertTrue(broadcast is TransferExecutionResult.Rejected)
        assertTrue(reconcile is TransferExecutionResult.Rejected)
        val unknownNetworkBroadcast = executor.broadcastPersistedTransfer(
            request.requestId, "test-agent", policy, windowStart, windowEnd, humanConfirmed = true
        )
        val unknownNetworkReconcile = executor.reconcileSignature(request.requestId, "test-agent")
        assertTrue(unknownNetworkBroadcast is TransferExecutionResult.Rejected)
        assertTrue(unknownNetworkReconcile is TransferExecutionResult.Rejected)
        assertEquals(LedgerState.SIGNED, ledger.find(request.requestId)?.state)

        val authorizedRequest = intent(
            requestId = "request_network_guard01", amountLamports = 10L, memo = null
        )
        assertTrue(ledger.claimAndAuthorize(
            authorizedRequest, transferPolicy(autonomous = true),
            now - 10_000L, now + 10_000L, now
        ) is LedgerAuthorizationResult.Created)
        val signResult = executor.signAuthorizedTransfer(
            authorizedRequest.requestId, transferPolicy(autonomous = true),
            now - 10_000L, now + 10_000L, humanApproval = false, signingTimeEpochMs = now
        )
        assertTrue(signResult is TransferExecutionResult.Rejected)
        assertEquals(LedgerState.AUTHORIZED, ledger.find(authorizedRequest.requestId)?.state)

        val store = WalletAgentPolicyStore(context)
        store.setManualTransferEnabled(SolanaNetwork.MAINNET, false)
        val manualAgentId = WalletAgentPolicyStore.manualAgentId(SolanaNetwork.MAINNET)
        val forgedPolicy = WalletAgentPolicy(
            agentId = manualAgentId,
            enabled = true,
            canTransfer = true,
            autonomousExecution = true,
            maxTransferLamports = 100L,
            dailyLimitLamports = 100L,
            allowedRecipients = setOf(recipient)
        )
        val manualRequest = WalletTransferIntent(
            requestId = "request_forged_policy01",
            agentId = manualAgentId,
            recipientAddress = recipient,
            amountLamports = 10L,
            createdAtEpochMs = now,
            network = SolanaNetwork.MAINNET
        )
        assertTrue(ledger.claimAndAuthorize(
            manualRequest, forgedPolicy,
            now - 10_000L, now + 10_000L, now
        ) is LedgerAuthorizationResult.Created)
        val mainnetExecutor = SolanaTransferExecutor(
            context, SolanaWalletRepository(context), ledger,
            SolanaRpcClient(endpoint = SolanaNetwork.MAINNET.rpcEndpoint, network = SolanaNetwork.MAINNET)
        )
        val forgedResult = mainnetExecutor.signAuthorizedTransfer(
            manualRequest.requestId, forgedPolicy,
            now - 10_000L, now + 10_000L, humanApproval = false, signingTimeEpochMs = now
        )
        assertTrue(forgedResult is TransferExecutionResult.Rejected)
        assertEquals(LedgerState.AUTHORIZED, ledger.find(manualRequest.requestId)?.state)

        assertTrue(ledger.beginSigningAutonomously(
            manualRequest.requestId, forgedPolicy,
            now - 10_000L, now + 10_000L, now
        ) is LedgerSigningStartResult.Started)
        val signature = "67rpwLCuS5DGA8KGZXKsVQ7dnPb9goRLoKfgGbLfQg9WoLUgNY77E2jT11fem3coV9nAkguBACzrU1iyZM4B8roQ"
        assertTrue(ledger.persistSignedTransaction(
            manualRequest.requestId, signature, serializedSignedTransaction(signature)
        ))
        val broadcast = mainnetExecutor.broadcastPersistedTransfer(
            manualRequest.requestId, manualAgentId, forgedPolicy,
            now - 10_000L, now + 10_000L, humanConfirmed = true
        )
        assertTrue(broadcast is TransferExecutionResult.Rejected)
        assertEquals(LedgerState.SIGNED, ledger.find(manualRequest.requestId)?.state)

        store.setManualTransferEnabled(SolanaNetwork.MAINNET, true)
        val enabledPolicy = store.manualTransferPolicy(SolanaNetwork.MAINNET)
        val unknownClusterExecutor = SolanaTransferExecutor(
            context, SolanaWalletRepository(context), ledger,
            SolanaRpcClient(endpoint = "https://rpc.test", client = OkHttpClient())
        )
        val wrongCluster = unknownClusterExecutor.broadcastPersistedTransfer(
            manualRequest.requestId, manualAgentId, enabledPolicy,
            now - 10_000L, now + 10_000L, humanConfirmed = true
        )
        assertTrue(wrongCluster is TransferExecutionResult.Rejected)
        assertEquals(LedgerState.SIGNED, ledger.find(manualRequest.requestId)?.state)
    }

    @Test
    fun sameRequestIdCannotBeReusedAcrossSolanaNetworks() {
        val now = System.currentTimeMillis()
        val devnetIntent = intent(
            requestId = "request_network_001",
            amountLamports = 20L
        ).copy(network = SolanaNetwork.DEVNET)
        val created = ledger.claimAndAuthorize(
            devnetIntent, transferPolicy(autonomous = true),
            now - 10_000L, now + 10_000L, now
        )
        assertTrue(created is LedgerAuthorizationResult.Created)
        try {
            ledger.claimAndAuthorize(
                devnetIntent.copy(network = SolanaNetwork.MAINNET),
                transferPolicy(autonomous = true),
                now - 10_000L, now + 10_000L, now + 1L
            )
            throw AssertionError("A request ID cannot be replayed on another network")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("different transfer payload"))
        }
        assertEquals(SolanaNetwork.DEVNET, ledger.find(devnetIntent.requestId)?.network)
    }

    @Test
    fun broadcastRevalidatesPolicyAndMovesReservationIntoCurrentDay() {
        val now = System.currentTimeMillis()
        val oldStart = now - 2L * 24L * 60L * 60L * 1000L
        val oldEnd = now - 24L * 60L * 60L * 1000L
        val request = intent(requestId = "request_broadcast_01", amountLamports = 20L, memo = null)
        val policy = transferPolicy(autonomous = true, dailyLimit = 100L)
        val created = ledger.claimAndAuthorize(
            request, policy, oldStart, oldEnd, oldStart + 1_000L
        )
        assertTrue(created is LedgerAuthorizationResult.Created)
        assertTrue(ledger.beginSigningAutonomously(
            request.requestId, policy, oldStart, oldEnd, oldStart + 2_000L
        ) is LedgerSigningStartResult.Started)
        val signature = "67rpwLCuS5DGA8KGZXKsVQ7dnPb9goRLoKfgGbLfQg9WoLUgNY77E2jT11fem3coV9nAkguBACzrU1iyZM4B8roQ"
        assertTrue(ledger.persistSignedTransaction(
            request.requestId, signature, serializedSignedTransaction(signature)
        ))

        val disabledPolicy = policy.copy(enabled = false, canTransfer = false)
        assertTrue(ledger.authorizeBroadcast(
            request.requestId, disabledPolicy, now - 10_000L, now + 10_000L, now
        ) is PolicyDecision.Denied)
        assertEquals(LedgerState.SIGNED, ledger.find(request.requestId)?.state)

        assertTrue(ledger.authorizeBroadcast(
            request.requestId, policy, now - 10_000L, now + 10_000L, now
        ) is PolicyDecision.Allowed)
        val next = ledger.claimAndAuthorize(
            intent(requestId = "request_after_bcast01", amountLamports = 90L, memo = null),
            policy, now - 10_000L, now + 10_000L, now + 1L
        )
        assertTrue(next is LedgerAuthorizationResult.Rejected)
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


    @Test
    fun recentDuplicateAgentIntentIsFoundEvenWithANewRequestId() {
        val now = System.currentTimeMillis()
        val original = intent(
            requestId = "request_12345678",
            createdAtEpochMs = now,
            memo = null
        )
        ledger.claim(original)

        val retry = intent(
            requestId = "request_87654321",
            createdAtEpochMs = now + 1L,
            memo = null
        )
        val duplicate = ledger.findRecentDuplicateIntent(retry, now + 1L)
        assertEquals(original.requestId, duplicate?.requestId)
        assertEquals(LedgerState.PREPARED, duplicate?.state)
    }


    @Test
    fun authorizedClaimAtomicallyDeduplicatesSameRecentAgentIntent() {
        val now = System.currentTimeMillis()
        val start = now - 10_000L
        val end = now + 10_000L
        val first = ledger.claimAndAuthorize(
            intent(
                requestId = "request_first_001",
                amountLamports = 50L,
                createdAtEpochMs = now,
                memo = null
            ),
            transferPolicy(), start, end, now
        )
        assertTrue(first is LedgerAuthorizationResult.Created)

        val repeated = ledger.claimAndAuthorize(
            intent(
                requestId = "request_second_01",
                amountLamports = 50L,
                createdAtEpochMs = now + 1L,
                memo = null
            ),
            transferPolicy(), start, end, now + 1L
        )
        assertTrue(repeated is LedgerAuthorizationResult.Existing)
        assertEquals("request_first_001", (repeated as LedgerAuthorizationResult.Existing).record.requestId)
    }

}
