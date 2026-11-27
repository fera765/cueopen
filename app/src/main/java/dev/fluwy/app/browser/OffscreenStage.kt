package dev.fluwy.app.browser

import android.app.Activity
import android.app.Presentation
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
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
 *  2. com o app em segundo plano ou fora dos recentes, num DISPLAY VIRTUAL privado do próprio app
 *     (VirtualDisplay + Presentation): uma janela real e visível para o Chromium, que não exige
 *     nenhuma permissão nem Activity. É a mesma técnica usada para hospedar views Android fora da tela; ou
 *  3. se o display virtual não puder ser criado, numa janela sobreposta de 1 px (permissão de sobreposição).
 * Quando a UI mostra o Browser, o WebView sai do palco e vai para a tela normalmente.
 */
internal object OffscreenStage {
    private const val TAG = "fluwy_offscreen_stage"
    private var activityRef: WeakReference<Activity>? = null
    private var overlay: FrameLayout? = null
    private var vDisplay: VirtualDisplay? = null
    private var vReader: ImageReader? = null
    private var vPresentation: Presentation? = null
    private var vStage: FrameLayout? = null

    /** App em primeiro plano (para o diagnóstico). */
    @Volatile var foreground: Boolean = false
        private set

    fun onActivityResumed(a: Activity) { activityRef = WeakReference(a); foreground = true }

    fun onActivityPaused(a: Activity) {
        if (activityRef?.get() === a) foreground = false
        // Janela da Activity deixa de ser visível: o Chromium pausaria as páginas. Leva os WebViews para o display virtual.
        runCatching { migrateFromActivity(a) }
    }

    /** Tira os WebViews do palco da Activity e os põe num palco que continua visível sem o app na tela. */
    private fun migrateFromActivity(a: Activity) {
        val stage = (a.window?.decorView as? ViewGroup)?.findViewWithTag<FrameLayout>(TAG) ?: return
        val views = (0 until stage.childCount).mapNotNull { stage.getChildAt(it) as? WebView }
        if (views.isEmpty()) return
        val target = virtualStage(a.applicationContext) ?: return
        stage.removeAllViews()
        views.forEach { v -> runCatching { target.addView(v, FrameLayout.LayoutParams(v.width.coerceAtLeast(360), v.height.coerceAtLeast(480))); v.onResume() } }
    }

    fun onActivityDestroyed(a: Activity) {
        if (activityRef?.get() === a) { activityRef = null; foreground = false }
        // Não deixa WebViews presos à janela destruída.
        runCatching { migrateFromActivity(a) }
    }

    fun overlayPermission(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 23 || runCatching { Settings.canDrawOverlays(ctx.applicationContext) }.getOrDefault(false)

    /** Onde o WebView está agora, em linguagem do log. */
    fun placement(v: WebView): String = when {
        !v.isAttachedToWindow -> "sem janela (o Chromium pausa a página)"
        isStaged(v) && v.parent === vStage -> "palco em display virtual (app em segundo plano)"
        isStaged(v) && v.parent === overlay -> "palco em janela sobreposta (app em segundo plano)"
        isStaged(v) -> "palco atrás da UI do app"
        else -> "na tela (aba Browser aberta)"
    }

    fun isStaged(v: View): Boolean = (v.parent as? View)?.tag == TAG

    /** Remove do palco (antes de a UI exibir o WebView na tela). */
    fun release(v: WebView) {
        if (isStaged(v)) (v.parent as? ViewGroup)?.removeView(v)
    }

    /** Garante que o WebView esteja numa janela. Deve rodar na main thread. */
    fun attach(v: WebView, width: Int, height: Int): Boolean {
        if (v.isAttachedToWindow) return true
        // Já está no display virtual e a janela dele acabou de ser criada: o "anexado" chega no próximo quadro.
        if (v.parent === vStage && vPresentation?.isShowing == true) return true
        (v.parent as? ViewGroup)?.removeView(v)
        // Em primeiro plano: atrás da UI. Fora dele (ou sem Activity): display virtual, que não depende de permissão.
        val stage = (if (foreground) activityStage() else null) ?: virtualStage(v.context) ?: activityStage() ?: return false
        return runCatching {
            stage.addView(v, FrameLayout.LayoutParams(width, height))
            v.onResume()
            true
        }.getOrDefault(false)
    }

    /** true quando existe um palco utilizável sem o app na tela (para o diagnóstico e as mensagens). */
    fun backgroundCapable(ctx: Context): Boolean = vPresentation?.isShowing == true || overlayPermission(ctx)

    /**
     * Palco num display virtual privado. O Presentation é uma janela de verdade nesse display, então o WebView
     * fica "na tela" para o Chromium (renderiza, roda JS, timers e requestAnimationFrame) mesmo com o app
     * em segundo plano ou removido dos recentes — o serviço em primeiro plano mantém o processo vivo.
     */
    private fun virtualStage(ctx: Context): FrameLayout? {
        vStage?.takeIf { vPresentation?.isShowing == true && vDisplay?.display?.isValid == true }?.let { return it }
        return runCatching {
            teardownVirtual()
            val app = ctx.applicationContext
            val dm = app.resources.displayMetrics
            val w = dm.widthPixels.coerceIn(360, 1440)
            val h = dm.heightPixels.coerceIn(640, 3200)
            val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            // Os quadros só precisam ser consumidos (senão o compositor trava); ninguém os exibe.
            reader.setOnImageAvailableListener({ r -> runCatching { r.acquireLatestImage()?.close() } }, Handler(Looper.getMainLooper()))
            val display = app.getSystemService(DisplayManager::class.java).createVirtualDisplay(
                "fluwy-offscreen-browser", w, h, dm.densityDpi, reader.surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
            ) ?: error("display virtual indisponível")
            val stage = FrameLayout(app).apply {
                tag = TAG
                isClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
            val presentation = Presentation(app, display.display)
            presentation.window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
            presentation.setContentView(stage, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            presentation.setCancelable(false)
            presentation.show()
            vReader = reader; vDisplay = display; vPresentation = presentation; vStage = stage
            stage
        }.onFailure {
            runCatching { dev.fluwy.app.diag.ErrorLog.log("browser", "Display virtual do browser indisponível: ${it.javaClass.simpleName}: ${it.message}") }
            teardownVirtual()
        }.getOrNull()
    }

    private fun teardownVirtual() {
        runCatching { vStage?.removeAllViews() }
        runCatching { vPresentation?.dismiss() }
        runCatching { vDisplay?.release() }
        runCatching { vReader?.close() }
        vStage = null; vPresentation = null; vDisplay = null; vReader = null
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
