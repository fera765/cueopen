package dev.cueopen.app.ui

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import dev.cueopen.app.ui.theme.Cue
import dev.cueopen.app.wallet.SolanaRpcClient
import dev.cueopen.app.wallet.SolanaNetwork
import dev.cueopen.app.wallet.SolanaWalletRepository
import dev.cueopen.app.wallet.WalletSetupSession
import dev.cueopen.app.wallet.WalletAgentPolicyStore
import dev.cueopen.app.CueApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun WalletScreen(nav: NavController) {
    val context = LocalContext.current
    val repository = remember(context) { SolanaWalletRepository(context.applicationContext) }
    val walletPolicyStore = remember(context) { WalletAgentPolicyStore(context.applicationContext) }
    val appState by CueApp.instance.store.state.collectAsState()
    val walletAgents = appState.agents
    var permissionRefresh by remember { mutableStateOf(0) }
    val networkPrefs = remember(context) {
        context.applicationContext.getSharedPreferences("solana_wallet_settings", android.content.Context.MODE_PRIVATE)
    }
    var selectedNetwork by remember(context) {
        mutableStateOf(
            runCatching {
                SolanaNetwork.valueOf(networkPrefs.getString("network", SolanaNetwork.MAINNET.name)!!)
            }.getOrDefault(SolanaNetwork.MAINNET)
        )
    }
    val c = Cue.c
    var address by remember { mutableStateOf<String?>(null) }
    var walletExists by remember { mutableStateOf(false) }
    var balance by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var setup by remember { mutableStateOf<WalletSetupSession?>(null) }
    var recoveryPhrase by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var restoreMode by remember { mutableStateOf(false) }
    var revealPhrase by remember { mutableStateOf(false) }
    var refreshCounter by remember { mutableStateOf(0) }
    val activeSetup by rememberUpdatedState(setup)

    DisposableEffect(Unit) {
        onDispose {
            activeSetup?.close()
            recoveryPhrase = ""
            confirmation = ""
        }
    }

    val protectSensitiveInput = revealPhrase || restoreMode || (walletExists && address == null)
    DisposableEffect(protectSensitiveInput) {
        val activity = context as? Activity
        if (protectSensitiveInput) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    fun refreshAddress() {
        address = runCatching { repository.publicAddress() }.getOrElse {
            error = it.message ?: "Não foi possível ler os dados da carteira"
            null
        }
    }

    LaunchedEffect(Unit) {
        walletExists = repository.hasWallet()
        refreshAddress()
    }
    LaunchedEffect(address, refreshCounter, selectedNetwork) {
        val current = address ?: return@LaunchedEffect
        loading = true
        error = null
        try {
            val lamports = withContext(Dispatchers.IO) { SolanaRpcClient(endpoint = selectedNetwork.rpcEndpoint, network = selectedNetwork).getBalanceLamports(current) }
            balance = String.format(Locale.US, "%.9f SOL", lamports / 1_000_000_000.0)
        } catch (e: Exception) {
            balance = null
            error = "Saldo indisponível. Verifique a conexão e tente novamente."
        } finally {
            loading = false
        }
    }

    Scaffold(
        containerColor = c.bg,
        topBar = { CueTopBar("Carteira Solana", { nav.popBackStack() }) }
    ) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                androidx.compose.material3.Icon(Icons.Rounded.AccountBalanceWallet, contentDescription = null)
                Text("Carteira local", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
            }
            Text(
                "A chave privada fica no dispositivo. Transferências exigem aprovação humana, assinatura local e confirmação separada antes da transmissão à rede selecionada.",
                color = c.ink2
            )
            if (walletExists && address == null && setup == null) {
                Text("A carteira local existe, mas os metadados públicos não estão disponíveis. Confirme a frase de recuperação para reparar os metadados sem substituir a chave armazenada.")
                OutlinedTextField(
                    value = recoveryPhrase,
                    onValueChange = { recoveryPhrase = it },
                    label = { Text("Frase de recuperação") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
                Button(onClick = {
                    val input = recoveryPhrase.toCharArray()
                    recoveryPhrase = ""
                    try {
                        address = repository.repairMetadataFromRecoveryPhrase(input)
                        walletExists = true
                        revealPhrase = false
                        error = null
                    } catch (e: Exception) {
                        error = e.message ?: "Não foi possível reparar os metadados"
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("Verificar frase e reparar carteira") }
            }

            if (address == null && setup == null && !walletExists) {
                Button(
                    onClick = {
                        error = null
                        try {
                            setup = if (restoreMode) {
                                val input = recoveryPhrase.toCharArray()
                                recoveryPhrase = ""
                                repository.beginRestore(input)
                            } else repository.beginCreate()
                            val words = setup!!.phraseForBackup()
                            recoveryPhrase = words.concatToString()
                            words.fill('\u0000')
                            revealPhrase = true
                        } catch (e: Exception) {
                            error = e.message ?: "Não foi possível iniciar a carteira"
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (restoreMode) "Restaurar carteira" else "Criar carteira") }

                OutlinedButton(
                    onClick = { restoreMode = !restoreMode; recoveryPhrase = ""; error = null },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (restoreMode) "Voltar para criar carteira" else "Já tenho uma frase de recuperação") }

                if (restoreMode) {
                    OutlinedTextField(
                        value = recoveryPhrase,
                        onValueChange = { recoveryPhrase = it },
                        label = { Text("Frase de recuperação (24 palavras)") },
                        supportingText = { Text("A frase é processada localmente. Não a envie no chat nem a compartilhe.") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3
                    )
                }
            }

            if (address != null) {
                Text("Endereço público", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                Text(address!!, color = c.ink2)
                WalletReceiveQr(address!!, selectedNetwork)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Endereço público Solana", address))
                    }) {
                        androidx.compose.material3.Icon(Icons.Rounded.ContentCopy, contentDescription = null)
                        Text("Copiar endereço")
                    }
                    OutlinedButton(onClick = { balance = null; error = null; refreshAddress(); refreshCounter++ }) {
                        androidx.compose.material3.Icon(Icons.Rounded.Refresh, contentDescription = null)
                        Text("Atualizar")
                    }
                }
                Text("Saldo (" + selectedNetwork.displayName + ")", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                if (loading) CircularProgressIndicator()
                else Text(balance ?: "Saldo ainda não disponível")
                Text("Saldo e taxas podem mudar rapidamente. Transações transmitidas à " + selectedNetwork.displayName + " não podem ser desfeitas.", color = c.ink2)

                Text("Permissões de carteira por agente", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                Text("Cada permissão é salva neste dispositivo e começa desativada. Leitura permite consultar endereço/saldo; a permissão de transferência só permite criar pedidos tipados, sempre sujeitos à revisão, aprovação humana, assinatura local e confirmação separada antes do envio. Agentes nunca recebem chaves privadas.", color = c.ink2)
                if (walletAgents.isEmpty()) {
                    Text("Nenhum agente foi configurado.")
                } else {
                    walletAgents.forEach { agent ->
                        key(agent.id, permissionRefresh) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(agent.name, style = androidx.compose.material3.MaterialTheme.typography.titleSmall)
                                var addressPermission by remember(agent.id, permissionRefresh) {
                                    mutableStateOf(walletPolicyStore.canAgentReadAddress(agent.id))
                                }
                                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Consultar endereço público")
                                    }
                                    androidx.compose.material3.Switch(
                                        checked = addressPermission,
                                        onCheckedChange = { enabled ->
                                            try {
                                                walletPolicyStore.setAgentReadAddress(agent.id, enabled)
                                                addressPermission = enabled
                                                permissionRefresh++
                                            } catch (e: Exception) {
                                                error = e.message ?: "Não foi possível salvar a permissão."
                                            }
                                        }
                                    )
                                }
                                var balancePermission by remember(agent.id, permissionRefresh) {
                                    mutableStateOf(walletPolicyStore.canAgentReadBalance(agent.id))
                                }
                                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Consultar saldo da rede selecionada")
                                    }
                                    androidx.compose.material3.Switch(
                                        checked = balancePermission,
                                        onCheckedChange = { enabled ->
                                            try {
                                                walletPolicyStore.setAgentReadBalance(agent.id, enabled)
                                                balancePermission = enabled
                                                permissionRefresh++
                                            } catch (e: Exception) {
                                                error = e.message ?: "Não foi possível salvar a permissão."
                                            }
                                        }
                                    )
                                }
                                var transferRequestPermission by remember(agent.id, permissionRefresh) {
                                    mutableStateOf(walletPolicyStore.canAgentRequestTransfer(agent.id))
                                }
                                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text("Solicitar transferência SOL (sempre exige aprovação humana)")
                                        Text(
                                            "Limite do agente: 0,05 SOL por pedido e 0,10 SOL por dia; sem assinatura ou envio autônomo.",
                                            color = c.ink2
                                        )
                                    }
                                    androidx.compose.material3.Switch(
                                        checked = transferRequestPermission,
                                        onCheckedChange = { enabled ->
                                            try {
                                                walletPolicyStore.setAgentRequestTransfer(agent.id, enabled)
                                                transferRequestPermission = enabled
                                                permissionRefresh++
                                            } catch (e: Exception) {
                                                error = e.message ?: "Não foi possível salvar a permissão."
                                            }
                                        }
                                    )
                                }
                                var maxAgentSol by remember(agent.id, permissionRefresh) {
                                    mutableStateOf(String.format(Locale.US, "%.9f", walletPolicyStore.agentMaxTransferLamports(agent.id) / 1_000_000_000.0))
                                }
                                var dailyAgentSol by remember(agent.id, permissionRefresh) {
                                    mutableStateOf(String.format(Locale.US, "%.9f", walletPolicyStore.agentDailyLimitLamports(agent.id) / 1_000_000_000.0))
                                }
                                var recipientAllowlist by remember(agent.id, permissionRefresh) {
                                    mutableStateOf(walletPolicyStore.agentAllowedRecipients(agent.id).joinToString("\n"))
                                }
                                OutlinedTextField(
                                    value = maxAgentSol,
                                    onValueChange = { maxAgentSol = it },
                                    label = { Text("Limite por pedido do agente (SOL, máx. 0,05)") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = dailyAgentSol,
                                    onValueChange = { dailyAgentSol = it },
                                    label = { Text("Limite diário do agente (SOL, máx. 0,10)") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = recipientAllowlist,
                                    onValueChange = { recipientAllowlist = it },
                                    label = { Text("Destinatários autorizados (um por linha; opcional)") },
                                    supportingText = { Text("Se preenchida, apenas estes endereços poderão ser solicitados. A aprovação humana continua obrigatória.") },
                                    modifier = Modifier.fillMaxWidth(),
                                    minLines = 2
                                )
                                OutlinedButton(
                                    onClick = {
                                        try {
                                            fun toLamports(value: String): Long =
                                                java.math.BigDecimal(value.trim())
                                                    .setScale(9, java.math.RoundingMode.UNNECESSARY)
                                                    .multiply(java.math.BigDecimal("1000000000"))
                                                    .longValueExact()
                                            val maxLamports = toLamports(maxAgentSol)
                                            val dailyLamports = toLamports(dailyAgentSol)
                                            walletPolicyStore.setAgentTransferLimits(agent.id, maxLamports, dailyLamports)
                                            val recipients = recipientAllowlist
                                                .split(Regex("""[,\s]+"""))
                                                .map { it.trim() }
                                                .filter { it.isNotEmpty() }
                                                .toSet()
                                            walletPolicyStore.setAgentAllowedRecipients(agent.id, recipients)
                                            error = null
                                            permissionRefresh++
                                        } catch (e: Exception) {
                                            error = e.message ?: "Limites ou destinatários inválidos."
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) { Text("Salvar limites e destinatários") }
                            }
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = selectedNetwork != SolanaNetwork.MAINNET,
                    onClick = {
                        selectedNetwork = SolanaNetwork.MAINNET
                        networkPrefs.edit().putString("network", selectedNetwork.name).apply()
                        balance = null
                        refreshCounter++
                    }
                ) { Text("Mainnet") }
                OutlinedButton(
                    enabled = selectedNetwork != SolanaNetwork.DEVNET,
                    onClick = {
                        selectedNetwork = SolanaNetwork.DEVNET
                        networkPrefs.edit().putString("network", selectedNetwork.name).apply()
                        balance = null
                        refreshCounter++
                    }
                ) { Text("Devnet (testes)") }
            }
            Text(
                if (selectedNetwork == SolanaNetwork.DEVNET)
                    "Devnet usa tokens de teste sem valor real."
                else
                    "Mainnet usa SOL real. Verifique a rede antes de enviar.",
                color = c.ink2
            )
            if (address != null) {
                key(selectedNetwork) {
                    WalletTransferPanel(context.applicationContext, repository, selectedNetwork)
                }
            }

            if (setup != null && revealPhrase) {
                Text("Frase de recuperação", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                Text("Anote estas 24 palavras em local privado. Qualquer pessoa com a frase pode controlar a carteira. Não tire capturas de tela.")
                Text(recoveryPhrase)
                OutlinedTextField(
                    value = confirmation,
                    onValueChange = { confirmation = it },
                    label = { Text("Digite novamente a frase para confirmar o backup") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
                Button(
                    onClick = {
                        val chars = confirmation.toCharArray()
                        confirmation = ""
                        try {
                            setup!!.confirmBackupAndPersist(chars)
                            setup!!.close()
                            setup = null
                            revealPhrase = false
                            recoveryPhrase = ""
                            restoreMode = false
                            refreshAddress()
                        } catch (e: Exception) {
                            error = e.message ?: "A confirmação não corresponde"
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Confirmar backup e salvar carteira") }
                TextButton(onClick = {
                    setup?.close()
                    setup = null
                    recoveryPhrase = ""
                    confirmation = ""
                    revealPhrase = false
                    error = "Configuração cancelada; nenhum segredo foi salvo."
                }) { Text("Cancelar e apagar a sessão temporária") }
            }

            error?.let { Text(it, color = c.ink2) }
        }
    }
}
