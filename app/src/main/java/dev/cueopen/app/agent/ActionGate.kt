package dev.cueopen.app.agent

import java.text.Normalizer

/**
 * Portão de confirmação por Agent (inspirado no `approvals/policy.ts` do OpenBot).
 *
 * Classifica a ação ANTES de executar pelo verbo do controle/ferramenta (o rótulo do botão, o nome
 * da ferramenta do conector), não pelo que o modelo diz que vai fazer. Se a categoria estiver marcada
 * no Agent (modo "ask"), a ação vira um card de confirmação e só roda depois do "Aprovar".
 * Modo "auto" = 100% autônomo: nada é bloqueado aqui.
 *
 * A Carteira Solana (wallet_*, trade_*) segue as regras próprias da aba Carteira do Agent, e
 * ligações/eventos já abrem a tela do Android para o usuário confirmar — não passam por aqui.
 */
object ActionGate {
    enum class Category(val id: String, val label: String, val question: String) {
        PURCHASE("purchase", "Comprar ou pagar", "Confirmar compra/pagamento?"),
        TRANSFER("transfer", "Transferir dinheiro ou negociar", "Confirmar transferência/negociação?"),
        DELETE("delete", "Apagar ou excluir", "Confirmar exclusão?"),
        SEND("send", "Enviar, publicar ou comentar", "Confirmar envio/publicação?"),
        ACCOUNT("account", "Senha, segurança ou conta", "Confirmar alteração na conta?");

        companion object {
            fun of(id: String?): Category? = entries.firstOrNull { it.id == id }
        }
    }

    val DEFAULT_CATEGORIES: List<String> = Category.entries.map { it.id }

    /** Aprovação/negação valem por este tempo; depois o agente precisa pedir de novo. */
    const val DECISION_TTL_MS = 30 * 60_000L

    fun requiresConfirmation(mode: String?, categories: List<String>?, category: Category): Boolean =
        mode != "auto" && category.id in (categories ?: DEFAULT_CATEGORIES)

    private fun norm(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    // Ordem importa: o mais específico primeiro ("enviar pix" é transferência, não "envio").
    private val textRules: List<Pair<Category, Regex>> = listOf(
        Category.ACCOUNT to Regex("\\b(alterar|mudar|trocar|redefinir|change|reset|update)\\b.{0,12}\\b(senha|password)\\b|\\b(excluir|apagar|deletar|delete|encerrar|desativar|deactivate|close)\\b.{0,8}\\b(minha |my )?(conta|account)\\b"),
        Category.TRANSFER to Regex("\\b(transferir|transfira|transferencia|sacar|saque|withdraw|transfer|apostar|aposta|place bet|bet|vender|venda agora|sell|enviar pix|fazer pix|enviar dinheiro|send money|swap)\\b"),
        Category.PURCHASE to Regex("\\b(comprar|compre|compra agora|finalizar (a )?compra|finalizar pedido|fechar pedido|concluir (a )?compra|confirmar (a |o )?(compra|pedido|pagamento)|pagar|pague|efetuar pagamento|checkout|buy|buy now|purchase|place (your )?order|pay|pay now|assinar|subscribe|contratar|gerar pix|gerar boleto)\\b"),
        Category.DELETE to Regex("\\b(excluir|exclua|apagar|apague|remover|remova|deletar|delete|remove|trash|lixeira|descartar|discard|unfollow|deixar de seguir)\\b"),
        Category.SEND to Regex("\\b(enviar|envie|publicar|publique|postar|poste|compartilhar|compartilhe|send|post|publish|share|tweet|retweet|responder|reply|comentar|comment|mandar|repost)\\b"),
    )

    /** Classifica um rótulo de botão/controle; só olha o começo (o verbo vem primeiro). */
    fun classifyText(text: String?): Category? {
        if (text.isNullOrBlank()) return null
        val t = norm(text).replace(Regex("\\s+"), " ").trim().take(80)
        return textRules.firstOrNull { (_, re) -> re.containsMatchIn(t) }?.first
    }

    private val nameTokens: List<Pair<Category, Set<String>>> = listOf(
        Category.ACCOUNT to setOf("password", "senha"),
        Category.TRANSFER to setOf("transfer", "withdraw", "swap", "trade", "bet", "sell", "sacar", "transferir", "apostar", "vender", "option"),
        Category.PURCHASE to setOf("buy", "purchase", "pay", "payment", "checkout", "order", "comprar", "pagar", "pedido", "subscribe"),
        Category.DELETE to setOf("delete", "remove", "trash", "destroy", "excluir", "apagar", "remover", "unfollow"),
        Category.SEND to setOf("send", "post", "publish", "reply", "comment", "share", "tweet", "message", "email", "enviar", "publicar", "postar", "comentar", "responder"),
    )

    /** Leituras óbvias nunca pedem confirmação, mesmo com verbo no nome (ex.: list_orders, get_trade_result). */
    private val readOnlyTokens = setOf("get", "list", "read", "search", "find", "fetch", "status", "result", "results", "history", "query", "view", "show", "check", "info", "balance", "quote", "price", "prices", "draft", "preview")

    fun classifyToolName(name: String): Category? {
        val bare = name.removePrefix("uic_").removePrefix("mcp_")
        val tokens = bare.split(Regex("[_\\-.]+|(?<=[a-z])(?=[A-Z])")).map { norm(it) }.filter { it.isNotEmpty() }.toSet()
        if (tokens.any { it in readOnlyTokens }) return null
        return nameTokens.firstOrNull { (_, words) -> tokens.any { it in words } }?.first
    }

    private val shellDelete = Regex("\\brm\\s[^|;&]*(/sdcard|/storage/|/mnt/|~/storage)|curl\\b[^|;]*-X\\s*DELETE")

    /**
     * @param label rótulo do elemento clicado/submetido (browser/acessibilidade), código JS ou comando.
     * @return categoria sensível ou null quando a ação é rotineira.
     */
    fun classify(tool: String, label: String?): Category? = when {
        tool == "device_notification_reply" -> Category.SEND
        tool == "browser_click" || tool == "browser_type" || tool == "device_accessibility_click" || tool == "device_accessibility_tap" -> classifyText(label)
        tool == "browser_js" -> label?.takeIf { Regex("click\\(|submit\\(|requestSubmit|dispatchEvent").containsMatchIn(it) }
            ?.let { code -> Regex("[\"'`]([^\"'`]{2,60})[\"'`]").findAll(code).mapNotNull { classifyText(it.groupValues[1]) }.firstOrNull() }
        tool == "shell" -> if (label != null && shellDelete.containsMatchIn(label)) Category.DELETE else null
        tool.startsWith("uic_") || tool.startsWith("mcp_") -> classifyToolName(tool)
        else -> null
    }

    /** Frase curta para o card: o que exatamente será feito e onde. */
    fun summary(category: Category, tool: String, label: String?, host: String?): String {
        val where = host?.takeIf { it.isNotBlank() }?.let { " em $it" }.orEmpty()
        val what = label?.replace(Regex("\\s+"), " ")?.trim()?.take(90)?.takeIf { it.isNotEmpty() }
        return when {
            tool == "device_notification_reply" -> "Responder$where: “${what ?: ""}”"
            tool == "browser_click" || tool == "device_accessibility_click" || tool == "device_accessibility_tap" -> "Tocar em “${what ?: "botão"}”$where"
            tool == "browser_type" -> "Enviar o formulário “${what ?: "campo"}”$where"
            tool == "browser_js" -> "Executar script na página$where que aciona “${category.label.lowercase()}”"
            tool == "shell" -> "Rodar comando que apaga arquivos: ${what ?: ""}"
            else -> "Usar ${tool.removePrefix("uic_").removePrefix("mcp_").replace("__", " · ").replace('_', ' ')}"
        }
    }

    /**
     * Chave da aprovação. Para cliques usa ferramenta+site+rótulo (o índice muda quando a página é relida);
     * para o resto, ferramenta+argumentos normalizados.
     */
    fun approvalKey(tool: String, normArgs: String, label: String?, host: String?): String =
        if ((tool == "browser_click" || tool == "device_accessibility_click") && !label.isNullOrBlank())
            "$tool|${host.orEmpty()}|${norm(label).trim().take(80)}"
        else "$tool|$normArgs"
}
