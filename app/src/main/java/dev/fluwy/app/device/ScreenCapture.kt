package dev.fluwy.app.device

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.CompletableDeferred
import java.io.File

/**
 * Captura de tela sob demanda via MediaProjection. O usuário autoriza na primeira vez (diálogo do
 * sistema) e a autorização é reutilizada enquanto o app estiver vivo.
 */
object ScreenCapture {
    private var projection: MediaProjection? = null
    private var resultCode = 0
    private var resultData: Intent? = null

    fun hasPermission() = resultData != null

    class RequestActivity : ComponentActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            val mgr = getSystemService(MediaProjectionManager::class.java)
            val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
                if (r.resultCode == Activity.RESULT_OK && r.data != null) { resultCode = r.resultCode; resultData = r.data }
                finish()
            }
            launcher.launch(mgr.createScreenCaptureIntent())
        }
    }

    /** Tira uma captura e salva em [out]. Retorna true se conseguiu. */
    suspend fun capture(ctx: Context, out: File): Boolean {
        val data = resultData ?: return false
        val mgr = ctx.getSystemService(MediaProjectionManager::class.java)
        val proj = mgr.getMediaProjection(resultCode, Intent(data))
        val dm: DisplayMetrics = ctx.resources.displayMetrics
        val w = dm.widthPixels; val h = dm.heightPixels
        val reader = ImageReader.newInstance(w, h, android.graphics.PixelFormat.RGBA_8888, 2)
        val done = CompletableDeferred<Boolean>()
        val handler = Handler(Looper.getMainLooper())
        var vd: VirtualDisplay? = null
        proj.registerCallback(object : MediaProjection.Callback() {}, handler)
        vd = proj.createVirtualDisplay("fluwy", w, h, dm.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, handler)
        reader.setOnImageAvailableListener({ r ->
            val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            runCatching {
                val plane = img.planes[0]
                val rowStride = plane.rowStride
                val bmp = Bitmap.createBitmap(rowStride / plane.pixelStride, h, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(plane.buffer)
                val cropped = Bitmap.createBitmap(bmp, 0, 0, w, h)
                out.outputStream().use { cropped.compress(Bitmap.CompressFormat.PNG, 90, it) }
                done.complete(true)
            }.onFailure { done.complete(false) }
            img.close(); r.setOnImageAvailableListener(null, null)
            handler.postDelayed({ runCatching { vd?.release(); reader.close(); proj.stop() } }, 200)
        }, handler)
        return runCatching { kotlinx.coroutines.withTimeout(6000) { done.await() } }.getOrDefault(false)
    }
}
