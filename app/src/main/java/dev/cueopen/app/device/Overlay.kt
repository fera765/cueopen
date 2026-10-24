package dev.cueopen.app.device

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/** Balão flutuante simples sobre outros apps (requer SYSTEM_ALERT_WINDOW). */
object Overlay {
    fun show(ctx: Context, text: String, millis: Long = 6000) {
        if (!Settings.canDrawOverlays(ctx)) return
        Handler(Looper.getMainLooper()).post {
            runCatching {
                val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val tv = TextView(ctx).apply {
                    this.text = text; setTextColor(Color.WHITE); setPadding(36, 28, 36, 28); textSize = 15f
                    setBackgroundColor(0xF0FF6B3D.toInt())
                }
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT,
                ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = 120 }
                wm.addView(tv, lp)
                Handler(Looper.getMainLooper()).postDelayed({ runCatching { wm.removeView(tv) } }, millis)
            }
        }
    }
}
