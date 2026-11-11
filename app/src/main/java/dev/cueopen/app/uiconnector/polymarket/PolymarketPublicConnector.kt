package dev.cueopen.app.uiconnector.polymarket

import dev.cueopen.app.uiconnector.ConnectorSettings
import dev.cueopen.app.uiconnector.ParamSpec
import dev.cueopen.app.uiconnector.RequiresAgentTerminal
import dev.cueopen.app.uiconnector.Tone
import dev.cueopen.app.uiconnector.UiBlock
import dev.cueopen.app.uiconnector.UiConnector
import dev.cueopen.app.uiconnector.UiConnectorCallContext
import dev.cueopen.app.uiconnector.UiConnectorSpec
import dev.cueopen.app.uiconnector.ToolSpec

/** Public Polymarket reader. It has no relationship to the local Solana wallet or trading. */
class PolymarketPublicConnector(
    private val curlProgram: String,
    private val runInTerminal: suspend (sessionId: String, command: String) -> String,
) : UiConnector, RequiresAgentTerminal {
    override val spec = UiConnectorSpec(
        id = ID,
        name = "Polymarket público",
        description = "Consulta carteiras EVM e posições da Polymarket por curl no terminal Linux. API pública, sem login, API key ou acesso à Wallet Solana; somente leitura.",
        version = "1.0.0",
        loginFields = emptyList(),
        tools = listOf(
            ToolSpec(
                name = "list_wallets",
                description = "Busca carteiras públicas nos leaderboards mensal e acumulado da Polymarket, via curl no terminal Linux. Somente leitura; não consulta nem usa a Wallet Solana.",
                params = listOf(ParamSpec("limit", "Número de carteiras por leaderboard (1 a 10; padrão 10).", "integer", required = false)),
            ),
            ToolSpec(
                name = "analyze_wallets",
                description = "Analisa, via curl, uma amostra de carteiras públicas da Polymarket: PnL realizado das posições encerradas dentro da janela móvel de 90 dias. Somente leitura: não envia nem copia ordens e não usa a Wallet Solana.",
                params = listOf(ParamSpec("limit", "Número de carteiras por leaderboard (1 a 10; padrão 10).", "integer", required = false)),
            ),
        ),
        accent = 0xFF5D68D8,
    )

    override val isLoggedIn: Boolean = true

    override suspend fun login(credentials: Map<String, String>): Result<Unit> = Result.success(Unit)
    override suspend fun logout() = Unit

    override suspend fun snapshot(settings: ConnectorSettings): List<UiBlock> = listOf(
        UiBlock.Header("Polymarket público", "Carteiras EVM consultadas via curl na sessão Linux do agente", "Somente leitura", Tone.NEUTRAL),
        UiBlock.Note("A API não exige chave. Ative este conector no perfil do agente e mantenha Terminal Linux habilitado. O conector não acessa a carteira Solana, não negocia e não copia ordens."),
    )

    override suspend fun onAction(actionId: String, args: Map<String, String>, settings: ConnectorSettings): Result<String> =
        Result.failure(IllegalArgumentException("Este conector não possui ações de escrita."))

    override suspend fun callTool(name: String, args: Map<String, String>, settings: ConnectorSettings): String =
        "ERRO: a consulta Polymarket precisa do contexto da sessão Android e do terminal Linux ativo."

    override suspend fun callTool(
        name: String,
        args: Map<String, String>,
        settings: ConnectorSettings,
        context: UiConnectorCallContext,
    ): String {
        if (name !in TOOL_NAMES) return "ERRO: ferramenta Polymarket desconhecida."
        val sessionId = context.sessionId?.takeIf { it.isNotBlank() }
            ?: return "ERRO: não foi possível identificar a sessão de terminal deste chat."
        val limitText = args["limit"]?.takeIf { it.isNotBlank() } ?: "10"
        val limit = limitText.toIntOrNull()?.takeIf { it in 1..10 }
            ?: return "ERRO: limit precisa ser um número inteiro entre 1 e 10."
        val command = "python3 -c ${shellQuote(curlProgram)} ${shellQuote(name)} --limit $limit"
        return runInTerminal(sessionId, command)
    }

    override fun secrets(): List<String> = emptyList()

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    companion object {
        const val ID = "polymarket"
        private val TOOL_NAMES = setOf("list_wallets", "analyze_wallets")
    }
}
