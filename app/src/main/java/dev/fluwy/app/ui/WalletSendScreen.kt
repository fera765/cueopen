package dev.fluwy.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import dev.fluwy.app.ui.theme.Fluwy
import dev.fluwy.app.wallet.SolanaNetwork
import dev.fluwy.app.wallet.SolanaWalletRepository

@Composable
fun WalletSendScreen(nav: NavController) {
    val context = LocalContext.current
    val repository = remember(context) { SolanaWalletRepository(context.applicationContext) }
    val network = remember { readSelectedNetwork(context) }
    val address = remember { runCatching { repository.publicAddress() }.getOrNull() }
    val c = Fluwy.c

    Scaffold(containerColor = c.bg, topBar = { FluwyTopBar("Enviar SOL", { nav.popBackStack() }) }) { pad ->
        Column(
            Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (address == null) {
                EmptyState(Icons.Rounded.AccountBalanceWallet, "Carteira não configurada", "Crie ou restaure sua carteira na tela Carteira antes de enviar SOL.")
            } else {
                Surface(shape = RoundedCornerShape(50), color = WalletPalette.chipBg, modifier = Modifier) {
                    Text(
                        if (network == SolanaNetwork.MAINNET) "Mainnet · SOL real" else "Devnet · tokens de teste",
                        Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                        color = WalletPalette.coralSoft, style = MaterialTheme.typography.labelMedium
                    )
                }
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), colors = CardDefaults.cardColors(containerColor = c.surface)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        WalletTransferPanel(context.applicationContext, repository, network)
                    }
                }
                Text(
                    "Transações transmitidas à " + network.displayName + " não podem ser desfeitas. Confira destinatário, valor, taxa e rede antes de confirmar.",
                    color = c.ink3, style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
