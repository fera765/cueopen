package dev.cueopen.app.agent

/** Limites do chat de edição: proposta para o alvo selecionado e browser sem interação com páginas. */
internal object ConnectorEditToolPolicy {
    const val PROPOSE_TOOL = "connector_edit_propose"

    val READ_ONLY_BROWSER_TOOLS = setOf(
        "browser_navigate",
        "browser_snapshot",
        "browser_read",
        "browser_scroll",
        "browser_back",
    )

    fun allows(name: String, browserEnabled: Boolean): Boolean =
        name == PROPOSE_TOOL || (browserEnabled && name in READ_ONLY_BROWSER_TOOLS)
}
