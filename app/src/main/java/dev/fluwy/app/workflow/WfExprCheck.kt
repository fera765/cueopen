package dev.fluwy.app.workflow

/** Validação estática das expressões novas (`{{ $… }}`): sintaxe e nós referenciados, antes de executar. */
object WfExprCheck {
    fun problem(expr: String, w: Workflow, self: WfNode): String? {
        val others = w.nodes.filter { it.id != self.id }
        val ctx = WfExprCtx(nodeItems = others.associate { it.id to listOf(WfItem.EMPTY) }, labels = others.associate { it.id to WfLogic.label(it) },
            triggerNodeId = w.nodes.firstOrNull { WfCatalog.of(it.type).trigger }?.id)
        return try { WfExpr.eval(expr, ctx); null } catch (e: WfExprError) {
            "{{ $expr }} — ${e.message}" + if (e.message?.contains("não existe") == true) " (nomes: ${others.joinToString { WfLogic.label(it) }})" else ""
        } catch (e: Exception) { "{{ $expr }} — expressão inválida" }
    }
}
