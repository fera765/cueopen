package dev.fluwy.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import dev.fluwy.app.wallet.SolanaNetwork

/** QR code only (no surrounding text) rendered on a white card for reliable scanning in dark mode. */
@Composable
fun WalletReceiveQr(address: String, network: SolanaNetwork) {
    val bitmap = remember(address) {
        runCatching {
            val matrix = QRCodeWriter().encode(address, BarcodeFormat.QR_CODE, QR_SIZE, QR_SIZE)
            val pixels = IntArray(QR_SIZE * QR_SIZE)
            for (y in 0 until QR_SIZE) {
                for (x in 0 until QR_SIZE) {
                    pixels[y * QR_SIZE + x] = if (matrix.get(x, y)) 0xff000000.toInt() else 0xffffffff.toInt()
                }
            }
            Bitmap.createBitmap(QR_SIZE, QR_SIZE, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, QR_SIZE, 0, 0, QR_SIZE, QR_SIZE)
            }
        }.getOrNull()
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "QR do endereço público Solana",
            modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(Color.White).padding(14.dp).size(216.dp)
        )
    } else {
        Text("Não foi possível gerar o QR. Use o endereço público abaixo.", style = MaterialTheme.typography.bodySmall)
    }
}

private const val QR_SIZE = 384
