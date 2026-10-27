package dev.cueopen.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import dev.cueopen.app.ui.theme.Cue

@Composable
fun WalletSwapScreen(nav: NavController) {
    val c = Cue.c
    Scaffold(containerColor = c.bg, topBar = { CueTopBar("Trocar", { nav.popBackStack() }) }) { pad ->
        Column(
            Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            EmptyState(
                Icons.Rounded.SwapHoriz,
                "Troca via Jupiter",
                "O motor de troca (swap) real via Jupiter está em integração e chega na próxima versão. Nenhuma cotação ou troca simulada será apresentada como real."
            )
            Text(
                "A troca usará a cotação e a rota oficiais do Jupiter, com assinatura local da transação, limite de slippage e revisão antes de enviar.",
                color = c.ink2, style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
