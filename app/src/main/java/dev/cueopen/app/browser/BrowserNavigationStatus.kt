package dev.cueopen.app.browser

/**
 * Tracks HTTP failures for the current top-level WebView navigation.
 * WebView also reports HTTP errors for subresources, which must not fail the page.
 */
internal class BrowserNavigationStatus {
    @Volatile private var mainFrameHttpError: String? = null

    fun recordHttpError(isMainFrame: Boolean, statusCode: Int): String? {
        if (!isMainFrame || statusCode !in 400..599) return null
        return "ERROR: HTTP $statusCode na página principal. Não use este conteúdo nem repita a URL; localize um endereço válido."
            .also { mainFrameHttpError = it }
    }

    fun pageFinished(): String = mainFrameHttpError ?: "OK"

    fun preserveHttpError(result: String): String = mainFrameHttpError ?: result

    fun httpError(): String? = mainFrameHttpError
}
