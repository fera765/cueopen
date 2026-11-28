package dev.fluwy.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import dev.fluwy.app.ui.theme.Fluwy
import dev.fluwy.app.wallet.SolanaWalletRepository

@Composable
fun WalletReceiveScreen(nav: NavController) {
    val context = LocalContext.current
    val repository = remember(context) { SolanaWalletRepository(context.applicationContext) }
    val network = remember { readSelectedNetwork(context) }
    val address = remember { runCatching { repository.publicAddress() }.getOrNull() }
    val c = Fluwy.c

    Scaffold(containerColor = c.bg, topBar = { FluwyTopBar("Receber SOL", { nav.popBackStack() }) }) { pad ->
        Column(
            Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (address == null) {
                EmptyState(Icons.Rounded.AccountBalanceWallet, "Carteira não configurada", "Crie ou restaure sua carteira na tela Carteira antes de receber SOL.")
            } else {
                Surface(shape = RoundedCornerShape(50), color = WalletPalette.chipBg) {
                    Text(network.displayName, Modifier.padding(horizontal = 14.dp, vertical = 7.dp), color = WalletPalette.coralSoft, style = MaterialTheme.typography.labelMedium)
                }
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = c.surface)) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            WalletReceiveQr(address, network)
                        }
                        Text("Seu endereço público", color = c.ink2, style = MaterialTheme.typography.bodyMedium)
                        Surface(shape = RoundedCornerShape(14.dp), color = c.surface2) {
                            Text(address, Modifier.padding(14.dp), color = c.ink, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                        }
                        Surface(
                            onClick = {
                                val clip = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                clip.setPrimaryClip(android.content.ClipData.newPlainText("Endereço público Solana", address))
                            },
                            shape = RoundedCornerShape(50), color = WalletPalette.coral, contentColor = WalletPalette.heroBg
                        ) {
                            Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.ContentCopy, null); Spacer(Modifier.width(8.dp)); Text("Copiar endereço")
                            }
                        }
                    }
                }
                Text(
                    "O QR contém apenas o endereço público — nunca a frase de recuperação. Confirme com o remetente que ele usa a rede " + network.displayName + ".",
                    color = c.ink3, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center
                )
            }
        }
    }
}
