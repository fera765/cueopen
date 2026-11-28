package dev.fluwy.app.workflow

/**
 * Migração do formato salvo dos fluxos. Idempotente: roda ao carregar e ao salvar.
 * - v1 → v2 (itens/n8n): `http.onError = continue` vira a configuração do nó "continuar em caso de erro";
 *   ligações sem porta conhecida voltam para "main". Expressões antigas ({{prev}}, {{id.campo}}) seguem válidas.
 */
object WfMigration {
    const val CURRENT = 2

    fun migrate(w: Workflow): Workflow {
        // Sempre (também em v2): o agente ainda pode mandar http.onError.
        var out = if (w.nodes.none { it.type == "http" && it.config.containsKey("onError") }) w else w.copy(nodes = w.nodes.map { n ->
            if (n.type == "http" && n.config.containsKey("onError")) {
                val cont = n.config["onError"] == "continue"
                n.copy(config = n.config - "onError", settings = if (cont) n.settings.copy(continueOnFail = true) else n.settings)
            } else n
        })
        if (out.version >= CURRENT) return out
        if (out.version < 2) {
            val ports = out.nodes.associate { it.id to (WfCatalog.of(it.type).ports + "main" + "error") }
            out = out.copy(edges = out.edges.map { e -> if (ports[e.from]?.contains(e.port) == false) e.copy(port = "main") else e })
        }
        return out.copy(version = CURRENT)
    }
}
