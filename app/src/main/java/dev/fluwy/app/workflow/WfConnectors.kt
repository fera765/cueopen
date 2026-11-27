package dev.fluwy.app.workflow

import dev.fluwy.app.FluwyApp
import dev.fluwy.app.connectors.ActivepiecesHub
import dev.fluwy.app.connectors.Connectors
import dev.fluwy.app.connectors.HubApi
import dev.fluwy.app.connectors.HubConnectors
import dev.fluwy.app.data.Store
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*

/**
 * Nó Conector unificado: apps do hub (Gmail, Sheets, Slack…), servidores MCP e conectores do app (inclusive os
 * criados por IA) atrás da mesma interface — escolher conector, escolher ação, preencher campos e receber
 * sempre a mesma resposta: {status, connector, action, data, text?}.
 */
object WfConnectors {
    const val HUB = "hub"
    const val MCP = "mcp"
    const val APP = "app"

    data class Source(val kind: String, val id: String, val name: String, val detail: String, val connection: String = "", val ready: Boolean = true)
    data class Action(val name: String, val title: String, val description: String)
    data class Field(val key: String, val label: String, val type: String, val required: Boolean, val description: String = "",
                     val options: List<Pair<String, String>> = emptyList(), val default: String = "", val dynamic: Boolean = false)

    fun kindLabel(kind: String) = when (kind) { HUB -> "Apps conectados"; MCP -> "Servidores MCP"; else -> "Conectores do app" }

    /** Formato da resposta do nó (igual para os três tipos), para o editor e para o agente. */
    fun responseShape(kind: String, id: String = "<id>"): String = when (kind) {
        HUB -> """{
  "status": "ok",
  "connector": "Gmail",
  "action": "send_email",
  "duration_ms": 812,
  "summary": "resumo curto do resultado",
  "data": { … resposta do app … }
}
Use {{$id.data.<campo>}} · {{$id.summary}} · {{$id}} (tudo)"""
        else -> """{
  "status": "ok",
  "connector": "nome",
  "action": "ferramenta",
  "data": { … JSON devolvido, ou o texto … },
  "text": "texto bruto devolvido"
}
Use {{$id.data.<campo>}} · {{$id.text}} · {{$id}} (tudo)"""
    }

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun mcpServers(store: Store) = store.state.value.connectors.filter {
        it.enabled && it.connectedAt != null && it.toolsJson != null && it.gatewayRef != ActivepiecesHub.REF
    }

    private fun mcpTools(store: Store, id: String): List<JsonObject> {
        val c = mcpServers(store).firstOrNull { it.id == id || it.name.equals(id, true) } ?: return emptyList()
        return runCatching { Json.parseToJsonElement(c.toolsJson ?: "[]").jsonArray.mapNotNull { it as? JsonObject } }.getOrDefault(emptyList())
    }

    private fun uic() = runCatching { FluwyApp.instance.uiConnectors }.getOrNull()

    /** Todos os conectores que um nó pode usar agora. */
    suspend fun sources(store: Store): List<Source> {
        val out = mutableListOf<Source>()
        if (HubConnectors.available(store)) runCatching { HubConnectors.connections(store) }.getOrDefault(emptyList()).forEach { c ->
            out += Source(HUB, c.pieceName, HubConnectors.appName(c.pieceName), c.displayName, c.externalId, c.status == "ACTIVE")
        }
        mcpServers(store).forEach { c -> out += Source(MCP, c.id, c.name, "${mcpTools(store, c.id).size} ferramentas") }
        uic()?.let { hub ->
            hub.connectors.filter { hub.enabled(it.spec.id) && it !is dev.fluwy.app.uiconnector.RequiresAgentTerminal && it.spec.tools.any { t -> !t.opensOrder } }
                .forEach { c -> out += Source(APP, c.spec.id, c.spec.name, c.spec.description.take(60)) }
        }
        return out
    }

    suspend fun actions(store: Store, kind: String, id: String): List<Action> = when (kind) {
        HUB -> {
            val piece = HubApi.pieceName(id)
            HubApi.catalogPiece(piece)?.actions?.map { Action(it.name, it.displayName, it.description) }
                ?: (HubApi.piece(piece)["actions"] as? JsonObject).orEmpty().values.mapNotNull { it as? JsonObject }
                    .map { Action(it.s("name"), it.s("displayName"), it.s("description")) }
        }
        MCP -> mcpTools(store, id).map { Action(it.s("name"), it.s("name"), it.s("description")) }
        else -> uic()?.get(id)?.spec?.tools?.filter { !it.opensOrder }?.map { Action(it.name, it.name, it.description) }.orEmpty()
    }

    /** Campos da ação, com opções fixas e (no hub) até 4 listas dinâmicas já resolvidas. */
    suspend fun fields(store: Store, kind: String, id: String, action: String, conexao: String = ""): List<Field> = when (kind) {
        HUB -> {
            val piece = HubApi.pieceName(id)
            val acts = (HubApi.piece(piece)["actions"] as? JsonObject).orEmpty()
            val a = (acts[action] ?: acts.values.firstOrNull { (it as? JsonObject)?.s("displayName").equals(action, true) }) as? JsonObject
                ?: throw IllegalArgumentException("a ação \"$action\" não existe em $piece")
            var resolved = 0
            (a["props"] as? JsonObject).orEmpty().mapNotNull { (k, v) ->
                val o = v as? JsonObject ?: return@mapNotNull null
                val type = o.s("type")
                if (type == "MARKDOWN" || type == "CUSTOM_AUTH" || type == "OAUTH2" || type == "SECRET_TEXT" && k == "auth") return@mapNotNull null
                var opts = ((o["options"] as? JsonObject)?.get("options") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                    .map { op -> op.s("label").ifBlank { op.s("value") } to ((op["value"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: op["value"].toString()) }
                val dyn = o.s("optionsDynamic") == "true" || type == "DROPDOWN" && opts.isEmpty()
                val refreshers = (o["refreshers"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.filter { it != "auth" }
                if (dyn && refreshers.isEmpty() && resolved < 4) {
                    resolved++
                    opts = withTimeoutOrNull(20_000) { runCatching { HubConnectors.optionList(piece, a.s("name").ifBlank { action }, k, conexao, JsonObject(emptyMap())) }.getOrNull() }.orEmpty()
                }
                Field(k, o.s("displayName").ifBlank { k }, type.lowercase(), o.s("required") == "true", o.s("description").take(200), opts,
                    o["defaultValue"]?.takeIf { it !is JsonNull }?.let { (it as? JsonPrimitive)?.contentOrNull ?: it.toString() }.orEmpty(), dyn)
            }
        }
        MCP -> {
            val t = mcpTools(store, id).firstOrNull { it.s("name") == action } ?: throw IllegalArgumentException("a ferramenta \"$action\" não existe")
            val schema = t["inputSchema"] as? JsonObject
            val req = (schema?.get("required") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
            (schema?.get("properties") as? JsonObject).orEmpty().map { (k, v) ->
                val o = v as? JsonObject ?: JsonObject(emptyMap())
                Field(k, o.s("title").ifBlank { k }, o.s("type").ifBlank { "string" }, k in req, o.s("description").take(200),
                    (o["enum"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.let { e -> e to e } },
                    o["default"]?.let { (it as? JsonPrimitive)?.contentOrNull ?: it.toString() }.orEmpty())
            }
        }
        else -> {
            val t = uic()?.get(id)?.spec?.tools?.firstOrNull { it.name == action } ?: throw IllegalArgumentException("a ação \"$action\" não existe")
            t.params.map { p -> Field(p.name, p.name, p.type, p.required, p.description, p.allowed.map { it to it }) }
        }
    }

    /** Converte o valor de um campo para o tipo certo (números, booleanos, listas e objetos). */
    private fun typed(type: String, v: String): JsonElement {
        val t = v.trim()
        return when (type.lowercase()) {
            "number", "integer" -> t.replace(',', '.').toDoubleOrNull()?.let { d -> if (d % 1.0 == 0.0 && kotlin.math.abs(d) < 1e15) JsonPrimitive(d.toLong()) else JsonPrimitive(d) } ?: JsonPrimitive(v)
            "checkbox", "boolean" -> JsonPrimitive(t.equals("true", true) || t == "1" || t.equals("sim", true))
            "array", "object", "json", "multi_select_dropdown", "static_multi_select_dropdown" ->
                runCatching { Json.parseToJsonElement(t) }.getOrElse { if (type.contains("array") || type.contains("multi")) JsonArray(t.split(',').map { JsonPrimitive(it.trim()) }.filter { it.content.isNotEmpty() }) else JsonPrimitive(v) }
            else -> JsonPrimitive(v)
        }
    }

    /** Ajusta os tipos dos campos já preenchidos (com {{…}} resolvidos) segundo o esquema da ação. */
    fun coerce(input: JsonObject, schema: List<Field>): JsonObject = buildJsonObject {
        input.forEach { (k, v) ->
            val f = schema.firstOrNull { it.key == k }
            val s = (v as? JsonPrimitive)?.takeIf { it.isString }?.content
            put(k, if (f != null && s != null) typed(f.type, s) else v)
        }
    }

    data class Out(val output: String, val error: String?)

    private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }

    private fun envelope(kind: String, name: String, action: String, raw: String): String {
        val parsed = runCatching { Json.parseToJsonElement(raw.trim()) }.getOrNull()?.takeIf { it is JsonObject || it is JsonArray }
        return pretty.encodeToString(JsonObject.serializer(), buildJsonObject {
            put("status", "ok"); put("connector", name); put("action", action)
            put("data", parsed ?: JsonPrimitive(raw.take(30_000))); put("text", raw.take(30_000))
        })
    }

    private val FAIL = Regex("^\\s*(ERRO|ERROR|RECUSADO|Falha|Ferramenta inválida|Conector desconhecido)", RegexOption.IGNORE_CASE)

    suspend fun run(store: Store, kind: String, id: String, action: String, campos: JsonObject, conexao: String): Out {
        if (id.isBlank() || action.isBlank()) return Out("", "Escolha o conector e a ação no nó.")
        val schema = runCatching { fields(store, kind, id, action, conexao) }.getOrDefault(emptyList())
        val input = coerce(campos, schema)
        val missing = schema.filter { it.required && it.default.isBlank() && (input[it.key] == null || (input[it.key] as? JsonPrimitive)?.contentOrNull?.isBlank() == true) }
        if (missing.isNotEmpty()) return Out("", "Campos obrigatórios vazios: ${missing.joinToString { it.label }}.")
        return when (kind) {
            HUB -> {
                if (!HubConnectors.available(store)) return Out("", "O hub de conectores não está ligado (Conectores › Apps).")
                val out = HubConnectors.run(store, id, action, input, conexao, full = true)
                if (FAIL.containsMatchIn(out)) Out("", out.take(1_500)) else Out(out, null)
            }
            MCP -> {
                val server = mcpServers(store).firstOrNull { it.id == id || it.name.equals(id, true) } ?: return Out("", "O servidor MCP \"$id\" não está ativo (Conectores).")
                val out = Connectors.callTool(store, Connectors.toolFunctionName(server.id, action), input)
                if (FAIL.containsMatchIn(out)) Out("", out.take(1_500)) else Out(envelope(kind, server.name, action, out), null)
            }
            else -> {
                val hub = uic() ?: return Out("", "Conectores do app indisponíveis.")
                val out = hub.callForWorkflow(id, action, input)
                if (FAIL.containsMatchIn(out)) Out("", out.take(1_500)) else Out(envelope(kind, hub.get(id)?.spec?.name ?: id, action, out), null)
            }
        }
    }

    /** Checagem do nó antes de testar/publicar: conector ativo, ação existente, obrigatórios preenchidos. */
    suspend fun validate(store: Store, n: WfNode): List<String> {
        if (n.type == "agent") {
            val a = n.config["agent"].orEmpty().trim()
            val agents = store.state.value.agents
            return if (a.isNotBlank() && agents.none { it.name.equals(a, true) })
                listOf("${WfLogic.label(n)}: o agente \"$a\" não existe (agentes: ${agents.joinToString { it.name }.ifBlank { "nenhum" }}). Deixe vazio para o padrão — ou use um nó \"ai\" para só formatar/resumir.")
            else emptyList()
        }
        if (n.type != "connector") return emptyList()
        val kind = n.config["source"].orEmpty().ifBlank { HUB }
        val id = n.config["conector"].orEmpty(); val action = n.config["acao"].orEmpty()
        val name = WfLogic.label(n)
        if (id.isBlank() || action.isBlank()) return listOf("$name: escolha o conector e a ação.")
        val src = sources(store).filter { it.kind == kind }
        if (src.none { it.id.equals(id, true) || it.id == HubApi.pieceName(id) && kind == HUB || it.name.equals(id, true) })
            return listOf("$name: o conector \"$id\" (${kindLabel(kind)}) não está conectado/ativo. Disponíveis: " +
                src.joinToString { it.id }.ifBlank { "nenhum" } + ".")
        val acts = runCatching { actions(store, kind, id) }.getOrDefault(emptyList())
        if (acts.isNotEmpty() && acts.none { it.name == action }) return listOf("$name: a ação \"$action\" não existe. Exemplos: ${acts.take(12).joinToString { it.name }}.")
        val schema = runCatching { fields(store, kind, id, action, n.config["conexao"].orEmpty()) }.getOrDefault(emptyList())
        val campos = runCatching { Json.parseToJsonElement(n.config["campos"].orEmpty().ifBlank { "{}" }).jsonObject }.getOrNull()
            ?: return listOf("$name: campos não são um objeto JSON.")
        val missing = schema.filter { f -> f.required && f.default.isBlank() && (campos[f.key] as? JsonPrimitive)?.contentOrNull.isNullOrBlank() && campos[f.key] !is JsonObject && campos[f.key] !is JsonArray }
        return if (missing.isEmpty()) emptyList() else listOf("$name: preencha ${missing.joinToString { "${it.key} (${it.label})" }}.")
    }
}
