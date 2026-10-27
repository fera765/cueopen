package dev.cueopen.app.uiconnector

/*
 * CueOpen UI Connector SDK — contrato público (open source).
 *
 * Um "UI Connector" é um conector com INTERFACE PRÓPRIA dentro do app: tela de login, painéis com
 * dados ao vivo e ações — e, ao mesmo tempo, um conjunto de FERRAMENTAS que o agente pode chamar.
 * Para criar um novo conector (Gmail, Binance, B3, Nubank…) basta:
 *   1. descrever o conector num [UiConnectorSpec] (campos de login, configurações, ferramentas);
 *   2. implementar [UiConnector] (login/logout, snapshot() → [UiBlock]s, onAction, callTool);
 *   3. registrar a classe em UiConnectorHub.connectors.
 * O app cuida do resto: formulário de login, cofre cifrado das credenciais, renderização dos
 * painéis, toggles "Agent required?" e por-agente, exposição das ferramentas ao LLM, limites de
 * segurança, redação de segredos e registro de erros. Este arquivo é puro Kotlin (sem Android)
 * para ser validado por testes de unidade na JVM.
 */

enum class FieldType { TEXT, EMAIL, PASSWORD, NUMBER }

/** Campo do formulário de login. Campos PASSWORD são sempre tratados como segredo. */
data class LoginField(
    val id: String,
    val label: String,
    val type: FieldType = FieldType.TEXT,
    val required: Boolean = true,
    val hint: String = "",
)

enum class SettingType { SWITCH, NUMBER, TEXT }

/** Configuração própria do conector, mostrada na tela do conector (ex.: limite por operação). */
data class SettingSpec(
    val id: String,
    val label: String,
    val description: String,
    val type: SettingType,
    val default: String,
    /** Marca configurações que ampliam o poder do agente (mostradas com aviso). */
    val risky: Boolean = false,
)

/** Parâmetro de uma ferramenta. [type]: string | integer | number | boolean. */
data class ParamSpec(
    val name: String,
    val description: String,
    val type: String = "string",
    val required: Boolean = true,
    val allowed: List<String> = emptyList(),
)

/** Ferramenta exposta ao agente como `uic_<connectorId>_<name>`. */
data class ToolSpec(
    val name: String,
    val description: String,
    val params: List<ParamSpec> = emptyList(),
    /** Ferramenta que move dinheiro/dados sensíveis; sempre passa pelos limites do conector. */
    val risky: Boolean = false,
    /**
     * Ferramenta que ABRE uma ordem de compra/venda. O hub exige uma validação prévia do avaliador
     * (typesafe_jev_check) para o ativo e o lado antes de executá-la. Exige [risky] e que o
     * conector implemente [UiConnector.orderIntents].
     */
    val requiresSignal: Boolean = false,
)

/** Ativo + lado de uma ordem que uma ferramenta vai abrir (para o portão de validação). */
data class OrderIntent(val asset: String, val side: dev.cueopen.app.trading.Side)

data class UiConnectorSpec(
    /** Só [a-z0-9], começa com letra, 2–20 chars (vira parte do nome das ferramentas). */
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val loginFields: List<LoginField>,
    val settings: List<SettingSpec> = emptyList(),
    val tools: List<ToolSpec> = emptyList(),
    val accent: Long = 0xFFFF6B3D,
)

enum class Tone { NEUTRAL, GOOD, BAD, WARN }

data class Stat(val label: String, val value: String, val tone: Tone = Tone.NEUTRAL)

/** Blocos de UI que o conector devolve; o app renderiza todos de forma genérica e mobile-first. */
sealed interface UiBlock {
    data class Header(val title: String, val subtitle: String = "", val badge: String = "", val tone: Tone = Tone.NEUTRAL) : UiBlock
    data class Stats(val items: List<Stat>) : UiBlock
    data class Table(val title: String, val columns: List<String>, val rows: List<List<String>>, val empty: String = "Nada por aqui.") : UiBlock
    /** Seletor segmentado (ex.: conta Demo/Real). Ao tocar, chama onAction(actionId, {"value": valor}). */
    data class Segmented(
        val actionId: String,
        val title: String,
        /** pares valor → rótulo */
        val options: List<Pair<String, String>>,
        val selected: String,
        /** valores que pedem confirmação antes de aplicar */
        val confirmValues: Set<String> = emptySet(),
        val confirmText: String = "Confirmar a mudança?",
    ) : UiBlock
    data class Button(val actionId: String, val label: String, val risky: Boolean = false, val args: Map<String, String> = emptyMap()) : UiBlock
    data class Note(val text: String, val tone: Tone = Tone.NEUTRAL) : UiBlock
}

/** Leitura tipada das configurações do conector (valores do usuário ou padrão do spec). */
class ConnectorSettings(private val lookup: (String) -> String?) {
    fun text(id: String): String = lookup(id).orEmpty()
    fun bool(id: String): Boolean = lookup(id)?.equals("true", ignoreCase = true) == true
    fun num(id: String, fallback: Double = 0.0): Double = lookup(id)?.replace(',', '.')?.toDoubleOrNull() ?: fallback
}

/** Contrato que cada conector implementa. Todas as funções suspend rodam fora da thread principal. */
interface UiConnector {
    val spec: UiConnectorSpec
    val isLoggedIn: Boolean

    suspend fun login(credentials: Map<String, String>): Result<Unit>
    suspend fun logout()

    /** Estado atual para a UI (consultado periodicamente enquanto a tela está aberta). */
    suspend fun snapshot(settings: ConnectorSettings): List<UiBlock>

    /** Ação disparada pelo USUÁRIO na UI (botões/segmentados). Sem os limites do agente. */
    suspend fun onAction(actionId: String, args: Map<String, String>, settings: ConnectorSettings): Result<String>

    /** Ferramenta chamada pelo AGENTE: sempre sujeita aos limites de segurança do conector. */
    suspend fun callTool(name: String, args: Map<String, String>, settings: ConnectorSettings): String

    /**
     * Para ferramentas com `requiresSignal`: quais ordens (ativo + lado) a chamada abriria.
     * Lista vazia = argumentos inválidos (o hub recusa). Padrão: nenhuma.
     */
    fun orderIntents(tool: String, args: Map<String, String>): List<OrderIntent> = emptyList()

    /** Valores sensíveis que NUNCA podem aparecer em saídas para o LLM (são redigidos pelo hub). */
    fun secrets(): List<String>
}

/**
 * Valida um [UiConnectorSpec]. É o "validador do UI Kit": roda nos testes e no registro do conector,
 * recusando specs que quebrariam o app ou vazariam segredo (ex.: login sem campo de senha marcado).
 */
object SpecValidator {
    private val ID = Regex("^[a-z][a-z0-9]{1,19}$")
    private val NAME = Regex("^[a-z][a-z0-9_]{1,31}$")
    private val FIELD = Regex("^[a-z][a-zA-Z0-9_]{0,31}$")
    private val TYPES = setOf("string", "integer", "number", "boolean")
    /** Ids reservados pela plataforma (toggles do hub). */
    private val RESERVED = setOf("enabled", "agentrequired")

    fun validate(s: UiConnectorSpec): List<String> {
        val p = mutableListOf<String>()
        if (!ID.matches(s.id)) p += "id '${s.id}' inválido: use [a-z0-9], começando com letra (2–20)."
        if (s.name.isBlank()) p += "name vazio."
        if (s.description.isBlank()) p += "description vazia."
        if (s.version.isBlank()) p += "version vazia."

        if (s.loginFields.isEmpty()) p += "loginFields vazio."
        s.loginFields.groupBy { it.id }.filter { it.value.size > 1 }.keys.forEach { p += "campo de login duplicado: $it" }
        s.loginFields.forEach {
            if (!FIELD.matches(it.id)) p += "campo de login '${it.id}' com id inválido."
            if (it.label.isBlank()) p += "campo de login '${it.id}' sem label."
        }
        if (s.loginFields.none { it.type == FieldType.PASSWORD }) p += "loginFields precisa de ao menos um campo PASSWORD (segredo)."

        s.settings.groupBy { it.id }.filter { it.value.size > 1 }.keys.forEach { p += "setting duplicada: $it" }
        s.settings.forEach {
            if (!FIELD.matches(it.id)) p += "setting '${it.id}' com id inválido."
            if (it.id.lowercase() in RESERVED) p += "setting '${it.id}' usa id reservado pela plataforma."
            if (it.label.isBlank()) p += "setting '${it.id}' sem label."
            when (it.type) {
                SettingType.SWITCH -> if (it.default.lowercase() !in setOf("true", "false")) p += "setting '${it.id}': default de SWITCH deve ser true/false."
                SettingType.NUMBER -> if (it.default.replace(',', '.').toDoubleOrNull() == null) p += "setting '${it.id}': default de NUMBER inválido."
                SettingType.TEXT -> {}
            }
        }

        s.tools.groupBy { it.name }.filter { it.value.size > 1 }.keys.forEach { p += "ferramenta duplicada: $it" }
        s.tools.forEach { t ->
            if (!NAME.matches(t.name)) p += "ferramenta '${t.name}' com nome inválido."
            if ("uic_${s.id}_${t.name}".length > 64) p += "ferramenta '${t.name}': nome completo excede 64 caracteres."
            if (t.description.isBlank()) p += "ferramenta '${t.name}' sem description (o LLM precisa dela)."
            if (t.requiresSignal && !t.risky) p += "ferramenta '${t.name}': requiresSignal exige risky = true."
            t.params.groupBy { it.name }.filter { it.value.size > 1 }.keys.forEach { p += "ferramenta '${t.name}': parâmetro duplicado $it" }
            t.params.forEach { a ->
                if (a.name.isBlank()) p += "ferramenta '${t.name}': parâmetro sem nome."
                if (a.type !in TYPES) p += "ferramenta '${t.name}': parâmetro '${a.name}' com tipo '${a.type}' inválido."
                if (a.description.isBlank()) p += "ferramenta '${t.name}': parâmetro '${a.name}' sem description."
            }
        }
        return p
    }

    /** Divide `uic_<id>_<tool>` em (id, tool). Ids não têm '_' , então o primeiro '_' após o prefixo separa. */
    fun parseToolName(full: String): Pair<String, String>? {
        if (!full.startsWith("uic_")) return null
        val rest = full.removePrefix("uic_")
        val i = rest.indexOf('_')
        if (i <= 0 || i == rest.lastIndex) return null
        return rest.substring(0, i) to rest.substring(i + 1)
    }
}

/** Remove segredos de um texto antes de ele chegar ao LLM/log. */
object Redactor {
    fun redact(text: String, secrets: List<String>): String {
        var out = text
        secrets.filter { it.length >= 4 }.forEach { out = out.replace(it, "•••") }
        return out
    }
}
