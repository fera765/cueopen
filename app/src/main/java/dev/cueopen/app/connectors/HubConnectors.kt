package dev.cueopen.app.connectors

import dev.cueopen.app.data.Store
import kotlinx.coroutines.withTimeoutOrNull
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
 * Conectores para o agente — 3 ferramentas genéricas que servem para QUALQUER conector do catálogo (nada é
 * predefinido por app): listar as contas conectadas, ver o que um conector faz (ações e campos, lidos do próprio
 * conector) e executar uma ação com a conta (no mini-hub, sem automação).
 *
 * A conta (tokens/chaves) sai do cofre do app só para o mini-hub, no pedido que a usa; o agente vê apenas o id.
 */
object HubConnectors {
    val TOOLS = setOf("conectores_listar", "conector_acoes", "conector_executar")

    private fun hub(store: Store) = store.state.value.connectors.firstOrNull { it.gatewayRef == ActivepiecesHub.REF }

    /** Conectores ligados para o agente (o mini-hub sobe sozinho no primeiro uso). */
    fun available(store: Store): Boolean = hub(store)?.let { it.enabled && it.connectedAt != null } == true

    data class Conn(val externalId: String, val displayName: String, val pieceName: String, val status: String)

    fun appName(piece: String): String = HubApi.catalogPiece(piece)?.displayName
        ?: piece.substringAfterLast("piece-").replace('-', ' ').replaceFirstChar { it.uppercase() }

    @Volatile private var connCache: Pair<Long, List<Conn>>? = null

    @Suppress("UNUSED_PARAMETER")
    suspend fun connections(store: Store, fresh: Boolean = false): List<Conn> {
        connCache?.takeIf { !fresh && System.currentTimeMillis() - it.first < 60_000 }?.let { return it.second }
        return HubApi.connections().map { Conn(it.externalId, it.displayName, it.pieceName, it.status) }.also { connCache = System.currentTimeMillis() to it }
    }

    fun invalidate() { connCache = null }

    fun lastKnown(): List<Conn>? = connCache?.second

    /** Resumo curto das contas (vai para o prompt: o agente sabe o que existe sem listar). Sem segredos. */
    internal fun inventoryOf(conns: List<Conn>): String? =
        conns.joinToString(" | ") { "${appName(it.pieceName)} \"${it.displayName}\" ${it.pieceName.substringAfter("piece-")}" + if (it.status != "ACTIVE") " (reconectar)" else "" }
            .take(1200).ifBlank { null }

    /**
     * Mantém a entrada "Conectores" na lista do app: conectada quando o Linux do app existe (o mini-hub instala o
     * Node sozinho) e com o inventário das contas. Preserva o liga/desliga do usuário.
     */
    suspend fun syncConnector(store: Store) {
        val conns = runCatching { connections(store, fresh = true) }.getOrDefault(emptyList())
        val cur = store.connector(ActivepiecesHub.CONNECTOR_ID)
        val ready = ActivepiecesHub.sandboxReady()
        if (cur == null && !ready && conns.isEmpty()) return
        val base = cur ?: ActivepiecesHub.connector()
        store.upsertConnector(base.copy(
            name = ActivepiecesHub.connector().name, url = ActivepiecesHub.MCP_URL, description = ActivepiecesHub.connector().description,
            needsAuth = false, toolsJson = "[]", lastError = null, gatewayRef = ActivepiecesHub.REF,
            connectedAt = base.connectedAt ?: if (ready || conns.isNotEmpty()) System.currentTimeMillis() else null,
            inventory = inventoryOf(conns),
        ))
    }

    suspend fun list(store: Store): String {
        val conns = runCatching { connections(store, fresh = true) }.getOrElse { return "ERRO: ${it.message}" }
        if (conns.isEmpty()) return "Nenhuma conta conectada ainda. Conectores sem login (ex.: http, date-helper) já funcionam: veja com conector_acoes. " +
            "Para apps com login, peça ao usuário para conectar em Apps › Conectores › Adicionar conector."
        return "Conectores disponíveis (${conns.size}):\n" + conns.joinToString("\n") { c ->
            "- ${appName(c.pieceName)} · \"${c.displayName}\" · conector=${c.pieceName} · conexao=${c.externalId}" + (if (c.status != "ACTIVE") " · status ${c.status} (o usuário precisa reconectar)" else "")
        } + "\nPróximo passo: conector_acoes {conector} para ver o que ele faz."
    }

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()

    suspend fun actions(store: Store, conector: String, acao: String, busca: String): String {
        if (conector.isBlank()) return "ERRO: informe conector (ex.: gmail)."
        val piece = HubApi.pieceName(conector)
        if (acao.isNotBlank()) return runCatching { fields(store, piece, acao.trim()) }.getOrElse { "ERRO: ${it.message}" }
        val q = busca.trim().lowercase()
        // Lista de ações vem do catálogo (instantâneo, sem instalar); se o conector não está no catálogo, do mini-hub.
        val cat = HubApi.catalogPiece(piece)
        data class A(val name: String, val title: String, val desc: String)
        val (title, acts, needsAuth) = if (cat != null) Triple(cat.displayName, cat.actions.map { A(it.name, it.displayName, it.description) }, cat.authTypes.isNotEmpty() && !cat.authOptional)
        else {
            val meta = runCatching { HubApi.piece(piece) }.getOrElse { return "ERRO: ${it.message}" }
            Triple(meta.s("displayName"), (meta["actions"] as? JsonObject).orEmpty().values.mapNotNull { it as? JsonObject }.map { A(it.s("name"), it.s("displayName"), it.s("description")) },
                HubApi.authList(meta["auth"]).isNotEmpty())
        }
        val shown = acts.filter { q.isBlank() || (it.name + " " + it.title + " " + it.desc).lowercase().contains(q) }
        if (shown.isEmpty()) return "O conector $title não tem ações" + (if (q.isNotBlank()) " com \"$busca\". Tente outra palavra ou omita busca." else ".")
        return "Ações de $title ($piece)" + (if (needsAuth) " — precisa da conexao (conectores_listar)" else " — não precisa de conta") + ":\n" +
            shown.take(60).joinToString("\n") { a -> "- ${a.name}: ${a.title}" + a.desc.takeIf { it.isNotBlank() }?.let { " — " + it.take(140) }.orEmpty() } +
            (if (shown.size > 60) "\n… e mais ${shown.size - 60}; use busca para filtrar." else "") +
            "\nPróximo passo: conector_acoes {conector:\"$piece\", acao:\"<nome>\"} para os campos."
    }

    /** Campos exatos da ação, com as opções de listas dinâmicas (pastas, planilhas, canais…) já resolvidas. */
    private suspend fun fields(store: Store, piece: String, acao: String): String {
        val meta = HubApi.piece(piece)
        val actions = (meta["actions"] as? JsonObject).orEmpty()
        val a = (actions[acao] ?: actions.values.firstOrNull { (it as? JsonObject)?.s("displayName").equals(acao, true) }) as? JsonObject
            ?: return "ERRO: a ação \"$acao\" não existe em $piece. Veja os nomes com conector_acoes {conector:\"$piece\"}."
        val name = a.s("name").ifBlank { acao }
        val props = (a["props"] as? JsonObject).orEmpty()
        val conn = runCatching { connections(store).filter { it.pieceName == piece } }.getOrDefault(emptyList()).singleOrNull()
        var resolved = 0
        val lines = props.entries.map { (k, v) ->
            val o = v as? JsonObject ?: return@map "- $k"
            val type = o.s("type")
            if (type == "MARKDOWN") return@map "- (nota) " + o.s("description").ifBlank { o.s("displayName") }.take(200)
            val staticOpts = ((o["options"] as? JsonObject)?.get("options") as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let { op -> op.s("label").ifBlank { op.s("value") } + "=" + op["value"].toString() } }
            val refreshers = (o["refreshers"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.filter { it != "auth" }
            var dyn: String? = null
            if (o.s("optionsDynamic") == "true" && refreshers.isEmpty() && resolved < 4) {
                resolved++
                dyn = withTimeoutOrNull(25_000) { runCatching { options(piece, name, k, conn?.externalId) }.getOrNull() }
            }
            "- $k (${type.lowercase()}${if (o.s("required") == "true") ", obrigatório" else ""}): ${o.s("displayName")}" +
                o.s("description").takeIf { it.isNotBlank() }?.let { " — " + it.take(160) }.orEmpty() +
                (o["defaultValue"]?.takeIf { it !is JsonNull }?.let { " · padrão: $it" }.orEmpty()) +
                (if (!staticOpts.isNullOrEmpty()) " · opções: ${staticOpts.take(20).joinToString()}" else "") +
                (dyn?.let { " · opções: $it" } ?: if (o.s("optionsDynamic") == "true") " · opções dinâmicas" + (if (refreshers.isNotEmpty()) " (dependem de ${refreshers.joinToString()})" else "") else "")
        }
        return ("Campos de ${a.s("displayName")} ($piece / $name):\n" + lines.joinToString("\n").ifBlank { "(sem campos)" }).take(7_000) +
            "\n\nPróximo passo: conector_executar {conector:\"$piece\", acao:\"$name\", campos:{…}, conexao}."
    }

    /** Opções de um campo dinâmico ("rótulo=valor"), com a conta quando houver. */
    private suspend fun options(piece: String, action: String, prop: String, conexao: String?): String? {
        val opts = optionList(piece, action, prop, conexao, JsonObject(emptyMap()))
        if (opts.isEmpty()) return null
        return opts.take(25).joinToString { it.first.take(60) + "=" + it.second.take(80) } + if (opts.size > 25) " … (+${opts.size - 25})" else ""
    }

    /** Opções de um campo dinâmico como (rótulo, valor JSON). [input] leva os campos já preenchidos (para campos dependentes). */
    suspend fun optionList(piece: String, action: String, prop: String, conexao: String?, input: JsonObject): List<Pair<String, String>> {
        val connValue = conexao?.takeIf { it.isNotBlank() }?.let { HubApi.connectionValue(it) }
        val r = ActivepiecesHub.call("/options", buildJsonObject {
            put("name", piece); HubApi.catalogVersion(piece)?.let { put("version", it) }
            put("action", action); put("prop", prop); put("input", input)
            connValue?.let { put("connection", it) }
        }, 60)
        (r["connection"] as? JsonObject)?.let { if (!conexao.isNullOrBlank()) HubApi.updateConnection(conexao, it) }
        if (r.s("ok") != "true") return emptyList()
        return ((r["options"] as? JsonObject)?.get("options") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .map { it.s("label").ifBlank { it.s("value") } to (it["value"]?.let { v -> (v as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: v.toString() } ?: "") }
    }

    /** "campos" pode vir como objeto ou como texto JSON (alguns modelos mandam string). */
    fun fields(el: JsonElement?): JsonObject? = when (el) {
        null, JsonNull -> JsonObject(emptyMap())
        is JsonObject -> el
        is JsonPrimitive -> el.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.let { t -> runCatching { Json.parseToJsonElement(t) as? JsonObject }.getOrNull() }
            ?: if (el.contentOrNull.isNullOrBlank()) JsonObject(emptyMap()) else null
        else -> null
    }

    private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }

    /**
     * Saída de conector_executar: só JSON, identado, já resumido pelo mini-hub (status, duration_ms, summary,
     * data, note). Erro de execução: "ERRO: " + JSON com status "error" (o app marca o passo como falha pelo prefixo).
     */
    internal fun formatRun(piece: String, r: JsonObject): String {
        if (r.s("ok") != "true") {
            val err = buildJsonObject {
                put("status", "error"); put("connector", appName(piece)); put("action", r.s("action"))
                put("error", r.s("error").take(3_000))
            }
            return "ERRO: " + pretty.encodeToString(JsonObject.serializer(), err)
        }
        val result = r["result"] as? JsonObject ?: buildJsonObject {
            put("status", "ok"); put("connector", appName(piece)); put("action", r.s("action")); r["output"]?.let { put("data", it) }
        }
        val text = pretty.encodeToString(JsonObject.serializer(), result)
        return if (text.length <= MAX_OUT) text else text.take(MAX_OUT) + "\n…[cortado em ${MAX_OUT / 1000} KB]"
    }

    private const val MAX_OUT = 30_000

    suspend fun run(store: Store, conector: String, acao: String, campos: JsonElement?, conexao: String, full: Boolean = false): String {
        if (conector.isBlank() || acao.isBlank()) return "ERRO: informe conector e acao (veja conector_acoes)."
        val input = fields(campos) ?: return "ERRO: campos precisa ser um objeto JSON (ex.: {\"to\":\"a@b.com\"})."
        val piece = HubApi.pieceName(conector)
        var conn = conexao.trim()
        val mine = runCatching { connections(store) }.getOrDefault(emptyList()).filter { it.pieceName == piece }
        if (conn.isBlank()) {
            // Uma única conta para esse app: usa ela (evita uma volta só para pegar o id).
            val active = mine.filter { it.status == "ACTIVE" }
            if (active.size == 1) conn = active.single().externalId
            else if (mine.size == 1) conn = mine.single().externalId
            else if (mine.size > 1 && active.size != 1) return "ERRO: há ${mine.size} contas de ${appName(piece)} conectadas: " +
                mine.joinToString { "\"${it.displayName}\" (conexao=${it.externalId})" } + ". Escolha uma e repita com conexao."
        }
        val value = if (conn.isNotBlank()) HubApi.connectionValue(conn)
            ?: return "ERRO: a conexao \"$conn\" não existe. Chame conectores_listar e use o id exato." else null
        if (value != null && value.s("type").isBlank())
            return "ERRO: a conta \"$conn\" de ${appName(piece)} veio de um backup e precisa de login de novo. Peça ao usuário para conectar o app em Apps › Conectores › Adicionar conector."
        val connValue = value?.takeIf { it.s("type") != "NO_AUTH" }
        val r = try {
            ActivepiecesHub.call("/run", buildJsonObject {
                put("name", piece); HubApi.catalogVersion(piece)?.let { put("version", it) }
                put("action", acao.trim()); put("input", input)
                connValue?.let { put("connection", it) }
                if (full) put("full", true)
            }, 900)
        } catch (e: ActivepiecesHub.MiniHubError) {
            val msg = e.message.orEmpty()
            if (conn.isNotBlank() && (msg.contains("invalid_grant") || msg.contains("HTTP 401"))) {
                HubApi.updateConnection(conn, null, "ERROR")
                return "ERRO: a conta \"$conn\" de ${appName(piece)} perdeu o acesso (token revogado ou vencido). Peça ao usuário para reconectar em Apps › Conectores."
            }
            if (msg.contains("conta conectada")) return "ERRO: ${appName(piece)} precisa de uma conta conectada e não há nenhuma. Peça ao usuário para conectar em Apps › Conectores › Adicionar conector."
            return "ERRO: $msg"
        }
        (r["connection"] as? JsonObject)?.let { if (conn.isNotBlank()) HubApi.updateConnection(conn, it) }
        return formatRun(piece, r)
    }
}
