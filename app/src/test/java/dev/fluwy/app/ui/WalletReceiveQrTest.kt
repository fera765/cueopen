package dev.fluwy.app.ui

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletReceiveQrTest {
    @Test
    fun publicAddressCanBeEncodedAsQrWithoutSecretMaterial() {
        val publicAddress = "HAgk14JpMQLgt6rVgv7cBQFJWFto5Dqxi472uT3DKpqk"
        val matrix = QRCodeWriter().encode(publicAddress, BarcodeFormat.QR_CODE, 128, 128)
        assertEquals(128, matrix.width)
        assertEquals(128, matrix.height)
        var darkModules = 0
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix.get(x, y)) darkModules++
            }
        }
        assertTrue(darkModules > 0)
        assertTrue(darkModules < matrix.width * matrix.height)

        val pixels = IntArray(matrix.width * matrix.height) { index ->
            val x = index % matrix.width
            val y = index / matrix.width
            if (matrix.get(x, y)) 0xff000000.toInt() else 0xffffffff.toInt()
        }
        val source = RGBLuminanceSource(matrix.width, matrix.height, pixels)
        val decoded = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)))
        assertEquals(publicAddress, decoded.text)
    }
}
