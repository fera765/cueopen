package dev.cueopen.app.agent

/**
 * Roteamento "conector primeiro": quando o pedido fala de um app/serviço (e-mail, planilha, Instagram…)
 * ou pede explicitamente um conector, as ferramentas do conector certo já entram no contexto do 1º passo
 * e o prompt diz exatamente qual usar — sem o agente vasculhar arquivos, memória ou terminal antes.
 */
object ConnectorRouting {
    /** Pedido explícito de usar um conector/integração (não de CRIAR um). */
    private val USE_CONNECTOR = listOf("conector", "conectores", "connector", "connectors", "integracao", "integracoes", "hub de apps", "hub de conectores", "app conectado", "apps conectados", "mcp")
    private val CREATE_CONNECTOR = listOf("crie um conector", "criar um conector", "criar conector", "crie o conector", "novo conector", "construa um conector", "construir um conector", "create a connector", "build a connector", "edite o conector", "editar o conector")

    /** Apps/serviços que o hub (Activepieces) cobre — palavra no pedido ⇒ ferramentas do hub no contexto. */
    val HUB_KEYWORDS: Set<String> = setOf(
        "gmail", "email", "e-mail", "emails", "e-mails", "inbox", "caixa de entrada", "outlook",
        "planilha", "planilhas", "sheets", "google sheets", "excel", "google drive", "drive", "google docs", "docs",
        "google calendar", "calendario google", "agenda do google", "evento no calendario",
        "instagram", "facebook", "meta ads", "anuncio", "anuncios", "tiktok", "linkedin", "twitter", "youtube", "pinterest",
        "github", "gitlab", "supabase", "cloudflare", "vercel", "notion", "trello", "airtable", "asana", "clickup", "jira",
        "hubspot", "pipedrive", "salesforce", "stripe", "shopify", "woocommerce", "wordpress", "mailchimp", "seo", "search console",
        "google analytics", "analytics", "slack", "zapier", "webhook", "automacao", "automatize", "fluxo", "activepieces",
    )

    fun wantsToUseConnector(qNorm: String): Boolean =
        USE_CONNECTOR.any { qNorm.contains(it) } && CREATE_CONNECTOR.none { qNorm.contains(it) }

    fun wantsToBuildConnector(qNorm: String): Boolean = CREATE_CONNECTOR.any { qNorm.contains(it) }

    /** Palavras que ligam um servidor MCP ao pedido: nome, descrição, nomes de ferramentas e (no hub) apps cobertos/conectados. */
    fun keywordsFor(name: String, description: String, toolNames: List<String>, isHub: Boolean, inventory: String?): Set<String> {
        val out = LinkedHashSet<String>()
        fun addWords(s: String) = ToolContextPlanner.norm(s).split(Regex("[^a-z0-9]+")).filter { it.length >= 4 && it !in STOP }.forEach { out += it }
        addWords(name); addWords(description)
        toolNames.forEach { t -> t.split('_', '-', '.').filter { it.length >= 5 && it !in STOP }.forEach { out += ToolContextPlanner.norm(it) } }
        if (isHub) {
            out += HUB_KEYWORDS
            inventory?.let { addWords(it) }
        }
        return out
    }

    private val STOP = setOf("list", "lista", "listar", "create", "criar", "update", "delete", "remove", "para", "com", "the", "and", "uma", "dos", "das", "get", "set",
        "tool", "tools", "pieces", "piece", "flow", "flows", "server", "servidor", "dados", "data", "item", "items", "todos", "todas", "local")

    /**
     * Instruções do prompt para conectores. [connected] = (nome, prefixo mcp_xxx, é hub).
     * [hubTools] = nomes reais das ferramentas do hub (para indicar a de execução direta).
     */
    fun promptLines(connected: List<Triple<String, String, Boolean>>, hubInventory: String?, hubTools: List<String>, mentioned: List<String>): List<String> {
        if (connected.isEmpty()) return emptyList()
        val lines = ArrayList<String>()
        lines += "6a. CONECTOR PRIMEIRO: quando o pedido envolver um app/serviço (e-mail, planilha, rede social, GitHub…) ou o usuário pedir \"use o conector\", " +
            "a 1ª ação é chamar a ferramenta do conector (mcp_*). NUNCA procure conectores no sistema de arquivos, no terminal ou na memória — /root/connectors é armazenamento interno, não tem ferramentas. " +
            "Conectores ativos: " + connected.joinToString("; ") { (n, p, hub) -> "$n → ferramentas ${p}__*" + if (hub) " (hub com centenas de apps)" else "" } + "."
        connected.firstOrNull { it.third }?.let { (_, p, _) ->
            val direct = hubTools.firstOrNull { Regex("run_action|execute_action|run_piece|execute_piece|call_action").containsMatchIn(it) }
            lines += if (direct != null)
                "6a. Hub de apps (${p}__*), tarefa única (ler e-mails, enviar mensagem, criar linha…): " +
                    "1) connectionExternalId vem das contas conectadas abaixo (ou de ${p}__ap_list_connections); " +
                    "2) descubra actionName com ${p}__ap_search_actions e os campos exatos com ${p}__ap_get_piece_props; " +
                    "3) execute UMA vez com ${p}__$direct {pieceName, actionName, input, connectionExternalId}. " +
                    "NUNCA monte fluxo, webhook ou passo de código temporário para tarefa única; nunca apague passos para contornar erro. " +
                    "Peça poucos itens (ex.: limite 5–10) para o resultado vir enxuto. Fluxos (ap_build_flow) só para automação recorrente pedida pelo usuário."
            else
                "6a. Hub de apps (${p}__*): descubra a piece/ação com ap_research_pieces/ap_search_actions e use os nomes exatos; fluxos só para automação recorrente."
            hubInventory?.takeIf { it.isNotBlank() }?.let { lines += "6a. Contas já conectadas no hub (não precisa listar de novo se bastar): " + it.replace(Regex("\\s+"), " ").take(700) }
        }
        if (mentioned.isNotEmpty()) lines += "6a. O usuário marcou com @: " + mentioned.joinToString() + ". Use exatamente esse(s) recurso(s) — sem procurar alternativas."
        return lines
    }
}
