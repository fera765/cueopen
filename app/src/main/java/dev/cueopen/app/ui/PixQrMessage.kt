package dev.cueopen.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cueopen.app.data.ChatItem
import dev.cueopen.app.ui.theme.Cue
import dev.cueopen.app.vault.PixRules
import dev.cueopen.app.vault.PixVault

/** QR Pix gerado pelo agente: o código é remontado aqui, no aparelho, a partir do Cofre (nunca fica no chat salvo). */
@Composable
fun PixQrMessage(item: ChatItem.PixQr) {
    val c = Cue.c
    val ctx = LocalContext.current
    val code = remember(item.id) {
        runCatching {
            val k = PixVault(ctx).list().first { it.id == item.keyId }
            PixRules.checkedPayload(k.key, k.name, k.city, item.cents, item.txid.ifBlank { "***" }, item.description)
        }.getOrNull()
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.surface).border(1.dp, c.line, RoundedCornerShape(18.dp)).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Pix · ${item.keyLabel}", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = c.ink2)
        Text(if (item.cents > 0) PixRules.money(item.cents) else "Valor livre", fontWeight = FontWeight.Bold, fontSize = 24.sp)
        if (item.description.isNotBlank()) Text(item.description, fontSize = 12.sp, color = c.ink3)
        if (code == null) {
            Text("Esta chave não está mais no Cofre, então o QR não pode ser exibido.", color = c.err, fontSize = 12.sp)
        } else {
            val bmp = remember(code) { qrBitmap(code) }
            Image(bmp.asImageBitmap(), "QR Code Pix", Modifier.size(220.dp).clip(RoundedCornerShape(12.dp)).background(Color.White).padding(8.dp))
            Text(if (item.cents > 0) "Valor fixo no código: qualquer alteração invalida o QR." else "Quem paga digita o valor no banco.", fontSize = 11.sp, color = c.ink3)
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
        }
    }
}
