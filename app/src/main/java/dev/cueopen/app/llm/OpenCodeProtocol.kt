package dev.cueopen.app.llm

import dev.cueopen.app.BuildConfig
import java.io.IOException
import java.net.URI

/** Contrato de transporte específico do OpenCode Go. */
internal object OpenCodeProtocol {
    private fun uri(baseUrl: String): URI? = runCatching { URI(baseUrl.trim()) }.getOrNull()

    fun isOpenCodeHost(baseUrl: String): Boolean =
        uri(baseUrl)?.host?.equals("opencode.ai", ignoreCase = true) == true

    fun userAgent(baseUrl: String): String? =
        if (isOpenCodeHost(baseUrl)) "CueOpen/${BuildConfig.VERSION_NAME}" else null

    /** A conversa reutiliza o ID persistido da Session; Go não aceita chamadas sem ele. */
    fun sessionHeader(baseUrl: String, conversationId: String?): String? {
        val parsed = uri(baseUrl) ?: return null
        if (parsed.host?.equals("opencode.ai", ignoreCase = true) != true) return null
        val id = conversationId?.trim()?.takeIf { it.isNotEmpty() }
        val isGo = parsed.path.orEmpty().startsWith("/inference/go/")
        if (isGo && id == null) throw IOException("OpenCode Go exige um ID de conversa estável em x-opencode-session.")
        return id
    }

}
