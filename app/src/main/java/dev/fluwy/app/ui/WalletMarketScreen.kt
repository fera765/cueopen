package dev.fluwy.app.ui

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import dev.fluwy.app.ui.theme.Fluwy
import dev.fluwy.app.wallet.SolanaMarketDataClient
import dev.fluwy.app.wallet.TokenMarket
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Mercado: descobre tokens de todo o ecossistema Solana com dados REAIS (GeckoTerminal, sem API key).
 * "Novos" lista do mais recente ao mais antigo; "Em alta" os com maior movimento agora.
 */
@Composable
fun WalletMarketScreen(nav: NavController) {
    val c = Fluwy.c
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val market = remember { SolanaMarketDataClient() }
    var sort by remember { mutableStateOf("new") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var tokens by remember { mutableStateOf<List<TokenMarket>>(emptyList()) }

    fun load() {
        loading = true; error = null
        scope.launch {
            runCatching { market.discoverTokens(sort, 40) }
                .onSuccess { tokens = it; loading = false; if (it.isEmpty()) error = "Nenhum token retornado agora." }
                .onFailure {
                    val msg = it.message ?: "Falha ao carregar o mercado."
                    error = if (msg.contains("429")) "$msg Configure opcionalmente uma chave Demo da CoinGecko em Configurações para usar uma quota maior." else msg
                    loading = false
                }
        }
    }
    LaunchedEffect(sort) { load() }

    Scaffold(containerColor = c.bg, topBar = { FluwyTopBar("Mercado Solana", { nav.popBackStack() }) }) { pad ->
        Column(
            Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Tokens de todo o ecossistema Solana, em tempo real e sem API key. Liquidez e volume reais ajudam a avaliar se dá para comprar e vender.",
                color = c.ink2, fontSize = 13.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Novos → antigos", sort == "new") { if (sort != "new") sort = "new" }
                Chip("Em alta", sort == "trending") { if (sort != "trending") sort = "trending" }
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = { load() }, enabled = !loading) {
                    Icon(Icons.Rounded.Refresh, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text("Atualizar")
                }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.accent)
            error?.let { Text(it, color = c.err, fontSize = 13.sp) }
            tokens.forEach { t -> TokenRow(t) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun TokenRow(t: TokenMarket) {
    val c = Fluwy.c
    val context = LocalContext.current
    FluwyCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t.symbol, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text(t.name.ifBlank { t.mint.take(12) + "…" }, color = c.ink2, fontSize = 12.sp, maxLines = 1)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    t.priceUsd?.let { "US$ " + String.format(Locale.US, if (it < 1.0) "%.8f" else "%.4f", it) } ?: "—",
                    fontWeight = FontWeight.Medium, fontSize = 14.sp
                )
                t.priceChange24hPct?.let {
                    Text(String.format(Locale.US, "%+.1f%% 24h", it), color = if (it >= 0) c.ok else c.err, fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            t.liquidityUsd?.let { Text("Liq US$ " + String.format(Locale.US, "%,.0f", it), color = c.ink2, fontSize = 11.sp) }
            t.volume24hUsd?.let { Text("Vol24h US$ " + String.format(Locale.US, "%,.0f", it), color = c.ink2, fontSize = 11.sp) }
        }
        t.poolCreatedAt?.let { Text("Criado: $it", color = c.ink3, fontSize = 10.sp) }
        Spacer(Modifier.height(6.dp))
        OutlinedButton(onClick = {
            val clip = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clip.setPrimaryClip(android.content.ClipData.newPlainText("mint", t.mint))
        }) { Icon(Icons.Rounded.ContentCopy, null, Modifier.size(14.dp)); Spacer(Modifier.width(6.dp)); Text("Copiar mint", fontSize = 12.sp) }
    }
}
