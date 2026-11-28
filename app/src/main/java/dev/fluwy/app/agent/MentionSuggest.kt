package dev.fluwy.app.agent

/**
 * Lógica (sem Android) da caixa de sugestões do "@" no Composer.
 *
 * - "@" sozinho (ou com texto parcial) mostra SÓ duas opções: Conectores e API Hub.
 * - "@CONECTOR:" abre a 2ª caixa com os conectores conectados (filtro pelo texto após os dois-pontos).
 * - "@APIHUB:" abre a 2ª caixa com as Web APIs ativas.
 * O token inserido é `@CONECTOR:<chave>` / `@APIHUB:<slug>`, reconhecido por [Mentions.find].
 */
object MentionSuggest {
    const val CONNECTOR_PREFIX = "CONECTOR:"
    const val APIHUB_PREFIX = "APIHUB:"

    data class Item(val label: String, val description: String, val insert: String, val kind: Mentions.Kind?)

    /** As duas opções iniciais da caixa "@". */
    val roots = listOf(
        Item("Conectores", "@conectores: escolher um conector conectado", "@$CONNECTOR_PREFIX", Mentions.Kind.CONNECTOR),
        Item("API Hub", "@APIHUB: escolher uma Web API", "@$APIHUB_PREFIX", Mentions.Kind.WEBAPI),
    )

    /** Chave estável do conector usada no token (só letras, dígitos, "_" e "-"). */
    fun connectorKey(targetId: String): String = targetId.replace(':', '_').replace(Regex("[^A-Za-z0-9_-]"), "_")

    fun connectorToken(targetId: String) = "@" + CONNECTOR_PREFIX + connectorKey(targetId)

    /**
     * Sugestões para o token atual (a última "palavra" digitada). [connectors] e [webApis] são alvos
     * de [Mentions.Target] já filtrados por tipo; [names] traduz slug de Web API em nome legível.
     */
    fun suggest(token: String, connectors: List<Mentions.Target>, webApis: List<Mentions.Target>, names: (String) -> String = { "" }): List<Item> {
        if (!token.startsWith("@")) return emptyList()
        val body = token.drop(1)
        return when {
            body.startsWith(CONNECTOR_PREFIX, true) -> {
                val q = body.substring(CONNECTOR_PREFIX.length)
                connectors.filter { it.kind == Mentions.Kind.CONNECTOR && it.label.startsWith(CONNECTOR_PREFIX, true) }
                    .filter { q.isBlank() || it.id.contains(q, true) || (it.displayName ?: "").contains(q, true) || it.label.contains(q, true) }
                    .map { Item("@conectores: " + (it.displayName ?: it.label.substringAfter(':')), "Conector", "@" + it.label + " ", Mentions.Kind.CONNECTOR) }
                    .take(30)
            }
            body.startsWith(APIHUB_PREFIX, true) -> {
                val q = body.substring(APIHUB_PREFIX.length)
                webApis.filter { it.kind == Mentions.Kind.WEBAPI && it.id.isNotBlank() }
                    .filter { q.isBlank() || it.id.contains(q, true) || names(it.id).contains(q, true) }
                    .map { Item("@APIHUB:" + it.id, "API Hub · " + names(it.id), "@APIHUB:" + it.id + " ", Mentions.Kind.WEBAPI) }
                    .take(30)
            }
            else -> roots.filter { body.isBlank() || it.label.contains(body, true) || it.insert.drop(1).startsWith(body, true) }
        }
    }
}
