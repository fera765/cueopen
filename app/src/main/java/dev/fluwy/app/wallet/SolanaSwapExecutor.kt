package dev.fluwy.app.wallet

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.sol4k.Keypair
import org.sol4k.VersionedTransaction

/**
 * Signs a Jupiter-built swap transaction locally and broadcasts it. The LLM/agent never reaches
 * this path: swaps are user-initiated from the wallet UI. The wallet secret is read only here,
 * used to sign, and zeroed immediately. sol4k's sign()/serialize() fail closed unless the stored
 * wallet is a required signer of the transaction, so a transaction that does not require the
 * user's signature cannot be serialized or sent.
 */
class SolanaSwapExecutor(
    context: Context,
    private val repository: SolanaWalletRepository,
    private val rpc: SolanaRpcClient,
    private val swapClient: SolanaSwapClient = SolanaSwapClient()
) {
    private val vault = WalletSecretVault(context.applicationContext)

    private companion object {
        // Buffer for network fee + possible destination token-account rent (~0.00204 SOL).
        const val FEE_AND_RENT_BUFFER_LAMPORTS = 3_000_000L
    }

    suspend fun executeSwap(
        inputMint: String,
        outputMint: String,
        amountRaw: Long,
        slippageBps: Int,
        network: SolanaNetwork
    ): SwapResult = withContext(Dispatchers.IO) {
        if (network != SolanaNetwork.MAINNET) {
            return@withContext SwapResult.Rejected("A troca via Jupiter só está disponível na Mainnet.")
        }
        if (rpc.network != network) {
            return@withContext SwapResult.Rejected("A rede do RPC não corresponde à rede selecionada.")
        }
        if (!repository.hasWallet() || !repository.backupConfirmed()) {
            return@withContext SwapResult.Rejected("Carteira indisponível ou backup não confirmado.")
        }
        val expectedAddress = repository.publicAddress()
            ?: return@withContext SwapResult.Rejected("Metadados da carteira indisponíveis.")

        // Pre-check SOL balance so a zero/low balance gives a clear message instead of a raw
        // "AccountNotFound" from the on-chain preflight. Needs the swap amount plus fee + ATA rent.
        if (inputMint == SolanaSwapClient.SOL_MINT) {
            val balance = runCatching { SolanaRpcClient.balanceWithFallback(network, expectedAddress) }.getOrNull()
            if (balance != null) {
                if (balance == 0L) {
                    return@withContext SwapResult.Rejected("Sua carteira não tem SOL. Deposite SOL para pagar a troca e a taxa de rede antes de trocar.")
                }
                if (balance < amountRaw + FEE_AND_RENT_BUFFER_LAMPORTS) {
                    return@withContext SwapResult.Rejected("Saldo de SOL insuficiente: a troca precisa do valor trocado mais ~0,003 SOL de taxa/rent. Reduza o valor ou deposite mais SOL.")
                }
            }
        } else {
            // Token->token (ou token->SOL): precheck real do saldo do token de entrada para
            // mensagem clara em vez de um "AccountNotFound"/"InsufficientFunds" cru no preflight.
            // Também exige um mínimo de SOL para a taxa de rede e eventual rent da conta de destino.
            val tokens = runCatching { SolanaRpcClient.tokenAccountsWithFallback(network, expectedAddress) }.getOrNull()
            val held = tokens?.filter { it.mint == inputMint }?.sumOf { it.amountRaw } ?: -1L
            if (held == 0L) {
                return@withContext SwapResult.Rejected("Sua carteira não possui esse token. Verifique seus ativos antes de trocar.")
            }
            if (held in 1 until amountRaw) {
                return@withContext SwapResult.Rejected("Saldo do token insuficiente para a troca solicitada. Reduza o valor da troca.")
            }
            val solBalance = runCatching { SolanaRpcClient.balanceWithFallback(network, expectedAddress) }.getOrNull()
            if (solBalance != null && solBalance < FEE_AND_RENT_BUFFER_LAMPORTS) {
                return@withContext SwapResult.Rejected("SOL insuficiente para pagar a taxa de rede/rent da troca (~0,003 SOL). Deposite um pouco de SOL e tente de novo.")
            }
        }

        val quote = try {
            swapClient.quote(inputMint, outputMint, amountRaw, slippageBps)
        } catch (e: Exception) {
            return@withContext SwapResult.Rejected(e.message ?: "Não foi possível obter a cotação.")
        }
        val base64Tx = try {
            swapClient.buildSwapTransaction(quote.rawQuoteResponse, expectedAddress)
        } catch (e: Exception) {
            return@withContext SwapResult.Rejected(e.message ?: "Não foi possível construir a transação de swap.")
        }

        var seed: ByteArray? = null
        var keypair: Keypair? = null
        var signedBytes: ByteArray? = null
        try {
            val secret = vault.read()
            seed = secret
            if (secret.size != 32) return@withContext SwapResult.Rejected("Semente da carteira com tamanho inesperado.")
            keypair = Keypair.fromSecretKey(secret)
            if (keypair.publicKey.toBase58() != expectedAddress) {
                return@withContext SwapResult.Rejected("A chave armazenada não corresponde aos metadados da carteira.")
            }
            val tx = VersionedTransaction.from(base64Tx)
            // Fails closed if the stored wallet is not a required signer of the Jupiter transaction.
            tx.sign(keypair)
            val bytes = try {
                tx.serialize()
            } catch (e: Exception) {
                return@withContext SwapResult.Rejected("A transação do Jupiter exige um assinante diferente da carteira local; troca bloqueada.")
            }
            signedBytes = bytes
            val simulation = rpc.simulateSerializedTransaction(bytes)
            if (!simulation.success) {
                val raw = simulation.error ?: "erro desconhecido"
                val friendly = if (raw.contains("AccountNotFound", ignoreCase = true) || raw.contains("InsufficientFunds", ignoreCase = true))
                    "A carteira não existe on-chain ou não tem SOL suficiente para a troca e a taxa. Deposite SOL e tente de novo. (preflight: $raw)"
                else "A verificação on-chain da troca falhou ($raw). Nada foi enviado."
                return@withContext SwapResult.Rejected(friendly)
            }
            val signature = rpc.sendSignedTransaction(bytes)
            SwapResult.Submitted(signature, quote.outAmountRaw, quote.priceImpactPct, quote.routeLabels)
        } catch (e: Exception) {
            SwapResult.Rejected(e.message ?: "Falha ao assinar ou enviar a troca.")
        } finally {
            seed?.fill(0)
            keypair?.secret?.fill(0)
            signedBytes?.fill(0)
        }
    }
}

sealed interface SwapResult {
    data class Submitted(
        val signature: String,
        val outAmountRaw: Long,
        val priceImpactPct: String,
        val routeLabels: List<String>
    ) : SwapResult
    data class Rejected(val reason: String) : SwapResult
}
