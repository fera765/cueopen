package dev.fluwy.app.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavController
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import dev.fluwy.app.ui.theme.Fluwy
import dev.fluwy.app.vault.CardRules
import dev.fluwy.app.vault.CardVault
import dev.fluwy.app.vault.PixRules
import dev.fluwy.app.vault.PixVault

/**
 * Cofre: lugar único das informações financeiras do usuário (carteira Solana, cartões e chaves Pix). Nada daqui é
 * exposto ao agente: a tela bloqueia captura (FLAG_SECURE) e os dados só aparecem mascarados.
 */
@Composable
fun VaultScreen(nav: NavController) {
    val c = Fluwy.c
    val ctx = LocalContext.current
    val vault = remember { CardVault(ctx) }
    val pix = remember { PixVault(ctx) }
    var cards by remember { mutableStateOf(vault.list()) }
    var keys by remember { mutableStateOf(pix.list()) }
    var adding by remember { mutableStateOf(false) }
    var addingPix by remember { mutableStateOf(false) }
    var charge by remember { mutableStateOf<PixVault.PixKey?>(null) }
    var removing by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    DisposableEffect(Unit) {
        val w = (ctx as? Activity)?.window
        w?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { w?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    @Composable fun header(title: String, action: String, onAction: () -> Unit) = Row(verticalAlignment = Alignment.CenterVertically) {
        SectionLabel(title); Spacer(Modifier.weight(1f))
        Box(Modifier.clip(CircleShape).background(c.ink).clickable(onClick = onAction).padding(horizontal = 14.dp, vertical = 7.dp)) {
            Text(action, color = c.bg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
    }
    Scaffold(containerColor = c.bg, topBar = { FluwyTopBar("Cofre", { nav.popBackStack() }) }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Lock, null, tint = c.ok, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                    Text("Cifrado no aparelho. Os agentes nunca veem estes dados.", fontSize = 12.sp, color = c.ink2)
                }
            }
            item { SectionLabel("Carteira Solana") }
            item {
                FluwyCard(onClick = { nav.navigate("wallet") }) {
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
            item { header("Cartões de crédito", "+ Adicionar cartão") { adding = true } }
            if (cards.isEmpty()) item { Text("Nenhum cartão no Cofre.", fontSize = 12.sp, color = c.ink3) }
            items(cards, key = { it.id }) { card ->
                Box {
                    CardFace(card.brand, "•••• •••• •••• ${card.last4}", card.holder, card.expiry, Modifier.fillMaxWidth())
                    IconButton({ removing = card.masked to { vault.remove(card.id); cards = vault.list() } }, Modifier.align(Alignment.TopEnd)) {
                        Icon(Icons.Rounded.DeleteOutline, "Remover", tint = Color.White.copy(alpha = 0.85f))
                    }
                }
            }
            item { header("Pix", "+ Adicionar chave") { addingPix = true } }
            if (keys.isEmpty()) item { Text("Nenhuma chave Pix. Adicione a sua para gerar QR Codes de cobrança com valor definido.", fontSize = 12.sp, color = c.ink3) }
            items(keys, key = { it.id }) { k ->
                FluwyCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x2232BCAD)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.QrCode2, null, tint = Color(0xFF32BCAD))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(k.label.ifBlank { "Pix · ${k.type.lowercase().replaceFirstChar { it.uppercase() }}" }, fontWeight = FontWeight.SemiBold)
                            Text("${k.masked} · ${k.name}", fontSize = 12.sp, color = c.ink2)
                        }
                        TextButton({ charge = k }) { Text("Gerar QR", color = c.accent) }
                        IconButton({ removing = "a chave ${k.masked}" to { pix.remove(k.id); keys = pix.list() } }) { Icon(Icons.Rounded.DeleteOutline, "Remover", tint = c.err) }
                    }
                }
            }
            item {
                Text("Cartões: o CVV não é guardado (regra PCI DSS) e o número completo nunca vai para o chat, os agentes ou os registros. " +
                    "Pix: o QR gerado leva o valor fixo e um código de integridade (CRC) — qualquer alteração no código invalida o pagamento.",
                    fontSize = 11.sp, color = c.ink3)
            }
        }
    }
    if (adding) AddCardDialog(vault, onDone = { adding = false; cards = vault.list() }, onDismiss = { adding = false })
    if (addingPix) AddPixDialog(pix, onDone = {
        // Chave nova: já abre o QR dela (valor livre), com a opção de definir um valor fixo.
        val before = keys.map { it.id }.toSet()
        addingPix = false; keys = pix.list(); charge = keys.firstOrNull { it.id !in before }
    }, onDismiss = { addingPix = false })
    charge?.let { PixChargeDialog(it) { charge = null } }
    removing?.let { (what, action) ->
        AlertDialog(onDismissRequest = { removing = null }, title = { Text("Remover $what?") }, text = { Text("Sai do Cofre deste aparelho.") },
            confirmButton = { TextButton({ action(); removing = null }) { Text("Remover", color = c.err) } },
            dismissButton = { TextButton({ removing = null }) { Text("Cancelar") } })
    }
}

private fun brandBrush(brand: String) = Brush.linearGradient(when (brand) {
    "Visa" -> listOf(Color(0xFF1A1F71), Color(0xFF3B4BC8))
    "Mastercard" -> listOf(Color(0xFF1B1B1B), Color(0xFFEB5E28))
    "Amex" -> listOf(Color(0xFF0B5E8E), Color(0xFF2EA8DF))
    "Elo" -> listOf(Color(0xFF111111), Color(0xFFFFC107))
    "Hipercard" -> listOf(Color(0xFF8B0000), Color(0xFFD32F2F))
    else -> listOf(Color(0xFF2B2D42), Color(0xFF5C6784))
})

/** Visual do cartão (prévia ao vivo no cadastro e item da lista). */
@Composable
private fun CardFace(brand: String, number: String, holder: String, expiry: String, modifier: Modifier = Modifier) {
    Column(modifier.aspectRatio(1.586f).clip(RoundedCornerShape(18.dp)).background(brandBrush(brand)).padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 38.dp, height = 28.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFFE8C66A)))
            Spacer(Modifier.weight(1f))
            Text(brand, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
        Spacer(Modifier.weight(1f))
        Text(number, color = Color.White, fontSize = 19.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
        Spacer(Modifier.height(12.dp))
        Row {
            Column(Modifier.weight(1f)) {
                Text("TITULAR", color = Color.White.copy(alpha = 0.6f), fontSize = 9.sp)
                Text(holder.ifBlank { "NOME DO TITULAR" }.uppercase(), color = Color.White, fontSize = 13.sp, maxLines = 1)
            }
            Column {
                Text("VALIDADE", color = Color.White.copy(alpha = 0.6f), fontSize = 9.sp)
                Text(expiry.ifBlank { "MM/AA" }, color = Color.White, fontSize = 13.sp)
            }
        }
    }
}

private fun groupNumber(d: String) = d.chunked(4).joinToString(" ")

@Composable
private fun VaultField(v: String, on: (String) -> Unit, label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, digits: Boolean, supporting: String? = null, error: Boolean = false) =
    OutlinedTextField(v, on, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
        leadingIcon = { Icon(icon, null) }, isError = error, supportingText = supporting?.let { { Text(it, fontSize = 11.sp) } },
        keyboardOptions = KeyboardOptions(keyboardType = if (digits) KeyboardType.Number else KeyboardType.Text, autoCorrectEnabled = false))

@Composable
private fun AddCardDialog(vault: CardVault, onDone: () -> Unit, onDismiss: () -> Unit) {
    val c = Fluwy.c
    var number by remember { mutableStateOf("") }
    var holder by remember { mutableStateOf("") }
    var expiry by remember { mutableStateOf("") }
    var cpf by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    val digits = number.filter { it.isDigit() }
    val brand = CardRules.brand(digits)
    val numberOk = digits.length < 13 || CardRules.luhn(digits)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.94f), shape = RoundedCornerShape(24.dp), color = c.bg) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Adicionar cartão", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                // Prévia ao vivo: só os 4 últimos dígitos aparecem por inteiro.
                CardFace(brand, groupNumber(digits.dropLast(4).replace(Regex("\\d"), "•") + digits.takeLast(4)).ifBlank { "•••• •••• •••• ••••" },
                    holder, expiry, Modifier.fillMaxWidth())
                VaultField(groupNumber(digits), { number = it.filter { ch -> ch.isDigit() }.take(19) }, "Número do cartão", Icons.Rounded.CreditCard, true,
                    supporting = if (!numberOk) "Número inválido" else null, error = !numberOk)
                VaultField(holder, { holder = it.take(40) }, "Nome do titular (como no cartão)", Icons.Rounded.Person, false)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) {
                        VaultField(expiry, { raw -> val d = raw.filter { it.isDigit() }.take(4); expiry = if (d.length > 2) d.take(2) + "/" + d.drop(2) else d },
                            "Validade", Icons.Rounded.Event, true)
                    }
                    Box(Modifier.weight(1.3f)) {
                        VaultField(cpf, { raw -> val d = raw.filter { it.isDigit() }.take(11)
                            cpf = buildString { d.forEachIndexed { i, ch -> if (i == 3 || i == 6) append('.'); if (i == 9) append('-'); append(ch) } } },
                            "CPF do titular", Icons.Rounded.Badge, true)
                    }
                }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.surface).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Shield, null, tint = c.ok, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    Text("Cifrado no aparelho. O CVV não é salvo: é pedido só numa compra que você autorizar.", fontSize = 11.sp, color = c.ink2)
                }
                err?.let { Text(it, color = c.err, fontSize = 12.sp) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onDismiss) { Text("Cancelar") }
                    Spacer(Modifier.width(6.dp))
                    Button({ err = runCatching { vault.add(digits, holder, expiry, cpf) }.getOrElse { it.message ?: "Falha ao salvar." }; if (err == null) onDone() },
                        shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = c.ink, contentColor = c.bg)) { Text("Salvar cartão") }
                }
            }
        }
    }
}

/** Lê um QR de uma imagem da galeria (zxing). */
private fun decodeQr(ctx: Context, uri: android.net.Uri): String? = runCatching {
    val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return null
    val px = IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
    MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bmp.width, bmp.height, px)))).text
}.getOrNull()

@Composable
private fun AddPixDialog(pix: PixVault, onDone: () -> Unit, onDismiss: () -> Unit) {
    val c = Fluwy.c
    val ctx = LocalContext.current
    var type by remember { mutableStateOf("CPF") }
    var key by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var city by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    fun fromCode(raw: String) {
        val p = PixRules.parse(raw)
        if (p == null) { err = "Não reconheci um código Pix."; return }
        err = if (!p.crcOk) "Atenção: o código lido tem o verificador (CRC) inválido — confira a chave." else null
        type = PixRules.guessType(p.key); key = p.key; if (p.name.isNotBlank()) name = p.name; if (p.city.isNotBlank()) city = p.city
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { decodeQr(ctx, it)?.let(::fromCode) ?: run { err = "Não encontrei um QR Code nessa imagem." } } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.94f), shape = RoundedCornerShape(24.dp), color = c.bg) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Adicionar chave Pix", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PixRules.TYPES.forEach { t -> FilterChip(type == t, { type = t; err = null }, { Text(if (t == "ALEATORIA") "Aleatória" else t.lowercase().replaceFirstChar { it.uppercase() }) }) }
                }
                VaultField(key, { key = it.take(80) }, when (type) { "EMAIL" -> "E-mail"; "TELEFONE" -> "Telefone com DDD"; "ALEATORIA" -> "Chave aleatória"; else -> type },
                    Icons.Rounded.Key, type in setOf("CPF", "CNPJ", "TELEFONE"))
                VaultField(name, { name = it.take(40) }, "Nome do recebedor (como no banco)", Icons.Rounded.Person, false)
                VaultField(city, { city = it.take(30) }, "Cidade", Icons.Rounded.LocationCity, false)
                VaultField(label, { label = it.take(30) }, "Apelido (opcional, ex.: Pix pessoal)", Icons.Rounded.Label, false)
                Text("Ou importe de um QR Code / Pix copia e cola:", fontSize = 12.sp, color = c.ink2)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { VaultField(code, { code = it; if (it.length > 30) fromCode(it) }, "Colar código Pix", Icons.Rounded.ContentPaste, false) }
                    FilledTonalIconButton({ pickImage.launch("image/*") }) { Icon(Icons.Rounded.QrCodeScanner, "Ler QR de uma imagem") }
                }
                err?.let { Text(it, color = c.err, fontSize = 12.sp) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onDismiss) { Text("Cancelar") }
                    Spacer(Modifier.width(6.dp))
                    Button({ err = runCatching { pix.add(type, key, name, city, label) }.getOrElse { it.message ?: "Falha ao salvar." }; if (err == null) onDone() },
                        shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = c.ink, contentColor = c.bg)) { Text("Salvar chave") }
                }
            }
        }
    }
}

internal fun qrBitmap(text: String, size: Int = 720): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val px = IntArray(size * size) { i -> if (m.get(i % size, i / size)) 0xff000000.toInt() else 0xffffffff.toInt() }
    return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply { setPixels(px, 0, size, 0, 0, size, size) }
}

/**
 * Cobrança Pix: abre já com o QR de VALOR LIVRE (quem paga digita) e permite gerar um QR de VALOR FIXO.
 * O código é conferido (releitura + CRC) antes de aparecer.
 */
@Composable
private fun PixChargeDialog(k: PixVault.PixKey, onDismiss: () -> Unit) {
    val c = Fluwy.c
    val ctx = LocalContext.current
    var amount by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    fun build(cv: Long) = runCatching { PixRules.checkedPayload(k.key, k.name, k.city, cv, PixRules.newTxid(), desc) }
    var shownCents by remember { mutableStateOf(0L) }
    var code by remember { mutableStateOf(build(0L).getOrNull()) }
    var editing by remember { mutableStateOf(false) }
    val cents = amount.filter { it.isDigit() }.toLongOrNull() ?: 0L
    fun money(cv: Long) = PixRules.money(cv)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.94f), shape = RoundedCornerShape(24.dp), color = c.bg) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Cobrar com Pix", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text("${k.masked} · ${k.name}", fontSize = 12.sp, color = c.ink2)
                if (code == null || editing) {
                    OutlinedTextField(if (cents > 0) money(cents) else "", { amount = it.filter { ch -> ch.isDigit() }.take(10) }, label = { Text("Valor fixo") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    VaultField(desc, { desc = it.take(40) }, "Descrição (opcional)", Icons.Rounded.Notes, false)
                    err?.let { Text(it, color = c.err, fontSize = 12.sp) }
                    Button({
                        err = null
                        if (cents < 1) err = "Informe um valor maior que zero." else build(cents).onSuccess { code = it; shownCents = cents; editing = false }.onFailure { err = it.message }
                    }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = c.ink, contentColor = c.bg)) { Text("Gerar QR com valor fixo") }
                    TextButton({ err = null; build(0L).onSuccess { code = it; shownCents = 0; editing = false }.onFailure { err = it.message } }) { Text("Usar valor livre") }
                } else {
                    val bmp = remember(code) { qrBitmap(code!!) }
                    Text(if (shownCents > 0) money(shownCents) else "Valor livre", fontWeight = FontWeight.Bold, fontSize = 26.sp)
                    Image(bmp.asImageBitmap(), "QR Code Pix", Modifier.size(240.dp).clip(RoundedCornerShape(12.dp)).background(Color.White).padding(8.dp))
                    Text(if (shownCents > 0) "Valor fixo no código. Qualquer alteração invalida o QR." else "Valor livre: quem paga digita o valor no banco.", fontSize = 11.sp, color = c.ink3)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton({
                            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Pix", code))
                            android.widget.Toast.makeText(ctx, "Pix copia e cola copiado", android.widget.Toast.LENGTH_SHORT).show()
                        }, shape = RoundedCornerShape(14.dp)) { Icon(Icons.Rounded.ContentCopy, null); Spacer(Modifier.width(6.dp)); Text("Copiar código") }
                        OutlinedButton({
                            val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, code)
                            ctx.startActivity(android.content.Intent.createChooser(send, "Enviar Pix"))
                        }, shape = RoundedCornerShape(14.dp)) { Icon(Icons.Rounded.Share, null); Spacer(Modifier.width(6.dp)); Text("Enviar") }
                    }
                    TextButton({ editing = true }) { Text(if (shownCents > 0) "Outro valor" else "Definir valor fixo") }
                }
                TextButton(onDismiss) { Text("Fechar") }
            }
        }
    }
}
