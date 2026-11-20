package dev.cueopen.app.workflow

import kotlinx.serialization.Serializable

/** Fluxo estilo n8n: nós ligados por arestas, disparados manualmente ou por horário. */
@Serializable
data class Workflow(
    val id: String = WfIds.new(),
    val name: String = "Novo fluxo",
    val enabled: Boolean = false,
    val nodes: List<WfNode> = emptyList(),
    val edges: List<WfEdge> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Serializable
data class WfNode(
    val id: String = WfIds.new(),
    val type: String,
    val label: String = "",
    val x: Float = 0f,
    val y: Float = 0f,
    val config: Map<String, String> = emptyMap(),
)

/** Aresta da saída [port] de [from] para a entrada de [to]. Porta "main" (padrão), "true"/"false" (Condição). */
@Serializable
data class WfEdge(val id: String = WfIds.new(), val from: String, val to: String, val port: String = "main")

@Serializable
data class WfRun(
    val id: String = WfIds.new(),
    val workflowId: String,
    val workflowName: String = "",
    val trigger: String = "manual",
    val startedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
    /** running · ok · error · waiting (aprovação) · cancelled */
    val status: String = "running",
    val steps: List<WfStep> = emptyList(),
    val error: String = "",
)

@Serializable
data class WfStep(
    val nodeId: String,
    val label: String,
    val type: String,
    val status: String,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val output: String = "",
    val error: String = "",
    /** Chat criado pelo nó (Agente IA), para abrir a conversa completa. */
    val sessionId: String? = null,
)

object WfIds {
    fun new(): String = java.util.UUID.randomUUID().toString().replace("-", "").take(12)
}

/** Campo de configuração de um tipo de nó (o editor monta o formulário a partir disto). */
data class WfField(
    val key: String,
    val label: String,
    val kind: Kind = Kind.TEXT,
    val hint: String = "",
    val options: List<Pair<String, String>> = emptyList(),
    val default: String = "",
    val required: Boolean = false,
) {
    enum class Kind { TEXT, MULTILINE, NUMBER, SELECT, TIME, JSON }
}

/** Tipo de nó do catálogo. [ports] = saídas ("main" ou "true"/"false"). */
data class WfNodeType(
    val type: String,
    val title: String,
    val category: String,
    val description: String,
    val color: Long,
    val icon: String,
    val fields: List<WfField> = emptyList(),
    val ports: List<String> = listOf("main"),
    val trigger: Boolean = false,
)

object WfCatalog {
    const val CAT_TRIGGER = "Gatilhos"
    const val CAT_AI = "IA e agentes"
    const val CAT_APPS = "Apps e integrações"
    const val CAT_LOGIC = "Lógica e dados"
    const val CAT_OUT = "Saídas"

    val TEMPLATE_HINT = "Use {{prev}} (saída do nó anterior), {{<id do nó>}} ou {{<id do nó>.campo}}, {{trigger.time}}."

    val TYPES: List<WfNodeType> = listOf(
        WfNodeType("trigger_manual", "Manual", CAT_TRIGGER, "Roda quando você toca em Executar.", 0xFF8A8F98, "play", trigger = true),
        WfNodeType("trigger_schedule", "Agendamento", CAT_TRIGGER, "Roda sozinho por intervalo, todo dia ou toda semana.", 0xFFFFA62B, "schedule", trigger = true,
            fields = listOf(
                WfField("mode", "Repetir", WfField.Kind.SELECT, options = listOf("interval" to "A cada X minutos", "daily" to "Todo dia", "weekly" to "Toda semana"), default = "daily"),
                WfField("minutes", "A cada (minutos)", WfField.Kind.NUMBER, "Mínimo 1", default = "60"),
                WfField("time", "Horário (HH:mm)", WfField.Kind.TIME, default = "09:00"),
                WfField("weekday", "Dia da semana", WfField.Kind.SELECT, options = listOf("1" to "Segunda", "2" to "Terça", "3" to "Quarta", "4" to "Quinta", "5" to "Sexta", "6" to "Sábado", "7" to "Domingo"), default = "1"),
            )),
        WfNodeType("agent", "Agente IA", CAT_AI, "Um agente do app executa a instrução (com ferramentas, browser, terminal e conectores) e devolve a resposta.", 0xFF7C5CFF, "agent",
            fields = listOf(
                WfField("prompt", "Instrução", WfField.Kind.MULTILINE, TEMPLATE_HINT, required = true),
                WfField("agent", "Agente (nome; vazio = padrão)", WfField.Kind.TEXT),
                WfField("timeout", "Tempo máximo (minutos)", WfField.Kind.NUMBER, default = "15"),
            )),
        WfNodeType("connector", "Conector (apps)", CAT_APPS, "Executa uma ação de um app conectado (Gmail, Sheets, Slack, Notion, CRM…) pelo hub de conectores.", 0xFF2FBF71, "apps",
            fields = listOf(
                WfField("conector", "Conector", WfField.Kind.TEXT, "Nome do app conectado (ex.: gmail)", required = true),
                WfField("acao", "Ação", WfField.Kind.TEXT, "Nome da ação (veja em Ver ações)", required = true),
                WfField("campos", "Campos (JSON)", WfField.Kind.JSON, "Ex.: {\"to\":\"{{prev}}\"}. $TEMPLATE_HINT", default = "{}"),
                WfField("conexao", "Conexão (opcional)", WfField.Kind.TEXT),
            )),
        WfNodeType("mcp", "Ferramenta MCP", CAT_APPS, "Chama uma ferramenta de um conector MCP configurado.", 0xFF2EC4C4, "mcp",
            fields = listOf(
                WfField("tool", "Ferramenta", WfField.Kind.TEXT, "mcp_<conector>__<ferramenta>", required = true),
                WfField("args", "Argumentos (JSON)", WfField.Kind.JSON, TEMPLATE_HINT, default = "{}"),
            )),
        WfNodeType("http", "Requisição HTTP", CAT_APPS, "Chama qualquer API (GET, POST, PUT, PATCH, DELETE).", 0xFF4D8DFF, "http",
            fields = listOf(
                WfField("method", "Método", WfField.Kind.SELECT, options = listOf("GET" to "GET", "POST" to "POST", "PUT" to "PUT", "PATCH" to "PATCH", "DELETE" to "DELETE"), default = "GET"),
                WfField("url", "URL", WfField.Kind.TEXT, TEMPLATE_HINT, required = true),
                WfField("headers", "Cabeçalhos (um por linha: Nome: valor)", WfField.Kind.MULTILINE),
                WfField("body", "Corpo", WfField.Kind.MULTILINE, TEMPLATE_HINT),
                WfField("timeout", "Tempo máximo (segundos)", WfField.Kind.NUMBER, default = "30"),
            )),
        WfNodeType("shell", "Terminal", CAT_APPS, "Roda um comando no Linux do app e devolve a saída.", 0xFF9C978E, "terminal",
            fields = listOf(
                WfField("command", "Comando", WfField.Kind.MULTILINE, TEMPLATE_HINT, required = true),
                WfField("timeout", "Tempo máximo (segundos)", WfField.Kind.NUMBER, default = "120"),
            )),
        WfNodeType("set", "Definir dados", CAT_LOGIC, "Monta campos novos a partir das saídas anteriores.", 0xFFB57BFF, "set",
            fields = listOf(WfField("values", "Campos (um por linha: nome = valor)", WfField.Kind.MULTILINE, TEMPLATE_HINT, required = true))),
        WfNodeType("if", "Condição (IF)", CAT_LOGIC, "Segue pelo caminho Verdadeiro ou Falso.", 0xFFFF5C8A, "branch",
            fields = listOf(
                WfField("left", "Valor", WfField.Kind.TEXT, TEMPLATE_HINT, default = "{{prev}}"),
                WfField("op", "Operador", WfField.Kind.SELECT, options = listOf(
                    "contains" to "contém", "not_contains" to "não contém", "equals" to "igual a", "not_equals" to "diferente de",
                    "gt" to "maior que", "lt" to "menor que", "empty" to "está vazio", "not_empty" to "não está vazio", "regex" to "casa com regex"), default = "contains"),
                WfField("right", "Comparar com", WfField.Kind.TEXT),
            ),
            ports = listOf("true", "false")),
        WfNodeType("delay", "Esperar", CAT_LOGIC, "Pausa o fluxo por um tempo.", 0xFF56CCF2, "timer",
            fields = listOf(WfField("seconds", "Segundos", WfField.Kind.NUMBER, default = "10"))),
        WfNodeType("approval", "Aprovação", CAT_LOGIC, "Pausa até você aprovar ou recusar (notificação + tela de execuções).", 0xFFFFC53D, "approval",
            fields = listOf(
                WfField("question", "Pergunta", WfField.Kind.MULTILINE, TEMPLATE_HINT, default = "Posso continuar?"),
                WfField("hours", "Esperar até (horas)", WfField.Kind.NUMBER, default = "24"),
            ),
            ports = listOf("true", "false")),
        WfNodeType("notify", "Notificação", CAT_OUT, "Mostra uma notificação no celular.", 0xFFFF6B3D, "notify",
            fields = listOf(
                WfField("title", "Título", WfField.Kind.TEXT, default = "Fluxo"),
                WfField("text", "Texto", WfField.Kind.MULTILINE, TEMPLATE_HINT, default = "{{prev}}"),
            )),
    )

    fun of(type: String): WfNodeType = TYPES.firstOrNull { it.type == type } ?: TYPES.first()
    fun categories(): List<String> = listOf(CAT_TRIGGER, CAT_AI, CAT_APPS, CAT_LOGIC, CAT_OUT)
}
