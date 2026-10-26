package dev.cueopen.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

@Composable
fun WalletReceiveQr(address: String) {
    val bitmap = remember(address) {
        runCatching {
            val matrix = QRCodeWriter().encode(address, BarcodeFormat.QR_CODE, QR_SIZE, QR_SIZE)
            Bitmap.createBitmap(QR_SIZE, QR_SIZE, Bitmap.Config.ARGB_8888).apply {
                for (y in 0 until QR_SIZE) {
                    for (x in 0 until QR_SIZE) {
                        setPixel(x, y, if (matrix.get(x, y)) 0xff000000.toInt() else 0xffffffff.toInt())
                    }
                }
            }
        }.getOrNull()
    }
    Column {
        Text("Receber SOL — QR da rede Solana")
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "QR do endereço público Solana",
                modifier = Modifier.size(220.dp)
            )
        } else {
            Text("Não foi possível gerar o QR. Use o endereço público exibido acima.")
        }
        Text("Este QR contém somente o endereço público; não contém frase de recuperação.")
    }
}

private const val QR_SIZE = 384
