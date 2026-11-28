package dev.fluwy.app.agent

/**
 * Stops deterministic file-tool failures from being replayed indefinitely by the agent.
 * Successful file operations reset the consecutive-failure streak; identical failures are
 * stopped sooner even if the model ignores the recovery instruction.
 */
internal class AgentFileToolFailureGuard(
    private val identicalFailureLimit: Int = 2,
    private val consecutiveFailureLimit: Int = 3,
) {
    private val identicalFailures = HashMap<String, Int>()
    private var consecutiveFailures = 0

    init {
        require(identicalFailureLimit >= 2)
        require(consecutiveFailureLimit >= identicalFailureLimit)
    }

    /** Returns a stop reason only when another file-tool attempt would repeat known failure. */
    fun record(toolName: String, normalizedArgs: String, output: String): String? {
        if (toolName !in FILE_TOOLS) return null
        if (!isFailure(output)) {
            consecutiveFailures = 0
            return null
        }

        consecutiveFailures++
        val signature = "$toolName|$normalizedArgs|${output.take(600)}"
        val identicalCount = (identicalFailures[signature] ?: 0) + 1
        identicalFailures[signature] = identicalCount

        return when {
            identicalCount >= identicalFailureLimit ->
                "A mesma operação de arquivo falhou repetidamente sem mudança de argumentos."
            consecutiveFailures >= consecutiveFailureLimit ->
                "Várias operações de arquivo falharam sem progresso observável."
            else -> null
        }
    }

    private fun isFailure(output: String): Boolean =
        output.startsWith("ERRO:", ignoreCase = true) ||
            output.startsWith("ERROR:", ignoreCase = true) ||
            output.startsWith("ERRO ao executar", ignoreCase = true) ||
            output.startsWith("Erro:", ignoreCase = true)

    private companion object {
        val FILE_TOOLS = setOf("file_read", "file_list")
    }
}
