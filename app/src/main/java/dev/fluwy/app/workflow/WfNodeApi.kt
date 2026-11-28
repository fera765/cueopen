package dev.fluwy.app.workflow

import kotlinx.serialization.json.JsonObject

/** Resultado de um nó: itens por porta de saída ("main", "true"/"false", "error"…). */
class WfNodeOutput(
    val ports: Map<String, List<WfItem>> = emptyMap(),
    val error: String? = null,
    /** Dados que o nó produziu mesmo falhando (ex.: corpo de um HTTP 404), anexados ao item de erro. */
    val errorData: JsonObject? = null,
    val sessionId: String? = null,
) {
    companion object {
        fun main(items: List<WfItem>) = WfNodeOutput(mapOf("main" to items))
        fun fail(msg: String, data: JsonObject? = null, sessionId: String? = null) = WfNodeOutput(error = msg, errorData = data, sessionId = sessionId)
    }
}

/**
 * Tipo de nó executável (como `INodeType` do n8n). O schema de parâmetros fica em [WfCatalog] ([schema]).
 * [perItem] = o executor chama [executeItem] uma vez por item (com retentativa por item); senão chama [execute]
 * uma vez com todos os itens (ex.: Juntar itens).
 */
abstract class WfNodeExec(val type: String, val perItem: Boolean = true) {
    val schema: WfNodeType get() = WfCatalog.of(type)
    open suspend fun execute(items: List<WfItem>, ctx: WfNodeCtx): WfNodeOutput = error("$type roda por item")
    open suspend fun executeItem(item: WfItem, index: Int, ctx: WfNodeCtx): WfNodeOutput = error("$type roda com todos os itens")
}

/** Contexto de um nó durante a execução. */
class WfNodeCtx(
    val w: Workflow,
    val run: WfRun,
    val node: WfNode,
    val expr: WfExprCtx,
    val setStatus: (String) -> Unit = {},
) {
    val isTest: Boolean get() = run.trigger == "test"
    fun cfg(key: String): String = node.config[key] ?: WfCatalog.of(node.type).fields.firstOrNull { it.key == key }?.default.orEmpty()
    fun call(item: WfItem, index: Int) = WfCall(this, item, index)
}

/** Um item sendo processado: preenche os parâmetros com as expressões deste item. */
class WfCall(val ctx: WfNodeCtx, val item: WfItem, val index: Int) {
    val w get() = ctx.w
    val run get() = ctx.run
    val node get() = ctx.node
    /** Texto do item atual (o `{{prev}}` do formato antigo). */
    val prev: String = item.text()
    val expr: WfExprCtx = ctx.expr.forItem(item, index)

    fun r(key: String): String = WfExpr.render(ctx.cfg(key), expr)
    fun rt(t: String, mode: Int): String = WfExpr.render(t, expr, when (mode) { 1 -> { v: String -> WfFormat.oneLine(v) }; 2 -> { v: String -> WfFormat.readable(v) }; else -> null })
    fun rh(key: String, short: Boolean): String = WfExpr.render(ctx.cfg(key), expr) { v -> if (short) WfFormat.oneLine(v) else WfFormat.readable(v) }
    fun value(key: String) = WfExpr.value(ctx.cfg(key), expr)
    fun setStatus(s: String) = ctx.setStatus(s)
}

/** Resultado simples (texto + porta) dos nós que produzem um item por item recebido. */
data class NodeResult(val output: String = "", val port: String? = null, val error: String? = null, val sessionId: String? = null)

/**
 * Nó que transforma cada item num item (texto/JSON) e escolhe a porta. [emptyCheck]: campos em que uma referência vazia é erro
 * antes de executar (IA/agente/aviso/e-mail não recebem instrução sem os dados).
 */
class WfItemNode(type: String, private val emptyCheck: List<String> = emptyList(), private val fn: suspend (WfCall) -> NodeResult) : WfNodeExec(type) {
    override suspend fun executeItem(item: WfItem, index: Int, ctx: WfNodeCtx): WfNodeOutput {
        val c = ctx.call(item, index)
        emptyCheck.flatMap { k -> WfExpr.emptyRefs(ctx.node.config[k].orEmpty(), c.expr) }.firstOrNull()?.let {
            return WfNodeOutput.fail("$it veio vazio: o nó anterior não produziu esse dado. Abra a saída dele e use um caminho que exista (veja \"caminhos\").")
        }
        val res = fn(c)
        if (res.error != null) return WfNodeOutput.fail(res.error, res.output.takeIf { it.isNotBlank() }?.let { WfItems.fromOutput(it).json }, res.sessionId)
        val out = if (res.output === c.prev || res.output == c.prev) item else WfItems.fromOutput(res.output)
        return WfNodeOutput(mapOf((res.port ?: "main") to listOf(out)), sessionId = res.sessionId)
    }
}
