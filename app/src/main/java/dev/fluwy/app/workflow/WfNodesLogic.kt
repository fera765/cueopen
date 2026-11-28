package dev.fluwy.app.workflow

import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Família Lógica e dados (sem Android, testável na JVM): Definir dados, Condição, Se for novo, Esperar, Separar e Juntar itens.

/** Definir dados: um item novo por item recebido, com os campos "nome = valor". */
internal suspend fun nodeSet(c: WfCall): NodeResult = NodeResult(buildJsonObject {
    c.r("values").lines().map { it.trim() }.filter { it.isNotEmpty() && it.contains('=') }.forEach { line ->
        put(line.substringBefore('=').trim(), line.substringAfter('=').trim())
    }
}.toString())

/** Condição: cada item segue pela porta "true" ou "false" (sem mudar o item). */
internal suspend fun nodeIf(c: WfCall): NodeResult {
    val ok = WfLogic.condition(c.r("left"), c.node.config["op"] ?: "contains", c.r("right"))
    return NodeResult(c.prev, port = if (ok) "true" else "false")
}

/** Se for novo: lembra chaves entre execuções (memória por fluxo+nó). */
internal suspend fun nodeIfNew(c: WfCall): NodeResult {
    val key = c.r("key").trim()
    if (key.isBlank()) return NodeResult(error = "A chave ficou vazia: confira o caminho em {{…}} (o nó anterior não trouxe esse campo).")
    val slot = "${c.w.id}|${c.node.id}"
    val seen = WorkflowStore.seen(slot)
    val isNew = key !in seen
    return when {
        c.run.trigger == "test" -> NodeResult(buildJsonObject { put("key", key); put("new", isNew); put("note", "teste: segue por \"sim\" para testar o resto; nada foi memorizado") }.toString(), port = "true")
        seen.isEmpty() && !WorkflowStore.seenInitialized(slot) && (c.node.config["first"] ?: "skip") == "skip" -> {
            WorkflowStore.remember(slot, key); NodeResult(buildJsonObject { put("key", key); put("new", false); put("note", "primeira execução: memorizado") }.toString(), port = "false")
        }
        else -> {
            if (isNew) WorkflowStore.remember(slot, key)
            NodeResult(buildJsonObject { put("key", key); put("new", isNew) }.toString(), port = if (isNew) "true" else "false")
        }
    }
}

/** Esperar: pausa uma vez e repassa todos os itens. */
internal class WfDelayNode : WfNodeExec("delay", perItem = false) {
    override suspend fun execute(items: List<WfItem>, ctx: WfNodeCtx): WfNodeOutput {
        val s = ctx.call(items.firstOrNull() ?: WfItem.EMPTY, 0).r("seconds").toLongOrNull() ?: 10L
        delay(s.coerceIn(0, 3_600) * 1_000L)
        return WfNodeOutput.main(items)
    }
}

/** Separar em itens: a lista indicada vira um item por elemento (objeto = o item; valor simples = {value: …}). */
internal class WfSplitNode : WfNodeExec("split_out") {
    override suspend fun executeItem(item: WfItem, index: Int, ctx: WfNodeCtx): WfNodeOutput {
        val c = ctx.call(item, index)
        val raw = ctx.cfg("field")
        var v = c.value("field")
        // Formato antigo ({{id.campo}}) chega como texto: tenta ler como JSON.
        if (v is JsonPrimitive && v.isString) runCatching { kotlinx.serialization.json.Json.parseToJsonElement(v.content) }.getOrNull()?.let { v = it }
        if (v is JsonObject && raw.isNotBlank() && !raw.contains("{{")) v = item.json[raw.trim()] ?: v
        val arr = v as? JsonArray ?: return WfNodeOutput.fail("\"${raw.take(80)}\" não resultou numa lista.")
        val limit = (ctx.cfg("limit").toIntOrNull() ?: 50).coerceIn(1, 1_000)
        return WfNodeOutput.main(arr.take(limit).map { e -> (e as? JsonObject)?.let { WfItem(it) } ?: WfItem(JsonObject(mapOf("value" to e))) })
    }
}

/** Juntar itens: todos os itens recebidos num só, em {campo: [...]}, mais `count` e `text` (lista legível). */
internal class WfAggregateNode : WfNodeExec("aggregate", perItem = false) {
    override suspend fun execute(items: List<WfItem>, ctx: WfNodeCtx): WfNodeOutput {
        val field = ctx.cfg("field").trim().ifBlank { "items" }
        return WfNodeOutput.main(listOf(WfItem(JsonObject(mapOf(
            field to JsonArray(items.map { it.json }),
            "count" to JsonPrimitive(items.size),
        )))))
    }
}
