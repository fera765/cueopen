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
import androidx.navigation.NavController
import dev.cueopen.app.ui.theme.Cue
import dev.cueopen.app.wallet.JupiterQuote
import dev.cueopen.app.wallet.SolanaNetwork
import dev.cueopen.app.wallet.SolanaRpcClient
import dev.cueopen.app.wallet.SolanaSwapClient
import dev.cueopen.app.wallet.SolanaSwapExecutor
import dev.cueopen.app.wallet.SolanaWalletRepository
import dev.cueopen.app.wallet.SwapResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

@Composable
fun WalletSwapScreen(nav: NavController) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val c = Cue.c
    val scope = rememberCoroutineScope()
    val repository = remember(context) { SolanaWalletRepository(context.applicationContext) }
    val network = remember { readSelectedNetwork(context) }
    val address = remember { runCatching { repository.publicAddress() }.getOrNull() }

    var amountSol by remember { mutableStateOf("") }
    var outputMint by remember { mutableStateOf(SolanaSwapClient.USDC_MINT) }
    var outputLabel by remember { mutableStateOf("USDC") }
    var slippageBps by remember { mutableStateOf(50) }

    var quote by remember { mutableStateOf<JupiterQuote?>(null) }
    var outDecimals by remember { mutableStateOf(6) }
    var quoting by remember { mutableStateOf(false) }
    var swapping by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusOk by remember { mutableStateOf(false) }
    var lastSignature by remember { mutableStateOf<String?>(null) }
    var confirmOpen by remember { mutableStateOf(false) }

    fun lamportsOf(sol: String): Long =
        BigDecimal(sol.trim()).setScale(9, RoundingMode.DOWN).movePointRight(9).longValueExact()

    fun outUi(raw: Long): String =
        BigDecimal(raw).movePointLeft(outDecimals).setScale(minOf(outDecimals, 6), RoundingMode.DOWN).toPlainString()

    Scaffold(containerColor = c.bg, topBar = { CueTopBar("Trocar", { nav.popBackStack() }) }) { pad ->
        Column(
            Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (address == null) {
                EmptyState(Icons.Rounded.SwapHoriz, "Carteira não configurada", "Crie ou restaure a carteira antes de trocar.")
                return@Column
            }
            if (network != SolanaNetwork.MAINNET) {
                CueCard {
                    Text("Troca disponível na Mainnet", fontWeight = FontWeight.SemiBold)
                    Text("O Jupiter só tem liquidez na Mainnet. Troque a rede para Mainnet em Carteira → Mais para usar o swap.", color = c.ink2, fontSize = 13.sp)
                }
                return@Column
            }

            Text("Trocar SOL por token", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Cotação e rota oficiais do Jupiter. A transação é assinada localmente no aparelho; nada é enviado sem você confirmar.", color = c.ink2, fontSize = 13.sp)

            CueField(amountSol, { amountSol = it; quote = null }, "Você paga (SOL)")

            Text("Receber", fontSize = 13.sp, color = c.ink2)
            val isPreset = outputMint == SolanaSwapClient.USDC_MINT || outputMint == SolanaSwapClient.USDT_MINT
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("USDC", outputMint == SolanaSwapClient.USDC_MINT) {
                    outputMint = SolanaSwapClient.USDC_MINT; outputLabel = "USDC"; outDecimals = 6; quote = null
                }
                Chip("USDT", outputMint == SolanaSwapClient.USDT_MINT) {
                    outputMint = SolanaSwapClient.USDT_MINT; outputLabel = "USDT"; outDecimals = 6; quote = null
                }
                Chip("Outro mint", !isPreset) {
                    outputMint = ""; outputLabel = "Token"; quote = null
                }
            }
            if (!isPreset) {
                CueField(outputMint, { outputMint = it; quote = null }, "Endereço do mint de saída (ex.: outra cripto SPL)")
            }

            Text("Slippage", fontSize = 13.sp, color = c.ink2)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(50 to "0,5%", 100 to "1%", 300 to "3%").forEach { (bps, lbl) ->
                    Chip(lbl, slippageBps == bps) { slippageBps = bps; quote = null }
                }
            }

            PillButton(if (quoting) "Cotando…" else "Cotar", Icons.Rounded.Refresh, primary = false, modifier = Modifier.fillMaxWidth(), enabled = !quoting && amountSol.isNotBlank() && outputMint.isNotBlank()) {
                status = null
                scope.launch {
                    quoting = true
                    try {
                        val lamports = withContext(Dispatchers.Default) { lamportsOf(amountSol) }
                        require(lamports > 0L) { "Valor inválido" }
                        val client = SolanaSwapClient()
                        val q = client.quote(SolanaSwapClient.SOL_MINT, outputMint.trim(), lamports, slippageBps)
                        val dec = if (outputMint == SolanaSwapClient.USDC_MINT || outputMint == SolanaSwapClient.USDT_MINT) 6
                            else runCatching { SolanaRpcClient(endpoint = network.rpcEndpoint, network = network).getTokenDecimals(outputMint.trim()) }.getOrDefault(6)
                        outDecimals = dec
                        quote = q
                    } catch (e: Exception) {
                        quote = null; statusOk = false; status = e.message ?: "Não foi possível cotar."
                    } finally { quoting = false }
                }
            }

            quote?.let { q ->
                CueCard {
                    Text("Cotação", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    QuoteRow("Você paga", String.format(Locale.US, "%.9f", q.inAmountRaw / 1_000_000_000.0) + " SOL", c)
                    QuoteRow("Você recebe (estim.)", "≈ " + outUi(q.outAmountRaw) + " " + outputLabel, c)
                    QuoteRow("Impacto de preço", formatPriceImpact(q.priceImpactPct), c)
                    if (q.routeLabels.isNotEmpty()) QuoteRow("Rota", q.routeLabels.joinToString(" → "), c)
                    Spacer(Modifier.height(10.dp))
                    PillButton("Revisar e trocar", Icons.Rounded.SwapHoriz, modifier = Modifier.fillMaxWidth(), enabled = !swapping) { confirmOpen = true }
                }
            }

            status?.let {
                CueCard {
                    Text(if (statusOk) "Troca enviada" else "Não concluída", fontWeight = FontWeight.SemiBold, color = if (statusOk) c.ok else c.err)
                    Text(it, color = c.ink2, fontSize = 13.sp)
                    lastSignature?.let { sig ->
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { runCatching { uriHandler.openUri(network.transactionExplorerUrl(sig)) } }) {
                            Icon(Icons.Rounded.OpenInNew, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Ver no Explorer")
                        }
                    }
                }
            }

            Text("Experimental: a troca real depende da liquidez e das condições de rede no momento. Confira os valores antes de confirmar; transações na Mainnet não podem ser desfeitas.", color = c.ink3, fontSize = 12.sp)
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmOpen) {
        val q = quote
        AlertDialog(
            onDismissRequest = { confirmOpen = false },
            title = { Text("Confirmar troca") },
            text = {
                Text(
                    "Trocar " + amountSol + " SOL por ≈ " + (q?.let { outUi(it.outAmountRaw) } ?: "—") + " " + outputLabel +
                        " na Mainnet, slippage " + (slippageBps / 100.0) + "%. A transação será assinada no aparelho e transmitida à rede. Esta ação não pode ser desfeita."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmOpen = false
                    val current = q ?: return@TextButton
                    scope.launch {
                        swapping = true; status = null; lastSignature = null
                        try {
                            val lamports = withContext(Dispatchers.Default) { lamportsOf(amountSol) }
                            val executor = SolanaSwapExecutor(
                                context.applicationContext, repository,
                                SolanaRpcClient(endpoint = network.rpcEndpoint, network = network)
                            )
                            when (val r = executor.executeSwap(SolanaSwapClient.SOL_MINT, outputMint.trim(), lamports, slippageBps, network)) {
                                is SwapResult.Submitted -> {
                                    statusOk = true; lastSignature = r.signature
                                    status = "Troca transmitida à Mainnet. Assinatura: " + r.signature + ". Acompanhe a confirmação no Explorer."
                                    quote = null
                                }
                                is SwapResult.Rejected -> { statusOk = false; status = r.reason }
                            }
                        } catch (e: Exception) {
                            statusOk = false; status = e.message ?: "Falha ao executar a troca."
                        } finally { swapping = false }
                    }
                }) { Text("Trocar agora") }
            },
            dismissButton = { TextButton(onClick = { confirmOpen = false }) { Text("Cancelar") } }
        )
    }
}

private fun formatPriceImpact(raw: String): String {
    val d = raw.toDoubleOrNull() ?: return raw + "%"
    val pct = d * 100.0 // Jupiter returns a fraction (e.g. 0.001 = 0.1%)
    return when {
        pct <= 0.0 -> "0%"
        pct < 0.01 -> "< 0,01%"
        else -> String.format(Locale.US, "%.3f%%", pct)
    }
}

@Composable
private fun QuoteRow(label: String, value: String, c: dev.cueopen.app.ui.theme.CueColors) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = c.ink2, fontSize = 13.sp)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}
