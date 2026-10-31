package dev.cueopen.app.browser

/**
 * Tracks HTTP failures for the current top-level WebView navigation.
 * WebView also reports HTTP errors for subresources, which must not fail the page.
 */
internal class BrowserNavigationStatus {
    @Volatile private var mainFrameError: String? = null

    fun recordHttpError(isMainFrame: Boolean, statusCode: Int): String? {
        if (!isMainFrame || statusCode !in 400..599) return null
        return "ERROR: HTTP $statusCode na página principal. Não use este conteúdo nem repita a URL; localize um endereço válido."
            .also { mainFrameError = it }
    }

    fun recordNetworkError(isMainFrame: Boolean, url: String, description: String): String? {
        if (!isMainFrame) return null
        val detail = description.trim().ifBlank { "falha de rede sem detalhes" }
        val code = detail.substringAfter("net::", detail).uppercase()
        val local = LoopbackEndpointProbe.endpoint(url)
        val message = when {
            code.contains("CONNECTION_REFUSED") && local != null ->
                "Conexão recusada em ${local.authority}. `localhost` é o próprio dispositivo/ambiente do WebView; confirme que o servidor está rodando nessa porta e acessível a partir dele. Não repita a navegação até corrigir o servidor ou o endereço."
            code.contains("CONNECTION_REFUSED") ->
                "O servidor recusou a conexão. Confira host, porta e se o serviço está ativo antes de tentar outra abordagem."
            code.contains("TIMED_OUT") || code.contains("TIMEOUT") ->
                "A conexão com o servidor expirou. Verifique alcance, processo e logs; não repita a mesma navegação sem diagnosticar a causa."
            code.contains("NAME_NOT_RESOLVED") ->
                "O nome do host não foi resolvido. Confira a URL ou localize o endereço oficial antes de tentar novamente."
            code.contains("INTERNET_DISCONNECTED") || code.contains("NETWORK_CHANGED") ->
                "A rede foi desconectada ou mudou durante a navegação. Confirme a conectividade antes de tentar novamente."
            code.contains("ADDRESS_UNREACHABLE") ->
                "O endereço não está acessível a partir do navegador. Confira rota, host e ambiente de rede."
            else -> "Falha de rede ao abrir a página. Inspecione a causa antes de repetir a navegação."
        }
        return "ERROR: $message (WebView: $detail; URL: $url)".also { mainFrameError = it }
    }

    fun timeoutMessage(targetUrl: String, currentUrl: String, readyState: String, bodyChars: Int): String {
        val current = currentUrl.ifBlank { "indisponível" }
        val ready = readyState.ifBlank { "indisponível" }
        val endpoint = LoopbackEndpointProbe.endpoint(targetUrl)
        return if (bodyChars >= 200) {
            "PARTIAL: há $bodyChars caracteres visíveis, mas o WebView não confirmou o fim do carregamento. URL atual: $current; document.readyState=$ready. O conteúdo ainda não prova que o app esteja pronto; inspecione o DOM/console e confirme o estado funcional esperado antes de agir."
        } else if (endpoint != null) {
            "TIMEOUT: o servidor local em ${endpoint.authority} aceitou a conexão, mas a navegação não produziu conteúdo nem confirmou o DOM. Verifique logs do servidor, resposta/rota, assets e erros de JavaScript; `localhost` é relativo ao dispositivo/ambiente do WebView. URL atual: $current; document.readyState=$ready. Não aumente o prazo nem repita a mesma chamada sem diagnóstico."
        } else {
            "TIMEOUT: o WebView não confirmou o fim do carregamento nem encontrou conteúdo útil. Verifique URL atual, rede, console, requisições e estado do DOM antes de tentar novamente. URL solicitada: $targetUrl; URL atual: $current; document.readyState=$ready."
        }
    }

    fun pageFinished(): String = mainFrameError ?: "OK"

    fun preserveHttpError(result: String): String = mainFrameError ?: result

    fun httpError(): String? = mainFrameError
}
