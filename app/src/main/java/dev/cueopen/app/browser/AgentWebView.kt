package dev.cueopen.app.browser

import android.content.Context
import android.view.View
import android.webkit.WebView

/**
 * WebView do agente que nunca é "escondido" para o Chromium.
 *
 * Com o app em segundo plano a janela fica invisível; o WebView repassa isso ao Chromium, que marca a
 * página como `hidden` e congela timers (setTimeout limitado a 1×/s e depois 1×/min) e renderização.
 * O Facebook (e outras SPAs) então nunca saem de `readyState=loading` — exatamente o que os logs
 * mostravam enquanto o usuário não olhava a tela. Aqui a visibilidade real fica registrada para
 * diagnóstico, mas o Chromium sempre recebe VISIBLE, como acontece num Chrome de desktop.
 */
internal class AgentWebView(context: Context) : WebView(context) {
    /** Visibilidade real da janela (para o diagnóstico de navegação). */
    @Volatile var realWindowVisibility: Int = View.GONE
        private set

    override fun onWindowVisibilityChanged(visibility: Int) {
        realWindowVisibility = visibility
        super.onWindowVisibilityChanged(View.VISIBLE)
    }

    fun windowVisibilityLabel(): String = when {
        !isAttachedToWindow -> "sem janela"
        realWindowVisibility == View.VISIBLE -> "janela visível"
        else -> "janela invisível (app em segundo plano) — página mantida ativa"
    }
}
