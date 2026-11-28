package dev.fluwy.app.connectors

import dev.fluwy.app.FluwyApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Catálogo, login e contas dos conectores do mini-hub.
 *
 * - Catálogo: assets/minihub/catalog.json, gerado no CI instalando e carregando cada piece de verdade
 *   (tools/minihub/build-catalog.js). Cada entrada traz a versão verificada, usada na primeira instalação.
 * - Contas: guardadas no aparelho, criptografadas (CredentialVault/Keystore). Nunca vão para a IA, logs ou
 *   diagnóstico; o mini-hub recebe a conta só no pedido que a usa.
 * - OAuth: igual ao Activepieces CE — com o app OAuth gerenciado do Activepieces (secrets.activepieces.com)
 *   quando ele existe para o conector, ou com o client próprio do usuário.
 */
object HubApi {
    const val CLOUD_REDIRECT = "https://secrets.activepieces.com/redirect"
    private const val VAULT_ID = "minihub_connections"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val web = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    class HubError(message: String, val code: Int = 0) : Exception(message)

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()

    fun authList(el: JsonElement?): List<JsonObject> = when (el) {
        is JsonObject -> listOf(el)
        is JsonArray -> el.mapNotNull { it as? JsonObject }
        else -> emptyList()
    }

    /** "gmail" → "@activepieces/piece-gmail"; nomes completos passam como estão. */
    fun pieceName(raw: String): String {
        val t = raw.trim().lowercase()
        return when {
            t.startsWith("@") -> t
            t.startsWith("piece-") -> "@activepieces/$t"
            else -> "@activepieces/piece-" + t.replace(Regex("[^a-z0-9]+"), "-").trim('-')
        }
    }

    // ---------------- Catálogo ----------------
    data class PieceSummary(val name: String, val displayName: String, val description: String, val logoUrl: String, val actions: Int, val authTypes: List<String>)

    /** Ação no catálogo: nome técnico, nome de exibição, descrição e campos obrigatórios. */
    data class CatalogAction(val name: String, val displayName: String, val description: String, val required: List<String>)
    data class CatalogPiece(val name: String, val version: String, val displayName: String, val description: String, val logoUrl: String,
                            val authTypes: List<String>, val authOptional: Boolean, val actions: List<CatalogAction>, val triggers: Int) {
        fun summary() = PieceSummary(name, displayName, description, logoUrl, actions.size, authTypes)
    }

    @Volatile private var catalogCache: List<CatalogPiece>? = null

    internal fun parseCatalog(text: String): List<CatalogPiece> {
        val root = json.parseToJsonElement(text).jsonObject
        return (root["pieces"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { o ->
            val name = o.s("name").ifBlank { return@mapNotNull null }
            val actions = (o["actions"] as? JsonArray).orEmpty().mapNotNull { a ->
                val arr = a as? JsonArray ?: return@mapNotNull null
                fun at(i: Int) = (arr.getOrNull(i) as? JsonPrimitive)?.contentOrNull.orEmpty()
                CatalogAction(at(0), at(1).ifBlank { at(0) }, at(2), (arr.getOrNull(3) as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull })
            }
            CatalogPiece(name, o.s("version"), o.s("displayName").ifBlank { name.substringAfter("piece-") }, o.s("description"), o.s("logoUrl"),
                (o["auth"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }, o.s("authOptional") == "true",
                actions, (o["triggers"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0)
        }
    }

    fun catalog(): List<CatalogPiece> = catalogCache ?: runCatching {
        FluwyApp.instance.assets.open("minihub/catalog.json").use { it.readBytes().toString(Charsets.UTF_8) }.let { parseCatalog(it) }
    }.getOrDefault(emptyList()).also { if (it.isNotEmpty()) catalogCache = it }

    fun catalogPiece(name: String): CatalogPiece? = pieceName(name).let { n -> catalog().firstOrNull { it.name == n } }

    /** Versão verificada pelo CI (usada só na 1ª instalação; depois vale a instalada/atualizada). */
    fun catalogVersion(name: String): String? = catalogPiece(name)?.version?.ifBlank { null }

    internal fun search(all: List<CatalogPiece>, query: String): List<CatalogPiece> {
        val q = query.trim().lowercase()
        if (q.isBlank()) return all
        val words = q.split(Regex("\\s+")).filter { it.isNotBlank() }
        return all.map { p ->
            val title = p.displayName.lowercase(); val slug = p.name.substringAfter("piece-")
            val hay = "$title $slug ${p.description.lowercase()} " + p.actions.joinToString(" ") { (it.displayName + " " + it.name).lowercase() }
            val score = when {
                title == q || slug == q -> 0
                title.startsWith(q) || slug.startsWith(q) -> 1
                title.contains(q) || slug.contains(q) -> 2
                words.all { hay.contains(it) } -> 3
                else -> 99
            }
            p to score
        }.filter { it.second < 99 }.sortedWith(compareBy({ it.second }, { it.first.displayName })).map { it.first }
    }

    fun cachedPieces(): List<PieceSummary> = catalog().map { it.summary() }

    suspend fun pieces(search: String = ""): List<PieceSummary> = withContext(Dispatchers.Default) {
        val all = catalog()
        if (all.isEmpty()) throw HubError("Catálogo de conectores ausente neste APK.")
        search(all, search).map { it.summary() }
    }

    // ---------------- Metadados completos (do mini-hub, com os campos de cada ação) ----------------
    private val pieceCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, JsonObject>>()
    private fun cacheFile(name: String) = java.io.File(FluwyApp.instance.cacheDir, "minihub/" + name.replace(Regex("[^A-Za-z0-9._-]"), "_")).apply { parentFile?.mkdirs() }

    /** Metadados completos (ações com campos, auth) da versão instalada. Na 1ª vez instala o conector (~10–60 s). */
    suspend fun piece(name: String): JsonObject {
        val full = pieceName(name)
        pieceCache[full]?.takeIf { System.currentTimeMillis() - it.first < 30 * 60_000 }?.let { return it.second }
        withContext(Dispatchers.IO) {
            cacheFile("piece_$full.json").takeIf { it.isFile && System.currentTimeMillis() - it.lastModified() < 7L * 24 * 3600_000 }?.readText()
        }?.let { body -> runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()?.let { pieceCache[full] = System.currentTimeMillis() to it; return it } }
        val meta = try {
            ActivepiecesHub.call("/metadata", buildJsonObject { put("name", full); catalogVersion(full)?.let { put("version", it) } }, 900)
        } catch (e: ActivepiecesHub.MiniHubError) {
            throw HubError(if (e.code == 404) "O conector \"$name\" não existe no catálogo do Activepieces." else e.message.orEmpty(), e.code)
        }
        withContext(Dispatchers.IO) { runCatching { cacheFile("piece_$full.json").writeText(meta.toString()) } }
        pieceCache[full] = System.currentTimeMillis() to meta
        return meta
    }

    fun invalidateCatalog() { runCatching { java.io.File(FluwyApp.instance.cacheDir, "minihub").deleteRecursively() }; pieceCache.clear() }

    // ---------------- OAuth ----------------
    private var cloudApps: Pair<Long, Map<String, String>>? = null

    /** Apps OAuth gerenciados do Activepieces (client_id por conector): login com um toque, como no Activepieces CE. */
    suspend fun cloudClientId(pieceName: String): String? = withContext(Dispatchers.IO) {
        val cached = cloudApps?.takeIf { System.currentTimeMillis() - it.first < 6 * 3600_000L }?.second
        val map = cached ?: runCatching {
            web.newCall(Request.Builder().url("https://secrets.activepieces.com/apps?edition=ce").build()).execute().use { r ->
                val o = json.parseToJsonElement(r.body?.string().orEmpty()).jsonObject
                o.mapNotNull { (k, v) -> ((v as? JsonObject)?.get("clientId") as? JsonPrimitive)?.contentOrNull?.let { k to it } }.toMap()
            }
        }.getOrNull()?.also { cloudApps = System.currentTimeMillis() to it } ?: emptyMap()
        map[pieceName]
    }

    /** Endereço de retorno para app OAuth próprio (o mesmo do hub antigo: quem já cadastrou não precisa mudar). */
    fun ownRedirectUrl(): String = ActivepiecesHub.BASE + "/redirect"

    data class AuthUrl(val url: String, val codeVerifier: String?)

    suspend fun authorizationUrl(pieceName: String, clientId: String, redirectUrl: String, props: Map<String, String>): AuthUrl {
        val o = try {
            ActivepiecesHub.call("/oauth/url", buildJsonObject {
                put("name", pieceName(pieceName)); catalogVersion(pieceName)?.let { put("version", it) }
                put("clientId", clientId); put("redirectUrl", redirectUrl)
                if (props.isNotEmpty()) put("props", JsonObject(props.mapValues { JsonPrimitive(it.value) }))
            }, 900)
        } catch (e: ActivepiecesHub.MiniHubError) { throw HubError(e.message.orEmpty(), e.code) }
        return AuthUrl(o.s("authorizationUrl").ifBlank { throw HubError("Não consegui gerar o link de login.") }, o.s("codeVerifier").ifBlank { null })
    }

    // ---------------- Contas conectadas (no aparelho, criptografadas) ----------------
    data class HubConnection(val id: String, val externalId: String, val displayName: String, val pieceName: String, val status: String)

    private val connMutex = Mutex()
    private fun vault() = dev.fluwy.app.uiconnector.CredentialVault(FluwyApp.instance)
    private fun loadAll(): MutableMap<String, JsonObject> = (vault().load(VAULT_ID) ?: emptyMap())
        .mapNotNull { (k, v) -> runCatching { k to json.parseToJsonElement(v).jsonObject }.getOrNull() }.toMap().toMutableMap()
    private fun saveAll(m: Map<String, JsonObject>) = vault().save(VAULT_ID, m.mapValues { it.value.toString() })

    private fun newExternalId() = java.util.UUID.randomUUID().toString().replace("-", "").take(21)

    /**
     * Cria a conta: troca o code OAuth por tokens (no mini-hub), valida a credencial no próprio serviço (validate
     * da piece) e só então guarda. [value] segue o formato do Activepieces ({"type": ..., ...}).
     */
    suspend fun createConnection(pieceName: String, displayName: String, type: String, value: JsonObject): String {
        val name = pieceName(pieceName)
        val version = catalogVersion(name)
        val conn: JsonObject = try {
            if ((type == "CLOUD_OAUTH2" || type == "OAUTH2") && value.s("code").isNotBlank()) {
                val r = ActivepiecesHub.call("/oauth/claim", buildJsonObject {
                    put("name", name); version?.let { put("version", it) }
                    put("mode", if (type == "CLOUD_OAUTH2") "cloud" else "own")
                    put("code", value.s("code")); value.s("code_challenge").ifBlank { null }?.let { put("codeVerifier", it) }
                    put("clientId", value.s("client_id")); value.s("client_secret").ifBlank { null }?.let { put("clientSecret", it) }
                    put("redirectUrl", value.s("redirect_url").ifBlank { if (type == "CLOUD_OAUTH2") CLOUD_REDIRECT else ownRedirectUrl() })
                    (value["props"] as? JsonObject)?.let { put("props", it) }
                }, 900)
                r["connection"] as? JsonObject ?: throw HubError("O serviço não devolveu os tokens.")
            } else if (type == "NO_AUTH") {
                buildJsonObject { put("type", "NO_AUTH") }
            } else value
        } catch (e: ActivepiecesHub.MiniHubError) { throw HubError("O serviço recusou o login: ${e.message}", e.code) }
        if (type != "NO_AUTH") {
            val v = try { ActivepiecesHub.call("/validate", buildJsonObject { put("name", name); version?.let { put("version", it) }; put("connection", conn) }, 900) }
                catch (e: ActivepiecesHub.MiniHubError) { throw HubError(e.message.orEmpty(), e.code) }
            if (v.s("valid") == "false") throw HubError("O serviço recusou os dados da conexão" + v.s("error").ifBlank { null }?.let { ": $it" }.orEmpty())
        } else {
            // Sem login: só garante o conector instalado para o agente usar na hora.
            piece(name)
        }
        val id = newExternalId()
        // Conta que veio de um backup (pendente de login) do mesmo app: a nova entra no lugar dela.
        val pendingName = withContext(Dispatchers.IO) { loadAll().values.firstOrNull { it.s("pieceName") == name && it.s("status") == PENDING }?.s("displayName") }
        val entry = buildJsonObject {
            put("externalId", id); put("displayName", displayName.ifBlank { catalogPiece(name)?.displayName ?: name.substringAfter("piece-") })
            put("pieceName", name); put("status", "ACTIVE"); put("createdAt", System.currentTimeMillis()); put("value", conn)
        }
        withContext(Dispatchers.IO) { connMutex.withLock { saveAll(loadAll().apply {
            if (pendingName != null) entries.firstOrNull { it.value.s("pieceName") == name && it.value.s("status") == PENDING }?.key?.let { remove(it) }
            put(id, entry)
        }) } }
        HubConnectors.invalidate()
        runCatching { HubConnectors.syncConnector(FluwyApp.instance.store) }
        return id
    }

    const val PENDING = "PENDING"

    /** Lista contas vindas de um backup: aparecem como "entre de novo" até o usuário conectar o app outra vez. */
    suspend fun importPending(list: List<Pair<String, String>>): Int {
        var n = 0
        withContext(Dispatchers.IO) { connMutex.withLock {
            val all = loadAll()
            list.forEach { (piece, display) ->
                val name = pieceName(piece)
                if (all.values.any { it.s("pieceName") == name && it.s("displayName") == display }) return@forEach
                val id = newExternalId()
                all[id] = buildJsonObject {
                    put("externalId", id); put("displayName", display); put("pieceName", name); put("status", PENDING)
                    put("createdAt", System.currentTimeMillis()); put("value", JsonObject(emptyMap()))
                }
                n++
            }
            saveAll(all)
        } }
        HubConnectors.invalidate()
        return n
    }

    suspend fun connections(): List<HubConnection> = withContext(Dispatchers.IO) {
        loadAll().values.map { o -> HubConnection(o.s("externalId"), o.s("externalId"), o.s("displayName"), o.s("pieceName"), o.s("status").ifBlank { "ACTIVE" }) }
            .sortedBy { it.displayName.lowercase() }
    }

    suspend fun deleteConnection(id: String) {
        withContext(Dispatchers.IO) { connMutex.withLock { saveAll(loadAll().apply { remove(id) }) } }
        HubConnectors.invalidate()
        runCatching { HubConnectors.syncConnector(FluwyApp.instance.store) }
    }

    /** Valor da conta (segredo): só para mandar ao mini-hub no pedido que a usa. */
    internal suspend fun connectionValue(externalId: String): JsonObject? = withContext(Dispatchers.IO) {
        loadAll()[externalId]?.get("value") as? JsonObject
    }

    /** Guarda tokens renovados pelo mini-hub; [status] ERROR quando o serviço revogou o acesso. */
    internal suspend fun updateConnection(externalId: String, value: JsonObject?, status: String? = null) = withContext(Dispatchers.IO) {
        connMutex.withLock {
            val all = loadAll()
            val cur = all[externalId] ?: return@withLock
            all[externalId] = JsonObject(cur.toMutableMap().apply { value?.let { put("value", it) }; status?.let { put("status", JsonPrimitive(it)) } })
            saveAll(all)
        }
        if (status != null) HubConnectors.invalidate()
    }
}
