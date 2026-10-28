package dev.cueopen.app.uiconnector.custom

import dev.cueopen.app.trading.Sides
import dev.cueopen.app.uiconnector.ConnectorSettings
import dev.cueopen.app.uiconnector.OrderIntent
import dev.cueopen.app.uiconnector.Redactor
import dev.cueopen.app.uiconnector.Stat
import dev.cueopen.app.uiconnector.Tone
import dev.cueopen.app.uiconnector.UiBlock
import dev.cueopen.app.uiconnector.UiConnector
import dev.cueopen.app.uiconnector.FieldType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URLEncoder

/**
 * Conector genérico movido por uma [CustomDef] (JSON). Faz só requisições HTTPS ao host declarado,
 * com [UrlGuard]; senhas e valores extraídos no login são tratados como segredo (nunca vão ao LLM).
 */
class CustomConnector(
    val def: CustomDef,
    private val http: HttpExec = OkHttpExec(),
    private val permissive: Boolean = false,
    ws: WsExec? = null,
) : UiConnector {
    override val spec = def.toSpec()

    /** Sessão de tempo real (WebSocket) quando a definição tem o bloco "realtime". */
    val rt: RealtimeSession? = def.realtime?.let { RealtimeSession(it, def.hosts, ws ?: OkHttpWs(permissive), permissive) }

    private fun defaultSettings(): Map<String, String> = def.settings.associate { "s.${it.id}" to it.default }

    /** Conecta o tempo real se preciso. null = ok (ou sem realtime); senão o motivo. */
    private suspend fun ensureRt(base: Map<String, String>): String? {
        val r = rt ?: return null
        if (!isLoggedIn) return "entre na conta primeiro"
        return r.ensure((creds ?: emptyMap()) + extracted + base)
    }

    /** Dados de um painel/ferramenta com "source": a coleção do tempo real. */
    private suspend fun sourceJson(source: String, base: Map<String, String>): JsonElement {
        val r = rt ?: throw IllegalStateException("conector sem tempo real")
        ensureRt(base)?.let { throw IllegalStateException("tempo real indisponível: $it") }
        return r.collection(source)
    }

    // ------------------------------------------------------------------ computação (bloco "compute")
    /** Ambiente dos cálculos: s (configurações), rt (estado do tempo real) e parâmetros. NUNCA credenciais. */
    private fun computeEnv(settings: ConnectorSettings?, args: Map<String, String>, params: Set<String>): Map<String, Any?> {
        val sMap: Map<String, Any?> = def.settings.associate { st ->
            val raw = settings?.text(st.id)?.ifEmpty { null } ?: st.default
            st.id to when (st.type) {
                dev.cueopen.app.uiconnector.SettingType.NUMBER -> Compute.numOrNull(raw) ?: raw
                dev.cueopen.app.uiconnector.SettingType.SWITCH -> raw.equals("true", ignoreCase = true)
                dev.cueopen.app.uiconnector.SettingType.TEXT -> raw
            }
        }
        val rtMap: Map<String, Any?> = rt?.let { r -> r.stateSnapshot().mapValues { (_, v) -> Compute.numOrNull(v) ?: v } + mapOf("connected" to r.connected, "messages" to r.received.get().toDouble()) } ?: emptyMap()
        val env = HashMap<String, Any?>()
        env["s"] = sMap; env["rt"] = rtMap
        params.forEach { p -> env[p] = args[p]?.let { v -> Compute.numOrNull(v) ?: v } }
        return env
    }

    /** Roda os cálculos e devolve (valores, variáveis de modelo c.nome). */
    private fun runCompute(comp: List<Pair<String, Compute.Node>>, json: JsonElement?, settings: ConnectorSettings?, args: Map<String, String>, params: Set<String>): Pair<Map<String, Any?>, Map<String, String>> {
        if (comp.isEmpty()) return emptyMap<String, Any?>() to emptyMap()
        val values = Compute.runAll(comp, computeEnv(settings, args, params), json)
        return values to values.entries.associate { "c." + it.key to Compute.str(it.value) }
    }

    /** Linhas de tabela/lista: caminho na resposta ou "c.nome" (lista calculada). */
    private fun rowsOf(spec: String, json: JsonElement?, computed: Map<String, Any?>): List<JsonElement> =
        if (spec.startsWith("c.")) (computed[spec.removePrefix("c.")] as? List<*>)?.map { Compute.toJson(it) }.orEmpty()
        else json?.let { JsonPath.select(it, spec) }.orEmpty()

    /** Fecha a conexão em tempo real e libera recursos (definição trocada/removida). */
    fun dispose() { rt?.dispose() }

    private val parser = Json { isLenient = true; ignoreUnknownKeys = true }

    @Volatile private var creds: Map<String, String>? = null
    @Volatile private var extracted: Map<String, String> = emptyMap()

    override val isLoggedIn: Boolean get() = creds != null || def.login.fields.isEmpty()

    private class Reply(val code: Int, val body: String, val json: JsonElement?, val headers: Map<String, List<String>>)

    private fun sessionVars(): Map<String, String> = (creds ?: emptyMap()) + extracted + (rt?.vars() ?: emptyMap())

    private fun settingVars(st: ConnectorSettings): Map<String, String> =
        def.settings.associate { "s.${it.id}" to st.text(it.id).ifEmpty { it.default } }

    private fun buildUrl(r: ReqDef, ctx: Template.Ctx): String {
        val path = if (r.url.isNotBlank()) "" else Template.render(r.path, ctx)
        val q = r.query.entries.joinToString("&") { (k, v) ->
            URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(Template.render(v, ctx), "UTF-8")
        }
        val head = if (r.url.isNotBlank()) Template.render(r.url, ctx) else def.baseUrl + (if (path.startsWith("/")) path else "/$path")
        return head + (if (q.isEmpty()) "" else (if (head.contains('?')) "&" else "?") + q)
    }

    private suspend fun send(r: ReqDef, vars: Map<String, String>, withGlobalHeaders: Boolean, jsonCtx: JsonElement? = null): Reply {
        val ctx = Template.Ctx(vars, jsonCtx)
        val url = buildUrl(r, ctx)
        UrlGuard.check(url, def.hosts, permissive)?.let { throw IllegalStateException("Requisição bloqueada: $it") }
        val headers = LinkedHashMap<String, String>()
        if (withGlobalHeaders) def.headers.forEach { (k, v) -> headers[k] = Template.render(v, ctx) }
        r.headers.forEach { (k, v) -> headers[k] = Template.render(v, ctx) }
        headers.putIfAbsent("Accept", "application/json, application/xml, application/rss+xml, text/xml, */*")
        val isForm = r.form.isNotEmpty() && r.method != "GET"
        val body = when {
            r.method == "GET" -> null
            isForm -> r.form.entries.joinToString("&") { (k, v) -> URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(Template.render(v, ctx), "UTF-8") }
            r.body == null -> null
            else -> Template.renderJson(r.body, ctx).toString()
        }
        val ctype = if (isForm) "application/x-www-form-urlencoded" else "application/json; charset=utf-8"
        val res = http.exec(HttpReq(r.method, url, headers, body, ctype))
        val parsed = ResponseTree.parse(res.body, res.headers["content-type"]?.firstOrNull())
        return Reply(res.code, res.body, parsed, res.headers)
    }

    /** Requisição autenticada com 1 re-login automático se a sessão expirou (401). */
    private suspend fun authed(r: ReqDef, extra: Map<String, String>, withJson: JsonElement? = null): Reply {
        var reply = send(r, sessionVars() + extra, true, withJson)
        if ((reply.code == 401) && creds != null && def.login.request != null) {
            val again = login(creds!!)
            if (again.isSuccess) reply = send(r, sessionVars() + extra, true, withJson)
        }
        return reply
    }

    override suspend fun login(credentials: Map<String, String>): Result<Unit> {
        return try {
            val lr = def.login.request
            val ex = LinkedHashMap<String, String>()
            if (lr != null) {
                val reply = send(lr, credentials, false)
                if (reply.code !in 200..299) return Result.failure(IllegalStateException("Login recusado pelo servidor (HTTP ${reply.code})."))
                val hdrs = reply.headers
                for ((name, src) in def.login.extract) {
                    val v: String? = when {
                        src.startsWith("$") -> reply.json?.let { JsonPath.first(it, src) }?.let { JsonPath.text(it) }?.ifEmpty { null }
                        src.startsWith("header:") -> hdrs[src.removePrefix("header:").lowercase()]?.firstOrNull()
                        else -> {
                            val cn = src.removePrefix("cookie:")
                            hdrs["set-cookie"].orEmpty().firstNotNullOfOrNull { c ->
                                val first = c.substringBefore(';')
                                if (first.substringBefore('=').trim() == cn) first.substringAfter('=', "").trim() else null
                            }
                        }
                    }
                    if (v.isNullOrEmpty()) return Result.failure(IllegalStateException("Login sem '$name' na resposta (confira o caminho \"$src\")."))
                    ex[name] = v
                }
            }
            creds = credentials
            extracted = ex
            val ver = def.login.verify
            if (ver != null) {
                val vr = send(ver, sessionVars(), true)
                if (vr.code !in 200..299) {
                    creds = null
                    extracted = emptyMap()
                    return Result.failure(IllegalStateException("Credenciais não aceitas (verificação HTTP ${vr.code})."))
                }
            }
            if (rt != null && def.realtime?.connect == "login") {
                ensureRt(defaultSettings())?.let { err ->
                    return Result.failure(IllegalStateException("Login aceito, mas a conexão em tempo real falhou: " + Redactor.redact(err, secrets())))
                }
            }
            Result.success(Unit)
        } catch (t: kotlinx.coroutines.CancellationException) {
            throw t
        } catch (t: Throwable) {
            Result.failure(IllegalStateException(Redactor.redact(t.message ?: t.javaClass.simpleName, secrets())))
        }
    }

    override suspend fun logout() {
        rt?.close()
        creds = null
        extracted = emptyMap()
    }

    override suspend fun snapshot(settings: ConnectorSettings): List<UiBlock> {
        val out = ArrayList<UiBlock>()
        val base = settingVars(settings)
        if (rt != null && isLoggedIn) {
            val err = ensureRt(base)
            if (err != null) out.add(UiBlock.Note("Tempo real desconectado: " + Redactor.redact(err, secrets()), Tone.BAD))
        }
        for (p in def.panels) {
            try {
                val reply = if (p.request != null) authed(p.request, base)
                    else if (p.source.isNotEmpty()) Reply(200, "", sourceJson(p.source, base), emptyMap()) else null
                if (reply != null && reply.code !in 200..299) {
                    out.add(UiBlock.Note("${p.title.ifBlank { p.type }}: servidor respondeu HTTP ${reply.code}.", Tone.BAD))
                    continue
                }
                val (cvals, cvars) = runCompute(p.compute, reply?.json, settings, emptyMap(), emptySet())
                val ctx = Template.Ctx(sessionVars() + base + cvars, reply?.json)
                fun r(t: String) = Template.render(t, ctx)
                when (p.type) {
                    "header" -> out.add(UiBlock.Header(r(p.title), r(p.subtitle), r(p.badge), p.tone))
                    "note" -> out.add(UiBlock.Note(r(p.text), p.tone))
                    "stats" -> out.add(UiBlock.Stats(p.stats.map { Stat(r(it.label), r(it.value), it.tone) }))
                    "table" -> {
                        val rows = rowsOf(p.rows, reply?.json, cvals).take(100).map { row ->
                            val rc = Template.Ctx(ctx.vars, row)
                            p.columns.map { c -> Template.render(c.value, rc) }
                        }
                        out.add(UiBlock.Table(r(p.title), p.columns.map { it.title }, rows, r(p.empty)))
                    }
                }
            } catch (t: kotlinx.coroutines.CancellationException) {
                throw t
            } catch (t: Throwable) {
                out.add(UiBlock.Note("${p.title.ifBlank { p.type }}: ${Redactor.redact(t.message ?: t.javaClass.simpleName, secrets())}", Tone.BAD))
            }
        }
        def.actions.forEach { out.add(UiBlock.Button(it.id, it.label, it.risky)) }
        return out
    }

    override suspend fun onAction(actionId: String, args: Map<String, String>, settings: ConnectorSettings): Result<String> {
        val a = def.actions.firstOrNull { it.id == actionId } ?: return Result.failure(IllegalArgumentException("Ação desconhecida: $actionId"))
        return try {
            val reply = authed(a.request, settingVars(settings) + args)
            if (reply.code !in 200..299) Result.failure(IllegalStateException("HTTP ${reply.code}: ${Redactor.redact(reply.body.take(200), secrets())}"))
            else Result.success(if (a.message.isBlank()) "OK (HTTP ${reply.code})" else Template.render(a.message, Template.Ctx(sessionVars() + settingVars(settings) + args, reply.json)))
        } catch (t: kotlinx.coroutines.CancellationException) {
            throw t
        } catch (t: Throwable) {
            Result.failure(IllegalStateException(Redactor.redact(t.message ?: t.javaClass.simpleName, secrets())))
        }
    }

    /** Valida argumentos do agente contra o esquema da ferramenta. null = ok; senão o motivo. */
    private fun checkArgs(t: ToolDef, args: Map<String, String>): String? {
        for (p in t.params) {
            val v = args[p.name]
            if (v.isNullOrBlank()) {
                if (p.required) return "parâmetro '${p.name}' é obrigatório."
                continue
            }
            if (p.allowed.isNotEmpty() && v !in p.allowed) return "parâmetro '${p.name}' deve ser um de: ${p.allowed.joinToString()}."
            when (p.type) {
                "integer" -> if (v.trim().toLongOrNull() == null) return "parâmetro '${p.name}' deve ser inteiro."
                "number" -> if (v.trim().replace(',', '.').toDoubleOrNull() == null) return "parâmetro '${p.name}' deve ser numérico."
                "boolean" -> if (v.trim().lowercase() !in setOf("true", "false")) return "parâmetro '${p.name}' deve ser true/false."
            }
        }
        return null
    }

    override suspend fun callTool(name: String, args: Map<String, String>, settings: ConnectorSettings): String {
        val t = def.tools.firstOrNull { it.name == name } ?: return "Ferramenta desconhecida: $name"
        checkArgs(t, args)?.let { return "ERRO: $it" }
        for (l in t.limits) {
            val raw = args[l.param]?.trim()?.replace(',', '.') ?: continue
            val v = raw.toDoubleOrNull() ?: return "ERRO: parâmetro '${l.param}' inválido."
            if (v <= 0.0) return "RECUSADO: '${l.param}' deve ser maior que zero."
            val max = settings.num(l.maxSetting, def.settings.firstOrNull { it.id == l.maxSetting }?.default?.toDoubleOrNull() ?: 0.0)
            if (v > max) return "RECUSADO: '${l.param}' = $raw excede o limite definido pelo usuário ($max). Reduza o valor."
        }
        return try {
            val vars = settingVars(settings) + args.filterValues { it.isNotEmpty() }
            val reply = when {
                t.request != null -> authed(t.request, vars)
                t.send != null -> {
                    ensureRt(settingVars(settings))?.let { return "ERRO: tempo real indisponível: " + Redactor.redact(it, secrets()) }
                    val got = rt!!.request(t.send.message, t.send.await, sessionVars() + vars)
                    if (t.send.await == null && t.response.isBlank() && t.list == null) return "Mensagem enviada."
                    Reply(200, got.toString(), got, emptyMap())
                }
                t.source.isNotEmpty() -> Reply(200, "", sourceJson(t.source, settingVars(settings)), emptyMap())
                else -> {
                    ensureRt(settingVars(settings))?.let { return "ERRO: tempo real indisponível: " + Redactor.redact(it, secrets()) }
                    Reply(200, "", null, emptyMap())
                }
            }
            if (reply.code in 300..399) return "ERRO HTTP ${reply.code}: o servidor redirecionou para ${reply.headers["location"]?.firstOrNull() ?: "?"} — atualize o endereço na definição do conector."
            if (reply.code !in 200..299) return "ERRO HTTP ${reply.code}: ${reply.body.take(300)}"
            val (cvals, cvars) = try { runCompute(t.compute, reply.json, settings, args, t.params.map { it.name }.toSet()) }
                catch (e: Compute.ComputeError) { return "ERRO no cálculo: ${e.message}" }
            val lst = t.list
            if (lst != null) {
                val ctxL = sessionVars() + vars + cvars
                val rows = rowsOf(lst.rows, reply.json, cvals)
                if (rows.isEmpty()) return "ERRO: nenhum item em ${lst.rows} (HTTP ${reply.code}). Estrutura recebida:\n" + ResponseTree.outline(reply.json, 3).take(1500)
                val lines = rows.take(lst.max).mapIndexed { n, row -> "${n + 1}. " + Template.render(lst.item, Template.Ctx(ctxL, row)) }
                val head = if (t.response.isNotBlank()) Template.render(t.response, Template.Ctx(ctxL, reply.json)) + "\n" else ""
                return head + lines.joinToString("\n")
            }
            if (t.response.isNotBlank()) Template.render(t.response, Template.Ctx(sessionVars() + vars + cvars, reply.json)).ifBlank {
                "ERRO: o modelo de resposta ficou vazio. Estrutura recebida:\n" + ResponseTree.outline(reply.json, 3).take(1500)
            }
            else if (cvals.isNotEmpty()) cvals.entries.joinToString("\n") { "${it.key}: ${Compute.str(it.value).take(600)}" }
            else reply.body.take(4000)
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (e: Throwable) {
            "ERRO: " + Redactor.redact(e.message ?: e.javaClass.simpleName, secrets())
        }
    }

    /**
     * Teste real do conector (usado pelo criador com IA antes de liberar a instalação): busca cada painel,
     * confere se tabelas têm linhas e colunas preenchidas e executa cada ferramenta NÃO sensível.
     * Devolve (passou, relatório). Ferramentas sensíveis (risky) nunca são executadas no teste.
     */
    suspend fun selfTest(settings: ConnectorSettings, toolArgs: Map<String, Map<String, String>>): Pair<Boolean, String> {
        if (!isLoggedIn) return false to "Conector exige login: o teste real só é possível após instalar e entrar."
        val sb = StringBuilder()
        var ok = true
        val base = settingVars(settings)
        val r0 = rt
        if (r0 != null) {
            val err = ensureRt(base)
            if (err != null) return false to Redactor.redact("✗ tempo real: não conectou — $err", secrets())
            r0.settle(8_000)
            val n = r0.received.get()
            if (n == 0L) { ok = false; sb.appendLine("✗ tempo real: conectou, mas nenhuma mensagem chegou em 8 s (confira onOpen/assinaturas).") }
            else sb.appendLine("✓ tempo real: conectado, $n mensagem(ns) recebida(s)")
            val st = r0.stateSnapshot()
            for (k in def.realtime!!.stateKeys) {
                val v = st[k]
                if (v == null) { ok = false; sb.appendLine("✗ estado rt.$k: nenhuma mensagem casou com a regra (confira match/set)") }
                else sb.appendLine("✓ estado rt.$k = " + v.take(80))
            }
            for ((c, size) in r0.collectionSizes()) {
                if (size == 0) { ok = false; sb.appendLine("✗ coleção $c: vazia (confira match/push)") } else sb.appendLine("✓ coleção $c: $size item(ns)")
            }
        }
        def.panels.forEachIndexed { idx, p ->
            val label = "painel $idx (${p.type}${if (p.title.isNotBlank()) " \"${p.title.take(30)}\"" else ""})"
            val r = p.request
            if (r == null && p.source.isEmpty() && p.compute.isEmpty() && !p.stats.any { it.value.contains("rt.") }) { sb.appendLine("✓ $label: sem requisição"); return@forEachIndexed }
            try {
                val reply = when {
                    r != null -> authed(r, base)
                    p.source.isNotEmpty() -> Reply(200, "", sourceJson(p.source, base), emptyMap())
                    else -> Reply(200, "", null, emptyMap())
                }
                if (reply.code !in 200..299) {
                    ok = false; sb.appendLine("✗ $label: HTTP ${reply.code} — ${reply.body.take(200)}"); return@forEachIndexed
                }
                val (cvals, cvars) = runCompute(p.compute, reply.json, settings, emptyMap(), emptySet())
                if (cvals.isNotEmpty()) sb.appendLine("  cálculos: " + cvals.entries.joinToString("; ") { e -> e.key + "=" + Compute.str(if (e.value is List<*>) (e.value as List<*>).lastOrNull { it != null } else e.value).take(40) })
                val ctx = Template.Ctx(sessionVars() + base + cvars, reply.json)
                when (p.type) {
                    "table" -> {
                        val rows = rowsOf(p.rows, reply.json, cvals)
                        if (rows.isEmpty()) {
                            ok = false
                            sb.appendLine("✗ $label: 0 linhas em ${p.rows}. Estrutura recebida:\n" + ResponseTree.outline(reply.json, 3).take(1500))
                        } else {
                            val first = p.columns.map { c -> Template.render(c.value, Template.Ctx(ctx.vars, rows[0])) }
                            if (first.all { it.isBlank() }) { ok = false; sb.appendLine("✗ $label: ${rows.size} linhas, mas todas as colunas vieram vazias (confira os caminhos das colunas relativos a cada linha). 1ª linha:\n" + ResponseTree.outline(rows[0], 2).take(800)) }
                            else sb.appendLine("✓ $label: ${rows.size} linhas; 1ª: " + first.joinToString(" | ").take(200))
                        }
                    }
                    "stats" -> {
                        val vals = p.stats.map { Template.render(it.value, ctx) }
                        if (vals.all { it.isBlank() || it == "-" }) { ok = false; sb.appendLine("✗ $label: valores vazios. Estrutura:\n" + ResponseTree.outline(reply.json, 3).take(1200)) }
                        else sb.appendLine("✓ $label: " + vals.joinToString(" | ").take(200))
                    }
                    else -> {
                        val txt = Template.render(p.title + " " + p.text, ctx).trim()
                        if (txt.isBlank()) { ok = false; sb.appendLine("✗ $label: texto vazio. Estrutura:\n" + ResponseTree.outline(reply.json, 3).take(1200)) }
                        else sb.appendLine("✓ $label: " + txt.take(200))
                    }
                }
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                ok = false; sb.appendLine("✗ $label: ${t.message ?: t.javaClass.simpleName}")
            }
        }
        for (t in def.tools) {
            if (t.risky) { sb.appendLine("• ferramenta ${t.name}: sensível — não executada no teste"); continue }
            val args = toolArgs[t.name].orEmpty()
            val missing = t.params.filter { it.required && args[it.name].isNullOrBlank() }.map { it.name }
            if (missing.isNotEmpty()) { ok = false; sb.appendLine("✗ ferramenta ${t.name}: informe exemplos em args_json para ${missing.joinToString()}"); continue }
            val out = callTool(t.name, args, settings)
            if (out.startsWith("ERRO") || out.startsWith("RECUSAD") || out.isBlank()) { ok = false; sb.appendLine("✗ ferramenta ${t.name}: " + out.take(1500)) }
            else sb.appendLine("✓ ferramenta ${t.name}: " + out.replace("\n", " ⏎ ").take(300))
        }
        return ok to Redactor.redact(sb.toString().trim(), secrets())
    }

    override fun orderIntents(tool: String, args: Map<String, String>): List<OrderIntent> {
        val t = def.tools.firstOrNull { it.name == tool } ?: return emptyList()
        val i = t.intent ?: return emptyList()
        if (checkArgs(t, args) != null) return emptyList()
        val ctx = Template.Ctx(args)
        val asset = Template.render(i.asset, ctx).trim()
        val side = Sides.parse(Template.render(i.direction, ctx)) ?: return emptyList()
        return if (asset.isEmpty()) emptyList() else listOf(OrderIntent(asset, side))
    }

    override fun secrets(): List<String> {
        val c = creds ?: return emptyList()
        val pw = def.login.fields.filter { it.type == FieldType.PASSWORD }.mapNotNull { c[it.id] }
        return (pw + extracted.values).filter { it.length >= 4 }
    }
}
