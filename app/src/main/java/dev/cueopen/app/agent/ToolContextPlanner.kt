package dev.cueopen.app.agent

import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

/**
 * Seleção contextual de ferramentas e ajuste do orçamento de contexto do agente.
 *
 * Antes, TODA chamada ao modelo levava o esquema de TODAS as ferramentas (carteira, trading, aparelho,
 * devtools, criador de conectores, cada ferramenta de cada conector e MCP), além de knowledge/memórias
 * com orçamento fixo. Isso custava milhares de tokens de entrada por passo, mesmo numa tarefa simples.
 *
 * O orquestrador decide, a cada passo, o que entra no contexto:
 *  - FERRAMENTAS por grupos: núcleo sempre; grupos especializados só quando o pedido indica, quando já
 *    foram usados na tarefa (fixos dali em diante — mantém o prefixo estável para o cache de prompt) ou
 *    quando o modelo pede com `tools_load`. Grupos ocultos aparecem num índice de 1 linha.
 *  - CONECTORES (uic_*) e servidores MCP (mcp_*): só os relevantes ao pedido/já usados; o resto no índice.
 *  - SKILLS: o índice mostra as mais relevantes primeiro, com teto.
 *  - ORÇAMENTOS de knowledge/memória encolhem conforme o histórico cresce (o total cabe no orçamento).
 */
object ToolContextPlanner {
    /** Grupos de ferramentas nativas (o nome do grupo é o que o modelo passa em tools_load). */
    val GROUPS: Map<String, Set<String>> = linkedMapOf(
        "core" to setOf("update_plan", "shell", "file_write", "file_edit", "file_read", "file_list", "complete_step", "show_file",
            "browser_navigate", "browser_read", "browser_snapshot", "browser_click", "browser_type", "browser_scroll", "browser_back", "browser_wait_for",
            "notes_write", "memory_save", "memory_search", "skill_load", "ask_user", "ask_question", "ask_secure", "finish", "tools_load"),
        "web_dev" to setOf("browser_js", "browser_network", "browser_console"),
        "agents" to setOf("multi_agents"),
        "schedule" to setOf("sleep", "set_schedule"),
        "skills_admin" to setOf("create_skill"),
        "wallet" to setOf("wallet_get_address", "wallet_get_balance", "wallet_list_tokens", "wallet_swap",
            "market_price", "market_candles", "market_liquidity", "market_discover", "market_token_info",
            "trade_set_rule", "trade_list", "trade_cancel", "trade_update_rule"),
        "device" to setOf(
            "device_notifications", "device_notify", "device_screenshot", "device_overlay",
            "device_bluetooth", "device_bluetooth_pair", "device_calendar_read", "device_calendar_create",
            "device_call_log", "device_contacts", "device_location",
            "device_dial", "device_microphone",
            "device_accessibility_open_app", "device_accessibility_read_screen",
            "device_accessibility_click", "device_accessibility_type",
            "device_accessibility_scroll", "device_accessibility_back",
            "device_accessibility_inspect_image"
        ),
        "connector_builder" to setOf("connector_spec", "connector_validate", "connector_test", "connector_propose", "connector_edit_propose"),
    )

    private val GROUP_OF: Map<String, String> = GROUPS.flatMap { (g, ts) -> ts.map { it to g } }.toMap()

    /** Palavras do pedido que ativam cada grupo (sem acento, minúsculas). */
    private val TRIGGERS: Map<String, List<String>> = mapOf(
        "web_dev" to listOf("javascript", "console", "network", "rede do site", "requisic", "devtools", "api do site", "scrap", "raspar", "depur", "debug", "xhr", "fetch", "cookie", "dom", "userscript", "user script", "tampermonkey"),
        "agents" to listOf("subagente", "sub-agente", "multi agente", "multiagente", "paralel", "em paralelo", "varios agentes", "workers", "pesquisa ampla", "wide research"),
        "schedule" to listOf("monitor", "acompanh", "a cada", "agend", "todo dia", "toda hora", "de hora em hora", "continu", "periodic", "lembr", "aguard", "espera", "minutos", "vela"),
        "skills_admin" to listOf("crie uma skill", "criar skill", "nova skill", "salve como skill"),
        "wallet" to listOf("carteira", "wallet", "solana", "sol ", "token", "swap", "saldo", "comprar", "vender", "day trade", "daytrade", "trade", "cripto", "crypto", "memecoin", "stop loss", "take profit", "liquidez", "pump", "jupiter", "usdc"),
        "device" to listOf("notifica", "print da tela", "captura de tela", "screenshot", "balao", "overlay", "celular", "aparelho", "bluetooth", "dispositivo pareado", "dispositivos pareados", "dispositivos proximos", "calendario", "ligaçao", "ligacao", "chamada", "contato", "contacts", "contact list", "address book", "agenda telefonica", "agenda de contatos", "telefone", "numero do contato", "localizaçao", "localizacao", "microfone", "navegar no aplicativo", "navegar nos apps", "abrir aplicativo", "abrir app", "acessar app", "acessar aplicativo", "outro aplicativo", "instagram", "whatsapp", "spotify"),
        "connector_builder" to listOf("conector", "connector"),
    )

    data class Input(
        val sessionId: String,
        /** Texto recente do usuário + resumo (o que o pedido indica). */
        val query: String,
        /** Nomes das ferramentas já chamadas nesta tarefa. */
        val usedTools: Set<String>,
        val builder: Boolean,
        /** Grupos que o agente tem permissão de usar (null = sem restrição extra). */
        val allowedGroups: Set<String>? = null,
        /** Conectores UI disponíveis: id → (nome, descrição). */
        val connectors: Map<String, Pair<String, String>> = emptyMap(),
        /** Servidores MCP: prefixo (ex.: "mcp_github") → nome. */
        val mcpServers: Map<String, String> = emptyMap(),
        /** Tokens estimados do histórico atual e orçamento de contexto. */
        val historyTokens: Int = 0,
        val contextBudget: Int = 24_000,
    )

    data class Plan(
        val groups: Set<String>,
        val connectors: Set<String>,
        val mcp: Set<String>,
        val hiddenGroups: List<String>,
        val hiddenConnectors: List<String>,
        val hiddenMcp: List<String>,
        val knowledgeChars: Int,
        val memoryChars: Int,
        val skillsShown: Int,
    ) {
        /** Fica no esquema enviado ao modelo? */
        fun keepTool(name: String, connectorIdOf: (String) -> String?, mcpPrefixOf: (String) -> String?): Boolean {
            if (name.startsWith("uic_")) return connectorIdOf(name)?.let { it in connectors } ?: true
            if (name.startsWith("mcp_")) return mcpPrefixOf(name)?.let { it in mcp } ?: true
            val g = GROUP_OF[name] ?: return true // ferramenta nova não classificada: nunca esconder
            return g in groups
        }

        /** Linha do system prompt com o que está disponível sob demanda (barata: só nomes). */
        fun index(connectorNames: Map<String, String>, mcpNames: Map<String, String>): String {
            val parts = ArrayList<String>()
            if (hiddenGroups.isNotEmpty()) parts += hiddenGroups.joinToString { "$it (${GROUPS[it].orEmpty().take(4).joinToString("/")}…)" }
            if (hiddenConnectors.isNotEmpty()) parts += "conectores: " + hiddenConnectors.joinToString { "connector:$it (${connectorNames[it] ?: it})" }
            if (hiddenMcp.isNotEmpty()) parts += "MCP: " + hiddenMcp.joinToString { "mcp:$it (${mcpNames[it] ?: it})" }
            return if (parts.isEmpty()) "" else
                "Ferramentas sob demanda (fora do contexto para economizar tokens; chame tools_load com os nomes quando precisar): " + parts.joinToString(" · ")
        }
    }

    /** Grupos/conectores pedidos pelo modelo via tools_load — fixos até o fim da tarefa. */
    private val loaded = ConcurrentHashMap<String, MutableSet<String>>()

    fun load(sessionId: String, names: List<String>): List<String> {
        val set = loaded.getOrPut(sessionId) { ConcurrentHashMap.newKeySet() }
        val ok = names.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        set += ok
        return ok
    }

    fun loadedFor(sessionId: String): Set<String> = loaded[sessionId].orEmpty()
    fun forget(sessionId: String) { loaded.remove(sessionId) }

    fun norm(s: String): String = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    private fun words(s: String) = norm(s).split(Regex("[^a-z0-9]+")).filter { it.length >= 4 }.toSet()

    /** Gatilhos curtos ("dom", "sol") só valem como palavra inteira — "domingo" não ativa devtools. */
    private fun hit(q: String, t: String): Boolean {
        val k = t.trim()
        return if (k.length <= 4) Regex("(?<![a-z0-9])" + Regex.escape(k) + "(?![a-z0-9])").containsMatchIn(q) else q.contains(t)
    }

    fun plan(i: Input): Plan {
        val all = GROUPS.keys
        val allowed = i.allowedGroups?.plus("core") ?: all
        val q = " " + norm(i.query) + " "
        val sticky = loadedFor(i.sessionId)
        val publicMarketResearch = listOf("polymarket", "mercado preditivo", "prediction market").any { hit(q, it) }
        val solanaWalletAction = listOf("solana", "sol ", "saldo", "transfer", "swap", "jupiter", "token", "day trade", "daytrade", "liquidez").any { hit(q, it) }
        val groups = LinkedHashSet<String>().apply { add("core") }
        for (g in all) {
            if (g == "core" || g !in allowed) continue
            val byWord = TRIGGERS[g].orEmpty().any { hit(q, it) } &&
                !(g == "wallet" && publicMarketResearch && !solanaWalletAction)
            val byUse = i.usedTools.any { GROUP_OF[it] == g }
            val byLoad = g in sticky
            val byBuilder = g == "connector_builder" && i.builder
            if (byWord || byUse || byLoad || byBuilder) groups.add(g)
        }
        // Conectores: nome/id/descrição batem com o pedido, já usados, ou carregados.
        val qWords = words(i.query)
        val usedConn = i.usedTools.filter { it.startsWith("uic_") }.map { it.removePrefix("uic_") }
        val conns = i.connectors.filter { (id, nd) ->
            val nameHit = q.contains(" " + norm(id)) || norm(nd.first).split(Regex("\\s+")).filter { it.length >= 3 }.any { q.contains(it) }
            val descHit = words(nd.first + " " + nd.second).intersect(qWords).size >= 2
            nameHit || descHit || usedConn.any { it.startsWith(id + "_") } || "connector:$id" in sticky || id in sticky
        }.keys
        val usedMcp = i.usedTools.filter { it.startsWith("mcp_") }
        val mcp = i.mcpServers.filter { (pfx, name) ->
            q.contains(norm(name)) || usedMcp.any { it.startsWith(pfx) } || "mcp:${pfx.removePrefix("mcp_")}" in sticky || pfx in sticky
        }.keys
        // Orçamento: o histórico consome o contexto; knowledge e memórias dividem a sobra.
        val free = (i.contextBudget - i.historyTokens).coerceAtLeast(0)
        val factor = when {
            free > i.contextBudget * 0.6 -> 1.0
            free > i.contextBudget * 0.3 -> 0.6
            else -> 0.35
        }
        return Plan(
            groups = groups,
            connectors = conns,
            mcp = mcp,
            hiddenGroups = (all - groups).filter { it in allowed },
            hiddenConnectors = (i.connectors.keys - conns).toList(),
            hiddenMcp = (i.mcpServers.keys - mcp).map { it.removePrefix("mcp_") },
            knowledgeChars = (4500 * factor).toInt(),
            memoryChars = (2400 * factor).toInt(),
            skillsShown = if (factor >= 1.0) 20 else 10,
        )
    }

    /**
     * Interpreta os nomes de tools_load: grupo nativo, "connector:<id>" ou "mcp:<servidor>".
     * Devolve (aceitos, desconhecidos).
     */
    fun resolveLoad(names: List<String>, connectorIds: Set<String>, mcpNames: Set<String>): Pair<List<String>, List<String>> {
        val ok = ArrayList<String>(); val bad = ArrayList<String>()
        for (raw in names) {
            val n = raw.trim().lowercase()
            when {
                n in GROUPS -> ok += n
                n.startsWith("connector:") && n.removePrefix("connector:") in connectorIds -> ok += n
                n in connectorIds -> ok += "connector:$n"
                n.startsWith("mcp:") && n.removePrefix("mcp:") in mcpNames -> ok += n
                n in mcpNames -> ok += "mcp:$n"
                else -> bad += raw
            }
        }
        return ok to bad
    }
}
