package dev.cueopen.app.browser

import android.app.Activity
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout
import java.lang.ref.WeakReference

/**
 * "Palco" invisível para o browser do agente.
 *
 * Um WebView solto (fora de qualquer janela) é tratado pelo Chromium como página ESCONDIDA:
 * requestAnimationFrame e boa parte da renderização ficam parados. Sites como a Biblioteca de
 * Anúncios do Facebook nunca terminam de montar (`readyState=loading`, 0 caracteres) — embora
 * abram normalmente quando o usuário olha a aba Browser, porque aí o WebView entra na tela.
 *
 * Aqui o WebView do agente é anexado:
 *  1. atrás do conteúdo da Activity (índice 0 do decorView): desenhado, mas coberto pela UI; ou
 *  2. com o app em segundo plano, numa janela sobreposta de 1 px, se a permissão de sobreposição existir.
 * Quando a UI mostra o Browser, o WebView sai do palco e vai para a tela normalmente.
 */
internal object OffscreenStage {
    private const val TAG = "cue_offscreen_stage"
    private var activityRef: WeakReference<Activity>? = null
    private var overlay: FrameLayout? = null

    fun onActivityResumed(a: Activity) { activityRef = WeakReference(a) }

    fun onActivityDestroyed(a: Activity) {
        if (activityRef?.get() === a) activityRef = null
        // Não deixa WebViews presos à janela destruída.
        (a.window?.decorView as? ViewGroup)?.findViewWithTag<FrameLayout>(TAG)?.removeAllViews()
    }

    fun isStaged(v: View): Boolean = (v.parent as? View)?.tag == TAG

    /** Remove do palco (antes de a UI exibir o WebView na tela). */
    fun release(v: WebView) {
        if (isStaged(v)) (v.parent as? ViewGroup)?.removeView(v)
    }

    /** Garante que o WebView esteja numa janela. Deve rodar na main thread. */
    fun attach(v: WebView, width: Int, height: Int): Boolean {
        if (v.isAttachedToWindow) return true
        (v.parent as? ViewGroup)?.removeView(v)
        val stage = activityStage() ?: overlayStage(v.context) ?: return false
        return runCatching {
            stage.addView(v, FrameLayout.LayoutParams(width, height))
            v.onResume()
            true
        }.getOrDefault(false)
    }

    private fun activityStage(): FrameLayout? {
        val a = activityRef?.get()?.takeIf { !it.isFinishing && !it.isDestroyed } ?: return null
        val decor = a.window?.decorView as? ViewGroup ?: return null
        decor.findViewWithTag<FrameLayout>(TAG)?.let { return it }
        return FrameLayout(a).apply {
            tag = TAG
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            // índice 0 = atrás do conteúdo do app: é desenhado (o Chromium o vê como visível), mas fica coberto.
            decor.addView(this, 0, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    private fun overlayStage(ctx: Context): FrameLayout? {
        overlay?.takeIf { it.isAttachedToWindow }?.let { return it }
        val app = ctx.applicationContext
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(app)) return null
        return runCatching {
            val wm = app.getSystemService(WindowManager::class.java)
            val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
            val lp = WindowManager.LayoutParams(1, 1, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT).apply { x = -10_000; y = -10_000; alpha = 0.01f }
            FrameLayout(app).apply { tag = TAG }.also { wm.addView(it, lp); overlay = it }
        }.getOrNull()
    }
}
