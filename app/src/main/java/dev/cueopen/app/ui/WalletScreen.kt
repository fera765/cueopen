package dev.cueopen.app.ui

import android.app.Activity
import android.content.Context
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import dev.cueopen.app.ui.theme.Cue
import dev.cueopen.app.wallet.SolanaNetwork
import dev.cueopen.app.wallet.SolanaRecentSignature
import dev.cueopen.app.wallet.SolanaRpcClient
import dev.cueopen.app.wallet.SolanaWalletRepository
import dev.cueopen.app.wallet.SplTokenBalance
import dev.cueopen.app.wallet.SolanaMarketDataClient
import dev.cueopen.app.wallet.SolanaSwapClient
import dev.cueopen.app.wallet.TokenDisplay
import coil.compose.AsyncImage
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.clip
import dev.cueopen.app.wallet.WalletSetupSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** Shared palette for the wallet surfaces (dark, MetaMask/Trust-inspired, coral accents). */
internal object WalletPalette {
    val coral = Color(0xFFFF895B)
    val coralSoft = Color(0xFFFFA27E)
    val heroBg = Color(0xFF17110F)
    val chipBg = Color(0xFF2A1B16)
}

internal fun readSelectedNetwork(context: Context): SolanaNetwork {
    val prefs = context.applicationContext.getSharedPreferences("solana_wallet_settings", Context.MODE_PRIVATE)
    return runCatching { SolanaNetwork.valueOf(prefs.getString("network", SolanaNetwork.MAINNET.name)!!) }
        .getOrDefault(SolanaNetwork.MAINNET)
}

internal fun shortAddress(address: String): String =
    if (address.length <= 14) address else address.take(6) + "…" + address.takeLast(6)

/** Wallet home / dashboard. Four actions navigate to dedicated pages. */
@Composable
fun WalletScreen(nav: NavController) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val repository = remember(context) { SolanaWalletRepository(context.applicationContext) }
    val c = Cue.c

    val networkPrefs = remember(context) {
        context.applicationContext.getSharedPreferences("solana_wallet_settings", Context.MODE_PRIVATE)
    }
    val selectedNetwork = remember { mutableStateOf(readSelectedNetwork(context)) }

    var address by remember { mutableStateOf<String?>(null) }
    var walletExists by remember { mutableStateOf(false) }
    var balance by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var hideBalance by remember { mutableStateOf(false) }
    var refreshCounter by remember { mutableStateOf(0) }

    var recent by remember { mutableStateOf<List<SolanaRecentSignature>>(emptyList()) }
    var historyLoading by remember { mutableStateOf(false) }
    var tokens by remember { mutableStateOf<List<SplTokenBalance>>(emptyList()) }
    var tokenMeta by remember { mutableStateOf<Map<String, TokenDisplay>>(emptyMap()) }

    // Onboarding / backup / repair state
    var setup by remember { mutableStateOf<WalletSetupSession?>(null) }
    var recoveryPhrase by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var restoreMode by remember { mutableStateOf(false) }
    var revealPhrase by remember { mutableStateOf(false) }
    var showRecoveryWords by remember { mutableStateOf(false) }

    fun refreshAddress() {
        address = runCatching { repository.publicAddress() }.getOrElse {
            error = it.message ?: "Não foi possível ler os dados da carteira"; null
        }
    }

    DisposableEffect(Unit) {
        onDispose { setup?.close(); recoveryPhrase = ""; confirmation = "" }
    }
    val protectSensitive = revealPhrase || restoreMode || (walletExists && address == null)
    DisposableEffect(protectSensitive) {
        val activity = context as? Activity
        if (protectSensitive) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    LaunchedEffect(Unit) {
        if (!networkPrefs.contains("network")) {
            networkPrefs.edit().putString("network", SolanaNetwork.MAINNET.name).commit()
        }
        selectedNetwork.value = readSelectedNetwork(context)
        walletExists = repository.hasWallet()
        refreshAddress()
    }
    LaunchedEffect(address, refreshCounter) {
        val current = address ?: return@LaunchedEffect
        loading = true; error = null
        try {
            val lamports = withContext(Dispatchers.IO) {
                SolanaRpcClient.balanceWithFallback(selectedNetwork.value, current)
            }
            balance = String.format(Locale.US, "%.9f", lamports / 1_000_000_000.0)
            error = null
        } catch (e: Exception) {
            // Keep the last known balance instead of blanking the hero; show a soft hint only.
            if (balance == null) error = "Saldo indisponível no momento. Tentaremos de novo ao atualizar."
        } finally { loading = false }
    }
    LaunchedEffect(address, refreshCounter) {
        val current = address ?: return@LaunchedEffect
        historyLoading = true
        try {
            recent = withContext(Dispatchers.IO) {
                SolanaRpcClient.recentSignaturesWithFallback(selectedNetwork.value, current, limit = 6)
            }
        } catch (e: Exception) { /* keep previous history */ } finally { historyLoading = false }
    }
    LaunchedEffect(address, refreshCounter) {
        val current = address ?: return@LaunchedEffect
        try {
            tokens = withContext(Dispatchers.IO) {
                SolanaRpcClient.tokenAccountsWithFallback(selectedNetwork.value, current)
            }
        } catch (e: Exception) { /* keep previous token list */ }
    }
    // Enriquece os ativos com nome, símbolo, ícone e preço USD reais (DexScreener, keyless, 1 chamada).
    LaunchedEffect(tokens, address) {
        val mints = tokens.map { it.mint } + SolanaSwapClient.SOL_MINT
        tokenMeta = runCatching { SolanaMarketDataClient().tokenDisplays(mints) }.getOrDefault(emptyMap())
    }

    Scaffold(containerColor = c.bg, topBar = { CueTopBar("Carteira", { nav.popBackStack() }) }) { pad ->
        Column(
            Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (address != null) {
                // ---- Hero balance card ----
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(26.dp),
                    colors = CardDefaults.cardColors(containerColor = WalletPalette.heroBg)
                ) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Surface(shape = RoundedCornerShape(50), color = WalletPalette.chipBg) {
                                Text(selectedNetwork.value.displayName, Modifier.padding(horizontal = 12.dp, vertical = 6.dp), color = WalletPalette.coralSoft, style = MaterialTheme.typography.labelMedium)
                            }
                            IconButton(onClick = { hideBalance = !hideBalance }) {
                                Icon(if (hideBalance) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, contentDescription = "Ocultar saldo", tint = Color(0xFFEDE7E5))
                            }
                        }
                        Text("Saldo total", color = Color(0xFFBDB4B0), style = MaterialTheme.typography.bodyMedium)
                        Row(verticalAlignment = Alignment.Bottom) {
                            if (loading) {
                                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp, color = WalletPalette.coral)
                            } else {
                                Text(if (hideBalance) "••••••" else (balance ?: "—"), color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.width(8.dp))
                                Text("SOL", color = Color(0xFFBDB4B0), fontSize = 18.sp, modifier = Modifier.padding(bottom = 6.dp))
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(shortAddress(address!!), color = Color(0xFFD6D0CE), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Surface(
                                onClick = {
                                    val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    clip.setPrimaryClip(android.content.ClipData.newPlainText("Endereço Solana", address))
                                },
                                shape = CircleShape, color = WalletPalette.chipBg
                            ) {
                                Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Rounded.ContentCopy, null, Modifier.size(15.dp), tint = WalletPalette.coralSoft)
                                    Spacer(Modifier.width(5.dp)); Text("Copiar", color = WalletPalette.coralSoft, style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                        if (error != null) Text(error!!, color = Color(0xFFE7A080), style = MaterialTheme.typography.bodySmall)
                    }
                }

                // ---- Four actions (navigate) ----
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    WalletActionButton("Enviar", Icons.Rounded.ArrowUpward, true, Modifier.weight(1f)) { nav.navigate("wallet/send") }
                    WalletActionButton("Receber", Icons.Rounded.ArrowDownward, false, Modifier.weight(1f)) { nav.navigate("wallet/receive") }
                    WalletActionButton("Trocar", Icons.Rounded.SwapHoriz, false, Modifier.weight(1f)) { nav.navigate("wallet/swap") }
                    WalletActionButton("Mais", Icons.Rounded.GridView, false, Modifier.weight(1f)) { nav.navigate("wallet/more") }
                }

                // ---- Market discovery ----
                OutlinedButton(onClick = { nav.navigate("wallet/market") }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.TrendingUp, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    Text("Mercado — descobrir criptos (novas → antigas)")
                }
                OutlinedButton(onClick = { nav.navigate("wallet/polymarket") }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.TrendingUp, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    Text("Polymarket — analisar carteiras (90 dias)")
                }

                // ---- Assets ----
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = c.surface)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Meus ativos", style = MaterialTheme.typography.titleMedium)
                            OutlinedButton(onClick = { balance = null; error = null; refreshAddress(); refreshCounter++ }) {
                                Icon(Icons.Rounded.Refresh, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Atualizar")
                            }
                        }
                        run {
                            val solMeta = tokenMeta[SolanaSwapClient.SOL_MINT]
                            val solAmt = balance?.toDoubleOrNull()
                            val solUsd = if (solAmt != null && solMeta?.priceUsd != null) solAmt * solMeta.priceUsd else null
                            AssetRow("Solana", "SOL",
                                if (hideBalance) "••••" else fmtAmount(balance),
                                if (hideBalance) null else fmtUsd(solUsd), solMeta?.imageUrl, c)
                        }
                        tokens.forEach { t ->
                            val meta = tokenMeta[t.mint]
                            val symbol = meta?.symbol?.ifBlank { null } ?: SolanaRpcClient.knownSymbol(t.mint) ?: (t.mint.take(4) + "…" + t.mint.takeLast(4))
                            val name = meta?.name?.ifBlank { null } ?: meta?.symbol?.ifBlank { null } ?: SolanaRpcClient.knownSymbol(t.mint) ?: "Token SPL"
                            val amt = t.uiAmount.toDoubleOrNull()
                            val usd = if (amt != null && meta?.priceUsd != null) amt * meta.priceUsd else null
                            AssetRow(name, symbol,
                                if (hideBalance) "••••" else fmtAmount(t.uiAmount),
                                if (hideBalance) null else fmtUsd(usd), meta?.imageUrl, c)
                        }
                        if (tokens.isEmpty()) {
                            Text("Nenhum token SPL nesta carteira ainda. Ao receber ou trocar por um token, ele aparece aqui.", color = c.ink3, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                // ---- Recent activity ----
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = c.surface)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Atividade recente", style = MaterialTheme.typography.titleMedium)
                        when {
                            historyLoading -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = c.accent)
                            recent.isEmpty() -> Text("Nenhuma movimentação on-chain nesta rede.", color = c.ink2, style = MaterialTheme.typography.bodySmall)
                            else -> recent.forEach { tx ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Surface(shape = CircleShape, color = if (tx.error == null) Color(0xFF14321F) else Color(0xFF3A1A1A)) {
                                        Icon(if (tx.error == null) Icons.Rounded.ArrowDownward else Icons.Rounded.SwapHoriz, null, Modifier.padding(9.dp).size(16.dp), tint = if (tx.error == null) c.ok else c.err)
                                    }
                                    Column(Modifier.weight(1f)) {
                                        Text(if (tx.error == null) "Transação registrada" else "Falha on-chain", style = MaterialTheme.typography.bodyMedium, color = if (tx.error == null) c.ink else c.err)
                                        Text(shortAddress(tx.signature), color = c.ink3, style = MaterialTheme.typography.bodySmall)
                                    }
                                    TextButton(onClick = {
                                        runCatching { uriHandler.openUri(selectedNetwork.value.transactionExplorerUrl(tx.signature)) }
                                    }) { Text("Explorer") }
                                }
                            }
                        }
                    }
                }
            }

            // ---- Repair: ciphertext exists but metadata missing ----
            if (walletExists && address == null && setup == null) {
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = c.surface)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Reparar carteira", style = MaterialTheme.typography.titleMedium)
                        Text("A carteira local existe, mas os metadados públicos não estão disponíveis. Confirme a frase de recuperação para reparar sem substituir a chave armazenada.", color = c.ink2, style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(recoveryPhrase, { recoveryPhrase = it }, label = { Text("Frase de recuperação") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                        Button(onClick = {
                            val input = recoveryPhrase.toCharArray(); recoveryPhrase = ""
                            try { address = repository.repairMetadataFromRecoveryPhrase(input); walletExists = true; error = null }
                            catch (e: Exception) { error = e.message ?: "Não foi possível reparar os metadados" }
                        }, modifier = Modifier.fillMaxWidth()) { Text("Verificar frase e reparar") }
                    }
                }
            }

            // ---- Onboarding: create / restore ----
            if (address == null && setup == null && !walletExists) {
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = c.surface)) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Surface(shape = CircleShape, color = WalletPalette.heroBg) {
                                Icon(Icons.Rounded.AccountBalanceWallet, null, Modifier.padding(18.dp).size(34.dp), tint = WalletPalette.coral)
                            }
                        }
                        Text("Sua carteira Solana", style = MaterialTheme.typography.titleLarge, modifier = Modifier.fillMaxWidth(), fontWeight = FontWeight.Bold)
                        Text("Autocustódia: as chaves ficam só neste aparelho. Crie uma nova carteira ou restaure com sua frase de 24 palavras.", color = c.ink2, style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = {
                            error = null
                            try {
                                setup = if (restoreMode) {
                                    val input = recoveryPhrase.toCharArray(); recoveryPhrase = ""; repository.beginRestore(input)
                                } else repository.beginCreate()
                                val words = setup!!.phraseForBackup(); recoveryPhrase = words.concatToString(); words.fill('\u0000')
                                revealPhrase = true; showRecoveryWords = false
                            } catch (e: Exception) { error = e.message ?: "Não foi possível iniciar a carteira" }
                        }, modifier = Modifier.fillMaxWidth()) { Text(if (restoreMode) "Restaurar carteira" else "Criar nova carteira") }
                        OutlinedButton(onClick = { restoreMode = !restoreMode; recoveryPhrase = ""; error = null }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (restoreMode) "Voltar para criar carteira" else "Já tenho uma frase de recuperação")
                        }
                        if (restoreMode) {
                            OutlinedTextField(recoveryPhrase, { recoveryPhrase = it }, label = { Text("Frase de recuperação (24 palavras)") },
                                supportingText = { Text("Processada localmente. Nunca envie no chat.") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                        }
                    }
                }
            }

            // ---- Backup confirmation ----
            if (setup != null && revealPhrase) {
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = c.surface)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Frase de recuperação", style = MaterialTheme.typography.titleMedium)
                        Text("Anote estas 24 palavras em local privado. Quem tiver a frase controla a carteira. Nunca envie ao agente ou ao chat.", color = c.ink2, style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { showRecoveryWords = !showRecoveryWords }) { Text(if (showRecoveryWords) "Ocultar" else "Mostrar palavras") }
                            OutlinedButton(onClick = {
                                val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                clip.setPrimaryClip(android.content.ClipData.newPlainText("Frase de recuperação Solana", recoveryPhrase))
                            }) { Icon(Icons.Rounded.ContentCopy, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Copiar") }
                            OutlinedButton(onClick = {
                                runCatching {
                                    val dir = java.io.File(context.cacheDir, "wallet").apply { mkdirs() }
                                    val f = java.io.File(dir, "cueopen-frase-recuperacao.txt")
                                    f.writeText("Fluwy Bot — Frase de recuperação (24 palavras)\nMantenha em segredo e offline. Quem tiver estas palavras controla a carteira.\nUse em qualquer wallet Solana compatível para acessar em outro dispositivo.\n\n$recoveryPhrase\n")
                                    val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", f)
                                    val send = android.content.Intent(android.content.Intent.ACTION_SEND)
                                        .setType("text/plain").putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                        .putExtra(android.content.Intent.EXTRA_SUBJECT, "Fluwy Bot — frase de recuperação")
                                        .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    context.startActivity(android.content.Intent.createChooser(send, "Baixar/salvar frase de recuperação").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                                }.onFailure { error = "Não foi possível exportar a frase: ${it.message}" }
                            }) { Icon(Icons.Rounded.Download, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Baixar") }
                        }
                        if (showRecoveryWords) Text(recoveryPhrase, color = c.ink) else Text("Palavras ocultas por segurança", color = c.ink3)
                        OutlinedTextField(confirmation, { confirmation = it }, label = { Text("Digite a frase novamente para confirmar") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                        Button(onClick = {
                            val chars = confirmation.toCharArray(); confirmation = ""
                            try {
                                setup!!.confirmBackupAndPersist(chars); setup!!.close(); setup = null
                                revealPhrase = false; recoveryPhrase = ""; restoreMode = false
                                walletExists = true; refreshAddress()
                            } catch (e: Exception) { error = e.message ?: "A confirmação não corresponde" }
                        }, modifier = Modifier.fillMaxWidth()) { Text("Confirmar backup e salvar") }
                        TextButton(onClick = {
                            setup?.close(); setup = null; recoveryPhrase = ""; confirmation = ""; revealPhrase = false
                            error = "Configuração cancelada; nenhum segredo foi salvo."
                        }) { Text("Cancelar e apagar sessão temporária") }
                    }
                }
            }

            error?.let { if (address == null) Text(it, color = c.ink2, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun AssetRow(name: String, symbol: String, amount: String, usd: String?, imageUrl: String?, c: dev.cueopen.app.ui.theme.CueColors) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!imageUrl.isNullOrBlank()) {
            AsyncImage(model = imageUrl, contentDescription = null, modifier = Modifier.size(44.dp).clip(CircleShape))
        } else {
            Surface(shape = CircleShape, color = WalletPalette.heroBg) {
                Icon(Icons.Rounded.AccountBalanceWallet, null, Modifier.padding(12.dp), tint = WalletPalette.coral)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(symbol, color = c.ink2, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(amount, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (!usd.isNullOrBlank()) Text(usd, color = c.ink2, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Formata o valor do ativo: até 6 casas significativas, sem zeros à direita desnecessários. */
private fun fmtAmount(raw: String?): String {
    val v = raw?.toDoubleOrNull() ?: return raw ?: "—"
    val s = when {
        v == 0.0 -> "0"
        v >= 1000 -> String.format(java.util.Locale.US, "%,.2f", v)
        v >= 1 -> String.format(java.util.Locale.US, "%.4f", v)
        else -> String.format(java.util.Locale.US, "%.6f", v)
    }
    return if (s.contains('.')) s.trimEnd('0').trimEnd('.') else s
}

private fun fmtUsd(v: Double?): String? {
    if (v == null || v <= 0.0) return null
    return "≈ US$ " + String.format(java.util.Locale.US, if (v >= 1.0) "%,.2f" else "%.4f", v)
}

@Composable
internal fun WalletActionButton(label: String, icon: ImageVector, primary: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Surface(
            onClick = onClick, modifier = Modifier.fillMaxWidth().height(58.dp), shape = RoundedCornerShape(20.dp),
            color = if (primary) WalletPalette.coral else Color(0xFF1C1A19),
            contentColor = if (primary) WalletPalette.heroBg else Color(0xFFF3EFED),
            border = BorderStroke(1.dp, if (primary) WalletPalette.coralSoft else Color(0xFF38302D))
        ) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = label, modifier = Modifier.size(24.dp)) }
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = Cue.c.ink)
    }
}
