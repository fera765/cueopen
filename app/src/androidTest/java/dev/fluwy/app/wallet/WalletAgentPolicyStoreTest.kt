package dev.fluwy.app.wallet

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WalletAgentPolicyStoreTest {
    private lateinit var context: Context
    private lateinit var store: WalletAgentPolicyStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("wallet_agent_policies", Context.MODE_PRIVATE)
            .edit().clear().commit()
        store = WalletAgentPolicyStore(context)
    }

    @After
    fun tearDown() {
        context.getSharedPreferences("wallet_agent_policies", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun transferPermissionDefaultsToDeniedAndIsScopedPerNetwork() {
        val recipient = "HAgk14JpMQLgt6rVgv7cBQFJWFto5Dqxi472uT3DKpqk"
        assertFalse(store.isManualTransferEnabled(SolanaNetwork.MAINNET))
        assertFalse(store.isManualTransferEnabled(SolanaNetwork.DEVNET))
        assertTrue(
            store.manualTransferPolicy(SolanaNetwork.MAINNET).authorize(
                WalletAction.TRANSFER, 1L, recipient, 0L
            ) is PolicyDecision.Denied
        )

        store.setManualTransferEnabled(SolanaNetwork.MAINNET, true)
        assertTrue(store.isManualTransferEnabled(SolanaNetwork.MAINNET))
        assertFalse(store.isManualTransferEnabled(SolanaNetwork.DEVNET))
        val recreatedStore = WalletAgentPolicyStore(context)
        assertTrue(recreatedStore.isManualTransferEnabled(SolanaNetwork.MAINNET))
        assertFalse(recreatedStore.isManualTransferEnabled(SolanaNetwork.DEVNET))
        assertTrue(
            store.manualTransferPolicy(SolanaNetwork.MAINNET).authorize(
                WalletAction.TRANSFER, 1L, recipient, 0L
            ) is PolicyDecision.RequiresUserApproval
        )
    }

    @Test
    fun manualPolicyRequiresApprovalAndEnforcesPerTransferAndDailyLimits() {
        val recipient = "HAgk14JpMQLgt6rVgv7cBQFJWFto5Dqxi472uT3DKpqk"
        store.setManualTransferEnabled(SolanaNetwork.DEVNET, true)
        val policy = store.manualTransferPolicy(SolanaNetwork.DEVNET)

        assertFalse(policy.autonomousExecution)
        assertTrue(
            policy.authorize(
                WalletAction.TRANSFER, 50_000_000L, recipient, 0L
            ) is PolicyDecision.RequiresUserApproval
        )
        assertTrue(
            policy.authorize(
                WalletAction.TRANSFER, 50_000_001L, recipient, 0L
            ) is PolicyDecision.Denied
        )
        assertTrue(
            policy.authorize(
                WalletAction.TRANSFER, 1L, recipient, 100_000_000L
            ) is PolicyDecision.Denied
        )
        assertTrue(
            policy.authorize(
                WalletAction.TRANSFER, 1L, recipient, Long.MAX_VALUE
            ) is PolicyDecision.Denied
        )
    }

    @Test
    fun agentReadPermissionsDefaultToDeniedPersistAndRemainIsolated() {
        val agentA = "agent-alpha"
        val agentB = "agent-beta"
        assertFalse(store.canAgentReadAddress(agentA))
        assertFalse(store.canAgentReadBalance(agentA))
        assertFalse(store.canAgentReadAddress(agentB))
        assertFalse(store.canAgentReadBalance(agentB))

        store.setAgentReadAddress(agentA, true)
        store.setAgentReadBalance(agentA, true)
        val recreated = WalletAgentPolicyStore(context)
        assertTrue(recreated.canAgentReadAddress(agentA))
        assertTrue(recreated.canAgentReadBalance(agentA))
        assertFalse(recreated.canAgentReadAddress(agentB))
        assertFalse(recreated.canAgentReadBalance(agentB))

        recreated.setAgentReadBalance(agentA, false)
        assertTrue(recreated.canAgentReadAddress(agentA))
        assertFalse(WalletAgentPolicyStore(context).canAgentReadBalance(agentA))
    }

    @Test
    fun completeAgentWalletGrantIsSavedAndRevokedAtomically() {
        val agent = "agent-atomic-wallet"
        store.setAgentCapabilities(agent, readAddress = true, readBalance = true, requestTransfer = true)
        assertTrue(store.canAgentReadAddress(agent))
        assertTrue(store.canAgentReadBalance(agent))
        assertTrue(store.canAgentRequestTransfer(agent))

        store.setAgentCapabilities(agent, readAddress = false, readBalance = false, requestTransfer = false)
        val recreated = WalletAgentPolicyStore(context)
        assertFalse(recreated.canAgentReadAddress(agent))
        assertFalse(recreated.canAgentReadBalance(agent))
        assertFalse(recreated.canAgentRequestTransfer(agent))
    }

    @Test
    fun agentReadGrantsRejectBlankAgentIdentity() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            store.setAgentReadAddress(" ", true)
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            store.setAgentReadBalance("", true)
        }
    }

    @Test
    fun disablingPermissionImmediatelyMakesPreviouslyReadPolicyStale() {
        store.setManualTransferEnabled(SolanaNetwork.MAINNET, true)
        val previouslyEnabled = store.manualTransferPolicy(SolanaNetwork.MAINNET)
        store.setManualTransferEnabled(SolanaNetwork.MAINNET, false)
        val current = store.manualTransferPolicy(SolanaNetwork.MAINNET)

        assertTrue(previouslyEnabled.enabled)
        assertFalse(current.enabled)
        assertTrue(
            current.authorize(
                WalletAction.TRANSFER,
                1L,
                "HAgk14JpMQLgt6rVgv7cBQFJWFto5Dqxi472uT3DKpqk",
                0L
            ) is PolicyDecision.Denied
        )
    }

    @Test
    fun transferIntentParsesLamportsExactlyAndRejectsPrecisionLoss() {
        val recipient = "HAgk14JpMQLgt6rVgv7cBQFJWFto5Dqxi472uT3DKpqk"
        val intent = WalletTransferIntent.fromSol(
            "request_1234567890", "agent-test", recipient, "0.000000001", 1L,
            network = SolanaNetwork.DEVNET
        )
        org.junit.Assert.assertEquals(1L, intent.amountLamports)
        org.junit.Assert.assertEquals(SolanaNetwork.DEVNET, intent.network)

        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            WalletTransferIntent.fromSol(
                "request_1234567890", "agent-test", recipient, "0.0000000001", 1L
            )
        }
    }

    @Test
    fun transferIntentRejectsZeroNegativeAndOverflowAmounts() {
        val recipient = "HAgk14JpMQLgt6rVgv7cBQFJWFto5Dqxi472uT3DKpqk"
        listOf("0", "-0.1", "9223372036.854775808").forEach { amount ->
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
                WalletTransferIntent.fromSol(
                    "request_1234567890", "agent-test", recipient, amount, 1L
                )
            }
        }
    }

    @Test
    fun transferIntentRejectsInvalidRequestIdentityAndRecipient() {
        val recipient = "HAgk14JpMQLgt6rVgv7cBQFJWFto5Dqxi472uT3DKpqk"
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            WalletTransferIntent(
                "short", "agent-test", recipient, 1L, 1L
            )
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            WalletTransferIntent(
                "request_1234567890", " ", recipient, 1L, 1L
            )
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            WalletTransferIntent(
                "request_1234567890", "agent-test", "not-a-solana-address", 1L, 1L
            )
        }
    }


    @Test
    fun agentTransferRequestsDefaultToDeniedAndRequireHumanApproval() {
        val agentA = "agent-wallet-request-a"
        val agentB = "agent-wallet-request-b"
        val recipient = "HAgk14JpMQLgt6rVgv7cBQFJWFto5Dqxi472uT3DKpqk"

        assertFalse(store.canAgentRequestTransfer(agentA))
        assertFalse(store.transferPolicy(agentA, SolanaNetwork.DEVNET).enabled)
        assertTrue(
            store.transferPolicy(agentA, SolanaNetwork.DEVNET).authorize(
                WalletAction.TRANSFER, 1L, recipient, 0L
            ) is PolicyDecision.Denied
        )

        store.setAgentRequestTransfer(agentA, true)
        val policy = store.transferPolicy(agentA, SolanaNetwork.DEVNET)
        assertTrue(policy.enabled)
        assertTrue(policy.canTransfer)
        assertFalse(policy.autonomousExecution)
        assertTrue(
            policy.authorize(WalletAction.TRANSFER, 1L, recipient, 0L)
                is PolicyDecision.RequiresUserApproval
        )
        assertFalse(store.canAgentRequestTransfer(agentB))
        assertFalse(store.transferPolicy(agentB, SolanaNetwork.DEVNET).enabled)
        assertTrue(WalletAgentPolicyStore(context).canAgentRequestTransfer(agentA))

        store.setAgentRequestTransfer(agentA, false)
        assertFalse(store.transferPolicy(agentA, SolanaNetwork.DEVNET).enabled)
    }

    @Test
    fun agentTransferPolicyEnforcesPerRequestAndDailyLimits() {
        val agentId = "agent-wallet-limits"
        val recipient = "HAgk14JpMQLgt6rVgv7cBQFJWFto5Dqxi472uT3DKpqk"
        store.setAgentRequestTransfer(agentId, true)
        val policy = store.transferPolicy(agentId, SolanaNetwork.MAINNET)

        assertTrue(
            policy.authorize(WalletAction.TRANSFER, 50_000_000L, recipient, 0L)
                is PolicyDecision.RequiresUserApproval
        )
        assertTrue(
            policy.authorize(WalletAction.TRANSFER, 50_000_001L, recipient, 0L)
                is PolicyDecision.Denied
        )
        assertTrue(
            policy.authorize(WalletAction.TRANSFER, 1L, recipient, 100_000_000L)
                is PolicyDecision.Denied
        )
    }


    @Test
    fun agentTransferLimitsAndRecipientAllowlistPersistAndEnforce() {
        val agentId = "agent-wallet-custom-policy"
        val recipient = "HAgk14JpMQLgt6rVgv7cBQFJWFto5Dqxi472uT3DKpqk"
        val otherRecipient = "11111111111111111111111111111111"
        store.setAgentRequestTransfer(agentId, true)
        store.setAgentTransferSettings(agentId, 10_000_000L, 20_000_000L, setOf(recipient))

        val recreated = WalletAgentPolicyStore(context)
        val policy = recreated.transferPolicy(agentId, SolanaNetwork.DEVNET)
        org.junit.Assert.assertEquals(10_000_000L, policy.maxTransferLamports)
        org.junit.Assert.assertEquals(20_000_000L, policy.dailyLimitLamports)
        org.junit.Assert.assertEquals(setOf(recipient), policy.allowedRecipients)
        assertTrue(
            policy.authorize(WalletAction.TRANSFER, 1L, recipient, 0L)
                is PolicyDecision.RequiresUserApproval
        )
        assertTrue(
            policy.authorize(WalletAction.TRANSFER, 1L, otherRecipient, 0L)
                is PolicyDecision.Denied
        )
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            recreated.setAgentTransferLimits(agentId, 50_000_001L, 100_000_000L)
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            recreated.setAgentAllowedRecipients(agentId, setOf("invalid-address"))
        }
    }

}
