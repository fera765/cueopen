package dev.cueopen.app.wallet

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.sol4k.Base58
import org.sol4k.Keypair
import org.sol4k.PublicKey
import org.sol4k.TransactionMessage
import org.sol4k.VersionedTransaction
import org.sol4k.instruction.TransferInstruction

/**
 * Local SOL transfer signing and durable broadcast orchestration.
 * Only policy-ledger transfer intents are accepted; arbitrary agent instructions are not.
 * Signed bytes and signature must be committed before any network broadcast.
 */
class SolanaTransferExecutor(
    context: Context,
    private val walletRepository: SolanaWalletRepository,
    private val ledger: WalletRequestLedger,
    private val rpc: SolanaRpcClient = SolanaRpcClient()
) {
    private val vault = WalletSecretVault(context.applicationContext)
    private val policyStore = WalletAgentPolicyStore(context.applicationContext)

    internal suspend fun signAuthorizedTransfer(
        requestId: String,
        policy: WalletAgentPolicy,
        windowStartEpochMs: Long,
        windowEndEpochMs: Long,
        humanApproval: Boolean = false,
        signingTimeEpochMs: Long = System.currentTimeMillis()
    ): TransferExecutionResult = withContext(Dispatchers.IO) {
        val record = ledger.find(requestId)
            ?: return@withContext TransferExecutionResult.Rejected("Transfer request does not exist")
        if (record.state != LedgerState.AUTHORIZED) {
            return@withContext TransferExecutionResult.Rejected("Transfer is not authorized")
        }
        if (rpc.network != record.network) {
            return@withContext TransferExecutionResult.Rejected("RPC network does not match the transfer request network")
        }
        if (record.agentId != WalletAgentPolicyStore.manualAgentId(record.network)) {
            return@withContext TransferExecutionResult.Rejected("No persisted manual policy grants this agent signing access")
        }
        val persistedPolicy = policyStore.manualTransferPolicy(record.network)
        if (!persistedPolicy.enabled || policy != persistedPolicy) {
            return@withContext TransferExecutionResult.Rejected("Transfer permission is disabled or the supplied policy is stale")
        }
        if (record.amountLamports <= 0L ||
            !SolanaAddressValidator.isValidAddress(record.recipientAddress)
        ) {
            return@withContext TransferExecutionResult.Rejected("Transfer payload is invalid")
        }
        if (record.memo != null) {
            return@withContext TransferExecutionResult.Rejected("Memo transfers are not supported by this signer")
        }

        val claim = if (humanApproval) {
            ledger.beginSigningAfterHumanApproval(
                requestId, persistedPolicy, windowStartEpochMs, windowEndEpochMs, signingTimeEpochMs
            )
        } else {
            ledger.beginSigningAutonomously(
                requestId, persistedPolicy, windowStartEpochMs, windowEndEpochMs, signingTimeEpochMs
            )
        }
        when (claim) {
            is LedgerSigningStartResult.AwaitingHumanApproval ->
                return@withContext TransferExecutionResult.AwaitingApproval
            is LedgerSigningStartResult.Rejected ->
                return@withContext TransferExecutionResult.Rejected(claim.decision.reason)
            is LedgerSigningStartResult.Started -> Unit
        }

        var seed: ByteArray? = null
        var keypair: Keypair? = null
        var serialized: ByteArray? = null
        try {
            if (!walletRepository.backupConfirmed()) {
                return@withContext abortAndReject(requestId, "Recovery phrase backup has not been confirmed")
            }
            val expectedAddress = walletRepository.publicAddress()
                ?: return@withContext abortAndReject(requestId, "Wallet metadata is unavailable")
            val secretSeed = vault.read()
            seed = secretSeed
            require(secretSeed.size == 32) { "Stored wallet seed has an unexpected length" }
            keypair = Keypair.fromSecretKey(secretSeed)
            if (keypair.publicKey.toBase58() != expectedAddress) {
                return@withContext abortAndReject(
                    requestId, "Stored secret does not match wallet metadata"
                )
            }
            if (record.recipientAddress == expectedAddress) {
                return@withContext abortAndReject(requestId, "Recipient is the same as the sending wallet")
            }

            val blockhash = rpc.getLatestBlockhash()
            val instruction = TransferInstruction(
                keypair.publicKey,
                PublicKey(record.recipientAddress),
                record.amountLamports
            )
            val message = TransactionMessage.newMessage(keypair.publicKey, blockhash.blockhash, instruction)
            val fee = rpc.getFeeForMessageLamports(message.serialize())
                ?: return@withContext abortAndReject(requestId, "Network fee is unavailable")
            val balance = rpc.getBalanceLamports(expectedAddress)
            if (record.amountLamports > Long.MAX_VALUE - fee ||
                balance < record.amountLamports + fee
            ) {
                return@withContext abortAndReject(
                    requestId, "Insufficient SOL for transfer amount and network fee"
                )
            }

            val transaction = VersionedTransaction(message)
            transaction.sign(keypair)
            val signedBytes = transaction.serialize()
            serialized = signedBytes
            if (signedBytes.size !in 66..MAX_TRANSACTION_BYTES || signedBytes[0].toInt() != 1) {
                return@withContext abortAndReject(requestId, "Unsupported signed transaction format")
            }
            val signature = Base58.encode(signedBytes.copyOfRange(1, 65))
            if (!SolanaSignatureValidator.isValid(signature)) {
                return@withContext abortAndReject(requestId, "Signer returned an invalid signature")
            }
            val simulation = rpc.simulateSerializedTransaction(signedBytes)
            if (!simulation.success) {
                return@withContext abortAndReject(
                    requestId, "Transaction simulation failed: " + (simulation.error ?: "unknown simulation error")
                )
            }
            if (!ledger.persistSignedTransaction(requestId, signature, signedBytes)) {
                return@withContext abortAndReject(
                    requestId, "Signed transaction could not be durably recorded; no broadcast was attempted"
                )
            }
            TransferExecutionResult.Signed(
                requestId, signature, signedBytes.size, fee, blockhash.lastValidBlockHeight
            )
        } catch (error: Exception) {
            // This executor never broadcasts before durable persistence; abort only if bytes were not stored.
            abortAndReject(requestId, error.message ?: "Could not sign transfer")
        } finally {
            seed?.fill(0)
            keypair?.secret?.fill(0)
            serialized?.fill(0)
        }
    }

    internal suspend fun broadcastPersistedTransfer(
        requestId: String,
        expectedAgentId: String,
        policy: WalletAgentPolicy,
        windowStartEpochMs: Long,
        windowEndEpochMs: Long,
        humanConfirmed: Boolean
    ): TransferExecutionResult = withContext(Dispatchers.IO) {
            require(expectedAgentId.isNotBlank())
            val record = ledger.find(requestId)
                ?: return@withContext TransferExecutionResult.Rejected("Transfer request does not exist")
            if (record.agentId != expectedAgentId || policy.agentId != expectedAgentId ||
                expectedAgentId != WalletAgentPolicyStore.manualAgentId(record.network)
            ) {
                return@withContext TransferExecutionResult.Rejected("Agent identity does not own this transfer request")
            }
            val persistedPolicy = policyStore.manualTransferPolicy(record.network)
            if (!persistedPolicy.enabled || policy != persistedPolicy) {
                return@withContext TransferExecutionResult.Rejected("Transfer permission is disabled or the supplied policy is stale")
            }
            if (rpc.network != record.network) {
                return@withContext TransferExecutionResult.Rejected("RPC network does not match the transfer request network")
            }
            if (record.state != LedgerState.SIGNED || record.signature.isNullOrBlank()) {
                return@withContext TransferExecutionResult.Rejected(
                    "Only a durably signed transaction may be broadcast"
                )
            }
            if (!humanConfirmed) {
                return@withContext TransferExecutionResult.Rejected("Explicit human confirmation is required before broadcast")
            }
            val policyDecision = ledger.authorizeBroadcast(
                requestId, persistedPolicy, windowStartEpochMs, windowEndEpochMs
            )
            if (policyDecision is PolicyDecision.Denied) {
                return@withContext TransferExecutionResult.Rejected(policyDecision.reason)
            }
            val bytes = ledger.signedTransaction(requestId)
                ?: return@withContext TransferExecutionResult.Rejected(
                    "Persisted transaction bytes are unavailable; refusing to rebuild or re-sign"
                )
            try {
                val latestPolicy = policyStore.manualTransferPolicy(record.network)
                if (!latestPolicy.enabled || latestPolicy != persistedPolicy) {
                    return@withContext TransferExecutionResult.Rejected(
                        "Transfer permission changed before broadcast; no network request was made"
                    )
                }
                val returnedSignature = rpc.sendPersistedTransaction(bytes)
                if (returnedSignature != record.signature) {
                    ledger.transition(requestId, LedgerState.SIGNED, LedgerState.UNKNOWN, record.signature)
                    return@withContext TransferExecutionResult.Unknown(
                        requestId, record.signature,
                        "RPC returned a signature different from the durable local signature"
                    )
                }
                if (!ledger.transition(
                    requestId, LedgerState.SIGNED, LedgerState.SUBMITTED, record.signature
                )) {
                    return@withContext TransferExecutionResult.Unknown(
                        requestId, record.signature, "Broadcast outcome requires reconciliation"
                    )
                }
                TransferExecutionResult.Submitted(requestId, record.signature)
            } catch (error: Exception) {
                // A timeout may occur after acceptance. Mark UNKNOWN and never blind-retry.
                ledger.transition(requestId, LedgerState.SIGNED, LedgerState.UNKNOWN, record.signature)
                TransferExecutionResult.Unknown(
                    requestId, record.signature,
                    error.message ?: "Network outcome is ambiguous; reconcile before any retry"
                )
            } finally {
                bytes.fill(0)
            }
        }

    internal suspend fun reconcileSignature(
        requestId: String,
        expectedAgentId: String
    ): TransferExecutionResult = withContext(Dispatchers.IO) {
            require(expectedAgentId.isNotBlank())
            val record = ledger.find(requestId)
                ?: return@withContext TransferExecutionResult.Rejected("Transfer request does not exist")
            if (record.agentId != expectedAgentId) {
                return@withContext TransferExecutionResult.Rejected("Agent identity does not own this transfer request")
            }
            if (rpc.network != record.network) {
                return@withContext TransferExecutionResult.Rejected("RPC network does not match the transfer request network")
            }
            val signature = record.signature
                ?: return@withContext TransferExecutionResult.Rejected("No persisted signature to reconcile")
            try {
                val status = rpc.getSignatureStatus(signature)
                    ?: return@withContext TransferExecutionResult.Unknown(
                        requestId, signature, "RPC has no status record; absence is not proof of failure"
                    )
                if (status.failedOnChain) {
                    val chainError = status.error ?: "Transaction failed on chain"
                    if (status.confirmationStatus == "finalized") {
                        ledger.markFinalizedChainFailure(
                            requestId, signature, status.confirmationStatus, chainError
                        )
                        return@withContext TransferExecutionResult.ChainFailed(
                            requestId, signature, chainError
                        )
                    }
                    return@withContext TransferExecutionResult.Unknown(
                        requestId, signature, "On-chain error is not finalized yet; reservation remains held"
                    )
                }
                if (status.isFinalized) {
                    val current = ledger.find(requestId)
                    if (current != null &&
                        current.state in setOf(LedgerState.SIGNED, LedgerState.SUBMITTED, LedgerState.UNKNOWN)
                    ) {
                        ledger.transition(requestId, current.state, LedgerState.CONFIRMED, signature)
                    }
                    return@withContext TransferExecutionResult.Confirmed(requestId, signature, status.slot)
                }
                TransferExecutionResult.Unknown(
                    requestId, signature,
                    "Transaction is not finalized yet (${status.confirmationStatus ?: "pending"})"
                )
            } catch (error: Exception) {
                TransferExecutionResult.Unknown(
                    requestId, signature, error.message ?: "Could not reconcile signature status"
                )
            }
        }

    private fun abortAndReject(requestId: String, reason: String): TransferExecutionResult.Rejected {
        ledger.abortSigningBeforePersistence(requestId)
        return TransferExecutionResult.Rejected(reason)
    }

    companion object {
        private const val MAX_TRANSACTION_BYTES = 1232
    }
}

sealed interface TransferExecutionResult {
    data class Signed(
        val requestId: String,
        val signature: String,
        val serializedBytes: Int,
        val feeLamports: Long?,
        val lastValidBlockHeight: Long?
    ) : TransferExecutionResult
    data class Submitted(val requestId: String, val signature: String) : TransferExecutionResult
    data class Confirmed(val requestId: String, val signature: String, val slot: Long) : TransferExecutionResult
    data class ChainFailed(val requestId: String, val signature: String, val reason: String) : TransferExecutionResult
    data class Unknown(val requestId: String, val signature: String, val reason: String) : TransferExecutionResult
    data object AwaitingApproval : TransferExecutionResult
    data class Rejected(val reason: String) : TransferExecutionResult
}
