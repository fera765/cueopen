package dev.cueopen.app.uiconnector

import android.content.Context
import android.os.SystemClock
import dev.cueopen.app.data.AgentProfile
import dev.cueopen.app.diag.ErrorLog
import dev.cueopen.app.trading.TradeCoordinator
import dev.cueopen.app.uiconnector.custom.CustomConnector
import dev.cueopen.app.uiconnector.custom.CustomConnectorStore
import dev.cueopen.app.uiconnector.custom.CustomDef
import dev.cueopen.app.uiconnector.custom.CustomDefParser
import dev.cueopen.app.uiconnector.iqoption.IqOptionConnector
import dev.cueopen.app.proxy.ProxyScope
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Hub dos UI Connectors: registro, cofre de credenciais, toggles e ponte com o agente.
 *
 * Regras de exposição das ferramentas do agente:
 *  1. o conector está habilitado (login/credenciais não são pré-requisito do schema);
 *  2. se "Agent required?" estiver LIGADO (padrão), só agentes com o conector ativado no perfil usam;
 *     se estiver DESLIGADO, qualquer conversa pode usar.
 * Na chamada, o Hub repete a checagem e tenta login com credenciais lembradas. O texto retornado
 * passa por [Redactor], mas ErrorLog registra a exceção antes dessa redação; não é sanitização universal.
 *
 * Ferramentas `opensOrder` passam pela validação de intent e coordenação local por venue/ativo.
 */
class UiConnectorHub(context: Context) {
    companion object { const val BACKUP_FORMAT = "cueopen-uiconnectors-backup" }

    private val ctx = context.applicationContext
    private val cueApp = ctx as? dev.cueopen.app.CueApp
    private val prefs = ctx.getSharedPreferences("uic_prefs", Context.MODE_PRIVATE)
    val vault = CredentialVault(ctx)
    private val customStore = CustomConnectorStore(ctx)
    private val automaticLoginBackoff = ConnectorRetryBackoff()
    private val automaticLoginLocks = ConcurrentHashMap<String, Mutex>()

    private fun automaticLoginLock(id: String): Mutex = automaticLoginLocks.computeIfAbsent(id) { Mutex() }
    private fun usesAutomaticLoginBackoff(id: String): Boolean = id == "iqoption"

    /** Conectores que vêm com o app. Para adicionar um novo em Kotlin: crie a classe e acrescente aqui. */
    private val builtin: List<UiConnector> = listOf<UiConnector>(IqOptionConnector(object : dev.cueopen.app.uiconnector.iqoption.KvStore {
        private val sp = context.applicationContext.getSharedPreferences("iq_state", Context.MODE_PRIVATE)
        override fun get(key: String): String? = sp.getString(key, null)
        override fun put(key: String, value: String) {
            check(sp.edit().putString(key, value).commit()) { "Não foi possível persistir o estado do conector IQ Option." }
        }
    }, httpClientProvider = { base ->
        cueApp?.proxyHttpClient(ProxyScope.AGENT_API, base)
            ?: throw IOException("CueApp indisponível; recusando conexão IQ Option direta.")
    }), dev.cueopen.app.uiconnector.gmail.GmailConnector(ctx), dev.cueopen.app.uiconnector.polymarket.PolymarketPublicConnector(
        curlProgram = ctx.assets.open("connectors/polymarket_curl.py").bufferedReader().use { it.readText() },
        runInTerminal = { sessionId, command ->
            val app = cueApp ?: throw IOException("CueApp indisponível; a consulta Polymarket precisa do terminal Linux local.")
            val result = app.terminals.get(sessionId).runAgentCommand(command, timeoutSec = 240, background = false)
            when {
                result.timedOut -> throw IOException("A consulta Polymarket excedeu o timeout do terminal Linux.")
                result.exitCode != 0 -> throw IOException(result.output.takeLast(600).ifBlank { "curl terminou com código ${result.exitCode}." })
                else -> result.output
            }
        },
    )).filter { c ->
        val problems = SpecValidator.validate(c.spec)
        if (problems.isNotEmpty()) ErrorLog.log("uiconnector", "spec inválido (${c.spec.id}): " + problems.joinToString("; "))
        problems.isEmpty()
    }

    init {
        val activeSettingKeys = builtin.firstOrNull { it.spec.id == "iqoption" }?.spec?.settings.orEmpty()
            .map { "set_iqoption_${it.id}" }.toSet()
        val obsoleteKeys = prefs.all.keys.filter { it.startsWith("set_iqoption_") && it !in activeSettingKeys }
        if (obsoleteKeys.isNotEmpty()) prefs.edit().also { editor -> obsoleteKeys.forEach { editor.remove(it) } }.apply()
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
            previous.firstOrNull { it is CustomConnector && it.def == def }
                ?: runCatching {
                    CustomConnector(def, bridge = dev.cueopen.app.uiconnector.custom.AndroidHostBridge(def, isGranted = { cap -> capabilityGranted(def.id, cap) }))
                }.getOrNull()
        }
        // instâncias descartadas fecham a conexão em tempo real (WebSocket)
        previous.filter { p -> next.none { it === p } }.forEach { (it as? CustomConnector)?.dispose() }
        return next
    }

    fun customYaml(id: String): String? = customStore.rawYaml(id)

    /** Valida e grava a definição. Se mudou, a sessão/credenciais antigas deixam de valer. */
    suspend fun saveCustom(yamlText: String): Result<CustomDef> {
        val r = customStore.save(yamlText, builtin.map { it.spec.id }.toSet())
        r.onSuccess { def ->
            val previous = custom
            val old = previous.firstOrNull { it.spec.id == def.id } as? CustomConnector
            if (old != null && old.def != def) { old.logout(); vault.clear(def.id) }
            custom = loadCustom(previous)
            // o ambiente descartável de validação do criador com IA não é mais necessário
            runCatching { dev.cueopen.app.uiconnector.custom.AndroidHostBridge.deleteValidation(def.id) }
        }
        return r
    }

    /** Atualiza pela proposta do chat sem limpar o cofre; o bloco login é imutável no sanitizer. */
    private suspend fun saveCustomKeepingCredentials(yamlText: String): Result<CustomDef> {
        val result = customStore.save(yamlText, builtin.map { it.spec.id }.toSet())
        if (result.isFailure) return result
        val def = result.getOrNull() ?: return Result.failure(IllegalStateException("A definição não foi salva."))
        val previous = custom
        val old = previous.firstOrNull { it.spec.id == def.id } as? CustomConnector
        if (old != null && old.def != def) old.logout()
        custom = loadCustom(previous)
        runCatching { dev.cueopen.app.uiconnector.custom.AndroidHostBridge.deleteValidation(def.id) }
        return Result.success(def)
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
            val raw = customStore.rawYaml(c.spec.id) ?: return@mapNotNull null
            val el = runCatching { dev.cueopen.app.uiconnector.custom.ConnectorYamlCodec.parse(CustomDefParser.stripFences(raw)) }.getOrNull() ?: return@mapNotNull null
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
        if ((root["format"] as? JsonPrimitive)?.contentOrNull != BACKUP_FORMAT) throw IllegalArgumentException("Arquivo inválido: não é um backup de UI Conectores do Fluwy Bot.")
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

    // ------------------------------------------------------------------ capacidades do aparelho (terminal/files/browser)
    /** Capacidades DECLARADAS por um conector criado pelo usuário (vazio para built-in/HTTP puro). */
    fun declaredCapabilities(id: String): Set<String> = (get(id) as? CustomConnector)?.def?.capabilities ?: emptySet()

    /** O usuário autorizou esta capacidade para este conector? Padrão: NÃO (precisa autorizar na tela do conector). */
    fun capabilityGranted(id: String, cap: String): Boolean = prefs.getBoolean("cap_${id}_$cap", false)
    fun setCapabilityGranted(id: String, cap: String, v: Boolean) { prefs.edit().putBoolean("cap_${id}_$cap", v).apply() }

    fun settingValue(c: UiConnector, s: SettingSpec): String = prefs.getString("set_${c.spec.id}_${s.id}", null) ?: s.default
    fun setSetting(c: UiConnector, s: SettingSpec, value: String) { prefs.edit().putString("set_${c.spec.id}_${s.id}", value).apply() }

    fun settings(c: UiConnector): ConnectorSettings =
        ConnectorSettings { key -> c.spec.settings.firstOrNull { it.id == key }?.let { settingValue(c, it) } }

    data class EditableSetting(
        val id: String,
        val label: String,
        val description: String,
        val type: SettingType,
        val value: String,
        val risky: Boolean,
    )

    data class EditableConfiguration(
        val id: String,
        val name: String,
        val description: String,
        val enabled: Boolean,
        val agentRequired: Boolean,
        val settings: List<EditableSetting>,
        val fingerprint: String,
    )

    fun isSecretSetting(setting: SettingSpec): Boolean =
        Regex("(?i)(secret|token|password|passwd|api.?key|credential|senha|chave|segredo|auth)")
            .containsMatchIn("${setting.id} ${setting.label} ${setting.description}")

    /** Só expõe à IA valores de configuração não secretos; capacidades e credenciais ficam fora do escopo. */
    fun editableConfiguration(id: String): EditableConfiguration? {
        val c = get(id) ?: return null
        val editable = c.spec.settings.filterNot(::isSecretSetting).mapNotNull { s ->
            val value = settingValue(c, s)
            val suspiciousMetadata = listOf(s.id, s.label, s.description).any {
                dev.cueopen.app.uiconnector.custom.ConnectorDefinitionEdit.containsSecretLikeValue(it)
            }
            if (suspiciousMetadata || dev.cueopen.app.uiconnector.custom.ConnectorDefinitionEdit.containsSecretLikeValue(value)) null
            else EditableSetting(s.id, s.label, s.description, s.type, value, s.risky)
        }
        return EditableConfiguration(id, c.spec.name, c.spec.description, enabled(id), agentRequired(id), editable, configurationFingerprint(id))
    }

    /** Fingerprint local: inclui a definição inteira, mas apenas o hash é exposto fora do Hub. */
    fun configurationFingerprint(id: String): String {
        val c = get(id) ?: return ""
        val material = buildString {
            append(c.spec.id).append('\n').append(c.spec.name).append('\n').append(c.spec.version).append('\n')
            append(enabled(id)).append('\n').append(agentRequired(id)).append('\n')
            c.spec.settings.sortedBy { it.id }.forEach { s ->
                append(s.id).append('|').append(s.label).append('|').append(s.type.name).append('|').append(s.default).append('|').append(s.risky)
                    .append('=').append(settingValue(c, s)).append('\n')
            }
            append(customYaml(id).orEmpty())
        }
        return MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    /** Aplica apenas campos editáveis após comparar o estado que originou o card; nada de rede/login é executado. */
    suspend fun applyEditableConfiguration(
        id: String,
        expectedFingerprint: String,
        enabledValue: Boolean?,
        agentRequiredValue: Boolean?,
        settingChanges: Map<String, String>,
        definitionYaml: String? = null,
        sessionId: String? = null,
    ): Result<Unit> = try {
        val current = get(id) ?: throw IllegalArgumentException("Este conector não está mais instalado.")
        if (expectedFingerprint.isBlank() || configurationFingerprint(id) != expectedFingerprint)
            throw IllegalStateException("A configuração do conector mudou desde a proposta. Reabra o chat de edição para revisar o estado atual.")

        var updatedDefinition: String? = null
        var settingsSpecs: List<SettingSpec> = current.spec.settings
        if (!definitionYaml.isNullOrBlank()) {
            if (!isCustom(id)) throw IllegalArgumentException("A definição compilada deste conector não pode ser alterada pelo chat.")
            val original = customYaml(id) ?: throw IllegalStateException("A definição customizada não está disponível.")
            val chatId = sessionId?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("A sessão da proposta não está disponível.")
            val restored = try {
                dev.cueopen.app.uiconnector.custom.ConnectorDefinitionEdit.restore(original, definitionYaml, chatId)
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (_: Exception) {
                throw IllegalArgumentException("A definição proposta não passou nas validações de segurança.")
            }
            val originalTree = try {
                dev.cueopen.app.uiconnector.custom.ConnectorYamlCodec.parse(original)
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (_: Exception) {
                throw IllegalStateException("A definição instalada não pôde ser lida com segurança.")
            }
            val updatedTree = try {
                dev.cueopen.app.uiconnector.custom.ConnectorYamlCodec.parse(restored)
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (_: Exception) {
                throw IllegalArgumentException("A definição proposta não pôde ser interpretada com segurança.")
            }
            if (originalTree != updatedTree) {
                val parsed = dev.cueopen.app.uiconnector.custom.CustomDefParser.parse(restored)
                val newDef = parsed.def ?: throw IllegalArgumentException("A definição proposta não passou no schema do conector.")
                if (newDef.id != id) throw IllegalArgumentException("A definição não pode mudar o id do conector selecionado.")
                val oldDef = dev.cueopen.app.uiconnector.custom.CustomDefParser.parse(original).def
                    ?: throw IllegalStateException("A definição instalada não passou no parser.")
                if (newDef.login != oldDef.login || newDef.headers != oldDef.headers || newDef.capabilities != oldDef.capabilities || newDef.setup != oldDef.setup)
                    throw IllegalArgumentException("Login, cabeçalhos, permissões e setup não podem ser alterados pelo chat.")
                settingsSpecs = newDef.settings
                updatedDefinition = restored
            }
        }
        if (enabledValue == null && agentRequiredValue == null && settingChanges.isEmpty() && updatedDefinition == null)
            throw IllegalArgumentException("A proposta não contém alterações.")

        val allowed = settingsSpecs.filterNot(::isSecretSetting).filter { spec ->
            val previous = current.spec.settings.firstOrNull { it.id == spec.id }
            val currentValue = previous?.let { settingValue(current, it) } ?: spec.default
            !dev.cueopen.app.uiconnector.custom.ConnectorDefinitionEdit.containsSecretLikeValue(currentValue) &&
                !dev.cueopen.app.uiconnector.custom.ConnectorDefinitionEdit.containsSecretLikeValue(spec.default)
        }.associateBy { it.id }
        val validated = settingChanges.map { (key, value) ->
            val spec = allowed[key] ?: throw IllegalArgumentException("A configuração '$key' não pode ser alterada por este chat.")
            if (value.length > 4096) throw IllegalArgumentException("O valor de '${spec.label}' é longo demais.")
            if (dev.cueopen.app.uiconnector.custom.ConnectorDefinitionEdit.containsSecretLikeValue(value))
                throw IllegalArgumentException("A proposta contém um valor que parece segredo; use o formulário seguro do conector para credenciais.")
            when (spec.type) {
                SettingType.SWITCH -> if (value.lowercase() !in setOf("true", "false")) throw IllegalArgumentException("'${spec.label}' precisa ser true ou false.")
                SettingType.NUMBER -> if (value.replace(',', '.').toDoubleOrNull()?.isFinite() != true) throw IllegalArgumentException("'${spec.label}' precisa ser um número válido.")
                SettingType.TEXT -> Unit
            }
            key to value
        }

        if (updatedDefinition != null) {
            val parsed = dev.cueopen.app.uiconnector.custom.CustomDefParser.parse(updatedDefinition)
            val def = parsed.def ?: throw IllegalArgumentException("A definição não passou no schema.")
            val validation = dev.cueopen.app.uiconnector.custom.ConnectorDryRunValidator.validate(updatedDefinition, def, settingsInput = settingChanges)
            if (!validation.passed) throw IllegalArgumentException("A validação offline do preview não passou. Nenhum acesso de rede, ação real ou credencial foi usado.")
            saveCustomKeepingCredentials(updatedDefinition).getOrElse {
                throw IllegalStateException("Não foi possível salvar a atualização do conector.")
            }
        }

        val editor = prefs.edit()
        enabledValue?.let { editor.putBoolean("enabled_$id", it) }
        agentRequiredValue?.let { editor.putBoolean("agentreq_$id", it) }
        validated.forEach { (key, value) -> editor.putString("set_${id}_$key", value) }
        check(editor.commit()) { "Não foi possível salvar a atualização do conector." }
        Result.success(Unit)
    } catch (cancel: kotlinx.coroutines.CancellationException) {
        throw cancel
    } catch (t: Throwable) {
        Result.failure(t)
    }

    // ------------------------------------------------------------------ sessão
    fun hasSavedCredentials(id: String): Boolean = vault.has(id)

    suspend fun login(id: String, credentials: Map<String, String>, remember: Boolean): Result<Unit> {
        val c = get(id) ?: return Result.failure(IllegalArgumentException("Conector desconhecido: $id"))
        val missing = c.spec.loginFields.filter { it.required && credentials[it.id].isNullOrBlank() }
        if (missing.isNotEmpty()) return Result.failure(IllegalArgumentException("Preencha: " + missing.joinToString { it.label }))
        val r = automaticLoginLock(id).withLock {
            if (usesAutomaticLoginBackoff(id)) automaticLoginBackoff.reset(id) // login iniciado pelo usuário ignora o cooldown automático
            try {
                c.login(credentials)
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (t: Throwable) {
                Result.failure(t)
            }
        }
        if (r.isSuccess) {
            if (usesAutomaticLoginBackoff(id)) automaticLoginBackoff.reset(id)
            if (remember) runCatching { vault.save(id, c.persistedCredentials() ?: credentials) }.onFailure { ErrorLog.log("uiconnector", "cofre: ${it.message}", null, id) }
            else vault.clear(id)
        } else if (usesAutomaticLoginBackoff(id)) {
            automaticLoginBackoff.recordFailure(id, SystemClock.elapsedRealtime(), r.exceptionOrNull()?.message.orEmpty())
        }
        return r
    }

    suspend fun logout(id: String) {
        get(id)?.logout()
        vault.clear(id)
        automaticLoginBackoff.reset(id)
    }

    /** Reconecta com as credenciais do cofre (processo reiniciado). */
    suspend fun ensureLoggedIn(id: String): Result<Unit> {
        val c = get(id) ?: return Result.failure(IllegalArgumentException("Conector desconhecido: $id"))
        if (c.isLoggedIn) return Result.success(Unit)
        val saved = vault.load(id) ?: return Result.failure(IllegalStateException("${c.spec.name}: faça login no conector (Conectores → UI Conectores)."))
        return automaticLoginLock(id).withLock {
            if (c.isLoggedIn) return@withLock Result.success(Unit)
            val now = SystemClock.elapsedRealtime()
            val remainingMs = if (usesAutomaticLoginBackoff(id)) automaticLoginBackoff.remainingMs(id, now) else 0L
            if (remainingMs > 0L) {
                val waitSec = (remainingMs + 999L) / 1_000L
                val lastReason = automaticLoginBackoff.state(id)?.reason.orEmpty().take(120)
                return@withLock Result.failure(ConnectorRetryDeferredException(
                    "${c.spec.name}: tentativa automática pausada por falha recente; nova tentativa em ${waitSec}s." +
                        if (lastReason.isBlank()) "" else " Último erro: $lastReason"
                ))
            }
            val result = try {
                c.login(saved)
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (t: Throwable) {
                Result.failure(t)
            }
            if (usesAutomaticLoginBackoff(id)) {
                if (result.isSuccess) automaticLoginBackoff.reset(id)
                else automaticLoginBackoff.recordFailure(id, SystemClock.elapsedRealtime(), result.exceptionOrNull()?.message.orEmpty())
            }
            result
        }
    }

    // ------------------------------------------------------------------ ponte com o agente
    fun usableBy(c: UiConnector, agent: AgentProfile?): Boolean {
        val id = c.spec.id
        if (!enabled(id)) return false
        if (c is RequiresAgentTerminal && agent?.useTerminal != true) return false
        // Login NÃO é exigido para expor as ferramentas: se a sessão caiu, a chamada responde pedindo login
        // (ou reconecta sozinha com o cofre). Assim o agente nunca "perde" o conector em silêncio.
        val agentOptedIn = agent?.uiConnectorIds?.contains(id) == true
        return agentOptedIn || !agentRequired(id)
    }

    /** Este agente tem algum conector que abre ordens? */
    fun hasOrderTools(agent: AgentProfile?): Boolean =
        connectors.any { c -> usableBy(c, agent) && c.spec.tools.any { it.opensOrder } }

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
     * [agentKey] identifica quem opera (id do agente, ou da sessão) — base da coordenação e das reservas.
     */
    suspend fun callTool(
        agent: AgentProfile?,
        agentKey: String,
        fullName: String,
        args: JsonObject,
        context: UiConnectorCallContext = UiConnectorCallContext(),
    ): String {
        val (id, tool) = SpecValidator.parseToolName(fullName) ?: return "Ferramenta inválida: $fullName"
        val c = get(id) ?: return "Conector desconhecido: $id"
        if (!usableBy(c, agent)) return "Este agente não tem acesso ao conector ${c.spec.name}. Ative-o no perfil do agente (UI Conectores)."
        val ts = c.spec.tools.firstOrNull { it.name == tool } ?: return "Ferramenta desconhecida: $fullName"
        val strArgs = args.entries.associate { (k, v) -> k to ((v as? JsonPrimitive)?.contentOrNull ?: v.toString()) }
        val out = try {
            ensureLoggedIn(id).getOrThrow()
            if (ts.opensOrder) {
                guardedCall(c, tool, agentKey, strArgs)
            } else if (c is CustomConnector) c.callTool(tool, strArgs, settings(c), delegatedModel = agent?.model)
            else c.callTool(tool, strArgs, settings(c), context)
        } catch (t: kotlinx.coroutines.CancellationException) {
            throw t
        } catch (t: Throwable) {
            if (t !is ConnectorRetryDeferredException)
                runCatching { ErrorLog.log("uiconnector", "$fullName: ${t.javaClass.simpleName}: ${t.message.orEmpty()}", null, fullName) }
            "ERRO em ${c.spec.name}: ${t.message ?: t.javaClass.simpleName}"
        }
        return Redactor.redact(out, c.secrets())
    }

    /**
     * Execução por um nó de Fluxo (sem agente). Ações que abrem ordens e conectores que exigem o terminal de um
     * agente não rodam em automação. Nunca lança: devolve texto; erros começam com "ERRO"/"RECUSADO".
     */
    suspend fun callForWorkflow(id: String, tool: String, args: JsonObject): String {
        val c = get(id) ?: return "ERRO: conector desconhecido: $id"
        if (!enabled(id)) return "ERRO: o conector ${c.spec.name} está desligado (Conectores)."
        val ts = c.spec.tools.firstOrNull { it.name == tool } ?: return "ERRO: ${c.spec.name} não tem a ação \"$tool\"."
        if (ts.opensOrder) return "RECUSADO: ações que abrem ordens não rodam em fluxos automáticos."
        if (c is RequiresAgentTerminal) return "RECUSADO: ${c.spec.name} precisa do terminal de um agente; use um nó Agente IA."
        val strArgs = args.entries.associate { (k, v) -> k to ((v as? JsonPrimitive)?.contentOrNull ?: v.toString()) }
        val out = try {
            ensureLoggedIn(id).getOrThrow()
            if (c is CustomConnector) c.callTool(tool, strArgs, settings(c)) else c.callTool(tool, strArgs, settings(c), UiConnectorCallContext())
        } catch (t: kotlinx.coroutines.CancellationException) { throw t
        } catch (t: Throwable) { "ERRO em ${c.spec.name}: ${t.message ?: t.javaClass.simpleName}" }
        return Redactor.redact(out, c.secrets())
    }

    /** Serializa ordens por venue, identifica intent e impede dois agentes de operar o mesmo ativo juntos. */
    private suspend fun guardedCall(c: UiConnector, tool: String, agentKey: String, args: Map<String, String>): String {
        val venue = c.spec.id
        val intents = c.orderIntents(tool, args)
        if (intents.isEmpty()) return "RECUSADO: não foi possível identificar o ativo e o lado (compra/venda) da ordem. Confira os argumentos."
        return TradeCoordinator.venueLock(venue).withLock {
            val reserved = ArrayList<OrderIntent>()
            fun releaseClaims() = reserved.forEach { TradeCoordinator.release(venue, it.asset, agentKey) }
            for (intent in intents) {
                val holder = TradeCoordinator.claim(venue, intent.asset, agentKey)
                if (holder != null) {
                    releaseClaims()
                    return@withLock "RECUSADO: outro agente já está operando ${intent.asset} agora. Aguarde ou escolha outro ativo."
                }
                reserved.add(intent)
            }
            val out = try {
                c.callTool(tool, args, settings(c))
            } catch (t: Throwable) {
                releaseClaims()
                throw t
            }
            if (out.startsWith("RECUSAD") || out.startsWith("ERRO")) releaseClaims()
            out
        }
    }
}
