package dev.fluwy.app.agent

/**
 * Catálogo (sem Android) dos comandos "/" da caixa de mensagem. Os handlers ficam em ChatScreen;
 * cada [Cmd.name] aqui precisa ter um ramo lá (há teste garantindo nomes únicos e grupos válidos).
 */
object SlashCommands {
    enum class Group(val title: String) { CONVERSA("Conversa"), CONTEXTO("Contexto"), AGENTES("Skills e agentes"), ABRIR("Abrir") }

    /** [arg]: texto de exemplo do argumento; comando com argumento é inserido com espaço no fim. */
    data class Cmd(val name: String, val description: String, val group: Group, val arg: String? = null)

    val all = listOf(
        Cmd("/new", "Começa um novo chat", Group.CONVERSA),
        Cmd("/stop", "Para o agente", Group.CONVERSA),
        Cmd("/undo", "Desfaz a última alteração de arquivo do agente", Group.CONVERSA),
        Cmd("/compact", "Compacta o contexto agora", Group.CONTEXTO),
        Cmd("/autocompact", "Liga/desliga a compactação automática", Group.CONTEXTO),
        Cmd("/clear", "Reinicia a sessão do zero (contexto e workspace)", Group.CONTEXTO),
        Cmd("/usage", "Uso de tokens e contexto", Group.CONTEXTO),
        Cmd("/skill", "Usar uma skill nesta mensagem", Group.AGENTES, "<skill>"),
        Cmd("/agent", "Passar a tarefa para um agent", Group.AGENTES, "<agent>"),
        Cmd("/create-agent", "Criar um agent a partir de uma descrição", Group.AGENTES, "<descrição>"),
        Cmd("/skills", "Abre as skills", Group.ABRIR),
        Cmd("/agents", "Abre os agents", Group.ABRIR),
        Cmd("/connectors", "Abre os conectores", Group.ABRIR),
        Cmd("/memory", "Abre a memória de longo prazo", Group.ABRIR),
        Cmd("/help", "Lista os comandos", Group.ABRIR),
    )

    fun find(name: String): Cmd? = all.firstOrNull { it.name.equals(name, true) }

    /** Filtro ao digitar: prefixo primeiro, depois "contém" no nome ou na descrição; ordem por grupo. */
    fun filter(typed: String): List<Cmd> {
        if (!typed.startsWith("/") || typed.contains(' ')) return emptyList()
        val q = typed.drop(1)
        if (q.isEmpty()) return all.sortedBy { it.group.ordinal }
        val prefix = all.filter { it.name.drop(1).startsWith(q, true) }
        val contains = all.filter { it !in prefix && (it.name.contains(q, true) || it.description.contains(q, true)) }
        return prefix.sortedBy { it.group.ordinal } + contains.sortedBy { it.group.ordinal }
    }

    /** Texto inserido ao escolher o comando. */
    fun insertText(c: Cmd) = c.name + " "

    /** "/skill gm" → ("/skill", "gm"); null se não for um comando com argumento de escolha. */
    fun pickerQuery(text: String): Pair<String, String>? {
        for (cmd in listOf("/skill", "/agent")) {
            if (text.startsWith("$cmd ", true)) {
                val rest = text.substring(cmd.length + 1)
                if (!rest.contains('@') && !rest.contains('\n') && rest.count { it == ' ' } <= 2) return cmd to rest
            }
        }
        return null
    }

    /**
     * "/skill Nome resto" ou "/agent Nome resto" → "@Nome resto", casando o nome mais longo de [names]
     * (sem diferenciar maiúsculas). Devolve null se não for esse comando ou se nenhum nome casar.
     */
    fun resolveArgCommand(text: String, skills: List<String>, agents: List<String>): String? {
        val t = text.trim()
        val (cmd, names) = when {
            t.startsWith("/skill ", true) -> "/skill" to skills
            t.startsWith("/agent ", true) -> "/agent" to agents
            else -> return null
        }
        val rest = t.substring(cmd.length).trimStart().removePrefix("@")
        val name = names.filter { it.isNotBlank() && rest.startsWith(it, true) && (rest.length == it.length || !rest[it.length].isLetterOrDigit()) }
            .maxByOrNull { it.length } ?: return null
        return ("@" + name + " " + rest.substring(name.length).trim()).trimEnd()
    }

    /** Ajuda em Markdown, agrupada. */
    fun helpMarkdown(): String = buildString {
        append("**Comandos**\n")
        Group.entries.forEach { g ->
            append("\n_").append(g.title).append("_\n")
            all.filter { it.group == g }.forEach { append("- `").append(it.name).append(it.arg?.let { a -> " $a" } ?: "").append("` — ").append(it.description).append('\n') }
        }
        append("\nUse `@` para marcar um conector (`@conectores`) ou uma Web API (`@APIHUB`).")
    }
}
