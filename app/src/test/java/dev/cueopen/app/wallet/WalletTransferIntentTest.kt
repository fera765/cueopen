package dev.cueopen.app.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WalletTransferIntentTest {
    private val validRecipient = "HAgk14JpMQLgt6rVgv7cBQFJWFto5Dqxi472uT3DKpqk"

    @Test
    fun parsesSolExactlyIntoLamports() {
        val intent = WalletTransferIntent.fromSol(
            requestId = "request_12345678",
            agentId = "user",
            recipientAddress = validRecipient,
            amountSol = "1.000000001",
            createdAtEpochMs = 1L
        )
        assertEquals(1_000_000_001L, intent.amountLamports)
    }

    @Test
    fun rejectsMoreThanNineDecimalPlaces() {
        assertThrows(IllegalArgumentException::class.java) {
            WalletTransferIntent.fromSol(
                requestId = "request_12345678",
                agentId = "user",
                recipientAddress = validRecipient,
                amountSol = "0.0000000001",
                createdAtEpochMs = 1L
            )
        }
    }

    @Test
    fun rejectsZeroAndNegativeAmounts() {
        for (amount in listOf("0", "-0.1")) {
            assertThrows(IllegalArgumentException::class.java) {
                WalletTransferIntent.fromSol(
                    requestId = "request_12345678",
                    agentId = "user",
                    recipientAddress = validRecipient,
                    amountSol = amount,
                    createdAtEpochMs = 1L
                )
            }
        }
    }

    @Test
    fun rejectsMalformedRecipient() {
        assertThrows(IllegalArgumentException::class.java) {
            WalletTransferIntent.fromSol(
                requestId = "request_12345678",
                agentId = "user",
                recipientAddress = "not-a-solana-address",
                amountSol = "0.5",
                createdAtEpochMs = 1L
            )
        }
    }

    @Test
    fun rejectsAmountOutsideLongLamportRange() {
        assertThrows(IllegalArgumentException::class.java) {
            WalletTransferIntent.fromSol(
                requestId = "request_12345678",
                agentId = "user",
                recipientAddress = validRecipient,
                amountSol = "9223372036.854775808",
                createdAtEpochMs = 1L
            )
        }
    }

    @Test
    fun rejectsInvalidRequestIdAndMemoLength() {
        assertThrows(IllegalArgumentException::class.java) {
            WalletTransferIntent(
                requestId = "short",
                agentId = "user",
                recipientAddress = validRecipient,
                amountLamports = 1L,
                createdAtEpochMs = 1L
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            WalletTransferIntent(
                requestId = "request_12345678",
                agentId = "user",
                recipientAddress = validRecipient,
                amountLamports = 1L,
                createdAtEpochMs = 1L,
                memo = "x".repeat(201)
            )
        }
    }
}
