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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.net.URLEncoder

/**
 * Conector genérico movido por uma [CustomDef] (JSON). Faz só requisições HTTPS ao host declarado,
 * com [UrlGuard]; senhas e valores extraídos no login são tratados como segredo (nunca vão ao LLM).
 */
class CustomConnector(
    val def: CustomDef,
    private val http: HttpExec = OkHttpExec(clientProvider = { base ->
        dev.cueopen.app.CueApp.instance.proxyHttpClient(dev.cueopen.app.proxy.ProxyScope.AGENT_API, base)
    }),
    private val permissive: Boolean = false,
    ws: WsExec? = null,
    /** Ponte para terminal/arquivos/browser (capacidades do aparelho). Padrão: nega tudo. */
    private val bridge: HostBridge = NoHostBridge,
) : UiConnector {
    override val spec = def.toSpec()

    /** Sessão de tempo real (WebSocket) quando a definição tem o bloco "realtime". */
    val rt: RealtimeSession? = def.realtime?.let {
        val socket = ws ?: OkHttpWs(permissive) { base ->
            dev.cueopen.app.CueApp.instance.proxyHttpClient(dev.cueopen.app.proxy.ProxyScope.AGENT_API, base)
        }
        RealtimeSession(it, def.hosts, socket, permissive, def.id)
    }
    override val stateChanges: Flow<Unit> get() = rt?.updates ?: emptyFlow()
    override val supportsPushUpdates: Boolean get() = rt != null
    override val fallbackRefreshMs: Long? get() = if (rt == null || def.panels.any { it.request != null }) refreshMs() else null

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

    override fun refreshMs(): Long = if (rt != null && def.panels.none { it.request != null }) 1_500 else 5_000

    /** Fecha a conexão em tempo real e libera recursos (definição trocada/removida). */
    fun dispose() { rt?.dispose() }

    private val parser = Json { isLenient = true; ignoreUnknownKeys = true }

    @Volatile private var creds: Map<String, String>? = null
    @Volatile private var extracted: Map<String, String> = emptyMap()

    override val isLoggedIn: Boolean get() = creds != null || def.login.fields.isEmpty()

    private fun debugLog() = runCatching { dev.cueopen.app.CueApp.instance.connectorDebugLog }.getOrNull()

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
        val debug = debugLog()
        debug?.rememberSecrets(def.id, secrets())
        val started = System.nanoTime()
        val res = try {
            http.exec(HttpReq(r.method, url, headers, body, ctype))
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            debug?.recordFailure(def.id, "http", t, url, r.method, (System.nanoTime() - started) / 1_000_000L)
            throw t
        }
        debug?.recordHttp(def.id, r.method, url, headers.keys, (System.nanoTime() - started) / 1_000_000L, res.code, "resposta recebida; corpo omitido")
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
            debugLog()?.rememberSecrets(def.id, credentials.values)
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

    /** Mensagem padrão quando o usuário ainda não autorizou uma capacidade do conector. */
    private fun capDenied(cap: String): String =
        "RECUSADO: o conector \"${def.name}\" precisa da sua autorização para usar ${Capabilities.label(cap)}. " +
            "Ative em Conectores → ${def.name} → Permissões."

    override suspend fun callTool(name: String, args: Map<String, String>, settings: ConnectorSettings): String =
        callTool(name, args, settings, delegatedModel = null)

    /** Agent-specific model is passed by Hub; direct editor/tests retain the app-default model. */
    suspend fun callTool(name: String, args: Map<String, String>, settings: ConnectorSettings, delegatedModel: String?): String {
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
            // passos de capacidade do aparelho (terminal/arquivos/browser): autorizados pelo usuário e
            // executados no sandbox/workspace isolado do conector. Em falha, devolve o erro direto.
            val stepReply: Reply? = when {
                t.shell != null -> {
                    if (!bridge.granted(Capabilities.TERMINAL)) return capDenied(Capabilities.TERMINAL)
                    // Prepara o ambiente uma vez (ex.: instalar yt-dlp) antes do 1º comando.
                    def.setup?.let { su ->
                        val setupCmd = Template.renderShell(su.run, Template.Ctx(settingVars(settings)))
                        bridge.ensureSetup(setupCmd, su.timeoutSec)?.let { sr ->
                            if (!sr.ok) return "ERRO ao preparar o conector (setup): " + Redactor.redact(sr.text.take(2000), secrets())
                        }
                    }
                    // Grava os arquivos declarados (sem heredoc — à prova de erro) antes de rodar o comando.
                    val fctx = Template.Ctx(sessionVars() + vars)
                    for (fw in t.shell.files) {
                        val wp = Template.render(fw.path, fctx)
                        val wr = bridge.fileOp("write", wp, Template.render(fw.content, fctx))
                        if (!wr.ok) return "ERRO ao criar o arquivo '$wp': " + Redactor.redact(wr.text.take(800), secrets())
                    }
                    val cmd = Template.renderShell(t.shell.run, Template.Ctx(sessionVars() + vars))
                    var r = bridge.shell(cmd, t.shell.timeoutSec)
                    // Auto-reparo: o comando sumiu do ambiente (pacote removido, setup antigo, ambiente
                    // recriado) → refaz o setup uma vez e tenta de novo, em vez de falhar em produção.
                    if (!r.ok && def.setup != null && (r.text.contains("not found") || r.text.contains("[exit 127]") || r.text.contains("ModuleNotFoundError"))) {
                        bridge.resetSetup()
                        val su = def.setup
                        val again = bridge.ensureSetup(Template.renderShell(su.run, Template.Ctx(settingVars(settings))), su.timeoutSec)
                        if (again == null || again.ok) r = bridge.shell(cmd, t.shell.timeoutSec)
                    }
                    lastStepRaw = r.text
                    if (!r.ok) return "ERRO: " + Redactor.redact(r.text.take(2000), secrets())
                    Reply(200, r.text, ResponseTree.parse(r.text, null), emptyMap())
                }
                t.file != null -> {
                    if (!bridge.granted(Capabilities.FILES)) return capDenied(Capabilities.FILES)
                    val ctxF = Template.Ctx(sessionVars() + vars)
                    val fpath = Template.render(t.file.path, ctxF)
                    val content = t.file.content?.let { Template.render(it, ctxF) }
                    val r = if (t.file.op == "replace") {
                        bridge.replaceFile(
                            fpath,
                            Template.render(t.file.oldText.orEmpty(), ctxF),
                            Template.render(t.file.newText.orEmpty(), ctxF),
                            t.file.allowMultiple,
                        )
                    } else bridge.fileOp(t.file.op, fpath, content)
                    lastStepRaw = r.text
                    if (!r.ok) return "ERRO: " + Redactor.redact(r.text.take(2000), secrets())
                    Reply(200, r.text, ResponseTree.parse(r.text, null), emptyMap())
                }
                t.fetch != null -> {
                    if (!bridge.granted(Capabilities.BROWSER)) return capDenied(Capabilities.BROWSER)
                    val url = Template.render(t.fetch.url, Template.Ctx(sessionVars() + vars))
                    val r = bridge.fetch(url, t.fetch.waitSec)
                    lastStepRaw = r.text
                    if (!r.ok) return "ERRO: " + Redactor.redact(r.text.take(2000), secrets())
                    Reply(200, r.text, ResponseTree.parse(r.text, null), emptyMap())
                }
                t.agent != null -> {
                    if (!bridge.granted(Capabilities.AGENT)) return capDenied(Capabilities.AGENT)
                    // Só os parâmetros declarados para esta ferramenta chegam ao subagente. Credenciais,
                    // settings e variáveis de sessão nunca são expandidas no prompt delegado.
                    val taskArgs = t.params.associate { it.name to args[it.name].orEmpty() }
                    val task = Template.render(t.agent.task, Template.Ctx(taskArgs))
                    val r = bridge.runAgentTask(task, delegatedModel, t.agent.maxSteps, t.agent.timeoutSec)
                    lastStepRaw = r.text
                    if (!r.ok) return "ERRO: " + Redactor.redact(r.text.take(2_000), secrets())
                    Reply(200, r.text, ResponseTree.parse(r.text, null), emptyMap())
                }
                else -> null
            }
            val reply = stepReply ?: when {
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
                if (rows.isEmpty()) {
                    // Lista que EXISTE mas veio vazia é um resultado válido ("nenhum par/registro"), não um erro
                    // do conector — antes virava "ERRO: nenhum item em $[*]" e o agente achava que quebrou.
                    val parentPath = lst.rows.removeSuffix("[*]")
                    val parent = if (lst.rows.startsWith("c.")) null else reply.json?.let { j -> if (parentPath == "$") j else JsonPath.first(j, parentPath) }
                    if (parent is kotlinx.serialization.json.JsonArray && parent.isEmpty())
                        return NO_RESULTS + " (a resposta do servidor veio com a lista vazia, HTTP ${reply.code}). Tente outros parâmetros."
                    return "ERRO: nenhum item em ${lst.rows} (HTTP ${reply.code}). Estrutura recebida:\n" + ResponseTree.outline(reply.json, 3).take(1500)
                }
                val lines = rows.take(lst.max).mapIndexed { n, row -> "${n + 1}. " + Template.render(lst.item, Template.Ctx(ctxL, row)) }
                val head = if (t.response.isNotBlank()) Template.render(t.response, Template.Ctx(ctxL, reply.json)) + "\n" else ""
                return head + lines.joinToString("\n")
            }
            val output = if (t.response.isNotBlank()) Template.render(t.response, Template.Ctx(sessionVars() + vars + cvars, reply.json)).ifBlank {
                "ERRO: o modelo de resposta ficou vazio. Estrutura recebida:\n" + ResponseTree.outline(reply.json, 3).take(1500)
            }
            else if (cvals.isNotEmpty()) cvals.entries.joinToString("\n") { "${it.key}: ${Compute.str(it.value).take(600)}" }
            else reply.body.take(4000)
            val policy = t.vslOutputPolicy
            if (policy != null) {
                val result = VslOutputGuardrails.validate(output, args, policy)
                if (!result.passed) return "ERRO: saída VSL não passou nas guardrails:\n" + result.errors.joinToString("\n")
            }
            output
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (e: Throwable) {
            "ERRO: " + Redactor.redact(e.message ?: e.javaClass.simpleName, secrets())
        }
    }

    companion object { const val NO_RESULTS = "Nenhum resultado" }

    /** Saída CRUA do último passo de terminal/arquivo/browser (para o teste achar falhas escondidas). */
    @Volatile private var lastStepRaw: String? = null

    /**
     * Harness de integração usado pelos testes que injetam HttpExec/HostBridge. Pode fazer I/O real se receber
     * implementações de produção e, portanto, NÃO é chamado pelo criador com IA nem é prova pré-instalação.
     * O builder usa [ConnectorDryRunValidator], exclusivamente com mocks offline. Devolve (passou, relatório).
     */
    suspend fun selfTest(settings: ConnectorSettings, toolArgs: Map<String, Map<String, String>>): Pair<Boolean, String> {
        val sb = StringBuilder()
        var ok = true
        val base = settingVars(settings)
        fun fail(line: String) { ok = false; sb.appendLine("✗ $line") }

        // ---------------------------------------------------------- 1. guardrails estáticos
        val findings = ConnectorGuardrails.staticCheck(def)
        val errs = findings.filter { it.level == ConnectorGuardrails.Level.ERROR }
        val warns = findings.filter { it.level == ConnectorGuardrails.Level.WARN }
        if (errs.isNotEmpty()) {
            ok = false
            sb.appendLine("GUARDRAILS — ${errs.size} erro(s) que fariam o conector falhar no agente:")
            errs.forEach { sb.appendLine(it.toString()) }
        } else sb.appendLine("✓ guardrails estáticos: comandos, scripts e arquivos sem defeitos conhecidos")

        // ---------------------------------------------------------- 2–3. ambiente, setup, comandos e sintaxe
        val shellTools = def.tools.filter { it.shell != null }
        val needsTerminal = shellTools.isNotEmpty() || def.setup != null
        var terminalReady = !needsTerminal
        if (needsTerminal) terminalReady = validateTerminal(settings, toolArgs, sb) { ok = false }
        if (def.tools.any { it.file != null }) {
            if (!bridge.granted(Capabilities.FILES)) fail("arquivos: o teste de validação não tem acesso ao workspace.")
            else {
                val w = bridge.fileOp("write", ".cue-validate/probe.txt", "ok")
                val r = if (w.ok) bridge.fileOp("read", ".cue-validate/probe.txt", null) else w
                bridge.fileOp("delete", ".cue-validate", null)
                if (!w.ok || !r.ok || r.text.trim() != "ok") fail("arquivos: workspace do conector não aceitou gravar/ler — ${(if (!w.ok) w else r).text.take(300)}")
                else sb.appendLine("✓ arquivos: workspace grava e lê")
            }
        }

        // ---------------------------------------------------------- 5a. tempo real e painéis (precisam de sessão)
        val r0 = rt
        if (!isLoggedIn) {
            sb.appendLine("⏳ painéis, tempo real e ferramentas HTTP: exigem login — serão testados depois que o usuário entrar.")
        } else {
            if (r0 != null) {
                val err = ensureRt(base)
                if (err != null) fail("tempo real: não conectou — $err")
                else {
                    r0.settle(8_000)
                    val n = r0.received.get()
                    if (n == 0L) fail("tempo real: conectou, mas nenhuma mensagem chegou em 8 s (confira onOpen/assinaturas).")
                    else sb.appendLine("✓ tempo real: conectado, $n mensagem(ns) recebida(s)")
                    val st = r0.stateSnapshot()
                    for (k in def.realtime!!.stateKeys) {
                        val v = st[k]
                        if (v == null) fail("estado rt.$k: nenhuma mensagem casou com a regra (confira match/set)")
                        else sb.appendLine("✓ estado rt.$k = " + v.take(80))
                    }
                    for ((c, size) in r0.collectionSizes()) {
                        if (size == 0) fail("coleção $c: vazia (confira match/push)") else sb.appendLine("✓ coleção $c: $size item(ns)")
                    }
                }
            }
            testPanels(settings, base, sb) { ok = false }
        }

        // ---------------------------------------------------------- 4–5b. ferramentas
        for (t in def.tools) {
            val cap = t.shell != null || t.file != null || t.fetch != null
            if (!ConnectorGuardrails.executableInValidation(t)) {
                sb.appendLine("• ferramenta ${t.name}: " + (if (cap) "efeito externo (envio/ordem) — não executada; comandos e sintaxe já validados acima" else "sensível — não executada no teste"))
                continue
            }
            if (!cap && !isLoggedIn) { sb.appendLine("⏳ ferramenta ${t.name}: HTTP com login — testada após entrar"); continue }
            if (t.shell != null && !terminalReady) { fail("ferramenta ${t.name}: não executada porque o terminal/setup falhou (veja acima)"); continue }
            val args = toolArgs[t.name].orEmpty()
            val missing = t.params.filter { it.required && args[it.name].isNullOrBlank() }.map { it.name }
            if (missing.isNotEmpty()) { fail("ferramenta ${t.name}: informe exemplos REAIS em args_yaml para ${missing.joinToString()} — sem isso não dá para provar que ela funciona"); continue }
            val runs = if (t.shell != null || t.file?.op == "write") 2 else 1
            var passedAll = true
            for (n in 1..runs) {
                lastStepRaw = null
                val t0 = System.currentTimeMillis()
                val out = callTool(t.name, args, settings)
                val secs = (System.currentTimeMillis() - t0) / 1000
                val raw = lastStepRaw.orEmpty()
                val tag = if (n == 1) "" else " (2ª execução — idempotência)"
                val hidden = if (t.shell != null) ConnectorGuardrails.hiddenFailure(raw) else null
                when {
                    out.startsWith("ERRO") || out.startsWith("RECUSAD") -> { passedAll = false; fail("ferramenta ${t.name}$tag: " + out.take(1500) + hint(out)) }
                    out.isBlank() -> { passedAll = false; fail("ferramenta ${t.name}$tag: resposta vazia") }
                    out.startsWith(NO_RESULTS) -> { passedAll = false; fail("ferramenta ${t.name}$tag: os exemplos de args_yaml não trouxeram nenhum item — use exemplos REAIS que retornem dados, para provar que o modelo de cada item funciona") }
                    hidden != null -> { passedAll = false; fail("ferramenta ${t.name}$tag: terminou, mas a saída mostra falha: \"$hidden\"" + hint(raw) + "\nSaída:\n" + raw.takeLast(1200)) }
                    else -> if (n == 1) sb.appendLine("✓ ferramenta ${t.name} (${secs}s): " + out.replace("\n", " ⏎ ").take(300))
                        else sb.appendLine("✓ ferramenta ${t.name}: 2ª execução também passou")
                }
                if (!passedAll) break
                if (n == 1 && runs == 2 && secs > 45) { sb.appendLine("• ferramenta ${t.name}: lenta (${secs}s) — 2ª execução dispensada"); break }
            }
        }
        if (warns.isNotEmpty()) {
            sb.appendLine("Avisos (não bloqueiam; corrija se fizer sentido):")
            warns.forEach { sb.appendLine(it.toString()) }
        }
        return ok to Redactor.redact(sb.toString().trim(), secrets())
    }

    /** Dica objetiva para as falhas mais comuns de terminal. */
    private fun hint(text: String): String = when {
        text.contains("not found") -> "\n→ comando inexistente no sandbox: instale no \"setup\" (apk add --no-cache <pacote> ou pip install --break-system-packages <pacote>)."
        text.contains("No such file") -> "\n→ arquivo/pasta inexistente: crie com \"files\" (ou mkdir -p) e use caminhos relativos ao workspace."
        Regex("No module named ['\"]yaml['\"]").containsMatchIn(text) -> "\n→ para `import yaml`, o pacote Alpine é `py3-yaml`: instale no setup com `apk add --no-cache py3-yaml` e valide o import antes de repetir o comando."
        text.contains("ModuleNotFoundError") || text.contains("ImportError") -> "\n→ módulo Python ausente: instale no \"setup\" com pip install --break-system-packages <módulo>."
        text.contains("SyntaxError") -> "\n→ erro de sintaxe no script gravado em \"files\": corrija o conteúdo (\\n para quebras de linha)."
        text.contains("timeout") -> "\n→ passou do tempo-limite: aumente shell.timeoutSec ou reduza o trabalho."
        text.contains("externally-managed") -> "\n→ use pip install --break-system-packages."
        text.contains("Permission denied") -> "\n→ sem permissão: rode scripts com o interpretador (bash x.sh / python3 x.py) em vez de ./x."
        else -> ""
    }

    /** Fase de terminal da validação. Devolve true se o terminal (e o setup) estão prontos para as ferramentas. */
    private suspend fun validateTerminal(settings: ConnectorSettings, toolArgs: Map<String, Map<String, String>>, sb: StringBuilder, onFail: () -> Unit): Boolean {
        fun fail(line: String) { onFail(); sb.appendLine("✗ $line") }
        if (!bridge.granted(Capabilities.TERMINAL)) { fail("terminal: o teste de validação não tem acesso ao sandbox."); return false }
        val probe = bridge.shell("echo cue-validate-ok", 30)
        if (!probe.ok || !probe.text.contains("cue-validate-ok")) { fail("terminal: sandbox indisponível — ${probe.text.take(400)}"); return false }
        sb.appendLine("✓ terminal: sandbox isolado de validação respondeu")
        def.setup?.let { su ->
            val cmd = Template.renderShell(su.run, Template.Ctx(settingVars(settings)))
            val r = bridge.ensureSetup(cmd, su.timeoutSec)
            when {
                r == null -> sb.appendLine("✓ setup: já preparado com este mesmo comando")
                !r.ok -> { fail("setup falhou — ${r.text.takeLast(1500)}" + hint(r.text)); return false }
                else -> sb.appendLine("✓ setup: rodou com sucesso" + (r.text.trim().takeIf { it.isNotEmpty() }?.let { " (" + it.lines().last().take(80) + ")" } ?: ""))
            }
        }
        // comandos externos usados pelas ferramentas e pelos scripts precisam existir APÓS o setup
        val needed = LinkedHashSet<String>()
        def.tools.mapNotNull { it.shell }.forEach { sh ->
            needed += ConnectorGuardrails.externalCommands(sh.run)
            sh.files.forEach { f -> ConnectorGuardrails.syntaxInterpreter(f.path)?.let { needed += it } }
        }
        var ready = true
        if (needed.isNotEmpty()) {
            val check = "for c in " + needed.joinToString(" ") { Template.shellQuote(it) } + "; do command -v \"\$c\" >/dev/null 2>&1 || echo \"FALTA:\$c\"; done; echo fim"
            val r = bridge.shell(check, 30)
            val missing = r.text.lines().filter { it.startsWith("FALTA:") }.map { it.removePrefix("FALTA:").trim() }
            if (!r.ok) { fail("terminal: não consegui conferir os comandos — ${r.text.take(300)}"); ready = false }
            else if (missing.isNotEmpty()) {
                fail("comando(s) inexistente(s) no sandbox: ${missing.joinToString()}. Instale no \"setup\" (Alpine: apk add --no-cache ${missing.joinToString(" ")}; Python: pip install --break-system-packages …) e confira no fim do setup com command -v.")
                ready = false
            } else sb.appendLine("✓ comandos disponíveis: ${needed.joinToString()}")
        }
        // sintaxe: scripts gravados por files e o próprio run (com os valores de exemplo)
        for (t in def.tools) {
            val sh = t.shell ?: continue
            val args = toolArgs[t.name].orEmpty()
            val vars = settingVars(settings) + t.params.associate { p -> p.name to (args[p.name]?.takeIf { it.isNotEmpty() } ?: p.allowed.firstOrNull() ?: "exemplo") }
            val ctx = Template.Ctx(sessionVars() + vars)
            for (fw in sh.files) {
                val path = Template.render(fw.path, ctx)
                val w = bridge.fileOp("write", path, Template.render(fw.content, ctx))
                if (!w.ok) { fail("ferramenta ${t.name}: não consegui gravar '$path' — ${w.text.take(300)}"); ready = false; continue }
                val chk = ConnectorGuardrails.syntaxCheck(path) ?: continue
                val interp = ConnectorGuardrails.syntaxInterpreter(path)
                if (interp != null && interp in needed && !ready) continue
                val r = bridge.shell(chk, 30)
                if (!r.ok) { fail("ferramenta ${t.name}: erro de sintaxe em '$path' — ${r.text.take(800)}"); ready = false }
                else sb.appendLine("✓ ferramenta ${t.name}: sintaxe de '$path' ok")
            }
            val cmd = Template.renderShell(sh.run, ctx)
            val r = bridge.shell("bash -n -c " + Template.shellQuote(cmd), 15)
            if (!r.ok) { fail("ferramenta ${t.name}: o comando \"run\" tem erro de sintaxe do shell — ${r.text.take(600)}"); ready = false }
        }
        return ready
    }

    /** Fase de painéis do teste (dados reais). */
    private suspend fun testPanels(settings: ConnectorSettings, base: Map<String, String>, sb: StringBuilder, onFail: () -> Unit) {
        fun fail(line: String) { onFail(); sb.appendLine("✗ $line") }
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
                if (reply.code !in 200..299) { fail("$label: HTTP ${reply.code} — ${reply.body.take(200)}"); return@forEachIndexed }
                val (cvals, cvars) = runCompute(p.compute, reply.json, settings, emptyMap(), emptySet())
                if (cvals.isNotEmpty()) sb.appendLine("  cálculos: " + cvals.entries.joinToString("; ") { e -> e.key + "=" + Compute.str(if (e.value is List<*>) (e.value as List<*>).lastOrNull { it != null } else e.value).take(40) })
                val ctx = Template.Ctx(sessionVars() + base + cvars, reply.json)
                when (p.type) {
                    "table" -> {
                        val rows = rowsOf(p.rows, reply.json, cvals)
                        if (rows.isEmpty()) fail("$label: 0 linhas em ${p.rows}. Estrutura recebida:\n" + ResponseTree.outline(reply.json, 3).take(1500))
                        else {
                            val first = p.columns.map { c -> Template.render(c.value, Template.Ctx(ctx.vars, rows[0])) }
                            if (first.all { it.isBlank() }) fail("$label: ${rows.size} linhas, mas todas as colunas vieram vazias (confira os caminhos das colunas relativos a cada linha). 1ª linha:\n" + ResponseTree.outline(rows[0], 2).take(800))
                            else sb.appendLine("✓ $label: ${rows.size} linhas; 1ª: " + first.joinToString(" | ").take(200))
                        }
                    }
                    "stats" -> {
                        val vals = p.stats.map { Template.render(it.value, ctx) }
                        if (vals.all { it.isBlank() || it == "-" }) fail("$label: valores vazios. Estrutura:\n" + ResponseTree.outline(reply.json, 3).take(1200))
                        else sb.appendLine("✓ $label: " + vals.joinToString(" | ").take(200))
                    }
                    else -> {
                        val txt = Template.render(p.title + " " + p.text, ctx).trim()
                        if (txt.isBlank()) fail("$label: texto vazio. Estrutura:\n" + ResponseTree.outline(reply.json, 3).take(1200))
                        else sb.appendLine("✓ $label: " + txt.take(200))
                    }
                }
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                fail("$label: ${t.message ?: t.javaClass.simpleName}")
            }
        }
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
