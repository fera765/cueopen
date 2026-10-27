package dev.cueopen.app.ui

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.Card
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import dev.cueopen.app.ui.theme.Cue
import dev.cueopen.app.wallet.SolanaRpcClient
import dev.cueopen.app.wallet.SolanaRecentSignature
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
    val uriHandler = LocalUriHandler.current
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
    var recentOnChain by remember { mutableStateOf<List<SolanaRecentSignature>>(emptyList()) }
    var historyLoading by remember { mutableStateOf(false) }
    var historyError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var setup by remember { mutableStateOf<WalletSetupSession?>(null) }
    var recoveryPhrase by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var restoreMode by remember { mutableStateOf(false) }
    var revealPhrase by remember { mutableStateOf(false) }
    var showRecoveryWords by remember { mutableStateOf(false) }
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
        // Persist the same default network used by the UI and agent tools on first launch.
        if (!networkPrefs.contains("network")) {
            networkPrefs.edit().putString("network", SolanaNetwork.MAINNET.name).commit()
        }
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

    LaunchedEffect(address, refreshCounter, selectedNetwork) {
        val current = address ?: return@LaunchedEffect
        historyLoading = true
        historyError = null
        try {
            recentOnChain = withContext(Dispatchers.IO) {
                SolanaRpcClient(endpoint = selectedNetwork.rpcEndpoint, network = selectedNetwork)
                    .getRecentSignatures(current, limit = 10)
            }
        } catch (e: Exception) {
            recentOnChain = emptyList()
            historyError = "Histórico on-chain indisponível no momento."
        } finally {
            historyLoading = false
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
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = c.surface)) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    androidx.compose.material3.Surface(shape = RoundedCornerShape(16.dp), color = c.surface2) {
                        androidx.compose.material3.Icon(Icons.Rounded.AccountBalanceWallet, contentDescription = null, modifier = Modifier.padding(13.dp), tint = c.ink)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Sua carteira", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
                        Text("Solana · autocustódia", color = c.ink2, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Text("Suas chaves permanecem no dispositivo. Transferências exigem aprovação humana e assinatura local.", color = c.ink2, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
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
                            showRecoveryWords = false
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
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = c.surface2)
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Saldo disponível", style = androidx.compose.material3.MaterialTheme.typography.labelLarge, color = c.ink2)
                            androidx.compose.material3.Surface(shape = RoundedCornerShape(50), color = c.surface) {
                                Text(selectedNetwork.displayName, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp), style = androidx.compose.material3.MaterialTheme.typography.labelSmall, color = c.ink2)
                            }
                        }
                        if (loading) {
                            CircularProgressIndicator()
                        } else {
                            Text(
                                balance ?: "— SOL",
                                style = androidx.compose.material3.MaterialTheme.typography.headlineMedium,
                                color = c.ink,
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                            )
                        }
                        Text("SOL · Saldo na rede selecionada", color = c.ink2, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                        if (error != null) Text(error!!, color = c.err, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                    }
                }
                Text("Receber", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
                Text("Seu endereço público", color = c.ink2, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = c.surface)) {
                    Text(address!!, modifier = Modifier.fillMaxWidth().padding(14.dp), color = c.ink, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                }
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
                Text("Os saldos e taxas podem mudar rapidamente. Transações transmitidas à " + selectedNetwork.displayName + " não podem ser desfeitas.", color = c.ink2)

                Text("Atividade", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
                Text("Movimentações registradas diretamente na rede. Detalhes de tokens e valores por transação podem não estar disponíveis.", color = c.ink2, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                if (historyLoading) {
                    CircularProgressIndicator()
                } else if (historyError != null) {
                    Text(historyError!!, color = c.err)
                    OutlinedButton(onClick = { refreshCounter++ }) { Text("Tentar novamente") }
                } else if (recentOnChain.isEmpty()) {
                    Text("Nenhuma assinatura recente encontrada nesta rede.", color = c.ink2)
                } else {
                    recentOnChain.forEach { tx ->
                        androidx.compose.material3.Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = c.surface)
                        ) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    if (tx.error == null) "Transação registrada" else "Transação com falha on-chain",
                                    color = if (tx.error == null) c.ok else c.err,
                                    style = androidx.compose.material3.MaterialTheme.typography.labelLarge
                                )
                                Text("Slot " + tx.slot, color = c.ink2)
                                tx.blockTimeEpochSeconds?.let { timestamp ->
                                    Text(
                                        java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR"))
                                            .format(java.util.Date(timestamp * 1000L)),
                                        color = c.ink2
                                    )
                                }
                                Text(tx.signature.take(18) + "…" + tx.signature.takeLast(10), color = c.ink)
                                TextButton(onClick = {
                                    runCatching { uriHandler.openUri(selectedNetwork.transactionExplorerUrl(tx.signature)) }
                                        .onFailure { error = "Não foi possível abrir o explorador de transações." }
                                }) { Text("Ver no Explorer") }
                            }
                        }
                    }
                }

                Text("Acesso dos agentes", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
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
                                            "Tetos de segurança: 0,05 SOL por pedido e 0,10 SOL por dia. Você pode reduzir esses limites; o agente nunca assina nem envia sozinho.",
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
                                            val recipients = recipientAllowlist
                                                .split(Regex("""[,\s]+"""))
                                                .map { it.trim() }
                                                .filter { it.isNotEmpty() }
                                                .toSet()
                                            walletPolicyStore.setAgentTransferSettings(
                                                agent.id, maxLamports, dailyLamports, recipients
                                            )
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

            Text("Rede Solana", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    modifier = Modifier.weight(1f),
                    enabled = selectedNetwork != SolanaNetwork.MAINNET,
                    onClick = {
                        selectedNetwork = SolanaNetwork.MAINNET
                        networkPrefs.edit().putString("network", selectedNetwork.name).apply()
                        balance = null
                        refreshCounter++
                    }
                ) { Text("Mainnet") }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
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
                Text("Anote estas 24 palavras em local privado. Qualquer pessoa com a frase pode controlar a carteira. Nunca envie a frase ao agente ou ao chat.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    OutlinedButton(onClick = { showRecoveryWords = !showRecoveryWords }) {
                        Text(if (showRecoveryWords) "Ocultar palavras" else "Mostrar palavras")
                    }
                    OutlinedButton(onClick = {
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Frase de recuperação Solana", recoveryPhrase))
                    }) {
                        androidx.compose.material3.Icon(Icons.Rounded.ContentCopy, contentDescription = null)
                        Text("Copiar palavras")
                    }
                }
                if (showRecoveryWords) Text(recoveryPhrase)
                else Text("Palavras ocultas por segurança")
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
