package dev.cueopen.app.ui

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
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
    }
}
