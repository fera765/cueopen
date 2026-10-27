package dev.cueopen.app.uiconnector

import android.content.Context
import dev.cueopen.app.data.AgentProfile
import dev.cueopen.app.diag.ErrorLog
import dev.cueopen.app.uiconnector.iqoption.IqOptionConnector
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
 */
class UiConnectorHub(context: Context) {
    private val ctx = context.applicationContext
    private val prefs = ctx.getSharedPreferences("uic_prefs", Context.MODE_PRIVATE)
    val vault = CredentialVault(ctx)

    /** Para adicionar um conector novo ao app: crie a classe e acrescente aqui. */
    val connectors: List<UiConnector> = listOf<UiConnector>(IqOptionConnector()).also { list ->
        list.forEach { c ->
            val problems = SpecValidator.validate(c.spec)
            if (problems.isNotEmpty()) ErrorLog.log("uiconnector", "spec inválido (${c.spec.id}): " + problems.joinToString("; "))
        }
    }.filter { SpecValidator.validate(it.spec).isEmpty() }

    fun get(id: String): UiConnector? = connectors.firstOrNull { it.spec.id == id }

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
            if (remember) runCatching { vault.save(id, credentials) }.onFailure { ErrorLog.log("uiconnector", "cofre: ${it.message}", null, id) }
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
        if (!c.isLoggedIn && !vault.has(id)) return false
        val agentOptedIn = agent?.uiConnectorIds?.contains(id) == true
        return agentOptedIn || !agentRequired(id)
    }

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

    /** Executa a ferramenta `uic_<id>_<tool>` para o agente. Nunca lança: devolve texto (erros viram texto). */
    suspend fun callTool(agent: AgentProfile?, fullName: String, args: JsonObject): String {
        val (id, tool) = SpecValidator.parseToolName(fullName) ?: return "Ferramenta inválida: $fullName"
        val c = get(id) ?: return "Conector desconhecido: $id"
        if (!usableBy(c, agent)) return "Este agente não tem acesso ao conector ${c.spec.name}. Ative-o no perfil do agente (UI Conectores)."
        if (c.spec.tools.none { it.name == tool }) return "Ferramenta desconhecida: $fullName"
        val strArgs = args.entries.associate { (k, v) -> k to ((v as? JsonPrimitive)?.contentOrNull ?: v.toString()) }
        val out = try {
            ensureLoggedIn(id).getOrThrow()
            c.callTool(tool, strArgs, settings(c))
        } catch (t: kotlinx.coroutines.CancellationException) {
            throw t
        } catch (t: Throwable) {
            runCatching { ErrorLog.log("uiconnector", "$fullName: ${t.javaClass.simpleName}: ${t.message.orEmpty()}", null, fullName) }
            "ERRO em ${c.spec.name}: ${t.message ?: t.javaClass.simpleName}"
        }
        return Redactor.redact(out, c.secrets())
    }
}
