package dev.cueopen.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import dev.cueopen.app.ui.theme.Cue
import dev.cueopen.app.wallet.PolymarketInsightsClient
import dev.cueopen.app.wallet.PolymarketInsightsReport
import dev.cueopen.app.wallet.PolymarketWalletInsight
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun WalletPolymarketInsightsScreen(nav: NavController) {
    val c = Cue.c
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember { PolymarketInsightsClient() }
    var report by remember { mutableStateOf<PolymarketInsightsReport?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun load() {
        if (loading) return
        loading = true
        error = null
        scope.launch {
            runCatching { client.analyzeLast90Days() }
                .onSuccess { report = it }
                .onFailure { error = it.message ?: "Não foi possível consultar a Data API pública da Polymarket." }
            loading = false
        }
    }

    LaunchedEffect(Unit) { load() }

    Scaffold(containerColor = c.bg, topBar = { CueTopBar("Polymarket · análise 90d", { nav.popBackStack() }) }) { pad ->
        Column(
            Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = c.surface)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        Icon(Icons.Rounded.TrendingUp, null, Modifier.size(22.dp), tint = c.accent)
                        Text("Carteiras lucrativas · janela móvel", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        "Dados públicos da Polymarket, sem API key. A análise é apenas informativa: não acessa sua carteira Solana, não negocia e não copia ordens.",
                        color = c.ink2, fontSize = 13.sp
                    )
                    Text(
                        "Candidatas: até 10 do leaderboard mensal + até 10 do acumulado. O score de 90 dias soma PnL realizado de posições fechadas cujo primeiro registro e último evento caem dentro da janela.",
                        color = c.ink2, fontSize = 13.sp
                    )
                    Text(
                        "Limites: amostra parcial, sem posições abertas nem posições iniciadas antes da janela. O leaderboard mensal não equivale a 90 dias; não é o PnL total da conta.",
                        color = c.warn, fontSize = 12.sp
                    )
                    OutlinedButton(onClick = { load() }, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
                        if (loading) CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Rounded.Refresh, null, Modifier.size(17.dp))
                        Spacer(Modifier.size(7.dp))
                        Text(if (loading) "Analisando dados públicos…" else "Atualizar análise")
                    }
                }
            }

            error?.let { message ->
                Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = c.surface)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Consulta não concluída", color = c.err, fontWeight = FontWeight.SemiBold)
                        Text(message, color = c.ink2, fontSize = 13.sp)
                        if (message.contains("429") || message.contains("503")) {
                            Text("A API pode aplicar limites por IP. Aguarde o período indicado por Retry-After e tente novamente.", color = c.warn, fontSize = 12.sp)
                        }
                    }
                }
            }

            report?.let { data ->
                Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = c.surface)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Janela: ${formatEpoch(data.windowStartEpochSeconds)} a ${formatEpoch(data.windowEndEpochSeconds)}", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text("${data.candidateCount} carteiras únicas consultadas · ${data.failedWalletCount} falhas", color = c.ink2, fontSize = 12.sp)
                        Text("Ordenadas pelo PnL realizado da coorte fechada, em USDC/USD.", color = c.ink2, fontSize = 12.sp)
                    }
                }
                if (data.rankedCandidates.isEmpty()) {
                    Text("A API não retornou carteiras candidatas nesta consulta.", color = c.ink2, fontSize = 13.sp)
                }
                data.rankedCandidates.forEachIndexed { index, insight ->
                    PolymarketWalletCard(index + 1, insight)
                }
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable
private fun PolymarketWalletCard(rank: Int, insight: PolymarketWalletInsight) {
    val c = Cue.c
    val context = LocalContext.current
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = c.surface)) {
        Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("#$rank · ${insight.displayName.ifBlank { "Carteira Polymarket" }}", fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(insight.wallet, color = c.ink3, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = {
                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Carteira pública Polymarket", insight.wallet))
                }) { Icon(Icons.Rounded.ContentCopy, contentDescription = "Copiar endereço público", tint = c.ink2) }
            }
            Text(
                if (insight.error == null) "PnL realizado das posições qualificadas: ${formatUsdc(insight.realizedClosedPnlUsdc)}"
                else "PnL da janela indisponível para esta carteira",
                color = if (insight.error == null && insight.realizedClosedPnlUsdc >= 0.0) c.ok else c.err,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp
            )
            Text(
                "${insight.closedPositionCount} posições fechadas · ${insight.positivePositionCount} positivas · ${insight.negativePositionCount} negativas · ${insight.zeroPositionCount} zeradas",
                color = c.ink2, fontSize = 12.sp
            )
            Text(
                "Taxa de posições positivas: ${insight.positiveRatePercent?.let { String.format(Locale.US, "%.1f%%", it) } ?: "não calculável"} · média: ${insight.averageClosedPnlUsdc?.let(::formatUsdc) ?: "—"}",
                color = c.ink2, fontSize = 12.sp
            )
            Text("Candidata via ${insight.sourceLabel}.", color = c.ink3, fontSize = 11.sp)
            insight.monthlyBoardPnlUsdc?.let {
                Text("PnL do leaderboard mensal (janela de mês, equity marcada): ${formatUsdc(it)}", color = c.ink3, fontSize = 11.sp)
            }
            insight.allTimeBoardPnlUsdc?.let {
                Text("PnL do leaderboard acumulado (vitalício): ${formatUsdc(it)}", color = c.ink3, fontSize = 11.sp)
            }
            if (insight.missingPnlCount > 0) {
                Text("${insight.missingPnlCount} posições sem PnL utilizável; taxa e média ficam ocultas.", color = c.warn, fontSize = 11.sp)
            }
            if (insight.positionsPageLimitReached) {
                Text("Leitura parcial: a API informou mais páginas que o limite de leitura desta análise.", color = c.warn, fontSize = 11.sp)
            }
            insight.error?.let { Text("Erro: $it", color = c.err, fontSize = 11.sp) }
        }
    }
}

private fun formatUsdc(value: Double): String = "US$ " + String.format(Locale.US, "%,.2f", value)

private fun formatEpoch(seconds: Long): String = runCatching {
    DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm 'UTC'")
        .withZone(ZoneId.of("UTC"))
        .format(Instant.ofEpochSecond(seconds))
}.getOrDefault(seconds.toString())
