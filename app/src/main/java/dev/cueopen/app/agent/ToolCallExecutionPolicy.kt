package dev.cueopen.app.agent

/** O dispatcher executa somente nomes cujo schema foi realmente enviado ao provider no passo atual. */
internal object ToolCallExecutionPolicy {
    fun isExposed(toolName: String, exposedToolNames: Set<String>): Boolean =
        toolName.isNotBlank() && toolName in exposedToolNames
}
