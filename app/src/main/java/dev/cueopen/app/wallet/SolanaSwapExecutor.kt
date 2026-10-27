package dev.cueopen.app.wallet

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
                return@withContext SwapResult.Rejected("Simulação da troca falhou: " + (simulation.error ?: "erro desconhecido") + ". Nada foi enviado.")
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
