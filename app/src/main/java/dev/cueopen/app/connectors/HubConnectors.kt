package dev.cueopen.app.connectors

import dev.cueopen.app.data.Store
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Conectores do hub para o agente — 3 ferramentas genéricas que servem para QUALQUER conexão feita no hub
 * (nada é predefinido por app): listar as conexões, ver o que um conector faz (ações e campos, lidos do próprio
 * conector) e executar uma ação com a conta conectada (ap_run_action, sem criar automação).
 */
object HubConnectors {
    val TOOLS = setOf("conectores_listar", "conector_acoes", "conector_executar")

    private fun hub(store: Store) = store.state.value.connectors.firstOrNull { it.gatewayRef == ActivepiecesHub.REF }

    /** O hub está ligado e conectado ao agente. */
    fun available(store: Store): Boolean = hub(store)?.let { it.enabled && it.connectedAt != null } == true

    private suspend fun mcp(store: Store, tool: String, args: JsonObject): String {
        if (!ActivepiecesHub.ensureRunning()) return "ERRO: o hub de conectores não está no ar. Peça ao usuário para iniciá-lo em Apps › Conectores."
        return Connectors.callTool(store, ActivepiecesHub.hubTool(tool), args)
    }

    data class Conn(val externalId: String, val displayName: String, val pieceName: String, val status: String)

    private val LINE = Regex("""externalId:\s*(\S+)\s*\|\s*displayName:\s*"?(.*?)"?\s*\|\s*piece:\s*(\S+)\s*\|\s*status:\s*(\w+)""")

    /** Linhas do ap_list_connections ("- externalId: X | displayName: "Y" | piece: P | status: ACTIVE …"). */
    fun parseConnections(text: String): List<Conn> =
        LINE.findAll(text).map { m -> Conn(m.groupValues[1], m.groupValues[2], m.groupValues[3], m.groupValues[4]) }.toList()

    fun appName(piece: String) = piece.substringAfterLast("piece-").replace('-', ' ').replaceFirstChar { it.uppercase() }

    suspend fun connections(store: Store): List<Conn> = parseConnections(mcp(store, "ap_list_connections", JsonObject(emptyMap())))

    suspend fun list(store: Store): String {
        val raw = mcp(store, "ap_list_connections", JsonObject(emptyMap()))
        if (raw.startsWith("ERRO")) return raw
        val conns = parseConnections(raw)
        if (conns.isEmpty()) return "Nenhum conector conectado no hub ainda. Peça ao usuário para conectar o app em Apps › Conectores › Adicionar conector."
        return "Conectores disponíveis (${conns.size}):\n" + conns.joinToString("\n") { c ->
            "- ${appName(c.pieceName)} · \"${c.displayName}\" · conector=${c.pieceName} · conexao=${c.externalId}" + (if (c.status != "ACTIVE") " · status ${c.status} (pode precisar reconectar)" else "")
        } + "\nPróximo passo: conector_acoes {conector} para ver o que ele faz."
    }

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()

    suspend fun actions(store: Store, conector: String, acao: String, busca: String): String {
        if (conector.isBlank()) return "ERRO: informe conector (ex.: gmail)."
        val piece = HubApi.pieceName(conector)
        if (acao.isNotBlank()) {
            // Campos exatos, com dicas do próprio hub (obrigatórios, opções a resolver, exemplo de entrada).
            val out = mcp(store, "ap_get_piece_props", buildJsonObject { put("pieceName", piece); put("actionOrTriggerName", acao.trim()); put("type", "action") })
            if (!out.startsWith("ERRO") && !out.trimStart().startsWith("❌")) return out.take(7_000) + "\n\nPróximo passo: conector_executar {conector:\"$piece\", acao:\"${acao.trim()}\", campos:{…}, conexao}."
            return runCatching { fieldsFromMetadata(piece, acao.trim()) }.getOrElse { "ERRO: ${it.message}" }
        }
        val meta = runCatching { HubApi.piece(piece) }.getOrElse { return "ERRO: ${it.message}" }
        val acts = (meta["actions"] as? JsonObject).orEmpty().values.mapNotNull { it as? JsonObject }
        val q = busca.trim().lowercase()
        val shown = acts.filter { q.isBlank() || (it.s("name") + " " + it.s("displayName") + " " + it.s("description")).lowercase().contains(q) }
        if (shown.isEmpty()) return "O conector ${meta.s("displayName")} não tem ações" + (if (q.isNotBlank()) " com \"$busca\". Tente outra palavra ou omita busca." else ".")
        val needsAuth = HubApi.authList(meta["auth"]).isNotEmpty()
        return "Ações de ${meta.s("displayName")} ($piece)" + (if (needsAuth) " — precisa da conexao (conectores_listar)" else " — não precisa de conta") + ":\n" +
            shown.take(60).joinToString("\n") { a -> "- ${a.s("name")}: ${a.s("displayName")}" + a.s("description").takeIf { it.isNotBlank() }?.let { " — " + it.take(140) }.orEmpty() } +
            (if (shown.size > 60) "\n… e mais ${shown.size - 60}; use busca para filtrar." else "") +
            "\nPróximo passo: conector_acoes {conector:\"$piece\", acao:\"<nome>\"} para os campos."
    }

    private suspend fun fieldsFromMetadata(piece: String, acao: String): String {
        val meta = HubApi.piece(piece)
        val a = (meta["actions"] as? JsonObject)?.get(acao) as? JsonObject
            ?: return "ERRO: a ação \"$acao\" não existe em $piece. Veja os nomes com conector_acoes {conector:\"$piece\"}."
        val props = (a["props"] as? JsonObject).orEmpty()
        return "Campos de ${a.s("displayName")} ($piece / $acao):\n" + props.entries.joinToString("\n") { (k, v) ->
            val o = v as? JsonObject ?: return@joinToString "- $k"
            val opts = ((o["options"] as? JsonObject)?.get("options") as? JsonArray)?.mapNotNull { (it as? JsonObject)?.s("value") }?.take(15)
            "- $k (${o.s("type").lowercase()}${if (o.s("required") == "true") ", obrigatório" else ""}): ${o.s("displayName")}" +
                o.s("description").takeIf { it.isNotBlank() }?.let { " — " + it.take(160) }.orEmpty() +
                (if (!opts.isNullOrEmpty()) " · opções: ${opts.joinToString()}" else "")
        }.ifBlank { "(sem campos)" }
    }

    /** "campos" pode vir como objeto ou como texto JSON (alguns modelos mandam string). */
    fun fields(el: JsonElement?): JsonObject? = when (el) {
        null, JsonNull -> JsonObject(emptyMap())
        is JsonObject -> el
        is JsonPrimitive -> el.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.let { t -> runCatching { Json.parseToJsonElement(t) as? JsonObject }.getOrNull() }
            ?: if (el.contentOrNull.isNullOrBlank()) JsonObject(emptyMap()) else null
        else -> null
    }

    suspend fun run(store: Store, conector: String, acao: String, campos: JsonElement?, conexao: String): String {
        if (conector.isBlank() || acao.isBlank()) return "ERRO: informe conector e acao (veja conector_acoes)."
        val input = fields(campos) ?: return "ERRO: campos precisa ser um objeto JSON (ex.: {\"to\":\"a@b.com\"})."
        val piece = HubApi.pieceName(conector)
        var conn = conexao.trim()
        if (conn.isBlank()) {
            // Uma única conta conectada para esse app: usa ela (evita uma volta só para pegar o id).
            val mine = runCatching { connections(store) }.getOrDefault(emptyList()).filter { it.pieceName == piece }
            if (mine.size == 1) conn = mine.single().externalId
            else if (mine.size > 1) return "ERRO: há ${mine.size} contas de ${appName(piece)} conectadas: " +
                mine.joinToString { "\"${it.displayName}\" (conexao=${it.externalId})" } + ". Escolha uma e repita com conexao."
        }
        val out = mcp(store, "ap_run_action", buildJsonObject {
            put("pieceName", piece); put("actionName", acao.trim()); put("input", input)
            if (conn.isNotBlank()) put("connectionExternalId", conn)
        })
        return out.take(12_000)
    }
}
