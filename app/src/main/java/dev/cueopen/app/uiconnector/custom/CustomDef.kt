package dev.cueopen.app.uiconnector.custom

import dev.cueopen.app.trading.Sides
import dev.cueopen.app.uiconnector.FieldType
import dev.cueopen.app.uiconnector.LoginField
import dev.cueopen.app.uiconnector.ParamSpec
import dev.cueopen.app.uiconnector.SettingSpec
import dev.cueopen.app.uiconnector.SettingType
import dev.cueopen.app.uiconnector.SpecValidator
import dev.cueopen.app.uiconnector.Tone
import dev.cueopen.app.uiconnector.ToolSpec
import dev.cueopen.app.uiconnector.UiConnectorSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * Definição declarativa (JSON) de um conector criado pelo usuário. Só HTTP/REST: nada é executado,
 * apenas requisições para UM host HTTPS declarado em baseUrl. Referência completa em docs/ui-connector-sdk.md.
 */

data class ReqDef(val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>, val body: JsonElement?)
data class ColDef(val title: String, val value: String)
data class StatDef(val label: String, val value: String, val tone: Tone)
data class PanelDef(
    val type: String, val title: String, val subtitle: String, val badge: String, val tone: Tone,
    val request: ReqDef?, val stats: List<StatDef>, val rows: String, val columns: List<ColDef>, val text: String, val empty: String,
)
data class ActionDef(val id: String, val label: String, val risky: Boolean, val request: ReqDef, val message: String)
data class LimitDef(val param: String, val maxSetting: String)
data class IntentDef(val asset: String, val direction: String)
data class ToolDef(
    val name: String, val description: String, val params: List<ParamSpec>, val risky: Boolean, val requiresSignal: Boolean,
    val intent: IntentDef?, val limits: List<LimitDef>, val request: ReqDef, val response: String,
)
data class LoginDef(val fields: List<LoginField>, val request: ReqDef?, val extract: Map<String, String>, val verify: ReqDef?)

data class CustomDef(
    val id: String, val name: String, val description: String, val version: String, val accent: Long,
    val baseUrl: String, val host: String, val headers: Map<String, String>, val login: LoginDef,
    val settings: List<SettingSpec>, val panels: List<PanelDef>, val actions: List<ActionDef>, val tools: List<ToolDef>,
) {
    fun toSpec() = UiConnectorSpec(
        id = id, name = name, description = description, version = version,
        loginFields = login.fields, settings = settings,
        tools = tools.map { ToolSpec(it.name, it.description, it.params, it.risky, it.requiresSignal) },
        accent = accent,
    )
}

object CustomDefParser {
    const val MAX_CHARS = 64 * 1024
    private val METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE")
    private val ID = Regex("^[a-z][a-z0-9]{1,19}$")
    private val IDENT = Regex("^[a-z][a-zA-Z0-9_]{0,31}$")
    private val EXTRACT_SRC = Regex("^(\\$.*|header:[A-Za-z0-9-]+|cookie:[A-Za-z0-9_.-]+)$")

    data class Parsed(val def: CustomDef?, val errors: List<String>, val warnings: List<String>)

    /** Remove cercas de Markdown (```json … ```) que IAs costumam colocar em volta do JSON. */
    fun stripFences(raw: String): String {
        var t = raw.trim()
        if (t.startsWith("```")) {
            t = t.substringAfter('\n', "")
            val end = t.lastIndexOf("```")
            if (end >= 0) t = t.substring(0, end)
        }
        return t.trim()
    }

    fun parse(input: String): Parsed {
        val text = stripFences(input)
        if (text.length > MAX_CHARS) return Parsed(null, listOf("Definição maior que 64 KB."), emptyList())
        val root = try { Json.parseToJsonElement(text) } catch (t: Exception) {
            return Parsed(null, listOf("JSON inválido: ${(t.message ?: "erro de sintaxe").take(180)}"), emptyList())
        }
        val o = root as? JsonObject ?: return Parsed(null, listOf("A raiz deve ser um objeto JSON { … }."), emptyList())
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
            Template.strings(r.body).forEach { tpl(it, "$path.body", vars, false) }
        }

        fun req(o: JsonObject?, path: String): ReqDef? {
            if (o == null) return null
            known(o, path, setOf("method", "path", "query", "headers", "body"))
            val m = str(o, "method", path, def = "GET").uppercase()
            if (m !in METHODS) errors.add("$path.method '$m' inválido (GET, POST, PUT, PATCH, DELETE).")
            val p = str(o, "path", path, required = true)
            if (p.isNotEmpty() && !p.startsWith("/")) errors.add("$path.path deve começar com \"/\".")
            if (p.contains("://") ) errors.add("$path.path não pode conter URL completa; o host vem de baseUrl.")
            if (m == "GET" && o["body"] != null) warnings.add("$path.body é ignorado em GET.")
            return ReqDef(m, p, strMap(obj(o, "query", path), "$path.query"), strMap(obj(o, "headers", path), "$path.headers"), o["body"])
        }

        fun parseAccent(s: String): Long {
            if (s.isBlank()) return 0xFFFF6B3D
            val h = s.trim().removePrefix("#")
            val v = if (h.length == 6) h.toLongOrNull(16) else null
            if (v == null) { warnings.add("accent '$s' inválido (use #RRGGBB); usando o padrão."); return 0xFFFF6B3D }
            return 0xFF000000L or v
        }

        fun build(o: JsonObject): CustomDef? {
            known(o, "$", setOf("id", "name", "description", "version", "accent", "baseUrl", "headers", "login", "settings", "panels", "actions", "tools"))
            val id = str(o, "id", "$", required = true)
            if (id.isNotEmpty() && !ID.matches(id)) errors.add("$.id '$id' inválido: use [a-z0-9], começando com letra (2–20).")
            val name = str(o, "name", "$", required = true)
            val description = str(o, "description", "$", required = true)
            val version = str(o, "version", "$", def = "1.0")
            val accent = parseAccent(str(o, "accent", "$"))
            val baseUrl = str(o, "baseUrl", "$", required = true).trim().trimEnd('/')
            var host = ""
            if (baseUrl.isNotEmpty()) {
                val (h, err) = UrlGuard.baseHost(baseUrl)
                if (err != null) errors.add("$.baseUrl: $err") else host = h.orEmpty()
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

            // login
            val lo = obj(o, "login", "$", required = true)
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
            val reqVars = sessionVars + settingVars

            // painéis
            val panels = ArrayList<PanelDef>()
            arr(o, "panels", "$", 10).forEachIndexed { i, po ->
                val p = "$.panels[$i]"
                known(po, p, setOf("type", "title", "subtitle", "badge", "tone", "request", "items", "rows", "columns", "text", "empty"))
                val type = str(po, "type", p, required = true).lowercase()
                if (type !in setOf("header", "stats", "table", "note")) errors.add("$p.type '$type' inválido (header, stats, table, note).")
                val preq = req(obj(po, "request", p), "$p.request")
                tplReq(preq, "$p.request", reqVars)
                val title = str(po, "title", p)
                val subtitle = str(po, "subtitle", p)
                val badge = str(po, "badge", p)
                val text = str(po, "text", p)
                val empty = str(po, "empty", p, def = "Nada por aqui.")
                val tn = tone(str(po, "tone", p), "$p.tone")
                for ((k, v) in listOf("title" to title, "subtitle" to subtitle, "badge" to badge, "text" to text, "empty" to empty)) tpl(v, "$p.$k", reqVars, true)
                val stats = ArrayList<StatDef>()
                val cols = ArrayList<ColDef>()
                var rows = ""
                if (type == "stats") {
                    arr(po, "items", p, 20, required = true).forEachIndexed { j, so ->
                        val sp = "$p.items[$j]"
                        known(so, sp, setOf("label", "value", "tone"))
                        val label = str(so, "label", sp, required = true)
                        val value = str(so, "value", sp, required = true)
                        tpl(label, "$sp.label", reqVars, true)
                        tpl(value, "$sp.value", reqVars, true)
                        stats.add(StatDef(label, value, tone(str(so, "tone", sp), "$sp.tone")))
                    }
                }
                if (type == "table") {
                    rows = str(po, "rows", p, required = true)
                    if (rows.isNotEmpty() && !JsonPath.isValid(rows)) errors.add("$p.rows: caminho inválido '$rows' (ex.: \"\$.data[*]\").")
                    if (preq == null) errors.add("$p.request é obrigatório em tabelas.")
                    arr(po, "columns", p, 8, required = true).forEachIndexed { j, co ->
                        val cp = "$p.columns[$j]"
                        known(co, cp, setOf("title", "value"))
                        val ct = str(co, "title", cp, required = true)
                        val cv = str(co, "value", cp, required = true)
                        tpl(cv, "$cp.value", reqVars, true)
                        cols.add(ColDef(ct, cv))
                    }
                }
                panels.add(PanelDef(type, title, subtitle, badge, tn, preq, stats, rows, cols, text, empty))
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
                known(to, p, setOf("name", "description", "params", "risky", "requiresSignal", "orderIntent", "limits", "request", "response"))
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
                val vars = reqVars + paramVars
                val risky = bool(to, "risky", p, false)
                val requiresSignal = bool(to, "requiresSignal", p, false)
                val treq = req(obj(to, "request", p, required = true), "$p.request")
                tplReq(treq, "$p.request", vars)
                if (treq != null && treq.method != "GET" && !risky) errors.add("$p: ferramenta com método ${treq.method} exige \"risky\": true.")
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
                if (risky && limits.isEmpty() && !requiresSignal) warnings.add("$p: ferramenta sensível sem \"limits\" — o agente não terá teto de valor definido por você.")
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
                if (requiresSignal && intent == null) errors.add("$p: requiresSignal exige orderIntent {asset, direction}.")
                if (requiresSignal && !risky) errors.add("$p: requiresSignal exige risky = true.")
                val response = str(to, "response", p)
                tpl(response, "$p.response", vars, true)
                val tname = str(to, "name", p, required = true)
                val tdesc = str(to, "description", p, required = true)
                if (treq != null) tools.add(ToolDef(tname, tdesc, params, risky, requiresSignal, intent, limits, treq, response))
            }
            return CustomDef(id, name, description, version, accent, baseUrl, host, headers, login, settings, panels, actions, tools)
        }
    }

    /** Resolve o lado de uma ordem a partir do texto renderizado (exposto para o conector). */
    fun side(raw: String) = Sides.parse(raw)
}
