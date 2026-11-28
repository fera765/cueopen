package dev.fluwy.app.workflow

/**
 * Registro dos tipos de nó executáveis (tipo → [WfNodeExec]). O schema (campos/portas) vem de [WfCatalog].
 * Para estender: implemente o nó num arquivo da família (WfNodes*.kt), registre aqui e descreva-o em [WfCatalog.TYPES].
 */
object WfNodeRegistry {
    /** Campos em que uma referência vazia é erro antes de executar. */
    private val EMPTY_CHECK = mapOf("ai" to listOf("prompt"), "agent" to listOf("prompt"), "notify" to listOf("text", "title"), "connector" to listOf("campos"))

    private fun item(type: String, fn: suspend (WfCall) -> NodeResult) = WfItemNode(type, EMPTY_CHECK[type].orEmpty(), fn)

    val all: Map<String, WfNodeExec> = listOf(
        item("trigger_manual", ::nodeTrigger), item("trigger_schedule", ::nodeTrigger), item("trigger_webhook", ::nodeTrigger),
        item("trigger_whatsapp", ::nodeTrigger), item("trigger_error", ::nodeTrigger),
        item("ai", ::nodeAi), item("ai_decide", ::nodeAiDecide), item("agent", ::nodeAgent), item("atendente", ::nodeAtendente),
        item("connector", ::nodeConnector), item("mcp", ::nodeMcp), item("webapi", ::nodeWebApi), item("http", ::nodeHttp), item("shell", ::nodeShell),
        item("set", ::nodeSet), item("if", ::nodeIf), item("if_new", ::nodeIfNew), WfDelayNode(), WfSplitNode(), WfAggregateNode(),
        item("approval", ::nodeApproval), item("review", ::nodeReview), item("notify", ::nodeNotify),
    ).associateBy { it.type }

    fun of(type: String): WfNodeExec? = all[type]
}
