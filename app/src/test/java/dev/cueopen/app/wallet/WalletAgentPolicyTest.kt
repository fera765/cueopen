package dev.cueopen.app.wallet

import org.junit.Assert.assertEquals
import org.junit.Test

class WalletAgentPolicyTest {
    private val validRecipient = "HAgk14JpMQLgt6rVgv7cBQFJWFto5Dqxi472uT3DKpqk"
    private val otherValidRecipient = "11111111111111111111111111111111"
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
            policy(enabled = false).authorize(WalletAction.TRANSFER, 1L, validRecipient)
        )
    }

    @Test
    fun transferRequiresApprovalByDefault() {
        assertEquals(
            PolicyDecision.RequiresUserApproval,
            policy().authorize(WalletAction.TRANSFER, 50L, validRecipient)
        )
    }

    @Test
    fun cumulativeDailyLimitIsEnforced() {
        assertEquals(
            PolicyDecision.Denied("Transfer exceeds the remaining daily limit"),
            policy().authorize(
                action = WalletAction.TRANSFER,
                amountLamports = 60L,
                recipient = validRecipient,
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
                recipient = validRecipient,
                spentTodayLamports = -1L
            )
        )
    }

    @Test
    fun autonomousTransferWithoutAllowlistIsAllowedForAnyRecipient() {
        // Allowlist is optional: with an empty allowlist, an autonomous transfer to any valid
        // recipient is authorized (per-transaction/daily limits still apply and are tested below).
        assertEquals(
            PolicyDecision.Allowed,
            policy(autonomous = true).authorize(WalletAction.TRANSFER, 10L, validRecipient)
        )
    }

    @Test
    fun autonomousTransferToAllowedRecipientCanBeAuthorized() {
        assertEquals(
            PolicyDecision.Allowed,
            policy(autonomous = true, recipients = setOf(validRecipient))
                .authorize(WalletAction.TRANSFER, 10L, validRecipient)
        )
    }

    @Test
    fun perTransferLimitIsEnforced() {
        assertEquals(
            PolicyDecision.Denied("Transfer exceeds the per-transaction limit"),
            policy().authorize(WalletAction.TRANSFER, 101L, validRecipient)
        )
    }

    @Test
    fun recipientOutsideExplicitAllowlistIsDenied() {
        assertEquals(
            PolicyDecision.Denied("Recipient is not allowlisted"),
            policy(recipients = setOf(validRecipient))
                .authorize(WalletAction.TRANSFER, 10L, otherValidRecipient)
        )
    }

    @Test
    fun transferPermissionIsRequired() {
        assertEquals(
            PolicyDecision.Denied("Transfers are not permitted"),
            WalletAgentPolicy(
                agentId = "read-only-agent",
                enabled = true,
                canReadBalance = true,
                maxTransferLamports = 100L,
                dailyLimitLamports = 150L
            ).authorize(WalletAction.TRANSFER, 1L, validRecipient)
        )
    }

    @Test
    fun spendingStateAboveDailyLimitIsDeniedWithoutOverflow() {
        assertEquals(
            PolicyDecision.Denied("Transfer exceeds the remaining daily limit"),
            policy().authorize(
                action = WalletAction.TRANSFER,
                amountLamports = 1L,
                recipient = validRecipient,
                spentTodayLamports = Long.MAX_VALUE
            )
        )
    }

    @Test
    fun exactRemainingDailyLimitIsAllowedForAutonomousRecipient() {
        assertEquals(
            PolicyDecision.Allowed,
            policy(autonomous = true, recipients = setOf(validRecipient))
                .authorize(
                    action = WalletAction.TRANSFER,
                    amountLamports = 50L,
                    recipient = validRecipient,
                    spentTodayLamports = 100L
                )
        )
    }

    @Test
    fun malformedRecipientIsDeniedBeforeAllowlistOrApproval() {
        assertEquals(
            PolicyDecision.Denied("Recipient is not a valid Solana public address"),
            policy().authorize(WalletAction.TRANSFER, 10L, "not-a-solana-address")
        )
    }
}
