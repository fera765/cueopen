package dev.cueopen.app.agent

import dev.cueopen.app.data.ChatItem
import dev.cueopen.app.data.LlmMessage
import dev.cueopen.app.data.Session

/** Nunca mantém árvore ou imagem de tela no histórico persistente da sessão. */
internal object AccessibilityToolHistory {
    const val REDACTED_RESULT = "[Resultado de acessibilidade omitido do histórico por privacidade; releia a tela se necessário.]"

    fun durableResult(toolName: String, output: String): String =
        if (toolName.startsWith("device_accessibility_")) REDACTED_RESULT else output

    /** Reidrata somente resultados de acessibilidade ainda vivos na memória deste loop. */
    fun forActiveModel(message: LlmMessage, transientResults: Map<String, String>): LlmMessage {
        if (message.role != "tool" || message.name?.startsWith("device_accessibility_") != true) return message
        val live = message.toolCallId?.let(transientResults::get)
        return message.copy(content = live ?: REDACTED_RESULT)
    }

    /** Migra conteúdo bruto já salvo em cartões/histórico quando uma sessão antiga é retomada. */
    fun sanitizePersistedSession(session: Session): Session = session.copy(
        llm = session.llm.map { message ->
            if (message.role == "tool" && message.name?.startsWith("device_accessibility_") == true)
                message.copy(content = REDACTED_RESULT)
            else message
        },
        items = session.items.map { item ->
            if (item is ChatItem.Step && item.tool.startsWith("device_accessibility_") && item.result.isNotBlank())
                item.copy(result = REDACTED_RESULT)
            else item
        },
    )
}
