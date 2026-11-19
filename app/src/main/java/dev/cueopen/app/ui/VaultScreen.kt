package dev.cueopen.app.ui

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import dev.cueopen.app.ui.theme.Cue
import dev.cueopen.app.vault.CardVault

/**
 * Cofre: lugar único das informações financeiras do usuário (carteira Solana e cartões). Nada daqui é exposto ao
 * agente: a tela bloqueia captura (FLAG_SECURE) e os cartões só aparecem mascarados.
 */
@Composable
fun VaultScreen(nav: NavController) {
    val c = Cue.c
    val ctx = LocalContext.current
    val vault = remember { CardVault(ctx) }
    var cards by remember { mutableStateOf(vault.list()) }
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<CardVault.Card?>(null) }
    DisposableEffect(Unit) {
        val w = (ctx as? Activity)?.window
        w?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { w?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    Scaffold(containerColor = c.bg, topBar = { CueTopBar("Cofre", { nav.popBackStack() }) }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Lock, null, tint = c.ok, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                    Text("Cifrado no aparelho. Os agentes nunca veem estes dados.", fontSize = 12.sp, color = c.ink2)
                }
            }
            item { SectionLabel("Carteira Solana") }
            item {
                CueCard(onClick = { nav.navigate("wallet") }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x339945FF)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.AccountBalanceWallet, null, tint = Color(0xFF9945FF))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Carteira Solana", fontWeight = FontWeight.SemiBold)
                            Text("Criar ou importar, endereço, saldo, enviar e receber", fontSize = 12.sp, color = c.ink2)
                        }
                        Icon(Icons.Rounded.ChevronRight, null, tint = c.ink3)
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("Cartões de crédito"); Spacer(Modifier.weight(1f))
                    Box(Modifier.clip(CircleShape).background(c.ink).clickable { adding = true }.padding(horizontal = 14.dp, vertical = 7.dp)) {
                        Text("+ Adicionar cartão", color = c.bg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            if (cards.isEmpty()) item { Text("Nenhum cartão no Cofre.", fontSize = 12.sp, color = c.ink3) }
            items(cards, key = { it.id }) { card ->
                CueCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(c.surface2), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.CreditCard, null, tint = c.ink2)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(card.masked, fontWeight = FontWeight.SemiBold)
                            Text("${card.holder} · validade ${card.expiry} · CPF •••.•••.•••-${card.cpf.takeLast(2)}", fontSize = 12.sp, color = c.ink2)
                        }
                        IconButton({ removing = card }) { Icon(Icons.Rounded.DeleteOutline, "Remover", tint = c.err) }
                    }
                }
            }
            item {
                Text("O CVV não é guardado (regra de segurança PCI DSS): ele é pedido só na hora de uma compra que você autorizar. " +
                    "Pagamentos usam um provedor com tokenização; o número completo nunca vai para o chat, para os agentes ou para os registros.",
                    fontSize = 11.sp, color = c.ink3)
            }
        }
    }
    if (adding) AddCardDialog(vault, onDone = { adding = false; cards = vault.list() }, onDismiss = { adding = false })
    removing?.let { card ->
        AlertDialog(onDismissRequest = { removing = null }, title = { Text("Remover ${card.masked}?") }, text = { Text("O cartão sai do Cofre deste aparelho.") },
            confirmButton = { TextButton({ vault.remove(card.id); cards = vault.list(); removing = null }) { Text("Remover", color = c.err) } },
            dismissButton = { TextButton({ removing = null }) { Text("Cancelar") } })
    }
}

@Composable
private fun AddCardDialog(vault: CardVault, onDone: () -> Unit, onDismiss: () -> Unit) {
    val c = Cue.c
    var number by remember { mutableStateOf("") }
    var holder by remember { mutableStateOf("") }
    var expiry by remember { mutableStateOf("") }
    var cpf by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    @Composable fun field(v: String, on: (String) -> Unit, label: String, digits: Boolean, secret: Boolean = false) =
        OutlinedTextField(v, on, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = if (digits) KeyboardType.Number else KeyboardType.Text, autoCorrectEnabled = false),
            visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None)
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Adicionar cartão") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            field(number, { number = it.filter { ch -> ch.isDigit() || ch == ' ' }.take(23) }, "Número do cartão", true, secret = true)
            field(holder, { holder = it.take(40) }, "Nome do titular", false)
            field(expiry, { expiry = it.filter { ch -> ch.isDigit() || ch == '/' }.take(5) }, "Validade (MM/AA)", true)
            field(cpf, { cpf = it.filter { ch -> ch.isDigit() || ch == '.' || ch == '-' }.take(14) }, "CPF do titular", true)
            Text("O CVV não é salvo: será pedido só no momento de uma compra autorizada.", fontSize = 11.sp, color = c.ink3)
            err?.let { Text(it, color = c.err, fontSize = 12.sp) }
        }
    }, confirmButton = {
        TextButton({ err = runCatching { vault.add(number, holder, expiry, cpf) }.getOrElse { it.message ?: "Falha ao salvar." }; if (err == null) onDone() }) { Text("Salvar") }
    }, dismissButton = { TextButton(onDismiss) { Text("Cancelar") } })
}
