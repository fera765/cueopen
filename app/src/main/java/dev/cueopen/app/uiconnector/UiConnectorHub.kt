package dev.cueopen.app.uiconnector

import android.content.Context
import dev.cueopen.app.data.AgentProfile
import dev.cueopen.app.diag.ErrorLog
import dev.cueopen.app.trading.Side
import dev.cueopen.app.trading.SignalGate
import dev.cueopen.app.trading.TradeCoordinator
import dev.cueopen.app.uiconnector.custom.CustomConnector
import dev.cueopen.app.uiconnector.custom.CustomConnectorStore
import dev.cueopen.app.uiconnector.custom.CustomDef
import dev.cueopen.app.uiconnector.custom.CustomDefParser
import dev.cueopen.app.uiconnector.iqoption.IqOptionConnector
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Hub dos UI Connectors: registro, cofre de credenciais, toggles e ponte com o agente.
 *
 * Regras de acesso do agente a um conector (todas precisam valer):
 *  1. o conector está habilitado e conectado (ou tem credenciais no cofre para reconectar);
 *  2. se "Agent required?" estiver LIGADO (padrão), só agentes com o conector ativado no perfil usam;
 *     se estiver DESLIGADO, qualquer conversa pode usar.
 * Toda saída de ferramenta passa por [Redactor] — segredos nunca chegam ao LLM.
 *
 * Ferramentas que abrem ordens (`requiresSignal`) só executam com uma validação prévia do avaliador
 * (portão em [TradeCoordinator.gate]), uma ordem por vez por conector, sem colisão entre agentes.
 */
class UiConnectorHub(context: Context) {
    companion object { const val BACKUP_FORMAT = "cueopen-uiconnectors-backup" }

    private val ctx = context.applicationContext
    private val prefs = ctx.getSharedPreferences("uic_prefs", Context.MODE_PRIVATE)
    val vault = CredentialVault(ctx)
    private val customStore = CustomConnectorStore(ctx)

    /** Conectores que vêm com o app. Para adicionar um novo em Kotlin: crie a classe e acrescente aqui. */
    private val builtin: List<UiConnector> = listOf<UiConnector>(IqOptionConnector(object : dev.cueopen.app.uiconnector.iqoption.KvStore {
        private val sp = context.applicationContext.getSharedPreferences("iq_state", Context.MODE_PRIVATE)
        override fun get(key: String): String? = sp.getString(key, null)
        override fun put(key: String, value: String) { sp.edit().putString(key, value).apply() }
    }), dev.cueopen.app.uiconnector.gmail.GmailConnector(ctx)).filter { c ->
        val problems = SpecValidator.validate(c.spec)
        if (problems.isNotEmpty()) ErrorLog.log("uiconnector", "spec inválido (${c.spec.id}): " + problems.joinToString("; "))
        problems.isEmpty()
    }

    @Volatile private var custom: List<UiConnector> = loadCustom(emptyList())

    /** Built-in + criados pelo usuário na página de Conectores. */
    val connectors: List<UiConnector> get() = builtin + custom

    fun get(id: String): UiConnector? = connectors.firstOrNull { it.spec.id == id }
    fun isCustom(id: String): Boolean = custom.any { it.spec.id == id }

    // ------------------------------------------------------------------ conectores do usuário
    private fun loadCustom(previous: List<UiConnector>): List<UiConnector> {
        val next = customStore.loadAll().mapNotNull { def ->
            // definição idêntica → mantém a instância (e a sessão logada)
            previous.firstOrNull { it is CustomConnector && it.def == def } ?: runCatching { CustomConnector(def) }.getOrNull()
        }
        // instâncias descartadas fecham a conexão em tempo real (WebSocket)
        previous.filter { p -> next.none { it === p } }.forEach { (it as? CustomConnector)?.dispose() }
        return next
    }

    fun customJson(id: String): String? = customStore.rawJson(id)

    /** Valida e grava a definição. Se mudou, a sessão/credenciais antigas deixam de valer. */
    suspend fun saveCustom(jsonText: String): Result<CustomDef> {
        val r = customStore.save(jsonText, builtin.map { it.spec.id }.toSet())
        r.onSuccess { def ->
            val previous = custom
            val old = previous.firstOrNull { it.spec.id == def.id } as? CustomConnector
            if (old != null && old.def != def) { old.logout(); vault.clear(def.id) }
            custom = loadCustom(previous)
        }
        return r
    }

    suspend fun deleteCustom(id: String) {
        (custom.firstOrNull { it.spec.id == id })?.logout()
        vault.clear(id)
        customStore.delete(id)
        custom = loadCustom(custom)
    }

    // ------------------------------------------------------------------ backup / importação
    /**
     * Backup de tudo dos UI Conectores: definições criadas pelo usuário + toggles + configurações.
     * Credenciais NÃO entram (ficam só no cofre cifrado deste aparelho).
     */
    fun exportBackup(): String {
        val defs = JsonArray(custom.mapNotNull { c ->
            val raw = customStore.rawJson(c.spec.id) ?: return@mapNotNull null
            val el = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(CustomDefParser.stripFences(raw)) }.getOrNull() ?: return@mapNotNull null
            buildJsonObject { put("id", c.spec.id); put("definition", el) }
        })
        val p = buildJsonObject {
            prefs.all.forEach { (k, v) ->
                when (v) {
                    is Boolean -> put(k, v)
                    is String -> put(k, v)
                    else -> {}
                }
            }
        }
        return buildJsonObject {
            put("format", BACKUP_FORMAT); put("version", 1); put("createdAt", System.currentTimeMillis())
            put("connectors", defs); put("prefs", p)
        }.toString()
    }

    data class ImportReport(val installed: List<String>, val errors: List<String>, val prefs: Int)

    suspend fun importBackup(text: String): Result<ImportReport> = runCatching {
        val root = kotlinx.serialization.json.Json.parseToJsonElement(text.trim()) as? JsonObject
            ?: throw IllegalArgumentException("Arquivo inválido: não é um backup de UI Conectores.")
        if ((root["format"] as? JsonPrimitive)?.contentOrNull != BACKUP_FORMAT) throw IllegalArgumentException("Arquivo inválido: não é um backup de UI Conectores do CueOpen.")
        val installed = ArrayList<String>(); val errors = ArrayList<String>()
        (root["connectors"] as? JsonArray)?.forEach { el ->
            val o = el as? JsonObject
            val def = o?.get("definition")
            val label = (o?.get("id") as? JsonPrimitive)?.contentOrNull ?: "?"
            if (def == null) { errors.add("$label: sem definição"); return@forEach }
            saveCustom(def.toString()).onSuccess { installed.add(it.name) }.onFailure { errors.add("$label: ${it.message?.lineSequence()?.firstOrNull()}") }
        }
        var n = 0
        val ed = prefs.edit()
        (root["prefs"] as? JsonObject)?.forEach { (k, v) ->
            val pv = v as? JsonPrimitive ?: return@forEach
            if (!(k.startsWith("enabled_") || k.startsWith("agentreq_") || k.startsWith("set_"))) return@forEach
            if (pv.isString) ed.putString(k, pv.content)
            else { val b = pv.content.lowercase(); if (b == "true" || b == "false") ed.putBoolean(k, b == "true") else return@forEach }
            n++
        }
        ed.apply()
        ImportReport(installed, errors, n)
    }

    // ------------------------------------------------------------------ toggles e configurações
    fun enabled(id: String): Boolean = prefs.getBoolean("enabled_$id", true)
    fun setEnabled(id: String, v: Boolean) { prefs.edit().putBoolean("enabled_$id", v).apply() }

    /** "Agent required?": ligado → só agentes com o conector ativado o usam. Padrão: ligado (mais seguro). */
    fun agentRequired(id: String): Boolean = prefs.getBoolean("agentreq_$id", true)
    fun setAgentRequired(id: String, v: Boolean) { prefs.edit().putBoolean("agentreq_$id", v).apply() }

    fun settingValue(c: UiConnector, s: SettingSpec): String = prefs.getString("set_${c.spec.id}_${s.id}", null) ?: s.default
    fun setSetting(c: UiConnector, s: SettingSpec, value: String) { prefs.edit().putString("set_${c.spec.id}_${s.id}", value).apply() }

    fun settings(c: UiConnector): ConnectorSettings =
        ConnectorSettings { key -> c.spec.settings.firstOrNull { it.id == key }?.let { settingValue(c, it) } }

    // ------------------------------------------------------------------ sessão
    fun hasSavedCredentials(id: String): Boolean = vault.has(id)

    suspend fun login(id: String, credentials: Map<String, String>, remember: Boolean): Result<Unit> {
        val c = get(id) ?: return Result.failure(IllegalArgumentException("Conector desconhecido: $id"))
        val missing = c.spec.loginFields.filter { it.required && credentials[it.id].isNullOrBlank() }
        if (missing.isNotEmpty()) return Result.failure(IllegalArgumentException("Preencha: " + missing.joinToString { it.label }))
        val r = c.login(credentials)
        if (r.isSuccess) {
            if (remember) runCatching { vault.save(id, c.persistedCredentials() ?: credentials) }.onFailure { ErrorLog.log("uiconnector", "cofre: ${it.message}", null, id) }
            else vault.clear(id)
        }
        return r
    }

    suspend fun logout(id: String) {
        get(id)?.logout()
        vault.clear(id)
    }

    /** Reconecta com as credenciais do cofre (processo reiniciado). */
    suspend fun ensureLoggedIn(id: String): Result<Unit> {
        val c = get(id) ?: return Result.failure(IllegalArgumentException("Conector desconhecido: $id"))
        if (c.isLoggedIn) return Result.success(Unit)
        val saved = vault.load(id) ?: return Result.failure(IllegalStateException("${c.spec.name}: faça login no conector (Conectores → UI Conectores)."))
        return c.login(saved)
    }

    // ------------------------------------------------------------------ ponte com o agente
    fun usableBy(c: UiConnector, agent: AgentProfile?): Boolean {
        val id = c.spec.id
        if (!enabled(id)) return false
        // Login NÃO é exigido para expor as ferramentas: se a sessão caiu, a chamada responde pedindo login
        // (ou reconecta sozinha com o cofre). Assim o agente nunca "perde" o conector em silêncio.
        val agentOptedIn = agent?.uiConnectorIds?.contains(id) == true
        return agentOptedIn || !agentRequired(id)
    }

    /** Este agente tem algum conector que abre ordens (precisa do avaliador de acurácia)? */
    fun hasTradingTools(agent: AgentProfile?): Boolean =
        connectors.any { c -> usableBy(c, agent) && c.spec.tools.any { it.requiresSignal } }

    /** Definições de ferramenta (formato OpenAI/OpenRouter) dos conectores que este agente pode usar. */
    fun toolDefinitions(agent: AgentProfile?): List<JsonObject> =
        connectors.filter { usableBy(it, agent) }.flatMap { c ->
            c.spec.tools.map { t ->
                buildJsonObject {
                    put("type", "function")
                    put("function", buildJsonObject {
                        put("name", "uic_${c.spec.id}_${t.name}")
                        put("description", "[${c.spec.name}] ${t.description}" + if (t.risky) " (ação sensível: respeita os limites definidos pelo usuário)" else "")
                        put("parameters", buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject {
                                t.params.forEach { p ->
                                    put(p.name, buildJsonObject {
                                        put("type", p.type)
                                        put("description", p.description)
                                        if (p.allowed.isNotEmpty()) put("enum", JsonArray(p.allowed.map { JsonPrimitive(it) }))
                                    })
                                }
                            })
                            put("required", JsonArray(t.params.filter { it.required }.map { JsonPrimitive(it.name) }))
                        })
                    })
                }
            }
        }

    fun isUiConnectorTool(name: String) = name.startsWith("uic_")

    /**
     * Executa a ferramenta `uic_<id>_<tool>` para o agente. Nunca lança: devolve texto (erros viram texto).
     * [agentKey] identifica quem opera (id do agente, ou da sessão) — base do portão e das reservas.
     */
    suspend fun callTool(agent: AgentProfile?, agentKey: String, fullName: String, args: JsonObject): String {
        val (id, tool) = SpecValidator.parseToolName(fullName) ?: return "Ferramenta inválida: $fullName"
        val c = get(id) ?: return "Conector desconhecido: $id"
        if (!usableBy(c, agent)) return "Este agente não tem acesso ao conector ${c.spec.name}. Ative-o no perfil do agente (UI Conectores)."
        val ts = c.spec.tools.firstOrNull { it.name == tool } ?: return "Ferramenta desconhecida: $fullName"
        val strArgs = args.entries.associate { (k, v) -> k to ((v as? JsonPrimitive)?.contentOrNull ?: v.toString()) }
        val out = try {
            ensureLoggedIn(id).getOrThrow()
            if (ts.requiresSignal) {
                // "require_jev": se o conector tem a setting e ela está OFF, pula o filtro TypeSafe Jev,
                // mas mantém a coordenação (uma ordem por vez, reserva do ativo). Sem a setting = sempre exige (padrão seguro).
                val requireJev = c.spec.settings.none { it.id == "require_jev" } || settings(c).bool("require_jev")
                guardedCall(c, tool, agentKey, strArgs, requireJev)
            } else c.callTool(tool, strArgs, settings(c))
        } catch (t: kotlinx.coroutines.CancellationException) {
            throw t
        } catch (t: Throwable) {
            runCatching { ErrorLog.log("uiconnector", "$fullName: ${t.javaClass.simpleName}: ${t.message.orEmpty()}", null, fullName) }
            "ERRO em ${c.spec.name}: ${t.message ?: t.javaClass.simpleName}"
        }
        return Redactor.redact(out, c.secrets())
    }

    /**
     * Portão de uma ordem: (1) uma ordem por vez neste conector (evita estourar "máx. de operações
     * abertas" com agentes concorrentes); (2) exige validação prévia do avaliador para CADA ativo/lado;
     * (3) reserva o ativo para este agente (outro agente não opera o mesmo ativo ao mesmo tempo);
     * (4) se a ordem não saiu (recusada/erro), devolve a aprovação para não gastar uma validação à toa.
     */
    private suspend fun guardedCall(c: UiConnector, tool: String, agentKey: String, args: Map<String, String>, requireValidation: Boolean = true): String {
        val venue = c.spec.id
        val intents = c.orderIntents(tool, args)
        if (intents.isEmpty()) return "RECUSADO: não foi possível identificar o ativo e o lado (compra/venda) da ordem. Confira os argumentos."
        return TradeCoordinator.venueLock(venue).withLock {
            val gate = TradeCoordinator.gate
            val consumed = ArrayList<SignalGate.Approval>()
            fun undo() { consumed.forEach { gate.restore(it) } }
            for (intent in intents) {
                if (requireValidation) {
                    val approval = gate.consume(agentKey, venue, setOf(intent.asset), intent.side)
                    if (approval == null) {
                        undo()
                        return@withLock missingValidation(venue, intent, gate)
                    }
                    consumed.add(approval)
                }
                val holder = TradeCoordinator.claim(venue, intent.asset, agentKey)
                if (holder != null) {
                    undo()
                    return@withLock "RECUSADO: outro agente já está operando ${intent.asset} agora. Aguarde ou escolha outro ativo."
                }
            }
            val out = try {
                c.callTool(tool, args, settings(c))
            } catch (t: Throwable) {
                undo()
                intents.forEach { TradeCoordinator.release(venue, it.asset, agentKey) }
                throw t
            }
            if (out.startsWith("RECUSAD") || out.startsWith("ERRO")) {
                undo()
                intents.forEach { TradeCoordinator.release(venue, it.asset, agentKey) }
            }
            out
        }
    }

    private fun missingValidation(venue: String, intent: OrderIntent, gate: SignalGate): String {
        val dir = if (intent.side == Side.BUY) "buy" else "sell"
        return "RECUSADO: falta a validação TypeSafe Jev para ${intent.side} ${intent.asset}. Antes de qualquer ordem chame typesafe_jev_check com " +
            "signals_json=[{\"id\":\"${intent.asset}\",\"venue\":\"$venue\",\"direction\":\"$dir\",\"data\":\"<dados reais: velas, preço, tendência>\"}] " +
            "e opere somente se vier ✅ OPERAR (acurácia ≥ ${gate.floor}%). A aprovação vale ${gate.ttlSeconds / 60} min e serve para UMA ordem."
    }
}
