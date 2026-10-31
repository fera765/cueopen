package dev.cueopen.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.cueopen.app.CueApp
import dev.cueopen.app.ui.theme.Cue
import dev.cueopen.app.wallet.SolanaNetwork
import dev.cueopen.app.wallet.SolanaWalletRepository
import dev.cueopen.app.wallet.WalletAgentPolicyStore
import java.util.Locale

@Composable
fun WalletMoreScreen(nav: NavController) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val c = Cue.c
    val repository = remember(context) { SolanaWalletRepository(context.applicationContext) }
    val policyStore = remember(context) { WalletAgentPolicyStore(context.applicationContext) }
    val networkPrefs = remember(context) {
        context.applicationContext.getSharedPreferences("solana_wallet_settings", android.content.Context.MODE_PRIVATE)
    }
    val st by CueApp.instance.store.state.collectAsStateWithLifecycle()
    var selectedNetwork by remember { mutableStateOf(readSelectedNetwork(context)) }
    val address = remember { runCatching { repository.publicAddress() }.getOrNull() }
    var permissionRefresh by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmLogout by remember { mutableStateOf(false) }

    Scaffold(containerColor = c.bg, topBar = { CueTopBar("Mais · Configurações", { nav.popBackStack() }) }) { pad ->
        Column(
            Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Network
            SectionLabel("Rede Solana")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    modifier = Modifier.weight(1f),
                    enabled = selectedNetwork != SolanaNetwork.MAINNET,
                    onClick = { selectedNetwork = SolanaNetwork.MAINNET; networkPrefs.edit().putString("network", selectedNetwork.name).commit() }
                ) { Text("Mainnet") }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    enabled = selectedNetwork != SolanaNetwork.DEVNET,
                    onClick = { selectedNetwork = SolanaNetwork.DEVNET; networkPrefs.edit().putString("network", selectedNetwork.name).commit() }
                ) { Text("Devnet (testes)") }
            }
            Text(
                if (selectedNetwork == SolanaNetwork.DEVNET) "Devnet usa tokens de teste sem valor real."
                else "Mainnet usa SOL real. Verifique a rede antes de enviar.",
                color = c.ink2, fontSize = 13.sp
            )

            // Wallet info
            if (address != null) {
                SectionLabel("Informações da carteira")
                CueCard {
                    Text("Endereço público", color = c.ink2, fontSize = 13.sp)
                    Text(address, color = c.ink, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val clip = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            clip.setPrimaryClip(android.content.ClipData.newPlainText("Endereço público Solana", address))
                        }) { Icon(Icons.Rounded.ContentCopy, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Copiar") }
                        OutlinedButton(onClick = {
                            val base = "https://explorer.solana.com/address/" + address
                            val url = if (selectedNetwork == SolanaNetwork.DEVNET) "$base?cluster=devnet" else base
                            runCatching { uriHandler.openUri(url) }
                        }) { Icon(Icons.Rounded.OpenInNew, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Ver no Explorer") }
                    }
                }
            }

            // Agent access
            SectionLabel("Acesso dos agentes")
            Text(
                "Cada permissão é salva neste aparelho e começa desativada. Leitura de carteira consulta endereço/saldo; transferência cria pedidos tipados. A frase de recuperação e a chave privada nunca são expostas ao agente.",
                color = c.ink2, fontSize = 13.sp
            )
            if (st.agents.isEmpty()) {
                CueCard { Text("Nenhum agente configurado ainda.", color = c.ink2) }
            } else {
                st.agents.forEach { agent ->
                    key(agent.id, permissionRefresh) {
                        CueCard {
                            Text(agent.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(6.dp))

                            var readAddr by remember(agent.id, permissionRefresh) { mutableStateOf(policyStore.canAgentReadAddress(agent.id)) }
                            PermissionRow("Consultar endereço público", readAddr) {
                                runCatching { policyStore.setAgentReadAddress(agent.id, it); readAddr = it; permissionRefresh++ }
                                    .onFailure { e -> error = e.message }
                            }
                            var readBal by remember(agent.id, permissionRefresh) { mutableStateOf(policyStore.canAgentReadBalance(agent.id)) }
                            PermissionRow("Consultar saldo da rede", readBal) {
                                runCatching { policyStore.setAgentReadBalance(agent.id, it); readBal = it; permissionRefresh++ }
                                    .onFailure { e -> error = e.message }
                            }
                            var reqTransfer by remember(agent.id, permissionRefresh) { mutableStateOf(policyStore.canAgentRequestTransfer(agent.id)) }
                            PermissionRow("Solicitar transferência SOL", reqTransfer) {
                                runCatching {
                                    policyStore.setAgentRequestTransfer(agent.id, it)
                                    if (!it) policyStore.setAgentAutonomousTransfer(agent.id, false)
                                    reqTransfer = it; permissionRefresh++
                                }.onFailure { e -> error = e.message }
                            }
                            if (reqTransfer) {
                                var auto by remember(agent.id, permissionRefresh) { mutableStateOf(policyStore.canAgentAutonomousTransfer(agent.id)) }
                                PermissionRow("Transferência automática (sem aprovação manual)", auto) {
                                    runCatching { policyStore.setAgentAutonomousTransfer(agent.id, it); auto = it; permissionRefresh++ }
                                        .onFailure { e -> error = e.message }
                                }
                                if (auto) Text(
                                    "⚠ Envio automático ativo: o agente pode enviar para qualquer endereço sem aprovação manual. A allowlist abaixo é opcional — se vazia, qualquer destinatário é permitido. Os limites por transação/dia são sempre aplicados.",
                                    color = c.warn, fontSize = 12.sp
                                )

                                var maxSol by remember(agent.id, permissionRefresh) {
                                    mutableStateOf(String.format(Locale.US, "%.9f", policyStore.agentMaxTransferLamports(agent.id) / 1_000_000_000.0))
                                }
                                var dailySol by remember(agent.id, permissionRefresh) {
                                    mutableStateOf(String.format(Locale.US, "%.9f", policyStore.agentDailyLimitLamports(agent.id) / 1_000_000_000.0))
                                }
                                var allowlist by remember(agent.id, permissionRefresh) {
                                    mutableStateOf(policyStore.agentAllowedRecipients(agent.id).filter { it.isNotBlank() }.joinToString("\n"))
                                }
                                Spacer(Modifier.height(8.dp))
                                CueField(maxSol, { maxSol = it }, "Limite por pedido (SOL, máx. 0,05)")
                                CueField(dailySol, { dailySol = it }, "Limite diário (SOL, máx. 0,10)")
                                CueField(allowlist, { allowlist = it }, "Allowlist (opcional; um por linha; vazio = qualquer endereço)", minLines = 2)
                                Spacer(Modifier.height(8.dp))
                                PillButton("Salvar limites e destinatários", Icons.Rounded.Check, modifier = Modifier.fillMaxWidth()) {
                                    runCatching {
                                        fun toLamports(v: String): Long = java.math.BigDecimal(v.trim())
                                            .setScale(9, java.math.RoundingMode.UNNECESSARY)
                                            .multiply(java.math.BigDecimal("1000000000")).longValueExact()
                                        val recipients = allowlist.split(Regex("""[,\s]+""")).map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                                        policyStore.setAgentTransferSettings(agent.id, toLamports(maxSol), toLamports(dailySol), recipients)
                                        error = null; permissionRefresh++
                                    }.onFailure { e -> error = e.message ?: "Limites ou destinatários inválidos." }
                                }
                            }
                            var dayTrade by remember(agent.id, permissionRefresh) { mutableStateOf(policyStore.canAgentDayTrade(agent.id)) }
                            PermissionRow("Day trade (mercado + swaps + regras)", dayTrade) {
                                runCatching { policyStore.setAgentDayTrade(agent.id, it); dayTrade = it; permissionRefresh++ }
                                    .onFailure { e -> error = e.message }
                            }
                            if (dayTrade) Text(
                                "Dados de mercado reais (preço, velas, liquidez), trocas e regras de stop-loss/take-profit. Entradas em SOL respeitam o limite por operação acima. Não concede envio de SOL a terceiros.",
                                color = c.ink2, fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            // Swap status (stage note)
            SectionLabel("Troca de tokens")
            CueCard {
                Text("A troca (swap) é feita via Jupiter, direto na carteira. Nenhuma cotação ou troca simulada é apresentada como real.", color = c.ink2, fontSize = 13.sp)
            }

            // Logout / sair da carteira
            if (address != null) {
                SectionLabel("Sessão da carteira")
                CueCard {
                    Text("Sair remove a carteira deste aparelho. Ela continua existindo na blockchain — você pode reabri-la a qualquer momento com a frase de recuperação, aqui ou em outra wallet. Sem a frase, o acesso é perdido.", color = c.ink2, fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    PillButton("Sair da carteira", Icons.Rounded.Logout, primary = false, modifier = Modifier.fillMaxWidth()) { confirmLogout = true }
                }
            }

            error?.let { Text(it, color = c.err, fontSize = 13.sp) }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (confirmLogout) AlertDialog(
        onDismissRequest = { confirmLogout = false },
        title = { Text("Sair da carteira?") },
        text = { Text("A carteira será removida deste aparelho. Guarde a frase de recuperação antes de sair — sem ela o acesso é perdido. Você poderá reabrir a carteira depois importando a frase.") },
        confirmButton = {
            TextButton(onClick = {
                runCatching { repository.forgetWallet() }
                    .onSuccess { confirmLogout = false; nav.popBackStack() }
                    .onFailure { e -> error = e.message ?: "Falha ao sair da carteira."; confirmLogout = false }
            }) { Text("Sair", color = c.err) }
        },
        dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Cancelar") } }
    )
}

@Composable
private fun PermissionRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), fontSize = 14.sp)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
