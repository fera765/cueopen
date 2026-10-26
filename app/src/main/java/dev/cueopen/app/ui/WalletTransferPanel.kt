package dev.cueopen.app.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.cueopen.app.wallet.LedgerAuthorizationResult
import dev.cueopen.app.wallet.LedgerState
import dev.cueopen.app.wallet.SolanaTransferExecutor
import dev.cueopen.app.wallet.SolanaWalletRepository
import dev.cueopen.app.wallet.TransferExecutionResult
import dev.cueopen.app.wallet.WalletAgentPolicy
import dev.cueopen.app.wallet.WalletRequestLedger
import dev.cueopen.app.wallet.WalletTransferIntent
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale
import java.util.UUID

private const val MANUAL_AGENT_ID = "manual-wallet-ui"
private const val MAX_TRANSFER_LAMPORTS = 50_000_000L
private const val DAILY_LIMIT_LAMPORTS = 100_000_000L
private const val LAMPORTS_PER_SOL = 1_000_000_000.0

private fun manualTransferPolicy() = WalletAgentPolicy(
    agentId = MANUAL_AGENT_ID,
    enabled = true,
    canTransfer = true,
    autonomousExecution = false,
    maxTransferLamports = MAX_TRANSFER_LAMPORTS,
    dailyLimitLamports = DAILY_LIMIT_LAMPORTS,
    allowedRecipients = emptySet()
)

private fun walletSpendingWindow(): Pair<Long, Long> {
    val start = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    val startMs = start.timeInMillis
    val endMs = (start.clone() as Calendar).apply {
        add(Calendar.DAY_OF_YEAR, 1)
    }.timeInMillis
    return startMs to endMs
}

@Composable
fun WalletTransferPanel(context: Context, repository: SolanaWalletRepository) {
    if (!repository.backupConfirmed()) return

    val ledger = remember(context) { WalletRequestLedger(context.applicationContext) }
    val executor = remember(context, repository, ledger) {
        SolanaTransferExecutor(context.applicationContext, repository, ledger)
    }
    val scope = rememberCoroutineScope()
    var recipient by remember { mutableStateOf("") }
    var amountSol by remember { mutableStateOf("") }
    var requestId by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<TransferExecutionResult?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmBroadcast by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Enviar SOL")
        Text(
            "Mainnet Solana. Limite manual: 0,05 SOL por transferência e 0,10 SOL por dia. " +
                "O envio exige aprovação e uma segunda confirmação explícita."
        )
        OutlinedTextField(
            value = recipient,
            onValueChange = { recipient = it; message = null },
            label = { Text("Endereço Solana do destinatário") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = amountSol,
            onValueChange = { amountSol = it; message = null },
            label = { Text("Valor em SOL (até 9 casas decimais)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Button(
            enabled = !busy && requestId == null,
            onClick = {
                busy = true
                message = null
                scope.launch {
                    try {
                        val policy = manualTransferPolicy()
                        val now = System.currentTimeMillis()
                        val window = walletSpendingWindow()
                        val intent = WalletTransferIntent.fromSol(
                            requestId = UUID.randomUUID().toString().replace("-", ""),
                            agentId = MANUAL_AGENT_ID,
                            recipientAddress = recipient.trim(),
                            amountSol = amountSol.trim(),
                            createdAtEpochMs = now
                        )
                        when (val claimed = ledger.claimAndAuthorize(
                            intent, policy, window.first, window.second, now
                        )) {
                            is LedgerAuthorizationResult.Created -> {
                                requestId = intent.requestId
                                result = null
                                message = "Pedido preparado. Revise o destinatário e o valor."
                            }
                            is LedgerAuthorizationResult.Existing ->
                                message = "Este pedido já existe; nenhum novo envio foi autorizado."
                            is LedgerAuthorizationResult.Approved ->
                                message = "O pedido já foi aprovado."
                            is LedgerAuthorizationResult.Rejected ->
                                message = claimed.decision.reason
                        }
                    } catch (error: Exception) {
                        message = error.message ?: "Não foi possível preparar a transferência."
                    } finally {
                        busy = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (busy) "Preparando..." else "Preparar transferência") }

        requestId?.let { id ->
            val record = ledger.find(id)
            Text("Pedido: " + id)
            Text("Destinatário: " + (record?.recipientAddress ?: recipient))
            Text(
                "Valor: " + (record?.amountLamports?.let {
                    String.format(Locale.US, "%.9f SOL", it / LAMPORTS_PER_SOL)
                } ?: amountSol)
            )
            if (record?.state == LedgerState.AWAITING_APPROVAL) {
                Button(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        message = null
                        scope.launch {
                            try {
                                val policy = manualTransferPolicy()
                                val window = walletSpendingWindow()
                                when (val approval = ledger.approveForSigning(
                                    id, policy, window.first, window.second, System.currentTimeMillis()
                                )) {
                                    is LedgerAuthorizationResult.Approved -> {
                                        result = executor.signAuthorizedTransfer(
                                            id, policy, window.first, window.second, humanApproval = true
                                        )
                                        message = when (val signed = result) {
                                            is TransferExecutionResult.Signed ->
                                                "Assinatura salva localmente; a transação ainda não foi transmitida."
                                            is TransferExecutionResult.Rejected -> signed.reason
                                            TransferExecutionResult.AwaitingApproval ->
                                                "A aprovação expirou; aprove o pedido novamente."
                                            else -> "Estado: " + signed.toString()
                                        }
                                    }
                                    is LedgerAuthorizationResult.Rejected -> message = approval.decision.reason
                                    else -> message = "Nenhuma nova autorização foi criada."
                                }
                            } catch (error: Exception) {
                                message = error.message ?: "Não foi possível assinar a transferência."
                            } finally {
                                busy = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (busy) "Processando..." else "Aprovar e assinar") }
            }

            when (val current = result) {
                is TransferExecutionResult.Signed -> {
                    Text("Assinatura: " + current.signature)
                    Button(
                        enabled = !busy,
                        onClick = { confirmBroadcast = true },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Transmitir à mainnet…") }
                }
                is TransferExecutionResult.Submitted -> {
                    Text("Enviado. Assinatura: " + current.signature)
                    OutlinedButton(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            scope.launch {
                                try {
                                    result = executor.reconcileSignature(id)
                                    message = result.toString()
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Consultar estado na rede") }
                }
                is TransferExecutionResult.Unknown -> {
                    Text("Estado incerto. Não reenvie manualmente.")
                    Text("Assinatura: " + current.signature)
                    OutlinedButton(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            scope.launch {
                                try {
                                    result = executor.reconcileSignature(id)
                                    message = result.toString()
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Consultar estado na rede") }
                }
                is TransferExecutionResult.Confirmed ->
                    Text("Transação finalizada no slot " + current.slot + ". Assinatura: " + current.signature)
                is TransferExecutionResult.ChainFailed ->
                    Text("A rede informou falha: " + current.reason)
                TransferExecutionResult.AwaitingApproval ->
                    Text("Aguardando aprovação humana.")
                is TransferExecutionResult.Rejected ->
                    Text(current.reason)
                null -> Unit
            }

            TextButton(
                enabled = !busy,
                onClick = {
                    requestId = null
                    result = null
                    message = null
                    recipient = ""
                    amountSol = ""
                }
            ) { Text("Limpar formulário") }
        }
        message?.let { Text(it) }
    }

    if (confirmBroadcast) {
        val current = requestId?.let { ledger.find(it) }
        AlertDialog(
            onDismissRequest = { confirmBroadcast = false },
            title = { Text("Confirmar envio à mainnet") },
            text = {
                Text(
                    "Você está prestes a transmitir uma transação Solana real.\n\n" +
                        "Destinatário: " + (current?.recipientAddress ?: "indisponível") +
                        "\nValor: " + (current?.amountLamports?.let {
                            String.format(Locale.US, "%.9f SOL", it / LAMPORTS_PER_SOL)
                        } ?: "indisponível") +
                        "\n\nTransações confirmadas não podem ser desfeitas."
                )
            },
            confirmButton = {
                Button(
                    enabled = !busy && requestId != null,
                    onClick = {
                        val id = requestId
                        if (id == null) {
                            confirmBroadcast = false
                        } else {
                            confirmBroadcast = false
                            busy = true
                            scope.launch {
                                try {
                                    result = executor.broadcastPersistedTransfer(id)
                                    message = result.toString()
                                } finally {
                                    busy = false
                                }
                            }
                        }
                    }
                ) { Text("Confirmar e transmitir") }
            },
            dismissButton = {
                TextButton(onClick = { confirmBroadcast = false }) { Text("Cancelar") }
            }
        )
    }
}
