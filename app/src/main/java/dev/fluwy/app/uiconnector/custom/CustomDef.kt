package dev.fluwy.app.uiconnector.custom

import dev.fluwy.app.trading.Sides
import dev.fluwy.app.uiconnector.FieldType
import dev.fluwy.app.uiconnector.LoginField
import dev.fluwy.app.uiconnector.ParamSpec
import dev.fluwy.app.uiconnector.SettingSpec
import dev.fluwy.app.uiconnector.SettingType
import dev.fluwy.app.uiconnector.SpecValidator
import dev.fluwy.app.uiconnector.Tone
import dev.fluwy.app.uiconnector.ToolSpec
import dev.fluwy.app.uiconnector.UiConnectorSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * Definição declarativa (YAML) de um conector criado pelo usuário: HTTP, realtime, compute e capacidades
 * locais explicitamente autorizadas. O parser e os guardrails validam a definição antes da instalação.
 * Referência completa em docs/ui-connector-sdk.md.
 */

data class ReqDef(
    val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>, val body: JsonElement?,
    /** URL absoluta (https) num dos hosts declarados; quando presente substitui baseUrl + path. */
    val url: String = "",
    /** Corpo application/x-www-form-urlencoded (ex.: OAuth client_credentials). */
    val form: Map<String, String> = emptyMap(),
)
data class ListDef(val rows: String, val item: String, val max: Int)
data class ColDef(val title: String, val value: String)
data class StatDef(val label: String, val value: String, val tone: Tone)
data class PanelDef(
    val type: String, val title: String, val subtitle: String, val badge: String, val tone: Tone,
    val request: ReqDef?, val stats: List<StatDef>, val rows: String, val columns: List<ColDef>, val text: String, val empty: String,
    /** Coleção do tempo real usada como dados ({"items":[…]}, mais recentes primeiro) em vez de request. */
    val source: String = "",
    /** Cálculos (nome → expressão) avaliados sobre a resposta; lidos como {{c.nome}} e em rows "c.nome". */
    val compute: List<Pair<String, Compute.Node>> = emptyList(),
)
data class ActionDef(val id: String, val label: String, val risky: Boolean, val request: ReqDef, val message: String)
data class LimitDef(val param: String, val maxSetting: String)
data class IntentDef(val asset: String, val direction: String)

/** Arquivo gravado (de forma segura, sem shell) no workspace do conector antes de rodar um comando. */
data class FileWrite(val path: String, val content: String)
/**
 * Passo de TERMINAL: grava [files] no workspace (opcional, sem heredoc — à prova de erro) e então roda
 * [run] (modelo shell-seguro) no sandbox do conector, com tempo-limite.
 */
data class ShellStep(val run: String, val timeoutSec: Int, val files: List<FileWrite> = emptyList())
/** Passo de ARQUIVO no workspace do conector; replace troca oldText por newText sem shell. */
data class FileStep(
    val op: String,
    val path: String,
    val content: String?,
    val oldText: String? = null,
    val newText: String? = null,
    val allowMultiple: Boolean = false,
)
/** Passo de BROWSER: abre [url] (host declarado) e devolve o texto renderizado da página. */
data class FetchStep(val url: String, val waitSec: Int)

/** Verificações pós-condición para saídas de texto. VSL mede duração por palavras, não por promessa de exatidão. */
data class VslOutputPolicy(
    val durationParam: String,
    val languageParam: String,
    val guardrailsParam: String,
    val wordsPerMinute: Int,
    val tolerance: Double,
    val requiredSections: List<String>,
    val forbiddenTermsParam: String = "",
)

/** Instrução delegada ao agente LLM; maxSteps nulo é reservado aos agentes do Fluwy Open Dev. */
data class AgentTaskStep(val task: String, val maxSteps: Int?, val timeoutSec: Int)

data class ToolDef(
    val name: String, val description: String, val params: List<ParamSpec>, val risky: Boolean, val opensOrder: Boolean,
    val intent: IntentDef?, val limits: List<LimitDef>, val request: ReqDef?, val response: String,
    val list: ListDef? = null,
    /** Envio de mensagem pelo tempo real (com espera opcional da resposta). */
    val send: SendDef? = null,
    /** Coleção do tempo real lida pela ferramenta. */
    val source: String = "",
    val compute: List<Pair<String, Compute.Node>> = emptyList(),
    /** Capacidades do aparelho (exigem `capabilities` declarada + autorização do usuário). */
    val shell: ShellStep? = null,
    val file: FileStep? = null,
    val fetch: FetchStep? = null,
    val vslOutputPolicy: VslOutputPolicy? = null,
    val agent: AgentTaskStep? = null,
)
data class LoginDef(val fields: List<LoginField>, val request: ReqDef?, val extract: Map<String, String>, val verify: ReqDef?)

data class CustomDef(
    val id: String, val name: String, val description: String, val version: String, val accent: Long,
    val baseUrl: String, val host: String, val headers: Map<String, String>, val login: LoginDef,
    val settings: List<SettingSpec>, val panels: List<PanelDef>, val actions: List<ActionDef>, val tools: List<ToolDef>,
    val allowedHosts: List<String> = emptyList(),
    val realtime: RtDef? = null,
    /** Capacidades do aparelho que o conector usa (terminal | files | browser). Exigem autorização do usuário. */
    val capabilities: Set<String> = emptySet(),
    /** Preparação única do ambiente (instalar/configurar dependências) rodada antes do 1º comando de terminal. */
    val setup: ShellStep? = null,
) {
    /** Todos os hosts que o conector pode chamar. */
    val hosts: Set<String> get() = (listOf(host) + allowedHosts).map { it.lowercase() }.toSet()

    fun toSpec() = UiConnectorSpec(
        id = id, name = name, description = description, version = version,
        loginFields = login.fields, settings = settings,
        tools = tools.map { ToolSpec(it.name, it.description, it.params, it.risky, it.opensOrder) },
        accent = accent,
    )
}

object CustomDefParser {
    const val MAX_CHARS = 64 * 1024
    private val METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE")
    private val ID = Regex("^[a-z][a-z0-9]{1,19}$")
    private val IDENT = Regex("^[a-z][a-zA-Z0-9_]{0,31}$")
    private val ORDER_WORDS = Regex("(?i)(buy|sell|order|trade|compra|vend|ordem|operar|opera[cç][aã]o|withdraw|saque|transfer)")
    private val EXTRACT_SRC = Regex("^(\\$.*|header:[A-Za-z0-9-]+|cookie:[A-Za-z0-9_.-]+)$")

    data class Parsed(val def: CustomDef?, val errors: List<String>, val warnings: List<String>)

    /** Remove cercas de Markdown (```yaml … ```) que IAs costumam colocar em volta da definição. */
    fun stripFences(raw: String): String {
        var t = raw.trim()
        if (t.startsWith("```")) {
            t = t.substringAfter('\n', "")
            val end = t.lastIndexOf("```")
            if (end >= 0) t = t.substring(0, end)
        }
        return t.trim()
    }

    private fun syntaxError(cause: Throwable): String {
        val message = cause.message.orEmpty()
        val duplicate = cause.javaClass.simpleName.contains("DuplicateKey", true) || message.contains("duplicate key", true)
        val detail = if (duplicate) message.lineSequence().firstOrNull { it.contains("duplicate key", true) }?.trim()?.take(180).orEmpty().ifBlank { "chave repetida" }
            else message.lineSequence().firstOrNull().orEmpty().take(220).ifBlank { "erro de sintaxe" }
        val kind = if (duplicate) " (chave duplicada)" else ""
        return "YAML inválido$kind: $detail. Confira indentação, dois-pontos, listas e aspas; valores com {{...}} devem ficar entre aspas."
    }

    fun parse(input: String): Parsed {
        val text = stripFences(input)
        if (text.length > MAX_CHARS) return Parsed(null, listOf("Definição maior que 64 KB."), emptyList())
        val root = try { ConnectorYamlCodec.parse(text) } catch (t: Exception) {
            return Parsed(null, listOf(syntaxError(t)), emptyList())
        }
        val o = root as? JsonObject ?: return Parsed(null, listOf("A raiz deve ser um mapa YAML de pares chave/valor."), emptyList())
        val c = Ctx()
        val def = c.build(o)
        if (def != null) {
            c.errors.addAll(SpecValidator.validate(def.toSpec()))
        }
        return Parsed(if (c.errors.isEmpty()) def else null, c.errors, c.warnings)
    }

    private class Ctx {
        val errors = ArrayList<String>()
        val warnings = ArrayList<String>()
        var hosts: Set<String> = emptySet()

        fun known(o: JsonObject, path: String, keys: Set<String>) {
            o.keys.filter { it !in keys }.forEach { warnings.add("$path.$it: chave desconhecida (ignorada).") }
        }

        fun str(o: JsonObject, k: String, path: String, required: Boolean = false, def: String = ""): String {
            val e = o[k]
            if (e == null || e is JsonNull) {
                if (required) errors.add("$path.$k é obrigatório.")
                return def
            }
            val p = e as? JsonPrimitive
            if (p == null) {
                errors.add("$path.$k deve ser texto.")
                return def
            }
            return p.content
        }

        fun bool(o: JsonObject, k: String, path: String, def: Boolean): Boolean {
            val e = o[k]
            if (e == null || e is JsonNull) return def
            val p = e as? JsonPrimitive
            val v = p?.content?.lowercase()
            if (p == null || p.isString || (v != "true" && v != "false")) {
                errors.add("$path.$k deve ser true ou false.")
                return def
            }
            return v == "true"
        }

        fun obj(o: JsonObject, k: String, path: String, required: Boolean = false): JsonObject? {
            val e = o[k]
            if (e == null || e is JsonNull) {
                if (required) errors.add("$path.$k é obrigatório.")
                return null
            }
            val v = e as? JsonObject
            if (v == null) errors.add("$path.$k deve ser um objeto { … }.")
            return v
        }

        fun arr(o: JsonObject, k: String, path: String, max: Int, required: Boolean = false): List<JsonObject> {
            val e = o[k]
            if (e == null || e is JsonNull) {
                if (required) errors.add("$path.$k é obrigatório.")
                return emptyList()
            }
            val a = e as? JsonArray
            if (a == null) {
                errors.add("$path.$k deve ser uma lista [ … ].")
                return emptyList()
            }
            if (a.size > max) errors.add("$path.$k tem ${a.size} itens (máximo $max).")
            val out = ArrayList<JsonObject>()
            a.take(max).forEachIndexed { i, el ->
                val ob = el as? JsonObject
                if (ob == null) errors.add("$path.$k[$i] deve ser um objeto { … }.") else out.add(ob)
            }
            return out
        }

        fun strMap(o: JsonObject?, path: String): Map<String, String> {
            if (o == null) return emptyMap()
            val m = LinkedHashMap<String, String>()
            for ((k, v) in o) {
                val p = v as? JsonPrimitive
                if (p == null || v is JsonNull) errors.add("$path.$k deve ser texto.") else m[k] = p.content
            }
            return m
        }

        fun tone(s: String, path: String): Tone = when (s.lowercase()) {
            "", "neutral" -> Tone.NEUTRAL
            "good" -> Tone.GOOD
            "bad" -> Tone.BAD
            "warn" -> Tone.WARN
            else -> { errors.add("$path: tone '$s' inválido (neutral, good, bad, warn)."); Tone.NEUTRAL }
        }

        fun tpl(t: String, path: String, vars: Set<String>, allowPath: Boolean) {
            Template.problems(t, vars, allowPath).forEach { errors.add("$path: $it") }
        }

        fun tplReq(r: ReqDef?, path: String, vars: Set<String>) {
            if (r == null) return
            tpl(r.path, "$path.path", vars, false)
            r.query.forEach { (k, v) -> tpl(v, "$path.query.$k", vars, false) }
            r.headers.forEach { (k, v) -> tpl(v, "$path.headers.$k", vars, false) }
            r.form.forEach { (k, v) -> tpl(v, "$path.form.$k", vars, false) }
            tpl(r.url, "$path.url", vars, false)
            Template.strings(r.body).forEach { tpl(it, "$path.body", vars, false) }
        }

        var usesNetwork = false

        fun req(o: JsonObject?, path: String): ReqDef? {
            if (o == null) return null
            usesNetwork = true
            known(o, path, setOf("method", "path", "url", "query", "headers", "body", "form"))
            val m = str(o, "method", path, def = "GET").uppercase()
            if (m !in METHODS) errors.add("$path.method '$m' inválido (GET, POST, PUT, PATCH, DELETE).")
            val url = str(o, "url", path).trim()
            val p = str(o, "path", path, required = url.isEmpty())
            if (url.isNotEmpty()) {
                if (p.isNotEmpty()) errors.add("$path: use \"path\" OU \"url\", não os dois.")
                if (!url.startsWith("https://")) errors.add("$path.url deve começar com https://")
                else {
                    val h = url.removePrefix("https://").substringBefore('/').substringBefore('?').lowercase()
                    if (h.contains("{{")) errors.add("$path.url: o host não pode ter variável.")
                    else if (h !in hosts) errors.add("$path.url: host '$h' não está em baseUrl/allowedHosts.")
                }
            } else {
                if (p.isNotEmpty() && !p.startsWith("/")) errors.add("$path.path deve começar com \"/\".")
                if (p.contains("://")) errors.add("$path.path não pode conter URL completa; use \"url\" com um host de allowedHosts.")
            }
            if (m == "GET" && (o["body"] != null || o["form"] != null)) warnings.add("$path: body/form são ignorados em GET.")
            if (o["body"] != null && o["form"] != null) errors.add("$path: use \"body\" OU \"form\", não os dois.")
            return ReqDef(m, p, strMap(obj(o, "query", path), "$path.query"), strMap(obj(o, "headers", path), "$path.headers"), o["body"],
                url, strMap(obj(o, "form", path), "$path.form"))
        }

        fun parseAccent(s: String): Long {
            if (s.isBlank()) return 0xFFFF6B3D
            val h = s.trim().removePrefix("#")
            val v = if (h.length == 6) h.toLongOrNull(16) else null
            if (v == null) { warnings.add("accent '$s' inválido (use #RRGGBB); usando o padrão."); return 0xFFFF6B3D }
            return 0xFF000000L or v
        }

        fun build(o: JsonObject): CustomDef? {
            known(o, "$", setOf("id", "name", "description", "version", "accent", "baseUrl", "allowedHosts", "headers", "login", "settings", "panels", "actions", "tools", "realtime", "capabilities", "setup"))
            val id = str(o, "id", "$", required = true)
            if (id.isNotEmpty() && !ID.matches(id)) errors.add("$.id '$id' inválido: use [a-z0-9], começando com letra (2–20).")
            val name = str(o, "name", "$", required = true)
            val description = str(o, "description", "$", required = true)
            val version = str(o, "version", "$", def = "1.0")
            val accent = parseAccent(str(o, "accent", "$"))
            // baseUrl só é obrigatório se o conector faz requisições (conectores de cálculo puro não precisam)
            val baseUrl = str(o, "baseUrl", "$").trim().trimEnd('/')
            var host = ""
            if (baseUrl.isNotEmpty()) {
                val (h, err) = UrlGuard.baseHost(baseUrl)
                if (err != null) errors.add("$.baseUrl: $err") else host = h.orEmpty()
            }
            val allowed = ArrayList<String>()
            val ah = o["allowedHosts"]
            if (ah != null && ah !is JsonNull) {
                val arrH = ah as? JsonArray
                if (arrH == null) errors.add("$.allowedHosts deve ser uma lista de domínios.")
                else {
                    if (arrH.size > 8) errors.add("$.allowedHosts: máximo 8 domínios.")
                    arrH.take(8).forEachIndexed { i, el ->
                        val hv = (el as? JsonPrimitive)?.content?.trim()?.lowercase().orEmpty()
                        val prob = UrlGuard.hostProblem(hv)
                        if (prob != null) errors.add("$.allowedHosts[$i]: $prob") else allowed.add(hv)
                    }
                }
            }
            hosts = (listOf(host) + allowed).filter { it.isNotEmpty() }.toSet()

            // capacidades do conector (inclui delegação LLM, separada de terminal/arquivos)
            val capabilities = LinkedHashSet<String>()
            val capEl = o["capabilities"]
            if (capEl != null && capEl !is JsonNull) {
                val arrC = capEl as? JsonArray
                if (arrC == null) errors.add("$.capabilities deve ser uma lista (ex.: [\"terminal\",\"files\"]).")
                else arrC.forEachIndexed { i, el ->
                    val cv = (el as? JsonPrimitive)?.content?.trim()?.lowercase().orEmpty()
                    if (cv !in Capabilities.ALL) errors.add("$.capabilities[$i]: '$cv' inválido (use terminal, files, browser ou agent).")
                    else capabilities.add(cv)
                }
            }

            // configurações
            val settings = ArrayList<SettingSpec>()
            arr(o, "settings", "$", 20).forEachIndexed { i, so ->
                val p = "$.settings[$i]"
                known(so, p, setOf("id", "label", "description", "type", "default", "risky"))
                val sid = str(so, "id", p, required = true)
                if (sid.isNotEmpty() && !IDENT.matches(sid)) errors.add("$p.id '$sid' inválido (letra inicial; letras, números e _).")
                val type = when (str(so, "type", p, def = "text").lowercase()) {
                    "number" -> SettingType.NUMBER
                    "switch" -> SettingType.SWITCH
                    "text" -> SettingType.TEXT
                    else -> { errors.add("$p.type deve ser number, switch ou text."); SettingType.TEXT }
                }
                settings.add(SettingSpec(sid, str(so, "label", p, required = true), str(so, "description", p, def = ""), type, str(so, "default", p, def = if (type == SettingType.SWITCH) "false" else ""), bool(so, "risky", p, false)))
            }
            val settingVars = settings.map { "s.${it.id}" }.toSet()

            // setup: preparação única do ambiente (ex.: instalar yt-dlp). Só configurações como variáveis.
            var setup: ShellStep? = null
            val so2 = obj(o, "setup", "$")
            if (so2 != null) {
                if (Capabilities.TERMINAL !in capabilities) errors.add("$.setup exige \"terminal\" na lista capabilities do conector.")
                known(so2, "$.setup", setOf("run", "timeoutSec"))
                val run = str(so2, "run", "$.setup", required = true)
                tpl(run, "$.setup.run", settingVars, false)
                val ts = str(so2, "timeoutSec", "$.setup", def = "600").toIntOrNull()
                if (ts == null || ts !in BridgeSecurity.MIN_TIMEOUT..BridgeSecurity.MAX_SETUP_TIMEOUT)
                    errors.add("$.setup.timeoutSec deve ser de ${BridgeSecurity.MIN_TIMEOUT} a ${BridgeSecurity.MAX_SETUP_TIMEOUT}.")
                setup = ShellStep(run, BridgeSecurity.clampSetupTimeout(ts ?: 600))
            }

            // login
            val lo = obj(o, "login", "$")
            var login = LoginDef(emptyList(), null, emptyMap(), null)
            if (lo != null) {
                known(lo, "$.login", setOf("fields", "request", "extract", "verify"))
                val fields = ArrayList<LoginField>()
                arr(lo, "fields", "$.login", 8, required = true).forEachIndexed { i, fo ->
                    val p = "$.login.fields[$i]"
                    known(fo, p, setOf("id", "label", "type", "required", "hint"))
                    val fid = str(fo, "id", p, required = true)
                    if (fid.isNotEmpty() && !IDENT.matches(fid)) errors.add("$p.id '$fid' inválido (letra inicial; letras, números e _).")
                    val ft = when (str(fo, "type", p, def = "text").lowercase()) {
                        "text" -> FieldType.TEXT
                        "email" -> FieldType.EMAIL
                        "password" -> FieldType.PASSWORD
                        "number" -> FieldType.NUMBER
                        else -> { errors.add("$p.type deve ser text, email, password ou number."); FieldType.TEXT }
                    }
                    fields.add(LoginField(fid, str(fo, "label", p, required = true), ft, bool(fo, "required", p, true), str(fo, "hint", p)))
                }
                val credVars = fields.map { it.id }.toSet()
                val extract = LinkedHashMap<String, String>()
                val exObj = obj(lo, "extract", "$.login")
                if (exObj != null) {
                    for ((k, v) in exObj) {
                        val src = (v as? JsonPrimitive)?.content
                        if (!IDENT.matches(k)) {
                            errors.add("$.login.extract.$k: nome de variável inválido.")
                        } else if (k in credVars) {
                            errors.add("$.login.extract.$k: conflita com um campo de login.")
                        } else if (src == null || !EXTRACT_SRC.matches(src)) {
                            errors.add("$.login.extract.$k: use \"\$.caminho\", \"header:Nome\" ou \"cookie:Nome\".")
                        } else if (src.startsWith("$") && !JsonPath.isValid(src)) {
                            errors.add("$.login.extract.$k: caminho inválido '$src'.")
                        } else {
                            extract.put(k, src)
                        }
                    }
                }
                val lreq = req(obj(lo, "request", "$.login"), "$.login.request")
                tplReq(lreq, "$.login.request", credVars)
                if (lreq == null && extract.isNotEmpty()) errors.add("$.login.extract exige login.request.")
                val verify = req(obj(lo, "verify", "$.login"), "$.login.verify")
                val sessionVars = credVars + extract.keys
                tplReq(verify, "$.login.verify", sessionVars)
                login = LoginDef(fields, lreq, extract, verify)
            }
            val credVars = login.fields.map { it.id }.toSet()
            val sessionVars = credVars + login.extract.keys
            val headers = strMap(obj(o, "headers", "$"), "$.headers")
            headers.forEach { (k, v) -> tpl(v, "$.headers.$k", sessionVars, false) }
            // tempo real (WebSocket)
            val rt = realtime(obj(o, "realtime", "$"), sessionVars + settingVars)
            val rtVars = if (rt == null) emptySet() else rt.stateKeys.map { "rt.$it" }.toSet() + setOf("rt.connected", "rt.messages")
            val collections = rt?.collections.orEmpty()
            val reqVars = sessionVars + settingVars + rtVars

            // painéis
            val panels = ArrayList<PanelDef>()
            arr(o, "panels", "$", 10).forEachIndexed { i, po ->
                val p = "$.panels[$i]"
                known(po, p, setOf("type", "title", "subtitle", "badge", "tone", "request", "items", "rows", "columns", "text", "empty", "source", "compute"))
                val pcomp = compute(obj(po, "compute", p), "$p.compute", emptySet())
                val pvars = reqVars + pcomp.map { "c.${it.first}" }
                val type = str(po, "type", p, required = true).lowercase()
                if (type !in setOf("header", "stats", "table", "note")) errors.add("$p.type '$type' inválido (header, stats, table, note).")
                val preq = req(obj(po, "request", p), "$p.request")
                tplReq(preq, "$p.request", reqVars)
                val psource = str(po, "source", p).trim()
                if (psource.isNotEmpty()) {
                    if (preq != null) errors.add("$p: use \"request\" OU \"source\", não os dois.")
                    if (rt == null) errors.add("$p.source exige o bloco \"realtime\".")
                    else if (psource !in collections) errors.add("$p.source '$psource' não é uma coleção do realtime (disponíveis: ${collections.joinToString().ifEmpty { "nenhuma — use push.to numa regra" }}).")
                }
                val title = str(po, "title", p)
                val subtitle = str(po, "subtitle", p)
                val badge = str(po, "badge", p)
                val text = str(po, "text", p)
                val empty = str(po, "empty", p, def = "Nada por aqui.")
                val tn = tone(str(po, "tone", p), "$p.tone")
                for ((k, v) in listOf("title" to title, "subtitle" to subtitle, "badge" to badge, "text" to text, "empty" to empty)) tpl(v, "$p.$k", pvars, true)
                val stats = ArrayList<StatDef>()
                val cols = ArrayList<ColDef>()
                var rows = ""
                if (type == "stats") {
                    arr(po, "items", p, 20, required = true).forEachIndexed { j, so ->
                        val sp = "$p.items[$j]"
                        known(so, sp, setOf("label", "value", "tone"))
                        val label = str(so, "label", sp, required = true)
                        val value = str(so, "value", sp, required = true)
                        tpl(label, "$sp.label", pvars, true)
                        tpl(value, "$sp.value", pvars, true)
                        stats.add(StatDef(label, value, tone(str(so, "tone", sp), "$sp.tone")))
                    }
                }
                if (type == "table") {
                    rows = str(po, "rows", p, required = true)
                    rowsOk(rows, "$p.rows", pcomp)
                    if (preq == null && psource.isEmpty() && !rows.startsWith("c.")) errors.add("$p: tabelas exigem \"request\" ou \"source\" (coleção do realtime).")
                    arr(po, "columns", p, 8, required = true).forEachIndexed { j, co ->
                        val cp = "$p.columns[$j]"
                        known(co, cp, setOf("title", "value"))
                        val ct = str(co, "title", cp, required = true)
                        val cv = str(co, "value", cp, required = true)
                        tpl(cv, "$cp.value", pvars, true)
                        cols.add(ColDef(ct, cv))
                    }
                }
                panels.add(PanelDef(type, title, subtitle, badge, tn, preq, stats, rows, cols, text, empty, psource, pcomp))
            }

            // ações do usuário (botões)
            val actions = ArrayList<ActionDef>()
            arr(o, "actions", "$", 10).forEachIndexed { i, ao ->
                val p = "$.actions[$i]"
                known(ao, p, setOf("id", "label", "risky", "request", "message"))
                val aid = str(ao, "id", p, required = true)
                if (aid.isNotEmpty() && !IDENT.matches(aid)) errors.add("$p.id '$aid' inválido.")
                val areq = req(obj(ao, "request", p, required = true), "$p.request")
                tplReq(areq, "$p.request", reqVars)
                val msg = str(ao, "message", p)
                tpl(msg, "$p.message", reqVars, true)
                val risky = bool(ao, "risky", p, false)
                if (areq != null && areq.method != "GET" && !risky) errors.add("$p: ação com método ${areq.method} exige \"risky\": true (pede confirmação).")
                val alabel = str(ao, "label", p, required = true)
                if (areq != null) actions.add(ActionDef(aid, alabel, risky, areq, msg))
            }
            actions.groupBy { it.id }.filter { it.value.size > 1 }.keys.forEach { errors.add("$.actions: id duplicado '$it'.") }

            // ferramentas do agente
            val tools = ArrayList<ToolDef>()
            arr(o, "tools", "$", 30).forEachIndexed { i, to ->
                val p = "$.tools[$i]"
                known(to, p, setOf("name", "description", "params", "risky", "opensOrder", "orderIntent", "limits", "request", "response", "list", "send", "source", "compute", "shell", "file", "fetch", "agent", "outputPolicy"))
                val params = ArrayList<ParamSpec>()
                arr(to, "params", p, 12).forEachIndexed { j, pa ->
                    val pp = "$p.params[$j]"
                    known(pa, pp, setOf("name", "description", "type", "required", "enum"))
                    val pn = str(pa, "name", pp, required = true)
                    if (pn.isNotEmpty() && !IDENT.matches(pn)) errors.add("$pp.name '$pn' inválido (letra inicial; letras, números e _).")
                    val en = (pa["enum"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
                    params.add(ParamSpec(pn, str(pa, "description", pp, required = true), str(pa, "type", pp, def = "string"), bool(pa, "required", pp, true), en))
                }
                val paramVars = params.map { it.name }.toSet()
                val tcomp = compute(obj(to, "compute", p), "$p.compute", paramVars)
                val vars = reqVars + paramVars
                val outVars = vars + tcomp.map { "c.${it.first}" }
                val risky = bool(to, "risky", p, false)
                val treq = req(obj(to, "request", p), "$p.request")
                tplReq(treq, "$p.request", vars)
                if (treq != null && treq.method != "GET" && !risky) errors.add("$p: ferramenta com método ${treq.method} exige \"risky\": true.")
                val tsend = send(obj(to, "send", p), "$p.send", vars, rt != null)
                val tsource = str(to, "source", p).trim()
                if (tsource.isNotEmpty()) {
                    if (rt == null) errors.add("$p.source exige o bloco \"realtime\".")
                    else if (tsource !in collections) errors.add("$p.source '$tsource' não é uma coleção do realtime (disponíveis: ${collections.joinToString().ifEmpty { "nenhuma" }}).")
                }
                // passos de capacidade do aparelho (terminal/files/browser)
                val tshell = shellStep(obj(to, "shell", p), "$p.shell", vars, capabilities, risky)
                val tfile = fileStep(obj(to, "file", p), "$p.file", vars, capabilities, risky)
                val tfetch = fetchStep(obj(to, "fetch", p), "$p.fetch", vars, capabilities)
                val unboundedSteps = id == LocalDevStudioTemplate.ID && str(to, "name", p) in LocalDevStudioTemplate.UNBOUNDED_AGENT_TOOLS
                val tagent = agentStep(obj(to, "agent", p), "$p.agent", paramVars, capabilities, risky, unboundedSteps)
                val modes = listOfNotNull(
                    treq?.let { "request" }, tsend?.let { "send" }, tsource.ifEmpty { null }?.let { "source" },
                    tshell?.let { "shell" }, tfile?.let { "file" }, tfetch?.let { "fetch" }, tagent?.let { "agent" },
                )
                if (modes.size > 1) errors.add("$p: use só um entre request, send, source, shell, file, fetch e agent (encontrado: ${modes.joinToString()}).")
                if (modes.isEmpty() && rt == null && to["send"] == null && tcomp.isEmpty()) errors.add("$p.request é obrigatório (ou send/source/shell/file/fetch/agent).")
                if (modes.isEmpty() && (rt != null || tcomp.isNotEmpty()) && str(to, "response", p).isBlank() && to["list"] == null) errors.add("$p: sem request/send/source, a ferramenta precisa de \"response\" lendo o estado (ex.: {{rt.saldo}}).")
                if (tsend != null && !risky && ORDER_WORDS.containsMatchIn(str(to, "name", p) + " " + str(to, "description", p))) errors.add("$p: ferramenta que envia ordem/operação pelo tempo real exige \"risky\": true.")
                val limits = ArrayList<LimitDef>()
                arr(to, "limits", p, 6).forEachIndexed { j, lo2 ->
                    val lp = "$p.limits[$j]"
                    known(lo2, lp, setOf("param", "maxSetting"))
                    val lpn = str(lo2, "param", lp, required = true)
                    val ms = str(lo2, "maxSetting", lp, required = true)
                    val prm = params.firstOrNull { it.name == lpn }
                    if (prm == null) errors.add("$lp.param '$lpn' não é parâmetro da ferramenta.")
                    else if (prm.type != "number" && prm.type != "integer") errors.add("$lp.param '$lpn' precisa ser number ou integer.")
                    val st = settings.firstOrNull { it.id == ms }
                    if (st == null || st.type != SettingType.NUMBER) errors.add("$lp.maxSetting '$ms' deve ser uma setting do tipo number.")
                    limits.add(LimitDef(lpn, ms))
                }
                var intent: IntentDef? = null
                val io = obj(to, "orderIntent", p)
                if (io != null) {
                    known(io, "$p.orderIntent", setOf("asset", "direction"))
                    val asset = str(io, "asset", "$p.orderIntent", required = true)
                    val dir = str(io, "direction", "$p.orderIntent", required = true)
                    tpl(asset, "$p.orderIntent.asset", paramVars, false)
                    tpl(dir, "$p.orderIntent.direction", paramVars, false)
                    intent = IntentDef(asset, dir)
                }
                // orderIntent identifica uma operação de ordem mesmo em definições antigas sem opensOrder.
                val opensOrder = bool(to, "opensOrder", p, false) || intent != null
                if (risky && limits.isEmpty() && !opensOrder && tagent == null) warnings.add("$p: ferramenta sensível sem \"limits\" — o agente não terá teto de valor definido por você.")
                if (opensOrder && intent == null) errors.add("$p: opensOrder exige orderIntent {asset, direction}.")
                if (opensOrder && !risky) errors.add("$p: opensOrder exige risky = true.")
                val response = str(to, "response", p)
                tpl(response, "$p.response", outVars, true)
                var vslPolicy: VslOutputPolicy? = null
                val vpo = obj(to, "outputPolicy", p)
                if (vpo != null) {
                    known(vpo, "$p.outputPolicy", setOf("type", "durationParam", "languageParam", "guardrailsParam", "forbiddenTermsParam", "wordsPerMinute", "tolerance", "requiredSections"))
                    val policyType = str(vpo, "type", "$p.outputPolicy", required = true)
                    if (policyType != "vsl") errors.add("$p.outputPolicy.type deve ser \"vsl\".")
                    val durationParam = str(vpo, "durationParam", "$p.outputPolicy", required = true)
                    val languageParam = str(vpo, "languageParam", "$p.outputPolicy", required = true)
                    val guardrailsParam = str(vpo, "guardrailsParam", "$p.outputPolicy", required = true)
                    val forbiddenTermsParam = str(vpo, "forbiddenTermsParam", "$p.outputPolicy")
                    val wpm = str(vpo, "wordsPerMinute", "$p.outputPolicy", def = "130").toIntOrNull()
                    if (wpm == null || wpm !in 80..240) errors.add("$p.outputPolicy.wordsPerMinute deve estar entre 80 e 240.")
                    val tolerance = str(vpo, "tolerance", "$p.outputPolicy", def = "0.20").toDoubleOrNull()
                    if (tolerance == null || tolerance !in 0.05..0.50) errors.add("$p.outputPolicy.tolerance deve estar entre 0.05 e 0.50.")
                    val sections = (vpo["requiredSections"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { x -> x.isString }?.content } ?: emptyList()
                    if (sections.size !in 3..12 || sections.any { it.isBlank() || it.length > 80 })
                        errors.add("$p.outputPolicy.requiredSections deve conter de 3 a 12 títulos não vazios (até 80 caracteres cada).")
                    if (sections.map { it.trim().lowercase() }.distinct().size != sections.size)
                        errors.add("$p.outputPolicy.requiredSections não pode repetir títulos.")
                    fun requireParam(id: String, expected: Set<String>, label: String) {
                        val prm = params.firstOrNull { it.name == id }
                        if (id.isBlank() || prm == null) errors.add("$p.outputPolicy.$label deve apontar para um parâmetro existente.")
                        else if (prm.type !in expected) errors.add("$p.outputPolicy.$label tem tipo incompatível (${prm.type}).")
                    }
                    requireParam(durationParam, setOf("number", "integer"), "durationParam")
                    requireParam(languageParam, setOf("string"), "languageParam")
                    requireParam(guardrailsParam, setOf("string"), "guardrailsParam")
                    if (forbiddenTermsParam.isNotBlank()) requireParam(forbiddenTermsParam, setOf("string"), "forbiddenTermsParam")
                    if (treq == null || response.isBlank() || to["list"] != null) errors.add("$p.outputPolicy exige request HTTP, response textual e não pode usar list.")
                    val requestTexts = treq?.let { listOf(it.path, it.url) + it.query.values + it.headers.values + it.form.values + Template.strings(it.body) }.orEmpty()
                    for ((id, label) in listOf(durationParam to "durationParam", languageParam to "languageParam", guardrailsParam to "guardrailsParam")) {
                        if (id.isNotBlank() && requestTexts.none { Regex("\\{\\{\\s*${Regex.escape(id)}(?:\\s*\\||\\s*\\}\\})").containsMatchIn(it) })
                            errors.add("$p.outputPolicy.$label não é enviado na requisição de geração.")
                    }
                    vslPolicy = VslOutputPolicy(durationParam, languageParam, guardrailsParam, wpm ?: 130, tolerance ?: 0.20, sections, forbiddenTermsParam)
                }
                var list: ListDef? = null
                val lso = obj(to, "list", p)
                if (lso != null) {
                    known(lso, "$p.list", setOf("rows", "item", "max"))
                    if (treq == null && tsend == null && tsource.isEmpty() && tcomp.isEmpty()) errors.add("$p.list exige request, send, source ou compute.")
                    val rows = str(lso, "rows", "$p.list", required = true)
                    rowsOk(rows, "$p.list.rows", tcomp)
                    val item = str(lso, "item", "$p.list", required = true)
                    tpl(item, "$p.list.item", outVars, true)
                    val mx = str(lso, "max", "$p.list", def = "20").toIntOrNull()
                    if (mx == null || mx !in 1..200) errors.add("$p.list.max deve ser de 1 a 200.")
                    list = ListDef(rows, item, (mx ?: 20).coerceIn(1, 200))
                }
                val tname = str(to, "name", p, required = true)
                val tdesc = str(to, "description", p, required = true)
                tools.add(ToolDef(tname, tdesc, params, risky, opensOrder, intent, limits, treq, response, list, tsend, tsource, tcomp, tshell, tfile, tfetch, vslPolicy, tagent))
            }
            if (baseUrl.isEmpty() && (usesNetwork || rt != null)) errors.add("$.baseUrl é obrigatório quando o conector faz requisições ou usa tempo real.")
            return CustomDef(id, name, description, version, accent, baseUrl, host, headers, login, settings, panels, actions, tools, allowed, rt, capabilities, setup)
        }

        /** Passo de terminal: comando [run] (modelo shell-seguro) rodado no sandbox do conector. */
        fun shellStep(o: JsonObject?, path: String, vars: Set<String>, caps: Set<String>, risky: Boolean): ShellStep? {
            if (o == null) return null
            if (Capabilities.TERMINAL !in caps) errors.add("$path exige \"terminal\" na lista capabilities do conector.")
            if (!risky) errors.add("$path: ferramenta de terminal exige \"risky\": true.")
            known(o, path, setOf("run", "timeoutSec", "files"))
            val run = str(o, "run", path, required = true)
            tpl(run, "$path.run", vars, false)
            val ts = str(o, "timeoutSec", path, def = "120").toIntOrNull()
            if (ts == null || ts !in BridgeSecurity.MIN_TIMEOUT..BridgeSecurity.MAX_TIMEOUT)
                errors.add("$path.timeoutSec deve ser de ${BridgeSecurity.MIN_TIMEOUT} a ${BridgeSecurity.MAX_TIMEOUT}.")
            // Arquivos gravados ANTES do comando (sem heredoc): forma à prova de erro de criar scripts/dados.
            val files = ArrayList<FileWrite>()
            arr(o, "files", path, 10).forEachIndexed { i, fo ->
                val fp = "$path.files[$i]"
                known(fo, fp, setOf("path", "content"))
                val fpath = str(fo, "path", fp, required = true)
                val content = str(fo, "content", fp, required = true)
                tpl(fpath, "$fp.path", vars, false)
                tpl(content, "$fp.content", vars, false)
                files.add(FileWrite(fpath, content))
            }
            return ShellStep(run, (ts ?: 120).coerceIn(BridgeSecurity.MIN_TIMEOUT, BridgeSecurity.MAX_TIMEOUT), files)
        }

        /** Passo de arquivo no workspace isolado do conector. */
        fun fileStep(o: JsonObject?, path: String, vars: Set<String>, caps: Set<String>, risky: Boolean): FileStep? {
            if (o == null) return null
            if (Capabilities.FILES !in caps) errors.add("$path exige \"files\" na lista capabilities do conector.")
            known(o, path, setOf("op", "path", "content", "oldText", "newText", "allowMultiple"))
            val op = str(o, "op", path, required = true).lowercase()
            if (op !in BridgeSecurity.FILE_OPS) errors.add("$path.op deve ser read, write, list, delete ou replace.")
            if ((op == "write" || op == "delete" || op == "replace") && !risky) errors.add("$path: op '$op' exige \"risky\": true.")
            val fp = str(o, "path", path, required = op != "list")
            tpl(fp, "$path.path", vars, false)
            val content = if (op == "write") str(o, "content", path) else null
            if (op == "write") tpl(content.orEmpty(), "$path.content", vars, false)
            val oldText = if (op == "replace") str(o, "oldText", path, required = true) else null
            val newText = if (op == "replace") str(o, "newText", path, required = true) else null
            if (op == "replace") {
                tpl(oldText.orEmpty(), "$path.oldText", vars, false)
                tpl(newText.orEmpty(), "$path.newText", vars, false)
            } else if (o.containsKey("oldText") || o.containsKey("newText") || o.containsKey("allowMultiple")) {
                errors.add("$path: oldText, newText e allowMultiple só são aceitos com op 'replace'.")
            }
            val allowMultiple = if (op == "replace") bool(o, "allowMultiple", path, false) else false
            return FileStep(op, fp, content, oldText, newText, allowMultiple)
        }

        /** Passo de browser: abre uma URL https de um host declarado e lê a página renderizada. */
        fun fetchStep(o: JsonObject?, path: String, vars: Set<String>, caps: Set<String>): FetchStep? {
            if (o == null) return null
            if (Capabilities.BROWSER !in caps) errors.add("$path exige \"browser\" na lista capabilities do conector.")
            known(o, path, setOf("url", "waitSec"))
            val url = str(o, "url", path, required = true)
            tpl(url, "$path.url", vars, false)
            val hostPart = url.removePrefix("https://").substringBefore('/').substringBefore('?')
            if (url.startsWith("https://") && !hostPart.contains("{{")) {
                if (hostPart.lowercase() !in hosts) errors.add("$path.url: host '${hostPart.lowercase()}' não está em baseUrl/allowedHosts.")
            } else if (!url.startsWith("https://") && !url.trimStart().startsWith("{{")) {
                errors.add("$path.url deve começar com https:// (ou ser um parâmetro {{...}} que forneça a URL https).")
            }
            val w = str(o, "waitSec", path, def = "2").toIntOrNull()
            if (w == null || w !in 0..10) errors.add("$path.waitSec deve ser de 0 a 10.")
            return FetchStep(url, (w ?: 2).coerceIn(0, 10))
        }

        /** A tarefa recebe só os parâmetros da ferramenta, nunca variáveis de login ou segredos do conector. */
        fun agentStep(o: JsonObject?, path: String, params: Set<String>, caps: Set<String>, risky: Boolean, unboundedSteps: Boolean): AgentTaskStep? {
            if (o == null) return null
            if (Capabilities.AGENT !in caps) errors.add("$path exige \"agent\" na lista capabilities do conector.")
            if (!risky) errors.add("$path: delegação LLM exige \"risky\": true para sinalizar a alteração potencial de arquivos/execução.")
            // budgetTokens é aceito como campo legado, mas ignorado para manter YAMLs instalados compatíveis.
            known(o, path, setOf("task", "maxSteps", "budgetTokens", "timeoutSec"))
            val task = str(o, "task", path, required = true)
            tpl(task, "$path.task", params, false)
            val rawSteps = str(o, "maxSteps", path, def = if (unboundedSteps) "" else "6")
            val steps = rawSteps.toIntOrNull()
            if (unboundedSteps) {
                if (o["maxSteps"] != null) {
                    if (steps == null || steps !in 1..ConnectorTaskAgent.MAX_STEPS)
                        errors.add("$path.maxSteps deve estar entre 1 e ${ConnectorTaskAgent.MAX_STEPS} quando informado.")
                    warnings.add("$path.maxSteps é ignorado nos agentes do Fluwy Open Dev; a execução continua sujeita ao timeout.")
                }
            } else if (steps == null || steps !in 1..ConnectorTaskAgent.MAX_STEPS) {
                errors.add("$path.maxSteps deve estar entre 1 e ${ConnectorTaskAgent.MAX_STEPS}.")
            }
            val timeout = str(o, "timeoutSec", path, def = "300").toIntOrNull()
            if (timeout == null || timeout !in 15..900) errors.add("$path.timeoutSec deve estar entre 15 e 900 segundos.")
            val stepLimit = if (unboundedSteps) null else (steps ?: 6).coerceIn(1, ConnectorTaskAgent.MAX_STEPS)
            return AgentTaskStep(task, stepLimit, (timeout ?: 300).coerceIn(15, 900))
        }

        /** Bloco "compute": {nome: expressão}. Valida sintaxe, funções, identificadores e referências c.x. */
        fun compute(o: JsonObject?, path: String, params: Set<String>): List<Pair<String, Compute.Node>> {
            if (o == null) return emptyList()
            if (o.size > 80) errors.add("$path: no máximo 80 cálculos.")
            val out = ArrayList<Pair<String, Compute.Node>>()
            val known = setOf("$", "s", "rt", "c") + params
            val cref = Regex("\\bc\\.([A-Za-z_][A-Za-z0-9_]*)")
            for ((name, v) in o.entries.take(80)) {
                val src = (v as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (!IDENT.matches(name) || name in setOf("s", "rt", "c")) { errors.add("$path.$name: nome inválido (letra inicial; letras, números e _)."); continue }
                if (src == null) { errors.add("$path.$name deve ser uma expressão em texto."); continue }
                val probs = Compute.problems(src, known)
                probs.forEach { errors.add("$path.$name: $it") }
                cref.findAll(src).map { it.groupValues[1] }.filter { ref -> out.none { it.first == ref } }.toSet()
                    .forEach { errors.add("$path.$name: c.$it não foi calculado antes (a ordem importa).") }
                if (probs.isEmpty()) runCatching { out.add(name to Compute.parse(src)) }
            }
            return out
        }

        fun rowsOk(rows: String, path: String, comp: List<Pair<String, Compute.Node>>) {
            if (rows.startsWith("c.")) {
                if (comp.none { it.first == rows.removePrefix("c.") }) errors.add("$path: '$rows' não está no compute deste item.")
            } else if (rows.isNotEmpty() && !JsonPath.isValid(rows)) errors.add("$path: caminho inválido '$rows' (ex.: \"\$.data[*]\" ou \"c.nome\").")
        }

        /** Mensagem do tempo real: objeto/array JSON ou string (texto cru). Valida os modelos. */
        fun rtMsg(e: JsonElement?, path: String, vars: Set<String>): JsonElement? {
            if (e == null || e is JsonNull) return null
            if (e is JsonPrimitive && !e.isString) { errors.add("$path deve ser objeto, lista ou texto."); return null }
            Template.strings(e).forEach { tpl(it, path, vars, false) }
            return e
        }

        fun matchMap(o: JsonObject?, path: String, vars: Set<String>): Map<String, String> {
            val m = strMap(o, path)
            if (m.size > 6) errors.add("$path: no máximo 6 condições.")
            m.forEach { (k, v) ->
                if (!k.startsWith("$") || !JsonPath.isValid(k)) errors.add("$path: chave '$k' deve ser um caminho (ex.: \"\$.name\").")
                tpl(v, "$path.$k", vars, false)
            }
            return m
        }

        fun send(o: JsonObject?, path: String, vars: Set<String>, hasRt: Boolean): SendDef? {
            if (o == null) return null
            if (!hasRt) errors.add("$path exige o bloco \"realtime\".")
            known(o, path, setOf("message", "await"))
            val sv = vars + setOf("rid", "now")
            val msg = rtMsg(o["message"], "$path.message", sv)
            if (msg == null) { errors.add("$path.message é obrigatório."); return null }
            val ao = obj(o, "await", path)
            var aw: AwaitDef? = null
            if (ao != null) {
                known(ao, "$path.await", setOf("match", "timeoutSec"))
                val mm = matchMap(obj(ao, "match", "$path.await", required = true), "$path.await.match", sv)
                if (mm.isEmpty()) errors.add("$path.await.match precisa de ao menos 1 condição.")
                val ts = str(ao, "timeoutSec", "$path.await", def = "10").toIntOrNull()
                if (ts == null || ts !in 1..60) errors.add("$path.await.timeoutSec deve ser de 1 a 60.")
                aw = AwaitDef(mm, (ts ?: 10).coerceIn(1, 60))
            }
            return SendDef(msg, aw)
        }

        fun realtime(o: JsonObject?, baseVars: Set<String>): RtDef? {
            if (o == null) return null
            val p = "$.realtime"
            known(o, p, setOf("url", "headers", "onOpen", "heartbeat", "rules", "connect"))
            val cv = baseVars + setOf("rid", "now")
            val url = str(o, "url", p, required = true).trim()
            if (url.isNotEmpty()) {
                if (!url.startsWith("wss://")) errors.add("$p.url deve começar com wss://")
                else {
                    val hp = url.removePrefix("wss://").substringBefore('/').substringBefore('?').lowercase()
                    val h = hp.substringBefore(':')
                    val port = hp.substringAfter(':', "")
                    if (h.contains("{{")) errors.add("$p.url: o host não pode ter variável.")
                    else if (h !in hosts) errors.add("$p.url: host '$h' não está em baseUrl/allowedHosts.")
                    if (port.isNotEmpty() && port != "443") errors.add("$p.url: somente a porta 443.")
                }
                tpl(url, "$p.url", cv, false)
            }
            val headers = strMap(obj(o, "headers", p), "$p.headers")
            headers.forEach { (k, v) -> tpl(v, "$p.headers.$k", cv, false) }
            val onOpen = ArrayList<JsonElement>()
            val oo = o["onOpen"]
            if (oo != null && oo !is JsonNull) {
                val a = oo as? JsonArray
                if (a == null) errors.add("$p.onOpen deve ser uma lista de mensagens.")
                else {
                    if (a.size > 10) errors.add("$p.onOpen: no máximo 10 mensagens.")
                    a.take(10).forEachIndexed { i, el -> rtMsg(el, "$p.onOpen[$i]", cv)?.let { onOpen.add(it) } }
                }
            }
            var hbSec = 0
            var hb: JsonElement? = null
            val ho = obj(o, "heartbeat", p)
            if (ho != null) {
                known(ho, "$p.heartbeat", setOf("everySec", "message"))
                val ev = str(ho, "everySec", "$p.heartbeat", required = true).toIntOrNull()
                if (ev == null || ev !in 5..300) errors.add("$p.heartbeat.everySec deve ser de 5 a 300.")
                hbSec = (ev ?: 30).coerceIn(5, 300)
                hb = rtMsg(ho["message"], "$p.heartbeat.message", cv)
                if (hb == null) errors.add("$p.heartbeat.message é obrigatório.")
            }
            val rules = ArrayList<RtRule>()
            arr(o, "rules", p, 30).forEachIndexed { i, ro ->
                val rp = "$p.rules[$i]"
                known(ro, rp, setOf("match", "set", "push"))
                val mm = matchMap(obj(ro, "match", rp), "$rp.match", baseVars)
                val set = strMap(obj(ro, "set", rp), "$rp.set")
                set.forEach { (k, v) ->
                    if (!IDENT.matches(k)) errors.add("$rp.set.$k: nome inválido (letra inicial; letras, números e _).")
                    if (k == "connected" || k == "messages") errors.add("$rp.set.$k: nome reservado.")
                    if (!v.startsWith("$") || !JsonPath.isValid(v)) errors.add("$rp.set.$k: use um caminho (ex.: \"\$.msg.amount\").")
                }
                var pushTo = ""; var item = "$"; var key = ""; var max = 100
                val po = obj(ro, "push", rp)
                if (po != null) {
                    known(po, "$rp.push", setOf("to", "item", "key", "max"))
                    pushTo = str(po, "to", "$rp.push", required = true)
                    if (pushTo.isNotEmpty() && !IDENT.matches(pushTo)) errors.add("$rp.push.to '$pushTo' inválido.")
                    item = str(po, "item", "$rp.push", def = "$")
                    if (item != "$" && !JsonPath.isValid(item)) errors.add("$rp.push.item: caminho inválido '$item'.")
                    key = str(po, "key", "$rp.push")
                    if (key.isNotEmpty() && !JsonPath.isValid(key)) errors.add("$rp.push.key: caminho inválido '$key'.")
                    val mx = str(po, "max", "$rp.push", def = "100").toIntOrNull()
                    if (mx == null || mx !in 1..500) errors.add("$rp.push.max deve ser de 1 a 500.")
                    max = (mx ?: 100).coerceIn(1, 500)
                }
                if (set.isEmpty() && po == null) errors.add("$rp: a regra precisa de \"set\" e/ou \"push\".")
                rules.add(RtRule(mm, set, pushTo, item, key, max))
            }
            val connect = str(o, "connect", p, def = "login").lowercase()
            if (connect !in setOf("login", "auto")) errors.add("$p.connect deve ser login ou auto.")
            return RtDef(url, headers, onOpen, hbSec, hb, rules, connect)
        }
    }

    /** Resolve o lado de uma ordem a partir do texto renderizado (exposto para o conector). */
    fun side(raw: String) = Sides.parse(raw)
}
