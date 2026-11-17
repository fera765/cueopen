package dev.cueopen.app.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Receitas prontas do hub (assets/hub/recipes.json): ações comuns já com piece, ação e formato de entrada conferidos
 * no código das pieces, e os gatilhos de automação mais usados. Entram no prompt SÓ as que batem com o pedido
 * (no máximo 2 ações + gatilhos quando é automação), então o agente executa de primeira, sem pesquisar a piece.
 */
object HubRecipes {
    data class Action(val id: String, val title: String, val piece: String, val action: String, val input: JsonObject, val tip: String, val keywords: List<String>)
    data class Trigger(val id: String, val title: String, val piece: String, val trigger: String, val input: JsonObject)
    data class Catalog(val actions: List<Action>, val triggers: List<Trigger>)

    @Volatile private var cached: Catalog? = null

    fun parse(json: String): Catalog {
        val root = Json.parseToJsonElement(json).jsonObject
        val actions = root["actions"]?.jsonArray.orEmpty().map { it.jsonObject }.map { o ->
            Action(o.str("id"), o.str("title"), o.str("piece"), o.str("action"), o["input"] as? JsonObject ?: JsonObject(emptyMap()),
                o.str("tip"), (o["keywords"] as? JsonArray).orEmpty().map { ToolContextPlanner.norm(it.jsonPrimitive.content) })
        }
        val triggers = root["triggers"]?.jsonArray.orEmpty().map { it.jsonObject }.map { o ->
            Trigger(o.str("id"), o.str("title"), o.str("piece"), o.str("trigger"), o["input"] as? JsonObject ?: JsonObject(emptyMap()))
        }
        return Catalog(actions, triggers)
    }

    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.content.orEmpty()

    fun catalog(load: () -> String): Catalog = cached ?: runCatching { parse(load()) }.getOrDefault(Catalog(emptyList(), emptyList())).also { cached = it }

    /** Linhas de prompt para o pedido normalizado [qNorm]. */
    fun promptLines(cat: Catalog, qNorm: String, automation: Boolean, hubPrefix: String): List<String> {
        val scored = cat.actions.map { a -> a to a.keywords.count { k -> qNorm.contains(k) } }.filter { it.second >= 1 }
            .sortedByDescending { it.second }.take(2).map { it.first }
        val out = ArrayList<String>()
        scored.forEach { a ->
            out += "6a. Receita pronta «${a.title}»: ${hubPrefix}__ap_run_action {\"pieceName\":\"${a.piece}\",\"actionName\":\"${a.action}\"," +
                "\"input\":${a.input},\"connectionExternalId\":\"<da conta conectada>\"}. ${a.tip}"
        }
        if (automation && cat.triggers.isNotEmpty()) {
            out += "6a. Automação: crie UM fluxo com ${hubPrefix}__ap_build_flow (nome \"${HubGuard.FLOW_PREFIX}<objetivo>\"; passos PIECE, evite CODE), " +
                "antes confira com ${hubPrefix}__ap_list_flows se já existe um igual para editar em vez de criar; depois publique com ap_lock_and_publish. " +
                "Gatilhos prontos: " + cat.triggers.joinToString("; ") { "${it.title} = ${it.piece}/${it.trigger} ${it.input}" } + "."
        }
        return out
    }
}
