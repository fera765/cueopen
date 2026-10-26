package dev.cueopen.app.wallet

import org.junit.Assert.assertEquals
import org.junit.Test

class WalletAgentPolicyTest {
    private fun policy(
        enabled: Boolean = true,
        autonomous: Boolean = false,
        recipients: Set<String> = emptySet()
    ) = WalletAgentPolicy(
        agentId = "trading-agent",
        enabled = enabled,
        canTransfer = true,
        autonomousExecution = autonomous,
        maxTransferLamports = 100L,
        dailyLimitLamports = 150L,
        allowedRecipients = recipients
    )

    @Test
    fun disabledAgentIsDenied() {
        assertEquals(
            PolicyDecision.Denied("Wallet access is disabled for this agent"),
            policy(enabled = false).authorize(WalletAction.TRANSFER, 1L, "recipient")
        )
    }

    @Test
    fun transferRequiresApprovalByDefault() {
        assertEquals(
            PolicyDecision.RequiresUserApproval,
            policy().authorize(WalletAction.TRANSFER, 50L, "recipient")
        )
    }

    @Test
    fun cumulativeDailyLimitIsEnforced() {
        assertEquals(
            PolicyDecision.Denied("Transfer exceeds the remaining daily limit"),
            policy().authorize(
                action = WalletAction.TRANSFER,
                amountLamports = 60L,
                recipient = "recipient",
                spentTodayLamports = 100L
            )
        )
    }

    @Test
    fun invalidDailySpendingStateFailsClosed() {
        assertEquals(
            PolicyDecision.Denied("Invalid daily spending state"),
            policy().authorize(
                action = WalletAction.TRANSFER,
                amountLamports = 1L,
                recipient = "recipient",
                spentTodayLamports = -1L
            )
        )
    }

    @Test
    fun autonomousTransfersRequireExplicitAllowlist() {
        assertEquals(
            PolicyDecision.Denied("Autonomous transfers require an explicit recipient allowlist"),
            policy(autonomous = true).authorize(WalletAction.TRANSFER, 10L, "recipient")
        )
    }

    @Test
    fun autonomousTransferToAllowedRecipientCanBeAuthorized() {
        assertEquals(
            PolicyDecision.Allowed,
            policy(autonomous = true, recipients = setOf("recipient"))
                .authorize(WalletAction.TRANSFER, 10L, "recipient")
        )
    }

    @Test
    fun perTransferLimitIsEnforced() {
        assertEquals(
            PolicyDecision.Denied("Transfer exceeds the per-transaction limit"),
            policy().authorize(WalletAction.TRANSFER, 101L, "recipient")
        )
    }
}
